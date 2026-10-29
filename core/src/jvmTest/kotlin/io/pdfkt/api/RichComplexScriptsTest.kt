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
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// ISO 32000-2:2020 §9.4.3 (TJ), §9.10.3 "ToUnicode CMaps", §14.9.4
// "Replacement text" (ActualText), §9.9 Table 125 (CIDFontType0C); UAX #9;
// the Microsoft script development specifications.

/**
 * Text in the scripts PDFolio's users write, set through the DSL with fonts
 * of the systems it runs on as fallbacks of a Latin face — the arrangement the
 * app uses — and read back from the file.
 */
class RichComplexScriptsTest {
    private fun font(file: String, id: String, index: Int = 0): FontRef.Supplied =
        FontRef.Supplied(systemFontBytes(file, "Complex scripts are checked", "the system fonts"), id, index)

    private val family: FontFamily by lazy {
        FontFamily.of(font("DejaVuSans.ttf", "sans")).withFallbacks(
            listOf(
                font("android/NotoNaskhArabic-Regular.ttf", "naskh"),
                font("android/NotoSansHebrew-Regular.ttf", "hebrew"),
                font("android/NotoSansDevanagari-VF.ttf", "deva"),
                font("android/NotoSansBengali-VF.ttf", "beng"),
                font("android/NotoSansTamil-VF.ttf", "taml"),
                font("android/NotoSansMalayalam-VF.ttf", "mlym"),
                font("android/NotoSansCJK-Regular.ttc", "cjk"),
            ),
        )
    }

    private fun page(vararg paragraphs: String, align: TextAlign = TextAlign.Start): Document = document {
        clock = PINNED_CLOCK
        theme = Theme(bodyFamily = family)
        for (text in paragraphs) paragraph(text, align = align)
    }

    @Test
    @DisplayName("Indic, CJK and Hebrew text reads back from the file as it was written")
    fun theTextReadsBack() {
        if (skipWithoutPdftotext("§14.9.4") || skipWithoutQpdf("§9.7")) return
        val lines = listOf(
            "सभी मनुष्यों को गौरव और अधिकारों के मामले में जन्मजात स्वतन्त्रता प्राप्त है।",
            "সমস্ত মানুষ স্বাধীনভাবে সমান মর্যাদা এবং অধিকার নিয়ে জন্মগ্রহণ করে।",
            "மனிதப் பிறவியினர் சகலரும் சுதந்திரமாகவே பிறக்கின்றனர்",
            "മനുഷ്യരെല്ലാവരും തുല്യാവകാശങ്ങളോടും",
            "人人生而自由，在尊严和权利上一律平等。",
            "כל בני האדם נולדו בני חורין ושווים בערכם ובזכויותיהם",
        )
        withRendered(page(*lines.toTypedArray())) { path ->
            assertQpdfCheckIsClean(path)
            // pdftotext brackets a right-to-left line in embedding marks of its own.
            val read = linesOf(extractedText(path)).map { line -> line.filterNot { it.code in BIDI_MARKS } }
            assertEquals(lines, read)
        }
    }

    @Test
    @DisplayName("a font with CFF2 outlines is embedded as a subset of the glyphs the page draws")
    fun aCjkPageCarriesASmallSubset() {
        val pdf = page("人人生而自由，在尊严和权利上一律平等。").toByteArray()
        val text = pdf.decodeToString()

        assertTrue("/CIDFontType0C" in text && "/CIDFontType0" in text, "the CJK font is not a CFF CIDFont")
        assertTrue(pdf.size < SMALL_PDF, "a page of twenty ideographs is ${pdf.size} bytes")
    }

    @Test
    @DisplayName("an Arabic paragraph runs right to left and starts at the right of its column")
    fun anArabicParagraphIsSetFromTheRight() {
        if (skipWithoutPdftoppm("§9.4")) return
        withRendered(page("مرحبا بالعالم")) { path ->
            val raster = rasterisePage(path, 1)
            val band = raster.inkBands().first()
            val ink = assertNotNull(raster.inkColumns(band))
            // A4 at the raster's resolution: the margins are equal, so a line
            // set from the right ends where a left-aligned one would start.
            assertTrue(ink.first > raster.width / 2, "the Arabic line starts at column ${ink.first} of ${raster.width}")
            assertTrue(
                raster.width - ink.last < raster.width / 6,
                "the Arabic line ends at ${ink.last} of ${raster.width}",
            )
        }
    }

    @Test
    @DisplayName("lam and alef are drawn as the one glyph of their ligature, which reads back as both")
    fun arabicIsShaped() {
        if (skipWithoutQpdf("§9.10.3")) return
        withRendered(page("لا")) { path ->
            val unpacked = Files.createTempFile("pdfkt-qdf", ".pdf")
            try {
                assertEquals(
                    0,
                    tool("qpdf", "--qdf", "--object-streams=disable", path.toString(), unpacked.toString()).status,
                )
                val file = Files.readAllBytes(unpacked).decodeToString()
                // One glyph shown, and the ToUnicode CMap maps it to LAM, ALEF.
                assertTrue(Regex("<[0-9A-Fa-f]{4}> ?<06440627>").containsMatchIn(file), "no glyph maps to LAM ALEF")
            } finally {
                Files.deleteIfExists(unpacked)
            }
        }
    }

    private companion object {
        const val SMALL_PDF: Int = 40_000

        /** U+202A to U+202E, the embedding and override marks. */
        val BIDI_MARKS: IntRange = 0x202A..0x202E
    }
}
