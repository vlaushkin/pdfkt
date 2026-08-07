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

// ISO 32000-2:2020 §7.3.5 "Name objects"

/**
 * Asserts that [bytes] is a name as the clause requires it to be written:
 *
 * > When writing a name in a PDF file, a SOLIDUS (2Fh) (/) shall be used to
 * > introduce a name.
 *
 * > No token delimiter (such as white-space) occurs between the SOLIDUS and
 * > the encoded name. White-space used as part of a name shall always be
 * > coded using the 2-digit hexadecimal notation.
 *
 * So: one SOLIDUS, then only characters that a reader will still count as
 * part of this token — no white-space and no delimiter of §7.2.3 — with every
 * NUMBER SIGN starting a complete 2-digit hexadecimal code. The bare solidus
 * passes, being the written form of the empty name.
 */
internal fun assertNameSyntax(bytes: ByteArray, what: String) {
    assertTrue(bytes.isNotEmpty(), "$what produced no bytes")
    assertTrue(
        bytes[0] == SOLIDUS_BYTE,
        "$what does not start with the SOLIDUS that introduces a name",
    )
    var index = 1
    while (index < bytes.size) {
        val byte = bytes[index]
        assertTrue(byte !in NAME_WHITE_SPACE, "$what contains white-space, which ends the token")
        assertTrue(byte !in NAME_DELIMITERS, "$what contains a delimiter, which ends the token")
        if (byte == NUMBER_SIGN_BYTE) {
            assertTrue(
                index + 2 < bytes.size &&
                    hexValue(bytes[index + 1]) >= 0 &&
                    hexValue(bytes[index + 2]) >= 0,
                "$what has a NUMBER SIGN at $index that is not followed by 2 hexadecimal digits",
            )
            index += 3
        } else {
            index++
        }
    }
}

/**
 * Reads a written name back: the inverse the clause spells out as "after all
 * escaping is expanded" — drop the introducing SOLIDUS, replace every NUMBER
 * SIGN and the 2 hexadecimal digits after it by the byte they encode, take
 * every other byte as itself, and interpret the resulting bytes as UTF-8.
 *
 * This exists so that a round-trip test can assert on the name that comes
 * back rather than on one particular spelling of it — NOTE 1 of the clause
 * says there is no unique spelling, so equality of written bytes is a
 * stricter claim than the clause makes. It follows the clause's decoding
 * rules only; nothing about how [writePdfName] chooses between the two
 * spellings a regular character has is assumed here.
 */
internal fun decodePdfName(bytes: ByteArray): String {
    assertNameSyntax(bytes, "the name under test")
    val decoded = Buffer()
    var index = 1
    while (index < bytes.size) {
        val byte = bytes[index]
        if (byte == NUMBER_SIGN_BYTE) {
            val high = hexValue(bytes[index + 1])
            val low = hexValue(bytes[index + 2])
            decoded.writeByte(((high shl 4) or low).toByte())
            index += 3
        } else {
            decoded.writeByte(byte)
            index++
        }
    }
    return decoded.readByteArray().decodeToString()
}

private const val SOLIDUS_BYTE: Byte = 0x2F
private const val NUMBER_SIGN_BYTE: Byte = 0x23

/** Table 1 — White-space characters, §7.2.3. */
private val NAME_WHITE_SPACE = byteArrayOf(0x00, 0x09, 0x0A, 0x0C, 0x0D, 0x20)

/** The delimiter characters of §7.2.3, including `{` and `}`. */
private val NAME_DELIMITERS = byteArrayOf(0x28, 0x29, 0x3C, 0x3E, 0x5B, 0x5D, 0x2F, 0x25, 0x7B, 0x7D)

/**
 * The value of one hexadecimal digit, or -1. Both cases are accepted: the
 * clause fixes the number of digits, not which of the two spellings of A–F a
 * writer picks.
 */
private fun hexValue(byte: Byte): Int = when (val code = byte.toInt() and 0xFF) {
    in 0x30..0x39 -> code - 0x30
    in 0x41..0x46 -> code - 0x41 + 10
    in 0x61..0x66 -> code - 0x61 + 10
    else -> -1
}

/**
 * The 2-digit hexadecimal code of one byte, as the clause writes it in
 * Table 4 — used to state expected bytes in tests without spelling out the
 * arithmetic each time.
 */
internal fun hexCodeOf(code: Int): String {
    if (code !in 0x00..0xFF) fail("$code is not an 8-bit value and cannot appear in a name")
    val digits = "0123456789ABCDEF"
    return "#${digits[code shr 4]}${digits[code and 0x0F]}"
}
