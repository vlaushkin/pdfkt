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
import kotlin.test.assertTrue

// ISO 32000-2:2020 §7.3.4 "String objects"

/**
 * What the clause requires of a string object:
 *
 * > A string object shall consist of a series of zero or more bytes.
 *
 * > String objects shall be written in one of the following two ways:
 * >
 * > - As a sequence of literal characters enclosed in parentheses () (using
 * >   LEFT PARENTHESIS (28h) and RIGHT PARENTHESIS (29h)); see 7.3.4.2,
 * >   "Literal strings"
 * > - As hexadecimal data enclosed in angle brackets <> (using LESS-THAN SIGN
 * >   (3Ch) and GREATER-THAN SIGN (3Eh)); see 7.3.4.3, "Hexadecimal strings"
 *
 * The examples of both subclauses are written in the reading direction — a
 * syntax and the string it denotes — so they are checked in that direction,
 * through [decodePdfString], which implements the clause's reading rules and
 * nothing of this writer. The writing direction is then checked separately,
 * against the rules themselves and by round trip.
 */
class PdfStringTest {
    /**
     * §7.3.4.2 EXAMPLE 1 — "The following are valid literal strings". Each one
     * is decoded to see what it denotes; between them they cover the whole of
     * the paragraph they illustrate: an ordinary string, a newline standing for
     * itself, balanced parentheses needing no special treatment, the special
     * characters that are not special inside a string, and the empty string.
     */
    @Test
    fun `every literal string of Example 1 is valid and denotes its contents`() {
        assertDecodes("(This is a string)", "This is a string")
        assertDecodes(
            "(Strings can contain newlines\nand such.)",
            "Strings can contain newlines\nand such.",
        )
        assertDecodes(
            "(Strings can contain balanced parentheses ()\nand special characters ( * ! & } ^ %and so on) .)",
            "Strings can contain balanced parentheses ()\nand special characters ( * ! & } ^ %and so on) .",
        )
        assertDecodes("(The following is an empty string .)", "The following is an empty string .")
        assertDecodes("()", "")
        assertDecodes("(It has zero (0) length.)", "It has zero (0) length.")
    }

    /**
     * §7.3.4.2 EXAMPLE 2 — a REVERSE SOLIDUS at the end of a line continues the
     * string on the next one, and "the resulting string value shall be
     * identical to that which would be read if the string were not split".
     */
    @Test
    fun `Example 2 shows a split literal string denoting the unsplit one`() {
        assertDecodes("(These \\\ntwo strings \\\nare the same.)", "These two strings are the same.")
        assertDecodes("(These two strings are the same.)", "These two strings are the same.")
        assertContentEquals(
            decodePdfString("(These two strings are the same.)".encodeToByteArray()),
            decodePdfString("(These \\\ntwo strings \\\nare the same.)".encodeToByteArray()),
        )
    }

    /**
     * §7.3.4.2 EXAMPLE 3 — an end-of-line marker with no REVERSE SOLIDUS before
     * it is a byte value of 0Ah, which is what `\n` denotes as well.
     */
    @Test
    fun `Example 3 shows an end-of-line marker denoting a line feed`() {
        assertDecodes(
            "(This string has an end-of-line at the end of it.\n)",
            "This string has an end-of-line at the end of it.\n",
        )
        assertDecodes("(So does this one.\\n)", "So does this one.\n")
    }

    /**
     * The rule Example 3 rests on, over each spelling of an end-of-line marker:
     * "irrespective of whether the end-of-line marker was a CARRIAGE RETURN
     * (0Dh), a LINE FEED (0Ah), or both".
     */
    @Test
    fun `an unescaped end-of-line marker denotes one line feed however it is spelled`() {
        assertContentEquals(bytes(0x0A), decodePdfString("(\n)".encodeToByteArray()))
        assertContentEquals(bytes(0x0A), decodePdfString("(\r)".encodeToByteArray()))
        assertContentEquals(bytes(0x0A), decodePdfString("(\r\n)".encodeToByteArray()))
        assertContentEquals(bytes(0x0A, 0x0A), decodePdfString("(\n\r)".encodeToByteArray()))
    }

    /**
     * §7.3.4.2 EXAMPLE 4 — "The \ddd escape sequence provides a way to
     * represent characters outside the printable ASCII character set."
     */
    @Test
    fun `Example 4 shows octal escapes denoting characters outside printable ASCII`() {
        val expected =
            "This string contains ".encodeToByteArray() +
                bytes(0xA5) +
                "two octal characters".encodeToByteArray() +
                bytes(0xC7) +
                ".".encodeToByteArray()
        assertContentEquals(
            expected,
            decodePdfString("(This string contains \\245two octal characters\\307.)".encodeToByteArray()),
        )
    }

    /**
     * §7.3.4.2 EXAMPLE 5 — "the literal (\0053) denotes a string containing two
     * characters, \005 (Control-E) followed by the digit 3, whereas both (\053)
     * and (\53) denote strings containing the single character \053, a plus
     * sign (+)."
     */
    @Test
    fun `Example 5 shows one two or three octal digits and where three are needed`() {
        assertContentEquals(bytes(0x05, '3'.code), decodePdfString("(\\0053)".encodeToByteArray()))
        assertContentEquals("+".encodeToByteArray(), decodePdfString("(\\053)".encodeToByteArray()))
        assertContentEquals("+".encodeToByteArray(), decodePdfString("(\\53)".encodeToByteArray()))
    }

    /** "high-order overflow shall be ignored" — 400 octal is 256, so \400 is 00h. */
    @Test
    fun `an octal escape ignores high-order overflow`() {
        assertContentEquals(bytes(0x00), decodePdfString("(\\400)".encodeToByteArray()))
        assertContentEquals(bytes(0xFF), decodePdfString("(\\377)".encodeToByteArray()))
    }

    /** Table 3 — Escape sequences in literal strings, every named row. */
    @Test
    fun `every named escape sequence of Table 3 denotes the character of that row`() {
        assertContentEquals(bytes(0x0A), decodePdfString("(\\n)".encodeToByteArray()))
        assertContentEquals(bytes(0x0D), decodePdfString("(\\r)".encodeToByteArray()))
        assertContentEquals(bytes(0x09), decodePdfString("(\\t)".encodeToByteArray()))
        assertContentEquals(bytes(0x08), decodePdfString("(\\b)".encodeToByteArray()))
        assertContentEquals(bytes(0x0C), decodePdfString("(\\f)".encodeToByteArray()))
        assertContentEquals(bytes(0x28), decodePdfString("(\\()".encodeToByteArray()))
        assertContentEquals(bytes(0x29), decodePdfString("(\\))".encodeToByteArray()))
        assertContentEquals(bytes(0x5C), decodePdfString("(\\\\)".encodeToByteArray()))
    }

    /**
     * > If the character following the REVERSE SOLIDUS is not one of those
     * > shown in "Table 3 — Escape sequences in literal strings", the REVERSE
     * > SOLIDUS shall be ignored.
     */
    @Test
    fun `a REVERSE SOLIDUS before anything Table 3 does not name is ignored`() {
        assertDecodes("(\\A)", "A")
        assertDecodes("(a\\qb)", "aqb")
    }

    /**
     * §7.3.4.3 EXAMPLE 1 — a hexadecimal string, "useful for including
     * arbitrary binary data in a PDF file".
     */
    @Test
    fun `Example 1 of the hexadecimal form denotes the bytes of its digit pairs`() {
        assertDecodes("<4E6F762073686D6F7A206B6120706F702E>", "Nov shmoz ka pop.")
    }

    /**
     * §7.3.4.3 EXAMPLE 2 — "<901FA3> is a 3-byte string consisting of the
     * characters whose hexadecimal codes are 90, 1F, and A3, but <901FA> is a
     * 3-byte string containing the characters whose hexadecimal codes are 90,
     * 1F, and A0."
     */
    @Test
    fun `Example 2 of the hexadecimal form shows a missing final digit assumed to be zero`() {
        assertContentEquals(bytes(0x90, 0x1F, 0xA3), decodePdfString("<901FA3>".encodeToByteArray()))
        assertContentEquals(bytes(0x90, 0x1F, 0xA0), decodePdfString("<901FA>".encodeToByteArray()))
    }

    /** "White-space characters shall be ignored", and either spelling of A–F is a digit. */
    @Test
    fun `white-space inside a hexadecimal string is ignored and lower case digits are digits`() {
        assertContentEquals(bytes(0x90, 0x1F, 0xA3), decodePdfString("<90 1F\nA3>".encodeToByteArray()))
        assertContentEquals(bytes(0x90, 0x1F, 0xA3), decodePdfString("<901fa3>".encodeToByteArray()))
    }

    /**
     * > The empty string can be represented in a hexadecimal string by a
     * > LESS-THAN SIGN (3Ch) immediately followed by a GREATER-THAN SIGN (3Eh).
     */
    @Test
    fun `the empty string is written in both forms and denotes no bytes`() {
        assertEquals("()", serializeToText(PdfString(ByteArray(0))))
        assertEquals("<>", serializeToText(PdfString(ByteArray(0), hex = true)))
        assertContentEquals(ByteArray(0), decodePdfString("()".encodeToByteArray()))
        assertContentEquals(ByteArray(0), decodePdfString("<>".encodeToByteArray()))
    }

    // --- the writing direction ---

    /** The clause's own hexadecimal example, produced rather than read. */
    @Test
    fun `the hexadecimal form writes two digits per byte between angle brackets`() {
        assertEquals(
            "<4E6F762073686D6F7A206B6120706F702E>",
            serializeToText(PdfString("Nov shmoz ka pop.".encodeToByteArray(), hex = true)),
        )
        assertEquals("<901FA3>", serializeToText(PdfString(bytes(0x90, 0x1F, 0xA3), hex = true)))
        assertEquals("<00FF>", serializeToText(PdfString(bytes(0x00, 0xFF), hex = true)))
    }

    /** A written hexadecimal string never has the odd number of digits §7.3.4.3 has to repair. */
    @Test
    fun `the hexadecimal form writes an even number of ASCII hexadecimal digits`() {
        for (value in ROUND_TRIP_CORPUS) {
            val written = serialize(PdfString(value, hex = true))
            assertEquals(2 * value.size + 2, written.size, "the hexadecimal form of ${describe(value)}")
            for (byte in written.copyOfRange(1, written.size - 1)) {
                assertTrue(
                    byte.toInt().toChar() in "0123456789ABCDEF",
                    "the hexadecimal form of ${describe(value)} contains a byte that is not a digit",
                )
            }
        }
    }

    /** Printable characters need no treatment at all, so they stand for themselves. */
    @Test
    fun `the literal form writes printable ASCII as itself`() {
        assertEquals("(This is a string)", serializeToText(PdfString("This is a string".encodeToByteArray())))
        assertEquals(
            "(A;Name_With-Various***Characters?)",
            serializeToText(PdfString("A;Name_With-Various***Characters?".encodeToByteArray())),
        )
    }

    /**
     * > Balanced pairs of parentheses within a string require no special
     * > treatment.
     */
    @Test
    fun `balanced parentheses are written as themselves`() {
        assertEquals(
            "(It has zero (0) length.)",
            serializeToText(PdfString("It has zero (0) length.".encodeToByteArray())),
        )
        assertEquals("(())", serializeToText(PdfString("()".encodeToByteArray())))
        assertEquals("((()))", serializeToText(PdfString("(())".encodeToByteArray())))
        assertEquals("(a(b)c(d)e)", serializeToText(PdfString("a(b)c(d)e".encodeToByteArray())))
    }

    /**
     * > Any characters may appear in a string except unbalanced parentheses and
     * > the backslash (REVERSE SOLIDUS (5Ch)), which shall be treated specially
     */
    @Test
    fun `an unbalanced parenthesis is escaped and so is the reverse solidus`() {
        assertEquals("(\\()", serializeToText(PdfString("(".encodeToByteArray())))
        assertEquals("(\\))", serializeToText(PdfString(")".encodeToByteArray())))
        assertEquals("(\\)\\()", serializeToText(PdfString(")(".encodeToByteArray())))
        assertEquals("(a\\(b)", serializeToText(PdfString("a(b".encodeToByteArray())))
        assertEquals("(a\\)b)", serializeToText(PdfString("a)b".encodeToByteArray())))
        assertEquals("((a)\\()", serializeToText(PdfString("(a)(".encodeToByteArray())))
        assertEquals("(\\\\)", serializeToText(PdfString("\\".encodeToByteArray())))
        assertEquals("(a\\\\(b)c)", serializeToText(PdfString("a\\(b)c".encodeToByteArray())))
    }

    /**
     * The two bytes an end-of-line marker would swallow: written as themselves
     * they would come back as one LINE FEED, so both carry the sequence Table 3
     * gives them. The remaining named rows are used where the table names them.
     */
    @Test
    fun `the control characters Table 3 names are written with their named sequences`() {
        assertEquals("(a\\nb)", serializeToText(PdfString("a\nb".encodeToByteArray())))
        assertEquals("(a\\rb)", serializeToText(PdfString("a\rb".encodeToByteArray())))
        assertEquals("(a\\r\\nb)", serializeToText(PdfString("a\r\nb".encodeToByteArray())))
        assertEquals("(a\\tb)", serializeToText(PdfString("a\tb".encodeToByteArray())))
        assertEquals("(a\\bb)", serializeToText(PdfString(bytes('a'.code, 0x08, 'b'.code))))
        assertEquals("(a\\fb)", serializeToText(PdfString(bytes('a'.code, 0x0C, 'b'.code))))
    }

    /** The clause's octal example, produced rather than read. */
    @Test
    fun `a byte outside printable ASCII is written as three octal digits`() {
        val value =
            "This string contains ".encodeToByteArray() +
                bytes(0xA5) +
                "two octal characters".encodeToByteArray() +
                bytes(0xC7) +
                ".".encodeToByteArray()
        assertEquals("(This string contains \\245two octal characters\\307.)", serializeToText(PdfString(value)))
        assertEquals("(\\000)", serializeToText(PdfString(bytes(0x00))))
        assertEquals("(\\001)", serializeToText(PdfString(bytes(0x01))))
        assertEquals("(\\177)", serializeToText(PdfString(bytes(0x7F))))
        assertEquals("(\\377)", serializeToText(PdfString(bytes(0xFF))))
    }

    /**
     * Three digits always, so that the digit rule — "Three octal digits shall
     * be used, with leading zeros as needed, if the next character of the
     * string is also a digit" — holds whatever follows. §7.3.4.2 EXAMPLE 5
     * states the value at stake: `(\0053)` is 05h then `3`, `(\53)` is `+`.
     */
    @Test
    fun `an octal escape before a digit keeps its leading zeros`() {
        assertEquals("(\\0053)", serializeToText(PdfString(bytes(0x05, '3'.code))))
        assertContentEquals(bytes(0x05, '3'.code), decodePdfString(serialize(PdfString(bytes(0x05, '3'.code)))))
    }

    /**
     * The literal form of this writer is 7-bit text: every byte it emits is in
     * the printable range, which is the point of escaping the values the clause
     * would let stand for themselves.
     */
    @Test
    fun `the literal form is written entirely in printable ASCII`() {
        for (value in ROUND_TRIP_CORPUS) {
            for (byte in serialize(PdfString(value))) {
                val code = byte.toInt() and 0xFF
                assertTrue(
                    code in 0x20..0x7E,
                    "the literal form of ${describe(value)} contains byte $code, outside printable ASCII",
                )
            }
        }
    }

    /**
     * The property both forms exist to have: what is written denotes the series
     * of bytes it was made from, read back by the clause's rules.
     */
    @Test
    fun `every string comes back unchanged from both written forms`() {
        for (value in ROUND_TRIP_CORPUS) {
            assertContentEquals(value, decodePdfString(serialize(PdfString(value))), "literal ${describe(value)}")
            assertContentEquals(
                value,
                decodePdfString(serialize(PdfString(value, hex = true))),
                "hexadecimal ${describe(value)}",
            )
        }
    }

    /** Every one of the 256 byte values, one string each and all of them at once. */
    @Test
    fun `every byte value survives the round trip in both forms`() {
        for (code in 0..0xFF) {
            val value = bytes(code)
            assertContentEquals(value, decodePdfString(serialize(PdfString(value))), "literal byte $code")
            assertContentEquals(
                value,
                decodePdfString(serialize(PdfString(value, hex = true))),
                "hexadecimal byte $code",
            )
        }
        val all = ByteArray(256) { it.toByte() }
        assertContentEquals(all, decodePdfString(serialize(PdfString(all))))
        assertContentEquals(all, decodePdfString(serialize(PdfString(all, hex = true))))
    }

    /** The literal form is the default: [PdfString.hex] selects the other one. */
    @Test
    fun `a string is written in the literal form unless the hexadecimal one is asked for`() {
        assertEquals("(Sample)", serializeToText(PdfString("Sample".encodeToByteArray())))
        assertEquals("<53616D706C65>", serializeToText(PdfString("Sample".encodeToByteArray(), hex = true)))
    }

    private fun assertDecodes(syntax: String, expected: String) {
        assertContentEquals(
            expected.encodeToByteArray(),
            decodePdfString(syntax.encodeToByteArray()),
            "the string written $syntax",
        )
    }

    private companion object {
        /** A byte array stated by the codes of its bytes, as the clause states them. */
        fun bytes(vararg codes: Int): ByteArray = ByteArray(codes.size) { codes[it].toByte() }

        fun describe(value: ByteArray): String = value.joinToString(" ", "[", "]") { (it.toInt() and 0xFF).toString() }

        val ROUND_TRIP_CORPUS: List<ByteArray> =
            listOf(
                ByteArray(0),
                "This is a string".encodeToByteArray(),
                "It has zero (0) length.".encodeToByteArray(),
                "(".encodeToByteArray(),
                ")".encodeToByteArray(),
                ")(".encodeToByteArray(),
                "((".encodeToByteArray(),
                "))".encodeToByteArray(),
                "a(b".encodeToByteArray(),
                "a)b".encodeToByteArray(),
                "(a)(".encodeToByteArray(),
                "(())".encodeToByteArray(),
                "\\".encodeToByteArray(),
                "a\\(b)c".encodeToByteArray(),
                "\\n".encodeToByteArray(),
                "\n".encodeToByteArray(),
                "\r".encodeToByteArray(),
                "\r\n".encodeToByteArray(),
                "a\r\nb\n\rc".encodeToByteArray(),
                "\t".encodeToByteArray(),
                bytes(0x00, 0x08, 0x0C, 0x7F, 0x80, 0xFF),
                bytes(0x05, '3'.code),
                bytes(0x90, 0x1F, 0xA3),
                "café — Фонт — 日本語 — 🙂".encodeToByteArray(),
                ByteArray(256) { it.toByte() },
            )
    }
}
