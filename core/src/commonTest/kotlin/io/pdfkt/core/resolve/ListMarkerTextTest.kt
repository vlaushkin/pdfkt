/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.resolve

import io.pdfkt.core.block.ListMarkerStyle
import kotlin.test.Test
import kotlin.test.assertEquals

// No clause applies: marker text is L4's own — the engine generates it during
// layout rather than expecting callers to supply it.

/** The text of a list marker: counting, numbering, and the two edges of each system. */
class ListMarkerTextTest {
    private fun marker(style: ListMarkerStyle, number: Int): String = listMarkerText(style, number, ".")

    /** The unordered markers are single characters and ignore the count. */
    @Test
    fun `unordered markers are a character each`() {
        assertEquals("•", marker(ListMarkerStyle.Bullet, 1))
        assertEquals("–", marker(ListMarkerStyle.Dash, 7))
        assertEquals("◦", marker(ListMarkerStyle.Circle, 99))
        assertEquals("▪", marker(ListMarkerStyle.Square, 0))
    }

    /** Decimal counts as anyone would expect, and takes the suffix the theme asked for. */
    @Test
    fun `decimal markers count and carry the suffix`() {
        assertEquals("1.", marker(ListMarkerStyle.Decimal, 1))
        assertEquals("42.", marker(ListMarkerStyle.Decimal, 42))
        assertEquals("7)", listMarkerText(ListMarkerStyle.Decimal, 7, ")"))
        assertEquals("3", listMarkerText(ListMarkerStyle.Decimal, 3, ""))
    }

    /**
     * The alphabet counts bijectively: there is no digit for zero, so `Z` is
     * followed by `AA` and not by `BA` — 26 is `Z`, 27 is `AA`, 52 is `AZ`, 53 is
     * `BA`.
     */
    @Test
    fun `alphabetic markers count bijectively in base 26`() {
        assertEquals("a.", marker(ListMarkerStyle.LowerAlpha, 1))
        assertEquals("z.", marker(ListMarkerStyle.LowerAlpha, 26))
        assertEquals("aa.", marker(ListMarkerStyle.LowerAlpha, 27))
        assertEquals("az.", marker(ListMarkerStyle.LowerAlpha, 52))
        assertEquals("ba.", marker(ListMarkerStyle.LowerAlpha, 53))
        assertEquals("A.", marker(ListMarkerStyle.UpperAlpha, 1))
        assertEquals("AB.", marker(ListMarkerStyle.UpperAlpha, 28))
    }

    /** Roman numerals in the subtractive form: 4 is IV, 9 is IX, 1994 is MCMXCIV. */
    @Test
    fun `roman markers are subtractive`() {
        assertEquals("I.", marker(ListMarkerStyle.UpperRoman, 1))
        assertEquals("IV.", marker(ListMarkerStyle.UpperRoman, 4))
        assertEquals("ix.", marker(ListMarkerStyle.LowerRoman, 9))
        assertEquals("XL.", marker(ListMarkerStyle.UpperRoman, 40))
        assertEquals("MCMXCIV.", marker(ListMarkerStyle.UpperRoman, 1994))
        assertEquals("MMMCMXCIX.", marker(ListMarkerStyle.UpperRoman, 3999))
    }

    /** Above the numerals' range the thousands simply repeat, which is the additive form. */
    @Test
    fun `roman numerals above 3999 repeat the thousand`() {
        assertEquals("MMMM.", marker(ListMarkerStyle.UpperRoman, 4000))
        assertEquals("MMMMM.", marker(ListMarkerStyle.UpperRoman, 5000))
    }

    /**
     * Neither system has a numeral for zero or for a negative, so a list that
     * starts below one is written in digits rather than in an invented numeral —
     * visibly odd, which is better than silently wrong.
     */
    @Test
    fun `counting below one falls back to digits`() {
        assertEquals("0.", marker(ListMarkerStyle.UpperRoman, 0))
        assertEquals("-1.", marker(ListMarkerStyle.LowerAlpha, -1))
    }
}
