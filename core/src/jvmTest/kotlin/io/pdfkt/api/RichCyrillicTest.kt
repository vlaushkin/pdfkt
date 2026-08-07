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
import io.pdfkt.core.font.UnencodableCharacterException
import io.pdfkt.core.style.FontFamily
import io.pdfkt.core.style.Theme
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// ISO 32000-2:2020 §9.7 "Composite fonts", §9.7.5.2 "Identity CMaps",
// §9.9.2 "Font subsets", §9.10.3 "ToUnicode CMaps". Which font a block is set in
// is the theme's, which is this library's own design.

/**
 * A document of Russian written through the DSL, set in a font program the
 * caller supplied — the one thing the fourteen standard fonts of §9.6.2.2 cannot
 * do, reached the way a caller reaches it: `theme = Theme.Default.copy(...)`.
 *
 * `:core` proves the embedded path against its clauses — the subset, the CMap,
 * the `W` array, the Type 0 dictionaries. What this adds is that a caller who
 * never leaves the facade gets it: the family is set on the theme, the words go
 * in as ordinary blocks, and every letter comes back out of a reader.
 *
 * A fully blank Cyrillic document with every test green is the failure mode
 * this file exists to rule out. Both halves of the answer to it are here —
 * the text extracts, and asking for the same document *without* a font that can
 * draw it stops the render instead of writing a page with a hole in it.
 *
 * The font is the system's DejaVu Sans — no binary is committed, and a missing
 * one fails rather than skips.
 */
class RichCyrillicTest {
    private val sans: ByteArray =
        systemFontBytes("DejaVuSans.ttf", "This test draws Cyrillic, which needs an embedded font program")

    private val family: FontFamily = FontFamily.of(FontRef.Supplied(sans, FONT_ID))

    @Test
    @DisplayName("every Russian line written through the DSL is extracted back exactly")
    fun theRussianDocumentIsExtractedBackAsItWasWritten() {
        if (skipWithoutQpdf("§9.7")) return
        if (skipWithoutPdftotext("§9.10.3")) return

        withRendered(russianDocument()) { path ->
            assertQpdfCheckIsClean(path)
            // The extractor is asked to keep the arrangement of the page, so the
            // gap it reports between a marker and the line it marks is however
            // wide that gap is drawn. What is being asserted is the words and
            // the lines they are on, so the runs of spaces are levelled first.
            val lines = linesOf(extractedPages(path).single()).map { line -> wordsOf(line).joinToString(" ") }
            assertEquals(expectedLines(), lines, "the Russian document does not read back as it was written")
        }
    }

    /**
     * That the text came out of the `ToUnicode` CMap rather than out of the file
     * happening to carry it.
     *
     * A page shows "pairs of bytes representing CIDs" (§9.7.5.2) — glyph indices
     * of the supplied program, which stand for nothing without the CMap. So the
     * heading that was drawn is nowhere in the file, in either of the two
     * encodings a search would try, and a reader still reports it.
     */
    @Test
    @DisplayName("the drawn text is nowhere in the file and is still what a reader reports")
    fun theTextIsNowhereInTheFileAndIsStillWhatAReaderReports() {
        if (skipWithoutPdftotext("§9.10.3")) return

        withRendered(russianDocument()) { path ->
            val file = Files.readAllBytes(path)
            assertFalse(
                contains(file, HEADING.encodeToByteArray()),
                "the drawn string is in the file as UTF-8: the page is not showing glyph indices",
            )
            assertFalse(
                contains(file, utf16Be(HEADING)),
                "the drawn string is in the file as UTF-16BE: the page is not showing glyph indices",
            )
            assertTrue(extractedText(path).contains(HEADING), "the reader does not report the heading")
        }
    }

    /**
     * And the letters are on the page, not only in the text layer: a document
     * that extracted correctly off an invisible page would pass every check
     * above.
     */
    @Test
    @DisplayName("the page carries one band of ink per line of Russian")
    fun theRussianPageIsNotBlank() {
        if (skipWithoutPdftoppm("§8.5")) return

        withRendered(russianDocument()) { path ->
            assertEquals(
                expectedLines().size,
                rasterisePage(path, 1).inkBands().size,
                "the page of Russian does not carry a band of ink per line of it",
            )
        }
    }

    /**
     * The no-silent-loss policy at the facade's
     * own door: the same document with the theme left alone is refused, naming
     * the character and where it was.
     *
     * This is the check that fails if blank Cyrillic pages ever come back. It is
     * also why the test above is worth its cost: without this one, a library that
     * dropped every unencodable character would pass by writing nothing at all.
     */
    @Test
    @DisplayName("Russian in a font that cannot draw it is refused rather than dropped")
    fun russianWithoutASuppliedFontIsRefused() {
        val refused = assertFailsWith<UnencodableCharacterException> {
            document {
                clock = PINNED_CLOCK
                paragraph(GREETING)
            }.toByteArray()
        }

        assertEquals("П", refused.character, "the error names a different character")
        assertEquals(0, refused.index)
    }

    // ------------------------------------------------------------- the document

    /**
     * The document under test: a heading, a paragraph and a list, all Russian,
     * all set in the supplied family.
     *
     * Every level of heading takes the family, not only the one used here: a
     * theme answers for all six (`Theme.headingFamilies`), and a document that
     * happened to use a seventh style of block would otherwise be set in a font
     * without the letters for it.
     */
    private fun russianDocument(): Document = document {
        clock = PINNED_CLOCK
        theme = Theme.Default.copy(
            bodyFamily = family,
            headingFamilies = (1..HEADING_LEVELS).associateWith { family },
            codeFamily = family,
        )

        heading(level = 1, HEADING)
        paragraph(PANGRAM)
        bulletList {
            for (text in ITEMS) item(text)
        }
    }

    /** The lines the document is printed as: the marker of a list item is drawn beside its text. */
    private fun expectedLines(): List<String> = listOf(HEADING, PANGRAM) + ITEMS.map { item -> "$BULLET $item" }

    /** [text] in the encoding a `ToUnicode` destination is written in, for the search above. */
    private fun utf16Be(text: String): ByteArray {
        val bytes = ByteArray(text.length * 2)
        for ((index, unit) in text.withIndex()) {
            bytes[index * 2] = (unit.code ushr Byte.SIZE_BITS).toByte()
            bytes[index * 2 + 1] = unit.code.toByte()
        }
        return bytes
    }

    /** Whether [needle] occurs anywhere in [haystack]. */
    private fun contains(haystack: ByteArray, needle: ByteArray): Boolean {
        outer@ for (start in 0..haystack.size - needle.size) {
            for (at in needle.indices) if (haystack[start + at] != needle[at]) continue@outer
            return true
        }
        return false
    }

    private companion object {
        const val FONT_ID: String = "DejaVuSans"

        const val HEADING: String = "Отчёт за квартал"

        /**
         * The Russian pangram: most of the alphabet, both cases, `й` among them —
         * a composite glyph, the classic case a subsetter gets wrong.
         */
        const val PANGRAM: String = "Съешь ещё этих мягких французских булок, да выпей чаю"

        val ITEMS: List<String> = listOf("Продажи выросли", "Расходы снизились")

        /** Hello world, in Russian — the shortest thing the standard fonts cannot draw. */
        const val GREETING: String = "Привет, мир!"

        /** What an unordered list is marked with (`ListMarkerStyle.Bullet`). */
        const val BULLET: String = "•"

        /** What a `Theme` answers for (`HeadingBlock.MIN_LEVEL..MAX_LEVEL`). */
        const val HEADING_LEVELS: Int = 6
    }
}
