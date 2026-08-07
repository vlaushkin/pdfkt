/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.samples

import io.pdfkt.api.Clock
import io.pdfkt.api.PdfDate
import io.pdfkt.api.assertQpdfCheckIsClean
import io.pdfkt.api.document
import io.pdfkt.api.extractedText
import io.pdfkt.api.imageXObjects
import io.pdfkt.api.skipWithoutPdftotext
import io.pdfkt.api.skipWithoutQpdf
import io.pdfkt.api.testPng
import io.pdfkt.api.writeTo
import kotlinx.io.asSink
import kotlinx.io.buffered
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.io.path.deleteIfExists
import kotlin.test.assertTrue

// ISO 32000-2:2020 §7.5 "File structure" — what qpdf is asked to accept below;
// §8.9.5 "Image dictionaries" — what the picture becomes; §9.4 "Text objects" —
// the marks pdftotext reads back. The shape of the document is this library's
// own design.

/**
 * A hello world in the README's shape, written and rendered. (The README's
 * literal snippet is guarded token-for-token by [ReadmeHelloWorldTest]; this
 * file renders a variant of it and reads the output back.)
 *
 * The public API in one file: two imports, `document { }`, a paragraph whose first run
 * is styled with a bare `var`, a table of rows and cells, a picture whose format
 * is read off its bytes, and `writeTo` handing the file to a sink the caller
 * opened. The same skeleton in docxkt writes a DOCX — that is what "paired"
 * means, and this is where the claim is checked instead of asserted in prose.
 *
 * It lives outside `io.pdfkt.api` deliberately: a consumer of the published
 * artifact reaches these names by importing them, so the import list above is
 * part of what is under test. No `io.pdfkt.core` import appears in it, and the
 * two names that are not the document's own — [testPng] and the tool runners —
 * are this module's *test* sources, which no consumer needs.
 *
 * Two departures from the naive form of the snippet, both deliberate:
 *
 * - the picture is `image(logo)` in the flow rather than
 *   `paragraph { image(logo) }`: in pdfkt an image is a block, and inside a
 *   `paragraph { }` the receiver is an inline scope with the document's own
 *   builders hidden by the DSL marker;
 * - the sink is kotlinx-io's, which is the type `writeTo` takes —
 *   `outputStream.asSink().buffered()`.
 */
class HelloWorldGateTest {

    @Test
    @DisplayName("the README hello world renders to a file qpdf accepts and pdftotext reads")
    fun theHelloWorldRenders() {
        if (skipWithoutQpdf("§7.5")) return
        if (skipWithoutPdftotext("§9.4")) return

        val logo: ByteArray = testPng(width = 48, height = 24)
        val myClock: Clock = Clock { PdfDate.ofEpochSeconds(PINNED_EPOCH_SECONDS, offsetMinutes = OFFSET_MINUTES) }

        val doc = document {
            clock = myClock
            paragraph {
                text("Report: ") { bold = true }
                text("Q4 2026 summary")
            }
            table {
                row {
                    cell { paragraph { text("Metric") } }
                    cell { paragraph { text("Value") } }
                }
            }
            image(logo) // bytes decide the format; fit and rotation optional
        }

        val path = Files.createTempFile("pdfkt-hello-world", ".pdf")
        try {
            // The sink is written and flushed but not closed by `writeTo`; the
            // `use` that opened it is what closes it.
            Files.newOutputStream(path).asSink().buffered().use { sink -> doc.writeTo(sink) }

            assertQpdfCheckIsClean(path)

            val text = extractedText(path)
            WRITTEN.forEach { written ->
                assertTrue(text.contains(written), "\"$written\" went in through the DSL and did not come back")
            }

            // The picture reached the page as an image XObject of its own
            // (§8.9.5, Table 87). A PNG has no filter a PDF takes, so it is
            // decoded and written back under /FlateDecode — the caller said
            // neither, which is the point of `image(bytes)`.
            val images = imageXObjects(path)
            assertTrue(images.size == 1, "the document should carry exactly one image, it carries ${images.size}")
            assertTrue(
                images.single().dictionary.contains("/FlateDecode"),
                "the PNG was not written back compressed:\n${images.single().dictionary}",
            )
        } finally {
            path.deleteIfExists()
        }
    }

    private companion object {
        /** 2026-08-06T09:30:00 three hours east of UT — a pinned clock, as every rendering test here has. */
        const val PINNED_EPOCH_SECONDS = 1_785_997_800L
        const val OFFSET_MINUTES = 180

        /** Every word the hello world writes, which pdftotext has to give back. */
        val WRITTEN: List<String> = listOf("Report:", "Q4 2026 summary", "Metric", "Value")
    }
}
