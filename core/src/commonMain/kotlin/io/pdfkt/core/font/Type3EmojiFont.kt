/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.font

import io.pdfkt.core.content.ContentStreamBuilder
import io.pdfkt.core.filter.flateStream
import io.pdfkt.core.image.ImageCatalog
import io.pdfkt.core.image.ResolvedImage
import io.pdfkt.core.image.pngImage
import io.pdfkt.core.obj.PdfArray
import io.pdfkt.core.obj.PdfDict
import io.pdfkt.core.obj.PdfInt
import io.pdfkt.core.obj.PdfName
import io.pdfkt.core.obj.PdfObject
import io.pdfkt.core.obj.PdfReal
import io.pdfkt.core.obj.PdfRef
import io.pdfkt.core.obj.PdfStream

// ISO 32000-2:2020 §9.6.4 "Type 3 fonts" (Tables 110, 111),
// §9.6.5.1 "Character encoding — General" (Table 112),
// §9.6.5.3 "Encodings for Type 3 fonts", §9.6.1 "Simple fonts — General",
// §8.8.1 "External objects — General", §8.9.4 "Image coordinate system",
// §9.10.3 "ToUnicode CMaps", §7.8.3 "Resource dictionaries", §7.3.8 "Stream objects"

/**
 * A picture per character, written as the one font PDF has that can carry one: a
 * Type 3 font whose glyph descriptions place PNGs.
 *
 * ```
 * /Font << /E1 → Type 3 font (Table 110)
 *                  /FontMatrix [1/unitsPerEm 0 0 1/unitsPerEm 0 0]
 *                  /CharProcs << /gNNN → "wx 0 d0  q  w 0 0 h x y cm  /Im1 Do  Q" >>
 *                  /Encoding << /Differences [1 /gNNN …] >>
 *                  /Widths [ … ]
 *                  /Resources << /XObject << /Im1 → image XObject (§8.9) >> >>
 *                  /ToUnicode → CMap (§9.10.3)
 * ```
 *
 * ## Why Type 3 and not one of the others
 *
 * §9.6.4 opens with what makes it the only candidate: "Font dictionaries for
 * other fonts simply contain information about the font and refer to a separate
 * font program for the actual glyph descriptions; a Type 3 font dictionary
 * contains the glyph descriptions. In Type 3 fonts, glyphs shall be defined by
 * streams of PDF graphics operators." A colour emoji has no outline to embed —
 * it arrives as a PNG, whether out of a font program's `CBDT` table
 * ([ColorEmojiFont]) or out of a caller's rasterizer ([RasterizedEmojiFont]) —
 * so there is nothing for a `FontFile2` to carry, and a stream of graphics
 * operators is exactly what can hold an image.
 *
 * The glyph descriptions begin with `d0` rather than `d1`, per Table 111 and its
 * NOTE 2: an image "does set the colour space and colour explicitly", and a `d1`
 * glyph "shall not include an image".
 *
 * ## Where the glyphs come from is not this class's business
 *
 * This is the emission and nothing else: the two fonts above differ in what a
 * character selects and in where a picture and its metrics come from, and agree
 * in every byte that reaches the file. Writing that agreement down once is what
 * keeps them from drifting — the file's structure is fixed by §9.6.4, not by
 * which of the two produced the pictures.
 *
 * A glyph arrives from a source already measured in the glyph coordinate system
 * this font's `FontMatrix` fixes ([Box]), because converting *into* that system
 * is precisely the part that differs: a strike states pixels of *ppem* to the em
 * of a font program, a rasterizer states pixels of the em it was asked for.
 *
 * ## One byte per emoji
 *
 * A Type 3 font is a simple font, so a page shows it one byte per glyph and the
 * `Encoding` dictionary is what the byte means: "A Type 3 font's mapping from
 * character codes to glyph names shall be entirely defined by its Encoding
 * entry" (§9.6.5.3). Codes are handed out as glyphs are first drawn, from 1
 * upwards — the clause's own example starts its `Differences` at 97 rather than
 * at 0, and leaving code 0 unassigned keeps a NUL byte out of the strings a page
 * carries.
 *
 * That bounds one of these fonts at 255 emoji. A document with more is refused,
 * naming the limit: splitting the codes over a second Type 3 font is a decision
 * about which font a run of text is drawn with, which belongs to the resolver
 * above these fonts and not to a font that would have to renumber pages already
 * drawn.
 *
 * ## Order of use
 *
 * Draw first, write afterwards, exactly as [EmbeddedFont]: [show] hands out the
 * codes and collects the glyphs, [fontDictionary] writes the CharProcs of what
 * was collected. A glyph offered after the dictionary was written is refused
 * rather than dropped — its code would name a CharProcs entry that is not there,
 * and "If the name is not present as a key in CharProcs, no glyph shall be
 * painted" (§9.6.4), which is a blank page reported as a success.
 *
 * @param id what the font is called within one document — the name its glyphs'
 *   images carry and the name every message about it uses.
 * @param unitsPerEm how many units of this font's glyph coordinate system make
 *   an em, which is the whole of its `FontMatrix`.
 */
internal class Type3EmojiFont(private val id: String, private val unitsPerEm: Int) {
    /**
     * The images of this font's glyphs, named `/Im1`, `/Im2`, … within the
     * font's own `/Resources` — which is where §9.6.4 says a glyph description
     * looks them up: "If any glyph descriptions refer to named resources they
     * shall be looked up in the Resources entry of the Type 3 font dictionary."
     *
     * A catalog of its own, not the document's: the names are local to these
     * glyph descriptions ("The scope of a resource name shall be local to a
     * particular content stream", §7.8.3), and an emoji drawn on a page is not
     * an image the document drew.
     */
    private val images = ImageCatalog()

    /**
     * The glyphs the document has shown, keyed as the glyph source keys them and
     * in first-use order — which is code order, since a code is handed out on
     * first use.
     */
    private val glyphs: MutableMap<String, Glyph> = LinkedHashMap()

    /** Whether [fontDictionary] has written the CharProcs that [glyphs] fixed. */
    private var written: Boolean = false

    /** The glyph [key] was shown as, or null for one this font has not shown. */
    fun shown(key: String): Glyph? = glyphs[key]

    /**
     * The code the next glyph shown would be handed — what a source names its
     * glyphs after when it has nothing of its own to name them by.
     */
    fun nextCode(): Int = FIRST_CODE + glyphs.size

    /**
     * The glyph [key] is shown as, registering it — with the next code, a
     * CharProcs entry and an image — on first use.
     *
     * @param key what the source tells one glyph from another by: a glyph index
     *   of a font program, an emoji sequence, whatever the source selects with.
     * @param name the glyph name, which is both the key of its CharProcs entry
     *   and what its `Differences` entry names. Which name is arbitrary:
     *   §9.6.5.3 says a Type 3 font's "mapping from character codes to glyph
     *   names shall be entirely defined by its Encoding entry", the names are
     *   looked up in this font's own CharProcs and nowhere else, and "Type 3
     *   fonts do not support the concept of a default glyph name". It has to be
     *   unique among the glyphs of one font, and traceable back to the source.
     * @param text what the code stands for, for the `ToUnicode` CMap of §9.10.3.
     * @param png the picture, as the bytes of a PNG datastream.
     * @param box where that picture goes and how far it advances, in this font's
     *   glyph coordinate system.
     * @throws IllegalStateException if the font dictionary has already been
     *   written, or if this would be the 256th glyph of a simple font.
     */
    fun show(key: String, name: String, text: String, png: ByteArray, box: Box): Glyph {
        glyphs[key]?.let { return it }
        check(!written) {
            "the emoji font \"$id\" was asked to show \"$text\" after its font dictionary had been " +
                "written into the file. The CharProcs carry the glyphs collected up to that point, and " +
                "a code naming no CharProcs entry paints nothing at all (ISO 32000-2:2020 §9.6.4) — " +
                "draw every page before writing the font dictionaries"
        }
        check(glyphs.size < MAX_GLYPHS) {
            "the emoji font \"$id\" has been asked for a ${glyphs.size + 1}th distinct emoji, " +
                "\"$text\". A Type 3 font is a simple font, whose glyphs \"shall be selected by " +
                "single-byte character codes\" (ISO 32000-2:2020 §9.6.1), so one of them holds at most " +
                "$MAX_GLYPHS glyphs. Splitting the text over a second emoji font is a decision about " +
                "which font a run is drawn with and belongs above this font"
        }
        val glyph = Glyph(
            code = nextCode(),
            name = PdfName(name),
            text = text,
            box = box,
            image = images.resolve(pngImage("$id glyph $name", png)),
        )
        glyphs[key] = glyph
        return glyph
    }

    /**
     * The Type 3 font dictionary of Table 110, and — through [define] — the
     * CharProcs streams, the image XObjects they paint, the `Widths` array and
     * the `ToUnicode` CMap under it.
     *
     * Every entry Table 110 marks Required is written:
     *
     * - `Type` and `Subtype`, `Font` and `Type3`;
     * - `FontBBox`, "the smallest rectangle enclosing all marks that would
     *   result if all of the glyphs of the font were placed with their origins
     *   coincident" — computed from the pictures of the glyphs this font holds,
     *   which is what the file's glyphs are;
     * - `FontMatrix`, the em the glyphs are measured in;
     * - `CharProcs`, one stream per glyph;
     * - `Encoding`, "an encoding dictionary whose Differences array shall
     *   specify the complete character encoding for this font";
     * - `FirstChar`, `LastChar` and `Widths`.
     *
     * `FontDescriptor` is not written: the table marks it "Required in Tagged
     * PDF documents", this library writes none, and the clause's own example
     * (§9.6.4) has no such entry. Nothing else in Table 110 is required, and
     * `Resources` and `ToUnicode` are the two optional entries this font cannot
     * do without — the first because the glyph descriptions name image XObjects,
     * the second because without it "no information would be available about
     * what the glyphs mean" (§9.10.3) and an emoji would extract as a byte.
     *
     * @param define makes an object indirect and hands back the reference. The
     *   CharProcs streams and the images have to be defined — "all streams shall
     *   be indirect objects" (§7.3.8) — and `Widths` is one because Table 110
     *   says it "should be an indirect reference".
     */
    fun fontDictionary(define: (PdfObject) -> PdfRef): PdfDict {
        written = true
        val shown = glyphs.values.toList()
        return PdfDict(
            mapOf(
                TYPE to FONT,
                SUBTYPE to TYPE_3,
                FONT_BBOX to fontBoundingBox(shown),
                FONT_MATRIX to fontMatrix(),
                CHAR_PROCS to charProcs(shown, define),
                ENCODING to encoding(shown),
                FIRST_CHAR to PdfInt(FIRST_CODE.toLong()),
                LAST_CHAR to PdfInt(lastCode(shown).toLong()),
                WIDTHS to define(widths(shown)),
                RESOURCES to PdfDict(mapOf(X_OBJECT to images.imageResources(define))),
                TO_UNICODE to define(
                    flateStream(TO_UNICODE_STREAM_DICT, toUnicodeCMap(unicodeMappings(shown), BYTES_PER_CODE)),
                ),
            ),
        )
    }

    /**
     * `FontMatrix`, "an array of six numbers specifying the font matrix, mapping
     * glyph space to text space": the em the glyphs are measured in, scaled to
     * the one unit of text space that a glyph of a font at size 1 occupies.
     *
     * The clause's NOTE offers the 1000-unit system as "a common practice"
     * rather than a rule, and the em of the source is the system the numbers
     * arrive in — one multiplication from what the source states, instead of
     * two.
     */
    private fun fontMatrix(): PdfArray {
        val scale = 1.0 / unitsPerEm
        return PdfArray(listOf(PdfReal(scale), ZERO, ZERO, PdfReal(scale), ZERO, ZERO))
    }

    /**
     * `CharProcs`, "a dictionary in which each key shall be a glyph name and the
     * value associated with that key shall be a content stream that constructs
     * and paints the glyph for that character".
     */
    private fun charProcs(shown: List<Glyph>, define: (PdfObject) -> PdfRef): PdfDict =
        PdfDict(shown.associate { glyph -> glyph.name to define(charProc(glyph)) })

    /**
     * One glyph description: the width, then the glyph's picture placed where
     * the source says it goes relative to the origin.
     *
     * ```
     * wx 0 d0
     * q
     * w 0 0 h x y cm
     * /Im1 Do
     * Q
     * ```
     *
     * The `cm` is what §8.9.4 requires of every image: "the image shall be
     * painted in the unit square of user space", so a picture of *w* × *h*
     * arrives by mapping that square onto the rectangle the glyph occupies — the
     * rectangle [Box] states, in the glyph coordinate system, "placing the glyph
     * origin at (0, 0)" as §9.6.4 requires.
     *
     * The `q` … `Q` is not required — §9.6.4 saves and restores the graphics
     * state around a glyph description anyway ("The graphics state shall be
     * saved before this invocation and shall be restored afterward") — but the
     * `cm` belongs to this one image, and a description that leaves the CTM
     * where it found it is one that can be extended without re-reading it.
     */
    private fun charProc(glyph: Glyph): PdfStream {
        val box = glyph.box
        val content = ContentStreamBuilder()
        content.glyphWidth(box.advance)
        content.save()
        content.concat(a = box.width, b = 0.0, c = 0.0, d = box.height, e = box.left, f = box.bottom)
        content.paintXObject(glyph.image.resourceName)
        content.restore()
        return flateStream(PdfDict(emptyMap()), content.build())
    }

    /**
     * The encoding dictionary of Table 112, whose `Differences` array is "the
     * complete character encoding for this font" (Table 110) — as §9.6.5.3
     * requires of a Type 3 font, where the array is not a difference from any
     * base encoding but the encoding itself.
     *
     * The codes are consecutive from [FIRST_CODE], so the array is one integer
     * and then the names — which is what §9.6.5.1 says such an array means:
     * "Each code shall be the first index in a sequence of character codes to be
     * changed. The first character name after the code becomes the name
     * corresponding to that code. Subsequent names replace consecutive code
     * indices until the next code appears in the array or the array ends."
     *
     * No `BaseEncoding` is written. Table 112 makes it optional and gives it a
     * meaning this font has no use for — it names one of the predefined
     * encodings the `Differences` are differences *from*, and here they are not
     * differences at all but the whole encoding (§9.6.5.3).
     */
    private fun encoding(shown: List<Glyph>): PdfDict = PdfDict(
        mapOf(
            TYPE to ENCODING,
            DIFFERENCES to PdfArray(listOf(PdfInt(FIRST_CODE.toLong())) + shown.map { glyph -> glyph.name }),
        ),
    )

    /**
     * `Widths`, "an array of (LastChar - FirstChar + 1) numbers, each element
     * being the glyph width for the character code that equals FirstChar plus
     * the array index", in the glyph space `FontMatrix` fixes.
     *
     * A font that showed nothing still writes one width, for the one code
     * `FirstChar` and `LastChar` then span; it has no CharProcs entry, and "if
     * the name is not present as a key in CharProcs, no glyph shall be painted".
     */
    private fun widths(shown: List<Glyph>): PdfArray = PdfArray(
        List(lastCode(shown) - FIRST_CODE + 1) { index -> PdfReal(shown.getOrNull(index)?.box?.advance ?: 0.0) },
    )

    /**
     * `FontBBox`, "expressed in the glyph coordinate system … the smallest
     * rectangle enclosing all marks that would result if all of the glyphs of
     * the font were placed with their origins coincident and their descriptions
     * executed".
     *
     * The marks of these glyphs are their pictures, so the rectangle is the
     * union of the rectangles [charProc] maps them onto. A font with no glyphs
     * writes the all-zero rectangle, which the table gives a meaning to: "If all
     * four elements of the rectangle are zero, a PDF processor shall make no
     * assumptions about glyph sizes based on the font bounding box."
     */
    private fun fontBoundingBox(shown: List<Glyph>): PdfArray {
        if (shown.isEmpty()) return PdfArray(List(RECTANGLE_NUMBERS) { ZERO })
        var minX = Double.MAX_VALUE
        var minY = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE
        var maxY = -Double.MAX_VALUE
        for (glyph in shown) {
            val box = glyph.box
            minX = minOf(minX, box.left)
            minY = minOf(minY, box.bottom)
            maxX = maxOf(maxX, box.left + box.width)
            maxY = maxOf(maxY, box.bottom + box.height)
        }
        return PdfArray(listOf(minX, minY, maxX, maxY).map { PdfReal(it) })
    }

    /**
     * What each code stands for, for the `ToUnicode` CMap of §9.10.3: the text
     * the glyph the code names was shown for.
     *
     * One string per code, and it is the whole of what was drawn — a code
     * standing for a ZWJ sequence maps back to every code point of it, since
     * "no information would be available about what the glyphs mean" otherwise.
     */
    private fun unicodeMappings(shown: List<Glyph>): List<Pair<Int, String>> =
        shown.map { glyph -> glyph.code to glyph.text }

    /** The last code this font assigned, or [FIRST_CODE] for a font that assigned none. */
    private fun lastCode(shown: List<Glyph>): Int = FIRST_CODE + maxOf(shown.size, 1) - 1

    override fun toString(): String = "Type3EmojiFont($id, ${glyphs.size} glyphs shown)"

    /**
     * One emoji of one document: the code it is shown as, the name its glyph
     * description is under, the text it stands for, where its picture goes and
     * the image XObject that picture was resolved to.
     */
    class Glyph(val code: Int, val name: PdfName, val text: String, val box: Box, val image: ResolvedImage)

    /**
     * Where one glyph's picture goes, in the glyph coordinate system of the font
     * that holds it — the rectangle §8.9.4's unit square is mapped onto, and the
     * displacement §9.6.4 has the `d0` operator and the `Widths` array state.
     *
     * @property advance how far the glyph moves the pen: `wx` of `d0`, "the
     *   horizontal displacement in the glyph coordinate system".
     * @property left the left edge of the picture, relative to the origin.
     * @property bottom its bottom edge, negative for a picture reaching below
     *   the baseline.
     * @property width the width of the picture, and [height] its height.
     */
    class Box(val advance: Double, val left: Double, val bottom: Double, val width: Double, val height: Double)

    companion object {
        /** "the units of glyph space are one-thousandth of a unit of text space" (§9.2.4). */
        const val GLYPH_SPACE_UNITS_PER_EM: Double = 1000.0

        /** The first code one of these fonts hands out; see the class documentation. */
        const val FIRST_CODE: Int = 1

        /** Codes 1 to 255: a simple font shows "single-byte character codes" (§9.6.1). */
        const val MAX_GLYPHS: Int = 255

        /** A simple font's code is one byte, which is what its `ToUnicode` codespace declares. */
        private const val BYTES_PER_CODE: Int = 1

        /** "A rectangle shall be written as an array of four numbers" (§7.9.5). */
        private const val RECTANGLE_NUMBERS: Int = 4

        private val ZERO: PdfReal = PdfReal(0.0)

        private val TYPE: PdfName = PdfName("Type")
        private val SUBTYPE: PdfName = PdfName("Subtype")
        private val FONT: PdfName = PdfName("Font")
        private val TYPE_3: PdfName = PdfName("Type3")
        private val FONT_BBOX: PdfName = PdfName("FontBBox")
        private val FONT_MATRIX: PdfName = PdfName("FontMatrix")
        private val CHAR_PROCS: PdfName = PdfName("CharProcs")
        private val ENCODING: PdfName = PdfName("Encoding")
        private val DIFFERENCES: PdfName = PdfName("Differences")
        private val FIRST_CHAR: PdfName = PdfName("FirstChar")
        private val LAST_CHAR: PdfName = PdfName("LastChar")
        private val WIDTHS: PdfName = PdfName("Widths")
        private val RESOURCES: PdfName = PdfName("Resources")
        private val X_OBJECT: PdfName = PdfName("XObject")
        private val TO_UNICODE: PdfName = PdfName("ToUnicode")
    }
}
