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
import io.pdfkt.core.image.pngOf
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
// §8.9.5.1 "Image dictionaries — General" (Table 87), §9.10.3 "ToUnicode CMaps",
// §7.8.3 "Resource dictionaries"
// Unicode Technical Standard #51 §1.4.6 "Emoji Sequences", §2.3 "Emoji ZWJ Sequences"

/**
 * The rasterized path: from an [EmojiRasterizer] the caller supplies to the
 * Type 3 font the file carries.
 *
 * The rasterizer here draws nothing — it hands back PNGs of flat colour with
 * metrics a test chose — so every number asserted below is a number a test put
 * into a [RasterGlyph]:
 *
 * | Emoji | Picture | Metrics, in pixels of the em asked for |
 * |---|---|---|
 * | 👋 | 4 × 4 | bearingX 1, bearingY 3, advance 5 |
 * | 👨‍👩‍👧 | 6 × 2 | bearingX 0, bearingY 2, advance 7 |
 * | 😂 | 4 × 4 | bearingX -1, bearingY 5, advance 4 |
 *
 * The em is [EM_PIXELS] pixels, and the glyph coordinate system of a rasterized
 * font *is* that pixel — so the numbers in the file are these numbers, and the
 * `FontMatrix` is what turns them into text space. The family is a ZWJ sequence
 * of three characters and two joiners, drawn as one picture, which is the thing
 * this path can do that a font program's `CBDT` cannot.
 */
class RasterizedEmojiFontTest {
    // ---- Showing text -----------------------------------------------------

    /**
     * A Type 3 font is a simple font: "the glyphs … shall be selected by
     * single-byte character codes" (§9.6.1), and which glyph a code selects is
     * this font's `Encoding` (§9.6.5.3). Codes are handed out as emoji are first
     * drawn, so the same emoji drawn twice is one code.
     */
    @Test
    fun `each distinct emoji is shown as one byte handed out on first use`() {
        val font = font()

        assertContentEquals(byteArrayOf(1, 2), font.encode(WAVE + FAMILY))
        assertContentEquals(byteArrayOf(2, 1, 3), font.encode(FAMILY + WAVE + TEARS))
    }

    /**
     * The whole point of the seam: a sequence Unicode composes out of several
     * code points is one picture and one code, because the rasterizer is asked
     * for the sequence. Out of a font program those draw as their parts, which
     * needs `GSUB` to be otherwise.
     */
    @Test
    fun `a ZWJ sequence is one glyph rather than the characters it is composed of`() {
        val font = font()

        assertContentEquals(byteArrayOf(1), font.encode(FAMILY))
        assertEquals(listOf(FAMILY), rasterizer.asked, "the rasterizer was asked for the parts of the family")
    }

    /** A run of several emoji is several glyphs: the font cuts it up the way the resolve stage does. */
    @Test
    fun `a run of adjacent emoji is one code each`() {
        assertContentEquals(byteArrayOf(1, 2, 3), font().encode(WAVE + FAMILY + TEARS))
    }

    /**
     * No silent loss. Null is how a rasterizer says it
     * has no picture, and a Type 3 font paints only what its CharProcs hold — so
     * drawing it would produce a page with a gap in it and every check green.
     */
    @Test
    fun `an emoji the rasterizer has no picture for is refused naming the sequence`() {
        val font = font()

        val refused = assertFailsWith<UnencodableCharacterException> { font.encode(WAVE + PARTY) }

        assertEquals(2, refused.index, "the index is into the string, and the waving hand is a surrogate pair")
        assertTrue(PARTY in refused.message.orEmpty(), refused.message)
        assertTrue("U+1F389" in refused.message.orEmpty(), refused.message)
        assertTrue("emoji" in refused.message.orEmpty(), refused.message)
        assertTrue("EmojiPolicy.Strip" in refused.message.orEmpty(), refused.message)
    }

    /** The sequence of a refused ZWJ emoji is named whole: half a family is nothing anybody wrote. */
    @Test
    fun `a refused sequence is named with every code point of it`() {
        val refused = assertFailsWith<UnencodableCharacterException> { font(pictures = mapOf()).encode(FAMILY) }

        assertTrue(FAMILY in refused.message.orEmpty(), refused.message)
        assertTrue("U+1F468 U+200D U+1F469 U+200D U+1F467" in refused.message.orEmpty(), refused.message)
    }

    /**
     * This font holds pictures of emoji and nothing else. Text named for it is
     * text nothing here can draw, and drawing nothing is the silent loss the
     * engine forbids — the resolve stage sends it the emoji alone and leaves the rest
     * in the font it was set in.
     */
    @Test
    fun `text that is not an emoji is refused rather than drawn as nothing`() {
        val refused = assertFailsWith<UnencodableCharacterException> { font().encode("$WAVE hi") }

        assertEquals(2, refused.index)
        assertEquals(" ", refused.character)
        assertTrue("emoji font" in refused.message.orEmpty(), refused.message)
    }

    /**
     * A picture whose PNG is not the size the rasterizer said it was would be
     * stretched into the box §8.9.4 maps it onto — a page that renders and
     * renders it wrong.
     */
    @Test
    fun `a picture whose PNG disagrees with the stated size is refused`() {
        val font = font(
            pictures = mapOf(
                WAVE to RasterGlyph(
                    png = squarePng(width = 4, height = 4, red = 0xFF),
                    width = 5,
                    height = 4,
                    bearingX = 0,
                    bearingY = 4,
                    advance = 5,
                ),
            ),
        )

        val refused = assertFailsWith<IllegalArgumentException> { font.encode(WAVE) }

        assertTrue("5 x 4" in refused.message.orEmpty(), refused.message)
        assertTrue("4 x 4" in refused.message.orEmpty(), refused.message)
    }

    // ---- Measuring --------------------------------------------------------

    /**
     * [GlyphSpaceMetrics] answers in the glyph space of §9.2.4 — "the units of
     * glyph space are one-thousandth of a unit of text space" — whatever this
     * font's own `FontMatrix` says. The em is 64 pixels, so a pixel is 1000/64
     * of those units.
     */
    @Test
    fun `advances come from the rasterizer converted out of its pixels`() {
        val font = font()

        assertEquals((5 + 7) * 1000.0 / EM_PIXELS, font.advanceOf(WAVE + FAMILY))
    }

    /**
     * A rasterizer states no ascent, so the font states the convention its
     * contract implies: an emoji is a picture of an em standing on the baseline.
     * A number derived from the glyphs drawn so far would make a line's height
     * depend on what the document drew before it.
     */
    @Test
    fun `the line of a rasterized emoji is one em above the baseline and nothing below`() {
        val font = font()
        font.encode(TEARS)

        assertEquals(1000.0, font.ascent)
        assertEquals(0.0, font.descent)
        assertEquals(0.0, font.leading)
    }

    /** Measuring is not drawing: a width asked for does not put a glyph in the file. */
    @Test
    fun `measuring text does not put its glyphs in the font`() {
        val font = font()
        font.advanceOf(WAVE + FAMILY + TEARS)
        font.encode(FAMILY)

        assertEquals("[1 /g1]", serializeToText(differencesOf(write(font).font)))
    }

    /** One call per distinct emoji per document: a page of a hundred waves rasterizes once. */
    @Test
    fun `the rasterizer is asked once per distinct emoji`() {
        val font = font()
        font.advanceOf(WAVE + WAVE)
        font.encode(WAVE + WAVE)
        font.encode(WAVE)

        assertEquals(listOf(WAVE), rasterizer.asked)
        assertEquals(listOf(EM_PIXELS), rasterizer.sizes.distinct())
    }

    // ---- The font dictionary (Table 110) ----------------------------------

    /**
     * Table 110 in full, for a font showing two emoji — the same dictionary
     * `ColorEmojiFontTest` asserts of a font program's bitmaps, because it is
     * the same emission over another source of pictures.
     */
    @Test
    fun `a rasterized font is written as a Type 3 font over its own CharProcs`() {
        val written = write(WAVE + FAMILY)

        assertEquals(
            "<</Type /Font /Subtype /Type3 /FontBBox [0 -1 6 3] " +
                "/FontMatrix [0.015625 0 0 0.015625 0 0] " +
                "/CharProcs <</g1 1 0 R /g2 2 0 R>> " +
                "/Encoding <</Type /Encoding /Differences [1 /g1 /g2]>> " +
                "/FirstChar 1 /LastChar 2 /Widths 3 0 R " +
                "/Resources <</XObject <</Im1 4 0 R /Im2 5 0 R>>>> /ToUnicode 6 0 R>>",
            serializeToText(written.font),
        )
    }

    /**
     * "FontMatrix … mapping glyph space to text space": the em the rasterizer
     * was asked for, so a unit of glyph space is one of its pixels. Written in
     * full — the clause forbids exponential notation (§7.3.3) and a rounded
     * matrix would scale every emoji on the page.
     */
    @Test
    fun `the font matrix is the em the rasterizer was asked for`() {
        val font = RasterizedEmojiFont("emoji", rasterizer, emPixels = 128)
        font.encode(WAVE)

        assertEquals(
            "[0.0078125 0 0 0.0078125 0 0]",
            serializeToText(write(font).font.entries[PdfName("FontMatrix")]!!),
        )
    }

    /**
     * "Widths … each element being the glyph width for the character code that
     * equals FirstChar plus the array index … interpreted in glyph space as
     * specified by FontMatrix", which is the raster's pixel here — and an
     * indirect reference, as the table says it should be.
     */
    @Test
    fun `the widths are the advances the rasterizer stated`() {
        val written = write(WAVE + FAMILY + TEARS)

        assertEquals("[5 7 4]", serializeToText(written.defined[written.widths]))
    }

    /**
     * "The stream shall include as its first operator either d0 or d1, followed
     * by operators describing one or more graphics objects" — `d0` because the
     * glyph is an image, which "does set the colour space and colour explicitly"
     * (Table 111, NOTE 2).
     *
     * The `cm` is §8.9.4's: an image "shall be painted in the unit square of
     * user space", so the square is mapped onto the rectangle the rasterizer's
     * bearings put the picture in — 4 × 4 pixels, its left edge at bearingX = 1
     * and its bottom edge at bearingY - height = -1.
     */
    @Test
    fun `a glyph description states its width and paints the picture where the bearings put it`() {
        val written = write(WAVE)

        assertContentEquals(
            flateEncode("5 0 d0\nq\n4 0 0 4 1 -1 cm\n/Im1 Do\nQ\n".encodeToByteArray()),
            assertIs<PdfStream>(written.defined[0]).data,
        )
    }

    /** The same for a picture that reaches left of its origin: nothing is clamped. */
    @Test
    fun `a picture with a negative bearing is placed left of the origin`() {
        val written = write(TEARS)

        assertContentEquals(
            flateEncode("4 0 d0\nq\n4 0 0 4 -1 1 cm\n/Im1 Do\nQ\n".encodeToByteArray()),
            assertIs<PdfStream>(written.defined[0]).data,
        )
    }

    /**
     * "FontBBox … the smallest rectangle enclosing all marks that would result if
     * all of the glyphs of the font were placed with their origins coincident and
     * their descriptions executed."
     *
     * The wave occupies [1, -1, 5, 3], the family [0, 0, 6, 2] and the tears
     * [-1, 1, 3, 5]: the union is the rectangle below, and no single glyph of
     * the three has it.
     */
    @Test
    fun `the bounding box is the union of the pictures the font holds`() {
        assertEquals(
            "[-1 -1 6 5]",
            serializeToText(write(WAVE + FAMILY + TEARS).font.entries[PdfName("FontBBox")]!!),
        )
    }

    /**
     * The picture itself: the rasterizer's PNG, decoded and written back as the
     * image XObject of Table 87. §9.6.4 is what puts it in the font's own
     * resources: "If any glyph descriptions refer to named resources they shall
     * be looked up in the Resources entry of the Type 3 font dictionary."
     */
    @Test
    fun `each glyph paints the rasterizer's PNG as an image XObject`() {
        val written = write(WAVE)
        val image = assertIs<PdfStream>(written.defined[written.image("Im1")])

        assertEquals(
            "<</Length ${image.data.size} /Type /XObject /Subtype /Image /Width 4 /Height 4 " +
                "/ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /FlateDecode>>",
            serializeToText(image.dict),
        )
        assertContentEquals(flateEncode(PngImage.decode(WAVE_PNG).samples), image.data)
    }

    /**
     * "In the absence of a ToUnicode entry, no information would be available
     * about what the glyphs mean" (§9.10.3) — and here a code stands for a whole
     * sequence, so the CMap maps it back to every code point of it. The codespace
     * is one byte wide, "consistent with the encoding that the font uses".
     */
    @Test
    fun `the ToUnicode CMap maps one byte codes back to the whole sequence`() {
        val written = write(WAVE + FAMILY)
        val cmap = assertIs<PdfStream>(written.defined[written.toUnicode])

        assertContentEquals(
            flateEncode(toUnicodeCMap(listOf(1 to WAVE, 2 to FAMILY), bytesPerCode = 1)),
            cmap.data,
        )
        val program = toUnicodeCMap(listOf(1 to WAVE, 2 to FAMILY), bytesPerCode = 1).decodeToString()
        assertTrue("1 begincodespacerange\n<00> <FF>" in program, program)
        assertTrue("<01> <D83DDC4B>" in program, program)
        assertTrue("<02> <D83DDC68200DD83DDC69200DD83DDC67>" in program, program)
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
        val font = font()
        font.encode(WAVE)
        font.fontDictionary(Definitions()::define)

        val refused = assertFailsWith<IllegalStateException> { font.encode(FAMILY) }

        assertTrue("emoji" in refused.message.orEmpty(), refused.message)
        assertTrue(FAMILY in refused.message.orEmpty(), refused.message)
        // A glyph already written is still fine: the file can draw it.
        assertContentEquals(byteArrayOf(1), font.encode(WAVE))
    }

    /**
     * A font that drew nothing still writes a font dictionary Table 110 admits:
     * `Widths` holds LastChar - FirstChar + 1 numbers, the encoding names no
     * glyph, and the bounding box is the all-zero one the table gives a meaning
     * to — "a PDF processor shall make no assumptions about glyph sizes".
     */
    @Test
    fun `a rasterized font a document never drew with writes an empty encoding`() {
        val written = write("")

        assertEquals("[0 0 0 0]", serializeToText(written.font.entries[PdfName("FontBBox")]!!))
        assertEquals("<<>>", serializeToText(written.font.entries[PdfName("CharProcs")]!!))
        assertEquals("[1]", serializeToText(differencesOf(written.font)))
        assertEquals("[0]", serializeToText(written.defined[written.widths]))
        assertEquals(emptyList(), rasterizer.asked, "a font nobody drew with rasterized something")
    }

    // ---- Helpers ----------------------------------------------------------

    private val rasterizer: RecordingRasterizer = RecordingRasterizer(PICTURES)

    private fun font(pictures: Map<String, RasterGlyph> = PICTURES): RasterizedEmojiFont {
        rasterizer.pictures = pictures
        return RasterizedEmojiFont("emoji", rasterizer, EM_PIXELS)
    }

    /** A rasterizer that hands back what a test put in it, and says what it was asked. */
    private class RecordingRasterizer(var pictures: Map<String, RasterGlyph>) : EmojiRasterizer {
        val asked: MutableList<String> = mutableListOf()
        val sizes: MutableList<Int> = mutableListOf()

        override fun rasterize(sequence: String, emPixels: Int): RasterGlyph? {
            asked += sequence
            sizes += emPixels
            return pictures[sequence]
        }
    }

    /** The objects one call to [RasterizedEmojiFont.fontDictionary] produced. */
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

    /** Shows [text] with a rasterized font and writes the font out. */
    private fun write(text: String): Written {
        val font = font()
        if (text.isNotEmpty()) font.encode(text)
        return write(font)
    }

    private fun write(font: RasterizedEmojiFont): Written {
        val definitions = Definitions()
        return Written(font.fontDictionary(definitions::define), definitions.objects)
    }

    private fun differencesOf(font: PdfDict): PdfObject =
        (font.entries[PdfName("Encoding")] as PdfDict).entries[PdfName("Differences")]!!

    private companion object {
        /** The em every picture of these tests is measured in. */
        const val EM_PIXELS: Int = 64

        /** U+1F44B WAVING HAND SIGN, one code point. */
        const val WAVE: String = "👋"

        /** U+1F468 ZWJ U+1F469 ZWJ U+1F467 — a family, one picture out of five code points. */
        const val FAMILY: String = "👨‍👩‍👧"

        /** U+1F602 FACE WITH TEARS OF JOY, whose picture reaches left of the origin. */
        const val TEARS: String = "😂"

        /** U+1F389 PARTY POPPER — the emoji this rasterizer has no picture for. */
        const val PARTY: String = "🎉"

        val WAVE_PNG: ByteArray = squarePng(width = 4, height = 4, red = 0xC0)

        /**
         * What the rasterizer of these tests answers: the table in the class
         * documentation.
         */
        val PICTURES: Map<String, RasterGlyph> = mapOf(
            WAVE to RasterGlyph(WAVE_PNG, width = 4, height = 4, bearingX = 1, bearingY = 3, advance = 5),
            FAMILY to RasterGlyph(
                png = squarePng(width = 6, height = 2, red = 0x40),
                width = 6,
                height = 2,
                bearingX = 0,
                bearingY = 2,
                advance = 7,
            ),
            TEARS to RasterGlyph(
                png = squarePng(width = 4, height = 4, red = 0x80),
                width = 4,
                height = 4,
                bearingX = -1,
                bearingY = 5,
                advance = 4,
            ),
        )

        /**
         * A PNG of one colour: truecolour, 8 bits a sample (ISO/IEC 15948 Table
         * 11's colour type 2), which is what a rasterizer of a page hands over.
         */
        fun squarePng(width: Int, height: Int, red: Int): ByteArray = pngOf(
            width = width,
            height = height,
            bitDepth = 8,
            colourType = 2,
            rows = List(height) { row ->
                ByteArray(width * 3) { at -> if (at % 3 == 0) red.toByte() else (row * width + at).toByte() }
            },
        )
    }
}
