/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.api

import io.pdfkt.core.font.FontRef
import io.pdfkt.core.style.FontFamily
import io.pdfkt.core.style.Theme
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// ISO 32000-2:2020 §7.7.3.3 "Page objects" — /MediaBox, which a section's paper
// becomes on each of its pages. The sections themselves are this library's own
// design.

/**
 * A photo album written with sections, read back by the tools a reader is: a
 * square cover in Russian, two photographs on sheets of their own sizes, each
 * with a signature under it.
 */
class RichSectionGateTest {
    private val sans: ByteArray =
        systemFontBytes("DejaVuSans.ttf", "This test draws a Russian cover, which needs an embedded font program")

    @Test
    @DisplayName("every page of the album has the size its section gave it")
    fun everyPageHasItsSectionsSize() {
        if (skipWithoutQpdf("§7.7.3.3")) return

        withRendered(album()) { path ->
            assertQpdfCheckIsClean(path)
            assertEquals(3, pageCount(path))
            val info = tool("pdfinfo", "-f", "1", "-l", "3", path.toString())
            if (info.status != 0) return@withRendered
            val sizes = PAGE_SIZE.findAll(info.output).map { match -> match.groupValues[1] to match.groupValues[2] }
            assertEquals(
                listOf("595.276" to "595.276", "640" to "${480 + 2 * 10 + SIGNATURE_SIZE * 1.5}", "300" to "400"),
                sizes.map { (width, height) -> width.trimZeros() to height.trimZeros() }.toList(),
                "pdfinfo reports other page sizes:\n${info.output}",
            )
        }
    }

    @Test
    @DisplayName("the cover and the signatures read back as they were written")
    fun theTextReadsBack() {
        if (skipWithoutPdftotext("§9.10.3")) return

        withRendered(album()) { path ->
            val pages = extractedPages(path, layout = false).map { page -> wordsOf(page).joinToString(" ") }
            assertEquals(TITLE, pages[0].substringBefore(" $SUBTITLE"), "the cover reads ${pages[0]}")
            assertTrue(pages[0].endsWith(SUBTITLE), "the cover reads ${pages[0]}")
            assertEquals(SIGNATURE, pages[1], "the first photograph's page reads ${pages[1]}")
            assertEquals("", pages.getOrElse(2) { "" }, "the unsigned photograph has text on its page: $pages")
        }
    }

    private fun album(): Document = document {
        clock = PINNED_CLOCK
        theme = Theme(bodyFamily = FontFamily.of(FontRef.Supplied(sans, "sans")), lineHeight = 1.5f)
        val cover = PageSize.A4.toSize().width
        section(PageSize.Custom(cover, cover), margins = NONE, verticalAlign = VerticalAlign.Middle) {
            paragraph(align = TextAlign.Center, spacingAfter = 10.pt) {
                text(TITLE) { fontSize = cover / 20.0f }
            }
            paragraph(align = TextAlign.Center) {
                text(SUBTITLE) { fontSize = cover / 35.0f }
            }
        }
        section(
            PageSize.Custom(640.pt, (480 + 2 * 10 + SIGNATURE_SIZE * 1.5).pt),
            margins = Margins.Fixed(top = 0.pt, right = 0.pt, bottom = 10.pt, left = 0.pt),
        ) {
            image(testPng(640, 480))
            paragraph(align = TextAlign.Right, indentRight = 5.pt, spacingBefore = 10.pt) {
                text(SIGNATURE) {
                    fontSize = SIGNATURE_SIZE.pt
                    color = Rgb.of(0x616161)
                }
            }
        }
        section(PageSize.Custom(300.pt, 400.pt), margins = NONE) { image(testPng(300, 400)) }
    }

    private fun String.trimZeros(): String = toDouble().let { value ->
        val rounded = kotlin.math.round(value * 1000.0) / 1000.0
        if (rounded == kotlin.math.floor(rounded)) rounded.toLong().toString() else rounded.toString()
    }
}

private val NONE: Margins = Margins.Fixed(0.pt, 0.pt, 0.pt, 0.pt)

private const val TITLE: String = "Лето в Карелии"

private const val SUBTITLE: String = "Июль 2026"

private const val SIGNATURE: String = "Фото: Анна"

private const val SIGNATURE_SIZE: Double = 8.42

private val PAGE_SIZE: Regex = Regex("""size:\s+([0-9.]+) x ([0-9.]+) pts""")
