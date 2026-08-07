/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.fragment

import io.pdfkt.core.doc.PdfDocumentWriter
import io.pdfkt.core.emit.PlanEmitter
import io.pdfkt.core.font.FontCatalog
import io.pdfkt.core.font.FontRef
import io.pdfkt.core.font.Standard14Name
import io.pdfkt.core.model.Pt
import io.pdfkt.core.model.Rgb
import io.pdfkt.core.model.Size
import io.pdfkt.core.model.pt
import io.pdfkt.core.obj.assertQpdfCheckIsClean
import io.pdfkt.core.obj.extractedText
import io.pdfkt.core.obj.skipWithoutPdftotext
import io.pdfkt.core.obj.skipWithoutQpdf
import io.pdfkt.core.plan.PagePlan
import io.pdfkt.core.plan.PlacedItem
import io.pdfkt.core.resolve.StyledTextRun
import kotlinx.io.asSink
import kotlinx.io.buffered
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// ISO 32000-2:2020 §9.4 "Text objects", §9.6.2.2 "Standard Type 1 fonts"

/**
 * The line breaker through the rest of the pipeline: broken lines, planned onto
 * a page, written to a file, and read back by a tool that never saw this
 * library.
 *
 * This is where the acceptance of the line breaker is actually settled. A test
 * of the breaker alone shows that its own answer holds together; only a text
 * extractor can show that the characters reached the file — and *that* is the
 * failure this test exists to make impossible: a breaker that loses characters
 * out of a long token while reporting success.
 *
 * pdftotext and qpdf are used as tools; their output is compared, never their
 * source.
 */
class LineBreakRenderTest {
    /**
     * A token of 200 characters, broken between characters against a column it
     * cannot fit, gives 200 characters back out of the file.
     *
     * The white space is taken out of what the extractor returned before the
     * comparison, because where a reader puts a line ending is the reader's
     * business — how many characters it found is not.
     */
    @Test
    fun `a two hundred character token comes out of the file with every character`() {
        if (skipWithoutQpdf("§7.7")) return
        if (skipWithoutPdftotext("§9.6.2.2")) return

        val fonts = FontCatalog()
        val lines = LineBreaker(fonts).breakIntoLines(listOf(run(LONG_TOKEN)), COLUMN, lineHeight = LINE_HEIGHT)

        assertTrue(lines.size > 1, "a 200-character token fitted a $COLUMN column, which cannot be")

        withRenderedPlan(fonts, lines) { path ->
            assertQpdfCheckIsClean(path)
            val extracted = extractedText(path).filterNot { character -> character.isWhitespace() }
            assertEquals(
                LONG_TOKEN.length,
                extracted.length,
                "${LONG_TOKEN.length - extracted.length} characters never reached the file",
            )
            assertEquals(LONG_TOKEN, extracted)
        }
    }

    /**
     * A sentence broken at its spaces comes back as the same words in the same
     * order — the ordinary case, asserted through the same route.
     */
    @Test
    fun `a paragraph broken at spaces comes back word for word`() {
        if (skipWithoutQpdf("§7.7")) return
        if (skipWithoutPdftotext("§9.4")) return

        val fonts = FontCatalog()
        val lines = LineBreaker(fonts).breakIntoLines(listOf(run(SENTENCE)), COLUMN, lineHeight = LINE_HEIGHT)

        assertTrue(lines.size > 1, "the sentence is wider than the column but came back as one line")

        withRenderedPlan(fonts, lines) { path ->
            assertQpdfCheckIsClean(path)
            val words = extractedText(path).split(WHITE_SPACE).filter { word -> word.isNotEmpty() }
            assertEquals(SENTENCE.split(" "), words)
        }
    }

    /**
     * The lines of [lines] planned down a page from the top margin, emitted, and
     * handed to [assertions] as a file.
     *
     * The vertical cursor here is the simplest one there is — each baseline the
     * height of its line below the one before — because the real one belongs
     * to the fragmenter. What is under test is the horizontal
     * answer; the vertical arrangement only has to keep the lines apart and in
     * order, so that a reader returns them the way they were planned.
     */
    private fun withRenderedPlan(fonts: FontCatalog, lines: List<TextLine>, assertions: (Path) -> Unit) {
        var baseline = Size.LETTER.height - MARGIN
        val items =
            lines.map { line ->
                baseline -= line.height
                PlacedItem.Text(x = MARGIN, y = baseline, runs = line.runs)
            }

        withTemporaryFile { path ->
            Files.newOutputStream(path).asSink().buffered().use { sink ->
                val emitter = PlanEmitter(PdfDocumentWriter(sink), fonts)
                emitter.emit(PagePlan(Size.LETTER, items))
                emitter.finish(fileIdentifier = FILE_IDENTIFIER)
            }
            assertions(path)
        }
    }

    private fun run(text: String): StyledTextRun =
        StyledTextRun(text, FontRef.Standard14(Standard14Name.HELVETICA), SIZE, Rgb.BLACK)

    private fun withTemporaryFile(block: (Path) -> Unit) {
        val path = Files.createTempFile("pdfkt-lines", ".pdf")
        try {
            block(path)
        } finally {
            path.deleteIfExists()
        }
    }

    private companion object {
        /**
         * 200 characters with no space and no soft hyphen in them: nowhere to
         * break except between characters. A breaker that quietly drops
         * characters at forced breaks returns fewer, which is why the exact
         * count is the assertion.
         */
        val LONG_TOKEN: String = buildString { repeat(200) { index -> append('a' + index % 26) } }

        const val SENTENCE: String =
            "The quick brown fox jumps over the lazy dog while the line breaker fills every line it can"

        val COLUMN: Pt = 120.pt

        val MARGIN: Pt = 72.pt

        val SIZE: Pt = 12.pt

        const val LINE_HEIGHT: Float = 1.2f

        val WHITE_SPACE: Regex = Regex("\\s+")

        /** "Each PDF file identifier byte-string shall have a minimum length of 16 bytes" (§14.4). */
        val FILE_IDENTIFIER: ByteArray = "line-breaker-fixture".encodeToByteArray()
    }
}
