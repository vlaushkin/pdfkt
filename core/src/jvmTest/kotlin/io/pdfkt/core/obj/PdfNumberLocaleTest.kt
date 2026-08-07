/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.obj

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

// ISO 32000-2:2020 §7.3.3 "Numeric objects"

/**
 * A written number is the same bytes under every locale.
 *
 * §7.2.2 admits one decimal point — PERIOD (2Eh) — and §7.3.3 admits one set
 * of digits, the decimal ones. A default locale that spells 34.5 as `34,5`, or
 * that numbers with Arabic-Indic digits, must not reach the file: the result
 * would not be a numeric object at all, and it would depend on the machine the
 * document was produced on.
 *
 * commonMain has no locale-aware formatting available to reach for, and the
 * digits are derived from the value rather than borrowed from a platform, so
 * this holds by construction. It is asserted anyway, and here rather than in
 * commonTest, because the JVM is the target with an ambient default locale to
 * set — the failure this guards against can only be staged where the ambient
 * state exists.
 */
class PdfNumberLocaleTest {
    /** Decimal comma, and a grouping separator that a formatter might insert. */
    private val germany: Locale = Locale.GERMANY

    /** Arabic-Indic digits: a locale whose numbering system is not 0-9. */
    private val easternArabic: Locale = Locale.forLanguageTag("ar-EG-u-nu-arab")

    @Test
    fun `numbers are written identically under every default locale`() {
        val samples =
            listOf(
                PdfInt(43445), PdfInt(-98), PdfInt(Long.MAX_VALUE),
                PdfReal(34.5), PdfReal(-3.62), PdfReal(123.6), PdfReal(-0.002),
                PdfReal(1.0E20), PdfReal(1.0E-9), PdfReal(1234567.891),
            )
        val expected = withLocale(Locale.ROOT) { samples.map { serializeToText(it) } }

        for (locale in listOf(germany, easternArabic, Locale.FRANCE)) {
            val actual = withLocale(locale) { samples.map { serializeToText(it) } }
            assertEquals(expected, actual, "numbers were written differently under $locale")
        }
    }

    /** The decimal separator of the written form is PERIOD whatever the locale says. */
    @Test
    fun `the decimal separator is a period under a decimal-comma locale`() {
        assertEquals("1234567.891", withLocale(germany) { serializeToText(PdfReal(1234567.891)) })
        assertEquals("43445", withLocale(germany) { serializeToText(PdfInt(43445)) })
    }

    private fun <T> withLocale(locale: Locale, block: () -> T): T {
        val previous = Locale.getDefault()
        Locale.setDefault(locale)
        try {
            return block()
        } finally {
            Locale.setDefault(previous)
        }
    }
}
