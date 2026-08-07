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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// OpenType 1.9.1 §"OpenType font file" — "Required Tables"

/**
 * The tables together: a character in, a glyph and an advance width out — and,
 * for a font with TrueType outlines, the glyphs that glyph is drawn out of.
 *
 * The assembly has one ordering constraint that is easy to get wrong and
 * impossible to notice afterwards — `hmtx` carries neither a count nor a length,
 * so `hhea` and `maxp` have to be read before it. A font whose two counts differ
 * from each other is what shows the constraint is honoured rather than
 * accidentally satisfied by equal numbers.
 */
class TrueTypeFontTest {
    @Test
    fun `a character maps to a glyph and the glyph to its advance width`() {
        val font = TrueTypeFont.parse(minimalFontBytes())

        assertEquals(1, font.glyphIdOf('A'.code))
        assertEquals(2, font.glyphIdOf('B'.code))
        assertEquals(600, font.advanceWidthOf(font.glyphIdOf('A'.code)))
        assertEquals(20, font.leftSideBearingOf(font.glyphIdOf('A'.code)))
    }

    @Test
    fun `a character the font has no glyph for is the missing glyph and not an error`() {
        // "character codes that do not correspond to any glyph in the font should
        // be mapped to glyph index 0" — the refusal belongs to the caller that
        // knows which character was asked for and where it came from.
        val font = TrueTypeFont.parse(minimalFontBytes())

        assertEquals(0, font.glyphIdOf('Ж'.code))
        assertEquals(500, font.advanceWidthOf(0))
    }

    @Test
    fun `the counts of hhea and maxp shape the metrics of the whole font`() {
        val font = TrueTypeFont.parse(
            minimalFontBytes(
                hhea = hheaTableBytes(numberOfHMetrics = 1),
                maxp = maxpTableBytes(numGlyphs = 3),
                hmtx = hmtxTableBytes(metrics = listOf(1229 to 5), leftSideBearings = listOf(7, 9)),
            ),
        )

        assertEquals(3, font.numGlyphs)
        assertEquals(1229, font.advanceWidthOf(0))
        assertEquals(1229, font.advanceWidthOf(2))
        assertEquals(9, font.leftSideBearingOf(2))
    }

    @Test
    fun `the em of the font is what head says it is`() {
        val font = TrueTypeFont.parse(minimalFontBytes(head = headTableBytes(unitsPerEm = 2048)))

        assertEquals(2048, font.unitsPerEm)
        assertEquals(2048, font.head.unitsPerEm)
    }

    @Test
    fun `a font missing a required table is refused by that table`() {
        val bytes = trueTypeFontBytes(
            listOf(
                "head" holding headTableBytes(),
                "hhea" holding hheaTableBytes(),
                "maxp" holding maxpTableBytes(numGlyphs = 1),
            ),
        )

        val refused = assertFailsWith<IllegalArgumentException> { TrueTypeFont.parse(bytes) }

        assertTrue("'hmtx'" in refused.message.orEmpty(), refused.message)
    }

    @Test
    fun `the tables stay reachable for the parses that come after this one`() {
        // The subsetter reads the tables it rebuilds through the directory rather
        // than by re-reading the file header.
        val font = TrueTypeFont.parse(minimalFontBytes())

        assertTrue("head" in font.directory)
        assertEquals(54, font.directory.readerOf("head").size)
    }

    @Test
    fun `head decides which format the loca table of the font is in`() {
        // "The format is specified by the indexToLocFormat entry in the 'head'
        // table" — the same three glyphs, located twice, and the closure of the
        // composite comes out the same either way.
        for (longOffsets in listOf(false, true)) {
            val tables = glyfAndLocaBytes(outlines, longOffsets)
            val font = TrueTypeFont.parse(
                minimalFontBytes(
                    head = headTableBytes(indexToLocFormat = if (longOffsets) 1 else 0),
                    glyf = tables.glyf,
                    loca = tables.loca,
                ),
            )

            val glyf = assertNotNull(font.glyf, "long offsets: $longOffsets")
            assertEquals(3, glyf.numGlyphs)
            assertEquals(setOf(1, 2), glyf.componentClosureOf(listOf(2)))
        }
    }

    @Test
    fun `a font whose outlines are not in this format has no glyph descriptions`() {
        // 'glyf' and 'loca' are required "for fonts with TrueType outlines"; a
        // font that carries its outlines elsewhere still measures text.
        val font = TrueTypeFont.parse(minimalFontBytes())

        assertNull(font.glyf)
        assertEquals(600, font.advanceWidthOf(font.glyphIdOf('A'.code)))
    }

    @Test
    fun `a font with glyf but no loca is refused`() {
        // Neither table says anything without the other: 'loca' is nothing but
        // offsets into 'glyf', and 'glyf' has no header of its own to find
        // glyphs by.
        val bytes = minimalFontBytes(glyf = glyfAndLocaBytes(outlines).glyf)

        val refused = assertFailsWith<IllegalArgumentException> { TrueTypeFont.parse(bytes) }

        assertTrue("'loca'" in refused.message.orEmpty(), refused.message)
    }

    /** .notdef with no outline, a simple glyph, and a composite built from it. */
    private val outlines: List<ByteArray> = listOf(
        ByteArray(0),
        simpleGlyphBytes(contours = listOf(listOf(GlyphPoint(0, 0), GlyphPoint(50, 90)))),
        compositeGlyphBytes(components = listOf(ComponentRecord(glyphId = 1, argument1 = 20, argument2 = 30))),
    )
}
