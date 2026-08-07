/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.font.truetype

// OpenType 1.9.1 §"EBLC — Embedded Bitmap Location Table": "IndexSubtableList",
// "IndexSubtable formats", "IndexSubtableFormat1".."IndexSubtableFormat5";
// §"CBLC — Color Bitmap Location Table"

/**
 * Where one glyph's bitmap is in the `CBDT` table, and the metrics that came
 * with the address.
 *
 * @property glyphId the glyph this locates a bitmap for.
 * @property imageFormat the `IndexSubHeader` field of that name — "Format of
 *   EBDT image data", which is what [CbdtTable] reads the bytes as.
 * @property offset where the data begins, counted "from the beginning of the
 *   EBDT table" as `imageDataOffset` is, with the glyph's own offset added.
 * @property length how many bytes belong to this glyph. For the variable-metrics
 *   index formats this is the distance to the next glyph's data and may include
 *   the padding that keeps those offsets aligned; for the constant-metrics
 *   formats it is `imageSize`. Either way the image format's own `dataLen` is
 *   what says how much of it is the image.
 * @property metrics the glyph metrics if the index subtable carried them —
 *   formats 2 and 5 keep "a single copy of the metrics information in the
 *   IndexSubTable in the EBLC" — and null if they are stored with the image
 *   data instead.
 */
internal class BitmapLocation(
    val glyphId: Int,
    val imageFormat: Int,
    val offset: Int,
    val length: Int,
    val metrics: BigGlyphMetrics?,
) {
    override fun toString(): String =
        "BitmapLocation(glyph $glyphId, image format $imageFormat, $length bytes at $offset of 'CBDT')"
}

/**
 * One `IndexSubtable`: which bytes of `CBDT` hold the bitmap of each glyph in
 * one range.
 *
 * > Each strike has an IndexSubtableList to support various formats and
 * > discontiguous ranges of bitmaps. Each IndexSubtable provides the locations
 * > of the bitmap data for one or more glyphs within the CBDT table, and may
 * > optionally provide glyph metrics to use for that set of glyphs.
 *
 * The five formats differ along two axes and in nothing else, which is why they
 * are five classes behind one [locationOf] and not five parsers:
 *
 * | | contiguous range | sparse glyph ids |
 * |---|---|---|
 * | metrics per glyph, in `CBDT` | [Format1] (`Offset32`), [Format3] (`Offset16`) | [Format4] |
 * | metrics once, here | [Format2] | [Format5] |
 *
 * > The choice of format that is used should be made with the aim of minimizing
 * > the size of the font file.
 *
 * ## A glyph in the range need not have a bitmap
 *
 * [locationOf] answers null for a glyph the subtable covers but has no image
 * for, which the format states in two different ways. For the offset formats it
 * is a zero-length run:
 *
 * > A small number of missing glyphs can be efficiently represented in formats 1
 * > or 3 by having the offset for the missing glyph be followed by the same
 * > offset for the next glyph, thus indicating a data size of zero.
 *
 * For the sparse formats it is simply absence from the array. Null rather than
 * an error, because a font not having a bitmap for a glyph is a fact about the
 * font, not a fault in it — the caller drawing text is the one that knows which
 * character it was and can refuse in those terms.
 *
 * @property what where this subtable is, for the error messages — the offset
 *   within the `CBLC` table it was read at.
 */
internal sealed class CblcIndexSubtable(
    val firstGlyphIndex: Int,
    val lastGlyphIndex: Int,
    val imageFormat: Int,
    /** `imageDataOffset` — "Offset to image data in EBDT table". */
    val imageDataOffset: Long,
    val what: String,
) {
    /** The `indexFormat` field — "Format of the IndexSubtable", 1..5. */
    abstract val indexFormat: Int

    /**
     * Where the bitmap of [glyphId] is, or null if this subtable does not cover
     * that glyph or covers it with no image data.
     */
    fun locationOf(glyphId: Int): BitmapLocation? {
        if (glyphId !in firstGlyphIndex..lastGlyphIndex) return null
        return locationInRange(glyphId)
    }

    protected abstract fun locationInRange(glyphId: Int): BitmapLocation?

    /**
     * A location from an offset and a length that are still `uint32`s of the
     * font file.
     *
     * The two are added to `imageDataOffset` — "Since the sbitOffsets elements
     * are added to the imageDataOffset base address in the IndexSubHeader…" —
     * and the sum is where the bounds of `CBDT` are checked, by [CbdtTable].
     * What is checked here is only that the address is one this library can
     * hold: a `uint32` reaches 4 GiB and an array index does not.
     */
    protected fun locationAt(glyphId: Int, offset: Long, length: Long, metrics: BigGlyphMetrics?): BitmapLocation? {
        require(length >= 0) {
            "$what: the bitmap data of glyph $glyphId is $length bytes long, which is the offset of the " +
                "next glyph's data minus this one's; the specification requires the image data of a " +
                "range to be \"stored contiguously and in glyph ID order\""
        }
        if (length == 0L) return null
        val start = imageDataOffset + offset
        require(start + length <= Int.MAX_VALUE) {
            "$what: the bitmap data of glyph $glyphId is $length bytes at offset $start of the 'CBDT' " +
                "table, past the ${Int.MAX_VALUE} bytes this library can address in one table"
        }
        return BitmapLocation(glyphId, imageFormat, start.toInt(), length.toInt(), metrics)
    }

    override fun toString(): String =
        "CblcIndexSubtable(format $indexFormat, glyphs $firstGlyphIndex..$lastGlyphIndex, " +
            "image format $imageFormat)"

    /**
     * > Format 1 can be used when glyphs have variable metrics. Offsets for the
     * > bitmap data are 4-byte aligned.
     *
     * @property sbitOffsets `Offset32 sbitOffsets[numOffsets]` — "Offsets to
     *   bitmap data for each glyph in the range", one longer than the range:
     *   "One extra entry is required to provide the size of data for the last
     *   glyph. Thus, numOffsets = lastGlyphIndex – firstGlyphIndex + 2."
     */
    class Format1(
        firstGlyphIndex: Int,
        lastGlyphIndex: Int,
        imageFormat: Int,
        imageDataOffset: Long,
        what: String,
        private val sbitOffsets: LongArray,
    ) : CblcIndexSubtable(firstGlyphIndex, lastGlyphIndex, imageFormat, imageDataOffset, what) {
        override val indexFormat: Int get() = FORMAT

        override fun locationInRange(glyphId: Int): BitmapLocation? {
            val index = glyphId - firstGlyphIndex
            val offset = sbitOffsets[index]
            return locationAt(glyphId, offset, sbitOffsets[index + 1] - offset, metrics = null)
        }

        companion object {
            const val FORMAT: Int = 1
        }
    }

    /**
     * > Format 2 can be used when glyphs have identical metrics. The bitmap data
     * > for all glyphs will be the same size, so an array of offsets is not
     * > required.
     *
     * With no offsets stored, the address of a glyph's data is the only one
     * consistent with a constant size: the images of the range lie one after
     * another in glyph order, so the *n*th glyph of the range starts *n* ×
     * [imageSize] into it. The clause states the halves of that separately —
     * "For the constant-metrics formats (2 and 5) the image data size is
     * constant, and is given in the imageSize field", and, of the variable
     * formats, that "image data must be stored contiguously and in glyph ID
     * order".
     *
     * @property imageSize "All the glyphs are of the same size."
     * @property bigMetrics "All glyphs have the same metrics; glyph data may be
     *   compressed, byte-aligned, or bit-aligned."
     */
    class Format2(
        firstGlyphIndex: Int,
        lastGlyphIndex: Int,
        imageFormat: Int,
        imageDataOffset: Long,
        what: String,
        private val imageSize: Long,
        private val bigMetrics: BigGlyphMetrics,
    ) : CblcIndexSubtable(firstGlyphIndex, lastGlyphIndex, imageFormat, imageDataOffset, what) {
        override val indexFormat: Int get() = FORMAT

        override fun locationInRange(glyphId: Int): BitmapLocation? =
            locationAt(glyphId, (glyphId - firstGlyphIndex) * imageSize, imageSize, bigMetrics)

        companion object {
            const val FORMAT: Int = 2
        }
    }

    /**
     * Format 1 with `Offset16` instead of `Offset32`:
     *
     * > The only difference between formats 1 and 3 is the size of the
     * > sbitOffsets array elements: format 1 uses uint32s while format 3 uses
     * > uint16s. Therefore format 1 can cover a greater range (> 64k bytes)
     * > while format 3 saves more space in the EBLC table.
     */
    class Format3(
        firstGlyphIndex: Int,
        lastGlyphIndex: Int,
        imageFormat: Int,
        imageDataOffset: Long,
        what: String,
        private val sbitOffsets: IntArray,
    ) : CblcIndexSubtable(firstGlyphIndex, lastGlyphIndex, imageFormat, imageDataOffset, what) {
        override val indexFormat: Int get() = FORMAT

        override fun locationInRange(glyphId: Int): BitmapLocation? {
            val index = glyphId - firstGlyphIndex
            val offset = sbitOffsets[index].toLong()
            return locationAt(glyphId, offset, sbitOffsets[index + 1].toLong() - offset, metrics = null)
        }

        companion object {
            const val FORMAT: Int = 3
        }
    }

    /**
     * > Format 4 can be used when glyphs have variable metrics and when the glyph
     * > IDs are sparse, not in a contiguous range. Format 4 uses an array of
     * > GlyphIdOffsetPair records. One extra record is stored to represent the
     * > size of the image data for the last glyph.
     *
     * @property glyphIds `glyphID` of each `GlyphIdOffsetPair`, the last of them
     *   belonging to the extra record and naming no glyph of this range.
     * @property sbitOffsets `sbitOffset` — "Location in EBDT" — of each pair.
     */
    class Format4(
        firstGlyphIndex: Int,
        lastGlyphIndex: Int,
        imageFormat: Int,
        imageDataOffset: Long,
        what: String,
        private val glyphIds: IntArray,
        private val sbitOffsets: IntArray,
    ) : CblcIndexSubtable(firstGlyphIndex, lastGlyphIndex, imageFormat, imageDataOffset, what) {
        override val indexFormat: Int get() = FORMAT

        override fun locationInRange(glyphId: Int): BitmapLocation? {
            // The array is one longer than the glyph count: its last entry is the
            // sentinel that sizes the last glyph, not a glyph of its own.
            val index = indexOfGlyph(glyphIds, glyphId, until = glyphIds.size - 1) ?: return null
            val offset = sbitOffsets[index].toLong()
            return locationAt(glyphId, offset, sbitOffsets[index + 1].toLong() - offset, metrics = null)
        }

        companion object {
            const val FORMAT: Int = 4

            /** `uint16 glyphID`, `Offset16 sbitOffset`. */
            const val PAIR_SIZE: Int = 4
        }
    }

    /**
     * > Format 5 can be used when glyphs have identical metrics, but the glyph
     * > IDs are sparse, not in a contiguous range.
     *
     * Constant size and a listed order, so a glyph's data starts at its position
     * in [glyphIds] times [imageSize] — the same reasoning as [Format2], with
     * the array taking the place of the contiguous range.
     *
     * @property glyphIds "One per glyph, sorted by glyph ID."
     */
    class Format5(
        firstGlyphIndex: Int,
        lastGlyphIndex: Int,
        imageFormat: Int,
        imageDataOffset: Long,
        what: String,
        private val imageSize: Long,
        private val bigMetrics: BigGlyphMetrics,
        private val glyphIds: IntArray,
    ) : CblcIndexSubtable(firstGlyphIndex, lastGlyphIndex, imageFormat, imageDataOffset, what) {
        override val indexFormat: Int get() = FORMAT

        override fun locationInRange(glyphId: Int): BitmapLocation? {
            val index = indexOfGlyph(glyphIds, glyphId, until = glyphIds.size) ?: return null
            return locationAt(glyphId, index * imageSize, imageSize, bigMetrics)
        }

        companion object {
            const val FORMAT: Int = 5
        }
    }

    companion object {
        /** `uint16 indexFormat`, `uint16 imageFormat`, `Offset32 imageDataOffset`. */
        private const val INDEX_SUB_HEADER_SIZE: Int = 8

        /**
         * Reads the subtable [offset] bytes into the `IndexSubtableList` [list]
         * covers, for the glyph range its record gave.
         *
         * The subtable has no length field: an `IndexSubtableRecord` says where
         * one starts and nothing about where it ends, and the sizes of the
         * arrays follow from the glyph range or from a count inside the subtable
         * itself. So the window is the rest of the list, whose size the
         * `BitmapSize` record does state — "The indexSubtableListSize is the
         * total size in bytes of the IndexSubtableList data, including the array
         * of IndexSubtables" — and an array that runs past it is refused by
         * [BigEndianReader] naming the field that did it.
         *
         * @param what where the subtable is, in the terms the errors should
         *   name it by; see [CblcIndexSubtable.what].
         * @throws IllegalArgumentException for an `indexFormat` outside 1..5, or
         *   arrays that do not fit the list.
         */
        fun parse(
            list: BigEndianReader,
            offset: Int,
            firstGlyphIndex: Int,
            lastGlyphIndex: Int,
            what: String,
        ): CblcIndexSubtable {
            require(offset in 0..list.size - INDEX_SUB_HEADER_SIZE) {
                "$what: an IndexSubHeader is $INDEX_SUB_HEADER_SIZE bytes and this one starts $offset " +
                    "bytes into an IndexSubtableList of ${list.size} bytes"
            }
            val reader = list.sliceAt(offset, list.size - offset, what)
            val indexFormat = reader.uint16()
            val imageFormat = reader.uint16()
            val imageDataOffset = reader.uint32()
            val glyphsInRange = lastGlyphIndex - firstGlyphIndex + 1

            return when (indexFormat) {
                Format1.FORMAT -> Format1(
                    firstGlyphIndex,
                    lastGlyphIndex,
                    imageFormat,
                    imageDataOffset,
                    what,
                    // "numOffsets = lastGlyphIndex – firstGlyphIndex + 2"
                    sbitOffsets = LongArray(glyphsInRange + 1) { reader.uint32() },
                )

                Format2.FORMAT -> Format2(
                    firstGlyphIndex,
                    lastGlyphIndex,
                    imageFormat,
                    imageDataOffset,
                    what,
                    imageSize = reader.uint32(),
                    bigMetrics = BigGlyphMetrics.read(reader),
                )

                Format3.FORMAT -> Format3(
                    firstGlyphIndex,
                    lastGlyphIndex,
                    imageFormat,
                    imageDataOffset,
                    what,
                    sbitOffsets = IntArray(glyphsInRange + 1) { reader.uint16() },
                )

                Format4.FORMAT -> parseFormat4(
                    reader,
                    firstGlyphIndex,
                    lastGlyphIndex,
                    imageFormat,
                    imageDataOffset,
                    what,
                )

                Format5.FORMAT -> parseFormat5(
                    reader,
                    firstGlyphIndex,
                    lastGlyphIndex,
                    imageFormat,
                    imageDataOffset,
                    what,
                )

                else -> throw IllegalArgumentException(
                    "$what: its indexFormat is $indexFormat, and the specification defines " +
                        "${Format1.FORMAT}..${Format5.FORMAT}",
                )
            }
        }

        private fun parseFormat4(
            reader: BigEndianReader,
            firstGlyphIndex: Int,
            lastGlyphIndex: Int,
            imageFormat: Int,
            imageDataOffset: Long,
            what: String,
        ): Format4 {
            val numGlyphs = reader.uint32()
            // "glyphArray[numGlyphs + 1] | One per glyph." — the array is read
            // before it is allocated so a count of four billion cannot be one.
            require((numGlyphs + 1) * Format4.PAIR_SIZE <= reader.size - reader.position) {
                "$what: its numGlyphs is $numGlyphs, which needs ${(numGlyphs + 1) * Format4.PAIR_SIZE} " +
                    "bytes of GlyphIdOffsetPair records at offset ${reader.position} of a " +
                    "${reader.size}-byte window"
            }
            val count = numGlyphs.toInt() + 1
            val glyphIds = IntArray(count)
            val sbitOffsets = IntArray(count)
            for (index in 0 until count) {
                glyphIds[index] = reader.uint16()
                sbitOffsets[index] = reader.uint16()
            }
            requireSorted(glyphIds, what, "the glyphID fields of its GlyphIdOffsetPair records")
            return Format4(
                firstGlyphIndex,
                lastGlyphIndex,
                imageFormat,
                imageDataOffset,
                what,
                glyphIds,
                sbitOffsets,
            )
        }

        private fun parseFormat5(
            reader: BigEndianReader,
            firstGlyphIndex: Int,
            lastGlyphIndex: Int,
            imageFormat: Int,
            imageDataOffset: Long,
            what: String,
        ): Format5 {
            val imageSize = reader.uint32()
            val bigMetrics = BigGlyphMetrics.read(reader)
            val numGlyphs = reader.uint32()
            require(numGlyphs * 2 <= reader.size - reader.position) {
                "$what: its numGlyphs is $numGlyphs, which needs ${numGlyphs * 2} bytes of glyphIdArray " +
                    "at offset ${reader.position} of a ${reader.size}-byte window"
            }
            val glyphIds = IntArray(numGlyphs.toInt()) { reader.uint16() }
            requireSorted(glyphIds, what, "its glyphIdArray")
            return Format5(
                firstGlyphIndex,
                lastGlyphIndex,
                imageFormat,
                imageDataOffset,
                what,
                imageSize,
                bigMetrics,
                glyphIds,
            )
        }
    }
}

/**
 * Checks the ordering the sparse formats state — "One per glyph, sorted by glyph
 * ID" — because it is what [indexOfGlyph] searches by, and an unsorted array
 * would answer "no bitmap" for glyphs that have one.
 */
private fun requireSorted(glyphIds: IntArray, what: String, field: String) {
    for (index in 1 until glyphIds.size) {
        require(glyphIds[index - 1] < glyphIds[index]) {
            "$what: $field are not sorted by glyph ID — entry ${index - 1} is ${glyphIds[index - 1]} " +
                "and entry $index is ${glyphIds[index]}"
        }
    }
}

/**
 * The position of [glyphId] in the first [until] entries of [glyphIds], or null
 * if it is not among them. A binary search, which the arrays being "sorted by
 * glyph ID" is what makes possible.
 */
private fun indexOfGlyph(glyphIds: IntArray, glyphId: Int, until: Int): Int? {
    var low = 0
    var high = until - 1
    while (low <= high) {
        val middle = (low + high) ushr 1
        val candidate = glyphIds[middle]
        when {
            candidate < glyphId -> low = middle + 1
            candidate > glyphId -> high = middle - 1
            else -> return middle
        }
    }
    return null
}
