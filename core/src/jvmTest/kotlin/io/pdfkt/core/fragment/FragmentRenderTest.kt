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
import io.pdfkt.core.block.PageBreakBlock
import io.pdfkt.core.block.ParagraphBlock
import io.pdfkt.core.block.TextRun
import io.pdfkt.core.doc.PdfDocumentWriter
import io.pdfkt.core.emit.PlanEmitter
import io.pdfkt.core.font.FontCatalog
import io.pdfkt.core.model.Insets
import io.pdfkt.core.model.Size
import io.pdfkt.core.model.pt
import io.pdfkt.core.obj.assertQpdfCheckIsClean
import io.pdfkt.core.obj.extractedText
import io.pdfkt.core.obj.qpdf
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
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// ISO 32000-2:2020 §7.7.3 "Page tree", §9.4 "Text objects"

/**
 * The fragmenter through the rest of the pipeline: blocks resolved, fragmented,
 * emitted, written, and read back by tools that never saw this library.
 *
 * This is where the fragmenter's acceptance is settled. A test over plans shows
 * that the fragmenter's own answer holds together; only a text extractor can
 * show that all sixty paragraphs reached the file, on more than one page, in the
 * order they were written — and losing a block at a page boundary is exactly the
 * failure that a page-count assertion alone would not catch.
 *
 * qpdf and pdftotext are used as tools; their output is compared, never their
 * source.
 */
class FragmentRenderTest {
    /**
     * Sixty paragraphs come out on more than one page, and every one of them
     * comes back out of the file in the order it was written.
     */
    @Test
    fun `sixty paragraphs come back from the file on two pages or more in order`() {
        if (skipWithoutQpdf("§7.7.3")) return
        if (skipWithoutPdftotext("§9.4")) return

        val paragraphs = (1..SIXTY).map { number -> "Paragraph number $number of the sixty in this document" }

        withRendered(paragraphs.map { text -> ParagraphBlock(listOf(TextRun(text))) }) { path, planned ->
            assertQpdfCheckIsClean(path)
            assertTrue(planned >= 2, "sixty paragraphs were planned onto $planned page")
            assertEquals(planned, pageCount(path), "the file has a different number of pages from the plan")
            assertContentEquals(
                paragraphs.flatMap { text -> text.split(" ") },
                extractedText(path).split(WHITE_SPACE).filter { word -> word.isNotEmpty() },
            )
        }
    }

    /**
     * A paragraph longer than a page arrives whole: the words of the part that
     * continues on the next page are neither lost at the break nor repeated
     * across it.
     */
    @Test
    fun `a paragraph split across pages comes back word for word`() {
        if (skipWithoutQpdf("§7.7.3")) return
        if (skipWithoutPdftotext("§9.4")) return

        val words = (1..900).map { number -> "word$number" }

        withRendered(listOf(ParagraphBlock(listOf(TextRun(words.joinToString(separator = " ")))))) { path, planned ->
            assertQpdfCheckIsClean(path)
            assertTrue(planned >= 2, "nine hundred words were planned onto $planned page")
            assertContentEquals(
                words,
                extractedText(path).split(WHITE_SPACE).filter { word -> word.isNotEmpty() },
            )
        }
    }

    /** A forced page break puts what follows it on a page of its own, and adds no blank one. */
    @Test
    fun `a page break puts the rest of the document on the next page`() {
        if (skipWithoutQpdf("§7.7.3")) return
        if (skipWithoutPdftotext("§9.4")) return

        val blocks = listOf(
            ParagraphBlock(listOf(TextRun("Before the break"))),
            PageBreakBlock,
            ParagraphBlock(listOf(TextRun("After the break"))),
            PageBreakBlock,
        )

        withRendered(blocks) { path, planned ->
            assertQpdfCheckIsClean(path)
            assertEquals(2, planned, "a trailing page break added a page with nothing on it")
            assertEquals(2, pageCount(path))
            val pages = extractedText(path).split(FORM_FEED).map { page -> page.trim() }.filter { it.isNotEmpty() }
            assertContentEquals(listOf("Before the break", "After the break"), pages)
        }
    }

    /**
     * [blocks] resolved, fragmented, emitted and written to a file, which is
     * handed to [assertions] together with the number of pages that were planned.
     *
     * One font catalog serves the resolver, the fragmenter and the emitter, which
     * is the contract of all three: a font measured from one catalog and written
     * from another would be two fonts.
     */
    private fun withRendered(blocks: List<Block>, assertions: (Path, Int) -> Unit) {
        val fonts = FontCatalog()
        val styled = Resolver(Theme.Default, fonts).resolve(blocks)
        val pages = Fragmenter(fonts, PageTemplate.of(Size.A4, Insets.uniform(56.pt))).fragment(styled)

        val path = Files.createTempFile("pdfkt-fragment", ".pdf")
        try {
            Files.newOutputStream(path).asSink().buffered().use { sink ->
                val emitter = PlanEmitter(PdfDocumentWriter(sink), fonts)
                for (page in pages) emitter.emit(page)
                emitter.finish(fileIdentifier = FILE_IDENTIFIER)
            }
            assertions(path, pages.size)
        } finally {
            path.deleteIfExists()
        }
    }

    /** How many pages the file has, according to qpdf. */
    private fun pageCount(path: Path): Int {
        val run = qpdf("--show-npages", path.toString())
        assertEquals(0, run.status, "qpdf could not count the pages:\n${run.output}")
        return run.output.trim().toInt()
    }

    private companion object {
        const val SIXTY: Int = 60

        val WHITE_SPACE: Regex = Regex("\\s+")

        const val FORM_FEED: String = "\u000C"

        /** "Each PDF file identifier byte-string shall have a minimum length of 16 bytes" (§14.4). */
        val FILE_IDENTIFIER: ByteArray = "fragmenter-fixture".encodeToByteArray()
    }
}
