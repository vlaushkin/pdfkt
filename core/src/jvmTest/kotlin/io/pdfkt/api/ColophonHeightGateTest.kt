/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.api

import io.pdfkt.core.doc.BandOverflowException
import io.pdfkt.core.doc.BandPlanner
import io.pdfkt.core.doc.layOut
import io.pdfkt.core.font.FontCatalog
import io.pdfkt.core.font.FontRef
import io.pdfkt.core.resolve.Resolver
import io.pdfkt.core.style.FontFamily
import io.pdfkt.core.style.Theme
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

// No clause applies: L4 is this library's own design.

/**
 * Colophons whose text the reader of an app types in, in a font the app
 * supplies — the document photo2pdf writes, which a two-line foot used to stop.
 *
 * Its foot is one line of Cyrillic and a URL that wraps to two in DejaVu Sans
 * at the theme's 11 points. Two lines of that face are 28.17 points tall: a
 * line takes the extent of the font (ascender to descender, 1.164 em of DejaVu
 * Sans — 12.80 points), the line height of the theme lies between baselines
 * (1.2 of the extent), and the last line takes its extent alone — so
 * `12.80 × 1.2 + 12.80`. The 28 points every band used to be given were sized
 * for Helvetica, whose extent is 0.925 em; nothing between the versions moved
 * the measure, and the strip was always too short for this face.
 */
class ColophonHeightGateTest {
    private val family = FontFamily.of(
        FontRef.Supplied(systemFontBytes("DejaVuSans.ttf", "Colophons in a supplied font are measured"), "sans"),
    )

    @Test
    @DisplayName("a two-line foot with no height is as tall as its two lines, and the document is written")
    fun aTwoLineFootIsWritten() {
        val document = ocrDocument { footer(PHOTO2PDF_FOOT) }
        assertNear(TWO_LINES_OF_DEJAVU, settledFooter(document), "the strip of a two-line foot")
        withRendered(document) { path ->
            if (skipWithoutPdftotext("the foot of the page")) return@withRendered
            assertContainsWords(extractedText(path), PHOTO2PDF_FOOT)
        }
    }

    @Test
    @DisplayName("a foot of five lines and more is written whole, the text paginated against its strip")
    fun aLongFootIsWritten() {
        val long = List(FIVE_LINES_AND_MORE) { PHOTO2PDF_FOOT }.joinToString(separator = " ")
        val document = ocrDocument {
            header(long, align = TextAlign.Start)
            footer(long)
        }
        val strip = settledFooter(document)
        assertTrue(strip > TWO_LINES_OF_DEJAVU * 2.0f, "a foot of $FIVE_LINES_AND_MORE wrapped lines is $strip tall")
        withRendered(document) { path ->
            if (skipWithoutPdftotext("the colophons of the page")) return@withRendered
            val pages = extractedPages(path, layout = false)
            assertTrue(pages.size > 1, "the body was not paginated onto more than one page, so nothing was proved")
            for (page in pages) assertContainsWords(page, long)
        }
    }

    @Test
    @DisplayName("a foot taller than the height it was given grows past it rather than stop the document")
    fun aFootTallerThanItsHeightGrows() {
        val document = ocrDocument { footer(PHOTO2PDF_FOOT, height = 28.pt) }
        assertNear(TWO_LINES_OF_DEJAVU, settledFooter(document), "the strip a 28-point foot grew to")
        document.toByteArray()
    }

    @Test
    @DisplayName("a strict foot taller than its height is still refused")
    fun aStrictFootIsRefused() {
        val document = ocrDocument { footer(PHOTO2PDF_FOOT, height = 28.pt, overflow = BandOverflow.Fail) }
        val refused = assertFailsWith<BandOverflowException> { document.toByteArray() }
        assertNear(TWO_LINES_OF_DEJAVU, refused.required ?: 0.pt, "the height the refusal reports")
    }

    private fun ocrDocument(bands: DocumentScope.() -> Unit): Document = document {
        clock = PINNED_CLOCK
        margins = Margins.Fixed(top = MARGIN, right = MARGIN, bottom = MARGIN, left = MARGIN)
        theme = Theme(bodyFamily = family)
        bands()
        repeat(BODY_PARAGRAPHS) { index -> paragraph("Распознанный текст, абзац ${index + 1}. $BODY") }
    }

    /** The strip [document]'s footer settled on, as the renderer settles it. */
    private fun settledFooter(document: Document): Pt {
        val spec = document.toDocumentSpec()
        val fonts = FontCatalog()
        val styled = Resolver(document.theme, fonts).resolve(spec.content)
        val (settled, _) = BandPlanner(document.theme, fonts).reserve(spec) { reserved ->
            layOut(reserved, styled, fonts)
        }
        return requireNotNull(settled.footer?.height) { "the footer's strip was not settled" }
    }

    private fun assertNear(expected: Pt, actual: Pt, what: String) {
        assertTrue(abs(expected.value - actual.value) < EPSILON, "$what: expected $expected, got $actual")
    }

    /** Every word of [text] is in [extracted], which pdftotext may have broken at other places. */
    private fun assertContainsWords(extracted: String, text: String) {
        val words = wordsOf(extracted).toSet()
        val missing = wordsOf(text).filterNot { word -> word in words }
        assertEquals(emptyList(), missing, "the extracted text is missing words of the colophon")
    }

    private companion object {
        const val PHOTO2PDF_FOOT =
            "Создано в Фото-в-ПДФ Конвертере в Одно Нажатие. Загрузите его здесь: https://photo2pdf.me"

        val MARGIN: Pt = 36.pt

        /** Two lines of DejaVu Sans at 11 points, as the class comment derives it. */
        val TWO_LINES_OF_DEJAVU: Pt = 28.170313.pt

        const val FIVE_LINES_AND_MORE: Int = 4

        const val BODY_PARAGRAPHS: Int = 60

        const val BODY = "Строка за строкой, как её прочёл распознаватель, без правки и без пропусков."

        const val EPSILON: Float = 0.001f
    }
}
