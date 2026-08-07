/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.font.truetype

// OpenType 1.9.1 §"hmtx — Horizontal metrix table"
// ISO 32000-2:2020 §9.2.4 "Glyph positioning and metrics"

/**
 * `hmtx` — "The horizontal metrics ('hmtx') table provides glyph advance widths
 * and left side bearings."
 *
 * The advance width is the number §9.2.4 calls a width — "the distance the
 * current text position shall move (by translating text space) when the glyph
 * is painted" — and the specification is unambiguous that this table is where it
 * comes from, whatever the outlines are: "The advance width is always obtained
 * from the 'hmtx' table."
 *
 * ## The table is shorter than the number of glyphs, on purpose
 *
 * `hmtx` has no length of its own and no count in it. It is two arrays whose
 * sizes are stated in two other tables — `numberOfHMetrics` in `hhea`,
 * `numGlyphs` in `maxp` — which is why this parse takes both:
 *
 * > As an optimization, the number of records can be less than the number of
 * > glyphs, in which case the advance width value of the last record applies to
 * > all remaining glyph IDs. This can be useful in monospaced fonts, or in fonts
 * > that have a large number of glyphs with the same advance width (provided the
 * > glyphs are ordered appropriately).
 *
 * Left side bearings are not folded in the same way: the glyphs past the last
 * record keep their own, in a second array "derived from the numGlyphs field in
 * the 'maxp' table minus numberOfHMetrics".
 */
internal class HmtxTable private constructor(
    private val advanceWidths: IntArray,
    private val leftSideBearings: IntArray,
) {
    /** How many glyphs this table describes — `maxp`'s numGlyphs. */
    val numGlyphs: Int get() = leftSideBearings.size

    /** How many `LongHorMetric` records the table holds — `hhea`'s numberOfHMetrics. */
    val numberOfHMetrics: Int get() = advanceWidths.size

    /**
     * "Advance width, in font design units" of the glyph [glyphId], which is the
     * last record's for every glyph past the last record.
     *
     * @throws IllegalArgumentException if [glyphId] is not a glyph of this font.
     *   A glyph id out of range is a lookup that has gone wrong somewhere above
     *   — a `cmap` disagreeing with `maxp`, a subset built against the wrong
     *   font — and answering it with a plausible width would put the error on
     *   the page instead of in the caller's face.
     */
    fun advanceWidthOf(glyphId: Int): Int {
        requireGlyph(glyphId)
        return advanceWidths[minOf(glyphId, advanceWidths.size - 1)]
    }

    /** "Glyph left side bearing, in font design units" of the glyph [glyphId]. */
    fun leftSideBearingOf(glyphId: Int): Int {
        requireGlyph(glyphId)
        return leftSideBearings[glyphId]
    }

    private fun requireGlyph(glyphId: Int) {
        require(glyphId in 0 until numGlyphs) {
            "there is no glyph $glyphId in a font of $numGlyphs glyphs (they are numbered " +
                "0..${numGlyphs - 1}, glyph 0 being .notdef)"
        }
    }

    override fun toString(): String = "hmtx($numberOfHMetrics records for $numGlyphs glyphs)"

    companion object {
        /**
         * Reads the table [reader] covers, given the two counts that describe its
         * shape.
         *
         * @param numberOfHMetrics `hhea`'s field of that name.
         * @param numGlyphs `maxp`'s field of that name.
         */
        fun parse(reader: BigEndianReader, numberOfHMetrics: Int, numGlyphs: Int): HmtxTable {
            require(numberOfHMetrics in 1..numGlyphs) {
                "the font's 'hhea' table gives numberOfHMetrics as $numberOfHMetrics while its 'maxp' " +
                    "table gives numGlyphs as $numGlyphs; there is one record per glyph at most, and the " +
                    "size of the left side bearing array is numGlyphs minus numberOfHMetrics"
            }
            reader.seek(0)
            val advanceWidths = IntArray(numberOfHMetrics)
            val leftSideBearings = IntArray(numGlyphs)
            for (glyphId in 0 until numberOfHMetrics) {
                advanceWidths[glyphId] = reader.uint16() // UFWORD advanceWidth
                leftSideBearings[glyphId] = reader.int16() // FWORD lsb
            }
            for (glyphId in numberOfHMetrics until numGlyphs) {
                leftSideBearings[glyphId] = reader.int16() // FWORD leftSideBearings[]
            }
            return HmtxTable(advanceWidths, leftSideBearings)
        }
    }
}
