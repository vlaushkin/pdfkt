/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.api

import io.pdfkt.core.doc.Metadata
import io.pdfkt.core.model.pt
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// ISO 32000-2:2020 §7.5 "File structure", §14.3.3 "Document information
// dictionary". The DSL above them is this library's own design.

/**
 * The seam, from the facade's side: `document { }.toByteArray()` reaches
 * [io.pdfkt.core.doc.render] and comes back as a file two tools that never saw
 * this library accept.
 *
 * It is deliberately one document and three questions. What the *layout* does
 * with a document — pages, bands, repeated headers, orphaned headings — is the
 * gate tests' own question, and asserting it twice is how two answers come to
 * disagree; what is asked here is only that the facade builds a block tree the
 * engine renders, and that the caller's own words and title survive the trip.
 */
class RichDocumentSeamTest {

    @Test
    @DisplayName("a document written through the DSL is a file a reader accepts")
    fun theDslProducesAReadableFile() {
        if (skipWithoutQpdf()) return

        withRendered(seamDocument()) { path -> assertQpdfCheckIsClean(path) }
    }

    @Test
    @DisplayName("every word written through the DSL comes back once, in order")
    fun theWordsComeBackInTheOrderTheyWereWritten() {
        if (skipWithoutPdftotext()) return

        withRendered(seamDocument()) { path ->
            // Layout mode: the text is reconstructed from where the marks
            // actually are, so a bullet and the line it marks come back as the
            // one line they are drawn as.
            val words = wordsOf(extractedText(path, layout = true))
            assertContentEquals(EXPECTED_WORDS, words, "the file does not read back as it was written")
        }
    }

    @Test
    @DisplayName("the metadata a caller set reaches the information dictionary")
    fun theTitleReachesTheInformationDictionary() {
        if (skipWithoutQpdf()) return

        withRendered(seamDocument()) { path ->
            val trailer = tool("qpdf", "--show-object=trailer", path.toString())
            assertEquals(0, trailer.status, trailer.output)
            val info = Regex("/Info (\\d+) (\\d+) R").find(trailer.output)
            assertTrue(info != null, "the trailer carries no /Info:\n${trailer.output}")
            val dictionary = tool(
                "qpdf",
                "--show-object=${info.groupValues[1]},${info.groupValues[2]}",
                path.toString(),
            )
            assertContains(dictionary.output, "Quarterly Report")
            assertContains(dictionary.output, Metadata.PRODUCER)
        }
    }

    /** The document under test. */
    private fun seamDocument(): Document = document {
        clock = PINNED_CLOCK
        metadata = Metadata(title = "Quarterly Report", author = "pdfkt")

        heading(level = 1, "Revenue")
        paragraph { +"The quarter closed above plan." }
        bulletList {
            item("Sales")
            item("Engineering")
        }
        hr(thickness = 1.pt)
        blockquote { +"Numbers are provisional." }
    }
}

/**
 * Every word of the document above, in the order it was written — the markers of
 * the list included, since a marker is text drawn on the page like any other.
 */
private val EXPECTED_WORDS: List<String> = listOf(
    "Revenue",
    "The", "quarter", "closed", "above", "plan.",
    "•", "Sales",
    "•", "Engineering",
    "Numbers", "are", "provisional.",
)
