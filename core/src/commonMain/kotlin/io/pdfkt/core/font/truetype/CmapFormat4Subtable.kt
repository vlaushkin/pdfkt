/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.font.truetype

// OpenType 1.9.1 §"cmap" — "Format 4: Segment mapping to delta values"

/**
 * `cmap` format 4 — "Segment mapping to delta values", "the standard
 * character-to-glyph-index mapping subtable for fonts that support only Unicode
 * Basic Multilingual Plane characters (U+0000 to U+FFFF)".
 *
 * The segments are held as the specification lays them out — four parallel
 * arrays plus a glyph id array — and the glyph id array is left in the font
 * bytes rather than copied out, because the way format 4 indexes it is not by
 * index but by address:
 *
 * > If the idRangeOffset value for the segment is not 0, the mapping of character
 * > codes relies on glyphIdArray. The character code offset from startCode is
 * > added to the idRangeOffset value. This sum is used as an offset from the
 * > current location within idRangeOffset itself to index out the correct
 * > glyphIdArray value. This obscure indexing trick works because glyphIdArray
 * > immediately follows idRangeOffset in the font file.
 *
 * [glyphIdOf] therefore computes the address the clause's C expression computes,
 * from the position of `idRangeOffset[i]` in the subtable, and reads a `uint16`
 * there. Rewriting the trick as an index into a copied array would be a second
 * definition of the same thing, and only one of the two could be checked against
 * the clause.
 *
 * `searchRange`, `entrySelector` and `rangeShift` are read past: they precompute
 * a binary search over `endCode`, and the specification asks parsers to derive
 * what they need from `segCountX2` instead — "incorrect values could potentially
 * be used as an attack vector against some implementations".
 */
internal class CmapFormat4Subtable private constructor(
    private val subtable: BigEndianReader,
    private val segCount: Int,
    private val endCode: IntArray,
    private val startCode: IntArray,
    private val idDelta: IntArray,
    private val idRangeOffset: IntArray,
) : CmapSubtable {
    override val format: Int get() = FORMAT

    override fun glyphIdOf(codePoint: Int): Int {
        // "This is the standard character-to-glyph-index mapping subtable for
        // fonts that support only Unicode Basic Multilingual Plane characters
        // (U+0000 to U+FFFF)." Nothing above the BMP is expressible in a uint16
        // endCode, so nothing above it is mapped.
        if (codePoint < 0 || codePoint > MAX_BMP_CODE_POINT) return NOTDEF

        // "You search for the first endCode that is greater than or equal to the
        // character code you want to map."
        val segment = firstSegmentEndingAtOrAfter(codePoint)
        if (segment < 0) return NOTDEF

        // "If the corresponding startCode is less than or equal to the character
        // code, then you use the corresponding idDelta and idRangeOffset to map
        // the character code to a glyph index (otherwise, the missingGlyph is
        // returned)."
        if (startCode[segment] > codePoint) return NOTDEF

        if (idRangeOffset[segment] == 0) {
            // "If the idRangeOffset is 0, the idDelta value is added directly to
            // the character code offset (i.e. idDelta[i] + c) to get the
            // corresponding glyph index. Again, the idDelta arithmetic is modulo
            // 65536. If the result after adding idDelta[i] + c is less than zero,
            // add 65536 to obtain a valid glyph ID."
            return (idDelta[segment] + codePoint) and MODULO_65536_MASK
        }

        // glyphId = *(idRangeOffset[i]/2 + (c - startCode[i]) + &idRangeOffset[i]),
        // in bytes rather than in uint16 words.
        val fieldAt = idRangeOffsetArrayAt(segCount) + 2 * segment
        val address = fieldAt + idRangeOffset[segment] + 2 * (codePoint - startCode[segment])
        val glyphId = subtable.uint16At(address)

        // "If the value obtained from the indexing operation is not 0 (which
        // indicates missingGlyph), idDelta[i] is added to it to get the glyph
        // index. The idDelta arithmetic is modulo 65536."
        if (glyphId == NOTDEF) return NOTDEF
        return (glyphId + idDelta[segment]) and MODULO_65536_MASK
    }

    /**
     * The index of the first segment whose `endCode` is at or after [codePoint],
     * or −1 if there is none.
     *
     * "The segments are sorted in order of increasing endCode values", so this is
     * a binary search — the one the discarded `searchRange` fields describe,
     * derived from `segCount` as the specification recommends.
     */
    private fun firstSegmentEndingAtOrAfter(codePoint: Int): Int {
        var low = 0
        var high = segCount - 1
        var found = -1
        while (low <= high) {
            val middle = (low + high) ushr 1
            if (endCode[middle] >= codePoint) {
                found = middle
                high = middle - 1
            } else {
                low = middle + 1
            }
        }
        return found
    }

    override fun toString(): String = "cmap format 4 ($segCount segments)"

    companion object {
        /** "format | Format number is set to 4." */
        const val FORMAT: Int = 4

        /** format, length, language, segCountX2, searchRange, entrySelector, rangeShift. */
        private const val HEADER_SIZE: Int = 14

        /** "reservedPad | Set to 0", between endCode[] and startCode[]. */
        private const val RESERVED_PAD_SIZE: Int = 2

        /** endCode, startCode, idDelta and idRangeOffset, one `uint16` each per segment. */
        private const val ARRAYS_SIZE_PER_SEGMENT: Int = 8

        private const val MAX_BMP_CODE_POINT: Int = 0xFFFF

        /** "The idDelta arithmetic is modulo 65536", as a mask on a non-negative sum. */
        private const val MODULO_65536_MASK: Int = 0xFFFF

        private const val NOTDEF: Int = 0

        /**
         * Where `idRangeOffset[0]` sits in the subtable: after the header, the
         * `segCount` end codes, the reserved pad, and the `segCount` start codes
         * and deltas.
         */
        private fun idRangeOffsetArrayAt(segCount: Int): Int =
            HEADER_SIZE + 2 * segCount + RESERVED_PAD_SIZE + 4 * segCount

        /** The smallest a subtable of [segCount] segments can be. */
        private fun sizeOf(segCount: Int): Int = HEADER_SIZE + RESERVED_PAD_SIZE + ARRAYS_SIZE_PER_SEGMENT * segCount

        /**
         * Reads a format 4 subtable, [rest] being the bytes of the `cmap` table
         * from the start of the subtable onwards.
         */
        fun parse(rest: BigEndianReader): CmapFormat4Subtable {
            val length = rest.uint16At(2)
            require(length >= HEADER_SIZE) {
                "a 'cmap' format 4 subtable declares itself $length bytes long, shorter than its own " +
                    "$HEADER_SIZE-byte header"
            }
            val subtable = rest.sliceAt(0, length, "a 'cmap' format 4 subtable")

            subtable.seek(6)
            val segCountX2 = subtable.uint16()
            require(segCountX2 >= 2 && segCountX2 % 2 == 0) {
                "a 'cmap' format 4 subtable gives segCountX2 as $segCountX2; it is twice the number of " +
                    "segments, and the last segment — start and end 0xFFFF — is required to be present"
            }
            val segCount = segCountX2 / 2
            require(sizeOf(segCount) <= length) {
                "a 'cmap' format 4 subtable declares itself $length bytes long, too few for the " +
                    "${sizeOf(segCount)} bytes the $segCount segments its segCountX2 of $segCountX2 " +
                    "announces take"
            }

            // The four parallel arrays, in the order the specification lists them,
            // read straight through from the end of the header — which is what
            // makes the reservedPad between endCode and startCode load-bearing.
            subtable.seek(HEADER_SIZE) // past searchRange, entrySelector, rangeShift — derived, not trusted
            val endCode = IntArray(segCount)
            val startCode = IntArray(segCount)
            val idDelta = IntArray(segCount)
            val idRangeOffset = IntArray(segCount)
            for (segment in 0 until segCount) {
                endCode[segment] = subtable.uint16()
            }
            subtable.skip(RESERVED_PAD_SIZE)
            for (segment in 0 until segCount) {
                startCode[segment] = subtable.uint16()
            }
            for (segment in 0 until segCount) {
                idDelta[segment] = subtable.int16()
            }
            for (segment in 0 until segCount) {
                idRangeOffset[segment] = subtable.uint16()
            }

            // "For the search to terminate, the final startCode and endCode values
            // must be 0xFFFF."
            require(endCode[segCount - 1] == MAX_BMP_CODE_POINT) {
                "a 'cmap' format 4 subtable ends at ${hex(endCode[segCount - 1].toLong())} rather than " +
                    "0xffff; the specification requires a final segment ending at 0xFFFF for the search " +
                    "to terminate"
            }
            for (segment in 0 until segCount) {
                require(startCode[segment] <= endCode[segment]) {
                    "segment $segment of a 'cmap' format 4 subtable runs from " +
                        "${hex(startCode[segment].toLong())} to ${hex(endCode[segment].toLong())}, " +
                        "backwards"
                }
                require(segment == 0 || endCode[segment - 1] < startCode[segment]) {
                    "segment $segment of a 'cmap' format 4 subtable starts at " +
                        "${hex(startCode[segment].toLong())}, at or before the end of the segment before " +
                        "it; the segments are required to be sorted by increasing endCode"
                }
            }

            return CmapFormat4Subtable(subtable, segCount, endCode, startCode, idDelta, idRangeOffset)
        }
    }
}
