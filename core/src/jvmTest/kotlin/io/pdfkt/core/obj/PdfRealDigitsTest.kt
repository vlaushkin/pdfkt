/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.obj

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ISO 32000-2:2020 §7.3.3 "Numeric objects"

/**
 * The digits of a written real, against an independent oracle.
 *
 * The clause tests state what the written form must look like; this one states
 * that the digits in it are the right digits. Writing a real means deriving
 * the exact decimal value of a binary floating-point number by integer
 * arithmetic and rounding it — arithmetic that is easy to get subtly wrong in
 * ways no example catches, so it is checked against something that computes
 * the same quantity a completely different way.
 *
 * The oracle is [BigDecimal]: `BigDecimal(double)` is the exact value of the
 * double, `round` to fifteen significant digits half-even is the same
 * rounding, `stripTrailingZeros` drops the same zeros, and `toPlainString` is
 * positional notation — the notation §7.3.3 requires. It is a JVM-only type,
 * which is why this test lives in jvmTest while the writer it checks is common
 * code: the arithmetic is platform-independent, so verifying it on one target
 * verifies it.
 */
class PdfRealDigitsTest {
    @Test
    fun `the written digits are the exact value of the double correctly rounded`() {
        for (value in PdfRealTest.ALL) {
            assertDigits(value)
        }
    }

    /**
     * Bit patterns rather than decimal literals, so the sweep reaches the
     * corners a hand-written list does not: subnormals, values whose exact
     * expansion runs to hundreds of digits, and magnitudes at both ends of the
     * exponent range. The seed is fixed — a failing case has to stay failing.
     */
    @Test
    fun `the written digits are correct across the range of the double`() {
        val random = Random(20200704)
        var checked = 0
        while (checked < 2000) {
            val value = Double.fromBits(random.nextLong())
            if (value.isNaN() || value.isInfinite()) continue
            assertDigits(value)
            checked++
        }
    }

    /**
     * Decimal literals of every length up to and past the fifteen digits that
     * are kept, which is where the rounding step is exercised rather than
     * skipped.
     */
    @Test
    fun `the written digits are correct for decimals of every length`() {
        val random = Random(20260804)
        for (digits in 1..18) {
            repeat(100) {
                val mantissa = random.nextLong(1L, POWERS_OF_TEN[digits])
                for (exponent in listOf(-20, -8, -1, 0, 1, 8)) {
                    assertDigits("${mantissa}e$exponent".toDouble())
                }
            }
        }
    }

    private fun assertDigits(value: Double) {
        val expected =
            BigDecimal(value)
                .round(MathContext(15, RoundingMode.HALF_EVEN))
                .stripTrailingZeros()
                .toPlainString()
        assertEquals(expected, serializeToText(PdfReal(value)), "the real ${bitsOf(value)} was written wrong")
    }

    /** A failure has to name the value in a form that reproduces it exactly. */
    private fun bitsOf(value: Double): String = "Double.fromBits(${value.toRawBits()}L)"

    private companion object {
        val POWERS_OF_TEN = LongArray(19) { index -> generateSequence(1L) { it * 10 }.elementAt(index) }
    }
}
