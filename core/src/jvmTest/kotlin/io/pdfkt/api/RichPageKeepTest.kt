/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.api

import io.pdfkt.core.block.CodeBlock
import io.pdfkt.core.block.HeadingBlock
import io.pdfkt.core.block.TextRun
import io.pdfkt.core.model.Pt
import io.pdfkt.core.model.pt
import io.pdfkt.core.style.Theme
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// ISO 32000-2:2020 §7.7.3 "Page tree", §9.4 "Text objects" — the structure the
// pages are read back out of. The keep rules themselves are this library's own
// design.

/**
 * Widow and orphan control as a caller of the DSL gets it: `heading(level, text)`
 * is never printed alone at the foot of a page, and a code block the DSL was
 * asked to keep together is never split.
 *
 * The document is rendered at every position of a sweep that walks it past a
 * page boundary one filler line at a time, and every sweep is run **twice** —
 * once with the flags the builder sets and once with the same blocks built
 * without them. The unkept run is not decoration: it is the proof that the sweep
 * reaches the positions where the flags have something to do. A sweep that never
 * crossed a boundary would let the kept run pass while doing nothing at all, so
 * the unkept run failing to orphan anything fails the test.
 *
 * The unkept run goes through `add(block)`, which is the DSL's own door for a
 * block a converter built — a Markdown walker, say, that has its own opinion
 * about keeps. So both runs are documents a caller can write, and what differs
 * between them is two booleans.
 */
class RichPageKeepTest {

    /**
     * A heading is never printed alone at the foot of a page: it moves to the
     * page its body text starts on. Both keep flags of `HeadingBlock` default to
     * true, and `heading(level, text)` is how a caller reaches them.
     */
    @Test
    @DisplayName("a heading written through the DSL is printed on the page of the text it introduces")
    fun aHeadingAndTheBodyItIntroducesArePrintedOnOnePage() {
        if (skipWithoutQpdf("§7.7.3")) return
        if (skipWithoutPdftotext("§9.4")) return

        val unkept = sweep { fillers -> headingDocument(fillers, keeps = false) }
        val kept = sweep { fillers -> headingDocument(fillers, keeps = true) }

        assertTrue(
            unkept.any { (_, pages) -> pageOf(pages, HEADING) != pageOf(pages, BODY) },
            "with the keep flags off no filler count left the heading and its body on different pages, so " +
                "the sweep never reached a page boundary and the kept run below proves " +
                "nothing:\n${describe(unkept, HEADING)}",
        )

        for ((fillers, pages) in kept) {
            assertEquals(
                pageOf(pages, BODY),
                pageOf(pages, HEADING),
                "with $fillers fillers the heading is printed alone at the foot of its page:\n${describe(pages)}",
            )
        }

        assertTrue(
            kept.map { (_, pages) -> pageOf(pages, HEADING) }.toSet().size > 1,
            "the kept sweep printed the heading on page 1 every time, so no boundary was crossed",
        )
    }

    /** A code block kept together and short enough to fit a page is printed on one page, every line of it. */
    @Test
    @DisplayName("a code block kept together is printed whole on one page")
    fun aKeptCodeBlockIsPrintedOnASinglePage() {
        if (skipWithoutQpdf("§7.7.3")) return
        if (skipWithoutPdftotext("§9.4")) return

        val unkept = sweep { fillers -> codeDocument(fillers, keepTogether = false) }
        val kept = sweep { fillers -> codeDocument(fillers, keepTogether = true) }

        assertTrue(
            unkept.any { (_, pages) -> pageOf(pages, codeLine(1)) != pageOf(pages, codeLine(CODE_LINES)) },
            "with keepTogether off no filler count broke the code block across pages, so the sweep never " +
                "reached a page boundary and the kept run below proves " +
                "nothing:\n${describe(unkept, codeLine(1))}",
        )

        for ((fillers, pages) in kept) {
            val first = pageOf(pages, codeLine(1))
            for (line in 1..CODE_LINES) {
                assertEquals(
                    first,
                    pageOf(pages, codeLine(line)),
                    "with $fillers fillers line $line of the kept code block left its block's " +
                        "page:\n${describe(pages)}",
                )
            }
        }

        assertTrue(
            kept.map { (_, pages) -> pageOf(pages, codeLine(1)) }.toSet().size > 1,
            "the kept sweep printed the code block on page 1 every time, so no boundary was crossed",
        )
    }

    // ------------------------------------------------------------- documents

    /** [fillers] one-line paragraphs, then a heading and the body text it introduces. */
    private fun DocumentScope.headingDocument(fillers: Int, keeps: Boolean) {
        fillerParagraphs(fillers)
        if (keeps) {
            heading(level = HEADING_LEVEL, HEADING)
        } else {
            add(
                HeadingBlock(
                    level = HEADING_LEVEL,
                    content = listOf(TextRun(HEADING)),
                    keepWithNext = false,
                    keepTogether = false,
                ),
            )
        }
        paragraph(BODY)
    }

    /** [fillers] one-line paragraphs, then a code block of [CODE_LINES] lines. */
    private fun DocumentScope.codeDocument(fillers: Int, keepTogether: Boolean) {
        fillerParagraphs(fillers)
        val text = (1..CODE_LINES).joinToString(separator = "\n") { line -> codeLine(line) }
        if (keepTogether) add(CodeBlock(text = text, keepTogether = true)) else codeBlock(text)
        paragraph(BODY)
    }

    private fun DocumentScope.fillerParagraphs(fillers: Int) {
        for (number in 1..fillers) paragraph(filler(number))
    }

    // ------------------------------------------------------------- the sweep

    /**
     * Every step of the sweep rendered to its own file and read back as pages.
     *
     * The first file of every sweep is checked with `qpdf --check`: the flags
     * change where the blocks go and nothing about how the file is written, so
     * one structural check per sweep says that what pdftotext is reading is a PDF
     * and not a coincidence.
     */
    private fun sweep(content: DocumentScope.(Int) -> Unit): List<Pair<Int, List<String>>> = SWEEP.map { fillers ->
        // The blocks are written inside the braces and not after them: a
        // `document { }` hands back a finished document, and the paper it is
        // set on is stated in the same block as the text.
        val written = document {
            clock = PINNED_CLOCK
            pageSize = PAGE
            margins = Margins.Fixed(MARGIN, MARGIN, MARGIN, MARGIN)
            theme = THEME
            content(fillers)
        }

        var pages: List<String> = emptyList()
        withRendered(written) { path ->
            if (fillers == SWEEP.first) assertQpdfCheckIsClean(path)
            pages = extractedPages(path, layout = false)
        }
        fillers to pages
    }

    /** Which page [needle] was printed on; a needle that reached no page at all fails loudly. */
    private fun pageOf(pages: List<String>, needle: String): Int {
        val index = pages.indexOfFirst { page -> page.contains(needle) }
        if (index < 0) throw AssertionError("'$needle' is in no page of the file:\n${describe(pages)}")
        return index
    }

    private fun describe(pages: List<String>): String =
        pages.withIndex().joinToString(separator = "\n") { (index, page) ->
            "---- page ${index + 1} ----\n${page.trim()}"
        }

    /** One line per step of a sweep naming the page [needle] was printed on. */
    private fun describe(sweep: List<Pair<Int, List<String>>>, needle: String): String =
        sweep.joinToString(separator = "\n") { (fillers, pages) ->
            "$fillers fillers -> '$needle' on page ${pageOf(pages, needle) + 1} of ${pages.size}"
        }

    private companion object {
        /**
         * A small page keeps the sweep cheap: every step of it is a whole PDF
         * written and read back, and what is being exercised is a page boundary,
         * which a short page reaches as well as a long one.
         */
        val PAGE: PageSize = PageSize.Custom(320.pt, 240.pt)

        val MARGIN: Pt = 24.pt

        /**
         * Filler counts rendered. Body text at 9pt with no space between
         * paragraphs advances the flow by about eight points a filler, so the
         * 192pt column here holds a little over twenty of them and a sweep this
         * long walks the block under test through more than one page's worth of
         * positions — which is what makes it certain to pass through the band
         * where the block fits the page and its continuation does not.
         */
        val SWEEP: IntRange = 1..26

        /**
         * 9pt body text with nothing between the paragraphs: one filler is one
         * line box, so the sweep steps past the boundary finely enough to land
         * inside that band rather than stepping over it.
         */
        val THEME: Theme = Theme.Default.copy(bodyFontSize = 9.pt, paragraphSpacingAfter = Pt.ZERO)

        const val HEADING_LEVEL: Int = 2

        const val HEADING: String = "Section Marker Heading"

        const val BODY: String = "Body sentence bound to the block above it."

        const val CODE_LINES: Int = 6

        fun codeLine(index: Int): String = "codeLine$index()"

        fun filler(index: Int): String = "Filler line $index holds the flow."
    }
}
