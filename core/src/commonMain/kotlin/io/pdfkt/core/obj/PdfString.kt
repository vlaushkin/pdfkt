/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.obj

import kotlinx.io.Sink

// ISO 32000-2:2020 §7.3.4 "String objects"

/**
 * A string object: a sequence of bytes, not of characters. Any mapping from
 * text to bytes belongs to whichever clause defines the string's use — this
 * type carries the result of that mapping.
 *
 * [hex] selects the written form, the hexadecimal one when true and the
 * literal one otherwise. Both forms denote the same bytes, so the choice is
 * an encoding decision made by the caller and not part of the value.
 */
public class PdfString(public val bytes: ByteArray, public val hex: Boolean = false) : PdfObject

/**
 * Writes a string object in the form selected by [PdfString.hex]: the literal
 * form of §7.3.4.2 or the hexadecimal form of §7.3.4.3.
 *
 * Whichever form, the bytes written denote exactly [PdfString.bytes] when read
 * back by the rules of the clause — a string object "shall consist of a series
 * of zero or more bytes", and every one of the 256 values reaches the file.
 */
internal fun Sink.writePdfString(obj: PdfString) {
    if (obj.hex) writeHexadecimalString(obj.bytes) else writeLiteralString(obj.bytes)
}

/**
 * The literal form: the bytes between LEFT PARENTHESIS and RIGHT PARENTHESIS.
 *
 * The clause names exactly two things that may not stand for themselves —
 * "Any characters may appear in a string except unbalanced parentheses and the
 * backslash (REVERSE SOLIDUS (5Ch)), which shall be treated specially" — and
 * adds that "Balanced pairs of parentheses within a string require no special
 * treatment". So parentheses are escaped precisely when they are unbalanced
 * (see [balancedParentheses]) and the REVERSE SOLIDUS always is.
 *
 * Two further bytes have no faithful unescaped spelling, which the clause
 * makes explicit rather than leaving to be discovered: "An end-of-line marker
 * appearing within a literal string without a preceding REVERSE SOLIDUS shall
 * be treated as a byte value of (0Ah), irrespective of whether the end-of-line
 * marker was a CARRIAGE RETURN (0Dh), a LINE FEED (0Ah), or both." A CARRIAGE
 * RETURN written as itself would therefore be read back as a LINE FEED, and a
 * CARRIAGE RETURN followed by a LINE FEED as a single one. Both are written
 * with the sequences Table 3 gives them.
 *
 * That leaves the free choice the clause states twice — "any 8-bit value may
 * appear in a string, represented either as itself or with the \\ddd notation
 * described". It is resolved towards representing every byte outside the
 * printable ASCII range with an escape, so that the written string is 7-bit
 * text that no transport can reinterpret. Table 3's named sequences are used
 * where the table names one; the octal notation covers the rest, always with
 * three digits, which satisfies "Three octal digits shall be used, with
 * leading zeros as needed, if the next character of the string is also a
 * digit" without having to look at the next byte at all.
 */
private fun Sink.writeLiteralString(bytes: ByteArray) {
    val balanced = balancedParentheses(bytes)
    writeByte(LEFT_PARENTHESIS)
    for (index in bytes.indices) {
        val byte = bytes[index]
        when (val code = byte.toInt() and 0xFF) {
            REVERSE_SOLIDUS_CODE -> writeEscaped(REVERSE_SOLIDUS)
            LEFT_PARENTHESIS_CODE, RIGHT_PARENTHESIS_CODE ->
                if (balanced[index]) writeByte(byte) else writeEscaped(byte)

            LINE_FEED_CODE -> writeEscaped(LOWERCASE_N)
            CARRIAGE_RETURN_CODE -> writeEscaped(LOWERCASE_R)
            HORIZONTAL_TAB_CODE -> writeEscaped(LOWERCASE_T)
            BACKSPACE_CODE -> writeEscaped(LOWERCASE_B)
            FORM_FEED_CODE -> writeEscaped(LOWERCASE_F)
            in PRINTABLE_FIRST..PRINTABLE_LAST -> writeByte(byte)
            else -> writeOctalEscape(code)
        }
    }
    writeByte(RIGHT_PARENTHESIS)
}

/**
 * Which parentheses of [bytes] belong to a balanced pair — the ones that need
 * no special treatment. Scanning once with a stack of the positions of the
 * open parentheses: a RIGHT PARENTHESIS that finds one on the stack closes it,
 * and both are balanced; one that finds the stack empty is unbalanced, as is
 * every LEFT PARENTHESIS still on the stack at the end.
 *
 * Because only balanced parentheses are then written as themselves, the nesting
 * a reader sees never goes negative and returns to zero exactly at the RIGHT
 * PARENTHESIS that closes the string.
 */
private fun balancedParentheses(bytes: ByteArray): BooleanArray {
    val balanced = BooleanArray(bytes.size)
    val open = ArrayDeque<Int>()
    for (index in bytes.indices) {
        when (bytes[index]) {
            LEFT_PARENTHESIS -> open.addLast(index)
            RIGHT_PARENTHESIS ->
                if (open.isNotEmpty()) {
                    balanced[open.removeLast()] = true
                    balanced[index] = true
                }
        }
    }
    return balanced
}

/**
 * The hexadecimal form: "a sequence of hexadecimal digits (0–9 and A–F or a–f)
 * encoded as ASCII characters and enclosed within angle brackets", two digits
 * per byte. Writing both digits of every byte leaves the odd-digit rule — "the
 * final digit shall be assumed to be 0" — with nothing to repair, and an empty
 * string with the LESS-THAN SIGN "immediately followed by a GREATER-THAN SIGN"
 * that the clause gives it.
 */
private fun Sink.writeHexadecimalString(bytes: ByteArray) {
    writeByte(LESS_THAN)
    for (byte in bytes) {
        val code = byte.toInt() and 0xFF
        writeByte(HEX_DIGITS[code shr 4])
        writeByte(HEX_DIGITS[code and 0x0F])
    }
    writeByte(GREATER_THAN)
}

/** One escape sequence of Table 3: the REVERSE SOLIDUS and the byte after it. */
private fun Sink.writeEscaped(byte: Byte) {
    writeByte(REVERSE_SOLIDUS)
    writeByte(byte)
}

/** The `\ddd` row of Table 3, in the three digits the clause asks for. */
private fun Sink.writeOctalEscape(code: Int) {
    writeByte(REVERSE_SOLIDUS)
    writeByte(OCTAL_DIGITS[(code shr 6) and 0x07])
    writeByte(OCTAL_DIGITS[(code shr 3) and 0x07])
    writeByte(OCTAL_DIGITS[code and 0x07])
}

private const val LEFT_PARENTHESIS: Byte = 0x28
private const val RIGHT_PARENTHESIS: Byte = 0x29
private const val LESS_THAN: Byte = 0x3C
private const val GREATER_THAN: Byte = 0x3E
private const val REVERSE_SOLIDUS: Byte = 0x5C

private const val LEFT_PARENTHESIS_CODE: Int = 0x28
private const val RIGHT_PARENTHESIS_CODE: Int = 0x29
private const val REVERSE_SOLIDUS_CODE: Int = 0x5C
private const val BACKSPACE_CODE: Int = 0x08
private const val HORIZONTAL_TAB_CODE: Int = 0x09
private const val LINE_FEED_CODE: Int = 0x0A
private const val FORM_FEED_CODE: Int = 0x0C
private const val CARRIAGE_RETURN_CODE: Int = 0x0D

/** SPACE (20h) to TILDE (7Eh), the range written as itself. */
private const val PRINTABLE_FIRST: Int = 0x20
private const val PRINTABLE_LAST: Int = 0x7E

private const val LOWERCASE_N: Byte = 0x6E
private const val LOWERCASE_R: Byte = 0x72
private const val LOWERCASE_T: Byte = 0x74
private const val LOWERCASE_B: Byte = 0x62
private const val LOWERCASE_F: Byte = 0x66

private val HEX_DIGITS: ByteArray = "0123456789ABCDEF".encodeToByteArray()
private val OCTAL_DIGITS: ByteArray = "01234567".encodeToByteArray()
