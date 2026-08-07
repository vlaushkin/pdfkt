/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.doc

import io.pdfkt.core.block.Block
import io.pdfkt.core.block.HeadingBlock
import io.pdfkt.core.block.HorizontalRuleBlock
import io.pdfkt.core.block.ListBlock
import io.pdfkt.core.block.ListItem
import io.pdfkt.core.block.PageBreakBlock
import io.pdfkt.core.block.ParagraphBlock
import io.pdfkt.core.block.TableBlock
import io.pdfkt.core.block.TableCell
import io.pdfkt.core.block.TableColumns
import io.pdfkt.core.block.TextRun
import io.pdfkt.core.emit.PlanEmitter
import io.pdfkt.core.font.FontCatalog
import io.pdfkt.core.fragment.Fragmenter
import io.pdfkt.core.fragment.PageTemplate
import io.pdfkt.core.model.Insets
import io.pdfkt.core.model.Pt
import io.pdfkt.core.model.Size
import io.pdfkt.core.model.pt
import io.pdfkt.core.obj.assertQpdfCheckIsClean
import io.pdfkt.core.obj.extractedText
import io.pdfkt.core.obj.qpdf
import io.pdfkt.core.obj.rasterisePage
import io.pdfkt.core.obj.skipWithoutPdftoppm
import io.pdfkt.core.obj.skipWithoutPdftotext
import io.pdfkt.core.obj.skipWithoutQpdf
import io.pdfkt.core.plan.PagePlan
import io.pdfkt.core.plan.PlacedItem
import io.pdfkt.core.resolve.Resolver
import io.pdfkt.core.style.Theme
import kotlinx.io.asSink
import kotlinx.io.buffered
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// ISO 32000-2:2020 §7.7.3 "Page tree", §8.5 "Path construction and painting",
// §9.4 "Text objects"

/**
 * The mixed-content gate: one document holding a heading, running text, a list, a
 * rule and a table long enough to span pages, taken through every stage the
 * engine has — resolve, fragment, emit, write — and then read back by three
 * tools that never saw this library.
 *
 * Each stage has tests of its own, and each of those is blind to a failure the
 * others could produce. What this adds is the three questions only a whole
 * document asks:
 *
 * - **Does the file say what the caller wrote, in that order?** `pdftotext`
 *   walks the marks, not the plan, so a block that was placed twice or lost at a
 *   page boundary shows up here and nowhere else.
 * - **Is anything drawn on top of anything else?** No structural tool can
 *   answer it: a document whose every line sits on one baseline extracts exactly
 *   like a document that is laid out. A rendered page can — text on separate
 *   lines leaves separate horizontal bands of ink, text drawn over itself leaves
 *   one — so every page of running text is rasterised and its bands are counted
 *   against the lines the plan says are on it.
 * - **Does the table carry its header onto every page it reaches?** The plan
 *   tests count `PlacedItem`s; only an extractor shows the header is on the
 *   page a reader turns to.
 *
 * qpdf, pdftotext and pdftoppm are used as *tools*; their output is compared,
 * never their source.
 */
class MixedDocumentTest {
    /**
     * The document arrives whole and in order: every word the caller wrote comes
     * back exactly once, in the order it was written, across three pages or
     * more.
     */
    @Test
    fun `a mixed document spans three pages and extracts in the order it was written`() {
        if (skipWithoutQpdf("§7.7.3")) return
        if (skipWithoutPdftotext("§9.4")) return

        withRendered { path, pages ->
            assertQpdfCheckIsClean(path)
            assertTrue(pages.size >= THREE_PAGES, "the document was planned onto ${pages.size} pages")
            assertEquals(pages.size, pageCount(path), "the file has a different number of pages from the plan")

            // The repeated header is left out of the sequence and asserted
            // separately: it is the one text in the document that a reader is
            // meant to see more than once, and how many times depends on how
            // many pages the table reaches.
            val written = wordsOf(path).filterNot { word -> word in HEADER_CELLS }
            assertContentEquals(
                expectedWords(),
                written,
                "the file does not read back as the document was written",
            )
        }
    }

    /**
     * Every page the table spans begins its part of the table with the header
     * row — the acceptance of "the repeated header rows are part of every
     * `head`" as a reader sees it.
     */
    @Test
    fun `the table carries its header onto every page it spans`() {
        if (skipWithoutQpdf("§7.7.3")) return
        if (skipWithoutPdftotext("§9.4")) return

        withRendered { path, _ ->
            val spanned = pagesOf(path).filter { page -> page.any { word -> word.startsWith(FIRST_COLUMN) } }
            assertTrue(spanned.size >= 2, "the table of $TABLE_ROWS rows reached ${spanned.size} page")
            for ((index, page) in spanned.withIndex()) {
                for (cell in HEADER_CELLS) {
                    assertEquals(
                        1,
                        page.count { word -> word == cell },
                        "page ${index + 1} of the table carries \"$cell\" a different number of times",
                    )
                }
                val header = page.indexOf(HEADER_CELLS.last())
                val firstBodyCell = page.indexOfFirst { word -> word.startsWith(FIRST_COLUMN) }
                assertTrue(
                    header in 0 until firstBodyCell,
                    "page ${index + 1} of the table has its header at $header and its first body cell " +
                        "at $firstBodyCell: the header is not above the rows it heads",
                )
            }
        }
    }

    /**
     * A rendered page of running text shows one horizontal band of ink per line
     * the plan put on it — the check that catches blocks drawn on top of each
     * other, which every structural tool is blind to.
     *
     * The expected count is the number of *things drawn*, not their positions:
     * lines sharing a baseline (a bullet and the first line of its item) are one
     * band, and a rule is a band of its own. So a fragmenter that stacked two
     * paragraphs at the same height would still be asked for as many bands as it
     * had lines, and the raster would come back with fewer.
     *
     * Pages carrying part of the table are left out, and that is a limit of the
     * instrument rather than a softened assertion: a table's vertical rules put
     * ink in every row of the raster between its top and its bottom, so its rows
     * are one band whatever the layout did. Those pages are what the extraction
     * tests above are for.
     */
    @Test
    fun `every page of running text shows one band of ink per line`() {
        if (skipWithoutQpdf("§7.7.3")) return
        if (skipWithoutPdftoppm("§8.5")) return

        withRendered { path, pages ->
            val flowing = pages.withIndex().filterNot { (_, page) -> hasTableRules(page) }
            assertTrue(
                flowing.size >= 2,
                "only ${flowing.size} page of the document is running text: the band check wants the " +
                    "pages before the table, which are the heading, the rule, the paragraphs and the list",
            )
            assertTrue(
                flowing.any { (_, page) -> page.items.any { item -> item is PlacedItem.Rect } },
                "no page of running text carries the horizontal rule, so nothing shows a rule is drawn " +
                    "clear of the lines around it",
            )

            for ((index, page) in flowing) {
                val bands = rasterisePage(path, index + 1).inkBands()
                assertEquals(
                    drawnRows(page),
                    bands.size,
                    "page ${index + 1} was planned with ${drawnRows(page)} rows of marks and rendered " +
                        "with ${bands.size} bands of ink: something is drawn on top of something else",
                )
            }
        }
    }

    /**
     * The band check with a broken page put through it: every second line of a
     * page of running text pulled up onto the one above, and the raster reports
     * fewer bands than the page has rows.
     *
     * A check that cannot fail proves nothing about the check that passed. This
     * is what makes the assertion above a measurement rather than a formality —
     * it is the same page, the same renderer and the same counting, differing
     * only in that the lines were moved on top of each other.
     */
    @Test
    fun `the band check reports fewer bands when lines are drawn on top of each other`() {
        if (skipWithoutPdftoppm("§8.5")) return

        val fonts = FontCatalog()
        val pages = plan(fonts)
        val flowing = pages.first { page -> !hasTableRules(page) }
        val collided = PagePlan(
            flowing.size,
            flowing.items.mapIndexed { index, item ->
                if (item is PlacedItem.Text && index % 2 == 1) item.copy(y = item.y + COLLISION) else item
            },
        )

        withFile(fonts, listOf(collided)) { path ->
            val bands = rasterisePage(path, 1).inkBands().size
            assertTrue(
                bands < drawnRows(collided),
                "the page was broken on purpose and still rendered $bands bands for its " +
                    "${drawnRows(collided)} rows: the band check cannot see a collision",
            )
        }
    }

    // ------------------------------------------------------------- the document

    /**
     * The document under test: a heading, running text, a rule, a list and a
     * table of [TABLE_ROWS] rows, which is more than a page of them.
     */
    private fun document(): List<Block> = buildList {
        add(HeadingBlock(level = 1, content = listOf(TextRun(TITLE))))
        // Under the title, where it is drawn on a page of nothing but running
        // text: the rule is what the band check needs in order to show that a
        // block which is not text keeps a band of its own.
        add(HorizontalRuleBlock())
        for (number in 1..PARAGRAPHS) add(ParagraphBlock(listOf(TextRun(paragraphText(number)))))
        add(HeadingBlock(level = 2, content = listOf(TextRun(FINDINGS))))
        add(
            ListBlock(
                items = (1..LIST_ITEMS).map { number ->
                    ListItem(listOf(ParagraphBlock(listOf(TextRun(itemText(number))))))
                },
            ),
        )
        // The table starts a section of its own, which is also what makes the
        // page the list is on a page the raster can be asked about: a table puts
        // ink in every row of the raster between its top and its bottom.
        add(PageBreakBlock)
        add(HeadingBlock(level = 2, content = listOf(TextRun(MEASUREMENTS))))
        add(
            TableBlock(
                columns = TableColumns.Equal(2),
                header = HEADER_CELLS.map { text -> TableCell(listOf(ParagraphBlock(listOf(TextRun(text))))) },
                rows = (1..TABLE_ROWS).map { number ->
                    rowCells(number).map { text -> TableCell(listOf(ParagraphBlock(listOf(TextRun(text))))) }
                },
            ),
        )
    }

    /** Every word of [document], in the order the caller wrote it, without the repeated header. */
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

    /** A paragraph of unique words, long enough to break into several lines. */
    private fun paragraphText(number: Int): String =
        (1..WORDS_PER_PARAGRAPH).joinToString(separator = " ") { word -> "para${number}word$word" }

    private fun itemText(number: Int): String = "Finding $number of the list"

    private fun rowCells(number: Int): List<String> = listOf("$FIRST_COLUMN$number", "beta$number")

    // -------------------------------------------------------------- the checks

    /**
     * How many rows of marks a page was planned with: one per baseline the text
     * items share, plus one per rectangle.
     *
     * Two text items on one baseline — a bullet and the line beside it — are one
     * row, because that is what they are on the page. The count is of items and
     * their baselines only; where those baselines *are* is what the raster is
     * asked, and the two numbers agreeing is the claim.
     */
    private fun drawnRows(page: PagePlan): Int {
        val baselines = page.items.filterIsInstance<PlacedItem.Text>().map { text -> text.y }.distinct()
        return baselines.size + page.items.count { item -> item is PlacedItem.Rect }
    }

    /** Whether a page carries a table: a rule taller than it is wide is a column separator. */
    private fun hasTableRules(page: PagePlan): Boolean =
        page.items.any { item -> item is PlacedItem.Rect && item.box.height > item.box.width }

    /**
     * The words of the file, page by page — pdftotext separates pages with a
     * form feed.
     *
     * The tool is asked to keep the arrangement of the page, so what it reports
     * is read off the geometry: a bullet and the line it marks come back as the
     * one line they are drawn as, and a table's row comes back as its cells left
     * to right.
     */
    private fun pagesOf(path: Path): List<List<String>> = extractedText(path, layout = true)
        .split(FORM_FEED)
        .map { page -> page.split(WHITE_SPACE).filter { word -> word.isNotEmpty() } }
        .filter { page -> page.isNotEmpty() }

    private fun wordsOf(path: Path): List<String> = pagesOf(path).flatten()

    /** How many pages the file has, according to qpdf. */
    private fun pageCount(path: Path): Int {
        val run = qpdf("--show-npages", path.toString())
        assertEquals(0, run.status, "qpdf could not count the pages:\n${run.output}")
        return run.output.trim().toInt()
    }

    /**
     * The document resolved, fragmented, emitted and written to a file, which is
     * handed to [assertions] with the pages that were planned.
     *
     * One font catalog serves all three stages, which is the contract of all
     * three: a font measured from one catalog and written from another would be
     * two fonts.
     */
    private fun withRendered(assertions: (Path, List<PagePlan>) -> Unit) {
        val fonts = FontCatalog()
        val pages = plan(fonts)
        withFile(fonts, pages) { path -> assertions(path, pages) }
    }

    /** The document resolved and fragmented, measured through [fonts]. */
    private fun plan(fonts: FontCatalog): List<PagePlan> {
        val styled = Resolver(Theme.Default, fonts).resolve(document())
        return Fragmenter(fonts, PageTemplate.of(Size.A4, Insets.uniform(56.pt))).fragment(styled)
    }

    /** [pages] emitted through [fonts] into a file that lives for the duration of [block]. */
    private fun withFile(fonts: FontCatalog, pages: List<PagePlan>, block: (Path) -> Unit) {
        val path = Files.createTempFile("pdfkt-mixed", ".pdf")
        try {
            Files.newOutputStream(path).asSink().buffered().use { sink ->
                val emitter = PlanEmitter(PdfDocumentWriter(sink), fonts)
                for (page in pages) emitter.emit(page)
                emitter.finish(fileIdentifier = FILE_IDENTIFIER)
            }
            block(path)
        } finally {
            path.deleteIfExists()
        }
    }

    private companion object {
        const val TITLE: String = "A mixed document across pages"
        const val FINDINGS: String = "Findings"
        const val MEASUREMENTS: String = "Measurements"

        const val PARAGRAPHS: Int = 14
        const val WORDS_PER_PARAGRAPH: Int = 28
        const val LIST_ITEMS: Int = 6
        const val TABLE_ROWS: Int = 80
        const val THREE_PAGES: Int = 3

        /**
         * How far a line is pulled up onto the one above it to break a page on
         * purpose: half of what eleven-point text set 1.2 line heights apart
         * keeps between its baselines, which leaves the two lines overlapping
         * and still at baselines of their own.
         */
        val COLLISION: Pt = 6.pt

        /** The header of the table, which is the text that repeats. */
        val HEADER_CELLS: List<String> = listOf("Clause", "Reading")

        /** What the first column of a body row starts with, which no other word does. */
        const val FIRST_COLUMN: String = "alpha"

        /** What an unordered list is marked with (`ListMarkerStyle.Bullet`). */
        const val BULLET: String = "•"

        val WHITE_SPACE: Regex = Regex("\\s+")

        /** FORM FEED (0Ch) — what pdftotext puts between the pages of a file. */
        const val FORM_FEED: String = "\u000C"

        /** "Each PDF file identifier byte-string shall have a minimum length of 16 bytes" (§14.4). */
        val FILE_IDENTIFIER: ByteArray = "mixed-document-gate".encodeToByteArray()
    }
}
