/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.doc

import io.pdfkt.core.content.ContentStreamBuilder
import io.pdfkt.core.font.FontCatalog
import io.pdfkt.core.font.FontRef
import io.pdfkt.core.font.UnencodableCharacterException
import io.pdfkt.core.font.embeddedProgramIn
import io.pdfkt.core.font.truetype.TrueTypeFont
import io.pdfkt.core.font.truetype.systemFontBytes
import io.pdfkt.core.obj.PdfDict
import io.pdfkt.core.obj.PdfName
import io.pdfkt.core.obj.assertQpdfCheckIsClean
import io.pdfkt.core.obj.extractedText
import io.pdfkt.core.obj.skipWithoutPdftotext
import io.pdfkt.core.obj.skipWithoutQpdf
import kotlinx.io.asSink
import kotlinx.io.buffered
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// ISO 32000-2:2020 §7.5 "File structure", §7.7 "Document structure",
// §7.8.2 "Content streams", §9.4.3 "Text-showing operators",
// §9.7 "Composite fonts", §9.7.5.2 "Identity CMaps",
// §9.9.2 "Font subsets", §9.10.3 "ToUnicode CMaps"

/**
 * The whole embedded path, end to end: a page of Russian, a font program the
 * caller supplied, and a reader that never saw this library reading the text
 * back off it.
 *
 * This is the gate on the embedded path. Every piece under it has its own test against
 * its own clause — the TrueType tables, the subsetter, the CMap, the Type 0
 * dictionaries, the file writer. What none of them can show is that a caller
 * who supplies bytes and draws a string gets a file that says that string:
 *
 * - the character codes on the page are glyph indices of the supplied program
 *   and mean nothing on their own (§9.7.5.2), so the text comes back only if
 *   the `ToUnicode` CMap of §9.10.3 is right;
 * - the glyphs those codes select come out of a subset (§9.9.2) renumbered
 *   against the original, so the letters are right only if the `CIDToGIDMap`
 *   agrees with the subset the same document wrote;
 * - the widths a reader advances by come from the `W` array, cut from the same
 *   `hmtx` the library measured with, so the words separate only if those agree.
 *
 * Any one of the three wrong and the file still opens, still passes
 * `qpdf --check`, and shows or extracts the wrong thing. `pdftotext` is what
 * distinguishes them, and it is used as a *tool*: what is compared is the text
 * it reports, never its source.
 *
 * The font is the system's DejaVu Sans — no binary is committed, and a
 * missing one fails rather than skips (`systemFontBytes`).
 */
class CyrillicHelloWorldTest {
    private val sans: ByteArray =
        systemFontBytes("DejaVuSans.ttf", "This test draws Cyrillic, which needs an embedded font program")

    /**
     * Hello world, in Russian: the shortest statement of what the whole
     * embedded path is for.
     *
     * None of these characters exists in any encoding of the fourteen fonts of
     * §9.6.2.2 (Annex D), so a document containing them is either an embedded
     * font or a blank page — and a fully blank Cyrillic document with every
     * test green is exactly the failure mode an embedding bug produces.
     * This is the check that fails when that happens.
     */
    @Test
    fun `a Russian greeting drawn through a supplied font is extracted back exactly`() {
        if (skipWithoutQpdf("§9.7")) return
        if (skipWithoutPdftotext("§9.10.3")) return

        withDocument(GREETING, GREETING_SIZE) { path ->
            assertQpdfCheckIsClean(path)
            assertEquals(GREETING, extractedText(path))
        }
    }

    /**
     * The same round trip over a text that uses most of the alphabet, both
     * cases, the letters built out of components, and the punctuation and
     * spaces between them.
     *
     * `Съешь ещё…` is the Russian pangram; `й` is a composite glyph —
     * DejaVu draws it out of `и` and a breve, and a
     * subset that dropped the components would draw whatever the renumbered
     * indices landed on. The spaces are part of the assertion too: they are
     * shown glyphs like any other, and a `W` array that lost them would run the
     * words together in the extracted text.
     */
    @Test
    fun `a Russian pangram survives the round trip through subset and CMap`() {
        if (skipWithoutQpdf("§9.9.2")) return
        if (skipWithoutPdftotext("§9.10.3")) return

        withDocument(PANGRAM, PANGRAM_SIZE) { path ->
            assertQpdfCheckIsClean(path)
            assertEquals(PANGRAM, extractedText(path))
        }
    }

    /**
     * That the text came out of the `ToUnicode` CMap rather than out of the
     * file happening to carry it.
     *
     * A page shows "pairs of bytes representing CIDs" (§9.7.5.2) — glyph
     * indices of DejaVu Sans, which stand for nothing without the CMap. So the
     * string that was drawn is nowhere in the file, in either of the two
     * encodings a search would try, and a reader still reports it.
     */
    @Test
    fun `the text is nowhere in the file and is still what a reader reports`() {
        if (skipWithoutQpdf("§9.7.5.2")) return
        if (skipWithoutPdftotext("§9.10.3")) return

        withDocument(GREETING, GREETING_SIZE) { path ->
            val file = Files.readAllBytes(path)
            assertFalse(
                indexOf(file, GREETING.encodeToByteArray()) >= 0,
                "the drawn string is in the file as UTF-8: the page is not showing glyph indices",
            )
            assertFalse(
                indexOf(file, utf16Be(GREETING)) >= 0,
                "the drawn string is in the file as UTF-16BE: the page is not showing glyph indices",
            )
            assertEquals(GREETING, extractedText(path))
        }
    }

    /**
     * The `FontFile2` of Table 125 against the file it was cut from: smaller
     * than the program the caller supplied, and larger for a document that drew
     * more distinct letters.
     *
     * "Font subsets" (§9.9.2) does not say a subset must be smaller — it says
     * what a subset is and how it is named. Being smaller is the whole point of
     * doing it, and growing with the glyphs drawn is what shows the file
     * carries the glyphs of *this* document rather than a fixed slice of the
     * font. Both numbers are read back through qpdf, which decodes the stream,
     * so they are the bytes a processor gets rather than the bytes this library
     * thinks it wrote.
     */
    @Test
    fun `the embedded program is smaller than the font supplied and grows with the letters drawn`() {
        if (skipWithoutQpdf("§9.9.2")) return

        val greeting = embeddedProgram(GREETING, GREETING_SIZE)
        val pangram = embeddedProgram(PANGRAM, PANGRAM_SIZE)

        assertTrue(
            greeting.size < sans.size,
            "the embedded program is no smaller than the font it was cut from: " +
                "${greeting.size} bytes against ${sans.size}",
        )
        assertTrue(
            greeting.size < pangram.size,
            "the subset did not grow with the letters drawn: ${greeting.size} bytes for " +
                "${distinctLetters(GREETING)} distinct letters, ${pangram.size} for ${distinctLetters(PANGRAM)}",
        )

        // And it grew because it holds more glyphs, not because a length
        // happened to change: the count is the subset's own maxp.
        val whole = TrueTypeFont.parse(sans)
        assertTrue(
            TrueTypeFont.parse(greeting).numGlyphs < TrueTypeFont.parse(pangram).numGlyphs,
            "the two subsets hold the same number of glyphs",
        )
        assertTrue(
            TrueTypeFont.parse(pangram).numGlyphs < whole.numGlyphs,
            "the subset of a pangram holds every glyph of the font",
        )
    }

    /**
     * The no-silent-loss policy at the end of the path it protects: a glyph the
     * current font cannot render is an error carrying the offending character
     * and its position — never an empty page or a dropped character.
     *
     * DejaVu Sans has no CJK. Asking it for one is the case where a library
     * that guessed would produce a page with a hole in it and a green suite.
     */
    @Test
    fun `a character the supplied program has no glyph for is refused rather than dropped`() {
        val fonts = FontCatalog()
        val font = fonts.resolve(FontRef.Supplied(sans, FONT_ID))

        val refused = assertFailsWith<UnencodableCharacterException> { font.encode(BEYOND_THE_FONT) }
        assertEquals("世", refused.character)
        assertEquals(BEYOND_THE_FONT.indexOf('世'), refused.index)
        assertEquals(FONT_ID, refused.fontName)
    }

    // ---- Writing the document ----------------------------------------------

    /**
     * One US Letter page showing [text] in DejaVu Sans at [size], written
     * through every layer the library has, and [block] run on the file.
     *
     * The text sits an inch from the left edge and an inch below the top
     * (§8.3.2.3: a default user space unit is 1/72 inch). Nothing here spells
     * out a character code: the resource name `Tf` selects, the codes `Tj`
     * shows and the dictionary `/Resources` names all come from one
     * [io.pdfkt.core.font.ResolvedFont], which is the wiring under test.
     *
     * The font is written after the page is drawn, as it must be: the subset is
     * cut from the glyphs the pages showed.
     */
    private fun withDocument(text: String, size: Double, block: (Path) -> Unit) {
        val path = Files.createTempFile("pdfkt-cyrillic-hello-world", ".pdf")
        try {
            val fonts = FontCatalog()
            val font = fonts.resolve(FontRef.Supplied(sans, FONT_ID))

            val content = ContentStreamBuilder()
            content.beginText()
            content.textFont(font.resourceName, size)
            content.nextLine(MARGIN, TOP)
            content.showText(font.encode(text))
            content.endText()

            Files.newOutputStream(path).asSink().buffered().use { sink ->
                val document = PdfDocumentWriter(sink)
                document.addPage(
                    mediaBox = pdfRectangle(0.0, 0.0, LETTER_WIDTH, LETTER_HEIGHT),
                    resources = PdfDict(mapOf(PdfName("Font") to fonts.fontResources(document::define))),
                    content = content.build(),
                )
                document.finish(fileIdentifier = FILE_IDENTIFIER)
            }
            block(path)
        } finally {
            path.deleteIfExists()
        }
    }

    /** The font program of a document showing [text], as qpdf decodes it out of the file. */
    private fun embeddedProgram(text: String, size: Double): ByteArray {
        var program = ByteArray(0)
        withDocument(text, size) { path -> program = embeddedProgramIn(path) }
        return program
    }

    private fun distinctLetters(text: String): Int = text.toSet().size

    /** [text] in the encoding a `ToUnicode` destination is written in, for the search below. */
    private fun utf16Be(text: String): ByteArray {
        val bytes = ByteArray(text.length * 2)
        for ((index, unit) in text.withIndex()) {
            bytes[index * 2] = (unit.code ushr 8).toByte()
            bytes[index * 2 + 1] = unit.code.toByte()
        }
        return bytes
    }

    /** Where [needle] occurs in [haystack], or -1. */
    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        outer@ for (start in 0..haystack.size - needle.size) {
            for (at in needle.indices) if (haystack[start + at] != needle[at]) continue@outer
            return start
        }
        return -1
    }

    private companion object {
        /** Hello world, in Russian. */
        const val GREETING: String = "Привет, мир!"

        /**
         * The Russian pangram: most of the alphabet, both cases, `й` among them
         * — a composite glyph, the classic case a subsetter gets wrong.
         */
        const val PANGRAM: String = "Съешь ещё этих мягких французских булок, да выпей чаю"

        const val FONT_ID: String = "DejaVuSans"

        /** Russian DejaVu Sans can draw, and two characters it has no glyph for. */
        const val BEYOND_THE_FONT: String = "Привет, 世界"

        /** Sizes at which each string fits between the margins of a US Letter page. */
        const val GREETING_SIZE: Double = 24.0
        const val PANGRAM_SIZE: Double = 12.0

        /** One inch (§8.3.2.3), and the same from the top of the page. */
        const val MARGIN: Double = 72.0
        const val TOP: Double = 720.0

        const val LETTER_WIDTH: Double = 612.0
        const val LETTER_HEIGHT: Double = 792.0

        /**
         * "Each PDF file identifier byte-string shall have a minimum length of
         * 16 bytes" (Table 15). Pinned, so the file this asserts about is the
         * same file every run (§14.4).
         */
        val FILE_IDENTIFIER: ByteArray = ByteArray(16) { (0x30 + it).toByte() }
    }
}
