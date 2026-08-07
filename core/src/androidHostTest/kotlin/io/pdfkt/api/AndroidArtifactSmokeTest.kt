/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.api

import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// ISO 32000-2:2020 §7.5.2 "File header", §7.5.5 "File trailer",
// §7.4.8 "DCTDecode filter", §7.7.3.2 "Page tree nodes" (Table 30),
// §7.7.3.3 "Page objects" (Table 31).

/**
 * The Android artifact, exercised the way an Android consumer exercises it.
 *
 * `:core` publishes an AAR of the same `commonMain` engine — `androidMain`
 * carries no source, because nothing in this library differs between the JVM
 * and Android. That is a claim about a
 * *compilation*, and a compilation that produced a zip is not a compilation
 * that writes PDF files. This test takes a document of photographs through
 * `document { }` on the Android compilation's own classes and reads the file
 * back.
 *
 * It is a **host** test: it runs on the JDK, needs no device, no emulator and
 * no Robolectric. There is nothing here for an instrumented test to add — no
 * `android.*` call exists on any path this library takes — and demanding one
 * would leave the artifact unchecked on every machine that has no emulator.
 * The photographs are encoded by the JDK's own codec for the same reason the
 * JVM gate encodes them (`RichImageGateTest`): a JPEG arrives already encoded
 * from a camera or a platform codec — on Android, from `Bitmap.compress` — and
 * this library embeds it as it arrived without decoding it (§7.4.8).
 *
 * qpdf is used as a tool, over the bytes of a file we wrote, which is what
 * the isolation rule permits. Where it is not installed the assertions about
 * the file's own bytes still stand: they are not inside the conditional.
 */
class AndroidArtifactSmokeTest {

    @Test
    fun theAndroidArtifactWritesOnePagePerPhotograph() {
        val pdf = photographs().toByteArray()

        val text = pdf.decodeToString()
        assertTrue(text.startsWith("%PDF-"), "a PDF file begins with its header (§7.5.2)")
        assertTrue(text.trimEnd().endsWith("%%EOF"), "and ends with the end-of-file marker (§7.5.5)")
        assertEquals(ALBUM.size, text.occurrencesOf(PAGE_OBJECT), "one page per photograph (Table 31)")
        assertEquals(1, text.occurrencesOf("/Count ${ALBUM.size}"), "and the page tree says so (Table 30)")
        assertEquals(ALBUM.size, text.occurrencesOf("/DCTDecode"), "each photograph embedded as it arrived (§7.4.8)")
        assertEquals(ALBUM.size, text.occurrencesOf("(Page "), "every page carries its number")

        // The size floor is a comparison rather than a magic number: the
        // document carries every photograph's bytes on top of a document's own
        // structure, so a truncated or empty write fails here before qpdf.
        val bare = onePhotograph().toByteArray()
        val photographs = ALBUM.sumOf { photograph -> photograph.size }
        assertTrue(
            pdf.size > bare.size + photographs - ALBUM.first().size,
            "${ALBUM.size} photographs ($photographs bytes of them) came out ${pdf.size} bytes long, " +
                "against ${bare.size} for a document carrying one",
        )

        val qpdf = qpdfOnPath()
        if (qpdf == null) {
            println("[android-smoke] qpdf is not installed here; the file was checked by its own bytes only")
            return
        }
        val path = Files.write(Files.createTempFile("pdfkt-android-album", ".pdf"), pdf).toString()
        assertEquals("", runTool(qpdf, "--check", path).failure, "qpdf --check")
        assertEquals(
            ALBUM.size.toString(),
            runTool(qpdf, "--show-npages", path).output.trim(),
            "the pages qpdf counts in the file",
        )
    }

    /** The document under test: one photograph to a page, under a header and a numbered footer. */
    private fun photographs(): Document = document {
        pageSize = PageSize.A4
        margins = Margins.Fixed(top = MARGIN.pt, right = MARGIN.pt, bottom = MARGIN.pt, left = MARGIN.pt)
        metadata = Metadata(title = "Android smoke document")
        clock = Clock { PdfDate.ofEpochSeconds(PINNED_EPOCH_SECONDS, offsetMinutes = OFFSET_MINUTES) }

        header("pdfkt on Android", align = TextAlign.Center)
        footer { paragraph("Page ${numbering.page} of ${numbering.pages}", align = TextAlign.Right) }

        ALBUM.forEachIndexed { index, photograph ->
            if (index > 0) pageBreak()
            image(photograph)
        }
    }

    /** The same document with one photograph in it, which is what the size floor is measured against. */
    private fun onePhotograph(): Document = document {
        pageSize = PageSize.A4
        margins = Margins.Fixed(top = MARGIN.pt, right = MARGIN.pt, bottom = MARGIN.pt, left = MARGIN.pt)
        metadata = Metadata(title = "Android smoke document")
        clock = Clock { PdfDate.ofEpochSeconds(PINNED_EPOCH_SECONDS, offsetMinutes = OFFSET_MINUTES) }

        header("pdfkt on Android", align = TextAlign.Center)
        footer { paragraph("Page ${numbering.page} of ${numbering.pages}", align = TextAlign.Right) }

        image(ALBUM.first())
    }

    /** How many times [text] occurs, which is how a count is read out of a file without a parser. */
    private fun String.occurrencesOf(text: String): Int = split(text).size - 1

    /**
     * qpdf, if this machine has it.
     *
     * PATH is walked rather than one directory assumed, so the test answers the
     * same question wherever it runs.
     */
    private fun qpdfOnPath(): String? = (System.getenv("PATH") ?: "")
        .split(":")
        .filter { directory -> directory.isNotEmpty() }
        .map { directory -> Path.of(directory, "qpdf") }
        .firstOrNull { candidate -> Files.isExecutable(candidate) }
        ?.toString()

    /** A tool run over the file, with its output and, on a non-zero exit, its own complaint. */
    private fun runTool(vararg command: String): ToolRun {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        process.outputStream.close()
        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        val failure = if (exitCode == 0) "" else "${command.joinToString(" ")} exited $exitCode:\n$output"
        return ToolRun(output, failure)
    }

    private data class ToolRun(val output: String, val failure: String)

    private companion object {
        /**
         * A page object (Table 31). The trailing space is what tells it from
         * the `/Type /Pages` of the tree root (Table 30).
         */
        const val PAGE_OBJECT: String = "/Type /Page "

        const val MARGIN = 24

        /** 2026-08-06T09:30:00 three hours east of UT, so the file carries a non-zero offset. */
        const val PINNED_EPOCH_SECONDS = 1_785_997_800L
        const val OFFSET_MINUTES = 180

        /**
         * The photographs: a landscape, a portrait, a square and a greyscale
         * frame.
         *
         * Four shapes, so that a document drawing one photograph four times
         * cannot pass the length assertion above; which photograph landed on
         * which page is `RichImageGateTest`'s question, asked there of the same
         * engine through the JVM target.
         */
        val ALBUM: List<ByteArray> = listOf(
            photograph(width = 640, height = 426, type = BufferedImage.TYPE_INT_RGB),
            photograph(width = 300, height = 450, type = BufferedImage.TYPE_INT_RGB),
            photograph(width = 512, height = 512, type = BufferedImage.TYPE_INT_RGB),
            photograph(width = 320, height = 240, type = BufferedImage.TYPE_BYTE_GRAY),
        )

        /**
         * A JPEG of the given shape, encoded here rather than committed.
         *
         * Painted in stripes rather than filled flat: a frame of one colour
         * encodes to almost nothing, and a scan of almost nothing says nothing
         * about whether the photograph reached the page.
         */
        fun photograph(width: Int, height: Int, type: Int): ByteArray {
            val image = BufferedImage(width, height, type)
            val graphics = image.createGraphics()
            for (x in 0 until width step STRIPE) {
                graphics.color = if ((x / STRIPE) % 2 == 0) Color.ORANGE else Color.DARK_GRAY
                graphics.fillRect(x, 0, STRIPE, height)
            }
            graphics.dispose()
            val bytes = ByteArrayOutputStream()
            assertTrue(ImageIO.write(image, "jpg", bytes), "the JDK encodes JPEG")
            return bytes.toByteArray()
        }

        /** The width of one painted stripe, in samples. */
        const val STRIPE: Int = 16
    }
}
