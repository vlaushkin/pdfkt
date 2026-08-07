/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.api

import io.pdfkt.core.block.ListMarkerStyle
import io.pdfkt.core.block.TextAlign
import io.pdfkt.core.doc.BandPages
import io.pdfkt.core.doc.Clock
import io.pdfkt.core.model.Rgb
import io.pdfkt.core.obj.PdfDate
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

// No clause applies: what is under test is the JavaScript face of the DSL.

/**
 * Cross-translation fidelity: each test builds the same document twice —
 * once through the chainable JavaScript facade, once through the Kotlin
 * DSL — and asserts the PDF bytes are identical. The clock is pinned, so
 * the whole file is compared.
 */
class JsFacadeTest {

    private val epochSeconds = 1_770_000_000L
    private val offsetMinutes = 60

    private fun jsDocument(): PdfDocument =
        PdfDocument(pinnedAtEpochSeconds = epochSeconds.toDouble(), timeZoneOffsetMinutes = offsetMinutes)

    private fun kotlinDocument(block: DocumentScope.() -> Unit): ByteArray = document {
        clock = Clock { PdfDate.ofEpochSeconds(epochSeconds, offsetMinutes = offsetMinutes) }
        block()
    }.toByteArray()

    @Test
    fun paragraph_runs_and_styles_match_the_kotlin_dsl() {
        val js = jsDocument()
            .heading(1, "Q4 2026 summary")
            .richHeading(3) { p -> p.text("styled ").italic().text("tail") }
            .paragraph { p ->
                p.text("Revenue: ").bold()
                    .text("up 12%")
                    .text(" warning").color(0xCC0000).fontSize(9.0)
            }
            .toBytes()

        val kotlin = kotlinDocument {
            heading(1, "Q4 2026 summary")
            heading(3) {
                text("styled ") { italic = true }
                text("tail")
            }
            paragraph {
                text("Revenue: ") { bold = true }
                text("up 12%")
                text(" warning") {
                    color = Rgb.of(0xCC0000)
                    fontSize = 9.0.pt
                }
            }
        }

        assertContentEquals(kotlin, js)
    }

    @Test
    fun lists_tables_and_blocks_match_the_kotlin_dsl() {
        val js = jsDocument()
            .bulletList(marker = "dash") { l -> l.item("first").item("second") }
            .numberedList(startNumber = 7) { l -> l.item("third") }
            .table { t ->
                t.headerRow { r -> r.cell("Metric").cell("Value") }
                    .row { r -> r.cell("Revenue").cell("1 310", alignment = "right") }
            }
            .codeBlock("val x = 1")
            .blockquote { p -> p.text("quoted") }
            .horizontalRule()
            .toBytes()

        val kotlin = kotlinDocument {
            bulletList(markerStyle = ListMarkerStyle.Dash) {
                item("first")
                item("second")
            }
            numberedList(startNumber = 7) { item("third") }
            table {
                header {
                    cell("Metric")
                    cell("Value")
                }
                row {
                    cell("Revenue")
                    cell("1 310", align = TextAlign.Right)
                }
            }
            codeBlock("val x = 1")
            blockquote { paragraph { text("quoted") } }
            hr()
        }

        assertContentEquals(kotlin, js)
    }

    @Test
    fun page_setup_colophons_and_breaks_match_the_kotlin_dsl() {
        val js = jsDocument()
            .landscape()
            .margins(24.0, 24.0, 24.0, 24.0)
            .metadata(title = "Report", author = "pdfkt")
            .header { p -> p.text("Q1 Report").bold() }
            .footerPageOfPages(pages = "exceptFirst")
            .simpleParagraph("body")
            .pageBreak()
            .simpleParagraph("more")
            .toBytes()

        val kotlin = kotlinDocument {
            landscape()
            margins = Margins.Fixed(24.0.pt, 24.0.pt, 24.0.pt, 24.0.pt)
            metadata = metadata.copy(title = "Report", author = "pdfkt")
            header { paragraph { text("Q1 Report") { bold = true } } }
            footer(pages = BandPages.ExceptFirst) { paragraph(pageOfPages(), align = TextAlign.Right) }
            paragraph("body")
            pageBreak()
            paragraph("more")
        }

        assertContentEquals(kotlin, js)
    }

    @Test
    fun link_and_break_match_the_kotlin_dsl() {
        val js = jsDocument()
            .paragraph { p ->
                p.link("https://example.com", "here")
                    .br()
                    .text("next line")
            }
            .toBytes()

        val kotlin = kotlinDocument {
            paragraph {
                link("https://example.com", "here")
                br(soft = false)
                text("next line")
            }
        }

        assertContentEquals(kotlin, js)
    }

    @Test
    fun the_output_is_a_pdf() {
        val bytes = jsDocument().simpleParagraph("Hello, world!").toBytes()
        val header = bytes.copyOfRange(0, 5).decodeToString()
        assertTrue(header == "%PDF-", "PDF header missing, got \"$header\"")
        val tail = bytes.copyOfRange(bytes.size - 32, bytes.size).decodeToString()
        assertTrue("%%EOF" in tail, "PDF trailer missing")
    }

    @Test
    fun a_style_call_without_a_run_is_refused() {
        assertFailsWith<IllegalStateException> {
            jsDocument().paragraph { p -> p.bold() }
        }
    }

    @Test
    fun an_empty_document_is_refused_at_render() {
        assertFailsWith<IllegalArgumentException> {
            jsDocument().toBytes()
        }
    }
}
