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
import io.pdfkt.core.block.ParagraphBlock
import io.pdfkt.core.block.TextRun
import io.pdfkt.core.model.Insets
import io.pdfkt.core.model.Pt
import io.pdfkt.core.model.Size
import io.pdfkt.core.model.pt
import io.pdfkt.core.obj.PdfDate
import io.pdfkt.core.obj.assertQpdfCheckIsClean
import io.pdfkt.core.obj.extractedText
import io.pdfkt.core.obj.qpdf
import io.pdfkt.core.obj.skipWithoutPdftotext
import io.pdfkt.core.obj.skipWithoutQpdf
import io.pdfkt.core.style.Theme
import kotlinx.io.asSink
import kotlinx.io.buffered
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// No clause applies: band layout is this library's own design.

/**
 * The band gate: a document of three pages, each of which carries its
 * running header and a footer saying which page of how many it is — read back by
 * a tool that never saw this library.
 *
 * `BandPlanTest` asks the plan where the bands are; this asks the file whether
 * they came out. The two are different questions and both are needed: a band
 * planned into the right rectangle and then dropped by the emitter passes the
 * first and fails this one, and pdftotext is asked to keep the arrangement of
 * the page so that "the footer is at the foot" is answered from where the marks
 * actually are rather than from the order they were written in.
 */
class BandRenderTest {
    /**
     * Every page carries "Page N of 3", and carries it below the text.
     *
     * The count is the acceptance of the band design as much as the number is:
     * "of 3" can only be written once the document has been paginated, and the
     * pagination is only fixed because the strip the footer goes in was reserved
     * before the fragmenter ever saw the page.
     */
    @Test
    fun `every page carries its number and the count of the pages`() {
        if (skipWithoutQpdf("band scheduling")) return
        if (skipWithoutPdftotext("band scheduling")) return

        withRendered { path ->
            assertQpdfCheckIsClean(path)
            assertEquals(PAGES, pageCount(path), "the gate is a document of $PAGES pages")

            val pages = pagesOf(path)
            assertEquals(PAGES, pages.size, "pdftotext read a document of ${pages.size} pages")
            for ((index, lines) in pages.withIndex()) {
                val number = index + 1
                assertEquals(HEADER, lines.first(), "page $number does not begin with the running header")
                assertEquals("Page $number of $PAGES", lines.last(), "page $number does not end with its footer")
            }
        }
    }

    /**
     * The bands are drawn beside the document, not instead of part of it: every
     * word of the content comes back, in the order it was written, with the
     * bands' words in neither the middle of it nor missing from the page.
     *
     * A band that ate a line of text — by being reserved and then drawn over —
     * would still put "Page 2 of 3" on page 2 and pass the check above.
     */
    @Test
    fun `the bands cost the document none of its text`() {
        if (skipWithoutPdftotext("band scheduling")) return

        withRendered { path ->
            val read = pagesOf(path)
                .flatMap { lines -> lines.drop(1).dropLast(1) }
                .flatMap { line -> line.split(WHITE_SPACE) }
                .filter { word -> word.isNotEmpty() }
            assertEquals(expectedWords(), read, "the document between its bands is not what was written")
        }
    }

    /**
     * A band drawn on some pages is still reserved on all of them: the same
     * document with a cover-page-only header paginates exactly as the one whose
     * header runs throughout.
     *
     * The band design fixes this on purpose — the reserve does not depend on
     * which pages the band is drawn on — and the check is what the decision is
     * worth: a reserve that followed the rule would reflow the text around where
     * the header happens to appear, and the two files would differ by more than
     * two lines of ink.
     */
    @Test
    fun `a band drawn on one page is reserved on every page`() {
        if (skipWithoutPdftotext("band scheduling")) return

        withRendered(headerPages = BandPages.FirstOnly) { path ->
            val pages = pagesOf(path)
            assertEquals(PAGES, pages.size, "the reserve changed the pagination: ${pages.size} pages")
            assertEquals(HEADER, pages.first().first(), "the cover lost the header it is the only page to have")
            for (page in pages.drop(1)) {
                assertTrue(HEADER !in page, "a page that the header rule leaves out carries it anyway: $page")
            }
            assertEquals(
                pages.map { lines -> lines.last() },
                (1..PAGES).map { number -> "Page $number of $PAGES" },
                "the footer does not run through a document whose header does not",
            )
        }
    }

    // ---------------------------------------------------------------- the file

    private fun document(): List<Block> = buildList {
        add(HeadingBlock(level = 1, content = listOf(TextRun(TITLE))))
        for (number in 1..PARAGRAPHS) add(ParagraphBlock(listOf(TextRun(paragraphText(number)))))
    }

    /** Every word of [document], in the order the caller wrote it. */
    private fun expectedWords(): List<String> = buildList {
        addAll(TITLE.split(" "))
        for (number in 1..PARAGRAPHS) addAll(paragraphText(number).split(" "))
    }

    private fun paragraphText(number: Int): String =
        (1..WORDS_PER_PARAGRAPH).joinToString(separator = " ") { word -> "para${number}word$word" }

    /** The document rendered into a file that lives for the duration of [block]. */
    private fun withRendered(headerPages: BandPages = BandPages.All, block: (Path) -> Unit) {
        val spec =
            DocumentSpec(
                pageSize = Size.A4,
                margins = Insets.uniform(MARGIN),
                header = BandSpec(height = BAND_HEIGHT, pages = headerPages) { listOf(paragraph(HEADER)) },
                footer = BandSpec(height = BAND_HEIGHT) { numbering ->
                    listOf(paragraph("Page ${numbering.page} of ${numbering.pages}"))
                },
                content = document(),
                metadata = Metadata(title = TITLE),
                clock = PINNED,
            )
        val path = Files.createTempFile("pdfkt-bands", ".pdf")
        try {
            Files.newOutputStream(path).asSink().buffered().use { sink -> render(spec, Theme.Default, sink) }
            block(path)
        } finally {
            path.deleteIfExists()
        }
    }

    private fun paragraph(text: String): Block = ParagraphBlock(listOf(TextRun(text)))

    /**
     * The lines of the file, page by page, as pdftotext lays them out — the pages
     * separated by the form feed the tool writes between them, and the lines in
     * the order the marks are on the paper.
     */
    private fun pagesOf(path: Path): List<List<String>> = extractedText(path, layout = true)
        .split(FORM_FEED)
        .map { page -> page.lines().map { line -> line.trim() }.filter { line -> line.isNotEmpty() } }
        .filter { page -> page.isNotEmpty() }

    private fun pageCount(path: Path): Int {
        val run = qpdf("--show-npages", path.toString())
        assertEquals(0, run.status, "qpdf could not count the pages:\n${run.output}")
        return run.output.trim().toInt()
    }

    private companion object {
        const val TITLE: String = "Quarterly report"
        const val HEADER: String = "Confidential draft"

        /** The gate's document: three pages of running text under one heading. */
        const val PAGES: Int = 3
        const val PARAGRAPHS: Int = 30
        const val WORDS_PER_PARAGRAPH: Int = 24

        val MARGIN: Pt = 56.pt
        val BAND_HEIGHT: Pt = 24.pt

        val PINNED: Clock = Clock { PdfDate(2026, 8, 5, 12, 0, 0, offsetMinutes = 0) }

        /** What pdftotext puts between the pages it read. */
        const val FORM_FEED: String = "\u000C"
        val WHITE_SPACE: Regex = Regex("\\s+")
    }
}
