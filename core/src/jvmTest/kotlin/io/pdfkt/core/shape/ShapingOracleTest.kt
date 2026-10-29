/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.shape

import io.pdfkt.core.font.truetype.TrueTypeFont
import io.pdfkt.core.font.truetype.systemFontBytes
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.assertTrue

// OpenType 1.9.1 §"GSUB", §"GPOS"; the Microsoft script development
// specifications. The other side of the comparison is HarfBuzz's hb-shape, a
// shaping engine written by other people against the same specifications.

/**
 * The [Shaper] against `hb-shape`, over the text of `shaping-corpus.txt`: for
 * every line, the same glyphs at the same positions with the same advances.
 *
 * The fonts are the system fonts of the platforms PDFolio runs on, read from
 * `PDFKT_TEST_FONT_DIR`; a missing one fails the test.
 */
class ShapingOracleTest {
    @Test
    @DisplayName("every line of the corpus shapes as hb-shape shapes it")
    fun theCorpusShapesAsHarfBuzzShapesIt() {
        if (!hasHbShape()) return
        val corpus = requireNotNull(javaClass.getResource("/shaping-corpus.txt")).readText()
            .lines().filter { it.isNotBlank() && !it.startsWith("#") }
        val fonts = HashMap<Pair<String, Int>, Shaper>()
        val failures = ArrayList<String>()
        for (line in corpus) {
            val (file, index, direction, escaped) = line.split('|', limit = 4)
            val text = unescape(escaped)
            val shaper = fonts.getOrPut(file to index.toInt()) {
                val bytes = systemFontBytes(file, "Shaping is checked", "the macOS and Android system fonts")
                Shaper(layoutFontOf(TrueTypeFont.parse(bytes, index.toInt())))
            }
            val ours = format(shaper.shape(text, direction == "rtl"))
            // hb-shape draws a default ignorable character as the font's space
            // with no advance; this library leaves it out, which draws the same.
            val space = TrueTypeFont.parse(systemFontBytes(file, "", ""), index.toInt()).glyphIdOf(0x20)
            val theirs = harfBuzz(fontPath(file), index.toInt(), direction, text)
                .removePrefix("[").removeSuffix("]").split("|").filter { it != "$space+0" }.joinToString("|", "[", "]")
            if (ours != theirs) failures += "$file \"$text\"\n   ours $ours\n theirs $theirs"
        }
        assertTrue(
            failures.isEmpty(),
            "${failures.size} of ${corpus.size} lines differ:\n" + failures.joinToString("\n"),
        )
    }

    private fun format(glyphs: List<ShapedGlyph>): String = glyphs.joinToString("|", "[", "]") {
        val offset = if (it.xOffset != 0 || it.yOffset != 0) "@${it.xOffset},${it.yOffset}" else ""
        "${it.id}$offset+${it.xAdvance}"
    }

    private fun harfBuzz(font: String, index: Int, direction: String, text: String): String {
        val process = ProcessBuilder(
            "hb-shape",
            "--no-glyph-names",
            "--no-clusters",
            "--direction=$direction",
            "--face-index=$index",
            font,
            text,
        ).redirectErrorStream(true).start()
        val output = process.inputStream.readBytes().decodeToString().trim()
        assertTrue(process.waitFor(1, TimeUnit.MINUTES), "hb-shape did not finish")
        return output
    }

    private fun fontPath(file: String): String =
        listOfNotNull(System.getenv("PDFKT_TEST_FONT_DIR")).map { Path.of(it, file) }
            .first { Files.isReadable(it) }.toString()

    private fun unescape(text: String): String =
        Regex("\\\\u([0-9A-Fa-f]{4})").replace(text) { it.groupValues[1].toInt(16).toChar().toString() }

    private fun hasHbShape(): Boolean = try {
        ProcessBuilder("hb-shape", "--version").start().waitFor() == 0
    } catch (absent: IOException) {
        println("hb-shape is not installed — skipping the shaping comparison")
        false
    }
}
