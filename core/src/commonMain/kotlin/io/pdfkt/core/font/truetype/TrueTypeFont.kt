/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.font.truetype

// OpenType 1.9.1 §"OpenType font file" — "Required Tables";
// §"cmap", §"head", §"hhea", §"hmtx", §"maxp", §"glyf", §"loca"
// ISO 32000-2:2020 §9.7 "Composite fonts", §9.9 "Embedded font programs"

/**
 * A font program, parsed as far as measuring text with it needs.
 *
 * This is the embedded font path reading its own program: the
 * caller supplies bytes ([io.pdfkt.core.font.FontRef.Supplied]), and everything
 * a document has to know about them is in the tables, not in a table of
 * assumptions about fonts:
 *
 * | Table | What it answers |
 * |---|---|
 * | `head` | how big the em is, in the units everything else is measured in |
 * | `hhea` | how far the font reaches above and below the baseline; how long `hmtx` is |
 * | `maxp` | how many glyphs there are |
 * | `hmtx` | how wide each glyph is |
 * | `cmap` | which glyph a character selects |
 * | `loca` | where each glyph's description is |
 * | `glyf` | what a glyph is drawn out of — for a composite, which other glyphs |
 * | `CBLC` | where a glyph's colour bitmap is, and how big |
 * | `CBDT` | the colour bitmaps themselves, one PNG per glyph |
 *
 * The first five are the ones the specification lists under "Required Tables"
 * for every font; `loca` and `glyf` are required "for fonts with TrueType
 * outlines" and absent from one with CFF outlines, which is why [glyf] is
 * nullable. `CBLC` and `CBDT` are neither required nor common: they are what a
 * colour-emoji font draws out of, and what the
 * `EmbeddedColorFont` policy reads (see [colorBitmapOf]).
 *
 * [cmap] is nullable for a different reason, and it is this library's own
 * output that makes it so. A font program embedded in a PDF is not a font file
 * anyone opens: ISO 32000-2 Table 124 states what one has to contain — "The font
 * program shall include these tables: 'glyf', 'head', 'hhea', 'hmtx', 'loca',
 * and 'maxp'" — and a character map is not among them, because a `CIDFontType2`
 * font is addressed by glyph index and never by character code. The subset
 * [TrueTypeSubset] writes carries exactly the listed tables and no character map
 * at all, and this parser reads back what this library writes.
 *
 * [directory] is how a subset reaches the rest of the tables, so nothing has to
 * re-read the file header to find them.
 *
 * ## Units
 *
 * Every measurement here is in the font's own design units — [unitsPerEm] of
 * them to the em — and none of them is in the 1000ths of an em a PDF measures
 * in. The conversion is one division, and it belongs where §9.2.4's glyph space
 * is implemented (`TextSpaceMetrics`), not spread over a parser. NOTE 3 of that
 * clause is the warning that makes keeping them apart worth the trouble:
 * "TrueType stores widths in units of 1024 or 2048 to an Em … however the Widths
 * array stores widths in units of 1000 to an Em."
 */
internal class TrueTypeFont private constructor(
    val directory: TableDirectory,
    val head: HeadTable,
    val hhea: HheaTable,
    val maxp: MaxpTable,
    val hmtx: HmtxTable,
    /**
     * The character map, or null if the program carries none — see the class
     * documentation. A font supplied by a caller has one; a subset this library
     * wrote for embedding does not.
     */
    val cmap: CmapTable?,
    /**
     * The glyph descriptions, or null if the font has none in this format —
     * a font with CFF outlines ([TableDirectory.COMPACT_FONT_FORMAT_OUTLINES])
     * carries its glyphs in a `CFF ` table instead, and every question this
     * class answers about measuring text is answered without either.
     */
    val glyf: GlyfTable?,
    /**
     * Where this font's colour bitmaps are, or null if it carries none — see
     * [CblcTable] and [colorBitmapOf]. A colour-emoji font has them; nothing
     * else this library reads does.
     */
    val cblc: CblcTable?,
    /** The colour bitmaps [cblc] locates, present exactly when [cblc] is. */
    val cbdt: CbdtTable?,
) {
    /** `head`'s unitsPerEm: how many design units make an em in this font. */
    val unitsPerEm: Int get() = head.unitsPerEm

    /** `maxp`'s numGlyphs: the glyphs are 0 until this. */
    val numGlyphs: Int get() = maxp.numGlyphs

    /**
     * The glyph the Unicode code point [codePoint] selects, or 0 — `.notdef`, "a
     * special glyph representing a missing character" — if this font has none.
     *
     * Returning 0 rather than raising is the `cmap` clause's rule and stops
     * here: the caller drawing text knows which character it asked about and
     * where in the text it was, and is the one that can refuse in the terms
     * the no-silent-loss policy requires: an error carrying the offending
     * character and its position.
     *
     * @throws IllegalArgumentException if the program has no `cmap` table. That
     *   is not a font a caller can supply — every font file has one — but it is
     *   what a subset written for embedding is, and asking such a program which
     *   glyph a character selects is a question about the document's encoding
     *   that has been put to the wrong thing.
     */
    fun glyphIdOf(codePoint: Int): Int = requireNotNull(cmap) {
        "the font program has no 'cmap' table, so it maps no character to any glyph; a font program " +
            "embedded in a PDF need not carry one (ISO 32000-2:2020 §9.9, Table 124), and one that " +
            "does not is addressed by glyph index"
    }.glyphIdOf(codePoint)

    /**
     * The advance width of the glyph [glyphId] in design units — §9.2.4's "the
     * distance the current text position shall move (by translating text space)
     * when the glyph is painted".
     */
    fun advanceWidthOf(glyphId: Int): Int = hmtx.advanceWidthOf(glyphId)

    /** The left side bearing of the glyph [glyphId] in design units. */
    fun leftSideBearingOf(glyphId: Int): Int = hmtx.leftSideBearingOf(glyphId)

    /**
     * The strikes of colour bitmaps this font carries — "A set of bitmaps for a
     * face at a given size is called a strike" — empty for a font with none.
     */
    val colorBitmapStrikes: List<BitmapStrike> get() = cblc?.strikes ?: emptyList()

    /**
     * The colour bitmap of the glyph [glyphId] in [strike], or null if the
     * strike has none for it.
     */
    fun colorBitmapOf(glyphId: Int, strike: BitmapStrike): ColorBitmapGlyph? = requireNotNull(cbdt) {
        "the font program has no 'CBDT' table, so it stores no colour bitmap for any glyph"
    }.glyphOrNull(strike, glyphId)

    /**
     * The colour bitmap of the glyph [glyphId] in the font's largest strike
     * ([CblcTable.largestStrike]), or null if the font has no colour bitmap for
     * it — including the case of a font that has none for anything.
     */
    fun colorBitmapOf(glyphId: Int): ColorBitmapGlyph? {
        val strike = cblc?.largestStrike ?: return null
        return colorBitmapOf(glyphId, strike)
    }

    override fun toString(): String = "TrueTypeFont($numGlyphs glyphs, $unitsPerEm units per em, " +
        "${cmap?.let { "mapped through $it" } ?: "no character map"})"

    companion object {
        /**
         * Parses [bytes] as a font program.
         *
         * The order is forced by the format: `hmtx` has neither a count nor a
         * length of its own, so `hhea` and `maxp` have to be read first —
         * "The number of LongHorMetric records is determined by the
         * numberOfHMetrics field in the 'hhea' table", and the array after them
         * "will be derived from the numGlyphs field in the 'maxp' table minus
         * numberOfHMetrics".
         *
         * @throws IllegalArgumentException if [bytes] are not a font program
         *   this library can read, saying which field made it say so. A font is
         *   input from outside; refusing it with a reason is the only honest
         *   alternative to drawing a document with the wrong glyphs in it.
         * @param index which font of a font collection to read; 0, the only
         *   font there is, for a file that is not a collection.
         */
        fun parse(bytes: ByteArray, index: Int = 0): TrueTypeFont {
            val file = BigEndianReader.over(bytes, "the font program")
            val directory = TableDirectory.parse(file, index)
            val head = HeadTable.parse(directory.readerOf("head"))
            val hhea = HheaTable.parse(directory.readerOf("hhea"))
            val maxp = MaxpTable.parse(directory.readerOf("maxp"))
            val hmtx = HmtxTable.parse(
                reader = directory.readerOf("hmtx"),
                numberOfHMetrics = hhea.numberOfHMetrics,
                numGlyphs = maxp.numGlyphs,
            )
            // Optional, and only because of what this library itself writes:
            // ISO 32000-2:2020 Table 124 does not list 'cmap' among the tables an
            // embedded TrueType font program has to include.
            val cmap = directory.readerOrNull("cmap")?.let { CmapTable.parse(it) }
            val colorBitmaps = colorBitmapsOrNull(directory)
            return TrueTypeFont(
                directory = directory,
                head = head,
                hhea = hhea,
                maxp = maxp,
                hmtx = hmtx,
                cmap = cmap,
                glyf = glyfOrNull(directory, head, maxp),
                cblc = colorBitmaps?.first,
                cbdt = colorBitmaps?.second,
            )
        }

        /**
         * The `CBLC` and `CBDT` tables of [directory], or null if the font
         * carries neither.
         *
         * Neither is any use without the other, exactly as `glyf` and `loca` are
         * not: `CBLC` "provides embedded bitmap locators" and locates them in
         * `CBDT`, which is "a collection of bitmap data" with nothing in it that
         * says which glyph any of it belongs to. A font with one of the two is
         * refused rather than read as a font with no colour bitmaps at all.
         */
        private fun colorBitmapsOrNull(directory: TableDirectory): Pair<CblcTable, CbdtTable>? {
            val hasCblc = "CBLC" in directory
            val hasCbdt = "CBDT" in directory
            if (!hasCblc && !hasCbdt) return null
            require(hasCblc && hasCbdt) {
                "the font program has a '${if (hasCblc) "CBLC" else "CBDT"}' table but no " +
                    "'${if (hasCblc) "CBDT" else "CBLC"}' table; 'CBLC' is the index of the bitmaps in " +
                    "'CBDT', and neither table says anything on its own"
            }
            return CblcTable.parse(directory.readerOf("CBLC")) to CbdtTable.parse(directory.readerOf("CBDT"))
        }

        /**
         * The `glyf` table of [directory], read through the `loca` table that
         * locates glyphs inside it, or null if the font carries neither.
         *
         * Neither is any use without the other — "the 'glyf' table must always
         * be used in conjunction with the 'loca' and 'maxp' tables", and `loca`
         * is nothing but offsets into `glyf` — so a font with one of them is
         * refused rather than read as a font with no outlines at all.
         */
        private fun glyfOrNull(directory: TableDirectory, head: HeadTable, maxp: MaxpTable): GlyfTable? {
            val hasGlyf = "glyf" in directory
            val hasLoca = "loca" in directory
            if (!hasGlyf && !hasLoca) return null
            require(hasGlyf && hasLoca) {
                "the font program has a '${if (hasGlyf) "glyf" else "loca"}' table but no " +
                    "'${if (hasGlyf) "loca" else "glyf"}' table; 'loca' is the array of offsets into " +
                    "'glyf', and neither table says anything on its own"
            }
            val loca = LocaTable.parse(
                reader = directory.readerOf("loca"),
                numGlyphs = maxp.numGlyphs,
                longOffsets = head.hasLongLocaOffsets,
            )
            return GlyfTable.parse(directory.readerOf("glyf"), loca)
        }
    }
}
