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
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// ISO 32000-2:2020 §7.7.3 "Page tree" and §9.4 "Text objects" — the pages and the
// lines on them, read back out of the file. When a band is drawn and what it says
// there is this library's own design.

/**
 * The four ways a document schedules a running head or foot, on paper: the same
 * band everywhere, a cover-only band, a band on everything but the cover, and one
 * that says a different thing on the first page — plus the line a band writes for
 * the page it is on, "Page 2 of 3".
 *
 * All of it through `document { }` and `toByteArray()`. Without these forms a
 * rich document that needed one of them would leave the DSL for a `BandSpec`
 * and a `DocumentSpec`, which
 * is the engine's vocabulary in a caller's file. [theFileNamesNoEngineType] holds
 * that claim against this file's own source, as `RichColophonGateTest` does.
 *
 * What is extracted answers *which* page carries a line. What is rastered answers
 * the thing extraction cannot: that a page whose band is not drawn still had the
 * strip taken off it, so the text of the cover begins where the text of page two
 * begins. A reserve that followed the page rule would move the body up on the
 * pages with no band, and every extraction test in this file would still pass.
 */
class RichBandGateTest {

    @Test
    @DisplayName("a cover-only head and a foot that skips the cover land on their own pages")
    fun aCoverOnlyHeadAndAFootThatSkipsTheCoverLandOnTheirOwnPages() {
        if (skipWithoutQpdf("§7.7.3")) return
        if (skipWithoutPdftotext("§9.4")) return

        withRendered(coverDocument()) { path ->
            assertQpdfCheckIsClean(path)
            assertEquals(PAGES, pageCount(path), "two page breaks should have made three pages")

            val pages = extractedPages(path)
            assertEquals(PAGES, pages.size, "pdftotext and qpdf disagree about how many pages there are")

            assertTrue(pages[0].contains(HEAD), "the cover lost the head asked for on the first page only")
            assertFalse(pages[1].contains(HEAD), "a FirstOnly head followed the document onto page 2")
            assertFalse(pages[2].contains(HEAD), "a FirstOnly head followed the document onto page 3")

            assertFalse(pages[0].contains(FOOT), "an ExceptFirst foot was drawn on the cover")
            assertTrue(pages[1].contains(FOOT), "page 2 lost the foot asked for on every page but the first")
            assertTrue(pages[2].contains(FOOT), "page 3 lost the foot asked for on every page but the first")

            for ((index, page) in pages.withIndex()) {
                assertTrue(page.contains(bodyOf(index + 1)), "page ${index + 1} lost its own paragraph")
            }
        }
    }

    /**
     * The strip is a reserve and not a consequence of the page rule: the body of
     * the cover — which carries no foot — ends no lower than the body of page 2,
     * which does, and the body of page 2 — which carries no head — begins no
     * higher than the body of the cover, which does.
     *
     * This is what `DocumentSpec.headerArea` means by taking the strip off every
     * page, and no structural tool asks it: a document that gave the pages without
     * a band the strip back extracts exactly like this one.
     */
    @Test
    @DisplayName("the strip is reserved on the pages the band is not drawn on")
    fun theStripIsReservedOnThePagesTheBandIsNotDrawnOn() {
        if (skipWithoutPdftoppm("the marks on the page")) return

        withRendered(coverDocument()) { path ->
            val cover = rasterisePage(path, page = 1).inkBands()
            val second = rasterisePage(path, page = 2).inkBands()

            // The cover: the head, then its paragraph, and nothing at the foot.
            assertEquals(BANDS_PER_PAGE, cover.size, "the cover should carry a head and a paragraph, got $cover")
            // Page 2: its paragraph, then the foot, and nothing at the head.
            assertEquals(BANDS_PER_PAGE, second.size, "page 2 should carry a paragraph and a foot, got $second")

            val coverBody = cover[1]
            val secondBody = second[0]
            assertTrue(
                abs(coverBody.first - secondBody.first) <= SLOP,
                "the body starts at row ${coverBody.first} on the cover and ${secondBody.first} on page 2: " +
                    "the header's strip was given back to the page that draws no header",
            )
            assertTrue(
                abs(coverBody.last - secondBody.last) <= SLOP,
                "the body ends at row ${coverBody.last} on the cover and ${secondBody.last} on page 2: " +
                    "the footer's strip was given back to the page that draws no footer",
            )
        }
    }

    @Test
    @DisplayName("a head that differs on the first page says both lines, each on its own pages")
    fun aHeadThatDiffersOnTheFirstPageSaysBothLines() {
        if (skipWithoutPdftotext("§9.4")) return

        withRendered(runningDocument()) { path ->
            val pages = extractedPages(path)
            assertEquals(PAGES, pages.size)

            assertTrue(pages[0].contains(TITLE), "the first page lost the title the head was given for it")
            assertFalse(pages[0].contains(HEAD), "the running head was drawn on the title page as well")
            for (rest in 1 until PAGES) {
                assertTrue(pages[rest].contains(HEAD), "page ${rest + 1} lost the running head")
                assertFalse(pages[rest].contains(TITLE), "the title followed the document onto page ${rest + 1}")
            }
        }
    }

    @Test
    @DisplayName("a foot written for its page says which page of how many, on every page")
    fun aFootWrittenForItsPageSaysWhichPageOfHowMany() {
        if (skipWithoutPdftotext("§9.4")) return

        withRendered(runningDocument()) { path ->
            val pages = extractedPages(path)
            assertEquals(PAGES, pages.size)

            for ((index, page) in pages.withIndex()) {
                val expected = numbering(index + 1, PAGES)
                assertTrue(
                    page.contains(expected),
                    "page ${index + 1} should carry \"$expected\"; what was extracted was:\n$page",
                )
            }
            // The count is the document's, not the page's: a band planned before
            // the pagination was known could only have said "of 1".
            assertFalse(pages[0].contains(numbering(1, 1)), "the foot counted the pages before they existed")
        }
    }

    /**
     * The import list of this file, read off the disk: no name in it begins with
     * `io.pdfkt.core`.
     *
     * The claim of this file is that every band scheduling
     * case goes through the rich DSL alone. A band that
     * quietly went back to `BandSpec` would compile and fail here, which is where
     * the claim belongs.
     */
    @Test
    @DisplayName("the file names no engine type")
    fun theFileNamesNoEngineType() {
        val source = SOURCE_PATHS.map { candidate -> Path.of(candidate) }
            .firstOrNull { candidate -> Files.isReadable(candidate) }
        val path = assertNotNull(
            source,
            "this test reads its own source and could not find it; the paths tried, relative to the " +
                "working directory ${Path.of("").toAbsolutePath()}, were ${SOURCE_PATHS.joinToString(", ")}",
        )

        val engineImports = Files.readAllLines(path)
            .map { line -> line.trim() }
            .filter { line -> line.startsWith("import ") && line.contains(ENGINE_PACKAGE) }

        assertTrue(
            engineImports.isEmpty(),
            "the DSL is meant to cover every scheduling case on its own, but the file imports the engine: " +
                engineImports.joinToString("; "),
        )
    }

    // ------------------------------------------------------------ the documents

    /** A head on the cover only, a foot on everything but the cover, three pages. */
    private fun coverDocument(): Document = document {
        clock = PINNED_CLOCK
        margins = Margins.Fixed(top = MARGIN.pt, right = MARGIN.pt, bottom = MARGIN.pt, left = MARGIN.pt)

        header(HEAD, pages = BandPages.FirstOnly)
        footer(FOOT, pages = BandPages.ExceptFirst)

        threePages()
    }

    /** A title on the first page and a running head after it, a numbered foot throughout. */
    private fun runningDocument(): Document = document {
        clock = PINNED_CLOCK
        margins = Margins.Fixed(top = MARGIN.pt, right = MARGIN.pt, bottom = MARGIN.pt, left = MARGIN.pt)

        header(firstPage = TITLE, otherPages = HEAD)
        footer { paragraph(numbering(numbering.page, numbering.pages), align = TextAlign.Right) }

        threePages()
    }

    /** One paragraph per page, three pages, the breaks written where the pages end. */
    private fun DocumentScope.threePages() {
        for (page in 1..PAGES) {
            if (page > 1) pageBreak()
            paragraph(bodyOf(page))
        }
    }

    private companion object {
        const val PAGES = 3
        const val MARGIN = 40

        const val HEAD = "Field notes"
        const val TITLE = "Season summary"
        const val FOOT = "Draft"

        /** A head or a foot, and one paragraph: what a page of these documents carries. */
        const val BANDS_PER_PAGE = 2

        /**
         * How far two rows of two rasters may differ and still be the same row, in
         * pixels: the antialiasing around a glyph, and nothing like the 28 pt strip
         * whose disappearance this would catch.
         */
        const val SLOP = 4

        const val ENGINE_PACKAGE = "io.pdfkt.core"

        val SOURCE_PATHS: List<String> = listOf(
            "src/jvmTest/kotlin/io/pdfkt/api/RichBandGateTest.kt",
            "core/src/jvmTest/kotlin/io/pdfkt/api/RichBandGateTest.kt",
        )

        /** The one line of body on page [page], distinct from every band's line. */
        fun bodyOf(page: Int): String = "Body of leaf $page"

        /** What the numbered foot says on page [page] of [pages]. */
        fun numbering(page: Int, pages: Int): String = "Page $page of $pages"
    }
}
