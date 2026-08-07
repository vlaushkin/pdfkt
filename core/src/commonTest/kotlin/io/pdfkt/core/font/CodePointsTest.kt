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

// ISO 32000-2:2020 §7.9.2.2 "Text string type",
// Annex D "Character sets and encodings"

/**
 * Walking a `String` by character rather than by `Char`.
 *
 * Annex D maps characters to codes, and a Kotlin `String` holds UTF-16 code
 * units, so the two only line up while every character fits in one of them.
 * §7.9.2.2's EXAMPLE 2 is the same arithmetic seen from the file's side: the
 * Russian word "тест" becomes "the sequence of bytes with hex codes FE, FF, 04,
 * 42, 04, 35, 04, 41, 04, 42" — two bytes per character, because UTF-16 is what
 * the string is in.
 */
class CodePointsTest {
    /** Where a character begins, and what it is, for text that needs no pairs. */
    @Test
    fun `text within the basic multilingual plane is one char per character`() {
        val text = "тест"
        val seen = mutableListOf<Pair<Int, Int>>()
        forEachCodePoint(text) { index, codePoint -> seen += index to codePoint }

        // The code points of §7.9.2.2 EXAMPLE 2, which spells them as the byte
        // pairs 04 42, 04 35, 04 41, 04 42.
        assertEquals(listOf(0 to 0x0442, 1 to 0x0435, 2 to 0x0441, 3 to 0x0442), seen)
        assertEquals(4, codePointCount(text))
        assertEquals(text.length, codePointCount(text))
    }

    /**
     * A character beyond the basic multilingual plane is one character occupying
     * two `Char`s: the index advances by two and the code point is the pair read
     * together, `0x10000 + ((high - 0xD800) << 10) + (low - 0xDC00)`.
     */
    @Test
    fun `a surrogate pair is one character at the index it begins`() {
        val text = "a🚀b"
        val seen = mutableListOf<Pair<Int, Int>>()
        forEachCodePoint(text) { index, codePoint -> seen += index to codePoint }

        assertEquals(listOf(0 to 'a'.code, 1 to 0x1F680, 3 to 'b'.code), seen)
        assertEquals(3, codePointCount(text))
        assertEquals(4, text.length)
        assertEquals(0x1F680, codePointAt(text, 1))
    }

    /**
     * An unpaired surrogate is passed through as itself. It is not a character
     * any encoding here can write, and repairing it would invent one; leaving it
     * whole means whoever encodes refuses it with its own position.
     */
    @Test
    fun `an unpaired surrogate is passed through as itself`() {
        // The strings are built at runtime: a lone surrogate in a source
        // literal does not survive Kotlin/JS code generation (the literal is
        // serialized through UTF-8), while a runtime string carries it fine.
        val aHighB = buildString {
            append('a')
            append(0xD83D.toChar())
            append('b')
        }
        val aHigh = buildString {
            append('a')
            append(0xD83D.toChar())
        }
        val aLowB = buildString {
            append('a')
            append(0xDE80.toChar())
            append('b')
        }
        assertEquals(0xD83D, codePointAt(aHighB, 1))
        assertEquals(0xD83D, codePointAt(aHigh, 1))
        assertEquals(0xDE80, codePointAt(aLowB, 1))
        assertEquals(3, codePointCount(aHighB))
    }

    /** The round trip, so an error message shows the character it is about. */
    @Test
    fun `a code point renders as the text it stands for`() {
        assertEquals("A", codePointToString('A'.code))
        assertEquals("м", codePointToString(0x043C))
        assertEquals("€", codePointToString(0x20AC))
        assertEquals("🚀", codePointToString(0x1F680))
        assertEquals(2, codePointToString(0x1F680).length)
    }

    @Test
    fun `an empty string holds no characters`() {
        assertEquals(0, codePointCount(""))
        forEachCodePoint("") { _, _ -> throw AssertionError("empty text yielded a character") }
    }
}
