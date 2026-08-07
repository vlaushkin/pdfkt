/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.fragment

import io.pdfkt.core.block.Block
import io.pdfkt.core.block.CodeBlock
import io.pdfkt.core.block.HeadingBlock
import io.pdfkt.core.block.ParagraphBlock
import io.pdfkt.core.block.TextRun
import io.pdfkt.core.doc.PdfDocumentWriter
import io.pdfkt.core.emit.PlanEmitter
import io.pdfkt.core.font.FontCatalog
import io.pdfkt.core.model.Insets
import io.pdfkt.core.model.Pt
import io.pdfkt.core.model.Size
import io.pdfkt.core.model.pt
import io.pdfkt.core.obj.assertQpdfCheckIsClean
import io.pdfkt.core.obj.extractedText
import io.pdfkt.core.obj.skipWithoutPdftotext
import io.pdfkt.core.obj.skipWithoutQpdf
import io.pdfkt.core.resolve.Resolver
import io.pdfkt.core.style.Theme
import kotlinx.io.asSink
import kotlinx.io.buffered
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// ISO 32000-2:2020 §7.7.3 "Page tree", §9.4 "Text objects" — the structure the
// pages are read back out of. The rules themselves are this library's own
// design.

/**
 * The two keep-together flags of the layout rules, through the whole pipeline
 * and back out of a file: which page a block landed on is what `pdftotext`
 * says, not what the plan claimed.
 *
 * `KeepFlagsTest` makes the same two claims about plans. This is the half that
 * cannot be argued with: a tool that never saw this library reports the page a
 * heading is printed on, and a heading printed alone at the foot of one is the
 * failure both flags exist to prevent.
 *
 * Each document is rendered at every position of a sweep that walks it past a
 * page boundary one filler line at a time, and every sweep is run **twice** —
 * once with the flags off and once with them on. The unkept run is not
 * decoration: it is the proof that the sweep reaches the positions where the
 * flags have something to do. A sweep that never crossed a boundary would let
 * the kept run pass while doing nothing at all, so the unkept run failing to
 * orphan anything fails the test.
 *
 * qpdf and pdftotext are used as tools; their output is compared, never their
 * source.
 */
class KeepFlagsRenderTest {
    /**
     * A heading is never printed alone at the foot of a page: it moves to the
     * page its body text starts on.
     */
    @Test
    fun `a heading and the body it introduces are printed on one page`() {
        if (skipWithoutQpdf("§7.7.3")) return
        if (skipWithoutPdftotext("§9.4")) return

        val split = sweep { fillers -> headingDocument(fillers, keeps = false) }
        val kept = sweep { fillers -> headingDocument(fillers, keeps = true) }

        assertTrue(
            split.any { (_, pages) -> pageOf(pages, HEADING) != pageOf(pages, BODY) },
            "with keepWithNext off no filler count left the heading and its body on different pages, " +
                "so the sweep never reached a page boundary and the kept run below proves " +
                "nothing:\n${describe(split, HEADING)}",
        )

        for ((fillers, pages) in kept) {
            assertEquals(
                pageOf(pages, BODY),
                pageOf(pages, HEADING),
                "with $fillers fillers the heading is printed alone at the foot of its " +
                    "page:\n${describe(pages)}",
            )
        }

        assertTrue(
            kept.map { (_, pages) -> pageOf(pages, HEADING) }.toSet().size > 1,
            "the kept sweep printed the heading on page 1 every time, so no boundary was crossed",
        )
    }

    /** A keepTogether block that fits a page is printed on one page, every line of it. */
    @Test
    fun `a keepTogether code block is printed on a single page`() {
        if (skipWithoutQpdf("§7.7.3")) return
        if (skipWithoutPdftotext("§9.4")) return

        val split = sweep { fillers -> codeDocument(fillers, keepTogether = false) }
        val kept = sweep { fillers -> codeDocument(fillers, keepTogether = true) }

        assertTrue(
            split.any { (_, pages) -> pageOf(pages, codeLine(1)) != pageOf(pages, codeLine(CODE_LINES)) },
            "with keepTogether off no filler count broke the code block across pages, so the sweep " +
                "never reached a page boundary and the kept run below proves " +
                "nothing:\n${describe(split, codeLine(1))}",
        )

        for ((fillers, pages) in kept) {
            val first = pageOf(pages, codeLine(1))
            for (line in 1..CODE_LINES) {
                assertEquals(
                    first,
                    pageOf(pages, codeLine(line)),
                    "with $fillers fillers line $line of the keepTogether block left its block's " +
                        "page:\n${describe(pages)}",
                )
            }
        }

        assertTrue(
            kept.map { (_, pages) -> pageOf(pages, codeLine(1)) }.toSet().size > 1,
            "the kept sweep printed the code block on page 1 every time, so no boundary was crossed",
        )
    }

    // ------------------------------------------------------------ documents

    /** [fillers] one-line paragraphs, then a heading and the body text it introduces. */
    private fun headingDocument(fillers: Int, keeps: Boolean): List<Block> = fillerParagraphs(fillers) +
        HeadingBlock(
            level = HEADING_LEVEL,
            content = listOf(TextRun(HEADING)),
            spacingAfter = 12.pt,
            keepWithNext = keeps,
            keepTogether = keeps,
        ) +
        ParagraphBlock(listOf(TextRun(BODY)))

    /** [fillers] one-line paragraphs, then a code block of [CODE_LINES] lines. */
    private fun codeDocument(fillers: Int, keepTogether: Boolean): List<Block> = fillerParagraphs(fillers) +
        CodeBlock(
            text = (1..CODE_LINES).joinToString(separator = "\n") { line -> codeLine(line) },
            keepTogether = keepTogether,
        ) +
        ParagraphBlock(listOf(TextRun(BODY)))

    private fun fillerParagraphs(fillers: Int): List<Block> =
        (1..fillers).map { number -> ParagraphBlock(listOf(TextRun(filler(number)))) }

    // ------------------------------------------------------------- the sweep

    /**
     * Every step of the sweep rendered to its own file and read back as pages.
     *
     * The first file of every sweep is checked with `qpdf --check`: the flags
     * change where the blocks go and nothing about how the file is written, so
     * one structural check per sweep says that what pdftotext is reading is a
     * PDF and not a coincidence.
     */
    private fun sweep(document: (Int) -> List<Block>): List<Pair<Int, List<String>>> = SWEEP.map { fillers ->
        var pages: List<String> = emptyList()
        withRendered(document(fillers)) { path ->
            if (fillers == SWEEP.first) assertQpdfCheckIsClean(path)
            pages = extractedText(path).split(FORM_FEED).filter { page -> page.isNotBlank() }
        }
        fillers to pages
    }

    /**
     * [blocks] resolved, fragmented, emitted and written to a file that lives
     * for the duration of [assertions].
     *
     * One font catalog serves the resolver, the fragmenter and the emitter,
     * which is the contract of all three.
     */
    private fun withRendered(blocks: List<Block>, assertions: (Path) -> Unit) {
        val fonts = FontCatalog()
        val styled = Resolver(THEME, fonts).resolve(blocks)
        val pages = Fragmenter(fonts, PageTemplate.of(PAGE, Insets.uniform(MARGIN))).fragment(styled)

        val path = Files.createTempFile("pdfkt-keep", ".pdf")
        try {
            Files.newOutputStream(path).asSink().buffered().use { sink ->
                val emitter = PlanEmitter(PdfDocumentWriter(sink), fonts)
                for (page in pages) emitter.emit(page)
                emitter.finish(fileIdentifier = FILE_IDENTIFIER)
            }
            assertions(path)
        } finally {
            path.deleteIfExists()
        }
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
        val PAGE: Size = Size(320.pt, 240.pt)

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

        const val FORM_FEED: String = "\u000C"

        /** "Each PDF file identifier byte-string shall have a minimum length of 16 bytes" (§14.4). */
        val FILE_IDENTIFIER: ByteArray = "keep-flags-fixture".encodeToByteArray()

        fun codeLine(index: Int): String = "codeLine$index()"

        fun filler(index: Int): String = "Filler line $index holds the flow."
    }
}
