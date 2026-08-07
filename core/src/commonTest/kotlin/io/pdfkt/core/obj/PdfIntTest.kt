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

// ISO 32000-2:2020 §7.3.3 "Numeric objects"

/**
 * What the clause requires of an integer object:
 *
 * > An integer shall be written as one or more decimal digits optionally
 * > preceded by a sign. The value shall be interpreted as a signed decimal
 * > integer and shall be converted to an integer object.
 *
 * > EXAMPLE 1 Integer objects
 * >
 * >     123    43445 +17    -98      0
 */
class PdfIntTest {
    /**
     * The clause's own example, written. `+17` is the one entry that comes
     * back differently: the leading PLUS SIGN is a spelling the clause admits
     * of a value, not a property of the value, and a writer that has to choose
     * one spelling chooses the shorter.
     */
    @Test
    fun `the values of Example 1 are written as decimal digits with a sign only when negative`() {
        assertEquals("123", serializeToText(PdfInt(123)))
        assertEquals("43445", serializeToText(PdfInt(43445)))
        assertEquals("17", serializeToText(PdfInt(17)))
        assertEquals("-98", serializeToText(PdfInt(-98)))
        assertEquals("0", serializeToText(PdfInt(0)))
    }

    @Test
    fun `an integer is written as ASCII digits`() {
        // "123" — §7.2.2 requires the structural tokens to be ASCII, so what
        // is asserted is the byte values, not the Kotlin spelling of them.
        assertContentEquals(byteArrayOf(0x31, 0x32, 0x33), serialize(PdfInt(123)))
        // "-98"
        assertContentEquals(byteArrayOf(0x2D, 0x39, 0x38), serialize(PdfInt(-98)))
    }

    @Test
    fun `every integer is written in the form the clause defines`() {
        for (value in SAMPLES) {
            assertIntegerSyntax(serialize(PdfInt(value)), "the integer $value")
        }
    }

    /**
     * No decimal point, no exponent, no radix marker and no digit grouping: an
     * integer object is digits. A grouping separator would be the visible
     * symptom of a locale reaching the output, and there is no locale to reach
     * it — the digits come from the value's own decimal expansion.
     */
    @Test
    fun `the digits written are the decimal expansion of the value`() {
        for (value in SAMPLES) {
            val text = serializeToText(PdfInt(value))
            assertEquals(value, text.toLong(), "the integer $value did not survive being written as $text")
        }
    }

    @Test
    fun `an integer keyword is a single token`() {
        for (value in SAMPLES) {
            assertSingleRegularToken(serialize(PdfInt(value)), "the integer $value")
        }
    }

    /**
     * Annex C observes that integers "can often be expressed within 32 bits";
     * that is advice about what processors accept, not a range this type
     * clips to. A value beyond it is written out, not silently truncated.
     */
    @Test
    fun `an integer beyond 32 bits is written in full`() {
        assertEquals("9223372036854775807", serializeToText(PdfInt(Long.MAX_VALUE)))
        assertEquals("-9223372036854775808", serializeToText(PdfInt(Long.MIN_VALUE)))
        assertEquals("4294967296", serializeToText(PdfInt(1L shl 32)))
    }

    @Test
    fun `an integer object carries the value it was built from`() {
        assertEquals(43445L, PdfInt(43445).value)
        assertEquals(PdfInt(17), PdfInt(17))
    }

    private companion object {
        val SAMPLES =
            listOf(
                0L, 1L, -1L, 9L, 10L, 98L, -98L, 123L, 17L, 43445L,
                999L, 1000L, -1000L, 2147483647L, -2147483648L, 1L shl 32,
                Long.MAX_VALUE, Long.MIN_VALUE,
            )
    }
}
