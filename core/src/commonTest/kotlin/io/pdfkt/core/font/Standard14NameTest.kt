/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.font

import io.pdfkt.core.obj.PdfName
import io.pdfkt.core.obj.serializeToText
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

// ISO 32000-2:2020 §9.6.2.2 "Standard Type 1 fonts (standard 14 fonts) (PDF 1.0-1.7)",
// §9.6.2.1 "Type 1 fonts — General" (Table 109), §9.6.5 "Character encoding",
// Annex D "Character sets and encodings"

class Standard14NameTest {
    /**
     * The list of §9.6.2.2, transcribed from the clause and in its order:
     *
     * > Times-Roman, Helvetica, Courier, Symbol, Times-Bold, Helvetica-Bold,
     * > Courier-Bold, ZapfDingbats, Times-Italic, Helvetica-Oblique,
     * > Courier-Oblique, Times-BoldItalic, Helvetica-BoldOblique,
     * > Courier-BoldOblique.
     *
     * Fourteen names, spelled as printed — every hyphen and every capital, since
     * the name is what a processor matches the font program by.
     */
    @Test
    fun `the standard 14 fonts are the ones the clause lists`() {
        assertEquals(
            listOf(
                "Times-Roman",
                "Helvetica",
                "Courier",
                "Symbol",
                "Times-Bold",
                "Helvetica-Bold",
                "Courier-Bold",
                "ZapfDingbats",
                "Times-Italic",
                "Helvetica-Oblique",
                "Courier-Oblique",
                "Times-BoldItalic",
                "Helvetica-BoldOblique",
                "Courier-BoldOblique",
            ),
            Standard14Name.entries.map { it.baseFont.name },
        )
    }

    /**
     * The dictionary of Table 109 for a standard font: the three Required
     * entries whose values the table fixes or the font names, the `/Encoding`
     * that says which table its character codes were produced by, and nothing
     * that would override the metrics the processor supplies.
     */
    @Test
    fun `a standard font dictionary names the font and its encoding`() {
        assertEquals(
            "<</Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding>>",
            serializeToText(Standard14Name.HELVETICA.fontDictionary()),
        )
        assertEquals(
            "<</Type /Font /Subtype /Type1 /BaseFont /Times-BoldItalic /Encoding /WinAnsiEncoding>>",
            serializeToText(Standard14Name.TIMES_BOLD_ITALIC.fontDictionary()),
        )
    }

    /**
     * Table 109: "The value of Encoding shall be either the name of a
     * predefined encoding (MacRomanEncoding, MacExpertEncoding, or
     * WinAnsiEncoding, as described in Annex D…) or an encoding dictionary".
     * The twelve Latin fonts name the predefined one this library encodes with;
     * none of the fourteen writes a dictionary, because none of them differs
     * from Table D.2 in a way a `Differences` array would express.
     */
    @Test
    fun `the twelve Latin fonts name the predefined encoding of Annex D`() {
        val encoding = PdfName("Encoding")
        for (font in Standard14Name.entries.filterNot { it.symbolic }) {
            assertEquals(
                PdfName("WinAnsiEncoding"),
                font.fontDictionary().entries[encoding],
                "${font.baseFont.name} does not name WinAnsiEncoding",
            )
        }
    }

    /**
     * Symbol and ZapfDingbats are the two symbolic fonts of §9.6.5.1 and get no
     * `/Encoding` at all, which selects the font program's built-in one. Naming
     * a Latin-text encoding for them would map codes to glyph names these
     * programs do not have, and §9.6.5.2 says what happens then: "If an encoding
     * maps to a character name that does not exist in the Type 1 font program,
     * the .notdef glyph shall be substituted." A valid file showing nothing.
     */
    @Test
    fun `the two symbolic fonts carry no encoding and select their built-in one`() {
        assertEquals(
            "<</Type /Font /Subtype /Type1 /BaseFont /Symbol>>",
            serializeToText(Standard14Name.SYMBOL.fontDictionary()),
        )
        assertEquals(
            "<</Type /Font /Subtype /Type1 /BaseFont /ZapfDingbats>>",
            serializeToText(Standard14Name.ZAPF_DINGBATS.fontDictionary()),
        )
    }

    /**
     * The other half of the `/Encoding` entry: the codes a shown string carries.
     * One byte per character (§9.6.1), from Table D.2 — so the ASCII range comes
     * out as itself, and the characters above it come out as the codes the annex
     * assigns rather than as UTF-8. `ä` is 344 octal = 228, `€` is 200 octal =
     * 128; UTF-8 would have written two and three bytes respectively.
     */
    @Test
    fun `a string is encoded as the single-byte codes of Table D-2`() {
        assertContentEquals(
            byteArrayOf(72, 101, 108, 108, 111),
            Standard14Name.HELVETICA.encode("Hello"),
        )
        assertContentEquals(
            byteArrayOf(228.toByte(), 128.toByte()),
            Standard14Name.HELVETICA.encode("ä€"),
        )
        assertContentEquals(byteArrayOf(), Standard14Name.COURIER.encode(""))
    }

    /**
     * No silent loss: a character Table D.2 assigns no
     * code to is refused where it appears, with the remedy attached, rather than
     * dropped from a page the caller believes carries it.
     */
    @Test
    fun `a character outside the encoding is refused rather than dropped`() {
        val failure =
            assertFailsWith<UnencodableCharacterException> {
                Standard14Name.TIMES_ROMAN.encode("Привет")
            }

        assertEquals(0, failure.index)
        assertEquals("П", failure.character)
        assertEquals("Times-Roman", failure.fontName)
    }

    /**
     * And a symbolic font is refused before Table D.2 is applied to it at all:
     * its own encoding is D.5 or D.6, and those tables are not transcribed yet.
     */
    @Test
    fun `the two symbolic fonts refuse the Latin-text encoding`() {
        for (font in listOf(Standard14Name.SYMBOL, Standard14Name.ZAPF_DINGBATS)) {
            val failure = assertFailsWith<UnsupportedOperationException> { font.encode("A") }
            assertTrue(failure.message!!.contains(font.baseFont.name), failure.message)
            assertTrue(failure.message!!.contains("D.5"), failure.message)
            assertTrue(failure.message!!.contains("D.6"), failure.message)
        }
    }

    /**
     * Table 109 on the four entries that carry metrics: "For the standard 14
     * fonts, the entries FirstChar, LastChar, Widths, and FontDescriptor shall
     * either all be present or all be absent." All absent is what is written,
     * and this is the assertion that says so — the entries are what would
     * override the font, and this library embeds no font program to override it
     * with.
     */
    @Test
    fun `no standard font dictionary carries metrics that would override the font`() {
        val overriding = setOf("FirstChar", "LastChar", "Widths", "FontDescriptor")
        for (font in Standard14Name.entries) {
            val keys = font.fontDictionary().entries.keys.map { it.name }
            assertEquals(
                emptyList(),
                keys.filter { it in overriding },
                "${font.baseFont.name} writes an entry that overrides the standard font",
            )
        }
    }

    /**
     * `/Name` was "Required in PDF 1.0; optional in PDF 1.1 through 1.7,
     * deprecated in PDF 2.0" (Table 109), and this library writes PDF 2.0
     * (§7.5.2).
     */
    @Test
    fun `no standard font dictionary carries the deprecated Name entry`() {
        for (font in Standard14Name.entries) {
            assertEquals(
                null,
                font.fontDictionary().entries.keys.firstOrNull { it.name == "Name" },
                "${font.baseFont.name} writes /Name, deprecated in PDF 2.0",
            )
        }
    }
}
