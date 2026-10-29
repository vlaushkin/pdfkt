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
import io.pdfkt.core.font.UnencodableCharacterException
import io.pdfkt.core.resolve.Resolver
import io.pdfkt.core.resolve.StyledParagraph
import io.pdfkt.core.resolve.StyledTextRun
import io.pdfkt.core.style.FontFamily
import io.pdfkt.core.style.Theme
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// ISO 32000-2:2020 §9.7 "Composite fonts" — the supplied fallback is a Type 0
// font like any supplied face. Falling back is this library's own design.

/** [FontFamily.fallbacks]: characters the face has no glyph for, drawn from the first font that has one. */
class FontFallbackTest {
    private val sans = FontRef.Supplied(
        systemFontBytes("DejaVuSans.ttf", "This test falls back to a font with Cyrillic in it"),
        "DejaVuSans",
    )
    private val mono = FontRef.Supplied(
        systemFontBytes("DejaVuSansMono.ttf", "This test falls back through a second supplied font"),
        "DejaVuSansMono",
    )
    private val helvetica = FontRef.Standard14(io.pdfkt.core.font.Standard14Name.HELVETICA)

    @Test
    @DisplayName("text the face cannot draw is drawn from the fallback, in runs that change font only where they must")
    fun runsChangeFontWhereTheFaceHasNoGlyph() {
        val runs = runsOf(FontFamily.HELVETICA.withFallbacks(listOf(sans)), "Hello, Привет мир!")

        assertEquals(
            listOf("Hello, " to helvetica, "Привет мир" to sans, "!" to helvetica),
            runs.map { it.text to it.font },
        )
    }

    @Test
    @DisplayName("the first fallback that has the character draws it, past one that has not")
    fun theFirstFallbackWithTheCharacterDrawsIt() {
        val times = FontRef.Standard14(io.pdfkt.core.font.Standard14Name.TIMES_ROMAN)

        // Times has no Cyrillic, so it is passed over; of the two that have it, the first draws it.
        val runs = runsOf(FontFamily.HELVETICA.withFallbacks(listOf(times, mono, sans)), "a ж b")

        assertEquals(listOf("a " to helvetica, "ж " to mono, "b" to helvetica), runs.map { it.text to it.font })
    }

    @Test
    @DisplayName("a fallback the text never needs is not written into the file")
    fun anUnusedFallbackIsNotWritten() {
        val pdf = document {
            clock = PINNED_CLOCK
            theme = Theme(bodyFamily = FontFamily.HELVETICA.withFallbacks(listOf(sans)))
            paragraph("Latin only")
        }.toByteArray().decodeToString()

        assertFalse("/Type0" in pdf || "/FontFile2" in pdf, "a font the text did not need was embedded")
        assertTrue("/Helvetica" in pdf)
    }

    @Test
    @DisplayName("a character no font of the family has is refused as it is without fallbacks")
    fun aCharacterNoFontHasIsRefused() {
        val refused = assertFailsWith<UnencodableCharacterException> {
            runsOf(FontFamily.HELVETICA.withFallbacks(listOf(sans)), "Привет 中")
        }
        assertTrue("中" in refused.message.orEmpty() || "4E2D" in refused.message.orEmpty(), refused.message)
    }

    @Test
    @DisplayName("an emoji font is not a fallback for text")
    fun anEmojiFontIsNotAFallback() {
        assertFailsWith<IllegalArgumentException> {
            FontFamily.HELVETICA.withFallbacks(listOf(FontRef.RasterizedEmoji({ _, _ -> null }, "emoji", 64)))
        }
    }

    private fun runsOf(family: FontFamily, text: String): List<StyledTextRun> {
        val doc = document {
            clock = PINNED_CLOCK
            paragraph(text)
        }
        val styled = Resolver(Theme(bodyFamily = family), FontCatalog()).resolve(doc.toDocumentSpec().content)
        return (styled.single() as StyledParagraph).content.filterIsInstance<StyledTextRun>()
    }
}
