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
import io.pdfkt.core.font.COLOR_EMOJI_ASCENDER
import io.pdfkt.core.font.COLOR_EMOJI_BEAM
import io.pdfkt.core.font.COLOR_EMOJI_GRIN
import io.pdfkt.core.font.COLOR_EMOJI_PPEM
import io.pdfkt.core.font.FontCatalog
import io.pdfkt.core.font.FontRef
import io.pdfkt.core.font.UnencodableCharacterException
import io.pdfkt.core.font.colorEmojiFontBytes
import io.pdfkt.core.fragment.LineBreaker
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
// Unicode Technical Standard #51 §1.4.6 "Emoji Sequences", §2.1 "Emoji
// Presentation", §2.3 "Emoji ZWJ Sequences"

/**
 * [EmojiPolicy.EmbeddedColorFont] in the resolve stage: where a run is split,
 * which font each half names, and what happens to an emoji the supplied colour
 * font has no picture for.
 *
 * The colour font is the three-glyph one of `colorEmojiFontBytes`: it has
 * pictures for U+1F600, U+1F601 and U+1F602 and for nothing else, so U+1F44B —
 * the waving hand — is the emoji it has to refuse, and U+200D and U+FE0F are
 * what a sequence brings with it that no `cmap` of this font maps.
 *
 * What is *not* asserted here is anything about geometry beyond the one thing
 * this policy decides: an emoji run and the text around it end up on one line,
 * whose ascent is the taller of the two fonts'. Everything else the line breaker
 * and the fragmenter do is theirs, and this policy changed neither.
 */
class EmojiColorFontPolicyTest {
    private val policy: EmojiPolicy.EmbeddedColorFont =
        EmojiPolicy.EmbeddedColorFont(colorEmojiFontBytes(), "emoji")

    private val theme: Theme = Theme.Default.copy(emojiPolicy = policy)

    // ---- The split ----------------------

    /**
     * "resolve turns inline runs into styled runs and splits them by
     * renderability: font changes, emoji policy boundaries" — and here the two
     * are the same thing: the emoji is a run of its own because it is drawn with
     * another font, and the text on either side is untouched, spaces included.
     */
    @Test
    fun `an emoji becomes a run of its own in the colour font`() {
        val runs = runsOf("Hi $GRIN there")

        assertEquals(listOf("Hi ", GRIN, " there"), runs.map { it.text })
        assertEquals(listOf(theme.bodyFamily.regular, policy.font, theme.bodyFamily.regular), runs.map { it.font })
    }

    /** The reference is the policy's own, so a document that draws a hundred emoji names one font program. */
    @Test
    fun `every emoji run names the one colour font of the policy`() {
        val runs = runsOf("$GRIN a $BEAM")

        assertSame(policy.font, runs[0].font)
        assertSame(policy.font, runs[2].font)
    }

    /**
     * Two emoji beside each other are drawn identically, so they are one run —
     * the merging rule of the resolve stage, which knows nothing about emoji.
     */
    @Test
    fun `adjacent emoji are one run`() {
        assertEquals(listOf("Hi ", GRIN + BEAM, "!"), runsOf("Hi $GRIN$BEAM!").map { it.text })
    }

    /**
     * The policy changes the font and nothing else. An emoji inside a link is
     * inside the link, at the size and in the colour of the text it stands in —
     * a picture that fell out of the link would be a picture the caller did not
     * write.
     */
    @Test
    fun `an emoji keeps the size colour and link of the text it stands in`() {
        val paragraph = ParagraphBlock(
            listOf(LinkRun("https://example.org", listOf(StyledRun(listOf(TextRun("go $GRIN")), bold = true)))),
        )
        val emoji = runsOf(paragraph).last()

        assertEquals(GRIN, emoji.text)
        assertEquals(policy.font, emoji.font)
        assertEquals(theme.bodyFontSize, emoji.size)
        assertEquals(theme.linkColor, emoji.color)
        assertEquals("https://example.org", emoji.link)
        assertTrue(emoji.underline, "the theme underlines links, and the emoji is part of one")
    }

    /**
     * The colour font is the document's, resolved through the catalog like any
     * other font — which is what makes it a Type 3 font dictionary in the file
     * (§9.6.4, Table 110) without anything below the resolve stage knowing that
     * emoji exist.
     */
    @Test
    fun `resolving registers the colour font with the document as a Type 3 font`() {
        val fonts = FontCatalog()
        Resolver(theme, fonts).resolve(listOf(ParagraphBlock(listOf(TextRun("Hi $GRIN")))))

        val emojiFont = fonts.fonts().single { it.ref is FontRef.ColorEmoji }
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
     * the two fonts' — here the colour font's, which states it in the
     * `sbitLineMetrics` of the strike being drawn rather than in `hhea`.
     *
     * The strike is 101 pixels above the baseline at 32 pixels to the em, so at
     * 11 pt that is 101 × 11 / 32 = 34.71875 pt; the emoji's own advance is
     * 5 pixels, 5 × 11 / 32 = 1.71875 pt. Both come out exactly in binary.
     */
    @Test
    fun `a mixed line shares one baseline and takes its ascent from the strike`() {
        val content = Resolver(theme, FontCatalog()).resolve(listOf(ParagraphBlock(listOf(TextRun("Hi $GRIN")))))
        val fonts = FontCatalog()
        val line = LineBreaker(fonts)
            .breakIntoLines((content.single() as StyledParagraph).content, width = 200.pt)
            .single()

        assertEquals(listOf("Hi ", GRIN), line.runs.map { it.text })
        assertEquals(
            Pt(COLOR_EMOJI_ASCENDER * theme.bodyFontSize.value / COLOR_EMOJI_PPEM),
            line.ascent,
            "the ascent of the strike, which is taller than Helvetica's at this size",
        )
        assertEquals(Pt(1.71875f), line.runs[1].width, "the strike's advance of 5 pixels at 11 pt")
        assertEquals(line.runs[0].width, line.runs[1].xOffset, "the emoji starts where the text ends")
    }

    // ---- No silent loss --------------------------

    /**
     * An emoji the supplied font has no picture for stops the document, naming
     * the emoji and where it stands in the string the caller wrote — not in the
     * fragment the splitter handed to the font.
     */
    @Test
    fun `an emoji the colour font has no picture for is refused with its position`() {
        val refused = assertFailsWith<UnencodableCharacterException> { runsOf("Hello $WAVE world") }

        assertEquals(0x1F44B, refused.codePoint)
        assertEquals(6, refused.index, "the wave is at index 6 of \"Hello 👋 world\"")
        assertEquals("Hello $WAVE world", refused.text)
        assertTrue("U+1F44B" in refused.message.orEmpty(), refused.message)
        assertTrue("EmojiPolicy.Strip" in refused.message.orEmpty(), refused.message)
    }

    /**
     * A ZWJ sequence is one segment (UTS #51 §2.3) and stays one: the font is
     * asked for the whole of it, and when it cannot draw all of it the report
     * names the sequence — every code point of it — and points at where the
     * sequence begins, not at the joiner in the middle of it.
     */
    @Test
    fun `a zero-width-joiner sequence is refused whole and named whole`() {
        val sequence = GRIN + ZWJ + BEAM
        val refused = assertFailsWith<UnencodableCharacterException> { runsOf("hi $sequence") }

        assertEquals(3, refused.index, "the sequence begins at index 3, joiner or no joiner")
        assertTrue("U+1F600 U+200D U+1F601" in refused.message.orEmpty(), refused.message)
        assertTrue("GSUB" in refused.message.orEmpty(), refused.message)
    }

    /**
     * The emoji presentation selector belongs to the emoji before it (ED-9), so
     * an emoji written with U+FE0F is one segment and is refused as one when the
     * font has no picture for the selector — the message names both code points,
     * which is how a reader can tell the selector is what the font was asked
     * for.
     */
    @Test
    fun `an emoji with the variation selector stays one segment`() {
        val refused = assertFailsWith<UnencodableCharacterException> { runsOf(GRIN + VS16) }

        assertEquals(0, refused.index)
        assertTrue("U+1F600 U+FE0F" in refused.message.orEmpty(), refused.message)
    }

    /**
     * A code block is one font per line by construction (`StyledCode`), which is
     * the one place this policy cannot be applied. It is refused with the emoji
     * named and the two policies that need no font offered — rather than drawn
     * in the code font, which would be a policy the document did not choose, or
     * dropped, which would be the silent loss the engine forbids.
     */
    @Test
    fun `an emoji in a code block is refused rather than set in two fonts`() {
        val refused = assertFailsWith<IllegalArgumentException> {
            Resolver(theme, FontCatalog()).resolve(listOf(CodeBlock("val a = 1 // $GRIN")))
        }

        assertTrue("U+1F600" in refused.message.orEmpty(), refused.message)
        assertTrue("EmojiPolicy.Placeholder" in refused.message.orEmpty(), refused.message)
    }

    /** A code block without emoji is untouched by the policy, whatever the policy is. */
    @Test
    fun `a code block without emoji resolves as it always did`() {
        val resolved = Resolver(theme, FontCatalog()).resolve(listOf(CodeBlock("val a = 1\nval b = 2")))

        assertEquals(listOf("val a = 1", "val b = 2"), (resolved.single() as StyledCode).lines.map { it.text })
    }

    private fun runsOf(text: String): List<StyledTextRun> = runsOf(ParagraphBlock(listOf(TextRun(text))))

    private fun runsOf(block: Block): List<StyledTextRun> {
        val resolved = Resolver(theme, FontCatalog()).resolve(listOf(block))
        return (resolved.single() as StyledParagraph).content.filterIsInstance<StyledTextRun>()
    }

    private companion object {
        const val GRIN: String = COLOR_EMOJI_GRIN
        const val BEAM: String = COLOR_EMOJI_BEAM

        /** U+1F44B WAVING HAND SIGN — an emoji this colour font has no picture for. */
        const val WAVE: String = "👋"

        /** U+200D ZERO WIDTH JOINER (UTS #51 §2.3). */
        const val ZWJ: String = "‍"

        /** U+FE0F VARIATION SELECTOR-16, the emoji presentation selector (ED-8). */
        const val VS16: String = "️"
    }
}
