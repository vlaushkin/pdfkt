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

// OpenType 1.9.1 §"loca — Index to Location"

/**
 * `loca`, the table that says where every glyph is.
 *
 * Three of its properties are read off the clause and asserted here, because
 * getting any of them wrong locates glyph descriptions somewhere plausible
 * rather than nowhere:
 *
 * > The local offset divided by 2 is stored.
 *
 * > … there is an extra entry in the offsets array after the entry for the last
 * > valid glyph ID. Thus, the number of elements in the offsets array is
 * > numGlyphs + 1.
 *
 * > if a glyph has no outline or instructions, then loca[n] = loca[n+1].
 */
class LocaTableTest {
    private fun loca(offsets: List<Int>, numGlyphs: Int, longOffsets: Boolean): LocaTable = LocaTable.parse(
        BigEndianReader.over(locaTableBytes(offsets, longOffsets), "the 'loca' table"),
        numGlyphs = numGlyphs,
        longOffsets = longOffsets,
    )

    @Test
    fun `a long table locates each glyph at the offset it stores`() {
        val table = loca(listOf(0, 34, 34, 107), numGlyphs = 3, longOffsets = true)

        assertEquals(3, table.numGlyphs)
        assertEquals(0, table.offsetOf(0))
        assertEquals(34, table.offsetOf(1))
        assertEquals(34, table.offsetOf(2))
        assertEquals(107, table.glyphDataEnd)
    }

    @Test
    fun `a short table stores half of each offset`() {
        // The same four offsets as above, in the format where "the local offset
        // divided by 2 is stored" — the bytes hold 0, 17, 17, 53 and the parser
        // has to report 0, 34, 34, 106. A parser that read them as written would
        // place every glyph but the first inside its predecessor.
        val table = loca(listOf(0, 34, 34, 106), numGlyphs = 3, longOffsets = false)

        assertEquals(0, table.offsetOf(0))
        assertEquals(34, table.offsetOf(1))
        assertEquals(34, table.offsetOf(2))
        assertEquals(106, table.glyphDataEnd)
    }

    @Test
    fun `the length of a glyph is the difference between two consecutive entries`() {
        val table = loca(listOf(0, 34, 34, 107), numGlyphs = 3, longOffsets = true)

        assertEquals(34, table.lengthOf(0))
        assertEquals(0, table.lengthOf(1))
        // The last glyph's length exists only because of the extra entry.
        assertEquals(73, table.lengthOf(2))
    }

    @Test
    fun `a glyph whose neighbouring offsets are equal has no outline`() {
        // "If the font does not contain an outline for this glyph, then the first
        // and second offsets should have the same value. This also applies to any
        // other glyphs without an outline, such as the glyph for the space
        // character."
        val table = loca(listOf(0, 0, 48, 48), numGlyphs = 3, longOffsets = true)

        assertFalse(table.hasOutline(0))
        assertTrue(table.hasOutline(1))
        assertFalse(table.hasOutline(2))
    }

    @Test
    fun `a table with one entry per glyph rather than one more is refused`() {
        val message = assertFailsWith<IllegalArgumentException> {
            loca(listOf(0, 34, 68), numGlyphs = 3, longOffsets = true)
        }.message

        assertTrue(message!!.contains("numGlyphs + 1"), message)
    }

    @Test
    fun `bytes past the offsets array are left alone`() {
        // A table record may report the padded length rather than "the actual
        // length of data", and a parser that insisted on an exact size would
        // refuse fonts over their padding.
        val table = loca(listOf(0, 34, 68, 102, 136, 170), numGlyphs = 3, longOffsets = true)

        assertEquals(3, table.numGlyphs)
        assertEquals(102, table.glyphDataEnd)
    }

    @Test
    fun `offsets that do not ascend are refused`() {
        // "Offsets must be in ascending order, with loca[n] <= loca[n+1]." A
        // descending pair gives a glyph a negative length, which is not a
        // description this library could read out of any bytes.
        val message = assertFailsWith<IllegalArgumentException> {
            loca(listOf(0, 96, 48, 120), numGlyphs = 3, longOffsets = true)
        }.message

        assertTrue(message!!.contains("ascending order"), message)
    }

    @Test
    fun `a glyph the font does not have has no location`() {
        val table = loca(listOf(0, 34, 34, 107), numGlyphs = 3, longOffsets = true)

        assertFailsWith<IllegalArgumentException> { table.offsetOf(3) }
        assertFailsWith<IllegalArgumentException> { table.lengthOf(-1) }
    }
}
