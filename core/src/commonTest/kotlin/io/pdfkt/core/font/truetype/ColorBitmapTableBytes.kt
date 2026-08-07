/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.font.truetype

// OpenType 1.9.1 §"CBLC — Color Bitmap Location Table", §"CBDT — Color Bitmap
// Data Table", §"EBLC — Embedded Bitmap Location Table" ("IndexSubtableList",
// "IndexSubtable formats")
// ISO/IEC 15948 §5.2 "PNG signature"

/**
 * `CBLC` and `CBDT` bytes written by hand from the field tables, the way
 * [TrueTypeFontBytes.kt][trueTypeFontBytes] writes the rest of a font.
 *
 * The offsets are the whole of what these two tables are, so the builders
 * compute them the way the clause describes them and the parser computes them
 * again from the bytes: `indexSubtableOffset` "from the start of the
 * IndexSubtableList", `imageDataOffset` "from the beginning of the EBDT table",
 * `sbitOffsets` from there. Two encodings of the same arithmetic that have to
 * agree is what the tests are for.
 *
 * Nothing here calls the parser, and no field is derived from a constant the
 * parser uses.
 */

/** `SbitLineMetrics`: ten one-byte fields, then `pad1` and `pad2`. */
internal fun sbitLineMetricsBytes(
    ascender: Int = 101,
    descender: Int = -27,
    widthMax: Int = 136,
    caretSlopeNumerator: Int = 0,
    caretSlopeDenominator: Int = 1,
    caretOffset: Int = 0,
    minOriginSB: Int = 0,
    minAdvanceSB: Int = 0,
    maxBeforeBL: Int = 0,
    minAfterBL: Int = 0,
): ByteArray = bigEndian {
    u8(ascender)
    u8(descender)
    u8(widthMax)
    u8(caretSlopeNumerator)
    u8(caretSlopeDenominator)
    u8(caretOffset)
    u8(minOriginSB)
    u8(minAdvanceSB)
    u8(maxBeforeBL)
    u8(minAfterBL)
    zeros(2) // pad1, pad2
}

/** `SmallGlyphMetrics`: five bytes, "for one layout direction only". */
internal fun smallGlyphMetricsBytes(
    height: Int = 128,
    width: Int = 136,
    bearingX: Int = 0,
    bearingY: Int = 101,
    advance: Int = 136,
): ByteArray = bigEndian {
    u8(height)
    u8(width)
    u8(bearingX)
    u8(bearingY)
    u8(advance)
}

/** `BigGlyphMetrics`: eight bytes, both layout directions. */
internal fun bigGlyphMetricsBytes(
    height: Int = 128,
    width: Int = 136,
    horiBearingX: Int = 0,
    horiBearingY: Int = 101,
    horiAdvance: Int = 136,
    vertBearingX: Int = -68,
    vertBearingY: Int = -10,
    vertAdvance: Int = 148,
): ByteArray = bigEndian {
    u8(height)
    u8(width)
    u8(horiBearingX)
    u8(horiBearingY)
    u8(horiAdvance)
    u8(vertBearingX)
    u8(vertBearingY)
    u8(vertAdvance)
}

/**
 * One `IndexSubtableRecord` and the `IndexSubtable` it points at.
 *
 * @property firstGlyphIndex "First glyph ID of this range."
 * @property lastGlyphIndex "Last glyph ID of this range (inclusive)."
 * @property bytes the subtable itself, starting with its `IndexSubHeader`.
 */
internal class IndexSubtableSpec(val firstGlyphIndex: Int, val lastGlyphIndex: Int, val bytes: ByteArray)

/** `IndexSubHeader`: `indexFormat`, `imageFormat`, `imageDataOffset`. */
private fun BigEndianBytes.indexSubHeader(indexFormat: Int, imageFormat: Int, imageDataOffset: Long) {
    u16(indexFormat)
    u16(imageFormat)
    u32(imageDataOffset)
}

/**
 * `IndexSubtableFormat1`: `Offset32 sbitOffsets[numOffsets]`, where
 * "numOffsets = lastGlyphIndex – firstGlyphIndex + 2".
 */
internal fun indexSubtableFormat1(
    firstGlyphIndex: Int,
    lastGlyphIndex: Int,
    imageFormat: Int,
    imageDataOffset: Long,
    sbitOffsets: List<Long>,
): IndexSubtableSpec = IndexSubtableSpec(
    firstGlyphIndex,
    lastGlyphIndex,
    bigEndian {
        indexSubHeader(indexFormat = 1, imageFormat = imageFormat, imageDataOffset = imageDataOffset)
        sbitOffsets.forEach { u32(it) }
    },
)

/** `IndexSubtableFormat2`: one `imageSize` and one `BigGlyphMetrics` for the whole range. */
internal fun indexSubtableFormat2(
    firstGlyphIndex: Int,
    lastGlyphIndex: Int,
    imageFormat: Int,
    imageDataOffset: Long,
    imageSize: Long,
    bigMetrics: ByteArray = bigGlyphMetricsBytes(),
): IndexSubtableSpec = IndexSubtableSpec(
    firstGlyphIndex,
    lastGlyphIndex,
    bigEndian {
        indexSubHeader(indexFormat = 2, imageFormat = imageFormat, imageDataOffset = imageDataOffset)
        u32(imageSize)
        bytes(bigMetrics)
    },
)

/** `IndexSubtableFormat3`: format 1 with `Offset16` offsets. */
internal fun indexSubtableFormat3(
    firstGlyphIndex: Int,
    lastGlyphIndex: Int,
    imageFormat: Int,
    imageDataOffset: Long,
    sbitOffsets: List<Int>,
): IndexSubtableSpec = IndexSubtableSpec(
    firstGlyphIndex,
    lastGlyphIndex,
    bigEndian {
        indexSubHeader(indexFormat = 3, imageFormat = imageFormat, imageDataOffset = imageDataOffset)
        sbitOffsets.forEach { u16(it) }
        // "When there is an odd number of elements in these arrays it is
        // necessary to add an extra padding element to maintain proper alignment."
        if (sbitOffsets.size % 2 != 0) u16(0)
    },
)

/**
 * `IndexSubtableFormat4`: `GlyphIdOffsetPair glyphArray[numGlyphs + 1]`, the
 * last pair being the extra record that sizes the last glyph.
 *
 * @param glyphIdsAndOffsets the pairs, sentinel included; `numGlyphs` is written
 *   as one less than their number, as the field table has it.
 */
internal fun indexSubtableFormat4(
    firstGlyphIndex: Int,
    lastGlyphIndex: Int,
    imageFormat: Int,
    imageDataOffset: Long,
    glyphIdsAndOffsets: List<Pair<Int, Int>>,
): IndexSubtableSpec = IndexSubtableSpec(
    firstGlyphIndex,
    lastGlyphIndex,
    bigEndian {
        indexSubHeader(indexFormat = 4, imageFormat = imageFormat, imageDataOffset = imageDataOffset)
        u32(glyphIdsAndOffsets.size.toLong() - 1)
        glyphIdsAndOffsets.forEach { (glyphId, sbitOffset) ->
            u16(glyphId)
            u16(sbitOffset)
        }
    },
)

/** `IndexSubtableFormat5`: constant metrics and a sparse `glyphIdArray`. */
internal fun indexSubtableFormat5(
    firstGlyphIndex: Int,
    lastGlyphIndex: Int,
    imageFormat: Int,
    imageDataOffset: Long,
    imageSize: Long,
    glyphIds: List<Int>,
    bigMetrics: ByteArray = bigGlyphMetricsBytes(),
): IndexSubtableSpec = IndexSubtableSpec(
    firstGlyphIndex,
    lastGlyphIndex,
    bigEndian {
        indexSubHeader(indexFormat = 5, imageFormat = imageFormat, imageDataOffset = imageDataOffset)
        u32(imageSize)
        bytes(bigMetrics)
        u32(glyphIds.size.toLong())
        glyphIds.forEach { u16(it) }
        if (glyphIds.size % 2 != 0) u16(0) // 32-bit alignment, as for format 3
    },
)

/** An `IndexSubtable` of a format the specification does not define. */
internal fun indexSubtableOfFormat(
    firstGlyphIndex: Int,
    lastGlyphIndex: Int,
    indexFormat: Int,
    imageFormat: Int = 17,
    imageDataOffset: Long = 0,
    body: ByteArray = ByteArray(8),
): IndexSubtableSpec = IndexSubtableSpec(
    firstGlyphIndex,
    lastGlyphIndex,
    bigEndian {
        indexSubHeader(indexFormat, imageFormat, imageDataOffset)
        bytes(body)
    },
)

/**
 * One `BitmapSize` record — one strike.
 *
 * [numberOfIndexSubtables] defaults to the number of subtables actually written
 * and can be set to something else, which is how a test asks what the parser
 * makes of a count the table does not back with records.
 */
internal class BitmapSizeSpec(
    val indexSubtables: List<IndexSubtableSpec>,
    val startGlyphIndex: Int,
    val endGlyphIndex: Int,
    val ppemX: Int = 109,
    val ppemY: Int = 109,
    val bitDepth: Int = 32,
    val flags: Int = 0x01,
    val hori: ByteArray = sbitLineMetricsBytes(),
    val vert: ByteArray = sbitLineMetricsBytes(ascender = 68, descender = -68),
    val numberOfIndexSubtables: Int = indexSubtables.size,
)

/**
 * A `CBLC` table: the header, the `BitmapSize` records, then one
 * `IndexSubtableList` per strike followed by that strike's subtables.
 *
 * > The IndexSubtableList for the first strike is located after the bitmapSizes
 * > records array. The IndexSubtables for that strike follow the
 * > IndexSubtableList header. Another IndexSubtableList (if more than one
 * > strike) and its IndexSubtables are next.
 */
internal fun cblcTableBytes(
    strikes: List<BitmapSizeSpec>,
    majorVersion: Int = 3,
    minorVersion: Int = 0,
    numSizes: Long = strikes.size.toLong(),
): ByteArray {
    val headerSize = 8
    val bitmapSizeRecordSize = 48
    val indexSubtableRecordSize = 8

    // Each strike's IndexSubtableList: the records, then the subtables, each
    // starting on a four-byte boundary ("The EBLC table specification requires
    // 32-bit alignment for all subtables").
    val listOffsets = ArrayList<Int>(strikes.size)
    val listBodies = ArrayList<ByteArray>(strikes.size)
    var next = headerSize + bitmapSizeRecordSize * strikes.size
    for (strike in strikes) {
        val subtableOffsets = ArrayList<Int>(strike.indexSubtables.size)
        var withinList = indexSubtableRecordSize * strike.indexSubtables.size
        for (subtable in strike.indexSubtables) {
            withinList += (4 - withinList % 4) % 4
            subtableOffsets += withinList
            withinList += subtable.bytes.size
        }
        listOffsets += next
        listBodies += bigEndian {
            strike.indexSubtables.forEachIndexed { index, subtable ->
                u16(subtable.firstGlyphIndex)
                u16(subtable.lastGlyphIndex)
                u32(subtableOffsets[index].toLong())
            }
            strike.indexSubtables.forEachIndexed { index, subtable ->
                zeros(subtableOffsets[index] - size)
                bytes(subtable.bytes)
            }
        }
        next += listBodies.last().size
    }

    return bigEndian {
        u16(majorVersion)
        u16(minorVersion)
        u32(numSizes)
        strikes.forEachIndexed { index, strike ->
            u32(listOffsets[index].toLong())
            u32(listBodies[index].size.toLong())
            u32(strike.numberOfIndexSubtables.toLong())
            u32(0) // colorRef — "Not used; set to 0."
            bytes(strike.hori)
            bytes(strike.vert)
            u16(strike.startGlyphIndex)
            u16(strike.endGlyphIndex)
            u8(strike.ppemX)
            u8(strike.ppemY)
            u8(strike.bitDepth)
            u8(strike.flags)
        }
        listBodies.forEach { bytes(it) }
    }
}

/** `GlyphBitmapFormat17`: `smallGlyphMetrics`, `dataLen`, `data[dataLen]`. */
internal fun glyphBitmapFormat17(
    png: ByteArray,
    metrics: ByteArray = smallGlyphMetricsBytes(),
    dataLen: Long = png.size.toLong(),
): ByteArray = bigEndian {
    bytes(metrics)
    u32(dataLen)
    bytes(png)
}

/** `GlyphBitmapFormat18`: `bigGlyphMetrics`, `dataLen`, `data[dataLen]`. */
internal fun glyphBitmapFormat18(
    png: ByteArray,
    metrics: ByteArray = bigGlyphMetricsBytes(),
    dataLen: Long = png.size.toLong(),
): ByteArray = bigEndian {
    bytes(metrics)
    u32(dataLen)
    bytes(png)
}

/** `GlyphBitmapFormat19`: `dataLen`, `data[dataLen]`, the metrics being in `CBLC`. */
internal fun glyphBitmapFormat19(png: ByteArray, dataLen: Long = png.size.toLong()): ByteArray = bigEndian {
    u32(dataLen)
    bytes(png)
}

/** A `CBDT` table and where each blob of glyph data landed in it. */
internal class CbdtBytes(val bytes: ByteArray, val offsets: List<Int>)

/**
 * A `CBDT` table: the four-byte header, then [glyphData] one after another.
 *
 * [align] is what the index formats assume of the offsets they store — format 1
 * says "Offsets for the bitmap data are 4-byte aligned" — and is why an offset
 * can be further from the previous one than that glyph's data is long.
 */
internal fun cbdtTableBytes(
    glyphData: List<ByteArray>,
    majorVersion: Int = 3,
    minorVersion: Int = 0,
    align: Int = 4,
): CbdtBytes {
    val offsets = ArrayList<Int>(glyphData.size)
    val out = BigEndianBytes()
    out.u16(majorVersion)
    out.u16(minorVersion)
    for (data in glyphData) {
        out.zeros((align - out.size % align) % align)
        offsets += out.size
        out.bytes(data)
    }
    return CbdtBytes(out.build(), offsets)
}

/**
 * Eight bytes of PNG signature — "the decimal values 137 80 78 71 13 10 26 10" —
 * and [payload] after them.
 *
 * A colour bitmap table stores "straight PNG data" and this library hands those
 * bytes on without decoding them, so what a test of these two tables needs is a
 * datastream it can recognise byte for byte, not a picture. The pictures are
 * checked where they are decoded, and against a font somebody else wrote
 * (`SystemColorBitmapFontTest`).
 */
internal fun pngDatastream(vararg payload: Int): ByteArray = bigEndian {
    u8(137)
    u8(80)
    u8(78)
    u8(71)
    u8(13)
    u8(10)
    u8(26)
    u8(10)
    payload.forEach { u8(it) }
}

/**
 * A font of the required tables plus [cblc] and [cbdt] — what
 * [TrueTypeFont.parse] has to open for a colour-emoji font to be usable at all.
 */
internal fun colorBitmapFontBytes(
    cblc: ByteArray,
    cbdt: ByteArray,
    numGlyphs: Int = 8,
    unitsPerEm: Int = 2048,
    cmap: ByteArray = cmapTableBytes(
        listOf(
            CmapRecord(
                platformId = 3,
                encodingId = 10,
                subtable = cmapFormat12Bytes(
                    listOf(Format12Group(startCharCode = 0x1F600, endCharCode = 0x1F602, startGlyphId = 1)),
                ),
            ),
        ),
    ),
): ByteArray = trueTypeFontBytes(
    listOfNotNull(
        "CBDT" holding cbdt,
        "CBLC" holding cblc,
        "cmap" holding cmap,
        "head" holding headTableBytes(unitsPerEm = unitsPerEm),
        "hhea" holding hheaTableBytes(numberOfHMetrics = 1),
        "hmtx" holding hmtxTableBytes(
            metrics = listOf(2550 to 0),
            leftSideBearings = List(numGlyphs - 1) { 0 },
        ),
        "maxp" holding maxpTableBytes(numGlyphs = numGlyphs),
    ),
)
