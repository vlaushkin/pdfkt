/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.api

import io.pdfkt.core.font.FontCatalog
import io.pdfkt.core.font.FontRef
import io.pdfkt.core.fragment.LineBreaker
import io.pdfkt.core.fragment.TextLine
import io.pdfkt.core.resolve.Resolver
import io.pdfkt.core.resolve.StyledParagraph
import io.pdfkt.core.style.FontFamily
import io.pdfkt.core.style.Theme
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Unicode Standard Annex #9 §3.3 (the levels), §3.4 L1 and L2 (the line).

/** Lines of mixed direction, as the line breaker places their runs. */
class BidiLineTest {
    private val family = FontFamily.of(
        FontRef.Supplied(systemFontBytes("DejaVuSans.ttf", "Bidi lines are checked"), "sans"),
    ).withFallbacks(
        listOf(
            FontRef.Supplied(
                systemFontBytes("android/NotoSansHebrew-Regular.ttf", "Bidi lines are checked", "the Android fonts"),
                "hebrew",
            ),
        ),
    )

    @Test
    @DisplayName("Hebrew inside an English sentence is drawn right to left between its English neighbours")
    fun hebrewInEnglish() {
        val line = lineOf("abc שלום def")
        val (latin, hebrew, rest) = line.runs

        assertFalse(line.rightToLeft)
        assertEquals(listOf("abc ", "שלום", " def"), line.runs.map { it.text })
        assertTrue(hebrew.rightToLeft && !latin.rightToLeft && !rest.rightToLeft)
        assertTrue(latin.xOffset < hebrew.xOffset && hebrew.xOffset < rest.xOffset)
    }

    @Test
    @DisplayName("a Hebrew paragraph runs right to left: the English word that follows is to its left")
    fun englishInHebrew() {
        val line = lineOf("שלום abc")
        val hebrew = line.runs.first { it.rightToLeft }
        val latin = line.runs.first { "abc" in it.text }

        assertTrue(line.rightToLeft, "the paragraph did not take its direction from its first letter")
        assertTrue(latin.xOffset < hebrew.xOffset, "abc is drawn at ${latin.xOffset}, after שלום at ${hebrew.xOffset}")
    }

    @Test
    @DisplayName("a paragraph told to run left to right does, whatever its first letter")
    fun theDirectionCanBeGiven() {
        val line = lineOf("שלום abc", TextDirection.LeftToRight)
        val hebrew = line.runs.first { it.rightToLeft }
        val latin = line.runs.first { "abc" in it.text }

        assertFalse(line.rightToLeft)
        assertTrue(hebrew.xOffset < latin.xOffset)
    }

    private fun lineOf(text: String, direction: TextDirection = TextDirection.Auto): TextLine {
        val fonts = FontCatalog()
        val doc = document {
            clock = PINNED_CLOCK
            paragraph(text, direction = direction)
        }
        val paragraph = Resolver(Theme(bodyFamily = family), fonts).resolve(doc.toDocumentSpec().content).single()
            as StyledParagraph
        return LineBreaker(fonts).breakIntoLines(paragraph.content, 500.pt, direction = paragraph.direction).single()
    }
}
