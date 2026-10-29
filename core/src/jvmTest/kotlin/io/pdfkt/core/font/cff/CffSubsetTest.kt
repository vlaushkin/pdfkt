/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.font.cff

import io.pdfkt.core.font.truetype.TrueTypeFont
import io.pdfkt.core.font.truetype.systemFontBytes
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.io.path.deleteIfExists
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Adobe Technical Note #5176 "The Compact Font Format Specification" §18
// "CID-keyed Fonts"; #5177 "The Type 2 Charstring Format" §4.7 "Subroutine
// Operators"; OpenType 1.9.1 §"CFF2". The check reads the subset back with
// fontTools, which is a reader written by other people against the same
// specifications.

/**
 * [CffSubset] against fonts of each kind it is written for, from the systems
 * PDFolio runs on: name-keyed CFF (Kohinoor, a Devanagari face of macOS),
 * CID-keyed CFF (Hiragino Sans GB, Apple SD Gothic Neo) and CFF2 (Noto Sans
 * CJK of Android, a variable font).
 *
 * Every glyph of every font is flattened, so a charstring this library cannot
 * read fails here rather than in a document; a sample of them is written as a
 * subset and drawn, glyph for glyph, against the source font at its default
 * instance.
 */
class CffSubsetTest {
    @ParameterizedTest(name = "{0} font {1}")
    @MethodSource("fonts")
    @DisplayName("every charstring of the font flattens to a self-contained one")
    fun everyGlyphFlattens(file: String, index: Int) {
        val font = cffOf(file, index)
        val flattener = CharstringFlattener(font)
        for (glyph in 0 until font.numGlyphs) {
            val charstring = flattener.flatten(glyph)
            assertEquals(ENDCHAR, charstring.last().toInt(), "glyph $glyph does not end with endchar")
        }
    }

    @ParameterizedTest(name = "{0} font {1}")
    @MethodSource("fonts")
    @DisplayName("a subset draws every glyph it keeps exactly as the font does")
    fun theSubsetDrawsWhatTheFontDraws(file: String, index: Int) {
        val python = System.getenv("PDFKT_FONTTOOLS_PYTHON") ?: "python3"
        if (!hasFontTools(python)) return
        val font = cffOf(file, index)
        // Glyphs from all over the font: different Font DICTs, different
        // subroutines, and with them the hint masks of glyphs with many stems.
        val sample = (1 until font.numGlyphs step maxOf(1, font.numGlyphs / SAMPLE)).toList()
        val subset = CffSubset.of(font, sample, "ABCDEF+Test")

        val subsetFile = Files.createTempFile("pdfkt-subset", ".cff")
        try {
            Files.write(subsetFile, subset)
            val script = requireNotNull(javaClass.getResource("/cff_outline_check.py")).path
            val fontFile = fontPath(file)
            val process = ProcessBuilder(python, script, fontFile, index.toString(), subsetFile.toString())
                .redirectErrorStream(true).start()
            val output = process.inputStream.readBytes().decodeToString()
            assertTrue(process.waitFor(5, TimeUnit.MINUTES), "the check did not finish")
            assertEquals(0, process.exitValue(), output)
            assertEquals("ok ${sample.size + 1}", output.trim())
        } finally {
            subsetFile.deleteIfExists()
        }
    }

    private fun cffOf(file: String, index: Int): CffFont {
        val bytes = systemFontBytes(file, "The CFF subset is checked", "the macOS and Android system fonts")
        val directory = TrueTypeFont.parse(bytes, index).directory
        return directory.readerOrNull("CFF ")?.let { CffFont.parseCff(it) }
            ?: CffFont.parseCff2(directory.readerOf("CFF2"))
    }

    private fun fontPath(file: String): String =
        listOfNotNull(System.getenv("PDFKT_TEST_FONT_DIR")).map { java.nio.file.Path.of(it, file) }
            .first { Files.isReadable(it) }.toString()

    private fun hasFontTools(python: String): Boolean {
        val present = try {
            ProcessBuilder(python, "-c", "import fontTools").start().waitFor() == 0
        } catch (absent: IOException) {
            false
        }
        if (!present) println("fontTools is not importable by $python — skipping the outline check")
        return present
    }

    companion object {
        const val ENDCHAR = 14
        const val SAMPLE = 400

        @JvmStatic
        fun fonts(): List<Arguments> = listOf(
            Arguments.of("macos/Kohinoor.ttc", 0),
            Arguments.of("macos/Hiragino Sans GB.ttc", 0),
            Arguments.of("macos/AppleSDGothicNeo.ttc", 0),
            Arguments.of("macos/NotoSansCanadianAboriginal-Regular.otf", 0),
            Arguments.of("android/NotoSansCJK-Regular.ttc", 0),
            Arguments.of("android/NotoSansCJK-Regular.ttc", 2),
        )
    }
}
