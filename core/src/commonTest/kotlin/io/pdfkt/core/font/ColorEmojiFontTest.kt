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
import io.pdfkt.core.image.PngImage
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

// ISO 32000-2:2020 §9.6.4 "Type 3 fonts" (Tables 110, 111),
// §9.6.5.3 "Encodings for Type 3 fonts", §8.9.4 "Image coordinate system",
// §8.9.5.1 "Image dictionaries — General" (Table 87),
// §9.10.3 "ToUnicode CMaps", §7.8.3 "Resource dictionaries"
// OpenType 1.9.1 §"CBLC — Color Bitmap Location Table",
// §"CBDT — Color Bitmap Data Table"

/**
 * The colour path, from a font program with colour bitmaps to the Type 3 font
 * the file carries.
 *
 * The font underneath is [colorEmojiFontBytes], written out by hand from the
 * OpenType field tables, so every number asserted here is a number a test put in
 * a field of a font program. Three glyphs, at glyph indices 1, 2 and 3, selected
 * by U+1F600, U+1F601 and U+1F602 through a `cmap` format 12 subtable:
 *
 * | Glyph | Bitmap | Metrics, in pixels of the strike |
 * |---|---|---|
 * | 1 | 4 × 4 | bearingX 1, bearingY 3, advance 5 |
 * | 2 | 6 × 2 | bearingX 0, bearingY 2, advance 7 |
 * | 3 | 4 × 4 | bearingX -1, bearingY 5, advance 4 |
 *
 * The strike is 32 pixels to the em and the program counts 2048 units to it, so
 * a pixel is **64 units of glyph space** and every number below is one of these
 * seven multiplied by 64 — the conversion the whole class exists to make. Glyph 3
 * reaches left of the origin and above the others, which is what a bounding box
 * over the union of the glyphs has to notice.
 */
class ColorEmojiFontTest {
    // ---- Showing text -----------------------------------------------------

    /**
     * A Type 3 font is a simple font: "the glyphs … shall be selected by
     * single-byte character codes" (§9.6.1), and which glyph a code selects is
     * this font's `Encoding` (§9.6.5.3). Codes are handed out as glyphs are first
     * drawn, so the same emoji drawn twice is one code.
     */
    @Test
    fun `each distinct emoji is shown as one byte handed out on first use`() {
        val font = ColorEmojiFont("emoji", fontBytes())

        assertContentEquals(byteArrayOf(1, 2), font.encode(GRIN + BEAM))
        assertContentEquals(byteArrayOf(2, 1, 3), font.encode(BEAM + GRIN + TEARS))
    }

    /**
     * No silent loss. A character the program's `cmap`
     * maps to glyph 0 has no picture in the font, and a Type 3 font paints only
     * what its CharProcs hold — so drawing it would produce a page with a gap in
     * it and every check green.
     */
    @Test
    fun `a character the font program has no glyph for is refused`() {
        val font = ColorEmojiFont("emoji", fontBytes())

        val refused = assertFailsWith<UnencodableCharacterException> { font.encode(GRIN + "A") }

        assertEquals("A", refused.character)
        assertEquals(2, refused.index, "the index is into the string, and the grinning face is a surrogate pair")
        assertEquals("emoji", refused.fontName)
    }

    /**
     * A glyph the `cmap` maps and the strike has no bitmap for: the picture is
     * the whole glyph here, so this is refused too, and the message says which of
     * the two tables answered.
     */
    @Test
    fun `a glyph the strike stores no bitmap for is refused`() {
        // The strike covers glyphs 1 and 2 only; U+1F602 selects glyph 3.
        val font = ColorEmojiFont("emoji", fontBytes(lastGlyphInStrike = 2))

        val refused = assertFailsWith<UnencodableCharacterException> { font.encode(GRIN + TEARS) }

        assertEquals(TEARS, refused.character)
        assertTrue("'CBLC'" in refused.message.orEmpty(), refused.message)
        assertTrue("glyph 3" in refused.message.orEmpty(), refused.message)
    }

    // ---- Measuring --------------------------------------------------------

    /**
     * [GlyphSpaceMetrics] answers in the glyph space of §9.2.4 — "the units of
     * glyph space are one-thousandth of a unit of text space" — whatever this
     * font's own `FontMatrix` says. The strike is 32 pixels to the em, so a pixel
     * is 1000/32 = 31.25 of those units.
     */
    @Test
    fun `advances come from the strike converted out of its pixels`() {
        val font = ColorEmojiFont("emoji", fontBytes())

        assertEquals((5 + 7) * 1000.0 / 32, font.advanceOf(GRIN + BEAM))
        // The strike's own line metrics: ascender 101 pixels, descender -27.
        assertEquals(101 * 1000.0 / 32, font.ascent)
        assertEquals(-27 * 1000.0 / 32, font.descent)
    }

    /** Measuring is not drawing: a width asked for does not put a glyph in the file. */
    @Test
    fun `measuring text does not put its glyphs in the font`() {
        val font = ColorEmojiFont("emoji", fontBytes())
        font.advanceOf(GRIN + BEAM + TEARS)
        font.encode(BEAM)

        val written = write(font)

        assertEquals("[1 /g2]", serializeToText(differencesOf(written.font)))
    }

    // ---- The font dictionary (Table 110) ----------------------------------

    /**
     * Table 110 in full, for a font showing two emoji. Every entry the table
     * marks Required is here, plus the `Resources` the glyph descriptions need
     * and the `ToUnicode` without which the emoji extract as bytes.
     */
    @Test
    fun `a colour font is written as a Type 3 font over its own CharProcs`() {
        val written = write(GRIN + BEAM)

        assertEquals(
            "<</Type /Font /Subtype /Type3 /FontBBox [0 -64 384 192] " +
                "/FontMatrix [0.00048828125 0 0 0.00048828125 0 0] " +
                "/CharProcs <</g1 1 0 R /g2 2 0 R>> " +
                "/Encoding <</Type /Encoding /Differences [1 /g1 /g2]>> " +
                "/FirstChar 1 /LastChar 2 /Widths 3 0 R " +
                "/Resources <</XObject <</Im1 4 0 R /Im2 5 0 R>>>> /ToUnicode 6 0 R>>",
            serializeToText(written.font),
        )
    }

    /**
     * "FontMatrix … mapping glyph space to text space": the em of the program,
     * so a unit of glyph space is a design unit. 1/2048, written in full — the
     * clause forbids exponential notation (§7.3.3) and a rounded matrix would
     * scale every emoji on the page.
     */
    @Test
    fun `the font matrix is the em of the program`() {
        assertEquals(
            "[0.0009765625 0 0 0.0009765625 0 0]",
            serializeToText(write(GRIN, fontBytes(unitsPerEm = 1024)).font.entries[PdfName("FontMatrix")]!!),
        )
    }

    /**
     * "Widths … each element being the glyph width for the character code that
     * equals FirstChar plus the array index … interpreted in glyph space as
     * specified by FontMatrix", which is 64 units to the pixel here — and an
     * indirect reference, as the table says it should be.
     */
    @Test
    fun `the widths are the strike advances in the glyph space the matrix fixes`() {
        val written = write(GRIN + BEAM + TEARS)

        assertEquals("[320 448 256]", serializeToText(written.defined[written.widths]))
    }

    /**
     * "The stream shall include as its first operator either d0 or d1, followed
     * by operators describing one or more graphics objects" — `d0` because the
     * glyph is an image, which "does set the colour space and colour explicitly"
     * (Table 111, NOTE 2).
     *
     * The `cm` is §8.9.4's: an image "shall be painted in the unit square of user
     * space", so the square is mapped onto the rectangle the strike's metrics
     * put the bitmap in — 4 × 4 pixels at 64 units each, its left edge at
     * bearingX = 1 pixel and its bottom edge at bearingY - height = -1 pixel.
     */
    @Test
    fun `a glyph description states its width and paints its bitmap where the strike puts it`() {
        val written = write(GRIN)

        assertContentEquals(
            flateEncode(
                (
                    "320 0 d0\n" +
                        "q\n" +
                        "256 0 0 256 64 -64 cm\n" +
                        "/Im1 Do\n" +
                        "Q\n"
                    ).encodeToByteArray(),
            ),
            assertIs<PdfStream>(written.defined[0]).data,
        )
    }

    /** The same for a glyph that reaches left of its origin: nothing is clamped. */
    @Test
    fun `a bitmap with a negative bearing is placed left of the origin`() {
        val written = write(TEARS)

        assertContentEquals(
            flateEncode("256 0 d0\nq\n256 0 0 256 -64 64 cm\n/Im1 Do\nQ\n".encodeToByteArray()),
            assertIs<PdfStream>(written.defined[0]).data,
        )
    }

    /**
     * "FontBBox … the smallest rectangle enclosing all marks that would result if
     * all of the glyphs of the font were placed with their origins coincident and
     * their descriptions executed."
     *
     * Glyph 1 occupies [64, -64, 320, 192], glyph 2 [0, 0, 384, 128] and glyph 3
     * [-64, 64, 192, 320]: the union is the rectangle below, and no single glyph
     * of the three has it.
     */
    @Test
    fun `the bounding box is the union of the glyphs the font holds`() {
        assertEquals(
            "[-64 -64 384 320]",
            serializeToText(write(GRIN + BEAM + TEARS).font.entries[PdfName("FontBBox")]!!),
        )
    }

    /**
     * The picture itself: the PNG of `CBDT`, decoded and written back as the image
     * XObject of Table 87 — the path task 42 built, reached from a font instead of
     * from a page. §9.6.4 is what puts it in the font's own resources: "If any
     * glyph descriptions refer to named resources they shall be looked up in the
     * Resources entry of the Type 3 font dictionary."
     */
    @Test
    fun `each glyph paints the bitmap of the strike as an image XObject`() {
        val written = write(GRIN)
        val image = assertIs<PdfStream>(written.defined[written.image("Im1")])

        assertEquals(
            "<</Length ${image.data.size} /Type /XObject /Subtype /Image /Width 4 /Height 4 " +
                "/ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /FlateDecode>>",
            serializeToText(image.dict),
        )
        assertContentEquals(flateEncode(PngImage.decode(colorEmojiPngBytes(GRIN_PIXELS)).samples), image.data)
    }

    /**
     * "In the absence of a ToUnicode entry, no information would be available
     * about what the glyphs mean" (§9.10.3) — and for an emoji font that is the
     * whole of the text. The codespace is one byte wide, "consistent with the
     * encoding that the font uses".
     */
    @Test
    fun `the ToUnicode CMap maps the one byte codes back to the emoji`() {
        val written = write(GRIN + BEAM)
        val cmap = assertIs<PdfStream>(written.defined[written.toUnicode])

        assertContentEquals(
            flateEncode(toUnicodeCMap(listOf(1 to GRIN, 2 to BEAM), bytesPerCode = 1)),
            cmap.data,
        )
        val program = flateDecodedText(1 to GRIN, 2 to BEAM)
        assertTrue("1 begincodespacerange\n<00> <FF>" in program, program)
        assertTrue("<01> <D83DDE00>" in program, program)
    }

    // ---- Order of use -----------------------------------------------------

    /**
     * Draw first, write afterwards. A glyph offered once the CharProcs have been
     * written has no entry there, and "if the name is not present as a key in
     * CharProcs, no glyph shall be painted" — a gap on the page, reported as a
     * success.
     */
    @Test
    fun `an emoji shown after the font was written is refused rather than dropped`() {
        val font = ColorEmojiFont("emoji", fontBytes())
        font.encode(GRIN)
        font.fontDictionary(Definitions()::define)

        val refused = assertFailsWith<IllegalStateException> { font.encode(BEAM) }

        assertTrue("emoji" in refused.message.orEmpty(), refused.message)
        assertTrue(BEAM in refused.message.orEmpty(), refused.message)
        // A glyph already written is still fine: the file can draw it.
        assertContentEquals(byteArrayOf(1), font.encode(GRIN))
    }

    /**
     * A font that drew nothing still writes a font dictionary Table 110 admits:
     * `Widths` holds LastChar - FirstChar + 1 numbers, the encoding names no
     * glyph, and the bounding box is the all-zero one the table gives a meaning
     * to — "a PDF processor shall make no assumptions about glyph sizes".
     */
    @Test
    fun `a colour font a document never drew with writes an empty encoding`() {
        val written = write("")

        assertEquals("[0 0 0 0]", serializeToText(written.font.entries[PdfName("FontBBox")]!!))
        assertEquals("<<>>", serializeToText(written.font.entries[PdfName("CharProcs")]!!))
        assertEquals("[1]", serializeToText(differencesOf(written.font)))
        assertEquals("[0]", serializeToText(written.defined[written.widths]))
    }

    /** A font program with no colour bitmaps is not a colour font, and says so. */
    @Test
    fun `a font program without colour bitmap tables is refused`() {
        val refused = assertFailsWith<IllegalArgumentException> {
            ColorEmojiFont("emoji", suppliedFontBytes()).encode("A")
        }

        assertTrue("'CBLC'" in refused.message.orEmpty(), refused.message)
        assertTrue("emoji" in refused.message.orEmpty(), refused.message)
    }

    // ---- Helpers ----------------------------------------------------------

    /** The objects one call to [ColorEmojiFont.fontDictionary] produced. */
    private class Written(val font: PdfDict, val defined: List<PdfObject>) {
        /** `Widths` is written indirectly, as Table 110 says it should be. */
        val widths: Int get() = indexOf(font.entries[PdfName("Widths")])

        val toUnicode: Int get() = indexOf(font.entries[PdfName("ToUnicode")])

        /** Where the image the font's own `/Resources` names [name] landed (§7.8.3). */
        fun image(name: String): Int {
            val resources = font.entries[PdfName("Resources")] as PdfDict
            val xObjects = resources.entries[PdfName("XObject")] as PdfDict
            return indexOf(xObjects.entries[PdfName(name)])
        }

        private fun indexOf(reference: PdfObject?): Int = (reference as PdfRef).number - 1
    }

    /** A `define` that keeps what it was given and numbers from 1, as a file does. */
    private class Definitions {
        val objects: MutableList<PdfObject> = mutableListOf()

        fun define(value: PdfObject): PdfRef {
            objects += value
            return PdfRef(objects.size)
        }
    }

    /** Shows [text] with a colour font of [bytes] and writes the font out. */
    private fun write(text: String, bytes: ByteArray = fontBytes()): Written {
        val font = ColorEmojiFont("emoji", bytes)
        if (text.isNotEmpty()) font.encode(text)
        return write(font)
    }

    private fun write(font: ColorEmojiFont): Written {
        val definitions = Definitions()
        return Written(font.fontDictionary(definitions::define), definitions.objects)
    }

    private fun differencesOf(font: PdfDict): PdfObject =
        (font.entries[PdfName("Encoding")] as PdfDict).entries[PdfName("Differences")]!!

    /** The CMap program itself, for the assertions about its text. */
    private fun flateDecodedText(vararg mappings: Pair<Int, String>): String =
        toUnicodeCMap(mappings.toList(), bytesPerCode = 1).decodeToString()

    /** The three-glyph colour font of this class's documentation. */
    private fun fontBytes(unitsPerEm: Int = 2048, lastGlyphInStrike: Int = 3): ByteArray =
        colorEmojiFontBytes(unitsPerEm, lastGlyphInStrike)

    private companion object {
        const val GRIN: String = COLOR_EMOJI_GRIN
        const val BEAM: String = COLOR_EMOJI_BEAM
        const val TEARS: String = COLOR_EMOJI_TEARS
    }
}
