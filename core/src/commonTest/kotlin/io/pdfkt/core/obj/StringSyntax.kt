/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.obj

import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlin.test.assertTrue
import kotlin.test.fail

// ISO 32000-2:2020 §7.3.4 "String objects"

/**
 * Reads a written string object back into the series of bytes it denotes,
 * following the reading rules the clause states and nothing else.
 *
 * This exists so that a round-trip test asserts on the *value* that comes back
 * rather than on one particular spelling of it: the clause leaves a writer
 * several choices — a parenthesis of a balanced pair may be escaped or not, an
 * 8-bit value may be written "either as itself or with the \\ddd notation" —
 * so equality of written bytes would be a stricter claim than the clause makes.
 * Nothing about how [writePdfString] resolves those choices is assumed here.
 *
 * The form is told apart by the character that opens it: LEFT PARENTHESIS for
 * the literal form of §7.3.4.2, LESS-THAN SIGN for the hexadecimal form of
 * §7.3.4.3. The whole of [bytes] shall be that one string object — a token
 * that ended early would leave trailing bytes, which is a failure here.
 */
internal fun decodePdfString(bytes: ByteArray): ByteArray {
    assertTrue(bytes.isNotEmpty(), "the string under test produced no bytes")
    return when (bytes[0]) {
        LEFT_PARENTHESIS_BYTE -> decodeLiteralString(bytes)
        LESS_THAN_BYTE -> decodeHexadecimalString(bytes)
        else -> fail(
            "a string object is enclosed in parentheses or in angle brackets, unlike ${bytes.decodeToString()}",
        )
    }
}

/**
 * §7.3.4.2, in the reading direction:
 *
 * > Any characters may appear in a string except unbalanced parentheses and
 * > the backslash (REVERSE SOLIDUS (5Ch)) [...] Balanced pairs of parentheses
 * > within a string require no special treatment.
 *
 * > Within a literal string, the REVERSE SOLIDUS is used as an escape
 * > character. [...] If the character following the REVERSE SOLIDUS is not one
 * > of those shown in "Table 3 — Escape sequences in literal strings", the
 * > REVERSE SOLIDUS shall be ignored.
 *
 * > The REVERSE SOLIDUS (5Ch) (backslash character) at the end of a line shall
 * > be used to indicate that the string continues on the following line. A PDF
 * > processor shall disregard the REVERSE SOLIDUS and the end-of-line marker
 * > following it when reading the string.
 *
 * > An end-of-line marker appearing within a literal string without a
 * > preceding REVERSE SOLIDUS shall be treated as a byte value of (0Ah),
 * > irrespective of whether the end-of-line marker was a CARRIAGE RETURN
 * > (0Dh), a LINE FEED (0Ah), or both.
 */
private fun decodeLiteralString(bytes: ByteArray): ByteArray {
    val decoded = Buffer()
    var depth = 1
    var index = 1
    while (index < bytes.size) {
        val byte = bytes[index]
        when {
            byte == REVERSE_SOLIDUS_BYTE -> {
                assertTrue(index + 1 < bytes.size, "a REVERSE SOLIDUS is the last byte of the literal string")
                index = decodeEscape(bytes, index + 1, decoded)
            }

            byte == LEFT_PARENTHESIS_BYTE -> {
                depth++
                decoded.writeByte(byte)
                index++
            }

            byte == RIGHT_PARENTHESIS_BYTE -> {
                depth--
                if (depth == 0) {
                    assertTrue(
                        index == bytes.size - 1,
                        "the literal string ends at $index, leaving ${bytes.size - 1 - index} trailing bytes",
                    )
                    return decoded.readByteArray()
                }
                decoded.writeByte(byte)
                index++
            }

            byte == CARRIAGE_RETURN_BYTE || byte == LINE_FEED_BYTE -> {
                decoded.writeByte(LINE_FEED_BYTE)
                index += endOfLineLength(bytes, index)
            }

            else -> {
                decoded.writeByte(byte)
                index++
            }
        }
    }
    fail("the literal string is never closed by a RIGHT PARENTHESIS")
}

/**
 * Decodes what follows a REVERSE SOLIDUS, starting at [index], and returns the
 * index just after the sequence. Table 3 in full: the named sequences, the
 * `\ddd` octal one — "The number ddd may consist of one, two, or three octal
 * digits; high-order overflow shall be ignored" — the end-of-line that
 * continues the string, and the ignored REVERSE SOLIDUS before anything else.
 */
private fun decodeEscape(bytes: ByteArray, index: Int, decoded: Buffer): Int {
    val byte = bytes[index]
    NAMED_ESCAPES[byte]?.let {
        decoded.writeByte(it)
        return index + 1
    }
    if (byte == CARRIAGE_RETURN_BYTE || byte == LINE_FEED_BYTE) {
        return index + endOfLineLength(bytes, index)
    }
    if (byte in OCTAL_DIGIT_FIRST..OCTAL_DIGIT_LAST) {
        var value = 0
        var digits = 0
        var at = index
        while (digits < 3 && at < bytes.size && bytes[at] in OCTAL_DIGIT_FIRST..OCTAL_DIGIT_LAST) {
            value = (value * 8 + (bytes[at] - OCTAL_DIGIT_FIRST)) and 0xFF
            digits++
            at++
        }
        decoded.writeByte(value.toByte())
        return at
    }
    decoded.writeByte(byte)
    return index + 1
}

/** One end-of-line marker: CARRIAGE RETURN, LINE FEED, or both, in bytes. */
private fun endOfLineLength(bytes: ByteArray, index: Int): Int =
    if (bytes[index] == CARRIAGE_RETURN_BYTE && index + 1 < bytes.size && bytes[index + 1] == LINE_FEED_BYTE) {
        2
    } else {
        1
    }

/**
 * §7.3.4.3, in the reading direction:
 *
 * > Each pair of hexadecimal digits defines one byte of the string.
 * > White-space characters (see "Table 1 — White-space characters") shall be
 * > ignored.
 *
 * > If the final digit of a hexadecimal string is missing — that is, if there
 * > is an odd number of digits — the final digit shall be assumed to be 0.
 */
private fun decodeHexadecimalString(bytes: ByteArray): ByteArray {
    val decoded = Buffer()
    var pending = -1
    var index = 1
    while (index < bytes.size) {
        val byte = bytes[index]
        if (byte == GREATER_THAN_BYTE) {
            assertTrue(
                index == bytes.size - 1,
                "the hexadecimal string ends at $index, leaving ${bytes.size - 1 - index} trailing bytes",
            )
            if (pending >= 0) decoded.writeByte((pending shl 4).toByte())
            return decoded.readByteArray()
        }
        if (byte !in STRING_WHITE_SPACE) {
            val value = hexDigitValue(byte)
            assertTrue(value >= 0, "the hexadecimal string contains ${byte.toInt()}, which is not a hexadecimal digit")
            if (pending < 0) {
                pending = value
            } else {
                decoded.writeByte(((pending shl 4) or value).toByte())
                pending = -1
            }
        }
        index++
    }
    fail("the hexadecimal string is never closed by a GREATER-THAN SIGN")
}

/**
 * The value of one hexadecimal digit, or -1. Both spellings of A–F are
 * accepted: the clause admits "0–9 and A–F or a–f".
 */
private fun hexDigitValue(byte: Byte): Int = when (val code = byte.toInt() and 0xFF) {
    in 0x30..0x39 -> code - 0x30
    in 0x41..0x46 -> code - 0x41 + 10
    in 0x61..0x66 -> code - 0x61 + 10
    else -> -1
}

private const val LEFT_PARENTHESIS_BYTE: Byte = 0x28
private const val RIGHT_PARENTHESIS_BYTE: Byte = 0x29
private const val LESS_THAN_BYTE: Byte = 0x3C
private const val GREATER_THAN_BYTE: Byte = 0x3E
private const val REVERSE_SOLIDUS_BYTE: Byte = 0x5C
private const val LINE_FEED_BYTE: Byte = 0x0A
private const val CARRIAGE_RETURN_BYTE: Byte = 0x0D
private const val OCTAL_DIGIT_FIRST: Byte = 0x30
private const val OCTAL_DIGIT_LAST: Byte = 0x37

/** Table 3 — Escape sequences in literal strings, without the `\ddd` row. */
private val NAMED_ESCAPES: Map<Byte, Byte> =
    mapOf(
        'n'.code.toByte() to 0x0A,
        'r'.code.toByte() to 0x0D,
        't'.code.toByte() to 0x09,
        'b'.code.toByte() to 0x08,
        'f'.code.toByte() to 0x0C,
        '('.code.toByte() to 0x28,
        ')'.code.toByte() to 0x29,
        '\\'.code.toByte() to 0x5C,
    )

/** Table 1 — White-space characters, §7.2.3. */
private val STRING_WHITE_SPACE = byteArrayOf(0x00, 0x09, 0x0A, 0x0C, 0x0D, 0x20)
