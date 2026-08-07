/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.api

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// ISO 32000-2:2020 §9.6.2.2 "Standard 14 fonts" — the faces the document is set
// in and the names §9.6.2.1 Table 109 has the file carry for them; §9.4 "Text
// objects" for the text read back out. Which property of the DSL selects which
// face is this library's own design.

/**
 * The property spelling of inline style — `text("x") { bold = true }` — held
 * against a real file.
 *
 * The unit test beside it says the DSL builds the run the caller wrote; this
 * asks the different question, which is whether what the caller wrote reaches
 * the page. A style that produced a correct `StyledRun` and no bold face in the
 * file would pass there and fail a reader here.
 *
 * Two readers answer it. `qpdf` lists the fonts the file carries: [styledDocument]
 * is written in the property form and in nothing else, so `/Helvetica-Bold` in
 * that file can only have come from `bold = true`, `/Helvetica-Oblique` from
 * `italic = true` and `/Courier` from `code = true`. That is why it holds no
 * `bold("x")` or `italic("x")`: a helper beside them would select the same faces
 * and the assertion would pass with the property form doing nothing.
 * `pdftotext` says the words are all there and in the order they were written —
 * the check that styling nine runs did not drop, duplicate or reorder one of
 * them. [mixedDocument] is where the two forms are written side by side.
 *
 * The colour is measured on the rendered page, because no structural tool can
 * tell red glyphs from black ones. The control is the same paragraph with the
 * `color` line taken out: everything else this library draws is black on white,
 * so a coloured pixel in the one and none in the other is the property arriving.
 */
class RichInlineStyleGateTest {

    @Test
    @DisplayName("the property form selects the faces it names and the file reads back word for word")
    fun theDocumentCarriesTheFacesThePropertiesAskedFor() {
        if (skipWithoutQpdf("§9.6.2.2")) return
        if (skipWithoutPdftotext("§9.4")) return

        withRendered(styledDocument()) { path ->
            assertQpdfCheckIsClean(path)

            val fonts = objectGraph(path)
            for (face in FACES) {
                assertTrue(
                    """"/BaseFont": "/$face"""" in fonts,
                    "the file carries no /$face, so the property that selects it did not reach the page",
                )
            }

            assertEquals(
                PARAGRAPH.split(" "),
                wordsOf(extractedPages(path).single()),
                "the styled paragraph does not read back as it was written",
            )
        }
    }

    /**
     * `color` on the page: the run set in [RED] leaves pixels that are a colour,
     * and the same paragraph without that one line leaves none.
     */
    @Test
    @DisplayName("a run given a colour is drawn in it and the same paragraph without it is not")
    fun theColouredRunIsDrawnInItsColour() {
        if (skipWithoutPdftoppm("the marks on the page")) return

        withRendered(styledDocument()) { path ->
            assertTrue(
                rasteriseColourPage(path, 1).colouredPixels() > COLOURED_PIXELS,
                "the page carries no colour, so `color = ...` did not reach the glyphs",
            )
        }

        withRendered(styledDocument(coloured = false)) { path ->
            assertEquals(
                0,
                rasteriseColourPage(path, 1).colouredPixels(),
                "a page of black text on white paper is not supposed to carry a coloured pixel",
            )
        }
    }

    /**
     * The two forms in one paragraph, the way the API is designed to hold them
     * together — the helpers stay as sugar beside the property form, and
     * neither breaks the other. A file, not a tree: the runs the two forms
     * build are asserted
     * against each other in `InlineRunScopeTest`, and what is asked here is
     * whether a paragraph written both ways at once still comes out one
     * paragraph, whole and in order.
     */
    @Test
    @DisplayName("the property form and the helper form mix in one paragraph and it reads back whole")
    fun theTwoFormsMixInOneParagraph() {
        if (skipWithoutQpdf("§7.7.3")) return
        if (skipWithoutPdftotext("§9.4")) return

        withRendered(mixedDocument()) { path ->
            assertQpdfCheckIsClean(path)
            assertEquals(
                MIXED_PARAGRAPH.split(" "),
                wordsOf(extractedPages(path).single()),
                "the paragraph written in both forms does not read back as it was written",
            )
        }
    }

    // ------------------------------------------------------------- the documents

    /**
     * The document under test: one paragraph in which every property of the
     * configure block is used, and *only* the configure block — see the class
     * comment for why no helper appears in it.
     *
     * @param coloured whether the run that carries [RED] states its colour. False
     *   is the control for [theColouredRunIsDrawnInItsColour], and it is the same
     *   document in every other respect — same words, same faces, same lengths.
     */
    private fun styledDocument(coloured: Boolean = true): Document = document {
        clock = PINNED_CLOCK
        paragraph {
            text("Report: ") { bold = true }
            text("Q4 2026 summary. ") { }
            text("dangerous ") { if (coloured) color = RED }
            text("monospaced ") { code = true }
            text("slanted ") { italic = true }
            text("large ") { fontSize = 18.pt }
            text("struck ") { strike = true }
            text("underlined") { underline = true }
        }
    }

    /** One paragraph written in the property form, the helper form and plain text. */
    private fun mixedDocument(): Document = document {
        clock = PINNED_CLOCK
        paragraph {
            text("Report: ") { bold = true }
            bold("Helper ")
            +"and "
            italic("sugar ")
            text("together") {
                color = RED
                underline = true
            }
        }
    }

    private companion object {
        /** The words of [styledDocument], in the order the runs write them. */
        const val PARAGRAPH: String =
            "Report: Q4 2026 summary. dangerous monospaced slanted large struck underlined"

        /** The words of [mixedDocument], in the order the runs write them. */
        const val MIXED_PARAGRAPH: String = "Report: Helper and sugar together"

        /**
         * The faces the paragraph asks for, spelled as Table 109 names them.
         * `Helvetica` is the theme's body family, so the plain runs are set in it;
         * the other three are what `bold`, `italic` and `code` resolve to.
         */
        val FACES: List<String> = listOf("Helvetica", "Helvetica-Bold", "Helvetica-Oblique", "Courier")

        val RED = Rgb.of(0xCC0000)

        /**
         * How many coloured pixels count as a coloured run: well above the stray
         * pixel a renderer's antialiasing might leave, well below the several
         * hundred a word of red text at 150 dpi covers.
         */
        const val COLOURED_PIXELS: Int = 100
    }
}
