/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.api

import io.pdfkt.core.font.FontRef
import io.pdfkt.core.style.EmojiPolicy
import io.pdfkt.core.style.FontFamily
import io.pdfkt.core.style.Theme
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// ISO 32000-2:2020 §9.6.4 "Type 3 fonts", §9.7 "Composite fonts",
// §9.10.3 "ToUnicode CMaps". Which font a run is set in is the theme's, and the
// theme is this library's own design.

/**
 * One paragraph in two alphabets and two kinds of font: Russian out of a
 * supplied TrueType program, emoji out of a colour font, written as one string
 * through `document { }`.
 *
 * A caller of this facade sets both on the theme and writes ordinary text. What
 * that has to produce is one line of one baseline whose words come back out of a
 * reader in the order they were written — the resolve stage splits the string at
 * every emoji and hands each piece to a different font program, and a split that lost a letter,
 * reordered the pieces or put the
 * emoji on a baseline of its own would still write a file that opens.
 *
 * `RichCyrillicTest` proves the Cyrillic half of this on its own, and `:core`
 * proves each font against its clause. What is only true here is the two
 * together.
 *
 * Both font programs come from the system; no binary is committed, and a missing
 * one fails rather than skips.
 */
class RichEmojiTest {
    private val family: FontFamily = FontFamily.of(
        FontRef.Supplied(
            systemFontBytes("DejaVuSans.ttf", "This test draws Cyrillic, which needs an embedded font program"),
            FONT_ID,
        ),
    )

    private val colourFont: EmojiPolicy.EmbeddedColorFont = EmojiPolicy.EmbeddedColorFont(
        systemFontBytes(
            "NotoColorEmoji.ttf",
            "This test draws colour emoji, which need a font of bitmaps",
            debianPackage = "fonts-noto-color-emoji",
        ),
        id = EMOJI_FONT_ID,
    )

    /**
     * The two font programs of the document, side by side in one file: the Type 3
     * font §9.6.4 defines for glyphs that are procedures rather than outlines, and
     * the Type 0 font §9.7 defines for the letters between them.
     *
     * Read out of qpdf's object graph, which is the whole file at once — so an
     * entry present in it is one a reader can reach, not one this library believes
     * it wrote.
     */
    @Test
    @DisplayName("a mixed paragraph carries a Type 3 colour font and a Type 0 text font")
    fun theDocumentCarriesBothKindsOfFont() {
        if (skipWithoutQpdf("§9.6.4")) return

        withRendered(mixedDocument(colourFont)) { path ->
            assertQpdfCheckIsClean(path)

            val graph = objectGraph(path)
            for (entry in TYPE3_CHAIN + TYPE0_CHAIN) {
                assertTrue(entry in graph, "the object graph has no $entry")
            }
        }
    }

    /**
     * The paragraph as a reader reports it, word for word and in order.
     *
     * Neither font's codes mean anything outside this file — a Type 0 font shows
     * "pairs of bytes representing CIDs" (§9.7.5.2), and the Type 3 font's codes
     * are numbers handed out on first use — so every word here comes back through
     * a `ToUnicode` CMap (§9.10.3), one written for each of the two fonts.
     *
     * The words rather than the line: an emoji is measured out of a `CBLC` strike
     * and advances about 1.25 em, from which this extractor estimates a line about
     * three ems tall for the Type 3 font and puts the emoji on output lines of
     * their own. That is the tool's arithmetic over a width §9.6.4 requires to be
     * the font's own; where the marks are is asserted below, off the page itself.
     */
    @Test
    @DisplayName("every word of the mixed paragraph is extracted back in the order it was written")
    fun theMixedParagraphIsExtractedBackAsItWasWritten() {
        if (skipWithoutPdftotext("§9.10.3")) return

        withRendered(mixedDocument(colourFont)) { path ->
            assertEquals(PARAGRAPH.split(" "), wordsOf(extractedPages(path).single()))
        }
    }

    /**
     * And both are drawn, not merely written into the text layer: the page carries
     * one band of ink, which is the paragraph on one line, and inside it pixels
     * that are a *colour* — which on a page of black text on white paper can only
     * have come out of an emoji's bitmap.
     *
     * The same document under [EmojiPolicy.Strip] is the control. It removes the
     * emoji before any font is consulted, so it renders the
     * same words in the same face with nothing coloured anywhere — which is what
     * makes the colour above evidence rather than a property of the paper.
     */
    @Test
    @DisplayName("the page carries the Cyrillic and the emoji on one line, and the emoji in colour")
    fun bothAreDrawnOnOneLine() {
        if (skipWithoutPdftoppm("§9.6.4")) return

        withRendered(mixedDocument(colourFont)) { path ->
            assertEquals(
                1,
                rasterisePage(path, 1).inkBands().size,
                "the mixed paragraph is not one band of ink, so it is not one line",
            )
            assertTrue(
                rasteriseColourPage(path, 1).colouredPixels() > COLOURED_PIXELS,
                "the page carries no colour, so no emoji bitmap was painted on it",
            )
        }

        withRendered(mixedDocument(EmojiPolicy.Strip)) { path ->
            assertEquals(
                0,
                rasteriseColourPage(path, 1).colouredPixels(),
                "a page of black text on white paper is not supposed to carry a coloured pixel",
            )
        }
    }

    // ------------------------------------------------------------- the document

    /**
     * The document under test: one paragraph of Russian with two emoji in it, set
     * in the supplied family, with [policy] deciding what becomes of the emoji.
     */
    private fun mixedDocument(policy: EmojiPolicy): Document = document {
        clock = PINNED_CLOCK
        theme = Theme.Default.copy(bodyFamily = family, emojiPolicy = policy)
        paragraph(if (policy == EmojiPolicy.Strip) STRIPPED else PARAGRAPH)
    }

    private companion object {
        const val FONT_ID: String = "DejaVuSans"

        const val EMOJI_FONT_ID: String = "NotoColorEmoji"

        /**
         * Russian and emoji in one string: a camera between two words and a party
         * popper at the end, so the split at an emoji is exercised in the middle of
         * the line and at the end of it.
         */
        const val PARAGRAPH: String = "Отчёт готов 📸 квартал закрыт 🎉"

        /** The same paragraph as [EmojiPolicy.Strip] leaves it, for the control render. */
        const val STRIPPED: String = "Отчёт готов квартал закрыт"

        /**
         * How many coloured pixels an emoji has to leave to count as painted. Two
         * emoji at 11 points, rendered at 150 dpi, are about 23 pixels square each;
         * a hundred is far below what either of them covers and far above the
         * nothing a page of text leaves.
         */
        const val COLOURED_PIXELS: Int = 100

        /** What §9.6.4 and §9.6.5.3 require of the font the emoji are drawn from. */
        val TYPE3_CHAIN: List<String> = listOf(
            "\"/Subtype\": \"/Type3\"",
            "\"/FontMatrix\"",
            "\"/CharProcs\"",
            "\"/Differences\"",
            "\"/Widths\"",
        )

        /** And of the font the letters between them are drawn from (§9.7, §9.10.3). */
        val TYPE0_CHAIN: List<String> = listOf(
            "\"/Subtype\": \"/Type0\"",
            "\"/Subtype\": \"/CIDFontType2\"",
            "\"/Encoding\": \"/Identity-H\"",
            "\"/ToUnicode\"",
            "\"/FontFile2\"",
        )
    }
}
