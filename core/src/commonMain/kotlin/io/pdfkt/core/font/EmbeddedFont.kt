/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.font

import io.pdfkt.core.filter.flateStream
import io.pdfkt.core.font.cff.CffFont
import io.pdfkt.core.font.cff.CffSubset
import io.pdfkt.core.font.truetype.Glyph
import io.pdfkt.core.font.truetype.PostTable
import io.pdfkt.core.font.truetype.TrueTypeFont
import io.pdfkt.core.font.truetype.TrueTypeSubset
import io.pdfkt.core.obj.PdfArray
import io.pdfkt.core.obj.PdfDict
import io.pdfkt.core.obj.PdfInt
import io.pdfkt.core.obj.PdfName
import io.pdfkt.core.obj.PdfObject
import io.pdfkt.core.obj.PdfReal
import io.pdfkt.core.obj.PdfRef
import io.pdfkt.core.obj.PdfString

// ISO 32000-2:2020 §9.7 "Composite fonts" (Tables 114, 115, 119),
// §9.8 "Font descriptors" (Tables 120, 121), §9.9 "Embedded font programs"
// (Tables 124, 125), §9.9.2 "Font subsets", §9.10.3 "ToUnicode CMaps",
// §9.2.4 "Glyph positioning and metrics"

/**
 * The embedded font path, whole: a font program the caller
 * supplied, measured from its own tables, shown by glyph index, and written into
 * the file as the object chain §9.7 defines.
 *
 * ```
 * /Font << /F1 → Type 0 font (Table 119)
 *                  /Encoding /Identity-H
 *                  /DescendantFonts [ CIDFont (Table 115)
 *                                       /Subtype /CIDFontType2
 *                                       /CIDToGIDMap → stream
 *                                       /FontDescriptor → descriptor (Table 120)
 *                                                           /FontFile2 → the subset ]
 *                  /ToUnicode → CMap (§9.10.3)
 * ```
 *
 * One path for every supplied font, whatever its text: "A `Supplied` font never
 * goes through a byte encoding even for pure-ASCII text — one code path that
 * always works beats two that must agree".
 *
 * ## What a character code is here
 *
 * `Identity-H` "maps 2-byte character codes ranging from 0 to 65,535 to the same
 * 2-byte CID value" (§9.7.5.2), so choosing the codes a page shows is choosing
 * the CIDs. This library uses **the glyph index the character has in the font the
 * caller supplied**.
 *
 * The alternative is numbering the CIDs as glyphs are first drawn, which yields a
 * smaller `CIDToGIDMap`; it also makes the bytes of a page depend on which pages
 * were drawn before it. Glyph indices of the supplied program do not: [encode] is
 * a function of the text and the font program alone, so a page can be encoded
 * before the document knows what else it will contain — which is exactly the
 * position an emitter is in, since the subset cannot be cut until every page has
 * been drawn.
 *
 * The two numberings then have to be reconciled, because a subset renumbers its
 * glyphs (`loca` and `hmtx` have one entry per glyph, so keeping the original
 * numbering would keep arrays the size of the whole font). That is what the
 * `CIDToGIDMap` entry of Table 115 is for — "the glyph index for a particular CID
 * value c shall be a 2-byte value stored in bytes 2 × c and 2 × c + 1" — and
 * writing it as a stream rather than `/Identity` is the price of the property
 * above.
 *
 * ## Order of use
 *
 * Draw first, write afterwards. [encode] collects the glyphs a page shows;
 * [fontDictionary] cuts the subset from what was collected. A glyph offered after
 * the dictionary was written is refused rather than dropped: it has no entry in
 * the `CIDToGIDMap` that was already written, so the page would show `.notdef`
 * with every check still green — the silent loss this library forbids.
 *
 * The font program is parsed on first use, not on construction. A [FontCatalog]
 * resolves references eagerly, and a document that names a font it never draws
 * with should not pay for parsing several megabytes of it.
 */
internal class EmbeddedFont(private val ref: FontRef.Supplied) : WrittenFont {
    private val program: TrueTypeFont by lazy(LazyThreadSafetyMode.NONE) { TrueTypeFont.parse(ref.bytes, ref.index) }

    /**
     * The `post` table, if the program has one. Table 124 does not require it of
     * an embedded program and this library does not write one, but a program a
     * caller supplies is a whole font file and usually carries it — see
     * [PostTable] for the two descriptor entries that have no other source.
     */
    private val post: PostTable? by lazy(LazyThreadSafetyMode.NONE) {
        program.directory.readerOrNull("post")?.let { PostTable.parse(it) }
    }

    /**
     * Every character code the document has shown, against the text it stands
     * for: the CIDs of the `W` array and the `CIDToGIDMap`, and the mappings of
     * the `ToUnicode` CMap.
     *
     * Keyed by glyph index — see the class documentation — and in first-use
     * order, which nothing downstream depends on: the subset sorts, and so does
     * everything written from this map.
     */
    private val shownGlyphs: MutableMap<Int, Int> = LinkedHashMap()

    /**
     * The program's CFF or CFF2 outlines, or null for a font with TrueType
     * outlines. "OpenType fonts may have TrueType or CFF outlines", and which
     * one decides the CIDFont's subtype and the stream its program is in.
     */
    private val compactOutlines: CffFont? by lazy(LazyThreadSafetyMode.NONE) {
        program.directory.readerOrNull("CFF ")?.let { CffFont.parseCff(it) }
            ?: program.directory.readerOrNull("CFF2")?.let { CffFont.parseCff2(it) }
    }

    /** The program written into the file, of one outline format or the other. */
    private sealed interface Subset {
        class TrueType(val subset: TrueTypeSubset) : Subset

        class Compact(val bytes: ByteArray) : Subset
    }

    /** Whether [fontDictionary] has written the subset that [shownGlyphs] fixed. */
    private var written: Boolean = false

    // ---- Metrics (§9.2.4) ------------------------------------------------

    /**
     * The advance of [text], summed over its characters and converted out of the
     * font's design units.
     *
     * @throws UnencodableCharacterException at the first character the font
     *   program has no glyph for.
     */
    override fun advanceOf(text: String): Double {
        var advance = 0.0
        forEachCodePoint(text) { index, codePoint ->
            advance += inGlyphSpace(program.advanceWidthOf(glyphIdOf(codePoint, text, index)))
        }
        return advance
    }

    override val ascent: Double get() = inGlyphSpace(program.hhea.ascender)

    override val descent: Double get() = inGlyphSpace(program.hhea.descender)

    /**
     * `hhea`'s lineGap: "Typographic line gap. Negative LineGap values are
     * treated as zero in some legacy platform implementations" — the gap left
     * between one line's descent and the next line's ascent, which is what
     * Table 120 calls `Leading`.
     */
    override val leading: Double get() = inGlyphSpace(program.hhea.lineGap)

    // ---- Showing text (§9.7.5.2) ------------------------------------------

    /**
     * The character codes [text] is shown as: two bytes per character, high-order
     * byte first, each of them the glyph index the character has in the supplied
     * program.
     *
     * "When the current font is a Type 0 font whose Encoding entry is Identity-H
     * or Identity-V, the string to be shown shall contain pairs of bytes
     * representing CIDs, high-order byte first" (§9.7.5.2).
     *
     * @throws UnencodableCharacterException at the first character the font
     *   program has no glyph for, naming it and its position.
     * @throws IllegalStateException if a character needs a glyph this font has
     *   not shown before and the font has already been written.
     */
    override fun encode(text: String): ByteArray {
        val codes = ByteArray(codePointCount(text) * BYTES_PER_CODE)
        var at = 0
        forEachCodePoint(text) { index, codePoint ->
            val glyphId = glyphIdOf(codePoint, text, index)
            show(glyphId, codePoint)
            codes[at++] = (glyphId ushr Byte.SIZE_BITS).toByte()
            codes[at++] = glyphId.toByte()
        }
        return codes
    }

    // ---- Writing the font (§9.7, §9.8, §9.9) ------------------------------

    /**
     * The Type 0 font dictionary of Table 119, and — through [define] — the
     * CIDFont, the descriptor, the subset of the program, the `CIDToGIDMap` and
     * the `ToUnicode` CMap under it.
     *
     * Called once the document has drawn everything it is going to draw: the
     * subset is cut from the glyphs [encode] collected.
     *
     * @param define makes an object indirect and hands back the reference to it.
     *   Three of the entries below have to be indirect — a font descriptor
     *   "shall be an indirect reference" (Table 115) and "all streams shall be
     *   indirect objects" (§7.3.8) — and the CIDFont is defined indirectly too so
     *   that the one-element `DescendantFonts` array is a reference rather than a
     *   dictionary a merge would have to duplicate.
     */
    override fun fontDictionary(define: (PdfObject) -> PdfRef): PdfDict {
        written = true
        val cff = compactOutlines
        val subset: Subset
        val name: PdfName
        if (cff == null) {
            val trueType = TrueTypeSubset.of(program, shownGlyphs.keys)
            name = PdfName(subsetFontName(ref.id, trueType.glyphIds))
            subset = Subset.TrueType(trueType)
        } else {
            val glyphIds = (shownGlyphs.keys + TrueTypeSubset.NOTDEF).sorted()
            name = PdfName(subsetFontName(ref.id, glyphIds))
            subset = Subset.Compact(CffSubset.of(cff, shownGlyphs.keys, name.name))
        }
        val descriptor = define(fontDescriptor(name, subset, define))
        val descendant = define(cidFont(name, subset, descriptor, define))
        val toUnicode = define(flateStream(TO_UNICODE_STREAM_DICT, toUnicodeCMap(unicodeMappings())))
        return PdfDict(
            mapOf(
                TYPE to FONT,
                SUBTYPE to TYPE_0,
                // "If the descendant is a Type 2 CIDFont, this name should be the
                // same as the CIDFont's BaseFont name."
                BASE_FONT to name,
                ENCODING to IDENTITY_H,
                // "A one-element array specifying the CIDFont dictionary that is
                // the descendant of this Type 0 font."
                DESCENDANT_FONTS to PdfArray(listOf(descendant)),
                TO_UNICODE to toUnicode,
            ),
        )
    }

    /**
     * The CIDFont dictionary of Table 115 — "Although its `Type` value is `Font`,
     * a CIDFont is not actually a font … It shall be used only as a descendant of
     * a Type 0 font."
     *
     * `CIDSystemInfo` is the `Adobe-Identity-0` collection: the CIDs of this font
     * are glyph indices and stand for no character collection of their own, which
     * is what `Identity` orders. Nothing is lost by saying so — "Identity CMaps
     * (Identity-H and Identity-V) are compatible with all CIDFonts" (§9.7.3).
     *
     * `DW` is left out: its default is 1000, and every CID a page can show is in
     * `W`, since `W` is written from the glyphs the pages showed.
     */
    private fun cidFont(name: PdfName, subset: Subset, descriptor: PdfRef, define: (PdfObject) -> PdfRef): PdfDict =
        PdfDict(
            buildMap {
                put(TYPE, FONT)
                put(SUBTYPE, if (subset is Subset.TrueType) CID_FONT_TYPE_2 else CID_FONT_TYPE_0)
                put(BASE_FONT, name)
                put(CID_SYSTEM_INFO, ADOBE_IDENTITY_0)
                put(FONT_DESCRIPTOR, descriptor)
                put(WIDTHS, widths())
                // "CIDToGIDMap … (Optional; Type 2 CIDFonts only)": a Type 0
                // CIDFont maps its CIDs through the charset of its program, which
                // [CffSubset] writes with the CIDs this font shows.
                if (subset is Subset.TrueType) {
                    put(CID_TO_GID_MAP, define(flateStream(PdfDict(emptyMap()), cidToGidMap(subset.subset))))
                }
            },
        )

    /**
     * The font descriptor of Table 120, for the CIDFont: "a font descriptor
     * describing the CIDFont's default metrics other than its glyph widths".
     *
     * Every entry the table marks Required is written, from the font's own
     * tables, converted to glyph space:
     *
     * - `FontBBox` is `head`'s bounding box, "the smallest rectangle enclosing
     *   the shape that would result if all of the glyphs of the font were placed
     *   with their origins coincident and then filled" — which is what `head`
     *   records.
     * - `Ascent` and `Descent` are `hhea`'s.
     * - `ItalicAngle` is `post`'s, or 0 for a font that carries no `post` table
     *   and so declares no slope.
     * - `StemV` is 0, which Table 120 defines as a value rather than as a gap:
     *   "A value of 0 indicates an unknown stem thickness." No table of a
     *   TrueType program states the dominant stem width, and a guess would be a
     *   number the file asserts and nothing supports.
     * - `CapHeight` is "Required for fonts that have Latin characters", and is
     *   written only for fonts that have one: it is the top of the font's `H`,
     *   "the vertical coordinate of the top of flat capital letters, measured
     *   from the baseline", read off that glyph's bounding box.
     *
     * The `FontFile2` stream carries the subset and declares `Length1`, "the
     * length in bytes of … the entire TrueType font program, after it has been
     * decoded using the filters specified by the stream's Filter entry"
     * (Table 125).
     */
    private fun fontDescriptor(name: PdfName, subset: Subset, define: (PdfObject) -> PdfRef): PdfDict {
        val fontFile = define(
            when (subset) {
                is Subset.TrueType -> flateStream(
                    PdfDict(mapOf(LENGTH_1 to PdfInt(subset.subset.bytes.size.toLong()))),
                    subset.subset.bytes,
                )
                // Table 125: FontFile3 with "CIDFontType0C — Type 0 CIDFont
                // program represented in the Compact Font Format".
                is Subset.Compact -> flateStream(PdfDict(mapOf(SUBTYPE to CID_FONT_TYPE_0C)), subset.bytes)
            },
        )
        return PdfDict(
            buildMap {
                put(TYPE, FONT_DESCRIPTOR)
                // "This name shall be the same as the value of BaseFont in the
                // font or CIDFont dictionary that refers to this font descriptor."
                put(FONT_NAME, name)
                put(FLAGS, PdfInt(flags().toLong()))
                put(
                    FONT_BBOX,
                    PdfArray(
                        listOf(program.head.xMin, program.head.yMin, program.head.xMax, program.head.yMax)
                            .map { PdfReal(inGlyphSpace(it)) },
                    ),
                )
                put(ITALIC_ANGLE, PdfReal(post?.italicAngle ?: UPRIGHT))
                put(ASCENT, PdfReal(ascent))
                put(DESCENT, PdfReal(descent))
                capHeight()?.let { put(CAP_HEIGHT, PdfReal(it)) }
                put(STEM_V, PdfInt(UNKNOWN_STEM_THICKNESS))
                put(if (subset is Subset.TrueType) FONT_FILE_2 else FONT_FILE_3, fontFile)
            },
        )
    }

    /**
     * Table 121's flags, as far as a TrueType program states them.
     *
     * `Symbolic` is set for every supplied font, and `Nonsymbolic` therefore
     * clear — "This flag and the Nonsymbolic flag shall not both be set or both
     * be clear." The flag means "Font contains glyphs outside the Standard Latin
     * character set", and a font reaches this library because the fourteen fonts
     * of §9.6.2.2 could not draw the document's text, which is the same statement.
     * It also decides nothing here: the flag "influences the font's default base
     * encoding", and a Type 0 font's encoding is the CMap named in `Encoding`
     * (§9.7.6.2), never a default.
     *
     * `Italic` comes from `head`'s macStyle and `FixedPitch` from `post`. `Serif`,
     * `Script`, `AllCap`, `SmallCap` and `ForceBold` are left clear: no table of
     * the program states them, and "all undefined flag bits are reserved and shall
     * be set to 0 by PDF writers".
     */
    private fun flags(): Int {
        var flags = SYMBOLIC
        if (program.head.macStyle and MAC_STYLE_ITALIC != 0) flags = flags or ITALIC
        if (post?.isFixedPitch == true) flags = flags or FIXED_PITCH
        return flags
    }

    /**
     * The top of this font's `H`, in glyph space, or null if the font has no
     * such glyph — in which case it has no Latin characters and Table 120 does
     * not require the entry.
     */
    private fun capHeight(): Double? {
        val glyf = program.glyf ?: return null
        if (program.cmap == null) return null
        val capital = program.glyphIdOf(CAPITAL_H)
        if (capital == TrueTypeSubset.NOTDEF) return null
        return when (val glyph = glyf.glyphOf(capital)) {
            is Glyph.Simple -> inGlyphSpace(glyph.header.yMax)
            is Glyph.Composite -> inGlyphSpace(glyph.header.yMax)
            // A capital H with no outline is not a capital H.
            Glyph.Blank -> null
        }
    }

    /**
     * The `W` array of Table 115, over the CIDs the document showed: "c
     * [w1 w2… wn] … c shall be an integer specifying a starting CID value; it
     * shall be followed by an array of n numbers that shall specify the widths
     * for n consecutive CIDs, starting with c."
     *
     * Runs of consecutive CIDs share one group, which is what the form is for —
     * the CIDs here are glyph indices, and a font numbers the glyphs of a script
     * consecutively, so a document in one language shows a few long runs.
     *
     * The widths are real numbers rather than rounded, because they are the
     * numbers a reader advances the text position by and this library measures
     * lines with the same division: §9.2.4 NOTE 3 — "Using real numbers instead
     * of integers can mitigate rounding errors caused by this difference, and is
     * recommended when precise character positioning is required."
     */
    private fun widths(): PdfArray {
        val cids = shownGlyphs.keys.sorted()
        val groups = ArrayList<PdfObject>()
        var first = 0
        while (first < cids.size) {
            var last = first
            while (last + 1 < cids.size && cids[last + 1] == cids[last] + 1) last++
            groups += PdfInt(cids[first].toLong())
            groups += PdfArray((first..last).map { PdfReal(inGlyphSpace(program.advanceWidthOf(cids[it]))) })
            first = last + 1
        }
        return PdfArray(groups)
    }

    /**
     * The `CIDToGIDMap` stream: "the bytes in the stream shall contain the
     * mapping from CIDs to glyph indices: the glyph index for a particular CID
     * value c shall be a 2-byte value stored in bytes 2 × c and 2 × c + 1, where
     * the first byte shall be the high-order byte."
     *
     * The stream runs to the highest CID the document showed and no further. A
     * CID past its end, and any CID within it that was never shown, reads as
     * glyph 0 — `.notdef`, which is what a code the document never wrote means.
     *
     * CID 0 maps to glyph 0 without being written: `.notdef` is glyph 0 of the
     * font and glyph 0 of every subset ("The glyph name .notdef shall be defined
     * in the font subset", §9.9.2), and the array starts out zero.
     */
    private fun cidToGidMap(subset: TrueTypeSubset): ByteArray {
        val highest = shownGlyphs.keys.maxOrNull() ?: TrueTypeSubset.NOTDEF
        val map = ByteArray((highest + 1) * BYTES_PER_CODE)
        for (cid in shownGlyphs.keys) {
            val glyphId = subset.subsetGlyphIdOf(cid)
            map[cid * BYTES_PER_CODE] = (glyphId ushr Byte.SIZE_BITS).toByte()
            map[cid * BYTES_PER_CODE + 1] = glyphId.toByte()
        }
        return map
    }

    /**
     * What each code the document showed stands for, in code order, for the
     * `ToUnicode` CMap of §9.10.3.
     *
     * One character per code. Two characters that select the same glyph — a
     * font that draws U+00A0 NO-BREAK SPACE with the glyph of U+0020 SPACE, say —
     * are one code in the file and cannot be told apart by anything a reader
     * sees; the first one drawn is the one recorded, and the text extracts as a
     * character the page did draw.
     */
    private fun unicodeMappings(): List<Pair<Int, String>> =
        shownGlyphs.entries.sortedBy { it.key }.map { (code, codePoint) -> code to codePointToString(codePoint) }

    // ---- Internals --------------------------------------------------------

    /**
     * The glyph the character [codePoint] selects, refusing the characters the
     * program has no glyph for.
     *
     * `cmap` answers 0 for a character it does not map, and 0 is `.notdef`. Drawing
     * it would put a missing-character box on the page and report success, so it
     * is an error here instead — with the character and its position in [text],
     * per the no-silent-loss policy.
     */
    /** Whether the program has a glyph for [codePoint] — asked without showing it. */
    fun canDraw(codePoint: Int): Boolean = program.glyphIdOf(codePoint) != TrueTypeSubset.NOTDEF

    private fun glyphIdOf(codePoint: Int, text: String, index: Int): Int {
        val glyphId = program.glyphIdOf(codePoint)
        if (glyphId == TrueTypeSubset.NOTDEF) {
            throw UnencodableCharacterException.outsideFontProgram(text, index, codePoint, ref.id)
        }
        return glyphId
    }

    /** Records that the document shows [glyphId] for the character [codePoint]. */
    private fun show(glyphId: Int, codePoint: Int) {
        if (glyphId in shownGlyphs) return
        check(!written) {
            "the font \"${ref.id}\" was asked to show the character " +
                "'${codePointToString(codePoint)}' with glyph $glyphId after its subset had been " +
                "written into the file. The subset carries the glyphs collected up to that point and " +
                "the CIDToGIDMap maps only those, so this glyph would come out as .notdef on the page " +
                "— draw every page before writing the font dictionaries"
        }
        shownGlyphs[glyphId] = codePoint
    }

    /**
     * [designUnits] of the font's own units, in the glyph space of §9.2.4, where
     * "the units of glyph space are one-thousandth of a unit of text space".
     *
     * NOTE 3 of the clause is this conversion: "TrueType stores widths in units
     * of 1024 or 2048 to an Em … however the Widths array stores widths in units
     * of 1000 to an Em."
     */
    private fun inGlyphSpace(designUnits: Int): Double = designUnits * GLYPH_SPACE_UNITS_PER_EM / program.unitsPerEm

    override fun toString(): String = "EmbeddedFont(${ref.id}, ${shownGlyphs.size} glyphs shown)"

    private companion object {
        /** "the units of glyph space are one-thousandth of a unit of text space" (§9.2.4). */
        const val GLYPH_SPACE_UNITS_PER_EM: Double = 1000.0

        /** An `Identity-H` code, and a `CIDToGIDMap` entry, are two bytes each. */
        const val BYTES_PER_CODE: Int = 2

        /** `H`, the glyph `CapHeight` is measured from. */
        const val CAPITAL_H: Int = 0x48

        /** Table 121, bit 1: "All glyphs have the same width". */
        const val FIXED_PITCH: Int = 1 shl 0

        /** Table 121, bit 3: "Font contains glyphs outside the Standard Latin character set". */
        const val SYMBOLIC: Int = 1 shl 2

        /** Table 121, bit 7: "Glyphs have dominant vertical strokes that are slanted". */
        const val ITALIC: Int = 1 shl 6

        /** `head`'s macStyle, bit 1: Italic. */
        const val MAC_STYLE_ITALIC: Int = 1 shl 1

        /** "A value of 0 indicates an unknown stem thickness" (Table 120). */
        const val UNKNOWN_STEM_THICKNESS: Long = 0

        /** The `ItalicAngle` of a font that declares no slope. */
        const val UPRIGHT: Double = 0.0

        val TYPE: PdfName = PdfName("Type")
        val SUBTYPE: PdfName = PdfName("Subtype")
        val FONT: PdfName = PdfName("Font")
        val TYPE_0: PdfName = PdfName("Type0")
        val CID_FONT_TYPE_2: PdfName = PdfName("CIDFontType2")
        val CID_FONT_TYPE_0: PdfName = PdfName("CIDFontType0")
        val CID_FONT_TYPE_0C: PdfName = PdfName("CIDFontType0C")
        val FONT_FILE_3: PdfName = PdfName("FontFile3")
        val BASE_FONT: PdfName = PdfName("BaseFont")
        val ENCODING: PdfName = PdfName("Encoding")
        val IDENTITY_H: PdfName = PdfName("Identity-H")
        val DESCENDANT_FONTS: PdfName = PdfName("DescendantFonts")
        val TO_UNICODE: PdfName = PdfName("ToUnicode")
        val CID_SYSTEM_INFO: PdfName = PdfName("CIDSystemInfo")

        /** Both the entry of Table 115 and the `/Type` of what it points at. */
        val FONT_DESCRIPTOR: PdfName = PdfName("FontDescriptor")
        val WIDTHS: PdfName = PdfName("W")
        val CID_TO_GID_MAP: PdfName = PdfName("CIDToGIDMap")
        val FONT_NAME: PdfName = PdfName("FontName")
        val FLAGS: PdfName = PdfName("Flags")
        val FONT_BBOX: PdfName = PdfName("FontBBox")
        val ITALIC_ANGLE: PdfName = PdfName("ItalicAngle")
        val ASCENT: PdfName = PdfName("Ascent")
        val DESCENT: PdfName = PdfName("Descent")
        val CAP_HEIGHT: PdfName = PdfName("CapHeight")
        val STEM_V: PdfName = PdfName("StemV")
        val FONT_FILE_2: PdfName = PdfName("FontFile2")
        val LENGTH_1: PdfName = PdfName("Length1")

        /**
         * `Adobe-Identity-0` (Table 114): the character collection of a font whose
         * CIDs are its own glyph indices. `Registry` "shall begin with the 4 or 5
         * characters of a registered developer prefix"; `Adobe` is the prefix this
         * collection is registered under, and `Ordering` "uniquely names the
         * character collection within the specified registry".
         */
        val ADOBE_IDENTITY_0: PdfDict = PdfDict(
            mapOf(
                PdfName("Registry") to PdfString("Adobe".encodeToByteArray()),
                PdfName("Ordering") to PdfString("Identity".encodeToByteArray()),
                PdfName("Supplement") to PdfInt(0),
            ),
        )
    }
}
