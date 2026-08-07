/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.font

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// ISO 32000-2:2020 Annex D "Character sets and encodings" (Table D.1, Table D.2
// and its notes 1-6, Table D.3), §9.6.5.1 "Character encoding — General",
// §7.9.2.2 "Text string type"

/**
 * WinAnsiEncoding against Table D.2.
 *
 * The literals below are read off the annex, row by row: the octal code of the
 * `WIN` column, and the code point the row's `PDF` code has in Table D.3. They
 * are literals rather than a second computation over the same table, because a
 * table compared against itself agrees with itself no matter what it says.
 */
class WinAnsiEncodingTest {
    private fun codeOf(character: Char): Int? = WinAnsiEncoding.codeOf(character.code)

    /**
     * The rows of Table D.2 the annex spells out in prose elsewhere, so the
     * transcription can be checked against the specification's own words rather
     * than only against its table.
     *
     * §7.9.2.2 EXAMPLE 1 states one of them outright: a text string "text‰"
     * where "the character '?' after the 'text' is represented by the hex code
     * 8B (octal code 213 - that is according to "D.2 Latin character set and
     * encodings")". Note 1 states the euro: "It is encoded as 200 in
     * WinAnsiEncoding and 240 in PDFDocEncoding". Note 2 states two more: "the
     * existing Zcaron and zcaron characters were added to WinAnsiEncoding as
     * the previously unused codes 216 and 236". Note 3 states the bullet: "only
     * code 225 is specifically assigned to the bullet character".
     */
    @Test
    fun `the codes the annex states in prose are the codes in the table`() {
        // Note 1: the euro, at 200 octal.
        assertEquals(0x80, WinAnsiEncoding.codeOf(0x20AC))
        assertEquals("Euro", WinAnsiEncoding.glyphNameOf(0x80))

        // Note 2: Zcaron and zcaron at the previously unused 216 and 236 octal.
        assertEquals(0x8E, WinAnsiEncoding.codeOf(0x017D))
        assertEquals("Zcaron", WinAnsiEncoding.glyphNameOf(0x8E))
        assertEquals(0x9E, WinAnsiEncoding.codeOf(0x017E))
        assertEquals("zcaron", WinAnsiEncoding.glyphNameOf(0x9E))

        // Note 3: the bullet, at 225 octal.
        assertEquals(0x95, WinAnsiEncoding.codeOf(0x2022))
        assertEquals("bullet", WinAnsiEncoding.glyphNameOf(0x95))

        // §7.9.2.2 EXAMPLE 1: 213 octal is 8B hex, and Table D.2 puts
        // guilsinglleft there.
        assertEquals(0x8B, WinAnsiEncoding.codeOf(0x2039))
        assertEquals("guilsinglleft", WinAnsiEncoding.glyphNameOf(0x8B))
    }

    /**
     * Table D.2 puts the printable ASCII range at the codes ASCII gives it —
     * `A` at 101 octal, `a` at 141, `0` at 060, space at 040 — and that identity
     * is what makes a WinAnsi string of plain English readable in a hex dump.
     */
    @Test
    fun `the ASCII range is encoded at its own codes`() {
        for (code in 0x20..0x7E) {
            assertEquals(code, codeOf(code.toChar()), "code point $code")
        }
        assertEquals("space", WinAnsiEncoding.glyphNameOf(0x20))
        assertEquals("A", WinAnsiEncoding.glyphNameOf(0x41))
        assertEquals("z", WinAnsiEncoding.glyphNameOf(0x7A))
        assertEquals("zero", WinAnsiEncoding.glyphNameOf(0x30))
        assertEquals("quotesingle", WinAnsiEncoding.glyphNameOf(0x27))
        assertEquals("grave", WinAnsiEncoding.glyphNameOf(0x60))
    }

    /**
     * The upper half beyond 240 octal is Latin-1: Table D.2 gives `Aacute` the
     * code 301 octal, and Table D.3 gives the same row's PDFDocEncoding code
     * 301 the code point U+00C1. From 241 octal to 377 the two run together,
     * except at the two codes the annex leaves unassigned.
     */
    @Test
    fun `the upper half repeats Latin-1 except where the annex leaves a gap`() {
        for (code in 0xA0..0xFF) {
            if (code == NO_BREAK_SPACE_CODE || code == SOFT_HYPHEN_CODE) continue
            assertEquals(code, codeOf(code.toChar()), "code point $code")
        }
        assertEquals("Aacute", WinAnsiEncoding.glyphNameOf(0xC1))
        assertEquals("germandbls", WinAnsiEncoding.glyphNameOf(0xDF))
        assertEquals("ydieresis", WinAnsiEncoding.glyphNameOf(0xFF))
    }

    /**
     * Between 200 and 237 octal the encoding is not Latin-1: those codes carry
     * typographic characters from all over Unicode, which is the part of the
     * table that cannot be computed and has to be read.
     */
    @Test
    fun `the typographic block between 200 and 237 octal is read from the table`() {
        val expected =
            listOf(
                0x82 to Triple("quotesinglbase", 0x201A, '‚'),
                0x83 to Triple("florin", 0x0192, 'ƒ'),
                0x84 to Triple("quotedblbase", 0x201E, '„'),
                0x85 to Triple("ellipsis", 0x2026, '…'),
                0x86 to Triple("dagger", 0x2020, '†'),
                0x87 to Triple("daggerdbl", 0x2021, '‡'),
                0x88 to Triple("circumflex", 0x02C6, 'ˆ'),
                0x89 to Triple("perthousand", 0x2030, '‰'),
                0x8A to Triple("Scaron", 0x0160, 'Š'),
                0x8C to Triple("OE", 0x0152, 'Œ'),
                0x91 to Triple("quoteleft", 0x2018, '‘'),
                0x92 to Triple("quoteright", 0x2019, '’'),
                0x93 to Triple("quotedblleft", 0x201C, '“'),
                0x94 to Triple("quotedblright", 0x201D, '”'),
                0x96 to Triple("endash", 0x2013, '–'),
                0x97 to Triple("emdash", 0x2014, '—'),
                0x98 to Triple("tilde", 0x02DC, '˜'),
                0x99 to Triple("trademark", 0x2122, '™'),
                0x9A to Triple("scaron", 0x0161, 'š'),
                0x9B to Triple("guilsinglright", 0x203A, '›'),
                0x9C to Triple("oe", 0x0153, 'œ'),
                0x9F to Triple("Ydieresis", 0x0178, 'Ÿ'),
            )

        for ((code, entry) in expected) {
            val (glyphName, codePoint, character) = entry
            assertEquals(glyphName, WinAnsiEncoding.glyphNameOf(code), "glyph at code $code")
            assertEquals(code, WinAnsiEncoding.codeOf(codePoint), "code of $glyphName")
            assertEquals(codePoint, character.code, "the literal written for $glyphName")
        }
    }

    /**
     * The straight and the curly quotes are four different characters at four
     * different codes, and the pair that is easiest to confuse is the pair a
     * text extraction gets wrong. Table D.2 lists `quotedbl` at 042 octal,
     * `quotedblleft` at 223 and `quotedblright` at 224; Table D.3 gives their
     * PDFDocEncoding rows the code points U+0022, U+201C and U+201D.
     */
    @Test
    fun `the straight and curly quotes are distinct characters`() {
        assertEquals(0x22, WinAnsiEncoding.codeOf(0x0022))
        assertEquals(0x93, WinAnsiEncoding.codeOf(0x201C))
        assertEquals(0x94, WinAnsiEncoding.codeOf(0x201D))

        assertEquals(0x27, WinAnsiEncoding.codeOf(0x0027))
        assertEquals(0x91, WinAnsiEncoding.codeOf(0x2018))
        assertEquals(0x92, WinAnsiEncoding.codeOf(0x2019))
    }

    /**
     * Table D.2 assigns 216 of the 256 codes. The rest — 000 to 037, 177, 201,
     * 215, 217, 220, 235, 240 and 255 octal — name no character, and note 3
     * ("all unused codes greater than 40 map to the bullet character … other
     * codes are subject to future reassignment") is a rule for a reader that
     * meets one, not a licence to write one.
     */
    @Test
    fun `the forty unassigned codes name no glyph`() {
        val unassigned =
            (0x00..0x1F).toList() +
                listOf(0x7F, 0x81, 0x8D, 0x8F, 0x90, 0x9D, NO_BREAK_SPACE_CODE, SOFT_HYPHEN_CODE)

        assertEquals(UNASSIGNED_CODES, unassigned.size)
        for (code in unassigned) {
            assertNull(WinAnsiEncoding.glyphNameOf(code), "code $code")
        }

        val assigned = (0..0xFF).count { WinAnsiEncoding.glyphNameOf(it) != null }
        assertEquals(ENCODED_CHARACTERS, assigned)
    }

    /**
     * Notes 5 and 6 give hyphen and space a second code each — 255 and 240
     * octal — and say in the same breath that Windows Code Page 1252 puts the
     * soft hyphen and the no-break space there instead, so a producer that means
     * those "may be specified using a Differences array in the encoding
     * dictionary". WinAnsiEncoding on its own therefore encodes neither, and
     * this library writes the code Table D.2 assigns rather than the alternative.
     */
    @Test
    fun `no-break space and soft hyphen are outside the encoding`() {
        assertNull(WinAnsiEncoding.codeOf(NO_BREAK_SPACE))
        assertNull(WinAnsiEncoding.codeOf(SOFT_HYPHEN))
        // Spelled by code point: both characters are invisible in a source file,
        // and an assertion nobody can read is an assertion nobody can check.
        for (codePoint in listOf(NO_BREAK_SPACE, SOFT_HYPHEN)) {
            val failure =
                assertFailsWith<UnencodableCharacterException> {
                    WinAnsiEncoding.encode("a${codePointToString(codePoint)}b", "Helvetica")
                }
            assertEquals(1, failure.index)
            assertEquals(codePoint, failure.codePoint)
        }

        assertEquals(0x20, WinAnsiEncoding.codeOf(' '.code))
        assertEquals(0x2D, WinAnsiEncoding.codeOf('-'.code))
        assertEquals(byteArrayOf(0x20, 0x2D).toList(), WinAnsiEncoding.encode(" -", "Helvetica").toList())
    }

    /**
     * A character selects a glyph through one code and a code names one glyph,
     * both ways round, over the whole table: a character encoded twice would
     * make the width measured and the width drawn depend on which lookup ran.
     */
    @Test
    fun `the table is one-to-one in both directions`() {
        var round = 0
        for (code in 0..0xFF) {
            val glyphName = WinAnsiEncoding.glyphNameOf(code) ?: continue
            assertTrue(glyphName.isNotEmpty(), "glyph name at code $code")
            round++
        }
        assertEquals(ENCODED_CHARACTERS, round)

        // Every assigned code is reachable from exactly one character, and that
        // character maps back to it.
        val reached = mutableSetOf<Int>()
        for (codePoint in 0..MAX_CODE_POINT_SEARCHED) {
            val code = WinAnsiEncoding.codeOf(codePoint) ?: continue
            assertTrue(reached.add(code), "code $code is reached by two characters")
            assertTrue(WinAnsiEncoding.glyphNameOf(code) != null, "code $code has no glyph")
        }
        assertEquals(ENCODED_CHARACTERS, reached.size)
    }

    /** One byte per character: a simple font's codes are bytes (§9.6.5.1). */
    @Test
    fun `encoding a string yields one byte per character`() {
        val encoded = WinAnsiEncoding.encode("Hello, World!", "Helvetica")

        assertEquals(13, encoded.size)
        assertEquals(
            listOf(0x48, 0x65, 0x6C, 0x6C, 0x6F, 0x2C, 0x20, 0x57, 0x6F, 0x72, 0x6C, 0x64, 0x21),
            encoded.map { it.toInt() and 0xFF },
        )

        // The codes above 177 octal are bytes with the high bit set, which is
        // what makes them a "single byte" and not a small integer.
        assertEquals(
            listOf(0xC4, 0xF6, 0xDF, 0x80),
            WinAnsiEncoding.encode("Äöß€", "Helvetica").map { it.toInt() and 0xFF },
        )
    }

    @Test
    fun `an empty string encodes to no bytes`() {
        assertEquals(0, WinAnsiEncoding.encode("", "Helvetica").size)
    }

    /**
     * The no-silent-loss policy, at the level of the encoding: a character with
     * no code raises, and the exception carries the character, where it is and
     * what to do instead.
     */
    @Test
    fun `a character outside the encoding raises naming it its position and the remedy`() {
        val failure =
            assertFailsWith<UnencodableCharacterException> {
                WinAnsiEncoding.encode("Hello, мир", "Helvetica")
            }

        assertEquals("м", failure.character)
        assertEquals(0x043C, failure.codePoint)
        assertEquals(7, failure.index)
        assertEquals("Helvetica", failure.fontName)
        assertEquals("WinAnsiEncoding", failure.encodingName)

        val message = failure.message!!
        assertTrue(message.contains("'м'"), message)
        assertTrue(message.contains("U+043C"), message)
        assertTrue(message.contains("index 7"), message)
        assertTrue(message.contains("Hello, мир"), message)
        assertTrue(message.contains("Helvetica"), message)
        assertTrue(message.contains("WinAnsiEncoding"), message)
        assertTrue(message.contains("Annex D"), message)
        assertTrue(message.contains("supply a font"), message)
        assertTrue(message.contains("FontRef.Supplied"), message)
    }

    /** The first offending character is the one reported, not the last. */
    @Test
    fun `the reported position is the first character that cannot be encoded`() {
        val failure =
            assertFailsWith<UnencodableCharacterException> {
                WinAnsiEncoding.encode("aЖbЯc", "Times-Roman")
            }

        assertEquals(1, failure.index)
        assertEquals("Ж", failure.character)
        assertEquals("Times-Roman", failure.fontName)
    }

    /**
     * A character beyond the basic multilingual plane is one character, not two
     * halves of one: the index is where it begins and the code point is the
     * whole of it. A message naming a lone surrogate would be a message about
     * something the caller never wrote.
     */
    @Test
    fun `a character beyond the basic multilingual plane is reported whole`() {
        val rocket = "🚀"
        val failure =
            assertFailsWith<UnencodableCharacterException> {
                WinAnsiEncoding.encode("go $rocket now", "Helvetica")
            }

        assertEquals(3, failure.index)
        assertEquals(0x1F680, failure.codePoint)
        assertEquals(rocket, failure.character)
        assertTrue(failure.message!!.contains("U+1F680"), failure.message)
    }

    /**
     * A long text is not printed whole into the message, but the character is
     * still shown in context and the index still points into the original.
     */
    @Test
    fun `a long text is excerpted around the offending character`() {
        val text = "x".repeat(200) + "Ж" + "y".repeat(200)
        val failure = assertFailsWith<UnencodableCharacterException> { WinAnsiEncoding.encode(text, "Helvetica") }

        val message = failure.message!!
        assertEquals(200, failure.index)
        assertEquals(text, failure.text)
        assertFalse(message.contains(text), "the message repeats the whole text")
        assertFalse(message.contains("x".repeat(EXCERPT_LENGTH)), "the excerpt is not bounded")
        assertTrue(message.contains("Ж"), message)
        assertTrue(message.contains("…"), message)
        assertTrue(message.contains("of 401 characters"), message)
    }

    /**
     * An unpaired surrogate encodes no character in Annex D, so it is refused
     * like anything else outside the table — not repaired, not skipped.
     */
    @Test
    fun `an unpaired surrogate is refused rather than repaired`() {
        // Built at runtime: a lone surrogate in a source literal does not
        // survive Kotlin/JS code generation.
        val aLoneB = buildString {
            append('a')
            append(0xD83D.toChar())
            append('b')
        }
        val failure =
            assertFailsWith<UnencodableCharacterException> {
                WinAnsiEncoding.encode(aLoneB, "Helvetica")
            }

        assertEquals(1, failure.index)
        assertEquals(0xD83D, failure.codePoint)
    }

    private companion object {
        /** Table D.2 assigns a WinAnsi code to this many characters. */
        const val ENCODED_CHARACTERS: Int = 216
        const val UNASSIGNED_CODES: Int = 40

        /** 240 and 255 octal — note 6's second code for space, note 5's for hyphen. */
        const val NO_BREAK_SPACE_CODE: Int = 0xA0
        const val SOFT_HYPHEN_CODE: Int = 0xAD

        const val NO_BREAK_SPACE: Int = 0x00A0
        const val SOFT_HYPHEN: Int = 0x00AD

        /** Everything Table D.2 encodes is below this; the sweep needs no more. */
        const val MAX_CODE_POINT_SEARCHED: Int = 0xFFFF

        /** How much of a long text the message quotes around the offending character. */
        const val EXCERPT_LENGTH: Int = 60
    }
}
