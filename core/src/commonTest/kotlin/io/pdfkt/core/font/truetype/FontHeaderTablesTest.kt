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
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// OpenType 1.9.1 §"head — Font header table", §"hhea — Horizontal header table",
// §"maxp — Maximum Profile table"

/**
 * The three tables that describe the font as a whole, each read field by field
 * against its field table in the specification.
 *
 * What is being checked is mostly the *offsets*: a header parser that is one
 * field out still returns numbers, and they are numbers that would silently
 * misplace every glyph on every page. So each test puts a distinguishable value
 * in one field and reads it back, and the fields the parser skips are the ones
 * between them.
 */
class FontHeaderTablesTest {
    private fun head(bytes: ByteArray): HeadTable = HeadTable.parse(BigEndianReader.over(bytes, "the 'head' table"))

    private fun hhea(bytes: ByteArray): HheaTable = HheaTable.parse(BigEndianReader.over(bytes, "the 'hhea' table"))

    private fun maxp(bytes: ByteArray): MaxpTable = MaxpTable.parse(BigEndianReader.over(bytes, "the 'maxp' table"))

    @Test
    fun `head is read field by field past the fields nobody keeps`() {
        val table = head(
            headTableBytes(
                unitsPerEm = 2048,
                xMin = -1021,
                yMin = -463,
                xMax = 1793,
                yMax = 1232,
                macStyle = 0b10,
                flags = 0b11,
                indexToLocFormat = 1,
            ),
        )

        assertEquals(2048, table.unitsPerEm)
        assertEquals(-1021, table.xMin)
        assertEquals(-463, table.yMin)
        assertEquals(1793, table.xMax)
        assertEquals(1232, table.yMax)
        assertEquals(0b10, table.macStyle)
        assertEquals(0b11, table.flags)
        assertEquals(1, table.indexToLocFormat)
    }

    @Test
    fun `indexToLocFormat says which loca offsets the font used`() {
        // "0 for short offsets (Offset16), 1 for long (Offset32)."
        assertFalse(head(headTableBytes(indexToLocFormat = 0)).hasLongLocaOffsets)
        assertTrue(head(headTableBytes(indexToLocFormat = 1)).hasLongLocaOffsets)
    }

    @Test
    fun `a head table without the magic number is refused`() {
        // "magicNumber | Set to 0x5F0F3CF5."
        val refused = assertFailsWith<IllegalArgumentException> { head(headTableBytes(magicNumber = 0x5F0F3CF4L)) }

        assertTrue("0x5f0f3cf5" in refused.message.orEmpty(), refused.message)
    }

    @Test
    fun `unitsPerEm is checked against the range the specification allows`() {
        // "Set to a value from 16 to 16384. Any value in this range is valid."
        assertEquals(16, head(headTableBytes(unitsPerEm = 16)).unitsPerEm)
        assertEquals(16384, head(headTableBytes(unitsPerEm = 16384)).unitsPerEm)
        assertFailsWith<IllegalArgumentException> { head(headTableBytes(unitsPerEm = 15)) }
        assertFailsWith<IllegalArgumentException> { head(headTableBytes(unitsPerEm = 16385)) }
    }

    @Test
    fun `a head table of an unknown major version is refused`() {
        val refused = assertFailsWith<IllegalArgumentException> { head(headTableBytes(majorVersion = 2)) }

        assertTrue("version 2" in refused.message.orEmpty(), refused.message)
    }

    @Test
    fun `an indexToLocFormat that is neither short nor long is refused`() {
        assertFailsWith<IllegalArgumentException> { head(headTableBytes(indexToLocFormat = 2)) }
    }

    @Test
    fun `hhea carries the extents of the font and the length of hmtx`() {
        val table = hhea(
            hheaTableBytes(
                ascender = 1901,
                descender = -483,
                lineGap = 0,
                advanceWidthMax = 2748,
                numberOfHMetrics = 4095,
            ),
        )

        assertEquals(1901, table.ascender)
        assertEquals(-483, table.descender)
        assertEquals(0, table.lineGap)
        assertEquals(2748, table.advanceWidthMax)
        assertEquals(4095, table.numberOfHMetrics)
    }

    @Test
    fun `the descender is read as the negative number a font writes`() {
        assertEquals(-483, hhea(hheaTableBytes(descender = -483)).descender)
    }

    @Test
    fun `an hhea table in an unknown metric data format is refused`() {
        // "metricDataFormat | 0 for current format."
        val refused = assertFailsWith<IllegalArgumentException> { hhea(hheaTableBytes(metricDataFormat = 1)) }

        assertTrue("metricDataFormat" in refused.message.orEmpty(), refused.message)
    }

    @Test
    fun `an hhea table without a single metric record is refused`() {
        assertFailsWith<IllegalArgumentException> { hhea(hheaTableBytes(numberOfHMetrics = 0)) }
    }

    @Test
    fun `maxp version 1 point 0 describes a font with TrueType outlines`() {
        // "Fonts with TrueType outlines must use Version 1.0 of this table."
        val table = maxp(maxpTableBytes(numGlyphs = 6253, version = MaxpTable.VERSION_1_0))

        assertEquals(6253, table.numGlyphs)
        assertTrue(table.describesTrueTypeOutlines)
    }

    @Test
    fun `maxp version 0 point 5 gives the glyph count and nothing else`() {
        // "Fonts with CFF or CFF2 outlines must use Version 0.5 of this table,
        // specifying only the numGlyphs field."
        val table = maxp(maxpTableBytes(numGlyphs = 42, version = MaxpTable.VERSION_0_5))

        assertEquals(42, table.numGlyphs)
        assertFalse(table.describesTrueTypeOutlines)
    }

    @Test
    fun `a maxp table of an unknown version is refused`() {
        val refused = assertFailsWith<IllegalArgumentException> {
            maxp(maxpTableBytes(numGlyphs = 42, version = 0x00020000L))
        }

        assertTrue("0x20000" in refused.message.orEmpty(), refused.message)
    }

    @Test
    fun `a font of no glyphs at all is refused`() {
        // Glyph 0 is ".notdef" and every font has it.
        assertFailsWith<IllegalArgumentException> { maxp(maxpTableBytes(numGlyphs = 0)) }
    }
}
