/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.font.truetype

// OpenType 1.9.1 §"CBLC — Color Bitmap Location Table": "CblcHeader",
// "BitmapSize record"; §"EBLC — Embedded Bitmap Location Table": "Table
// structure", "Bit depth", "Bitmap flags", "IndexSubtableList"

/**
 * One strike — "A set of bitmaps for a face at a given size" — described by one
 * `BitmapSize` record.
 *
 * The record says how big the bitmaps are ([ppemX], [ppemY]), what a pixel of
 * them is ([bitDepth]), how a line of them is spaced ([hori], [vert]), which
 * glyphs it might cover ([startGlyphIndex], [endGlyphIndex]) and where the
 * answer to "which glyphs it actually covers" lives ([indexSubtables]). The
 * clause is explicit that the range and the coverage are different questions:
 *
 * > The startGlyphIndex and endGlyphIndex describe the minimum and maximum glyph
 * > IDs in the strike, but a strike does not necessarily contain bitmaps for all
 * > glyph IDs in this range. The IndexSubtables determine which glyphs are
 * > actually present in the EBDT table.
 *
 * @property hori "Line metrics for text rendered horizontally."
 * @property vert "Line metrics for text rendered vertically."
 * @property startGlyphIndex "Lowest glyph index for this size."
 * @property endGlyphIndex "Highest glyph index for this size."
 * @property ppemX "Horizontal pixels per em."
 * @property ppemY "Vertical pixels per em." The two may differ: "Typically,
 *   ppemX will be equal to ppemY for devices with 'square' pixels. To
 *   accommodate devices with rectangular pixels, and to allow for bitmaps with
 *   other aspect ratios, ppemX and ppemY may differ."
 * @property bitDepth 1, 2, 4 or 8 for grey levels, or the value `CBLC` adds:
 *   "the value of 32 is used to identify color bitmaps with 8 bit per channel
 *   BGRA pixel data". A colour strike's images are still whatever its index
 *   subtables' `imageFormat` says — 32 describes the pixels, not the container.
 * @property flags "Vertical or horizontal", the `Bitmap flags` enumeration; see
 *   [metricsAreHorizontal].
 * @property indexSubtables the strike's `IndexSubtableList`, in the order the
 *   font lists it, which is "sorted by firstGlyphIndex".
 */
internal class BitmapStrike(
    val hori: SbitLineMetrics,
    val vert: SbitLineMetrics,
    val startGlyphIndex: Int,
    val endGlyphIndex: Int,
    val ppemX: Int,
    val ppemY: Int,
    val bitDepth: Int,
    val flags: Int,
    val indexSubtables: List<CblcIndexSubtable>,
) {
    /**
     * Which direction the strike's `SmallGlyphMetrics` are measured in — the
     * question the flags exist to answer:
     *
     * > 0x01 HORIZONTAL_METRICS Horizontal
     * > 0x02 VERTICAL_METRICS Vertical
     *
     * A strike flagged for neither is read as horizontal. That is not a value
     * the enumeration gives a meaning to, and horizontal is the direction this
     * library lays text out in; refusing the font over a reserved-bit field
     * would cost a document its emoji for a field no glyph is drawn from.
     */
    val metricsAreHorizontal: Boolean get() = flags and VERTICAL_METRICS == 0 || flags and HORIZONTAL_METRICS != 0

    /** The line metrics of the direction [metricsAreHorizontal] names. */
    val lineMetrics: SbitLineMetrics get() = if (metricsAreHorizontal) hori else vert

    /**
     * Where the bitmap of [glyphId] is in the `CBDT` table, or null if this
     * strike has none for it.
     *
     * > After determining the strike, the rasterizer searches this array for the
     * > range containing the given glyph ID.
     *
     * The search is linear because the array is short — a subtable covers a
     * range, and a font carries "an index subtable for each range or format
     * change", which is a handful even for a font of every emoji there is.
     */
    fun locationOf(glyphId: Int): BitmapLocation? {
        if (glyphId !in startGlyphIndex..endGlyphIndex) return null
        for (subtable in indexSubtables) {
            val location = subtable.locationOf(glyphId)
            if (location != null) return location
        }
        return null
    }

    override fun toString(): String = "BitmapStrike(${ppemX}x$ppemY ppem, bit depth $bitDepth, glyphs " +
        "$startGlyphIndex..$endGlyphIndex in ${indexSubtables.size} index subtables)"

    companion object {
        /** `Offset32`, three `uint32`s, two `SbitLineMetrics`, two `uint16`s, four bytes. */
        const val SIZE: Int = 16 + 2 * SbitLineMetrics.SIZE + 8

        /** The `Bitmap flags` mask "0x01 HORIZONTAL_METRICS". */
        const val HORIZONTAL_METRICS: Int = 0x01

        /** The `Bitmap flags` mask "0x02 VERTICAL_METRICS". */
        const val VERTICAL_METRICS: Int = 0x02

        /** "the value of 32 is used to identify color bitmaps with 8 bit per channel BGRA pixel data". */
        const val COLOR_BIT_DEPTH: Int = 32
    }
}

/**
 * `CBLC` — "The CBLC table provides embedded bitmap locators. It is used
 * together with the CBDT table, which provides embedded, color bitmap glyph
 * data."
 *
 * This is half of how a colour-emoji font is read: `CBLC` says which bytes of
 * `CBDT` are which glyph's picture, and [CbdtTable] reads the picture. The pair
 * is what the `EmbeddedColorFont` policy draws from: CBDT/CBLC
 * PNG glyphs become a Type 3 font (clause 9.6.4) whose CharProcs place image
 * XObjects.
 *
 * ## It is the EBLC table, with wider pixels
 *
 * > The formats of these two tables are downward compatible extensions of the
 * > EBDT and EBLC tables used for embedded monochrome and grayscale bitmaps.
 * > … Remaining details regarding the structure of the CBDT table are identical
 * > to those defined for the EBLC table.
 *
 * So everything below the header — the `BitmapSize` record, the
 * `IndexSubtableList`, the five index formats — is the `EBLC` clause's, cited
 * where it is implemented. What `CBLC` changes is the version, the meaning of
 * `bitDepth` 32, and the image formats `CBDT` adds.
 *
 * ## The whole table is read at parse time
 *
 * Index subtables are the addresses, not the images: a font of every emoji in
 * Unicode carries some sixteen kilobytes of them in front of eleven megabytes of
 * PNG. Reading them all when the font is opened costs nothing measurable and
 * makes every later lookup a search over arrays rather than over bytes; the
 * images stay where they are and are copied one at a time, when asked for.
 *
 * @property majorVersion "Major version of the CBLC table, = 3."
 * @property minorVersion "Minor version of the CBLC table, = 0."
 * @property strikes one per `BitmapSize` record, in the order the font lists
 *   them.
 */
internal class CblcTable private constructor(
    val majorVersion: Int,
    val minorVersion: Int,
    val strikes: List<BitmapStrike>,
) {
    /**
     * The strike with the most pixels to the em, or null if the table describes
     * none.
     *
     * A PDF has no raster size to match a strike against — a page is placed on
     * whatever device prints it — so the choice that cannot be wrong is the one
     * the clause recommends for the case where scaling is needed anyway:
     *
     * > It is recommended that where possible the application downscale using
     * > the closest sized bitmap that is larger than the desired end raster
     * > size.
     *
     * Larger than every end raster size is the largest strike the font has.
     */
    val largestStrike: BitmapStrike? get() = strikes.maxByOrNull { it.ppemY }

    override fun toString(): String = "CblcTable(version $majorVersion.$minorVersion, ${strikes.size} strikes)"

    companion object {
        /** "Note that the first version of the CBLC table is 3.0." */
        const val MAJOR_VERSION: Int = 3

        /** `uint16 majorVersion`, `uint16 minorVersion`, `uint32 numSizes`. */
        private const val HEADER_SIZE: Int = 8

        /** `uint16 firstGlyphIndex`, `uint16 lastGlyphIndex`, `Offset32 indexSubtableOffset`. */
        private const val INDEX_SUBTABLE_RECORD_SIZE: Int = 8

        /**
         * Reads the `CBLC` table [reader] covers.
         *
         * @throws IllegalArgumentException if the table is not one this library
         *   can read, naming the offset within `CBLC` at which it stopped being
         *   one. A font is input from outside the library; a strike read at the
         *   wrong offset would put some other glyph's picture on the page, so
         *   every claim the table makes about its own size is checked against
         *   the bytes there are.
         */
        fun parse(reader: BigEndianReader): CblcTable {
            reader.seek(0)
            val majorVersion = reader.uint16()
            val minorVersion = reader.uint16()
            require(majorVersion == MAJOR_VERSION) {
                "the 'CBLC' table has majorVersion $majorVersion at offset 0; the first version of the " +
                    "table is $MAJOR_VERSION.0 and this library reads $MAJOR_VERSION.x"
            }
            val numSizes = reader.uint32()
            require(HEADER_SIZE + numSizes * BitmapStrike.SIZE <= reader.size) {
                "the 'CBLC' table claims $numSizes BitmapSize records, which need " +
                    "${numSizes * BitmapStrike.SIZE} bytes after its $HEADER_SIZE-byte header, but the " +
                    "table is ${reader.size} bytes"
            }

            val strikes = ArrayList<BitmapStrike>(numSizes.toInt())
            for (index in 0 until numSizes.toInt()) {
                strikes += parseStrike(reader, HEADER_SIZE + index * BitmapStrike.SIZE)
            }
            return CblcTable(majorVersion, minorVersion, strikes)
        }

        /** One `BitmapSize` record, at [at] bytes into the table, and its index subtables. */
        private fun parseStrike(reader: BigEndianReader, at: Int): BitmapStrike {
            reader.seek(at)
            val indexSubtableListOffset = reader.uint32()
            val indexSubtableListSize = reader.uint32()
            val numberOfIndexSubtables = reader.uint32()
            reader.skip(4) // colorRef — "Not used; set to 0."
            val hori = SbitLineMetrics.read(reader)
            val vert = SbitLineMetrics.read(reader)
            val startGlyphIndex = reader.uint16()
            val endGlyphIndex = reader.uint16()
            val ppemX = reader.uint8()
            val ppemY = reader.uint8()
            val bitDepth = reader.uint8()
            val flags = reader.int8()

            require(startGlyphIndex <= endGlyphIndex) {
                "the BitmapSize record at offset $at of the 'CBLC' table covers glyphs $startGlyphIndex.." +
                    "$endGlyphIndex, and its startGlyphIndex is the \"Lowest glyph index for this size\""
            }
            require(indexSubtableListOffset + indexSubtableListSize <= reader.size) {
                "the BitmapSize record at offset $at of the 'CBLC' table puts its IndexSubtableList at " +
                    "offset $indexSubtableListOffset with $indexSubtableListSize bytes, past the end of " +
                    "the ${reader.size}-byte table"
            }
            val list = reader.sliceAt(
                offset = indexSubtableListOffset.toInt(),
                length = indexSubtableListSize.toInt(),
                what = "the IndexSubtableList at offset $indexSubtableListOffset of the 'CBLC' table",
            )
            require(numberOfIndexSubtables * INDEX_SUBTABLE_RECORD_SIZE <= list.size) {
                "the BitmapSize record at offset $at of the 'CBLC' table claims $numberOfIndexSubtables " +
                    "index subtables, whose records need " +
                    "${numberOfIndexSubtables * INDEX_SUBTABLE_RECORD_SIZE} bytes, in an " +
                    "IndexSubtableList of ${list.size} bytes"
            }

            val subtables = ArrayList<CblcIndexSubtable>(numberOfIndexSubtables.toInt())
            for (index in 0 until numberOfIndexSubtables.toInt()) {
                list.seek(index * INDEX_SUBTABLE_RECORD_SIZE)
                val firstGlyphIndex = list.uint16()
                val lastGlyphIndex = list.uint16()
                val subtableOffset = list.uint32()
                require(firstGlyphIndex <= lastGlyphIndex) {
                    "IndexSubtableRecord $index of ${list.what} covers glyphs $firstGlyphIndex.." +
                        "$lastGlyphIndex, and lastGlyphIndex is the \"Last glyph ID of this range " +
                        "(inclusive)\""
                }
                subtables += CblcIndexSubtable.parse(
                    list = list,
                    offset = subtableOffset.toInt(),
                    firstGlyphIndex = firstGlyphIndex,
                    lastGlyphIndex = lastGlyphIndex,
                    what = "the index subtable at offset " +
                        "${indexSubtableListOffset + subtableOffset} of the 'CBLC' table",
                )
            }
            return BitmapStrike(
                hori = hori,
                vert = vert,
                startGlyphIndex = startGlyphIndex,
                endGlyphIndex = endGlyphIndex,
                ppemX = ppemX,
                ppemY = ppemY,
                bitDepth = bitDepth,
                flags = flags,
                indexSubtables = subtables,
            )
        }
    }
}
