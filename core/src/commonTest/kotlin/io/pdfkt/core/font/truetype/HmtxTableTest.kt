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
import kotlin.test.assertTrue

// OpenType 1.9.1 §"hmtx — Horizontal metrix table"
// ISO 32000-2:2020 §9.2.4 "Glyph positioning and metrics"

/**
 * `hmtx`, and the optimisation that makes it the one table whose shape cannot be
 * read out of itself:
 *
 * > As an optimization, the number of records can be less than the number of
 * > glyphs, in which case the advance width value of the last record applies to
 * > all remaining glyph IDs.
 *
 * A parser that misses this reads the trailing left side bearings as advance
 * widths, and every glyph past the last record comes out a wrong width — which
 * is a mis-set line rather than a crash, so it needs a test rather than a
 * runtime check. The font that exercises it here is the shape of a real
 * monospaced one: many glyphs, few records.
 */
class HmtxTableTest {
    private fun hmtx(bytes: ByteArray, numberOfHMetrics: Int, numGlyphs: Int): HmtxTable =
        HmtxTable.parse(BigEndianReader.over(bytes, "the 'hmtx' table"), numberOfHMetrics, numGlyphs)

    @Test
    fun `one record per glyph gives each its own advance width`() {
        val table = hmtx(
            hmtxTableBytes(metrics = listOf(500 to 0, 600 to 12, 700 to -3)),
            numberOfHMetrics = 3,
            numGlyphs = 3,
        )

        assertEquals(500, table.advanceWidthOf(0))
        assertEquals(600, table.advanceWidthOf(1))
        assertEquals(700, table.advanceWidthOf(2))
        assertEquals(0, table.leftSideBearingOf(0))
        assertEquals(12, table.leftSideBearingOf(1))
        assertEquals(-3, table.leftSideBearingOf(2))
    }

    @Test
    fun `the last record supplies the advance width of every glyph after it`() {
        val table = hmtx(
            hmtxTableBytes(metrics = listOf(500 to 0, 1229 to 12), leftSideBearings = listOf(40, 50, 60)),
            numberOfHMetrics = 2,
            numGlyphs = 5,
        )

        assertEquals(500, table.advanceWidthOf(0))
        assertEquals(1229, table.advanceWidthOf(1))
        assertEquals(1229, table.advanceWidthOf(2))
        assertEquals(1229, table.advanceWidthOf(3))
        assertEquals(1229, table.advanceWidthOf(4))
    }

    @Test
    fun `the glyphs after the last record keep their own left side bearings`() {
        // "If numberOfHMetrics is less than the total number of glyphs, then the
        // hMetrics array is followed by an array for the left side bearing values
        // of the remaining glyphs."
        val table = hmtx(
            hmtxTableBytes(metrics = listOf(500 to 0, 1229 to 12), leftSideBearings = listOf(40, -50, 60)),
            numberOfHMetrics = 2,
            numGlyphs = 5,
        )

        assertEquals(0, table.leftSideBearingOf(0))
        assertEquals(12, table.leftSideBearingOf(1))
        assertEquals(40, table.leftSideBearingOf(2))
        assertEquals(-50, table.leftSideBearingOf(3))
        assertEquals(60, table.leftSideBearingOf(4))
    }

    @Test
    fun `the counts of hhea and maxp describe the table`() {
        val table = hmtx(
            hmtxTableBytes(metrics = listOf(500 to 0), leftSideBearings = listOf(1, 2, 3)),
            numberOfHMetrics = 1,
            numGlyphs = 4,
        )

        assertEquals(1, table.numberOfHMetrics)
        assertEquals(4, table.numGlyphs)
    }

    @Test
    fun `a glyph the font does not have has no width to answer with`() {
        val table = hmtx(hmtxTableBytes(metrics = listOf(500 to 0)), numberOfHMetrics = 1, numGlyphs = 1)

        val refused = assertFailsWith<IllegalArgumentException> { table.advanceWidthOf(1) }

        assertTrue("1 glyph" in refused.message.orEmpty(), refused.message)
        assertFailsWith<IllegalArgumentException> { table.advanceWidthOf(-1) }
        assertFailsWith<IllegalArgumentException> { table.leftSideBearingOf(1) }
    }

    @Test
    fun `more records than glyphs is refused`() {
        val refused = assertFailsWith<IllegalArgumentException> {
            hmtx(hmtxTableBytes(metrics = listOf(500 to 0, 600 to 0)), numberOfHMetrics = 2, numGlyphs = 1)
        }

        assertTrue("numberOfHMetrics" in refused.message.orEmpty(), refused.message)
    }

    @Test
    fun `a table too short for the arrays the two counts describe is refused`() {
        assertFailsWith<IllegalArgumentException> {
            hmtx(hmtxTableBytes(metrics = listOf(500 to 0)), numberOfHMetrics = 1, numGlyphs = 4)
        }
    }
}
