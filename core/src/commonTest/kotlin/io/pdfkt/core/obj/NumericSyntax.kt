/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.obj

import kotlin.test.assertTrue
import kotlin.test.fail

// ISO 32000-2:2020 §7.3.3 "Numeric objects"

/**
 * Asserts that [bytes] is a written number in the form the clause defines:
 *
 * > An integer shall be written as one or more decimal digits optionally
 * > preceded by a sign.
 *
 * > A real value shall be written as one or more decimal digits with an
 * > optional sign and a leading, trailing, or embedded PERIOD (2Eh) (decimal
 * > point).
 *
 * Together: an optional sign, at least one decimal digit, and at most one
 * period anywhere among the digits. Nothing else — which is the same sentence
 * as the clause's closing prohibition read from the other side, since a radix
 * marker `#` and the `E` of an exponent are both characters this rejects.
 *
 * The bytes are checked numerically. §7.2.2 requires the tokens that describe
 * the structure of a PDF file to use the ASCII character set, so "these are
 * digits" is a claim about byte values, not about what a string decoder makes
 * of them.
 */
internal fun assertNumericSyntax(bytes: ByteArray, what: String) {
    assertTrue(bytes.isNotEmpty(), "$what produced no bytes")
    var index = 0
    if (bytes[0] == PLUS || bytes[0] == MINUS) index = 1
    var digits = 0
    var periods = 0
    while (index < bytes.size) {
        val byte = bytes[index]
        when {
            byte >= ZERO && byte <= NINE -> digits++
            byte == PERIOD -> periods++
            else -> fail("$what contains ${describe(byte)}, which §7.3.3 does not admit in a number")
        }
        index++
    }
    assertTrue(digits > 0, "$what has no decimal digit; §7.3.3 requires one or more")
    assertTrue(periods <= 1, "$what has $periods decimal points; §7.3.3 admits at most one")
}

/**
 * Asserts that [bytes] is the written form of an integer: the same production
 * without the period. The clause keeps the two apart in both directions — "A
 * real number shall not be present when an integer is expected" — so a writer
 * of integer objects that emitted `1.0` would be writing the wrong type of
 * numeric object even though the value is right.
 */
internal fun assertIntegerSyntax(bytes: ByteArray, what: String) {
    assertNumericSyntax(bytes, what)
    assertTrue(
        bytes.none { it == PERIOD },
        "$what contains a decimal point and is therefore written as a real, not an integer",
    )
}

private const val PLUS: Byte = 0x2B
private const val MINUS: Byte = 0x2D
private const val PERIOD: Byte = 0x2E
private const val ZERO: Byte = 0x30
private const val NINE: Byte = 0x39

private fun describe(byte: Byte): String {
    val code = byte.toInt() and 0xFF
    val printable = if (code in 0x21..0x7E) " '${code.toChar()}'" else ""
    return "byte 0x${code.toString(16).uppercase().padStart(2, '0')}$printable"
}
