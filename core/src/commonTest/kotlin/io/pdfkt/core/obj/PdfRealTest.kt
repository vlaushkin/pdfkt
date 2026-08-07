/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.obj

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

// ISO 32000-2:2020 §7.3.3 "Numeric objects"

/**
 * What the clause requires of a real object:
 *
 * > A real value shall be written as one or more decimal digits with an
 * > optional sign and a leading, trailing, or embedded PERIOD (2Eh) (decimal
 * > point). [...] Wherever a real number is expected, an integer may be used
 * > instead. For example, it is not necessary to write the number 1.0 in real
 * > format; the integer 1 is sufficient.
 *
 * > EXAMPLE 2 Real objects
 * >
 * >     34.5   -3.62 +123.6        4. -.002 0
 *
 * > A PDF writer shall not use the PostScript language syntax for numbers with
 * > non-decimal radices (such as 16#FFFE) or in exponential format (such as
 * > 6.02E23).
 *
 * The example is a list of forms a reader shall accept; a writer picks one of
 * them per value. The tests below pin the choice — no `+`, no leading `.`, no
 * trailing `.` — and then hold the prohibition against every magnitude a
 * double can hold, which is where a formatter borrowed from the platform
 * breaks.
 */
class PdfRealTest {
    /**
     * Each value of Example 2, written. Three come back in a different one of
     * the clause's admitted forms than the example spells them in: `+123.6`
     * loses a sign that says nothing, `4.` loses a period the clause itself
     * calls unnecessary, and `-.002` gains the integer part its form omits.
     * All three are the same numbers written the way this writer writes them.
     */
    @Test
    fun `the values of Example 2 are written in one admitted form each`() {
        assertEquals("34.5", serializeToText(PdfReal(34.5)))
        assertEquals("-3.62", serializeToText(PdfReal(-3.62)))
        assertEquals("123.6", serializeToText(PdfReal(123.6)))
        assertEquals("4", serializeToText(PdfReal(4.0)))
        assertEquals("-0.002", serializeToText(PdfReal(-0.002)))
        assertEquals("0", serializeToText(PdfReal(0.0)))
    }

    @Test
    fun `a real is written as ASCII digits and a period`() {
        // "34.5" — the byte values, not the Kotlin spelling of them (§7.2.2).
        assertContentEquals(byteArrayOf(0x33, 0x34, 0x2E, 0x35), serialize(PdfReal(34.5)))
        // "-0.002"
        assertContentEquals(
            byteArrayOf(0x2D, 0x30, 0x2E, 0x30, 0x30, 0x32),
            serialize(PdfReal(-0.002)),
        )
    }

    @Test
    fun `every real is written in the form the clause defines`() {
        for (value in ALL) {
            assertNumericSyntax(serialize(PdfReal(value)), "the real $value")
            assertSingleRegularToken(serialize(PdfReal(value)), "the real $value")
        }
    }

    /**
     * The clause's closing prohibition, held against the magnitudes that make
     * a platform formatter reach for an exponent. `Double.toString` writes
     * `6.02E23` here — and writes it differently per target — which is why
     * none of this comes from the platform.
     */
    @Test
    fun `no magnitude is written in exponential form or with a radix marker`() {
        for (value in ALL) {
            val text = serializeToText(PdfReal(value))
            for (forbidden in listOf('e', 'E', '#', '+')) {
                assertTrue(
                    forbidden !in text,
                    "the real $value was written as $text, which contains $forbidden",
                )
            }
        }
    }

    /** The clause's own example of the format a writer shall not use. */
    @Test
    fun `the number the prohibition names is written in positional notation`() {
        assertEquals("602000000000000000000000", serializeToText(PdfReal(6.02E23)))
    }

    /**
     * A magnitude far below one keeps every zero between the period and its
     * first significant digit. Writing `0` instead would be the silent loss
     * this library's no-silent-loss policy rules out — and it is the shape an
     * implementation that formats with a fixed number of decimal places falls
     * into.
     */
    @Test
    fun `a magnitude far below one is written out in full`() {
        assertEquals("0.000000001", serializeToText(PdfReal(1.0E-9)))

        val smallest = serializeToText(PdfReal(Double.MIN_VALUE))
        assertTrue(smallest.startsWith("0.0000"), "the smallest subnormal was written as $smallest")
        assertEquals(
            Double.MIN_VALUE,
            smallest.toDouble(),
            "the smallest subnormal did not survive being written as $smallest",
        )
    }

    /**
     * At the other end the digits run the other way. Rounding to fifteen
     * significant digits is visible here: `2^70` is an exactly representable
     * integer and is written with its last seven digits zeroed. That is the
     * documented split between the two numeric objects — an integer that has
     * to survive intact is a [PdfInt], which is exact by construction.
     */
    @Test
    fun `a magnitude far above one is written out in full`() {
        assertEquals("100000000000000000000", serializeToText(PdfReal(1.0E20)))
        assertEquals("1180591620717410000000", serializeToText(PdfReal(1180591620717411303424.0)))
    }

    /**
     * Fifteen significant decimal digits are what survives the decimal →
     * binary → decimal round trip, so a value a caller spelled out comes back
     * spelled the way they spelled it, and the tail a binary representation
     * adds below that does not reach the file.
     */
    @Test
    fun `the noise of the binary representation is not written`() {
        assertEquals("0.3", serializeToText(PdfReal(0.1 + 0.2)))
        assertEquals("0.333333333333333", serializeToText(PdfReal(1.0 / 3.0)))
        assertEquals("0.666666666666667", serializeToText(PdfReal(2.0 / 3.0)))
        assertEquals("1.1", serializeToText(PdfReal(1.1)))
        assertEquals("2.675", serializeToText(PdfReal(2.675)))
    }

    @Test
    fun `a value spelled with fifteen digits or fewer survives being written`() {
        for (value in SAMPLES) {
            val text = serializeToText(PdfReal(value))
            assertEquals(value, text.toDouble(), "the real $value did not survive being written as $text")
        }
    }

    /**
     * "It is not necessary to write the number 1.0 in real format; the integer
     * 1 is sufficient" — so an integral value keeps no trailing period and no
     * trailing zero, and neither does a fraction that ends in one.
     */
    @Test
    fun `an integral value is written without a decimal point`() {
        assertEquals("1", serializeToText(PdfReal(1.0)))
        assertEquals("-7", serializeToText(PdfReal(-7.0)))
        assertEquals("612", serializeToText(PdfReal(612.0)))
        assertEquals("0.5", serializeToText(PdfReal(0.500)))
        assertEquals("72.25", serializeToText(PdfReal(72.2500)))
    }

    /** The clause has one zero, and no sign distinguishes it from itself. */
    @Test
    fun `negative zero is written as zero`() {
        assertEquals("0", serializeToText(PdfReal(-0.0)))
        assertContentEquals(serialize(PdfReal(0.0)), serialize(PdfReal(-0.0)))
    }

    @Test
    fun `a sign is written when and only when the value is negative`() {
        for (value in ALL) {
            val text = serializeToText(PdfReal(value))
            assertEquals(value < 0.0, text.startsWith("-"), "the real $value was written as $text")
        }
    }

    /**
     * NaN and the infinities are not mathematical real numbers and the clause
     * has no spelling for them — the only ones a platform formatter offers,
     * `NaN` and `Infinity`, are not numbers in this grammar at all. They are
     * rejected where they are constructed rather than written out as
     * something a processor would misread.
     */
    @Test
    fun `a value that is not a real number is rejected`() {
        for (value in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException>("$value was accepted as a real object") {
                PdfReal(value)
            }
        }
    }

    @Test
    fun `a real object carries the value it was built from`() {
        assertEquals(34.5, PdfReal(34.5).value)
        assertEquals(PdfReal(-3.62), PdfReal(-3.62))
    }

    internal companion object {
        /** Values whose decimal spelling has at most fifteen digits. */
        val SAMPLES =
            listOf(
                0.0, 1.0, -1.0, 4.0, 34.5, -3.62, 123.6, -0.002, 0.5, -0.5,
                0.1, 0.25, 2.675, 612.0, 841.89, 595.275, 1.0E-9, -1.0E-9, 1.0E20,
                6.02E23, 1.0E-300, 3.14159265358979, 0.000123456789012345,
            )

        /**
         * Values chosen for the formatter's edges rather than for PDF: the
         * ends of the range, a subnormal, a sixteen-digit value that rounding
         * carries into a seventeenth, and an exactly representable integer
         * larger than fifteen digits.
         */
        val EXTREMES =
            listOf(
                Double.MIN_VALUE,
                Double.MAX_VALUE,
                1.0E308,
                1.0E-320,
                9.999999999999999E22,
                1180591620717411303424.0,
            )

        /** Everything the form of the written number is asserted over. */
        val ALL: List<Double> = SAMPLES + EXTREMES + EXTREMES.map { -it }
    }
}
