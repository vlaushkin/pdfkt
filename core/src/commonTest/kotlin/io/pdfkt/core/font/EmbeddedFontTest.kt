/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.font

import io.pdfkt.core.filter.flateEncode
import io.pdfkt.core.font.truetype.TrueTypeFont
import io.pdfkt.core.font.truetype.TrueTypeSubset
import io.pdfkt.core.font.truetype.postTableBytes
import io.pdfkt.core.obj.PdfDict
import io.pdfkt.core.obj.PdfName
import io.pdfkt.core.obj.PdfObject
import io.pdfkt.core.obj.PdfRef
import io.pdfkt.core.obj.PdfStream
import io.pdfkt.core.obj.serializeToText
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

// ISO 32000-2:2020 §9.7 "Composite fonts" (Tables 114, 115, 119),
// §9.7.5.2 "Predefined CMaps", §9.8 "Font descriptors" (Tables 120, 121),
// §9.9 "Embedded font programs" (Tables 124, 125), §9.9.2 "Font subsets",
// §9.10.3 "ToUnicode CMaps", §9.2.4 "Glyph positioning and metrics"

/**
 * The embedded path, from a supplied font program to the objects the file
 * carries.
 *
 * The font underneath is written out by hand from the OpenType field tables
 * (`TrueTypeFontBytes`), six glyphs of it, so that every number asserted here is
 * a number this test put in a field of a font program rather than a number read
 * out of whatever font a machine happened to have. The glyphs are shaped for what
 * the writing has to get right:
 *
 * | Glyph | Character | Why it is there |
 * |---|---|---|
 * | 0 | — | `.notdef`, which "shall be defined in the font subset" (§9.9.2) |
 * | 1 | `A` | a component of glyph 3, and a glyph the document does not show |
 * | 2 | `B` | shown, and renumbered by the subset |
 * | 3 | `C` | a composite, whose components the closure has to pull in |
 * | 4 | `D` | never shown, never a component: the glyph that must be dropped |
 * | 5 | `E` | shown, and the one that lands furthest from its original index |
 *
 * The document below shows `B` and `E` — glyphs 2 and 5 — so the subset is
 * `[0, 2, 5]` and the CIDs the pages wrote (2 and 5, the *font's* glyph indices)
 * are not the glyph indices of the subset (1 and 2). That gap is what the
 * `CIDToGIDMap` closes, and a writer that ignored it would produce a document
 * that renders the wrong letters.
 */
class EmbeddedFontTest {
    // ---- Showing text -----------------------------------------------------

    /**
     * "When the current font is a Type 0 font whose Encoding entry is Identity-H
     * or Identity-V, the string to be shown shall contain pairs of bytes
     * representing CIDs, high-order byte first" (§9.7.5.2).
     */
    @Test
    fun `text is shown as two byte glyph indices high order byte first`() {
        val font = EmbeddedFont(FontRef.Supplied(fontBytes(), "body"))

        assertContentEquals(byteArrayOf(0, 2, 0, 5), font.encode("BE"))
        assertContentEquals(byteArrayOf(0, 1, 0, 2, 0, 3, 0, 4, 0, 5), font.encode("ABCDE"))
    }

    /**
     * No silent loss. A character the program has no
     * glyph for maps to glyph 0 in the `cmap` table, and glyph 0 is the
     * missing-character box: drawing it would produce a page that renders and
     * says the wrong thing.
     */
    @Test
    fun `a character the font program has no glyph for is refused`() {
        val font = EmbeddedFont(FontRef.Supplied(fontBytes(), "body"))

        val refused = assertFailsWith<UnencodableCharacterException> { font.encode("BZE") }

        assertEquals("Z", refused.character)
        assertEquals(1, refused.index)
        assertEquals("BZE", refused.text)
        assertEquals("body", refused.fontName)
        assertTrue(".notdef" in refused.message.orEmpty(), refused.message)
        // And the remedy is not the one a standard 14 font gives: the font was
        // supplied already, so "supply a font" would be advice already taken.
        assertTrue("supply a font program whose glyphs cover it" in refused.message.orEmpty(), refused.message)
    }

    // ---- Measuring --------------------------------------------------------

    /**
     * §9.2.4 NOTE 3: "TrueType stores widths in units of 1024 or 2048 to an Em …
     * however the Widths array stores widths in units of 1000 to an Em." The font
     * here counts 2048 units to the em, so no advance of it is its own glyph-space
     * width and a writer that skipped the division would set every line about
     * twice too wide.
     */
    @Test
    fun `advances are converted out of the design units of the program`() {
        val font = EmbeddedFont(FontRef.Supplied(fontBytes(unitsPerEm = 2048), "body"))

        // 'B' is glyph 2, 700 design units; 'E' is glyph 5, 1000.
        assertEquals(1700.0 * 1000 / 2048, font.advanceOf("BE"))
        assertEquals(800.0 * 1000 / 2048, font.ascent)
        assertEquals(-200.0 * 1000 / 2048, font.descent)
        assertEquals(100.0 * 1000 / 2048, font.leading)
    }

    /** A font whose em is a thousand units measures in the units it stores. */
    @Test
    fun `a font of a thousand units to the em needs no conversion`() {
        val font = EmbeddedFont(FontRef.Supplied(fontBytes(), "body"))

        assertEquals(1700.0, font.advanceOf("BE"))
        assertEquals(800.0, font.ascent)
        assertEquals(-200.0, font.descent)
    }

    /** Measuring refuses what showing refuses, and at the same character. */
    @Test
    fun `measuring a character the font program has no glyph for is refused`() {
        val font = EmbeddedFont(FontRef.Supplied(fontBytes(), "body"))

        val refused = assertFailsWith<UnencodableCharacterException> { font.advanceOf("BZ") }

        assertEquals(1, refused.index)
    }

    /** Measuring is not drawing: a width asked for does not put a glyph in the file. */
    @Test
    fun `measuring text does not put its glyphs in the subset`() {
        val font = EmbeddedFont(FontRef.Supplied(fontBytes(), "body"))
        font.advanceOf("ABCDE")
        font.encode("B")

        val definitions = Definitions()
        font.fontDictionary(definitions::define)

        assertEquals(2, TrueTypeFont.parse(subsetBytes(listOf(2))).numGlyphs)
        assertContentEquals(flateEncode(subsetBytes(listOf(2))), (definitions.objects[0] as PdfStream).data)
    }

    // ---- The object chain -------------------------------------------------

    /**
     * Table 119 in full, and the one shape §9.10.3's Example 1 shows for a
     * TrueType program: `Identity-H` over a one-element `DescendantFonts`, with a
     * `ToUnicode` CMap beside it.
     */
    @Test
    fun `a supplied font is written as a Type 0 font over a CIDFontType2`() {
        val written = write("BE")

        assertEquals(
            "<</Type /Font /Subtype /Type0 /BaseFont /${written.name} /Encoding /Identity-H " +
                "/DescendantFonts [4 0 R] /ToUnicode 5 0 R>>",
            serializeToText(written.type0),
        )
    }

    /**
     * Table 115: the descendant. `CIDSystemInfo` is `Adobe-Identity-0`, the
     * collection of a font whose CIDs are its own glyph indices, and
     * `CIDToGIDMap` is a stream because the subset renumbered them.
     *
     * `DW` is absent: its default is 1000, and every CID a page can show is in
     * `W`.
     */
    @Test
    fun `the descendant names the collection the widths and the CID to GID map`() {
        val written = write("BE")

        assertEquals(
            "<</Type /Font /Subtype /CIDFontType2 /BaseFont /${written.name} " +
                "/CIDSystemInfo <</Registry (Adobe) /Ordering (Identity) /Supplement 0>> " +
                "/FontDescriptor 2 0 R /W [2 [700] 5 [1000]] /CIDToGIDMap 3 0 R>>",
            serializeToText(written.cidFont),
        )
    }

    /**
     * "c shall be an integer specifying a starting CID value; it shall be
     * followed by an array of n numbers that shall specify the widths for n
     * consecutive CIDs, starting with c." Glyphs 2, 3 and 4 are consecutive and
     * share one group.
     */
    @Test
    fun `consecutive CIDs share one group of the W array`() {
        val written = write("BCD")

        assertEquals("[2 [700 800 900]]", serializeToText(written.cidFont.entries[PdfName("W")]!!))
    }

    /**
     * The widths a reader advances by are the widths this library measures lines
     * with, "consistent with the actual widths given in the CIDFont program" —
     * and, per §9.2.4 NOTE 3, real numbers rather than rounded ones.
     */
    @Test
    fun `the widths are the advances of the program in glyph space`() {
        val written = write("BE", fontBytes(unitsPerEm = 2048))

        assertEquals("[2 [341.796875] 5 [488.28125]]", serializeToText(written.cidFont.entries[PdfName("W")]!!))
    }

    /**
     * Table 120, for the CIDFont. Every entry the table marks Required is
     * written, from this font's own `head`, `hhea` and `post`, converted to glyph
     * space; `StemV` is 0, which the table defines as "an unknown stem thickness"
     * rather than as a gap, because no table of a TrueType program states it.
     *
     * `CapHeight` is absent here: the table requires it only "for fonts that have
     * Latin characters", and this font's character map covers `A` to `E` — it has
     * no `H` to measure one from.
     */
    @Test
    fun `the descriptor states the metrics the font program carries`() {
        val written = write("BE")

        assertEquals(
            "<</Type /FontDescriptor /FontName /${written.name} /Flags 4 " +
                "/FontBBox [-100 -200 900 800] /ItalicAngle 0 /Ascent 800 /Descent -200 " +
                "/StemV 0 /FontFile2 1 0 R>>",
            serializeToText(written.descriptor),
        )
    }

    /**
     * The two flags of Table 121 a TrueType program states, and the `ItalicAngle`
     * of Table 120, which only the `post` table gives: bit 3 `Symbolic` is set for
     * every supplied font, bit 7 `Italic` comes from `head`'s macStyle and bit 1
     * `FixedPitch` from `post`. 4 + 64 + 1 = 69.
     */
    @Test
    fun `an italic monospaced font declares its slope and its pitch`() {
        val written =
            write("BE", fontBytes(macStyle = 2, post = postTableBytes(italicAngle = -12.5, isFixedPitch = true)))

        assertEquals("69", serializeToText(written.descriptor.entries[PdfName("Flags")]!!))
        assertEquals("-12.5", serializeToText(written.descriptor.entries[PdfName("ItalicAngle")]!!))
    }

    /**
     * "CapHeight … The vertical coordinate of the top of flat capital letters,
     * measured from the baseline" — read off the font's own `H`, for a font that
     * has one. Here `H` selects glyph 4, whose bounding box reaches 700.
     */
    @Test
    fun `a font with a capital H states its cap height`() {
        val written = write("B", fontBytes(mapsCapitalH = true))

        assertEquals("700", serializeToText(written.descriptor.entries[PdfName("CapHeight")]!!))
    }

    /**
     * Table 125: "Length1 … The length in bytes of … the entire TrueType font
     * program, after it has been decoded using the filters specified by the
     * stream's Filter entry, if any" — the subset, not the font it was cut from.
     */
    @Test
    fun `the font program in the file is the subset and declares its length`() {
        val subset = subsetBytes(listOf(2, 5))
        val written = write("BE")
        val stream = assertIs<PdfStream>(written.defined[0])

        assertContentEquals(flateEncode(subset), stream.data)
        assertEquals(
            "<</Length ${stream.data.size} /Filter /FlateDecode /Length1 ${subset.size}>>",
            serializeToText(stream.dict),
        )
        assertEquals(3, TrueTypeFont.parse(subset).numGlyphs, "the subset is not .notdef and the two shown")
        assertTrue(subset.size < fontBytes().size, "the subset is no smaller than the font it was cut from")
    }

    /**
     * The entry the whole numbering rests on: "the glyph index for a particular
     * CID value c shall be a 2-byte value stored in bytes 2 × c and 2 × c + 1,
     * where the first byte shall be the high-order byte".
     *
     * The document showed glyphs 2 and 5 of the font. The subset holds `[0, 2, 5]`
     * and numbers them 0, 1, 2 — so CID 2 is glyph 1 and CID 5 is glyph 2, and
     * every CID between them, which no page wrote, is glyph 0.
     */
    @Test
    fun `the CID to GID map sends every code the pages wrote to its glyph in the subset`() {
        val written = write("BE")

        val map = byteArrayOf(
            0, 0, // CID 0 — .notdef, glyph 0 of every subset
            0, 0, // CID 1 — never shown
            0, 1, // CID 2 — 'B', glyph 1 of the subset
            0, 0, // CID 3 — never shown
            0, 0, // CID 4 — never shown
            0, 2, // CID 5 — 'E', glyph 2 of the subset
        )
        assertContentEquals(flateEncode(map), assertIs<PdfStream>(written.defined[2]).data)
    }

    /**
     * "In the absence of a ToUnicode entry, no information would be available
     * about what the glyphs mean" (§9.10.3). The codes the pages wrote are glyph
     * indices; this is the only thing in the file that says which characters they
     * were.
     *
     * The CMap's own syntax is asserted in `ToUnicodeCMapTest`, against the
     * clause's example; what is asserted here is which mappings went into it —
     * code 2 for `B` and code 5 for `E`, the codes this document actually wrote.
     */
    @Test
    fun `the ToUnicode CMap maps the codes the pages wrote back to their characters`() {
        val written = write("BE")
        val stream = assertIs<PdfStream>(written.defined[4])

        assertContentEquals(flateEncode(toUnicodeCMap(listOf(2 to "B", 5 to "E"))), stream.data)
        assertEquals(
            "<</Length ${stream.data.size} /Filter /FlateDecode /Type /CMap " +
                "/CMapName /Adobe-Identity-UCS2 " +
                "/CIDSystemInfo <</Registry (Adobe) /Ordering (UCS2) /Supplement 0>>>>",
            serializeToText(stream.dict),
        )
    }

    // ---- Subsetting -------------------------------------------------------

    /**
     * Subset closure: a composite glyph (e.g. й) pulls its base
     * and mark components into the subset. Glyph 3 is drawn out of glyphs 1 and
     * 5, so a document that shows only `C` carries four glyphs.
     */
    @Test
    fun `a composite glyph pulls the glyphs it is drawn from into the subset`() {
        val written = write("C")

        assertEquals(4, TrueTypeFont.parse(subsetBytes(listOf(3))).numGlyphs)
        // The name records which glyphs were kept: 0, 1, 3 and 5.
        assertEquals(subsetFontName("body", listOf(0, 1, 3, 5)), written.name)
    }

    /** A glyph nothing showed and nothing is drawn from is not in the file. */
    @Test
    fun `the subset grows with the glyphs the document draws`() {
        assertTrue(subsetBytes(listOf(2)).size < subsetBytes(listOf(2, 3, 4, 5)).size)
        assertEquals(subsetFontName("body", listOf(0, 2)), write("B").name)
        assertEquals(subsetFontName("body", listOf(0, 1, 2, 3, 4, 5)), write("BCDE").name)
    }

    /** "The glyph name .notdef shall be defined in the font subset" (§9.9.2). */
    @Test
    fun `a font a document never drew with still carries notdef`() {
        val written = write("")

        assertEquals(1, TrueTypeFont.parse(subsetBytes(emptyList())).numGlyphs)
        assertEquals("[]", serializeToText(written.cidFont.entries[PdfName("W")]!!))
        // Nothing to map, and the CMap still declares the codespace it maps in.
        assertContentEquals(flateEncode(toUnicodeCMap(emptyList())), assertIs<PdfStream>(written.defined[4]).data)
    }

    /**
     * The name of a subset, per §9.9.2 — "shall begin with a tag followed by a
     * plus sign (+) followed by the PostScript name of the font from which the
     * subset was created" — and the same name in all three places Tables 115, 119
     * and 120 require it to agree.
     */
    @Test
    fun `the subset name tags the font and is the same in every entry that names it`() {
        val written = write("BE")

        assertTrue(Regex("^[A-Z]{6}\\+body$").matches(written.name), written.name)
        assertEquals(PdfName(written.name), written.type0.entries[PdfName("BaseFont")])
        assertEquals(PdfName(written.name), written.cidFont.entries[PdfName("BaseFont")])
        assertEquals(PdfName(written.name), written.descriptor.entries[PdfName("FontName")])
    }

    // ---- Order of use -----------------------------------------------------

    /**
     * Draw first, write afterwards. A glyph offered once the subset has been cut
     * has no entry in the `CIDToGIDMap` already written, so the page would show
     * `.notdef` and every check would still pass — the silent loss the
     * architecture forbids, refused here instead.
     */
    @Test
    fun `a glyph shown after the font was written is refused rather than dropped`() {
        val font = EmbeddedFont(FontRef.Supplied(fontBytes(), "body"))
        font.encode("B")
        font.fontDictionary(Definitions()::define)

        val refused = assertFailsWith<IllegalStateException> { font.encode("E") }

        assertTrue("body" in refused.message.orEmpty(), refused.message)
        assertTrue("E" in refused.message.orEmpty(), refused.message)
        // A glyph already in the subset is still fine: the file can draw it.
        assertContentEquals(byteArrayOf(0, 2), font.encode("B"))
    }

    // ---- Helpers ----------------------------------------------------------

    /** The objects one call to [EmbeddedFont.fontDictionary] produced. */
    private class Written(val type0: PdfDict, val defined: List<PdfObject>) {
        val descriptor: PdfDict get() = defined[1] as PdfDict
        val cidFont: PdfDict get() = defined[3] as PdfDict
        val name: String get() = (type0.entries[PdfName("BaseFont")] as PdfName).name
    }

    /** A `define` that keeps what it was given and numbers from 1, as a file does. */
    private class Definitions {
        val objects: MutableList<PdfObject> = mutableListOf()

        fun define(value: PdfObject): PdfRef {
            objects += value
            return PdfRef(objects.size)
        }
    }

    /** Shows [text] with a font of [bytes] and writes the font out. */
    private fun write(text: String, bytes: ByteArray = fontBytes()): Written {
        val font = EmbeddedFont(FontRef.Supplied(bytes, "body"))
        if (text.isNotEmpty()) font.encode(text)
        val definitions = Definitions()
        return Written(font.fontDictionary(definitions::define), definitions.objects)
    }

    /**
     * The subset of the default font over [glyphIds], cut a second time by the
     * caller of the class under test.
     *
     * The bytes in the file are held against these rather than decoded out of the
     * stream: the subsetter has its own tests, and what this file has to show is
     * that the program the document carries is the subset of the glyphs the pages
     * showed. What is under the filter is checked against a reader that never saw
     * this library in the system-font test on the JVM.
     */
    private fun subsetBytes(glyphIds: List<Int>): ByteArray =
        TrueTypeSubset.of(TrueTypeFont.parse(fontBytes()), glyphIds).bytes

    /** The six-glyph font of this class's documentation. */
    private fun fontBytes(
        unitsPerEm: Int = 1000,
        macStyle: Int = 0,
        post: ByteArray? = null,
        mapsCapitalH: Boolean = false,
    ): ByteArray = suppliedFontBytes(unitsPerEm, macStyle, post, mapsCapitalH)
}
