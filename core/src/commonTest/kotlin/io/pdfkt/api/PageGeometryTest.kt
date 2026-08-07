/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.api

import io.pdfkt.core.model.Insets
import io.pdfkt.core.model.Pt
import io.pdfkt.core.model.Size
import io.pdfkt.core.model.pt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [PageSize] and [Margins] against the geometry the engine is handed.
 *
 * The A-series values are ISO 216 millimetres converted at 25.4 mm to the inch,
 * in the 1 ⁄ 72 inch units of ISO 32000-2:2020 §8.3.2.3 — not a rounding of them
 * kept for compatibility with anything.
 */
class PageGeometryTest {

    @Test
    fun a4IsTwoHundredAndTenMillimetresByTwoHundredAndNinetySeven() {
        val size = PageSize.A4.toSize()

        assertClose(595.276f, size.width)
        assertClose(841.890f, size.height)
        assertEquals(Size.A4, size)
    }

    @Test
    fun a5IsHalfOfA4() {
        val size = PageSize.A5.toSize()

        assertClose(419.528f, size.width)
        assertClose(595.276f, size.height)
        // Halving A4 along its long side gives A5, which is the whole of the
        // A series: the short side of A4 is the long side of A5.
        assertClose(PageSize.A4.toSize().width.value, size.height.value)
    }

    @Test
    fun theInchSizesAreExact() {
        assertEquals(Size(Pt(612.0f), Pt(792.0f)), PageSize.Letter.toSize())
        assertEquals(Size(Pt(612.0f), Pt(1008.0f)), PageSize.Legal.toSize())
    }

    @Test
    fun aCustomSheetIsWhateverItWasGiven() {
        assertEquals(Size(200.pt, 400.pt), PageSize.Custom(200.pt, 400.pt).toSize())
    }

    @Test
    fun marginsAreNamedInTheOrderACallerSaysThemAndKeptInTheEnginesOrder() {
        val insets = Margins.Fixed(top = 10.pt, right = 20.pt, bottom = 30.pt, left = 40.pt).toInsets()

        assertEquals(Insets(left = 40.pt, bottom = 30.pt, right = 20.pt, top = 10.pt), insets)
        assertEquals(60.pt, insets.horizontal)
        assertEquals(40.pt, insets.vertical)
    }

    @Test
    fun theUnstatedMarginIsHalfAnInch() {
        assertEquals(Insets.uniform(36.pt), Margins.Fixed().toInsets())
    }

    private fun assertClose(expected: Float, actual: Pt) = assertClose(expected, actual.value)

    private fun assertClose(expected: Float, actual: Float) {
        val difference = if (expected > actual) expected - actual else actual - expected
        assertTrue(difference < TOLERANCE, "expected $expected, got $actual")
    }
}

/** A thousandth of a point: the values are stated to three decimals. */
private const val TOLERANCE: Float = 0.001f
