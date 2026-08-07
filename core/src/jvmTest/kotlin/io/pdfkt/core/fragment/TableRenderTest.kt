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
import io.pdfkt.core.block.ListBlock
import io.pdfkt.core.block.ListItem
import io.pdfkt.core.block.ParagraphBlock
import io.pdfkt.core.block.TableBlock
import io.pdfkt.core.block.TableCell
import io.pdfkt.core.block.TableColumns
import io.pdfkt.core.block.TextRun
import io.pdfkt.core.doc.PdfDocumentWriter
import io.pdfkt.core.emit.PlanEmitter
import io.pdfkt.core.font.FontCatalog
import io.pdfkt.core.model.Insets
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
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// ISO 32000-2:2020 §7.7.3 "Page tree", §9.4 "Text objects"

/**
 * Tables through the rest of the pipeline: a block tree resolved, fragmented,
 * emitted, written, and read back by tools that never saw this library.
 *
 * The plan tests of `TableTest` show that the table planner's own answer holds
 * together. Only an extractor can show that the header really is drawn again on
 * the fourth page and that not one cell was lost at a break — which is the half
 * of "the repeated header rows are part of every `head`" that a count of `PlacedItem`s cannot settle.
 *
 * qpdf and pdftotext are used as tools; their output is compared, never their
 * source.
 */
class TableRenderTest {
    /**
     * A table of many rows spans several pages, every page it spans begins
     * with the header, and every cell of every row comes back exactly once.
     */
    @Test
    fun `a long table repeats its header on every page and loses no cell`() {
        if (skipWithoutQpdf("§7.7.3")) return
        if (skipWithoutPdftotext("§9.4")) return

        val rows = (1..MANY_ROWS).map { number -> listOf("alpha$number", "beta$number") }
        val table = TableBlock(
            columns = TableColumns.Equal(2),
            rows = rows.map { row -> row.map { text -> TableCell(listOf(paragraph(text))) } },
            header = listOf(TableCell(listOf(paragraph("Left"))), TableCell(listOf(paragraph("Right")))),
        )

        withRendered(listOf(table)) { path, planned ->
            assertQpdfCheckIsClean(path)
            assertTrue(planned >= THREE, "$MANY_ROWS rows were planned onto $planned page")
            val pages = pagesOf(path)
            assertEquals(planned, pages.size)
            for ((index, page) in pages.withIndex()) {
                assertContentEquals(
                    listOf("Left", "Right"),
                    page.take(2),
                    "page ${index + 1} of the table does not begin with its header",
                )
            }
            assertContentEquals(
                rows.flatten(),
                pages.flatMap { page -> page.drop(2) },
                "the cells did not come back once each, in the order they were written",
            )
        }
    }

    /**
     * A table that does not repeat its header carries it on the first page only,
     * and still loses nothing at the breaks.
     */
    @Test
    fun `a header that does not repeat is written once`() {
        if (skipWithoutQpdf("§7.7.3")) return
        if (skipWithoutPdftotext("§9.4")) return

        val rows = (1..MANY_ROWS).map { number -> listOf("alpha$number") }
        val table = TableBlock(
            columns = TableColumns.Equal(1),
            rows = rows.map { row -> row.map { text -> TableCell(listOf(paragraph(text))) } },
            header = listOf(TableCell(listOf(paragraph("Only")))),
            repeatHeader = false,
        )

        withRendered(listOf(table)) { path, planned ->
            assertQpdfCheckIsClean(path)
            assertTrue(planned >= 2, "$MANY_ROWS rows were planned onto $planned page")
            val words = pagesOf(path).flatten()
            assertEquals(1, words.count { word -> word == "Only" })
            assertContentEquals(listOf("Only") + rows.flatten(), words)
        }
    }

    /**
     * The nested-content acceptance through a file: a cell holding a bullet list
     * and a nested table draws both, because both were planned by the same
     * fragmenter the cell itself was.
     */
    @Test
    fun `a cell holding a list and a nested table comes back whole`() {
        if (skipWithoutQpdf("§7.7.3")) return
        if (skipWithoutPdftotext("§9.4")) return

        val nested = TableBlock(
            columns = TableColumns.Equal(1),
            rows = listOf(listOf(TableCell(listOf(paragraph("nested"))))),
        )
        val list = ListBlock(
            items = (1..2).map { number -> ListItem(listOf(paragraph("item$number"))) },
        )
        val table = TableBlock(
            columns = TableColumns.Fractional(listOf(2.0f, 1.0f)),
            rows = listOf(listOf(TableCell(listOf(list, nested)), TableCell(listOf(paragraph("beside"))))),
        )

        withRendered(listOf(table)) { path, planned ->
            assertQpdfCheckIsClean(path)
            assertEquals(1, planned)
            // Sorted, because a text extractor walking a cell that holds a
            // bullet list reports the markers and the items as the two columns
            // they are drawn in rather than in reading order. What this test is
            // for is that every mark reached the page; the order they were
            // planned in is `TableTest`'s claim, made where the plan is.
            assertContentEquals(
                listOf("beside", "item1", "item2", "nested", "•", "•").sorted(),
                pagesOf(path).single().sorted(),
            )
        }
    }

    private fun paragraph(text: String): ParagraphBlock = ParagraphBlock(listOf(TextRun(text)))

    /** The words of the file, page by page — pdftotext separates pages with a form feed. */
    private fun pagesOf(path: Path): List<List<String>> = extractedText(path)
        .split(FORM_FEED)
        .map { page -> page.split(WHITE_SPACE).filter { word -> word.isNotEmpty() } }
        .filter { page -> page.isNotEmpty() }

    /**
     * [blocks] resolved, fragmented, emitted and written to a file, which is
     * handed to [assertions] together with the number of pages that were
     * planned. One font catalog serves all three stages, which is the contract
     * of all three.
     */
    private fun withRendered(blocks: List<Block>, assertions: (Path, Int) -> Unit) {
        val fonts = FontCatalog()
        val styled = Resolver(Theme.Default, fonts).resolve(blocks)
        val pages = Fragmenter(fonts, PageTemplate.of(Size.A4, Insets.uniform(56.pt))).fragment(styled)

        val path = Files.createTempFile("pdfkt-table", ".pdf")
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

    private companion object {
        const val THREE: Int = 3
        const val MANY_ROWS: Int = 120

        val WHITE_SPACE: Regex = Regex("\\s+")

        const val FORM_FEED: String = "\u000C"

        /** "Each PDF file identifier byte-string shall have a minimum length of 16 bytes" (§14.4). */
        val FILE_IDENTIFIER: ByteArray = "table-fixture-page".encodeToByteArray()
    }
}
