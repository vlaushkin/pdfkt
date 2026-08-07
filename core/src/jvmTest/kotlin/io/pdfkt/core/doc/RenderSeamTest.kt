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
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// ISO 32000-2:2020 §7.5.5 "File trailer" (Table 15), §7.9.4 "Dates",
// §14.3.3 "Document information dictionary"

/**
 * The render seam, taken end to end: one [DocumentSpec], one [render] call,
 * and a file that three questions are then asked of by tools that never saw this
 * library.
 *
 * - Is it a document? `qpdf --check`, and the text a reader extracts is the text
 *   the caller wrote.
 * - Is the information dictionary reachable the way §7.5.5 says it is — from the
 *   trailer, as an indirect reference — rather than an orphan object that
 *   happens to be in the file?
 * - Are its dates the pinned clock's, to the character? That is the acceptance
 *   of the "real clock, injected" rule: pin the clock and the
 *   bytes are fixed, so a date read from anywhere else inside the engine cannot
 *   pass.
 */
class RenderSeamTest {
    /** The whole seam: `render` writes a document a reader accepts and reads back. */
    @Test
    fun `render writes a document that reads back as it was written`() {
        if (skipWithoutQpdf("§7.5.5")) return
        if (skipWithoutPdftotext("§9.4")) return

        withRendered { path ->
            assertQpdfCheckIsClean(path)
            // The words rather than the lines: a paragraph is broken into lines
            // by the engine, and where those breaks fall is the line breaker's
            // test to make. What this asks is that every word the caller wrote
            // is in the file exactly once and in the order it was written.
            val written = extractedText(path).split(WHITE_SPACE).filter { word -> word.isNotEmpty() }
            assertContentEquals(expectedWords(), written, "the file does not read back as it was written")
            assertTrue(pageCount(path) >= 2, "the document was written onto ${pageCount(path)} page")
        }
    }

    /**
     * `/Info` is in the trailer, as an indirect reference, and the object it
     * names is the information dictionary of §14.3.3 — the whole of Table 15's
     * requirement: "(Optional; shall be an indirect reference) The PDF file's
     * information dictionary."
     *
     * The reference is read from the trailer rather than searched for in the
     * body, because being *reachable from the trailer* is what the entry is for:
     * an information dictionary a reader cannot get to from there is not one.
     */
    @Test
    fun `the trailer points at the information dictionary`() {
        if (skipWithoutQpdf("§7.5.5")) return

        withRendered { path ->
            val trailer = showObject(path, "trailer")
            val reference = INFO_REFERENCE.find(trailer)
            assertTrue(reference != null, "the trailer carries no /Info:\n$trailer")
            assertEquals("0", reference.groupValues[2], "a newly created file defines generation 0 objects")

            val info = showObject(path, "${reference.groupValues[1]},0")
            assertContains(info, "/Title (Report of 2026)")
            assertContains(info, "/Producer (pdfkt)")
            assertContains(info, "/CreationDate (D:20140314124211+01'00)")
            assertContains(info, "/ModDate (D:20140314124211+01'00)")
        }
    }

    /**
     * The dates are the clock's, and only the clock's: the same document
     * rendered through a clock pinned to another instant differs in exactly
     * those two strings.
     */
    @Test
    fun `a document rendered through another clock carries that clock's dates`() {
        if (skipWithoutQpdf("§7.5.5")) return

        val other = Clock { PdfDate(1998, 12, 23, 19, 52, 0, offsetMinutes = -8 * 60) }
        withRendered(clock = other) { path ->
            val trailer = showObject(path, "trailer")
            val reference = requireNotNull(INFO_REFERENCE.find(trailer))
            val info = showObject(path, "${reference.groupValues[1]},0")
            assertContains(info, "/CreationDate (D:19981223195200-08'00)")
            assertContains(info, "/ModDate (D:19981223195200-08'00)")
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

    /** The document rendered through [clock] into a file that lives for the duration of [block]. */
    private fun withRendered(clock: Clock = PINNED, block: (Path) -> Unit) {
        val spec =
            DocumentSpec(
                pageSize = Size.A4,
                margins = Insets.uniform(56.pt),
                content = document(),
                metadata = Metadata(title = TITLE),
                clock = clock,
            )
        val path = Files.createTempFile("pdfkt-seam", ".pdf")
        try {
            Files.newOutputStream(path).asSink().buffered().use { sink -> render(spec, Theme.Default, sink) }
            block(path)
        } finally {
            path.deleteIfExists()
        }
    }

    /** One object of the file, as qpdf prints it. */
    private fun showObject(path: Path, id: String): String {
        val run = qpdf("--show-object=$id", path.toString())
        assertEquals(0, run.status, "qpdf could not show $id:\n${run.output}")
        return run.output
    }

    private fun pageCount(path: Path): Int {
        val run = qpdf("--show-npages", path.toString())
        assertEquals(0, run.status, "qpdf could not count the pages:\n${run.output}")
        return run.output.trim().toInt()
    }

    private companion object {
        const val TITLE: String = "Report of 2026"

        /** Enough running text that the seam has to paginate rather than fill one page. */
        const val PARAGRAPHS: Int = 30
        const val WORDS_PER_PARAGRAPH: Int = 24

        /** The clock the file is written through, pinned to the example of §14.3.3. */
        val PINNED: Clock = Clock { PdfDate(2014, 3, 14, 12, 42, 11, offsetMinutes = 60) }

        /** `/Info N G R` in the trailer dictionary. */
        val INFO_REFERENCE: Regex = Regex("""/Info\s+(\d+)\s+(\d+)\s+R""")

        val WHITE_SPACE: Regex = Regex("\\s+")
    }
}
