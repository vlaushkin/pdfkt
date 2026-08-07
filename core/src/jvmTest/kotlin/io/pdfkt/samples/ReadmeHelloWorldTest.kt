/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.samples

import io.pdfkt.api.*
import kotlinx.io.asSink
import kotlinx.io.buffered
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// ISO 32000-2:2020 §7.5.2 "File header", §7.5.5 "File trailer" — the two
// markers this test reads out of the file's own bytes. Nothing here implements
// a clause: what is under test is that the README's example is true.

/**
 * The README's hello world, run.
 *
 * A README example is documentation that rots silently: it keeps reading well
 * long after the API it describes has moved, because nothing compiles it. This
 * file compiles it, runs it, and checks that it is still the *same* example —
 * `theExampleIsTheOneTheReadmeGives` reads `README.md` off the disk and requires
 * every line of its fenced Kotlin block to appear, verbatim and in order,
 * between the two markers below.
 *
 * Two kinds of line are dropped before that comparison, and only two: the
 * `import` lines, which a Kotlin file carries at the top and not in the middle
 * of a function, and the one line binding `logo` — a picture the reader supplies
 * and this test encodes for itself. Everything else — the builder, every verb in
 * it, and the line that writes the file — is compared character for character.
 *
 * The document really is written, to `report.pdf` beside this module, because
 * that is the path the README gives and a path that is not the README's is a
 * different example. It is removed again whether the test passes or fails.
 */
class ReadmeHelloWorldTest {

    /** The picture the example draws, encoded here rather than committed. */
    private val logo: ByteArray = pngBytes()

    @Test
    @DisplayName("the README's hello world writes a PDF a reader accepts")
    fun theReadmesHelloWorldWritesAPdf() {
        val report = File(REPORT)
        try {
            // README-BEGIN
            val doc = document {
                clock = Clock { PdfDate.ofEpochSeconds(System.currentTimeMillis() / 1000) }

                heading(1, "Q4 2026 summary")
                paragraph {
                    text("Revenue: ") { bold = true }
                    text("up 12% on the quarter")
                }
                table {
                    header {
                        cell("Metric")
                        cell("Value")
                    }
                    row {
                        cell("Revenue")
                        cell("1 310")
                    }
                }
                image(logo) // the bytes decide the format; fit and rotation are optional
            }

            File("report.pdf").outputStream().asSink().buffered().use { doc.writeTo(it) }
            // README-END

            val written = report.readBytes()
            val text = written.decodeToString()
            assertTrue(text.startsWith("%PDF-"), "a PDF file begins with its header (§7.5.2)")
            assertTrue(text.trimEnd().endsWith("%%EOF"), "and ends with the end-of-file marker (§7.5.5)")
            assertTrue(
                written.size > logo.size,
                "the example wrote ${written.size} bytes, less than the picture in it",
            )

            if (qpdfIsInstalled()) {
                val check = run("qpdf", "--check", report.absolutePath)
                assertEquals(0, check.status, "qpdf rejected the file the README's example writes:\n${check.output}")
            }
        } finally {
            report.delete()
        }
    }

    @Test
    @DisplayName("the example above is the one the README gives")
    fun theExampleIsTheOneTheReadmeGives() {
        val readme = readmeExample()
        assertTrue(readme.isNotEmpty(), "README.md has no fenced Kotlin block to check")

        val here = markedRegion()
        assertEquals(
            readme.joinToString("\n"),
            here.joinToString("\n"),
            "the README's example and the one this test runs have drifted apart",
        )
    }

    // ---------------------------------------------------------------- reading

    /**
     * The README's fenced Kotlin block, less its imports and the line binding
     * `logo`, with trailing blanks trimmed.
     */
    private fun readmeExample(): List<String> {
        val lines = repositoryFile("README.md").readLines()
        val heading = lines.indexOfFirst { line -> line.trim() == HELLO_WORLD }
        assertTrue(heading >= 0, "README.md has no \"$HELLO_WORLD\" section")
        val open = lines.drop(heading).indexOfFirst { line -> line.trim() == FENCE_OPEN } + heading
        assertTrue(open > heading, "the \"$HELLO_WORLD\" section has no ```kotlin block")
        val close = lines.drop(open + 1).indexOfFirst { line -> line.trim() == FENCE_CLOSE } + open + 1
        assertTrue(close > open, "the README's Kotlin block is never closed")
        return lines.subList(open + 1, close)
            .filterNot(::supplied)
            .dropWhile(String::isBlank)
            .dropLastWhile(String::isBlank)
    }

    /** The lines this test supplies for the reader: the imports and the picture. */
    private fun supplied(line: String): Boolean = line.startsWith("import ") || line.startsWith("val logo")

    /**
     * The example as this file runs it: the lines between the two markers, with
     * the indentation of the enclosing `try` block taken back off, so that what
     * is compared is the example and not where in a test it happens to sit.
     */
    private fun markedRegion(): List<String> {
        val lines = repositoryFile(SOURCE).readLines()
        val begin = lines.indexOfFirst { line -> line.trim() == "// README-BEGIN" }
        val end = lines.indexOfFirst { line -> line.trim() == "// README-END" }
        assertTrue(begin >= 0 && end > begin, "the markers are gone from $SOURCE")
        return lines.subList(begin + 1, end)
            .map { line -> if (line.startsWith(INDENT)) line.removePrefix(INDENT) else line }
            .dropWhile(String::isBlank)
            .dropLastWhile(String::isBlank)
    }

    /**
     * [name] as a path from the repository root, whichever directory the tests
     * were started in — Gradle runs them from the module, a developer's runner
     * may not.
     */
    private fun repositoryFile(name: String): File = listOf("..", ".", "../..")
        .map { prefix -> File(prefix, name).absoluteFile }
        .firstOrNull { candidate -> candidate.isFile }
        ?: error("$name is not where this test can reach it, from ${File(".").absolutePath}")

    // ------------------------------------------------------------------ tools

    private class Run(val status: Int, val output: String)

    private fun run(vararg command: String): Run {
        val process = ProcessBuilder(command.toList()).redirectErrorStream(true).start()
        val output = process.inputStream.readBytes().decodeToString()
        assertTrue(process.waitFor(TOOL_TIMEOUT_SECONDS, TimeUnit.SECONDS), "${command.first()} did not finish")
        return Run(process.exitValue(), output)
    }

    private fun qpdfIsInstalled(): Boolean = try {
        run("qpdf", "--version").status == 0
    } catch (absent: java.io.IOException) {
        println("[readme] qpdf is not installed here (${absent.message}); the file was checked by its own bytes only")
        false
    }

    /** A small opaque PNG, encoded by the JDK when the test runs. */
    private fun pngBytes(): ByteArray {
        val image = BufferedImage(LOGO_WIDTH, LOGO_HEIGHT, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        graphics.color = Color(30, 70, 130)
        graphics.fillRect(0, 0, LOGO_WIDTH, LOGO_HEIGHT)
        graphics.dispose()
        val encoded = ByteArrayOutputStream()
        check(ImageIO.write(image, "png", encoded)) { "this JDK has no png encoder to build a fixture with" }
        return encoded.toByteArray()
    }

    private companion object {
        const val REPORT = "report.pdf"
        const val SOURCE = "core/src/jvmTest/kotlin/io/pdfkt/samples/ReadmeHelloWorldTest.kt"
        const val HELLO_WORLD = "## Hello world"
        const val FENCE_OPEN = "```kotlin"
        const val FENCE_CLOSE = "```"

        /** Three levels in: the class, the method, the `try`. */
        const val INDENT = "            "

        const val LOGO_WIDTH = 120
        const val LOGO_HEIGHT = 40

        const val TOOL_TIMEOUT_SECONDS = 30L
    }
}
