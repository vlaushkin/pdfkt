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
import kotlin.jvm.JvmInline

// ISO 32000-2:2020 §7.3.5 "Name objects"

/**
 * A name object, held in its decoded form: [name] is the sequence of
 * characters the name denotes, without the leading solidus and with any
 * `#`-escapes already resolved. Escaping is a property of the written
 * representation, not of the object.
 *
 * A value class so that names compare and hash by their text — [PdfDict]
 * keys them. That is also the identity the clause asks for: two names denote
 * the same object exactly when their character sequences match after all
 * escaping is expanded, so `#`-escapes must not survive into this type. A
 * `PdfName("Lime#20Green")` is the name whose eighth character is a NUMBER
 * SIGN, a different object from `PdfName("Lime Green")`, and it is written
 * `/Lime#2320Green`.
 *
 * The clause defines a name over 8-bit values while [name] is text; the
 * bridge is the clause's own: "the sequence of bytes making up the name
 * object should be interpreted according to UTF-8". So the bytes of the name
 * are the UTF-8 encoding of [name], and every character that has a UTF-8
 * encoding can appear in a name.
 *
 * @throws IllegalArgumentException if [name] contains character code 0. A
 *   name is "defined by a sequence of any characters (8-bit values) except
 *   null", so such a value denotes no name object at all; rejecting it here
 *   fails at the construction that is wrong rather than at the write that
 *   would have to invent a repair.
 */
@JvmInline
public value class PdfName(public val name: String) : PdfObject {
    init {
        require(!name.contains('\u0000')) {
            "a name object admits any character except null (character code 0)"
        }
    }
}

/**
 * Writes a name object: the introducing SOLIDUS, then the UTF-8 bytes of the
 * name, each either as itself or as a 2-digit hexadecimal code preceded by a
 * NUMBER SIGN.
 *
 * Which of the two the clause allows per byte:
 *
 * - a NUMBER SIGN "shall be written by using its 2-digit hexadecimal code
 *   (23), preceded by the NUMBER SIGN" (rule a) — no choice;
 * - any other regular character "shall be written as itself or by using its
 *   2-digit hexadecimal code" (rule b) — a choice, which NOTE 1 names as the
 *   reason a name has no unique written form;
 * - any character that is not a regular character — the white-space and
 *   delimiter characters of §7.2.3 — "shall be written using its 2-digit
 *   hexadecimal code, preceded by the NUMBER SIGN only" (rule c);
 * - and of the regular characters, those "outside the range EXCLAMATION
 *   MARK(21h) (!) to TILDE (7Eh) (~) should be written using the hexadecimal
 *   notation".
 *
 * The one choice rule b leaves is resolved towards the shorter spelling:
 * within `!` to `~`, a regular character is written as itself. Every other
 * byte is escaped, which covers rules a and c and the recommendation in one
 * condition. The result is a single token of printable ASCII — the property
 * that keeps a name from ending a token early or from needing a separator
 * after the solidus, which the clause forbids ("No token delimiter (such as
 * white-space) occurs between the SOLIDUS and the encoded name").
 *
 * An empty name writes as the bare solidus, which "introduces a unique valid
 * name defined by the empty sequence of characters" — not a malformed token.
 */
internal fun Sink.writePdfName(obj: PdfName) {
    writeByte(SOLIDUS)
    for (byte in obj.name.encodeToByteArray()) {
        val code = byte.toInt() and 0xFF
        if (code in LITERAL_FIRST..LITERAL_LAST && code != NUMBER_SIGN_CODE && !isDelimiter(code)) {
            writeByte(byte)
        } else {
            writeByte(NUMBER_SIGN)
            writeByte(UPPERCASE_HEX[code shr 4])
            writeByte(UPPERCASE_HEX[code and 0x0F])
        }
    }
}

private const val SOLIDUS: Byte = 0x2F
private const val NUMBER_SIGN: Byte = 0x23
private const val NUMBER_SIGN_CODE: Int = 0x23

/** EXCLAMATION MARK (21h) to TILDE (7Eh), the range written as itself. */
private const val LITERAL_FIRST: Int = 0x21
private const val LITERAL_LAST: Int = 0x7E

/** `0123456789ABCDEF`, as the ASCII bytes §7.2.2 requires of a token. */
private val UPPERCASE_HEX: ByteArray = "0123456789ABCDEF".encodeToByteArray()

/**
 * The delimiter characters of §7.2.3 — `(`, `)`, `<`, `>`, `[`, `]`, `/` and
 * `%`, plus `{` and `}`. They are the characters inside the printable range
 * that are not regular, so rule c applies to them even though they would
 * otherwise be spellable as themselves.
 */
private fun isDelimiter(code: Int): Boolean = when (code) {
    0x28, 0x29, 0x3C, 0x3E, 0x5B, 0x5D, 0x2F, 0x25, 0x7B, 0x7D -> true
    else -> false
}
