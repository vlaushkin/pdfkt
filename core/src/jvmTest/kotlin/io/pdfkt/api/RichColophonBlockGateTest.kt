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
import kotlin.math.roundToInt
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// ISO 32000-2:2020 §7.7.3 "Page tree" for the three pages, §9.4 "Text objects"
// for the lines read back off them, and §9.6.2.2 "Standard 14 fonts" — with
// §9.6.2.1 Table 109 for the names the file carries — for the face the bold run
// in the head is set in. That a colophon is written as blocks is this library's
// own design.

/**
 * The block forms of the colophons on paper: a head that is a styled run rather
 * than a line of plain text, and a foot that says which page of how many.
 *
 * This is what `header { }` buys over `header("text")`. The head of
 * [reportDocument] is `text("Q1 Report") { bold = true }`, and **nothing else in
 * the document is bold** — so `/Helvetica-Bold` in the file can only have come
 * out of the head, which is the assertion a colophon that quietly dropped its
 * styling and set the line in the body face would fail. The foot is
 * `paragraph(pageOfPages(), align = TextAlign.Right)`, and the three pages carry
 * three different numbers: a band written once and handed to every page could
 * only have said one of them.
 *
 * The sides are measured on the rendered page, because extraction cannot tell a
 * line flush left from the same line flush right. Two documents answer it: the
 * foot that states `align = TextAlign.Right` reaches the right margin, and the
 * one that states nothing sits at the left — which is the asymmetry
 * the block form removes, and the one thing about this form that a
 * caller migrating from `footer("text")` has to know.
 */
class RichColophonBlockGateTest {

    @Test
    @DisplayName("the three pages carry the bold head and each its own page number")
    fun theThreePagesCarryTheBoldHeadAndTheirOwnPageNumber() {
        if (skipWithoutQpdf("§7.7.3")) return
        if (skipWithoutPdftotext("§9.4")) return

        withRendered(reportDocument()) { path ->
            assertQpdfCheckIsClean(path)
            assertEquals(PAGES, pageCount(path), "two page breaks should have made three pages")

            val pages = extractedPages(path)
            assertEquals(PAGES, pages.size, "pdftotext and qpdf disagree about how many pages there are")

            for ((index, page) in pages.withIndex()) {
                val number = index + 1
                val marks = withoutSpacing(page)
                assertTrue(page.contains(HEAD), "page $number lost the head written as a styled run")
                assertTrue(page.contains(bodyOf(number)), "page $number lost its own paragraph")
                assertTrue(
                    marks.contains(numberOf(number)),
                    "page $number should carry \"${numberOf(number)}\"; what was extracted was:\n$page",
                )
                for (other in 1..PAGES) {
                    if (other == number) continue
                    assertFalse(
                        marks.contains(numberOf(other)),
                        "page $number carries \"${numberOf(other)}\" as well as its own number",
                    )
                }
            }

            // The count is the document's and not the page's: a foot planned
            // before the pagination was known could only have said "1/1".
            assertFalse(
                withoutSpacing(pages[0]).contains("1/1"),
                "the foot counted the pages before they existed",
            )

            val fonts = objectGraph(path)
            assertTrue(
                """"/BaseFont": "/$BOLD_FACE"""" in fonts,
                "the file carries no /$BOLD_FACE, so `bold = true` inside the head did not reach the page",
            )
        }
    }

    /**
     * Where the two bands sit across the page: the head flush left because a block
     * colophon has no side of its own, the foot flush right because it said so.
     */
    @Test
    @DisplayName("a block head is flush left and a foot that asks for the right margin reaches it")
    fun theBandsSitOnTheSidesTheyWereGiven() {
        if (skipWithoutPdftoppm("the marks on the page")) return

        withRendered(reportDocument()) { path ->
            val raster = rasterisePage(path, page = 2)
            val bands = raster.inkBands()
            assertEquals(
                BANDS_PER_PAGE,
                bands.size,
                "a page with a head, a paragraph and a foot leaves three bands of ink, and left $bands",
            )

            val headInk = assertNotNull(raster.inkColumns(bands.first()), "the head left no mark")
            val footInk = assertNotNull(raster.inkColumns(bands.last()), "the foot left no mark")

            assertTrue(
                headInk.first <= MARGIN.px + SLOP,
                "the head begins at column ${headInk.first}, not at the ${MARGIN}pt left margin",
            )
            assertTrue(
                footInk.last >= raster.width - MARGIN.px - SLOP,
                "the foot ends at column ${footInk.last}, not at the ${MARGIN}pt right margin: " +
                    "align = TextAlign.Right did not reach the band",
            )
        }
    }

    /**
     * The same foot with the alignment line taken out: it sits at the left margin,
     * where the block form puts everything nobody placed.
     *
     * The control is the text form in the same position — `footer("...")`, whose
     * right-hand default is kept for compatibility. One
     * document is not enough here: a renderer that ignored alignment altogether
     * would draw both at the left and pass an assertion about the block form
     * alone.
     */
    @Test
    @DisplayName("a block foot that states no side is flush left where the text form is flush right")
    fun aBlockFootWithNoSideIsFlushLeftWhereTheTextFormIsFlushRight() {
        if (skipWithoutPdftoppm("the marks on the page")) return

        withRendered(plainFootDocument(asBlock = true)) { path ->
            val raster = rasterisePage(path, page = 1)
            val footInk = assertNotNull(
                raster.inkColumns(raster.inkBands().last()),
                "the block foot left no mark",
            )
            assertTrue(
                footInk.first <= MARGIN.px + SLOP,
                "the block foot begins at column ${footInk.first}: it was not set flush left",
            )
            assertTrue(
                footInk.last < raster.width - MARGIN.px - CLEAR.px,
                "the block foot reaches column ${footInk.last}: it is not set flush left",
            )
        }

        withRendered(plainFootDocument(asBlock = false)) { path ->
            val raster = rasterisePage(path, page = 1)
            val footInk = assertNotNull(
                raster.inkColumns(raster.inkBands().last()),
                "the text foot left no mark",
            )
            assertTrue(
                footInk.last >= raster.width - MARGIN.px - SLOP,
                "the text foot ends at column ${footInk.last}: the form that defaults to the right margin " +
                    "no longer reaches it",
            )
        }
    }

    // ------------------------------------------------------------- the documents

    /**
     * Three pages, a head that is one bold run, a foot that says "2 / 3" on the
     * page that is the second of three. Nothing else in it is styled.
     */
    private fun reportDocument(): Document = document {
        clock = PINNED_CLOCK
        margins = Margins.Fixed(top = MARGIN.pt, right = MARGIN.pt, bottom = MARGIN.pt, left = MARGIN.pt)

        header { paragraph { text(HEAD) { bold = true } } }
        footer { paragraph(pageOfPages(), align = TextAlign.Right) }

        for (page in 1..PAGES) {
            if (page > 1) pageBreak()
            paragraph(bodyOf(page))
        }
    }

    /**
     * One page carrying the same line as a foot, written in the block form or in
     * the text form — the pair [aBlockFootWithNoSideIsFlushLeftWhereTheTextFormIsFlushRight]
     * compares. Neither says where the line goes; the defaults of the two forms do.
     */
    private fun plainFootDocument(asBlock: Boolean): Document = document {
        clock = PINNED_CLOCK
        margins = Margins.Fixed(top = MARGIN.pt, right = MARGIN.pt, bottom = MARGIN.pt, left = MARGIN.pt)

        if (asBlock) footer { paragraph(FOOT) } else footer(FOOT)

        paragraph(bodyOf(1))
    }

    /** This many points as columns of the raster, at the resolution it was drawn at. */
    private val Int.px: Int get() = (this * RASTER_DPI / POINTS_PER_INCH).roundToInt()

    private companion object {
        const val PAGES = 3
        const val MARGIN = 40

        const val HEAD = "Q1 Report"
        const val FOOT = "Draft"

        /** The face `bold = true` resolves to in the default theme, as Table 109 names it. */
        const val BOLD_FACE = "Helvetica-Bold"

        /** A head, one paragraph and a foot. */
        const val BANDS_PER_PAGE = 3

        const val POINTS_PER_INCH = 72.0

        /** A glyph's side bearing and the renderer's antialiasing, in pixels. */
        const val SLOP = 8

        /**
         * How much white a line has to leave on the side it is *not* set against,
         * in points: the foot is far shorter than the column, so one that came
         * within this of the far margin was not set to the near one.
         */
        const val CLEAR = 100

        /** The one line of body on page [page], distinct from every band's line. */
        fun bodyOf(page: Int): String = "Body of leaf $page"

        /**
         * What the foot of page [page] reads as, once the spacing is out of the
         * way: `pageOfPages()` draws "1 / 3" and pdftotext hands back "1/3",
         * because the gap either side of the solidus is a space at the foot's size
         * and the extractor does not keep a gap that small. What is being asserted
         * is which page carries which number, so the assertion is made where the
         * two spellings agree — see [withoutSpacing].
         */
        fun numberOf(page: Int): String = "$page/$PAGES"

        /** The marks of an extracted page with every run of whitespace taken out. */
        fun withoutSpacing(page: String): String = page.replace(WHITE_SPACE, "")
    }
}
