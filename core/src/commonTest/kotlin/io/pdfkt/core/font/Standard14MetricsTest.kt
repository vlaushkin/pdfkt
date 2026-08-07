/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.font

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// ISO 32000-2:2020 §9.6.2.2 "Standard Type 1 fonts (standard 14 fonts) (PDF 1.0-1.7)",
// §9.2.4 "Glyph positioning and metrics", §9.8.1 "Font descriptors — General"
// (Table 120); Adobe Font Metrics File Format Specification, technical note
// #5004 §7.1 "Global Font Information", §8 "Individual Character Metrics"

/**
 * The fourteen fonts of §9.6.2.2 measured from Adobe's AFM files for them.
 *
 * §9.6.2.2 is what makes this necessary. `Widths` and `FontDescriptor`
 * "ordinarily" are absent for these fonts, and then "PDF processors shall
 * provide glyph widths and font descriptor data for those standard fonts" — the
 * numbers live outside the file, in a set of files this library has to carry.
 * The numbers asserted below are the ones Adobe's files state, in the units
 * every AFM number is in, "units equal to 1/1000 of the scale factor (point
 * size) of the font" (#5004 §3.2), which is the glyph space of §9.2.4.
 *
 * They are asserted as literals because that is the only way to notice that the
 * table this module carries stopped being the table Adobe published — comparing
 * it against itself would notice nothing. The source of every literal is
 * third_party/adobe-core14-afm/<FontName>.afm.
 */
class Standard14MetricsTest {
    private fun metricsOf(name: Standard14Name): Standard14Metrics = Standard14Metrics(name)

    /**
     * All fourteen resolve, and each resolves to *its own* metrics: the `FontName`
     * of the AFM has to be the `/BaseFont` the clause spells for that font
     * (§9.6.2.2), or a document would be laid out with one font's widths and
     * drawn with another's.
     */
    @Test
    fun `every one of the fourteen has the AFM of the font it names`() {
        assertEquals(14, Standard14Name.entries.size)

        for (name in Standard14Name.entries) {
            val afm = metricsOf(name).afm
            assertEquals(name.baseFont.name, afm.fontName, "AFM of $name")
            assertTrue(afm.charMetrics.isNotEmpty(), "AFM of $name has no character metrics")
        }
    }

    /**
     * `Ascender` and `Descender` of #5004 §7.1, which are what Table 120 asks a
     * font descriptor's `Ascent` and `Descent` for. Twelve of the fourteen state
     * both; the numbers below are theirs.
     *
     * They come out in glyph space, so [FontMetrics] scaling them by size / 1000
     * makes a 1000-point Helvetica reach 718 points above its baseline.
     */
    @Test
    fun `ascent and descent are the AFM Ascender and Descender`() {
        val expected =
            mapOf(
                Standard14Name.TIMES_ROMAN to (683.0 to -217.0),
                Standard14Name.TIMES_BOLD to (683.0 to -217.0),
                Standard14Name.TIMES_ITALIC to (683.0 to -217.0),
                Standard14Name.TIMES_BOLD_ITALIC to (683.0 to -217.0),
                Standard14Name.HELVETICA to (718.0 to -207.0),
                Standard14Name.HELVETICA_BOLD to (718.0 to -207.0),
                Standard14Name.HELVETICA_OBLIQUE to (718.0 to -207.0),
                Standard14Name.HELVETICA_BOLD_OBLIQUE to (718.0 to -207.0),
                Standard14Name.COURIER to (629.0 to -157.0),
                Standard14Name.COURIER_BOLD to (629.0 to -157.0),
                Standard14Name.COURIER_OBLIQUE to (629.0 to -157.0),
                Standard14Name.COURIER_BOLD_OBLIQUE to (629.0 to -157.0),
            )

        for ((name, extents) in expected) {
            val metrics = metricsOf(name)
            assertEquals(extents.first, metrics.ascent, "ascent of $name")
            assertEquals(extents.second, metrics.descent, "descent of $name")
            assertTrue(metrics.descent < 0.0, "descent of $name is negative (Table 120)")
        }
    }

    /**
     * Symbol and ZapfDingbats state no `Ascender`, `Descender` or `CapHeight` —
     * the keywords are optional in #5004 §7.1 and are described in terms of a
     * lowercase d, a lowercase p and a capital H, none of which these two fonts
     * have. Their extents come from `FontBBox` instead, which they do state:
     * "the lower left corner and the upper right corner of the font bounding
     * box".
     */
    @Test
    fun `the two fonts without an Ascender fall back to the font bounding box`() {
        val symbol = metricsOf(Standard14Name.SYMBOL)
        assertNull(symbol.afm.ascender)
        assertNull(symbol.afm.descender)
        assertNull(symbol.afm.capHeight)
        assertEquals(1010.0, symbol.ascent)
        assertEquals(-293.0, symbol.descent)

        val dingbats = metricsOf(Standard14Name.ZAPF_DINGBATS)
        assertNull(dingbats.afm.ascender)
        assertEquals(820.0, dingbats.ascent)
        assertEquals(-143.0, dingbats.descent)
    }

    /**
     * `CapHeight` and `FontBBox` per font — the entries Table 120 names
     * `CapHeight` and `FontBBox` in a font descriptor, and the ones that tell a
     * layout how tall a line of capitals is.
     */
    @Test
    fun `cap heights and font bounding boxes are the AFM values`() {
        assertEquals(662.0, metricsOf(Standard14Name.TIMES_ROMAN).afm.capHeight)
        assertEquals(676.0, metricsOf(Standard14Name.TIMES_BOLD).afm.capHeight)
        assertEquals(653.0, metricsOf(Standard14Name.TIMES_ITALIC).afm.capHeight)
        assertEquals(718.0, metricsOf(Standard14Name.HELVETICA).afm.capHeight)
        assertEquals(562.0, metricsOf(Standard14Name.COURIER).afm.capHeight)

        val box: AfmBBox = metricsOf(Standard14Name.HELVETICA).afm.fontBBox
        assertEquals(-166.0, box.llx)
        assertEquals(-225.0, box.lly)
        assertEquals(1000.0, box.urx)
        assertEquals(931.0, box.ury)

        assertEquals(450.0, metricsOf(Standard14Name.TIMES_ROMAN).afm.xHeight)
    }

    /**
     * The widths themselves, by glyph name — the form an encoding of Annex D
     * reaches them by — and by the code of the font's own default encoding.
     *
     * Courier is the check with a shape of its own: it is the monospaced font of
     * the fourteen, so every one of its glyphs advances by the same 600 glyph
     * units, and a width table that lost its per-glyph values would still pass a
     * test written against Courier alone. Hence the proportional fonts beside it.
     */
    @Test
    fun `widths come from the AFM character metrics`() {
        val helvetica = metricsOf(Standard14Name.HELVETICA).afm
        assertEquals(278.0, helvetica.widthOfGlyph("space"))
        assertEquals(667.0, helvetica.widthOfGlyph("A"))
        assertEquals(222.0, helvetica.widthOfGlyph("i"))
        assertEquals(944.0, helvetica.widthOfGlyph("W"))
        assertEquals(667.0, helvetica.widthOfCode(65))
        assertEquals("A", helvetica.glyphNameOfCode(65))

        val times = metricsOf(Standard14Name.TIMES_ROMAN).afm
        assertEquals(250.0, times.widthOfGlyph("space"))
        assertEquals(722.0, times.widthOfGlyph("A"))
        assertEquals(333.0, times.widthOfGlyph("f"))

        val courier = metricsOf(Standard14Name.COURIER).afm
        for (metric: AfmCharMetric in courier.charMetrics) {
            assertEquals(600.0, metric.width, "Courier is monospaced: ${metric.glyphName}")
        }
    }

    /**
     * The glyphs beyond the font's own default encoding are in the table too,
     * with the code −1 that #5004 §8 gives an unencoded character. They are what
     * makes WinAnsiEncoding (Annex D) possible at all for these fonts: it puts
     * glyphs at codes AdobeStandardEncoding does not, and their widths have to
     * exist to be found.
     */
    @Test
    fun `unencoded glyphs carry widths for an encoding to reach`() {
        val times = metricsOf(Standard14Name.TIMES_ROMAN).afm

        assertEquals(444.0, times.widthOfGlyph("ccedilla"))
        assertEquals(500.0, times.widthOfGlyph("ydieresis"))
        assertEquals(750.0, times.widthOfGlyph("onehalf"))
        assertEquals(760.0, times.widthOfGlyph("registered"))
        assertNotNull(times.widthOfGlyph("Euro"))
    }

    /**
     * A string is measured by the route §9.6.5.1 describes: a character becomes
     * a code (Table D.2), the code names a glyph, and the glyph's `WX` is the
     * width. The sum below is the AFM's own numbers for those glyph names —
     * Helvetica's `H` is 722, `i` is 222 — so a wrong table anywhere along the
     * route shows up as a wrong total rather than as a plausible one.
     */
    @Test
    fun `a string is measured through the encoding of Annex D`() {
        val helvetica = metricsOf(Standard14Name.HELVETICA)

        assertEquals(722.0, helvetica.advanceOf("H"))
        assertEquals(222.0, helvetica.advanceOf("i"))
        assertEquals(722.0 + 222.0, helvetica.advanceOf("Hi"))
        assertEquals(0.0, helvetica.advanceOf(""))

        // The characters above 177 octal go through the same route; Table D.2
        // puts the euro at 200 octal and Adobe's file names that glyph "Euro".
        assertEquals(helvetica.afm.widthOfGlyph("Euro"), helvetica.advanceOf("€"))
        assertEquals(helvetica.afm.widthOfGlyph("adieresis"), helvetica.advanceOf("ä"))

        // Courier is monospaced, so a string of n characters is 600n wide
        // whichever characters they are.
        val courier = metricsOf(Standard14Name.COURIER)
        assertEquals(600.0 * 5, courier.advanceOf("Größe"))
    }

    /**
     * Nothing in this library guesses. A character WinAnsiEncoding has no code
     * for is refused with the character, its position and the remedy attached —
     * the "no silent loss" policy. Without it, an unmeasurable character
     * renders as fully blank Cyrillic documents with every test
     * green. A width of 0 here would be exactly that failure.
     */
    @Test
    fun `a character outside the encoding is refused rather than measured`() {
        val failure =
            assertFailsWith<UnencodableCharacterException> {
                metricsOf(Standard14Name.HELVETICA).advanceOf("Привет")
            }

        assertEquals(0, failure.index)
        assertEquals("П", failure.character)
        assertEquals("Helvetica", failure.fontName)
        assertTrue(failure.message!!.contains("supply a font"), failure.message)
    }

    /**
     * Symbol and ZapfDingbats are the two symbolic fonts of §9.6.5.1, whose
     * character sets and built-in encodings are D.5 and D.6 rather than
     * Table D.2. Measuring `A` in Symbol through WinAnsiEncoding would ask for a
     * glyph named `A`, which Symbol does not have — its code 101 octal is
     * `Alpha` — so the encoding is refused outright instead of half-applied.
     */
    @Test
    fun `the two symbolic fonts do not use the Latin-text encoding`() {
        for (name in listOf(Standard14Name.SYMBOL, Standard14Name.ZAPF_DINGBATS)) {
            val metrics = metricsOf(name)
            assertNull(metrics.afm.widthOfGlyph("A"), "$name has a glyph named A")

            val failure = assertFailsWith<UnsupportedOperationException> { metrics.advanceOf("A") }
            assertTrue(failure.message!!.contains(name.baseFont.name), failure.message)
            assertTrue(failure.message!!.contains("D.5"), failure.message)
            assertTrue(failure.message!!.contains("D.6"), failure.message)
        }

        assertEquals(
            listOf(Standard14Name.SYMBOL, Standard14Name.ZAPF_DINGBATS),
            Standard14Name.entries.filter { it.symbolic },
        )
    }

    /**
     * The twelve Latin fonts have every one of the 216 glyphs Table D.2 names.
     * They must: a standard 14 font carries no `Widths` array, so a glyph the
     * encoding reaches and the metrics do not describe is a character that can
     * be drawn and cannot be measured.
     */
    @Test
    fun `the twelve Latin fonts carry a width for every character of Table D-2`() {
        for (name in Standard14Name.entries.filterNot { it.symbolic }) {
            val metrics = metricsOf(name)
            for (code in 0..MAX_CODE) {
                val glyphName = WinAnsiEncoding.glyphNameOf(code) ?: continue
                assertNotNull(metrics.afm.widthOfGlyph(glyphName), "$name has no width for $glyphName")
            }
        }
    }

    /**
     * A standard 14 font has no leading of its own: AFM describes a font program
     * and the gap between lines is not a property of one, so Table 120's default
     * of 0 stands and a line is exactly as tall as the font reaches.
     */
    @Test
    fun `the standard 14 have no leading of their own`() {
        for (name in Standard14Name.entries) {
            assertEquals(0.0, metricsOf(name).leading, "leading of $name")
        }
    }

    private companion object {
        /** A simple font's character codes are single bytes (§9.6.5.1). */
        const val MAX_CODE: Int = 0xFF
    }
}
