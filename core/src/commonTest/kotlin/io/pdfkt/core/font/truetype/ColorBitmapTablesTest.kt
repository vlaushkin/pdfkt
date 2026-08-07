/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.font.truetype

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// OpenType 1.9.1 §"CBLC — Color Bitmap Location Table", §"CBDT — Color Bitmap
// Data Table", §"EBLC — Embedded Bitmap Location Table"

/**
 * `CBLC` and `CBDT` against bytes written out of their field tables.
 *
 * The two tables are one mechanism — an address and the bytes at it — so they
 * are tested together: a strike parsed with nothing read through it would only
 * say that this file and `ColorBitmapTableBytes.kt` agree on a layout.
 *
 * What each test pins is one sentence of the clause. The five index formats
 * differ in how they say where a glyph's data is and in nothing else, so each
 * one is asked the same question — where is glyph *n*, and what are the bytes
 * there — and has to give the same answer as the others about the same `CBDT`.
 */
class ColorBitmapTablesTest {
    /** Three datastreams a test can tell apart byte for byte. */
    private val firstPng = pngDatastream(0x01, 0x02, 0x03, 0x04)
    private val secondPng = pngDatastream(0x05, 0x06, 0x07, 0x08)
    private val thirdPng = pngDatastream(0x09, 0x0A, 0x0B, 0x0C)

    /**
     * A `CBDT` holding the three as image format 17: `smallGlyphMetrics`, then
     * `dataLen`, then the datastream. The three have different metrics, which is
     * what "glyphs have variable metrics" means and what index formats 1, 3 and
     * 4 exist for.
     */
    private val variableMetricsCbdt = cbdtTableBytes(
        listOf(
            glyphBitmapFormat17(firstPng, smallGlyphMetricsBytes(height = 12, width = 20, advance = 21)),
            glyphBitmapFormat17(secondPng, smallGlyphMetricsBytes(height = 13, width = 22, advance = 23)),
            glyphBitmapFormat17(thirdPng, smallGlyphMetricsBytes(height = 14, width = 24, advance = 25)),
        ),
    )

    /**
     * The same three datastreams as image format 19 — "metrics in CBLC table" —
     * each padded to the same 16 bytes, which is what a constant `imageSize`
     * means.
     */
    private val constantMetricsCbdt = cbdtTableBytes(
        listOf(
            glyphBitmapFormat19(firstPng),
            glyphBitmapFormat19(secondPng),
            glyphBitmapFormat19(thirdPng),
        ),
    )

    /** The constant size of one glyph's data in [constantMetricsCbdt]: `dataLen` plus twelve bytes of PNG. */
    private val constantImageSize: Long = 16

    private fun cblc(bytes: ByteArray): CblcTable = CblcTable.parse(BigEndianReader.over(bytes, "the 'CBLC' table"))

    private fun cbdt(bytes: ByteArray): CbdtTable = CbdtTable.parse(BigEndianReader.over(bytes, "the 'CBDT' table"))

    /**
     * Offsets into [variableMetricsCbdt] relative to its first glyph's data,
     * with the one past the last: the `sbitOffsets` array of index formats 1 and
     * 3, "One extra entry … to provide the size of data for the last glyph".
     */
    private fun variableOffsets(): List<Int> {
        val base = variableMetricsCbdt.offsets.first()
        return variableMetricsCbdt.offsets.map { it - base } + (variableMetricsCbdt.bytes.size - base)
    }

    @Test
    fun `a BitmapSize record describes the size its bitmaps were drawn at`() {
        // "ppemX | Horizontal pixels per em", "bitDepth | … the value of 32 is
        // used to identify color bitmaps with 8 bit per channel BGRA pixel data".
        val table = cblc(
            cblcTableBytes(
                listOf(
                    BitmapSizeSpec(
                        indexSubtables = listOf(
                            indexSubtableFormat1(
                                firstGlyphIndex = 1,
                                lastGlyphIndex = 3,
                                imageFormat = 17,
                                imageDataOffset = 4,
                                sbitOffsets = listOf(0L, 0L, 0L, 0L),
                            ),
                        ),
                        startGlyphIndex = 1,
                        endGlyphIndex = 3,
                        ppemX = 109,
                        ppemY = 110,
                        bitDepth = 32,
                        flags = 0x01,
                        hori = sbitLineMetricsBytes(ascender = 101, descender = -27, widthMax = 136),
                    ),
                ),
            ),
        )

        assertEquals(3, table.majorVersion)
        assertEquals(0, table.minorVersion)
        val strike = table.strikes.single()
        assertEquals(109, strike.ppemX)
        assertEquals(110, strike.ppemY)
        assertEquals(32, strike.bitDepth)
        assertEquals(1, strike.startGlyphIndex)
        assertEquals(3, strike.endGlyphIndex)
        assertTrue(strike.metricsAreHorizontal)
        assertEquals(101, strike.hori.ascender)
        assertEquals(-27, strike.hori.descender)
        assertEquals(136, strike.hori.widthMax)
        assertEquals(strike.hori, strike.lineMetrics)
    }

    @Test
    fun `index format 1 sizes a glyph by the offset of the next one`() {
        // "image data must be stored contiguously and in glyph ID order, so the
        // image data size may be calculated by subtracting the offset for the
        // current glyph from the offset of the next glyph".
        val offsets = variableOffsets()
        val strike = strikeOf(
            indexSubtableFormat1(
                firstGlyphIndex = 1,
                lastGlyphIndex = 3,
                imageFormat = 17,
                imageDataOffset = variableMetricsCbdt.offsets.first().toLong(),
                sbitOffsets = offsets.map { it.toLong() },
            ),
            startGlyphIndex = 1,
            endGlyphIndex = 3,
        )
        val data = cbdt(variableMetricsCbdt.bytes)

        val second = assertNotNull(data.glyphOrNull(strike, glyphId = 2))
        assertContentEquals(secondPng, second.png)
        assertEquals(
            SmallGlyphMetrics(height = 13, width = 22, bearingX = 0, bearingY = 101, advance = 23),
            second.metrics,
        )
        assertNull(second.bigMetrics)
        assertEquals(109, second.ppemX)
        assertContentEquals(firstPng, assertNotNull(data.glyphOrNull(strike, 1)).png)
        assertContentEquals(thirdPng, assertNotNull(data.glyphOrNull(strike, 3)).png)
    }

    @Test
    fun `an offset repeated is a glyph the strike has no bitmap for`() {
        // "A small number of missing glyphs can be efficiently represented in
        // formats 1 or 3 by having the offset for the missing glyph be followed
        // by the same offset for the next glyph, thus indicating a data size of
        // zero."
        val offsets = variableOffsets()
        val strike = strikeOf(
            indexSubtableFormat1(
                firstGlyphIndex = 1,
                lastGlyphIndex = 4,
                imageFormat = 17,
                imageDataOffset = variableMetricsCbdt.offsets.first().toLong(),
                sbitOffsets = (offsets + offsets.last()).map { it.toLong() },
            ),
            startGlyphIndex = 1,
            endGlyphIndex = 4,
        )

        assertNull(strike.locationOf(4))
        assertNull(cbdt(variableMetricsCbdt.bytes).glyphOrNull(strike, 4))
        assertNotNull(strike.locationOf(3))
    }

    @Test
    fun `a glyph outside every index subtable has no bitmap`() {
        // "The startGlyphIndex and endGlyphIndex describe the minimum and maximum
        // glyph IDs in the strike, but a strike does not necessarily contain
        // bitmaps for all glyph IDs in this range. The IndexSubtables determine
        // which glyphs are actually present in the EBDT table."
        val offsets = variableOffsets()
        val strike = strikeOf(
            indexSubtableFormat1(
                firstGlyphIndex = 1,
                lastGlyphIndex = 3,
                imageFormat = 17,
                imageDataOffset = variableMetricsCbdt.offsets.first().toLong(),
                sbitOffsets = offsets.map { it.toLong() },
            ),
            startGlyphIndex = 1,
            endGlyphIndex = 9,
        )

        assertNull(strike.locationOf(0))
        assertNull(strike.locationOf(4))
        assertNull(strike.locationOf(9))
        assertNotNull(strike.locationOf(1))
    }

    @Test
    fun `index format 3 addresses the same data with 16-bit offsets`() {
        // "The only difference between formats 1 and 3 is the size of the
        // sbitOffsets array elements: format 1 uses uint32s while format 3 uses
        // uint16s."
        val strike = strikeOf(
            indexSubtableFormat3(
                firstGlyphIndex = 1,
                lastGlyphIndex = 3,
                imageFormat = 17,
                imageDataOffset = variableMetricsCbdt.offsets.first().toLong(),
                sbitOffsets = variableOffsets(),
            ),
            startGlyphIndex = 1,
            endGlyphIndex = 3,
        )
        val data = cbdt(variableMetricsCbdt.bytes)

        assertContentEquals(firstPng, assertNotNull(data.glyphOrNull(strike, 1)).png)
        assertContentEquals(secondPng, assertNotNull(data.glyphOrNull(strike, 2)).png)
        assertContentEquals(thirdPng, assertNotNull(data.glyphOrNull(strike, 3)).png)
        assertEquals(24, assertNotNull(data.glyphOrNull(strike, 3)).metrics.width)
    }

    @Test
    fun `index format 4 lists the sparse glyph ids it has data for`() {
        // "Format 4 can be used when glyphs have variable metrics and when the
        // glyph IDs are sparse … One extra record is stored to represent the size
        // of the image data for the last glyph."
        val offsets = variableOffsets()
        val strike = strikeOf(
            indexSubtableFormat4(
                firstGlyphIndex = 1,
                lastGlyphIndex = 40,
                imageFormat = 17,
                imageDataOffset = variableMetricsCbdt.offsets.first().toLong(),
                glyphIdsAndOffsets = listOf(
                    1 to offsets[0],
                    20 to offsets[1],
                    40 to offsets[2],
                    41 to offsets[3], // the extra record, sizing glyph 40
                ),
            ),
            startGlyphIndex = 1,
            endGlyphIndex = 40,
        )
        val data = cbdt(variableMetricsCbdt.bytes)

        assertContentEquals(firstPng, assertNotNull(data.glyphOrNull(strike, 1)).png)
        assertContentEquals(secondPng, assertNotNull(data.glyphOrNull(strike, 20)).png)
        assertContentEquals(thirdPng, assertNotNull(data.glyphOrNull(strike, 40)).png)
        assertNull(strike.locationOf(19))
        assertNull(strike.locationOf(21))
        assertNull(strike.locationOf(41))
    }

    @Test
    fun `index format 2 keeps one copy of the metrics for the whole range`() {
        // "Format 2 can be used when glyphs have identical metrics. The bitmap
        // data for all glyphs will be the same size, so an array of offsets is
        // not required."
        val strike = strikeOf(
            indexSubtableFormat2(
                firstGlyphIndex = 1,
                lastGlyphIndex = 3,
                imageFormat = 19,
                imageDataOffset = constantMetricsCbdt.offsets.first().toLong(),
                imageSize = constantImageSize,
                bigMetrics = bigGlyphMetricsBytes(
                    height = 30,
                    width = 31,
                    horiBearingX = 1,
                    horiBearingY = 28,
                    horiAdvance = 32,
                    vertBearingX = -15,
                    vertBearingY = -2,
                    vertAdvance = 34,
                ),
            ),
            startGlyphIndex = 1,
            endGlyphIndex = 3,
        )
        val data = cbdt(constantMetricsCbdt.bytes)

        assertContentEquals(secondPng, assertNotNull(data.glyphOrNull(strike, 2)).png)
        assertContentEquals(thirdPng, assertNotNull(data.glyphOrNull(strike, 3)).png)
        val glyph = assertNotNull(data.glyphOrNull(strike, 1))
        assertContentEquals(firstPng, glyph.png)
        // The strike is flagged horizontal, so the small metrics are the
        // horizontal half of the record kept in the index subtable.
        assertEquals(
            SmallGlyphMetrics(height = 30, width = 31, bearingX = 1, bearingY = 28, advance = 32),
            glyph.metrics,
        )
        assertEquals(34, assertNotNull(glyph.bigMetrics).vertAdvance)
    }

    @Test
    fun `index format 5 lists sparse glyph ids of one constant size`() {
        // "Format 5 can be used when glyphs have identical metrics, but the glyph
        // IDs are sparse, not in a contiguous range."
        val strike = strikeOf(
            indexSubtableFormat5(
                firstGlyphIndex = 5,
                lastGlyphIndex = 900,
                imageFormat = 19,
                imageDataOffset = constantMetricsCbdt.offsets.first().toLong(),
                imageSize = constantImageSize,
                glyphIds = listOf(5, 300, 900),
            ),
            startGlyphIndex = 5,
            endGlyphIndex = 900,
        )
        val data = cbdt(constantMetricsCbdt.bytes)

        assertContentEquals(firstPng, assertNotNull(data.glyphOrNull(strike, 5)).png)
        assertContentEquals(secondPng, assertNotNull(data.glyphOrNull(strike, 300)).png)
        assertContentEquals(thirdPng, assertNotNull(data.glyphOrNull(strike, 900)).png)
        assertNull(strike.locationOf(299))
        assertNull(strike.locationOf(901))
    }

    @Test
    fun `image format 18 carries the metrics of both layout directions`() {
        // "Big glyph metrics define metrics information for both horizontal and
        // vertical layouts."
        val metrics = bigGlyphMetricsBytes(
            height = 40,
            width = 41,
            horiBearingX = 2,
            horiBearingY = 38,
            horiAdvance = 42,
            vertBearingX = -20,
            vertBearingY = -3,
            vertAdvance = 44,
        )
        val table = cbdtTableBytes(listOf(glyphBitmapFormat18(firstPng, metrics)))
        val subtable = indexSubtableFormat1(
            firstGlyphIndex = 1,
            lastGlyphIndex = 1,
            imageFormat = 18,
            imageDataOffset = table.offsets.first().toLong(),
            sbitOffsets = listOf(0L, (table.bytes.size - table.offsets.first()).toLong()),
        )
        val data = cbdt(table.bytes)

        val horizontal = assertNotNull(
            data.glyphOrNull(strikeOf(subtable, 1, 1, flags = 0x01), glyphId = 1),
        )
        assertEquals(
            SmallGlyphMetrics(height = 40, width = 41, bearingX = 2, bearingY = 38, advance = 42),
            horizontal.metrics,
        )

        // "Which direction applies, horizontal or vertical, is determined by the
        // flags field of BitmapSize records within the CBLC table."
        val vertical = assertNotNull(
            data.glyphOrNull(strikeOf(subtable, 1, 1, flags = 0x02), glyphId = 1),
        )
        assertEquals(
            SmallGlyphMetrics(height = 40, width = 41, bearingX = -20, bearingY = -3, advance = 44),
            vertical.metrics,
        )
        assertContentEquals(firstPng, vertical.png)
    }

    @Test
    fun `the largest strike is the one a page draws from`() {
        // "It is recommended that where possible the application downscale using
        // the closest sized bitmap that is larger than the desired end raster
        // size" — a page has no raster size, so the largest strike is the choice
        // that is never the wrong way round.
        val small = BitmapSizeSpec(
            indexSubtables = listOf(indexSubtableFormat1(1, 1, 17, 4, listOf(0L, 0L))),
            startGlyphIndex = 1,
            endGlyphIndex = 1,
            ppemX = 32,
            ppemY = 32,
        )
        val large = BitmapSizeSpec(
            indexSubtables = listOf(indexSubtableFormat1(1, 1, 17, 4, listOf(0L, 0L))),
            startGlyphIndex = 1,
            endGlyphIndex = 1,
            ppemX = 128,
            ppemY = 128,
        )

        assertEquals(128, assertNotNull(cblc(cblcTableBytes(listOf(small, large))).largestStrike).ppemY)
        assertEquals(128, assertNotNull(cblc(cblcTableBytes(listOf(large, small))).largestStrike).ppemY)
        assertEquals(2, cblc(cblcTableBytes(listOf(small, large))).strikes.size)
    }

    @Test
    fun `a CBLC of a version this library does not read is refused`() {
        // "Note that the first version of the CBLC table is 3.0."
        val error = assertFailsWith<IllegalArgumentException> {
            cblc(cblcTableBytes(strikes = emptyList(), majorVersion = 2))
        }
        assertTrue(error.message!!.contains("majorVersion 2 at offset 0"), error.message)
    }

    @Test
    fun `a CBDT of a version this library does not read is refused`() {
        val error = assertFailsWith<IllegalArgumentException> {
            cbdt(cbdtTableBytes(glyphData = emptyList(), majorVersion = 2).bytes)
        }
        assertTrue(error.message!!.contains("majorVersion 2 at offset 0"), error.message)
    }

    @Test
    fun `an index subtable of an undefined format is refused with its offset`() {
        // "There are five different formats used for the IndexSubtable"; the
        // header is eight bytes and the first strike's IndexSubtableList follows
        // the one 48-byte BitmapSize record, so the subtable is at 8 + 48 + 8.
        val error = assertFailsWith<IllegalArgumentException> {
            cblc(
                cblcTableBytes(
                    listOf(
                        BitmapSizeSpec(
                            indexSubtables = listOf(indexSubtableOfFormat(1, 3, indexFormat = 6)),
                            startGlyphIndex = 1,
                            endGlyphIndex = 3,
                        ),
                    ),
                ),
            )
        }
        assertTrue(error.message!!.contains("at offset 64 of the 'CBLC' table"), error.message)
        assertTrue(error.message!!.contains("indexFormat is 6"), error.message)
    }

    @Test
    fun `an IndexSubtableList that runs past the table is refused`() {
        val bytes = cblcTableBytes(
            listOf(
                BitmapSizeSpec(
                    indexSubtables = listOf(indexSubtableFormat1(1, 1, 17, 4, listOf(0L, 16L))),
                    startGlyphIndex = 1,
                    endGlyphIndex = 1,
                ),
            ),
        )
        // indexSubtableListSize, the second uint32 of the BitmapSize record.
        bytes.putUint32(offset = 12, value = 0xFFFFL)

        val error = assertFailsWith<IllegalArgumentException> { cblc(bytes) }
        assertTrue(error.message!!.contains("BitmapSize record at offset 8"), error.message)
        assertTrue(error.message!!.contains("past the end"), error.message)
    }

    @Test
    fun `a dataLen longer than the run the glyph occupies is refused with its offset`() {
        val table = cbdtTableBytes(
            listOf(glyphBitmapFormat17(firstPng, dataLen = firstPng.size.toLong() + 1)),
        )
        val strike = strikeOf(
            indexSubtableFormat1(
                firstGlyphIndex = 1,
                lastGlyphIndex = 1,
                imageFormat = 17,
                imageDataOffset = table.offsets.first().toLong(),
                sbitOffsets = listOf(0L, (table.bytes.size - table.offsets.first()).toLong()),
            ),
            1,
            1,
        )

        val error = assertFailsWith<IllegalArgumentException> { cbdt(table.bytes).glyphOrNull(strike, 1) }
        assertTrue(error.message!!.contains("dataLen is ${firstPng.size + 1}"), error.message)
        assertTrue(error.message!!.contains("of the 'CBDT' table"), error.message)
    }

    @Test
    fun `bitmap data that is not a PNG datastream is refused with its offset`() {
        // "Images for each individual glyph may be stored as straight PNG data,
        // as specified in ISO/IEC 15948" — whose §5.2 fixes the first eight
        // bytes. Data that does not start with them is an offset gone wrong.
        val notAPng = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9)
        val table = cbdtTableBytes(listOf(glyphBitmapFormat17(notAPng)))
        val strike = strikeOf(
            indexSubtableFormat1(
                firstGlyphIndex = 1,
                lastGlyphIndex = 1,
                imageFormat = 17,
                imageDataOffset = table.offsets.first().toLong(),
                sbitOffsets = listOf(0L, (table.bytes.size - table.offsets.first()).toLong()),
            ),
            1,
            1,
        )

        val error = assertFailsWith<IllegalArgumentException> { cbdt(table.bytes).glyphOrNull(strike, 1) }
        assertTrue(error.message!!.contains("PNG signature"), error.message)
        assertTrue(error.message!!.contains("offset ${table.offsets.first() + 9} of the 'CBDT' table"), error.message)
    }

    @Test
    fun `an image format this library does not read is named`() {
        // The nine formats of the EBDT table are monochrome and greyscale
        // bitmaps; format 5 is one of them.
        val table = cbdtTableBytes(listOf(glyphBitmapFormat17(firstPng)))
        val strike = strikeOf(
            indexSubtableFormat1(
                firstGlyphIndex = 1,
                lastGlyphIndex = 1,
                imageFormat = 5,
                imageDataOffset = table.offsets.first().toLong(),
                sbitOffsets = listOf(0L, (table.bytes.size - table.offsets.first()).toLong()),
            ),
            1,
            1,
        )

        val error = assertFailsWith<IllegalArgumentException> { cbdt(table.bytes).glyphOrNull(strike, 1) }
        assertTrue(error.message!!.contains("image format is 5"), error.message)
        assertTrue(error.message!!.contains("17, 18 and 19"), error.message)
    }

    @Test
    fun `image format 19 without metrics in the index subtable is refused`() {
        // Format 19 is "metrics in CBLC table"; index format 1 stores none, so
        // the pair says nothing about how big the glyph is.
        val table = cbdtTableBytes(listOf(glyphBitmapFormat19(firstPng)))
        val strike = strikeOf(
            indexSubtableFormat1(
                firstGlyphIndex = 1,
                lastGlyphIndex = 1,
                imageFormat = 19,
                imageDataOffset = table.offsets.first().toLong(),
                sbitOffsets = listOf(0L, (table.bytes.size - table.offsets.first()).toLong()),
            ),
            1,
            1,
        )

        val error = assertFailsWith<IllegalArgumentException> { cbdt(table.bytes).glyphOrNull(strike, 1) }
        assertTrue(error.message!!.contains("image format 19"), error.message)
        assertTrue(error.message!!.contains("index formats 2 and 5"), error.message)
    }

    @Test
    fun `a sparse glyph id array out of order is refused`() {
        // "One per glyph, sorted by glyph ID" — the order a binary search needs.
        val error = assertFailsWith<IllegalArgumentException> {
            cblc(
                cblcTableBytes(
                    listOf(
                        BitmapSizeSpec(
                            indexSubtables = listOf(
                                indexSubtableFormat5(
                                    firstGlyphIndex = 5,
                                    lastGlyphIndex = 900,
                                    imageFormat = 19,
                                    imageDataOffset = 4,
                                    imageSize = 16,
                                    glyphIds = listOf(900, 300, 5),
                                ),
                            ),
                            startGlyphIndex = 5,
                            endGlyphIndex = 900,
                        ),
                    ),
                ),
            )
        }
        assertTrue(error.message!!.contains("not sorted by glyph ID"), error.message)
        assertTrue(error.message!!.contains("of the 'CBLC' table"), error.message)
    }

    @Test
    fun `a font carrying one of the two tables is refused`() {
        val cblcBytes = cblcTableBytes(
            listOf(
                BitmapSizeSpec(
                    indexSubtables = listOf(indexSubtableFormat1(1, 1, 17, 4, listOf(0L, 16L))),
                    startGlyphIndex = 1,
                    endGlyphIndex = 1,
                ),
            ),
        )
        val font = trueTypeFontBytes(
            listOf(
                "CBLC" holding cblcBytes,
                "cmap" holding cmapTableBytes(
                    listOf(CmapRecord(3, 1, cmapFormat4Bytes(listOf(Format4Segment(0x41, 0x41, -0x40))))),
                ),
                "head" holding headTableBytes(),
                "hhea" holding hheaTableBytes(numberOfHMetrics = 1),
                "hmtx" holding hmtxTableBytes(listOf(500 to 0), listOf(0, 0)),
                "maxp" holding maxpTableBytes(numGlyphs = 3),
            ),
        )

        val error = assertFailsWith<IllegalArgumentException> { TrueTypeFont.parse(font) }
        assertTrue(error.message!!.contains("'CBLC' table but no 'CBDT' table"), error.message)
    }

    @Test
    fun `a font answers for a glyph out of its largest strike`() {
        val offsets = variableOffsets()
        val font = TrueTypeFont.parse(
            colorBitmapFontBytes(
                cblc = cblcTableBytes(
                    listOf(
                        BitmapSizeSpec(
                            indexSubtables = listOf(
                                indexSubtableFormat1(
                                    firstGlyphIndex = 1,
                                    lastGlyphIndex = 3,
                                    imageFormat = 17,
                                    imageDataOffset = variableMetricsCbdt.offsets.first().toLong(),
                                    sbitOffsets = offsets.map { it.toLong() },
                                ),
                            ),
                            startGlyphIndex = 1,
                            endGlyphIndex = 3,
                            ppemX = 96,
                            ppemY = 96,
                        ),
                        BitmapSizeSpec(
                            indexSubtables = listOf(
                                indexSubtableFormat1(
                                    firstGlyphIndex = 1,
                                    lastGlyphIndex = 3,
                                    imageFormat = 17,
                                    imageDataOffset = variableMetricsCbdt.offsets.first().toLong(),
                                    sbitOffsets = offsets.map { it.toLong() },
                                ),
                            ),
                            startGlyphIndex = 1,
                            endGlyphIndex = 3,
                            ppemX = 128,
                            ppemY = 128,
                        ),
                    ),
                ),
                cbdt = variableMetricsCbdt.bytes,
            ),
        )

        assertEquals(2, font.colorBitmapStrikes.size)
        // U+1F600 is the first glyph of the font's format 12 subtable.
        val glyphId = font.glyphIdOf(0x1F600)
        assertEquals(1, glyphId)
        val glyph = assertNotNull(font.colorBitmapOf(glyphId))
        assertContentEquals(firstPng, glyph.png)
        assertEquals(128, glyph.ppemY)
        assertEquals(20, glyph.metrics.width)
        assertEquals(21, glyph.metrics.advance)
        assertNull(font.colorBitmapOf(glyphId = 7))
    }

    @Test
    fun `a font with no colour bitmaps has no strikes and no glyphs`() {
        val font = TrueTypeFont.parse(minimalFontBytes())

        assertNull(font.cblc)
        assertNull(font.cbdt)
        assertTrue(font.colorBitmapStrikes.isEmpty())
        assertNull(font.colorBitmapOf(glyphId = 1))
    }

    /** A one-strike `CBLC` around [subtable], parsed back. */
    private fun strikeOf(
        subtable: IndexSubtableSpec,
        startGlyphIndex: Int,
        endGlyphIndex: Int,
        flags: Int = 0x01,
    ): BitmapStrike = cblc(
        cblcTableBytes(
            listOf(
                BitmapSizeSpec(
                    indexSubtables = listOf(subtable),
                    startGlyphIndex = startGlyphIndex,
                    endGlyphIndex = endGlyphIndex,
                    flags = flags,
                ),
            ),
        ),
    ).strikes.single()
}
