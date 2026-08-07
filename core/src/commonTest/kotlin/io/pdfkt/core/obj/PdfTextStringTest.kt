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

// ISO 32000-2:2020 §7.9.2.2 "Text string type", §7.11.5 "URL specifications"

/** Text strings, against the two encodings the clause allows and its own example. */
class PdfTextStringTest {
    /**
     * "7-bit U.S. ASCII is a strict subset of PDFDocEncoding" (§7.11.5), so text
     * that is entirely ASCII is its own encoding — no marker, no widening.
     */
    @Test
    fun `ascii text is written as itself`() {
        val string = pdfTextString("Report 2026 - final draft!")
        assertContentEquals("Report 2026 - final draft!".encodeToByteArray(), string.bytes)
        assertTrue(!string.hex, "readable text stays readable in the file")
    }

    /**
     * EXAMPLE 2 of §7.9.2.2, byte for byte:
     *
     * > A PDF dictionary containing key 'Key' with the value that is the text
     * > string "тест" … where the characters in parentheses is the sequence of
     * > bytes with hex codes FE, FF, 04, 42, 04, 35, 04, 41, 04, 42.
     */
    @Test
    fun `the cyrillic example of the clause is encoded byte for byte`() {
        val string = pdfTextString("тест")
        assertContentEquals(
            byteArrayOf(
                0xFE.toByte(), 0xFF.toByte(),
                0x04, 0x42,
                0x04, 0x35,
                0x04, 0x41,
                0x04, 0x42,
            ),
            string.bytes,
        )
    }

    /**
     * "For text strings encoded in UTF-16BE, the first two bytes shall be 254
     * followed by 255."
     */
    @Test
    fun `text beyond ascii carries the byte order marker`() {
        val string = pdfTextString("café")
        assertEquals(254.toByte(), string.bytes[0])
        assertEquals(255.toByte(), string.bytes[1])
        assertEquals(2 + "café".length * 2, string.bytes.size)
        assertTrue(string.hex, "a sequence of code units is written as bytes, not dressed up as text")
    }

    /**
     * "PDF readers that process PDF files containing Unicode text strings shall
     * be prepared to handle supplementary characters; that is, characters
     * requiring more than two bytes to represent." One such character is two
     * UTF-16 code units and comes out as four bytes after the marker.
     */
    @Test
    fun `a supplementary character is written as its two code units`() {
        val string = pdfTextString("📘") // U+1F4D8 BLUE BOOK
        assertContentEquals(
            byteArrayOf(0xFE.toByte(), 0xFF.toByte(), 0xD8.toByte(), 0x3D, 0xDC.toByte(), 0xD8.toByte()),
            string.bytes,
        )
    }

    /**
     * The bytes reach the file whichever form carries them: a string object
     * "shall consist of a series of zero or more bytes" (§7.3.4), and the two
     * forms of §7.3.4.2 and §7.3.4.3 differ only in how they spell them.
     */
    @Test
    fun `the written object spells the same bytes in either form`() {
        assertEquals("(Title)", serializeToText(pdfTextString("Title")))
        assertEquals("<FEFF04420435044104420020D83DDCD8>", serializeToText(pdfTextString("тест 📘")))
    }
}
