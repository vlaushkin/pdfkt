/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.resolve

import io.pdfkt.core.block.Block
import io.pdfkt.core.block.CodeBlock
import io.pdfkt.core.block.LinkRun
import io.pdfkt.core.block.ParagraphBlock
import io.pdfkt.core.block.StyledRun
import io.pdfkt.core.block.TextRun
import io.pdfkt.core.font.EmojiRasterizer
import io.pdfkt.core.font.FontCatalog
import io.pdfkt.core.font.FontRef
import io.pdfkt.core.font.RasterGlyph
import io.pdfkt.core.font.UnencodableCharacterException
import io.pdfkt.core.fragment.LineBreaker
import io.pdfkt.core.image.pngOf
import io.pdfkt.core.model.Pt
import io.pdfkt.core.model.pt
import io.pdfkt.core.obj.PdfDict
import io.pdfkt.core.obj.PdfName
import io.pdfkt.core.obj.PdfRef
import io.pdfkt.core.obj.serializeToText
import io.pdfkt.core.style.EmojiPolicy
import io.pdfkt.core.style.Theme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

// ISO 32000-2:2020 §9.6.4 "Type 3 fonts" (Table 110)
// Unicode Technical Standard #51 §1.4.6 "Emoji Sequences", §2.3 "Emoji ZWJ
// Sequences"

/**
 * [EmojiPolicy.Rasterized] in the resolve stage: where a run is split, which
 * font each half names, and what happens to an emoji the caller's rasterizer has
 * no picture for.
 *
 * The rasterizer here has pictures for the waving hand and for one ZWJ sequence
 * — a family — and for nothing else, so U+1F389, the party popper, is the emoji
 * it has to refuse. Every picture is [PICTURE_PIXELS] pixels square with an
 * advance of the same, at an em of [EM_PIXELS], so an emoji advances exactly
 * half an em and the numbers of the layout come out in binary.
 *
 * The routing asserted here is the routing `EmojiColorFontPolicyTest` asserts of
 * the other font-drawn policy, because it is the same routing: the resolve stage
 * knows that an emoji goes to a font of its own, and nothing more about which.
 */
class EmojiRasterizedPolicyTest {
    private val rasterizer: EmojiRasterizer = EmojiRasterizer { sequence, emPixels ->
        if (sequence !in PICTURES) null else picture(emPixels)
    }

    private val policy: EmojiPolicy.Rasterized = EmojiPolicy.Rasterized(rasterizer, "emoji", EM_PIXELS)

    private val theme: Theme = Theme.Default.copy(emojiPolicy = policy)

    // ---- The split ----------------------

    /**
     * "resolve turns inline runs into styled runs and splits them by
     * renderability: font changes, emoji policy boundaries" — the emoji is a run
     * of its own because it is drawn with another font, and the text on either
     * side is untouched, spaces included.
     */
    @Test
    fun `an emoji becomes a run of its own in the rasterized font`() {
        val runs = runsOf("Hi $WAVE there")

        assertEquals(listOf("Hi ", WAVE, " there"), runs.map { it.text })
        assertEquals(listOf(theme.bodyFamily.regular, policy.font, theme.bodyFamily.regular), runs.map { it.font })
    }

    /** The reference is the policy's own, so a document that draws a hundred emoji names one rasterizer. */
    @Test
    fun `every emoji run names the one rasterized font of the policy`() {
        val runs = runsOf("$WAVE a $FAMILY")

        assertSame(policy.font, runs[0].font)
        assertSame(policy.font, runs[2].font)
    }

    /**
     * The policy changes the font and nothing else: an emoji inside a link is
     * inside the link, at the size and in the colour of the text it stands in.
     */
    @Test
    fun `an emoji keeps the size colour and link of the text it stands in`() {
        val paragraph = ParagraphBlock(
            listOf(LinkRun("https://example.org", listOf(StyledRun(listOf(TextRun("go $WAVE")), bold = true)))),
        )
        val emoji = runsOf(paragraph).last()

        assertEquals(WAVE, emoji.text)
        assertEquals(policy.font, emoji.font)
        assertEquals(theme.linkColor, emoji.color)
        assertEquals("https://example.org", emoji.link)
    }

    /**
     * A ZWJ sequence is one segment (UTS #51 §2.3), one run, and — this being
     * the rasterized path — one picture: the rasterizer is asked for the family,
     * not for the three people it is made of.
     */
    @Test
    fun `a ZWJ sequence goes to the rasterizer whole and is drawn as one glyph`() {
        val asked = mutableListOf<String>()
        val recording = EmojiRasterizer { sequence, emPixels ->
            asked += sequence
            if (sequence !in PICTURES) null else picture(emPixels)
        }
        val fonts = FontCatalog()
        val theme = Theme.Default.copy(emojiPolicy = EmojiPolicy.Rasterized(recording, "emoji", EM_PIXELS))

        val runs = Resolver(theme, fonts).resolve(listOf(ParagraphBlock(listOf(TextRun("a $FAMILY")))))
        val content = (runs.single() as StyledParagraph).content.filterIsInstance<StyledTextRun>()

        assertEquals(listOf("a ", FAMILY), content.map { it.text })
        assertEquals(listOf(FAMILY), asked.distinct())
    }

    /**
     * The font is the document's, resolved through the catalog like any other —
     * which is what makes it a Type 3 font dictionary in the file (§9.6.4, Table
     * 110) without anything below the resolve stage knowing that emoji exist.
     */
    @Test
    fun `resolving registers the rasterized font with the document as a Type 3 font`() {
        val fonts = FontCatalog()
        Resolver(theme, fonts).resolve(listOf(ParagraphBlock(listOf(TextRun("Hi $WAVE")))))

        val emojiFont = fonts.fonts().single { it.ref is FontRef.RasterizedEmoji }
        var defined = 0
        val dictionary = emojiFont.fontDictionary { PdfRef(++defined) }
        val encoding = assertIs<PdfDict>(dictionary.entries[PdfName("Encoding")])

        assertEquals(PdfName("Type3"), dictionary.entries[PdfName("Subtype")])
        assertEquals(
            "[1 /g1]",
            serializeToText(encoding.entries[PdfName("Differences")]!!),
            "one code, for the one emoji the document drew",
        )
    }

    // ---- One baseline ---------------------------------

    /**
     * A mixed line is one line: the text and the emoji sit side by side on the
     * baseline the line was placed on, and the line's ascent is the taller of
     * the two fonts' — here the rasterized font's, which states one em, taller
     * than Helvetica's ascent at any size.
     *
     * The emoji's own advance is half an em, so at 11 pt it is 5.5 pt.
     */
    @Test
    fun `a mixed line shares one baseline and takes its ascent from the em`() {
        val content = Resolver(theme, FontCatalog()).resolve(listOf(ParagraphBlock(listOf(TextRun("Hi $WAVE")))))
        val line = LineBreaker(FontCatalog())
            .breakIntoLines((content.single() as StyledParagraph).content, width = 200.pt)
            .single()

        assertEquals(listOf("Hi ", WAVE), line.runs.map { it.text })
        assertEquals(Pt(theme.bodyFontSize.value), line.ascent, "one em at the body size")
        assertEquals(Pt(theme.bodyFontSize.value / 2), line.runs[1].width, "half an em of advance at 11 pt")
        assertEquals(line.runs[0].width, line.runs[1].xOffset, "the emoji starts where the text ends")
    }

    // ---- No silent loss --------------------------

    /**
     * An emoji the rasterizer answers null for stops the document, naming the
     * emoji and where it stands in the string the caller wrote — not in the
     * fragment the splitter handed to the font.
     */
    @Test
    fun `an emoji the rasterizer has no picture for is refused with its position`() {
        val refused = assertFailsWith<UnencodableCharacterException> { runsOf("Hello $PARTY world") }

        assertEquals(0x1F389, refused.codePoint)
        assertEquals(6, refused.index, "the party popper is at index 6 of \"Hello 🎉 world\"")
        assertEquals("Hello $PARTY world", refused.text)
        assertTrue("U+1F389" in refused.message.orEmpty(), refused.message)
        assertTrue("$EM_PIXELS pixels to the em" in refused.message.orEmpty(), refused.message)
        assertTrue("EmojiPolicy.Strip" in refused.message.orEmpty(), refused.message)
    }

    /**
     * A sequence is refused whole and named whole: the rasterizer was asked for
     * one picture of it, so there is no code point in it that is the one at
     * fault, and the index points at where the sequence begins.
     */
    @Test
    fun `a sequence with no picture is named with every code point of it`() {
        val sequence = PARTY + ZWJ + PARTY
        val refused = assertFailsWith<UnencodableCharacterException> { runsOf("hi $sequence") }

        assertEquals(3, refused.index, "the sequence begins at index 3, joiner or no joiner")
        assertTrue("U+1F389 U+200D U+1F389" in refused.message.orEmpty(), refused.message)
    }

    /**
     * A code block is one font per line by construction (`StyledCode`), which is
     * the one place a font-drawn emoji policy cannot be applied. It is refused
     * with the emoji named and the two policies that need no font offered.
     */
    @Test
    fun `an emoji in a code block is refused rather than set in two fonts`() {
        val refused = assertFailsWith<IllegalArgumentException> {
            Resolver(theme, FontCatalog()).resolve(listOf(CodeBlock("val a = 1 // $WAVE")))
        }

        assertTrue("U+1F44B" in refused.message.orEmpty(), refused.message)
        assertTrue("EmojiPolicy.Rasterized" in refused.message.orEmpty(), refused.message)
        assertTrue("EmojiPolicy.Placeholder" in refused.message.orEmpty(), refused.message)
    }

    /** Two rasterizers under one id would draw one document's emoji with the other's pictures. */
    @Test
    fun `two rasterizers under one id are refused`() {
        val fonts = FontCatalog()
        fonts.resolve(policy.font)

        val refused = assertFailsWith<IllegalArgumentException> {
            fonts.resolve(EmojiPolicy.Rasterized({ _, emPixels -> picture(emPixels) }, "emoji", EM_PIXELS).font)
        }

        assertTrue("\"emoji\"" in refused.message.orEmpty(), refused.message)
    }

    private fun runsOf(text: String): List<StyledTextRun> = runsOf(ParagraphBlock(listOf(TextRun(text))))

    private fun runsOf(block: Block): List<StyledTextRun> {
        val resolved = Resolver(theme, FontCatalog()).resolve(listOf(block))
        return (resolved.single() as StyledParagraph).content.filterIsInstance<StyledTextRun>()
    }

    private companion object {
        /** The em every picture of these tests is drawn at. */
        const val EM_PIXELS: Int = 64

        /** Half an em, square: the size of every picture this rasterizer draws. */
        const val PICTURE_PIXELS: Int = 32

        /** U+1F44B WAVING HAND SIGN, one code point. */
        const val WAVE: String = "👋"

        /** U+1F468 ZWJ U+1F469 ZWJ U+1F467 — a family, one picture out of five code points. */
        const val FAMILY: String = "👨‍👩‍👧"

        /** U+1F389 PARTY POPPER — the emoji this rasterizer has no picture for. */
        const val PARTY: String = "🎉"

        /** U+200D ZERO WIDTH JOINER (UTS #51 §2.3). */
        const val ZWJ: String = "‍"

        val PICTURES: Set<String> = setOf(WAVE, FAMILY)

        /** One picture, square and half an em, whatever emoji it stands for. */
        fun picture(emPixels: Int): RasterGlyph {
            val side = PICTURE_PIXELS * emPixels / EM_PIXELS
            return RasterGlyph(
                png = pngOf(
                    width = side,
                    height = side,
                    bitDepth = 8,
                    colourType = 2,
                    rows = List(side) { ByteArray(side * 3) { at -> at.toByte() } },
                ),
                width = side,
                height = side,
                bearingX = 0,
                bearingY = side,
                advance = side,
            )
        }
    }
}
