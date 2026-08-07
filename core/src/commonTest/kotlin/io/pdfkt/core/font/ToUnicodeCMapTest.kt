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
import kotlin.test.assertTrue

// ISO 32000-2:2020 §9.10.3 "ToUnicode CMaps", §9.7.5.4 "CMap example and
// operator summary"

/**
 * The `ToUnicode` CMap, asserted as the text it is.
 *
 * A CMap is a PostScript program a reader executes, so the assertion that means
 * anything is on the bytes: the operators, in the order the clause's own example
 * puts them, with the operand counts the syntax requires. Example 2 of §9.10.3
 * is written out below and the program is held against it.
 */
class ToUnicodeCMapTest {
    /**
     * The whole program for one mapping, against the shape of the clause's
     * example — the header, the codespace range `Identity-H` implies, one
     * `beginbfchar` block, and the two `end`s the example finishes with.
     */
    @Test
    fun `the CMap is the program the clause writes out`() {
        val cmap = toUnicodeCMap(listOf(0x2A to "A")).decodeToString()

        assertEquals(
            """
            /CIDInit /ProcSet findresource begin
            12 dict begin
            begincmap
            /CIDSystemInfo
            <</Registry (Adobe)
            /Ordering (UCS2)
            /Supplement 0
            >> def
            /CMapName /Adobe-Identity-UCS2 def
            /CMapType 2 def
            1 begincodespacerange
            <0000> <FFFF>
            endcodespacerange
            1 beginbfchar
            <002A> <0041>
            endbfchar
            endcmap
            CMapName currentdict /CMap defineresource pop
            end
            end

            """.trimIndent(),
            cmap,
        )
    }

    /**
     * "the mapping from character codes to Unicode character sequences expressed
     * in UTF-16BE encoding" — Cyrillic sits above the byte range, so a mapping
     * written in anything narrower would map these codes to halves of characters.
     */
    @Test
    fun `a destination is written as UTF-16BE`() {
        val cmap = toUnicodeCMap(listOf(0x0413 to "П", 0x0414 to "р")).decodeToString()

        assertTrue("2 beginbfchar\n<0413> <041F>\n<0414> <0440>\nendbfchar" in cmap, cmap)
    }

    /**
     * The clause's own example of a character beyond the basic multilingual
     * plane: "the character code <3A 51> is mapped to the Unicode value UNICODE
     * HAN CHARACTER 'U+2003E' (U+2003E), which is expressed by the byte sequence
     * <D840DC3E> in UTF-16BE encoding."
     */
    @Test
    fun `a character beyond the basic plane is written as its surrogate pair`() {
        val cmap = toUnicodeCMap(listOf(0x3A51 to codePointToString(0x2003E))).decodeToString()

        assertTrue("1 beginbfchar\n<3A51> <D840DC3E>\nendbfchar" in cmap, cmap)
    }

    /**
     * A destination may be more than one character — the clause's ligature case,
     * "the original character codes are the glyph indices for the ligatures ff,
     * fi, and ffl".
     */
    @Test
    fun `a code standing for several characters keeps all of them`() {
        val cmap = toUnicodeCMap(listOf(0x5F to "ffl")).decodeToString()

        assertTrue("<005F> <00660066006C>" in cmap, cmap)
    }

    /**
     * The count before `beginbfchar` is part of the syntax, so the blocks have to
     * be cut somewhere; a hundred is where the specification's own CMap cuts them
     * (§9.7.5.4, `100 begincidrange`). What matters is that the counts and the
     * blocks agree.
     */
    @Test
    fun `the mappings are cut into blocks whose counts match them`() {
        val mappings = (1..250).map { it to codePointToString(0x400 + it) }

        val cmap = toUnicodeCMap(mappings).decodeToString()

        assertEquals(3, cmap.split("beginbfchar").size - 1, cmap.take(200))
        assertEquals(3, cmap.split("endbfchar").size - 1)
        assertEquals(2, cmap.split("100 beginbfchar").size - 1)
        assertEquals(1, cmap.split("50 beginbfchar").size - 1)
        // Every mapping, and nothing else: the dictionary of the header opens
        // with "<<" and the codespace range names FFFF.
        for (line in cmap.lines().filter { it.startsWith("<") && !it.startsWith("<<") && "FFFF" !in it }) {
            assertTrue(Regex("^<[0-9A-F]{4}> <([0-9A-F]{4})+>$").matches(line), line)
        }
    }

    /**
     * A font a document resolved but never drew with. The codespace still has to
     * be declared — the clause requires `begincodespacerange` of every ToUnicode
     * CMap — and no `beginbfchar` block is written, because a block of zero
     * entries is not a thing the syntax has.
     */
    @Test
    fun `a CMap with no mappings is still a CMap`() {
        val cmap = toUnicodeCMap(emptyList()).decodeToString()

        assertTrue("1 begincodespacerange\n<0000> <FFFF>\nendcodespacerange" in cmap, cmap)
        assertTrue("beginbfchar" !in cmap, cmap)
        assertTrue(cmap.endsWith("endcmap\nCMapName currentdict /CMap defineresource pop\nend\nend\n"), cmap)
    }

    /**
     * The codespace the CMap declares is `<0000>` to `<FFFF>`, and a code outside
     * it is a code no reader would look up — refused rather than written into a
     * program that then means something else.
     */
    @Test
    fun `a code outside the declared codespace is refused`() {
        val refused = assertFailsWith<IllegalArgumentException> { toUnicodeCMap(listOf(0x10000 to "A")) }

        assertTrue("65536" in refused.message.orEmpty(), refused.message)
    }

    /**
     * The other width a font of this library shows codes in: one byte, for a
     * simple font, whose glyphs "shall be selected by single-byte character
     * codes" (§9.6.1). §9.10.3 requires the CMap to match — "The CMap file shall
     * contain begincodespacerange and endcodespacerange operators that are
     * consistent with the encoding that the font uses" — so both the codespace
     * and every `srcCode` are two hexadecimal digits wide.
     */
    @Test
    fun `a simple font declares a one byte codespace and writes one byte codes`() {
        val cmap = toUnicodeCMap(listOf(1 to "😀", 0xFF to "A"), bytesPerCode = 1).decodeToString()

        assertTrue("1 begincodespacerange\n<00> <FF>\nendcodespacerange" in cmap, cmap)
        assertTrue("<01> <D83DDE00>" in cmap, cmap)
        assertTrue("<FF> <0041>" in cmap, cmap)
    }

    /** A code past the byte a simple font shows it in has no place in that CMap. */
    @Test
    fun `a code outside the one byte codespace is refused`() {
        val refused = assertFailsWith<IllegalArgumentException> {
            toUnicodeCMap(listOf(0x100 to "A"), bytesPerCode = 1)
        }

        assertTrue("<00>..<FF>" in refused.message.orEmpty(), refused.message)
    }

    /** A code that stands for nothing is the entry not being written at all. */
    @Test
    fun `a code mapped to no text is refused`() {
        val refused = assertFailsWith<IllegalArgumentException> { toUnicodeCMap(listOf(0x20 to "")) }

        assertTrue("no text" in refused.message.orEmpty(), refused.message)
    }

    /** "dstString may be a string of up to 512 bytes." */
    @Test
    fun `a destination past the length the clause allows is refused`() {
        val refused =
            assertFailsWith<IllegalArgumentException> { toUnicodeCMap(listOf(0x20 to "A".repeat(257))) }

        assertTrue("512" in refused.message.orEmpty(), refused.message)
        // One character short of the limit is inside it.
        assertTrue("<0020> <" in toUnicodeCMap(listOf(0x20 to "A".repeat(256))).decodeToString())
    }
}
