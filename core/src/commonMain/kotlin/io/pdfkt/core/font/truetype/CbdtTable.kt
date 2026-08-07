/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.font.truetype

// OpenType 1.9.1 §"CBDT — Color Bitmap Data Table": "Table structure",
// "Compressed color bitmaps", "Glyph metrics", "Glyph bitmap data formats"
// ISO/IEC 15948 §5.2 "PNG signature"

/**
 * One glyph's colour bitmap: the PNG the font stores for it, and where that
 * picture goes relative to the origin.
 *
 * The bytes are handed on untouched. `CBDT`'s compressed formats say what they
 * are — "Images for each individual glyph may be stored as straight PNG data, as
 * specified in ISO/IEC 15948" — and a PDF embeds a PNG's *pixels*, so decoding
 * belongs to the image path (`PngImage`), not to a font parser. What this class
 * guarantees is that [png] is the whole of what the font stored and nothing
 * around it.
 *
 * @property glyphId the glyph the font stores this picture for.
 * @property ppemX the strike's "Horizontal pixels per em".
 * @property ppemY the strike's "Vertical pixels per em" — the divisor that turns
 *   every pixel measurement here into a fraction of an em.
 * @property metrics the glyph's metrics in the strike's direction, wherever they
 *   came from: the image data (formats 17 and 18) or the index subtable (format
 *   19).
 * @property bigMetrics the two-direction record when that is what the font
 *   stored, and null when it stored one direction's worth.
 * @property png "Raw PNG data", `dataLen` bytes of it.
 */
internal class ColorBitmapGlyph(
    val glyphId: Int,
    val ppemX: Int,
    val ppemY: Int,
    val metrics: SmallGlyphMetrics,
    val bigMetrics: BigGlyphMetrics?,
    val png: ByteArray,
) {
    override fun toString(): String = "ColorBitmapGlyph(glyph $glyphId, ${metrics.width}x${metrics.height} " +
        "at $ppemX x $ppemY ppem, ${png.size} bytes of PNG)"
}

/**
 * `CBDT` — "The CBDT table is used to embed color bitmap glyph data. It is used
 * together with the CBLC table, which provides embedded bitmap locators."
 *
 * The table is a header and then, in the clause's words, nothing but bytes:
 *
 * > The rest of the CBDT table is a collection of bitmap data. The data can be
 * > presented in three possible formats, indicated by information in the CBLC
 * > table.
 *
 * Which is why this class does not parse the table: it holds a window over it
 * and reads one glyph when a [BitmapLocation] from [CblcTable] says where. There
 * is no other way in — an offset into `CBDT` means nothing without the
 * `imageFormat` that came with it.
 *
 * ## The three formats this reads, and the nine it does not
 *
 * `CBDT` adds three formats to `EBDT`'s nine, and all three hold a PNG:
 *
 * - **17** — `smallGlyphMetrics`, `uint32 dataLen`, `uint8 data[dataLen]`
 * - **18** — `bigGlyphMetrics`, `uint32 dataLen`, `uint8 data[dataLen]`
 * - **19** — `uint32 dataLen`, `uint8 data[dataLen]`, metrics in `CBLC`
 *
 * `EBDT`'s own formats 1..9 are bit- and byte-aligned monochrome and greyscale
 * bitmaps, and "All bitmap image data formats defined in the EBDT / EBLC tables
 * are valid for use with the CBDT / CBLC tables". They are not read here: a
 * strike of them is a black-and-white bitmap font, which is not what this
 * library reaches for `CBDT` to draw, and reading them would mean a second image
 * path for pixels no colour-emoji font stores. A strike in one of those formats
 * is refused by name rather than approximated.
 */
internal class CbdtTable private constructor(
    private val reader: BigEndianReader,
    val majorVersion: Int,
    val minorVersion: Int,
) {
    /** How many bytes the table holds, header included. */
    val size: Int get() = reader.size

    /**
     * The colour bitmap [strike] holds for [glyphId], or null if it holds none.
     *
     * @throws IllegalArgumentException if the strike locates data this table
     *   does not have, or in a format this library does not read, naming the
     *   offset within `CBDT` in either case.
     */
    fun glyphOrNull(strike: BitmapStrike, glyphId: Int): ColorBitmapGlyph? {
        val location = strike.locationOf(glyphId) ?: return null
        return glyphAt(location, strike)
    }

    /** The colour bitmap at [location], of a glyph of [strike]. */
    fun glyphAt(location: BitmapLocation, strike: BitmapStrike): ColorBitmapGlyph {
        val what = "the bitmap data of glyph ${location.glyphId}, ${location.length} bytes at offset " +
            "${location.offset} of the 'CBDT' table"
        require(location.offset >= HEADER_SIZE) {
            "$what: it would start inside the ${HEADER_SIZE}-byte CbdtHeader"
        }
        val data = reader.sliceAt(location.offset, location.length, what)

        return when (location.imageFormat) {
            FORMAT_SMALL_METRICS_PNG -> {
                val metrics = SmallGlyphMetrics.read(data)
                glyph(location, strike, metrics, bigMetrics = null, png = readPng(data, location, what))
            }

            FORMAT_BIG_METRICS_PNG -> {
                val metrics = BigGlyphMetrics.read(data)
                glyph(
                    location,
                    strike,
                    metrics.inDirection(strike.metricsAreHorizontal),
                    metrics,
                    png = readPng(data, location, what),
                )
            }

            FORMAT_CBLC_METRICS_PNG -> {
                val metrics = requireNotNull(location.metrics) {
                    "$what: it is in image format $FORMAT_CBLC_METRICS_PNG, whose metrics are in the " +
                        "'CBLC' table, but the index subtable that located it is of an index format that " +
                        "carries none — only index formats ${CblcIndexSubtable.Format2.FORMAT} and " +
                        "${CblcIndexSubtable.Format5.FORMAT} keep a copy of the metrics"
                }
                glyph(
                    location,
                    strike,
                    metrics.inDirection(strike.metricsAreHorizontal),
                    metrics,
                    png = readPng(data, location, what),
                )
            }

            else -> throw IllegalArgumentException(
                "$what: its image format is ${location.imageFormat}, and this library reads the colour " +
                    "formats $FORMAT_SMALL_METRICS_PNG, $FORMAT_BIG_METRICS_PNG and " +
                    "$FORMAT_CBLC_METRICS_PNG, which hold PNG data; the formats 1..9 the 'EBDT' table " +
                    "defines are monochrome and greyscale bitmaps",
            )
        }
    }

    private fun glyph(
        location: BitmapLocation,
        strike: BitmapStrike,
        metrics: SmallGlyphMetrics,
        bigMetrics: BigGlyphMetrics?,
        png: ByteArray,
    ): ColorBitmapGlyph = ColorBitmapGlyph(
        glyphId = location.glyphId,
        ppemX = strike.ppemX,
        ppemY = strike.ppemY,
        metrics = metrics,
        bigMetrics = bigMetrics,
        png = png,
    )

    /**
     * `uint32 dataLen` and the `data[dataLen]` after it, checked to be a PNG.
     *
     * `dataLen` is the length that counts. The distance to the next glyph's data
     * is not: the index formats align their offsets ("Offsets for the bitmap
     * data are 4-byte aligned"), so a run can be longer than the image in it,
     * and the constant-size formats size every glyph by the largest.
     *
     * The signature is checked because it is the one thing "straight PNG data,
     * as specified in ISO/IEC 15948" always begins with, and because the failure
     * it catches — an offset landing a few bytes wrong — otherwise reaches a
     * document as a glyph that will not draw.
     */
    private fun readPng(data: BigEndianReader, location: BitmapLocation, what: String): ByteArray {
        val dataLen = data.uint32()
        val available = data.size - data.position
        require(dataLen <= available) {
            "$what: its dataLen is $dataLen bytes of PNG data at offset " +
                "${location.offset + data.position} of the 'CBDT' table, and the run this glyph's data " +
                "occupies has $available bytes left"
        }
        val png = data.sliceAt(data.position, dataLen.toInt(), "$what: its PNG data").toByteArray()
        require(png.size >= PNG_SIGNATURE.size && PNG_SIGNATURE.indices.all { png[it] == PNG_SIGNATURE[it] }) {
            "$what: the ${png.size} bytes at offset ${location.offset + data.position} of the 'CBDT' " +
                "table do not begin with the eight-byte PNG signature, which every datastream of " +
                "ISO/IEC 15948 begins with"
        }
        return png
    }

    override fun toString(): String = "CbdtTable(version $majorVersion.$minorVersion, $size bytes)"

    companion object {
        /** "Note that the first version of the CBDT table is 3.0." */
        const val MAJOR_VERSION: Int = 3

        /** "Format 17: small metrics, PNG image data". */
        const val FORMAT_SMALL_METRICS_PNG: Int = 17

        /** "Format 18: big metrics, PNG image data". */
        const val FORMAT_BIG_METRICS_PNG: Int = 18

        /** "Format 19: metrics in CBLC table, PNG image data". */
        const val FORMAT_CBLC_METRICS_PNG: Int = 19

        /** `uint16 majorVersion`, `uint16 minorVersion`. */
        private const val HEADER_SIZE: Int = 4

        /**
         * "the first eight bytes of a PNG datastream", ISO/IEC 15948 §5.2 — the
         * decimal values 137 80 78 71 13 10 26 10.
         */
        private val PNG_SIGNATURE: ByteArray = byteArrayOf(
            137.toByte(),
            80,
            78,
            71,
            13,
            10,
            26,
            10,
        )

        /**
         * Reads the header of the `CBDT` table [reader] covers and keeps the
         * window over the rest.
         *
         * @throws IllegalArgumentException if the version is not one this
         *   library reads.
         */
        fun parse(reader: BigEndianReader): CbdtTable {
            reader.seek(0)
            val majorVersion = reader.uint16()
            val minorVersion = reader.uint16()
            require(majorVersion == MAJOR_VERSION) {
                "the 'CBDT' table has majorVersion $majorVersion at offset 0; the first version of the " +
                    "table is $MAJOR_VERSION.0 and this library reads $MAJOR_VERSION.x"
            }
            return CbdtTable(reader, majorVersion, minorVersion)
        }
    }
}
