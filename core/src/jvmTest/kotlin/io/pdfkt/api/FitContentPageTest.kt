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

// ISO 32000-2:2020 §7.7.3.3 "Page objects" (MediaBox), Annex C Table C.1 (the
// largest page). Pages as tall as their content are this library's own design.

/** `section(PageSize.FitContent(width))`: pages as tall as what is on them. */
class FitContentPageTest {
    private val family = FontFamily.of(
        FontRef.Supplied(systemFontBytes("DejaVuSans.ttf", "Fitted pages are measured"), "sans"),
    )
        .withFallbacks(
            listOf(
                FontRef.Supplied(
                    systemFontBytes(
                        "android/NotoSansCJK-Regular.ttc",
                        "Fitted pages are measured",
                        "the Android fonts",
                    ),
                    "cjk",
                ),
            ),
        )

    @Test
    @DisplayName("a photo and a caption in two fonts are one page, as tall as the two and the margins")
    fun aPhotoAndItsCaptionAreOnePage() {
        val pdf = document {
            clock = PINNED_CLOCK
            theme = Theme(bodyFamily = family)
            section(PageSize.FitContent(300.pt), margins = Margins.Fixed(0.pt, 0.pt, 10.pt, 0.pt)) {
                image(testJpeg(300, 200))
                paragraph("夏休みの思い出 · PDFolio", spacingBefore = 10.pt)
            }
        }.toByteArray().decodeToString()

        val boxes = mediaBoxes(pdf)
        assertEquals(1, boxes.size, "the caption went onto a page of its own")
        val (width, height) = boxes.single()
        assertEquals(300.0, width, 0.01)
        // The photo, the gap, a line of the caption, the bottom margin: a line
        // of a CJK face mixed with a Latin one is more than 1.5 of its size.
        assertTrue(height > 200 + 10 + 10 + 12 && height < 200 + 10 + 10 + 12 * 2.5, "the page is $height tall")
    }

    @Test
    @DisplayName("each page break starts a page measured on its own")
    fun eachPageIsMeasuredOnItsOwn() {
        val pdf = document {
            clock = PINNED_CLOCK
            section(PageSize.FitContent(200.pt), margins = Margins.Fixed(10.pt, 10.pt, 10.pt, 10.pt)) {
                paragraph("One line")
                pageBreak()
                paragraph("One line")
                paragraph("and a second")
            }
        }.toByteArray().decodeToString()

        val heights = mediaBoxes(pdf).map { it.second }
        assertEquals(2, heights.size)
        assertTrue(heights[1] > heights[0], "the page of two lines is not taller: $heights")
    }

    private fun mediaBoxes(pdf: String): List<Pair<Double, Double>> =
        Regex("/MediaBox ?\\[0 0 ([0-9.]+) ([0-9.]+)]").findAll(pdf)
            .map { it.groupValues[1].toDouble() to it.groupValues[2].toDouble() }.toList()
}
