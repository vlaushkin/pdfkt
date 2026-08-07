/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.font.truetype

import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// OpenType 1.9.1 §"CBLC — Color Bitmap Location Table", §"CBDT — Color Bitmap
// Data Table"; ISO/IEC 15948 §11.2.2 "IHDR Image header"

/**
 * The `CBLC`/`CBDT` parser against a colour font it did not write: the
 * system's `NotoColorEmoji.ttf` (Debian's `fonts-noto-color-emoji`).
 *
 * The synthetic tests in `commonTest` say the parser reads the layout the
 * specification states. They cannot say the specification was read correctly,
 * because the same misreading produces both the writer and the reader. A font
 * built by somebody else from the same document is the independent half — and it
 * is a font, not a rendering library: nothing here compares against how another
 * program parses it.
 *
 * No font binary is committed, and a missing one fails rather than skips.
 *
 * ## What makes an offset wrong noticeable
 *
 * Every glyph of this font is a PNG, and a PNG says its own width and height in
 * the `IHDR` chunk that ISO/IEC 15948 puts first. The `CBLC` tables say the same
 * two numbers, from an entirely different place in the file. So the check that
 * covers all four thousand glyphs is that the two agree — an index subtable read
 * one field out of step would land on a datastream whose header says something
 * else, or on no datastream at all.
 */
class SystemColorBitmapFontTest {
    private val font = TrueTypeFont.parse(
        systemFontBytes("NotoColorEmoji.ttf", WHY, debianPackage = "fonts-noto-color-emoji"),
    )
    private val strike = assertNotNull(font.cblc).strikes.single()

    @Test
    fun `the font carries one strike of colour bitmaps`() {
        // "the value of 32 is used to identify color bitmaps with 8 bit per
        // channel BGRA pixel data"
        assertEquals(3, assertNotNull(font.cblc).majorVersion)
        assertEquals(3, assertNotNull(font.cbdt).majorVersion)
        assertEquals(32, strike.bitDepth)
        assertEquals(strike.ppemX, strike.ppemY)
        assertTrue(strike.ppemX >= 32, "a colour strike of ${strike.ppemX} ppem is too small to be this font's")
        assertTrue(strike.metricsAreHorizontal)
        assertTrue(strike.startGlyphIndex < strike.endGlyphIndex)
        assertTrue(strike.endGlyphIndex < font.numGlyphs)
        assertTrue(
            strike.indexSubtables.size > 1,
            "the strike has ${strike.indexSubtables.size} index subtables; this font covers " +
                "discontiguous ranges of glyphs and needs \"an index subtable for each range or " +
                "format change\"",
        )
        assertEquals(strike, assertNotNull(font.cblc).largestStrike)
    }

    @Test
    fun `the index subtables cover ascending ranges of glyphs`() {
        // "Records must be sorted by firstGlyphIndex, and records should not have
        // overlapping glyph ID ranges."
        var previousLast = -1
        for (subtable in strike.indexSubtables) {
            assertTrue(
                subtable.firstGlyphIndex > previousLast,
                "the index subtable for glyphs ${subtable.firstGlyphIndex}..${subtable.lastGlyphIndex} " +
                    "starts at or before the end of the previous range, which ends at $previousLast",
            )
            assertTrue(subtable.firstGlyphIndex <= subtable.lastGlyphIndex)
            assertTrue(subtable.firstGlyphIndex >= strike.startGlyphIndex)
            assertTrue(subtable.lastGlyphIndex <= strike.endGlyphIndex)
            previousLast = subtable.lastGlyphIndex
        }
    }

    @Test
    fun `every glyph the strike locates is a PNG of the size the metrics claim`() {
        var found = 0
        for (glyphId in 0 until font.numGlyphs) {
            val glyph = font.colorBitmapOf(glyphId) ?: continue
            found++
            assertEquals(strike.ppemX, glyph.ppemX)
            assertEquals(strike.ppemY, glyph.ppemY)
            assertEquals(
                glyph.metrics.width to glyph.metrics.height,
                pngSize(glyph.png),
                "glyph $glyphId: the 'CBDT' metrics and the PNG's own IHDR chunk disagree on its size",
            )
            assertTrue(
                glyph.metrics.advance > 0,
                "glyph $glyphId advances ${glyph.metrics.advance} pixels, so a line of these would " +
                    "draw every emoji on top of the last",
            )
        }
        assertTrue(found > 1000, "only $found glyphs of ${font.numGlyphs} have a colour bitmap")
    }

    @Test
    fun `a glyph no index subtable covers has no bitmap`() {
        // The ranges are discontiguous, so glyphs fall between them; and glyph 0
        // is .notdef, below the strike's startGlyphIndex.
        assertNull(font.colorBitmapOf(0))
        val gaps = strike.indexSubtables.zipWithNext()
            .flatMap { (before, after) -> (before.lastGlyphIndex + 1) until after.firstGlyphIndex }
        assertTrue(gaps.isNotEmpty(), "this font's index subtables were expected to leave gaps")
        gaps.forEach { glyphId ->
            assertNull(font.colorBitmapOf(glyphId), "glyph $glyphId lies between two index subtable ranges")
        }
    }

    @Test
    fun `the emoji a document draws decode as pictures of the metrics size`() {
        // A grinning face, a party popper and a camera with flash: what an
        // emoji-bearing document puts on a page.
        listOf(0x1F600, 0x1F389, 0x1F4F8).forEach { codePoint ->
            val glyphId = font.glyphIdOf(codePoint)
            assertTrue(glyphId != 0, "the font has no glyph for U+${codePoint.toString(16).uppercase()}")
            val glyph = assertNotNull(
                font.colorBitmapOf(glyphId),
                "no colour bitmap for glyph $glyphId, U+${codePoint.toString(16).uppercase()}",
            )

            // ImageIO is used as a tool, the way qpdf and pdftotext are: it says
            // whether these bytes are a picture, not how to parse a font.
            val image = assertNotNull(
                ImageIO.read(ByteArrayInputStream(glyph.png)),
                "the ${glyph.png.size} bytes 'CBDT' stores for glyph $glyphId are not a readable PNG",
            )
            assertEquals(glyph.metrics.width, image.width)
            assertEquals(glyph.metrics.height, image.height)
            assertTrue(image.colorModel.hasAlpha(), "a colour emoji bitmap without an alpha channel")
        }
    }

    @Test
    fun `the bitmap advance and the hmtx advance describe the same font`() {
        // The two are measured in different units — "Horizontal advance width in
        // pixels" of a strike of ppemX pixels to the em, against the design units
        // of 'hmtx', unitsPerEm of them to the em — and are the same distance.
        val glyphId = font.glyphIdOf(0x1F600)
        val glyph = assertNotNull(font.colorBitmapOf(glyphId))

        val inPixels = glyph.metrics.advance.toDouble() / glyph.ppemX
        val inDesignUnits = font.advanceWidthOf(glyphId).toDouble() / font.unitsPerEm
        assertTrue(
            abs(inPixels - inDesignUnits) < 0.01,
            "the strike advances glyph $glyphId by ${glyph.metrics.advance}/${glyph.ppemX} = $inPixels em " +
                "and 'hmtx' by ${font.advanceWidthOf(glyphId)}/${font.unitsPerEm} = $inDesignUnits em",
        )
    }

    @Test
    fun `the strike line metrics reach in the directions hhea does`() {
        // "The horizontal and vertical line metrics contain the ascender,
        // descender, linegap, and advance information for the strike."
        assertTrue(strike.hori.ascender > 0, "the strike's ascender is ${strike.hori.ascender}")
        assertTrue(strike.hori.descender < 0, "the strike's descender is ${strike.hori.descender}")
        assertTrue(strike.hori.widthMax > 0)
        assertTrue(font.hhea.ascender > 0 && font.hhea.descender < 0)
    }

    /**
     * The width and height a PNG states for itself: the `IHDR` chunk, which
     * ISO/IEC 15948 requires to be the first chunk of the datastream, is eight
     * bytes of signature and eight bytes of chunk header in, and opens with two
     * four-byte integers.
     */
    private fun pngSize(png: ByteArray): Pair<Int, Int> {
        val reader = BigEndianReader.over(png, "a PNG datastream from the 'CBDT' table")
        reader.seek(8 + 4)
        assertEquals("IHDR", reader.tag(), "the first chunk of the datastream is not IHDR")
        return reader.uint32().toInt() to reader.uint32().toInt()
    }

    private companion object {
        const val WHY: String = "The CBLC and CBDT parser is checked"
    }
}
