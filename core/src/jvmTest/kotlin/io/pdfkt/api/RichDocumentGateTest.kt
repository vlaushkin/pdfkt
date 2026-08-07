/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.api

import io.pdfkt.core.block.PageBreakBlock
import io.pdfkt.core.doc.Metadata
import io.pdfkt.core.style.Theme
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// ISO 32000-2:2020 §7.7.3 "Page tree", §8.5 "Path construction and painting",
// §9.4 "Text objects" — the structure the pages are read back out of. What is
// laid out inside them is this library's own design.

/**
 * The gate of the rich text facade: one document holding every kind of block the
 * DSL builds, written the way a caller writes it — `document { }` and
 * `toByteArray()` — and read back by three tools that never saw this library.
 *
 * These are the behaviours a flow document is expected to keep:
 * all block types render in DSL order and `pdftotext`
 * preserves it, a page renders as N horizontal bands, one per line, and a long
 * table spans pages and repeats its header. The engine has tests of its own for
 * each of them — this asks the different question, which is whether a caller who
 * only ever writes the DSL gets them. Nothing here builds a `DocumentSpec`,
 * resolves or fragments: the facade's claim is that it does not have to.
 *
 * Three questions only a whole document asks:
 *
 * - **Does the file say what the caller wrote, in that order?** `pdftotext`
 *   walks the marks, not the plan, so a block placed twice or lost at a page
 *   boundary shows up here.
 * - **Is anything drawn on top of anything else?** No structural tool can
 *   answer it: a document whose every line sits on one baseline extracts exactly
 *   like a document that is laid out. A rendered page can, and the count it is
 *   held against is the extractor's own count of the lines on that page.
 * - **Does the table carry its header onto every page it reaches?** Only an
 *   extractor shows the header is on the page a reader turns to.
 */
class RichDocumentGateTest {

    @Test
    @DisplayName("the document spans pages and reads back in the order it was written")
    fun theDocumentReadsBackInTheOrderItWasWritten() {
        if (skipWithoutQpdf("§7.7.3")) return
        if (skipWithoutPdftotext("§9.4")) return

        withRendered(mixedDocument()) { path ->
            assertQpdfCheckIsClean(path)
            val pages = extractedPages(path)
            assertEquals(pages.size, pageCount(path), "pdftotext and qpdf disagree about how many pages there are")
            assertTrue(pages.size >= LEAST_PAGES, "the document came out on ${pages.size} pages")

            // The repeated header is left out of the sequence and asserted
            // separately: it is the one text in the document a reader is meant to
            // see more than once, and how many times depends on how many pages
            // the table reaches.
            val written = pages.flatMap { page -> wordsOf(page) }.filterNot { word -> word in HEADER_CELLS }
            assertContentEquals(
                expectedWords(),
                written,
                "the file does not read back as the document was written",
            )
        }
    }

    /**
     * Every page the table spans begins its part of the table with the header
     * row — `repeatHeader`, which the DSL's `table { }` has on by default, as a
     * reader sees it.
     */
    @Test
    @DisplayName("a table longer than a page repeats its header above the rows on every page")
    fun theTableCarriesItsHeaderOntoEveryPageItSpans() {
        if (skipWithoutQpdf("§7.7.3")) return
        if (skipWithoutPdftotext("§9.4")) return

        withRendered(mixedDocument()) { path ->
            val spanned = extractedPages(path).map { page -> wordsOf(page) }.filter { words -> carriesTable(words) }
            assertTrue(spanned.size >= 2, "the table of $TABLE_ROWS rows reached ${spanned.size} page")

            for ((index, words) in spanned.withIndex()) {
                for (cell in HEADER_CELLS) {
                    assertEquals(
                        1,
                        words.count { word -> word == cell },
                        "page ${index + 1} of the table carries \"$cell\" a different number of times",
                    )
                }
                val header = words.indexOf(HEADER_CELLS.last())
                val firstBodyCell = words.indexOfFirst { word -> word.startsWith(FIRST_COLUMN) }
                assertTrue(
                    header in 0 until firstBodyCell,
                    "page ${index + 1} of the table has its header at $header and its first body cell at " +
                        "$firstBodyCell: the header is not above the rows it heads",
                )
            }
        }
    }

    /**
     * A rendered page of running text shows one horizontal band of ink per line
     * printed on it — the check that catches blocks drawn on top of each other,
     * which every structural tool is blind to.
     *
     * The number of lines is not this test's own arithmetic: it is what the
     * extractor reports for that page, asked to keep the arrangement of the page,
     * so a bullet and the line it marks count as the one line they are drawn as.
     * The rule counts too, and it is counted separately because it carries no
     * text — a band with nothing to extract is exactly what a rule should leave.
     *
     * Pages carrying part of the table are left out, and that is a limit of the
     * instrument rather than a softened assertion: a table's vertical rules put
     * ink in every row of the raster between its top and its bottom, so its rows
     * are one band whatever the layout did. Those pages are what the two
     * extraction tests above are for.
     */
    @Test
    @DisplayName("every page of running text shows one band of ink per line printed on it")
    fun everyPageOfRunningTextShowsOneBandOfInkPerLine() {
        if (skipWithoutPdftotext("§9.4")) return
        if (skipWithoutPdftoppm("§8.5")) return

        withRendered(mixedDocument()) { path ->
            val flowing = extractedPages(path).withIndex().filterNot { (_, page) -> carriesTable(wordsOf(page)) }
            assertTrue(
                flowing.size >= 2,
                "only ${flowing.size} page of the document is running text: the band check wants the pages " +
                    "before the table, which are the heading, the rule, the paragraphs and the list",
            )
            assertTrue(
                flowing.any { (_, page) -> wordsOf(page).contains(BULLET) },
                "no page of running text carries the list, so nothing here shows that a marker and the line " +
                    "it marks are the one band they are drawn as",
            )

            for ((index, page) in flowing) {
                // The rule is the second block of the document, so the page it is
                // drawn on is the first one. It is added rather than extracted
                // because a rule is a path and not text: pdftotext reports
                // nothing for it, and a band with nothing to extract is exactly
                // what it should leave.
                val rules = if (index == 0) 1 else 0
                val printed = linesOf(page).size + rules
                val bands = rasterisePage(path, index + 1).inkBands()
                assertEquals(
                    printed,
                    bands.size,
                    "page ${index + 1} was printed with ${linesOf(page).size} lines of text and $rules rule " +
                        "and rendered as ${bands.size} bands of ink: something is drawn on top of something " +
                        "else",
                )
            }
        }
    }

    /**
     * The band check with a broken document put through it: the same words, the
     * same builders, and a line height that draws the lines through each other.
     *
     * A check that cannot fail proves nothing about the check that passed. The
     * two documents differ in one value of the theme, so what the raster reports
     * differently is the collision and not the text — and the count it is held
     * against comes from the *upright* rendering, since an extractor asked about
     * lines drawn on top of each other has no more idea than the raster does.
     */
    @Test
    @DisplayName("the band check reports fewer bands when the lines are drawn through each other")
    fun theBandCheckReportsFewerBandsWhenTheLinesAreDrawnThroughEachOther() {
        if (skipWithoutPdftotext("§9.4")) return
        if (skipWithoutPdftoppm("§8.5")) return

        var upright = 0
        withRendered(oneParagraph(Theme.Default)) { path ->
            upright = rasterisePage(path, 1).inkBands().size
            assertEquals(
                linesOf(extractedPages(path).single()).size,
                upright,
                "the paragraph that the collision below is measured against does not itself come out " +
                    "one band per line",
            )
        }
        assertTrue(upright >= LEAST_LINES, "the paragraph came out on $upright line: it does not wrap")

        withRendered(oneParagraph(Theme.Default.copy(lineHeight = COLLIDING_LINE_HEIGHT))) { path ->
            val collided = rasterisePage(path, 1).inkBands().size
            assertTrue(
                collided < upright,
                "the same paragraph set at a line height of $COLLIDING_LINE_HEIGHT still rendered as " +
                    "$collided bands against the $upright of the upright one: the band check cannot see a " +
                    "collision",
            )
        }
    }

    // ------------------------------------------------------------- the document

    /**
     * The document under test, written the way the DSL is meant to be written: a
     * heading, a rule, running text, a second heading, a bulleted list, and a
     * table of [TABLE_ROWS] rows, which is more than a page of them.
     */
    private fun mixedDocument(): Document = document {
        clock = PINNED_CLOCK
        metadata = Metadata(title = TITLE, author = "pdfkt")

        heading(level = 1, TITLE)
        // Under the title, on a page of nothing but running text: the rule is
        // what the band check needs in order to show that a block which is not
        // text keeps a band of its own.
        hr()
        for (number in 1..PARAGRAPHS) paragraph(paragraphText(number))
        heading(level = 2, FINDINGS)
        bulletList {
            for (number in 1..LIST_ITEMS) item(itemText(number))
        }
        // The table starts a section of its own, which is also what makes the
        // page the list is on a page the raster can be asked about: a table puts
        // ink in every row of the raster between its top and its bottom, so a
        // list sharing its page could not be counted in bands. The break is a
        // block like any other and goes in through the door a converter uses.
        add(PageBreakBlock)
        heading(level = 2, MEASUREMENTS)
        table {
            header {
                for (title in HEADER_CELLS) cell(title)
            }
            for (number in 1..TABLE_ROWS) {
                row {
                    for (text in rowCells(number)) cell(text)
                }
            }
        }
    }

    /** One paragraph long enough to wrap, which is what the collision check needs. */
    private fun oneParagraph(styling: Theme): Document = document {
        clock = PINNED_CLOCK
        theme = styling
        paragraph(paragraphText(1))
    }

    /** Every word of [mixedDocument], in the order the caller wrote it, without the repeated header. */
    private fun expectedWords(): List<String> = buildList {
        addAll(TITLE.split(" "))
        for (number in 1..PARAGRAPHS) addAll(paragraphText(number).split(" "))
        add(FINDINGS)
        for (number in 1..LIST_ITEMS) {
            add(BULLET)
            addAll(itemText(number).split(" "))
        }
        add(MEASUREMENTS)
        for (number in 1..TABLE_ROWS) addAll(rowCells(number))
    }

    /** A paragraph of words that occur nowhere else, long enough to break into several lines. */
    private fun paragraphText(number: Int): String =
        (1..WORDS_PER_PARAGRAPH).joinToString(separator = " ") { word -> "para${number}word$word" }

    private fun itemText(number: Int): String = "Finding $number of the list"

    private fun rowCells(number: Int): List<String> = listOf("$FIRST_COLUMN$number", "beta$number")

    /** Whether a page carries part of the table — the header cells or a body row. */
    private fun carriesTable(words: List<String>): Boolean =
        words.any { word -> word in HEADER_CELLS || word.startsWith(FIRST_COLUMN) }

    private companion object {
        const val TITLE: String = "A mixed document across pages"
        const val FINDINGS: String = "Findings"
        const val MEASUREMENTS: String = "Measurements"

        /**
         * Enough running text for two whole pages of it before the list, so that
         * the band check has more than one page to count and the second of them
         * begins mid-flow rather than at a block boundary.
         */
        const val PARAGRAPHS: Int = 26
        const val WORDS_PER_PARAGRAPH: Int = 28
        const val LIST_ITEMS: Int = 6
        const val TABLE_ROWS: Int = 80

        /** The running text, the list and the table, none of which shares a page with all the others. */
        const val LEAST_PAGES: Int = 4

        /** The header of the table, which is the text that repeats. */
        val HEADER_CELLS: List<String> = listOf("Clause", "Reading")

        /** What the first column of a body row starts with, which no other word does. */
        const val FIRST_COLUMN: String = "alpha"

        /** What an unordered list is marked with (`ListMarkerStyle.Bullet`). */
        const val BULLET: String = "•"

        /**
         * A line height that sets consecutive baselines closer together than the
         * glyphs are tall, so the lines are drawn through each other. It is a
         * theme value and so reachable from the DSL, which is what makes the
         * broken document a document a caller could write rather than a plan
         * edited behind the facade's back.
         */
        const val COLLIDING_LINE_HEIGHT: Float = 0.25f

        /** Below this the paragraph is one line and there is nothing to collide with. */
        const val LEAST_LINES: Int = 3
    }
}
