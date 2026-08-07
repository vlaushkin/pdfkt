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
import kotlin.test.assertSame
import kotlin.test.assertTrue

// ISO 32000-2:2020 §9.2.4 "Glyph positioning and metrics",
// §9.8.1 "Font descriptors — General" (Table 120)

/**
 * The contract L4 sees, and the one arithmetic rule behind
 * it. §9.2.4 fixes the units the answer comes back in:
 *
 * > The glyph coordinate system is the space in which an individual character's
 * > glyph is defined. All path coordinates and metrics shall be interpreted in
 * > glyph space. For all font types except Type 3, the units of glyph space are
 * > one-thousandth of a unit of text space.
 *
 * A font program measures in glyph space and knows nothing of the size a caller
 * asks for; [FontMetrics] answers in text space, which for text drawn at size
 * *s* means glyph units times *s* divided by 1000. Every answer below is that
 * one multiplication, and the numbers are chosen so it is legible: an extent of
 * 1000 glyph units is exactly the font size.
 *
 * The metrics here belong to no font. They are a stated set of glyph-space
 * extents, which is all [FontMetrics] needs to be pinned down — where those
 * extents come from is the business of the two paths (AFM for the standard 14,
 * the font program's own tables for a supplied font), not of this rule.
 */
class FontMetricsContractTest {
    /**
     * A width source whose every glyph advances the same amount, standing in for
     * the per-glyph tables the two paths parse. The extents are those Table 120
     * names: `Ascent`, "The maximum height above the baseline reached by glyphs
     * in this font"; `Descent`, "The value shall be a negative number";
     * `Leading`, "The spacing between baselines of consecutive lines of text.
     * Default value: 0".
     */
    private class EvenGlyphSpaceMetrics(
        private val advancePerCharacter: Double,
        override val ascent: Double,
        override val descent: Double,
        override val leading: Double = 0.0,
    ) : GlyphSpaceMetrics {
        override fun advanceOf(text: String): Double = advancePerCharacter * text.length
    }

    private fun metricsOf(leading: Double = 0.0): FontMetrics = TextSpaceMetrics(
        EvenGlyphSpaceMetrics(
            advancePerCharacter = 500.0,
            ascent = 750.0,
            descent = -250.0,
            leading = leading,
        ),
    )

    /**
     * "it is the distance the current text position shall move (by translating
     * text space) when the glyph is painted" (§9.2.4) — three glyphs of 500
     * glyph units at size 16 move the position by 3 × 500 × 16 / 1000.
     */
    @Test
    fun `a width is the sum of the glyph advances in text space`() {
        val metrics = metricsOf()

        assertEquals(24.0f, metrics.widthOf("abc", 16f), TOLERANCE)
        assertEquals(8.0f, metrics.widthOf("a", 16f), TOLERANCE)
        assertEquals(12.0f, metrics.widthOf("abc", 8f), TOLERANCE)
    }

    /** Nothing drawn moves the text position by nothing. */
    @Test
    fun `the width of the empty string is zero`() {
        assertEquals(0.0f, metricsOf().widthOf("", 16f), TOLERANCE)
    }

    /**
     * Ascent and descent scale by the same rule, and the sign of the descent
     * survives it: Table 120 requires the descent to be negative, so a caller
     * who adds it to a baseline moves down the page and does not have to know
     * which way this library happened to point it.
     */
    @Test
    fun `ascent and descent are the glyph-space extents in text space`() {
        val metrics = metricsOf()

        assertEquals(12.0f, metrics.ascent(16f), TOLERANCE)
        assertEquals(-4.0f, metrics.descent(16f), TOLERANCE)
        assertTrue(metrics.descent(16f) < 0f)
    }

    /**
     * A line is as tall as the font reaches above the baseline plus as deep as
     * it reaches below it, plus whatever the font asks be left between lines.
     * The descent is negative, so it is subtracted.
     *
     * Table 120's `Leading` is "the spacing between baselines of consecutive
     * lines of text" with a default of 0 — a default that only makes sense if
     * the entry is the spacing *added* to the extents rather than the whole
     * baseline-to-baseline distance, since a baseline distance of zero would
     * stack every line of every font without one on top of the last. It is read
     * as the added gap here, and that reading is why 1000 units of extent and no
     * leading make a line exactly one em tall.
     */
    @Test
    fun `a line is the ascent above and the descent below plus the leading`() {
        assertEquals(16.0f, metricsOf().lineHeight(16f), TOLERANCE)
        assertEquals(12.0f, metricsOf(leading = 200.0).lineHeight(10f), TOLERANCE)
    }

    /**
     * Both font paths — standard-14 metrics and embedded programs — answer
     * through the same interface, and the
     * catalog is where a caller gets one. What it answers *with* is the width
     * table of the path, which is why this asserts the shape and not a number.
     */
    @Test
    fun `the catalog answers with metrics for both paths`() {
        val catalog = FontCatalog()

        val standard: ResolvedFont = catalog.resolve(FontRef.Standard14(Standard14Name.HELVETICA))
        val supplied: ResolvedFont = catalog.resolve(FontRef.Supplied(byteArrayOf(0, 1), "body"))

        assertTrue(standard.metrics !== supplied.metrics)
        assertSame(standard.metrics, catalog.metricsOf(FontRef.Standard14(Standard14Name.HELVETICA)))
    }

    /**
     * The standard 14 answer their extents from the AFM metrics of the font,
     * through the same scaling as
     * everything else: Helvetica states `Ascender 718` and `Descender -207` in
     * glyph space, so at 1000 points it reaches 718 above the baseline and 207
     * below, and a line of it is 925 tall.
     */
    @Test
    fun `the standard 14 measure from their AFM metrics`() {
        val metrics = FontCatalog().metricsOf(FontRef.Standard14(Standard14Name.HELVETICA))

        assertEquals(718.0f, metrics.ascent(1000f), TOLERANCE)
        assertEquals(-207.0f, metrics.descent(1000f), TOLERANCE)
        assertEquals(925.0f, metrics.lineHeight(1000f), TOLERANCE)
        assertEquals(11.488f, metrics.ascent(16f), 1e-3f)
    }

    /**
     * A standard 14 font measures a string through WinAnsiEncoding (Annex D):
     * Helvetica's `H` is 722 glyph units and its `i` is 222, so `Hi` at 1000
     * points is 944 wide in text space.
     */
    @Test
    fun `the standard 14 measure a string through the encoding of Annex D`() {
        val metrics = FontCatalog().metricsOf(FontRef.Standard14(Standard14Name.HELVETICA))

        assertEquals(944.0f, metrics.widthOf("Hi", 1000f), TOLERANCE)
        assertEquals(15.104f, metrics.widthOf("Hi", 16f), 1e-3f)
        assertEquals(0.0f, metrics.widthOf("", 16f), TOLERANCE)
    }

    /**
     * The acceptance of the "no silent loss" policy, at the level a caller
     * meets it: a character outside WinAnsiEncoding drawn with a
     * [FontRef.Standard14] is an error naming the character, where it is, and
     * what to do about it — never a dropped glyph, never a substituted one.
     *
     * The failure this guards against is not a crash: it is a run of
     * Cyrillic documents that come out fully blank with every test
     * green. Nothing about a blank page is detectable from
     * a return value, which is why the remedy is spelled out in the message
     * rather than left to a reader of these docs.
     */
    @Test
    fun `a character outside the encoding raises with the character its position and the remedy`() {
        val metrics = FontCatalog().metricsOf(FontRef.Standard14(Standard14Name.HELVETICA))

        val failure = assertFailsWith<UnencodableCharacterException> { metrics.widthOf("Hello, мир", 16f) }

        assertEquals("м", failure.character)
        assertEquals(7, failure.index)
        assertEquals("Helvetica", failure.fontName)

        val message = failure.message!!
        assertTrue(message.contains("'м'"), message)
        assertTrue(message.contains("U+043C"), message)
        assertTrue(message.contains("index 7"), message)
        assertTrue(message.contains("Helvetica"), message)
        assertTrue(message.contains("supply a font"), message)
        assertTrue(message.contains("FontRef.Supplied"), message)

        // It is an IllegalArgumentException, which is what widthOf documents,
        // so a caller that only wants "the measurement failed" needs to know
        // nothing about encodings.
        assertFailsWith<IllegalArgumentException> { metrics.widthOf("мир", 16f) }
    }

    /**
     * The same contract over the other path: a supplied font is measured from
     * its own tables, in the units of §9.2.4 rather than the program's.
     *
     * The font below counts 2048 units to the em — NOTE 3's own example of the
     * discrepancy — so every number here is one the conversion had to perform.
     * `B` is glyph 2 of it, 700 units wide; `hhea` gives the extents.
     */
    @Test
    fun `a supplied font measures from the program it was supplied as`() {
        val metrics = FontCatalog().metricsOf(FontRef.Supplied(suppliedFontBytes(unitsPerEm = 2048), "body"))

        assertEquals(700f * 16 / 2048, metrics.widthOf("B", 16f), TOLERANCE)
        assertEquals(800f * 16 / 2048, metrics.ascent(16f), TOLERANCE)
        assertEquals(-200f * 16 / 2048, metrics.descent(16f), TOLERANCE)
        // Ascent, minus the negative descent, plus hhea's lineGap of 100.
        assertEquals(1100f * 16 / 2048, metrics.lineHeight(16f), TOLERANCE)
    }

    /**
     * Bytes that are not a font program are refused, with the field that made
     * the parser say so. Answering 0 instead would be a document whose text
     * overlaps or runs off the page, produced by a run that reported success.
     */
    @Test
    fun `bytes that are not a font program refuse to measure anything`() {
        val supplied = FontCatalog().metricsOf(FontRef.Supplied(byteArrayOf(0, 1), "body"))

        val refused = assertFailsWith<IllegalArgumentException> { supplied.widthOf("A", 16f) }

        assertTrue(refused.message.orEmpty().isNotEmpty(), "a refusal that says nothing")
        assertFailsWith<IllegalArgumentException> { supplied.ascent(16f) }
        assertFailsWith<IllegalArgumentException> { supplied.descent(16f) }
        assertFailsWith<IllegalArgumentException> { supplied.lineHeight(16f) }
    }

    /**
     * A character the supplied program has no glyph for is refused exactly as a
     * character outside a byte encoding is — same type, same position, and a
     * remedy that fits the path it came from.
     */
    @Test
    fun `a character the supplied program has no glyph for raises with its position`() {
        val metrics = FontCatalog().metricsOf(FontRef.Supplied(suppliedFontBytes(), "body"))

        val failure = assertFailsWith<UnencodableCharacterException> { metrics.widthOf("BCZ", 16f) }

        assertEquals("Z", failure.character)
        assertEquals(2, failure.index)
        assertEquals("body", failure.fontName)
        assertTrue("supply a font program whose glyphs cover it" in failure.message.orEmpty(), failure.message)
    }

    private companion object {
        /**
         * Text space is a real-number space and the arithmetic is a scaling; the
         * comparison is to the width of a hair on a page, not to the last bit of
         * a float.
         */
        const val TOLERANCE: Float = 1e-6f
    }
}
