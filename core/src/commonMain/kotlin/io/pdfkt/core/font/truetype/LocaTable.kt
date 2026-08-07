/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.font.truetype

// OpenType 1.9.1 §"loca — Index to Location", §"head — Font header table"
// (indexToLocFormat), §"maxp — Maximum Profile table" (numGlyphs)

/**
 * `loca` — "The index to location ('loca') table stores an array of offsets to
 * the locations of glyph descriptions in the 'glyf' table, relative to the
 * beginning of that table. Offsets in the array are referenced by corresponding
 * glyph IDs."
 *
 * It is the only thing that says where a glyph is: "The 'glyf' table does not
 * include any overall table header or records providing offsets to glyph data
 * blocks."
 *
 * ## Three facts of the clause, all load-bearing here
 *
 * - **There is one offset more than there are glyphs.** "To compute the length
 *   of the last glyph description, there is an extra entry in the offsets array
 *   after the entry for the last valid glyph ID. Thus, the number of elements in
 *   the offsets array is numGlyphs + 1." So a glyph's length is a subtraction,
 *   [lengthOf], and the table cannot be read without `maxp`'s numGlyphs — the
 *   array has no count of its own.
 * - **The width of an entry is written down in another table.** "There are two
 *   formats of this table: a short format, and a long format. The format is
 *   specified by the indexToLocFormat entry in the 'head' table." In the short
 *   format "the local offset divided by 2 is stored", in the long one "the
 *   actual local offset is stored" — which is why every short-format offset this
 *   parser reports is even, and why a font in that format pads its glyph data to
 *   even boundaries.
 * - **Equal neighbours mean a glyph with nothing to draw.** "if a glyph has no
 *   outline or instructions, then loca[n] = loca[n+1]" — the space character is
 *   the everyday case, and glyph 0 is the other one the clause names. Such a
 *   glyph is not missing and not an error: it has zero bytes of description, and
 *   [hasOutline] is how the difference is asked about rather than by comparing
 *   lengths at every call site.
 */
internal class LocaTable private constructor(private val offsets: IntArray) {
    /** How many glyphs this table locates — `maxp`'s numGlyphs. */
    val numGlyphs: Int get() = offsets.size - 1

    /**
     * The offset just past the last glyph's description, which is how many bytes
     * of `glyf` the table accounts for.
     */
    val glyphDataEnd: Int get() = offsets[offsets.size - 1]

    /** Where the description of the glyph [glyphId] starts within `glyf`. */
    fun offsetOf(glyphId: Int): Int {
        requireGlyph(glyphId)
        return offsets[glyphId]
    }

    /**
     * How many bytes the description of the glyph [glyphId] occupies:
     * "The length of each glyph description is determined by the difference
     * between two consecutive entries: loca[n+1] - loca[n]."
     */
    fun lengthOf(glyphId: Int): Int {
        requireGlyph(glyphId)
        return offsets[glyphId + 1] - offsets[glyphId]
    }

    /**
     * Whether the glyph [glyphId] has a description at all, or is one of those
     * the clause describes with "then the first and second offsets should have
     * the same value".
     */
    fun hasOutline(glyphId: Int): Boolean = lengthOf(glyphId) > 0

    private fun requireGlyph(glyphId: Int) {
        require(glyphId in 0 until numGlyphs) {
            "there is no glyph $glyphId in a font of $numGlyphs glyphs (they are numbered " +
                "0..${numGlyphs - 1}, glyph 0 being .notdef)"
        }
    }

    override fun toString(): String = "loca($numGlyphs glyphs, $glyphDataEnd bytes of glyph data)"

    companion object {
        /** "The local offset divided by 2 is stored." */
        private const val SHORT_OFFSET_SCALE: Int = 2

        private const val SHORT_OFFSET_SIZE: Int = 2
        private const val LONG_OFFSET_SIZE: Int = 4

        /**
         * Reads the table [reader] covers.
         *
         * @param numGlyphs `maxp`'s field of that name; the array is one longer.
         * @param longOffsets `head`'s indexToLocFormat, as
         *   [HeadTable.hasLongLocaOffsets]: `Offset32` entries rather than
         *   `Offset16` ones.
         * @throws IllegalArgumentException if the table is too short for the
         *   font's glyphs, or its offsets do not ascend. Both are conditions the
         *   clause states outright, and a parser that read past either would
         *   produce glyph descriptions of negative or nonsensical length out of
         *   whatever bytes followed.
         */
        fun parse(reader: BigEndianReader, numGlyphs: Int, longOffsets: Boolean): LocaTable {
            require(numGlyphs >= 1) {
                "a font of $numGlyphs glyphs has no 'loca' table to read; every font has at least " +
                    "glyph 0, and the offsets array is numGlyphs + 1 entries long"
            }
            val entries = numGlyphs + 1
            val entrySize = if (longOffsets) LONG_OFFSET_SIZE else SHORT_OFFSET_SIZE
            require(entries.toLong() * entrySize <= reader.size) {
                "the 'loca' table is ${reader.size} bytes, too short for the $entries " +
                    "${entrySize * 8}-bit offsets a font of $numGlyphs glyphs needs " +
                    "(\"the number of elements in the offsets array is numGlyphs + 1\")"
            }
            // A table longer than that is not refused: a table record may record
            // the padded length rather than "the actual length of data", and the
            // entries past the last glyph describe glyphs this font does not have.

            reader.seek(0)
            val offsets = IntArray(entries)
            for (index in 0 until entries) {
                offsets[index] = if (longOffsets) {
                    val offset = reader.uint32()
                    require(offset <= Int.MAX_VALUE) {
                        "the 'loca' table locates glyph $index at offset $offset, past any 'glyf' table " +
                            "that can be held in memory"
                    }
                    offset.toInt()
                } else {
                    reader.uint16() * SHORT_OFFSET_SCALE
                }
                require(index == 0 || offsets[index] >= offsets[index - 1]) {
                    "the 'loca' table locates glyph ${index - 1} at ${offsets[index - 1]} and the next " +
                        "description at ${offsets[index]}; \"Offsets must be in ascending order, with " +
                        "loca[n] <= loca[n+1]\", and the difference between them is the length of a glyph"
                }
            }
            return LocaTable(offsets)
        }
    }
}
