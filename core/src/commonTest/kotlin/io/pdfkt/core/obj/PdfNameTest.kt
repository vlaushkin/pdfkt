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
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

// ISO 32000-2:2020 §7.3.5 "Name objects"

/**
 * What the clause requires of a name object:
 *
 * > When writing a name in a PDF file, a SOLIDUS (2Fh) (/) shall be used to
 * > introduce a name. The SOLIDUS is not part of the name but is a prefix
 * > indicating that what follows is a sequence of characters representing the
 * > name in the PDF file and shall follow these rules:
 * >
 * > a) A NUMBER SIGN (23h) (#) in a name shall be written by using its
 * >    2-digit hexadecimal code (23), preceded by the NUMBER SIGN.
 * > b) Any character in a name that is a regular character (other than NUMBER
 * >    SIGN) shall be written as itself or by using its 2-digit hexadecimal
 * >    code, preceded by the NUMBER SIGN.
 * > c) Any character that is not a regular character shall be written using
 * >    its 2-digit hexadecimal code, preceded by the NUMBER SIGN only.
 *
 * > Regular characters that are outside the range EXCLAMATION MARK(21h) (!)
 * > to TILDE (7Eh) (~) should be written using the hexadecimal notation.
 */
class PdfNameTest {
    /**
     * Table 4 — Examples of literal names, read as the clause states it: each
     * row is a syntax and the name it results in. Every row is checked in the
     * decoding direction, which is the direction the table is written in.
     */
    @Test
    fun `the syntax of every row of Table 4 decodes to the name of that row`() {
        for ((syntax, name) in TABLE_4) {
            assertEquals(name, decodePdfName(syntax.encodeToByteArray()), "the name written $syntax")
        }
    }

    /**
     * The same table in the writing direction. Ten of its eleven rows are
     * already the shortest spelling of their name, so they are what this
     * writer produces.
     *
     * The eleventh, `/A#42` for `AB`, is the row NOTE 1 exists for: `B` is a
     * regular character, so rule b admits both `B` and `#42`, and choosing
     * between them is the writer's. This writer writes `/AB` — a different
     * spelling of the same name, not a different name, which the row below
     * asserts rather than pretending the table fixes the bytes.
     */
    @Test
    fun `the names of Table 4 are written in the shortest spelling the rules admit`() {
        for ((syntax, name) in TABLE_4) {
            if (syntax == "/A#42") continue
            assertEquals(syntax, serializeToText(PdfName(name)), "the name $name")
        }
    }

    @Test
    fun `a regular character is written as itself where Table 4 spells it hexadecimally`() {
        assertEquals("/AB", serializeToText(PdfName("AB")))
        assertEquals(
            decodePdfName("/A#42".encodeToByteArray()),
            decodePdfName(serialize(PdfName("AB"))),
            "/AB and /A#42 are spellings of one name",
        )
    }

    /** Rule a: the NUMBER SIGN itself has one spelling and it is `#23`. */
    @Test
    fun `a number sign in a name is written as its own hexadecimal code`() {
        assertEquals("/#23", serializeToText(PdfName("#")))
        assertEquals("/The_Key_of_F#23_Minor", serializeToText(PdfName("The_Key_of_F#_Minor")))
        assertEquals("/a#23#23b", serializeToText(PdfName("a##b")))
    }

    /**
     * Rule c over the white-space characters of Table 1, §7.2.3 — the case
     * the clause repeats outside the rules: "White-space used as part of a
     * name shall always be coded using the 2-digit hexadecimal notation."
     */
    @Test
    fun `white-space in a name is written hexadecimally`() {
        assertEquals("/a#09b", serializeToText(PdfName("a\tb")))
        assertEquals("/a#0Ab", serializeToText(PdfName("a\nb")))
        assertEquals("/a#0Cb", serializeToText(PdfName("a\u000Cb")))
        assertEquals("/a#0Db", serializeToText(PdfName("a\rb")))
        assertEquals("/a#20b", serializeToText(PdfName("a b")))
    }

    /** Rule c over the delimiter characters of §7.2.3. */
    @Test
    fun `a delimiter in a name is written hexadecimally`() {
        for (delimiter in "()<>[]/%{}") {
            assertEquals(
                "/a${hexCodeOf(delimiter.code)}b",
                serializeToText(PdfName("a${delimiter}b")),
                "the delimiter $delimiter",
            )
        }
    }

    /**
     * The recommendation that closes the rules: a regular character outside
     * `!` to `~` is written hexadecimally. Above 7Fh a character is more than
     * one byte, and it is the bytes — the UTF-8 ones the clause names — that
     * are escaped, one code per byte.
     */
    @Test
    fun `a character outside the printable range is written hexadecimally`() {
        assertEquals("/#01", serializeToText(PdfName("\u0001")))
        assertEquals("/#7F", serializeToText(PdfName("\u007F")))
        // U+00E9 LATIN SMALL LETTER E WITH ACUTE is C3 A9 in UTF-8.
        assertEquals("/caf#C3#A9", serializeToText(PdfName("café")))
        // U+0424 CYRILLIC CAPITAL LETTER EF is D0 A4.
        assertEquals("/#D0#A4", serializeToText(PdfName("Ф")))
    }

    /**
     * > The token SOLIDUS (a slash followed by no regular characters)
     * > introduces a unique valid name defined by the empty sequence of
     * > characters.
     */
    @Test
    fun `the empty name is written as the bare solidus`() {
        assertEquals("/", serializeToText(PdfName("")))
        assertEquals("", decodePdfName(serialize(PdfName(""))))
    }

    @Test
    fun `a name is introduced by the solidus and written in ASCII`() {
        for (name in ROUND_TRIP_CORPUS) {
            val bytes = serialize(PdfName(name))
            assertContentEquals(byteArrayOf(0x2F), bytes.copyOfRange(0, 1), "the name $name")
            assertNameSyntax(bytes, "the name $name")
            for (byte in bytes.copyOfRange(1, bytes.size)) {
                val code = byte.toInt() and 0xFF
                assertTrue(
                    code in 0x21..0x7E,
                    "the name $name contains byte $code, outside the printable ASCII range",
                )
            }
        }
    }

    /**
     * The round trip: whatever the name, expanding the escapes of what was
     * written gives the name back. This is the property the clause's own
     * definition of identity rests on — two names denote the same object when
     * their bytes match "after all escaping is expanded".
     */
    @Test
    fun `every name comes back unchanged after its escapes are expanded`() {
        for (name in ROUND_TRIP_CORPUS) {
            assertEquals(name, decodePdfName(serialize(PdfName(name))), "the name $name")
        }
    }

    /** Every 8-bit value except null, one name each, and all of them at once. */
    @Test
    fun `every character a name admits survives the round trip`() {
        val everyCharacter = StringBuilder()
        for (code in 1..0xFF) {
            val character = code.toChar().toString()
            assertEquals(character, decodePdfName(serialize(PdfName(character))), "character $code")
            everyCharacter.append(character)
        }
        val all = everyCharacter.toString()
        assertEquals(all, decodePdfName(serialize(PdfName(all))))
    }

    /**
     * > A name object is an atomic symbol uniquely defined by a sequence of
     * > any characters (8-bit values) except null (character code 0).
     *
     * A value carrying that character denotes no name object, so it is
     * rejected where it is constructed rather than silently escaped into
     * something a reader would take for a different name.
     */
    @Test
    fun `a name cannot contain the null character`() {
        assertFailsWith<IllegalArgumentException> { PdfName("A\u0000B") }
        assertFailsWith<IllegalArgumentException> { PdfName("\u0000") }
    }

    /**
     * > PDF name objects are considered distinct objects if, after all
     * > escaping is expanded, the resulting sequences of bytes are not an
     * > exact binary match.
     *
     * The name whose characters are `Lime#20Green` is therefore not the name
     * `Lime Green`, and writing it may not produce bytes that a reader would
     * expand into the latter.
     */
    @Test
    fun `a name holding an escape sequence is distinct from the name that sequence encodes`() {
        val escaped = PdfName("Lime#20Green")
        val spaced = PdfName("Lime Green")
        assertNotEquals(escaped, spaced)
        assertEquals("/Lime#2320Green", serializeToText(escaped))
        assertEquals("/Lime#20Green", serializeToText(spaced))
        assertNotEquals(decodePdfName(serialize(escaped)), decodePdfName(serialize(spaced)))
    }

    /** Table 4 — Examples of literal names: syntax, and the resulting name. */
    private companion object {
        val TABLE_4 =
            listOf(
                "/Name1" to "Name1",
                "/ASomewhatLongerName" to "ASomewhatLongerName",
                "/A;Name_With-Various***Characters?" to "A;Name_With-Various***Characters?",
                "/1.2" to "1.2",
                "/\$\$" to "\$\$",
                "/@pattern" to "@pattern",
                "/.notdef" to ".notdef",
                "/Lime#20Green" to "Lime Green",
                "/paired#28#29parentheses" to "paired()parentheses",
                "/The_Key_of_F#23_Minor" to "The_Key_of_F#_Minor",
                "/A#42" to "AB",
            )

        val ROUND_TRIP_CORPUS =
            TABLE_4.map { (_, name) -> name } +
                listOf(
                    "",
                    "Type",
                    "#",
                    "##",
                    "#23",
                    " ",
                    "\t\n\u000C\r ",
                    "()<>[]{}/%",
                    "a b\tc",
                    "\u0001\u001F\u007F",
                    "café",
                    "Фонт",
                    "日本語",
                    "🙂",
                    "Lime#20Green",
                )
    }
}
