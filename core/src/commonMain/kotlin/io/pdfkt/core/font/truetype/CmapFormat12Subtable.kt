/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.font.truetype

// OpenType 1.9.1 §"cmap" — "Format 12: Segmented coverage"

/**
 * `cmap` format 12 — "Segmented coverage", "the standard character-to-glyph-index
 * mapping subtable for fonts supporting Unicode character repertoires that
 * include supplementary-plane characters (U+10000 to U+10FFFF)".
 *
 * > Format 12 is similar to format 4 in that it defines segments for sparse
 * > representation. It differs, however, in that it uses 32-bit character codes.
 *
 * And it differs in having no indexing trick: a group is a run of consecutive
 * character codes mapped to a run of consecutive glyph ids, so the glyph is an
 * addition. The groups are held as read; the search over them is the binary one
 * their required sort order allows — "Groups must be sorted by increasing
 * startCharCode. A group's endCharCode must be less than the startCharCode of
 * the following group, if any."
 */
internal class CmapFormat12Subtable private constructor(
    private val startCharCodes: LongArray,
    private val endCharCodes: LongArray,
    private val startGlyphIds: IntArray,
) : CmapSubtable {
    override val format: Int get() = FORMAT

    /** How many `SequentialMapGroup` records the subtable holds. */
    val groupCount: Int get() = startCharCodes.size

    override fun glyphIdOf(codePoint: Int): Int {
        if (codePoint < 0) return NOTDEF
        val code = codePoint.toLong()
        var low = 0
        var high = groupCount - 1
        while (low <= high) {
            val middle = (low + high) ushr 1
            when {
                code < startCharCodes[middle] -> high = middle - 1
                code > endCharCodes[middle] -> low = middle + 1
                // "startGlyphID | Glyph index corresponding to the starting
                // character code", and "Glyph IDs for subsequent characters follow
                // in sequence".
                else -> return startGlyphIds[middle] + (code - startCharCodes[middle]).toInt()
            }
        }
        return NOTDEF
    }

    override fun toString(): String = "cmap format 12 ($groupCount groups)"

    companion object {
        /** "format | Subtable format; set to 12." */
        const val FORMAT: Int = 12

        /** format, reserved, length, language, numGroups. */
        private const val HEADER_SIZE: Int = 16

        /** startCharCode, endCharCode, startGlyphID. */
        private const val GROUP_SIZE: Int = 12

        /** Glyph ids are `uint16` throughout the format; `maxp` counts them in one. */
        private const val MAX_GLYPH_ID: Int = 0xFFFF

        private const val NOTDEF: Int = 0

        /**
         * Reads a format 12 subtable, [rest] being the bytes of the `cmap` table
         * from the start of the subtable onwards.
         */
        fun parse(rest: BigEndianReader): CmapFormat12Subtable {
            val length = rest.uint32At(4)
            require(length <= rest.size) {
                "a 'cmap' format 12 subtable declares itself $length bytes long, past the end of the " +
                    "${rest.size} bytes of 'cmap' that follow its start"
            }
            val subtable = rest.sliceAt(0, length.toInt(), "a 'cmap' format 12 subtable")

            subtable.seek(12)
            val numGroups = subtable.uint32()
            require(HEADER_SIZE + numGroups * GROUP_SIZE <= length) {
                "a 'cmap' format 12 subtable declares itself $length bytes long, too few for the " +
                    "$numGroups groups it announces"
            }
            val groupCount = numGroups.toInt()

            val startCharCodes = LongArray(groupCount)
            val endCharCodes = LongArray(groupCount)
            val startGlyphIds = IntArray(groupCount)
            for (group in 0 until groupCount) {
                val startCharCode = subtable.uint32()
                val endCharCode = subtable.uint32()
                val startGlyphId = subtable.uint32()
                require(startCharCode <= endCharCode) {
                    "group $group of a 'cmap' format 12 subtable runs from ${hex(startCharCode)} to " +
                        "${hex(endCharCode)}, backwards"
                }
                require(group == 0 || endCharCodes[group - 1] < startCharCode) {
                    "group $group of a 'cmap' format 12 subtable starts at ${hex(startCharCode)}, at or " +
                        "before the end of the group before it; the groups are required to be sorted by " +
                        "increasing startCharCode"
                }
                require(startGlyphId + (endCharCode - startCharCode) <= MAX_GLYPH_ID) {
                    "group $group of a 'cmap' format 12 subtable maps ${hex(startCharCode)}.." +
                        "${hex(endCharCode)} from glyph $startGlyphId onwards, which runs past glyph " +
                        "$MAX_GLYPH_ID; a glyph id is a uint16"
                }
                startCharCodes[group] = startCharCode
                endCharCodes[group] = endCharCode
                startGlyphIds[group] = startGlyphId.toInt()
            }
            return CmapFormat12Subtable(startCharCodes, endCharCodes, startGlyphIds)
        }
    }
}
