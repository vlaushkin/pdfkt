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
import io.pdfkt.core.font.Standard14Name
import io.pdfkt.core.obj.PdfDict
import io.pdfkt.core.obj.PdfName
import io.pdfkt.core.obj.assertQpdfCheckIsClean
import io.pdfkt.core.obj.extractedText
import io.pdfkt.core.obj.qpdf
import io.pdfkt.core.obj.skipWithoutPdftotext
import io.pdfkt.core.obj.skipWithoutQpdf
import kotlinx.io.asSink
import kotlinx.io.buffered
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// ISO 32000-2:2020 §7.5 "File structure", §7.7 "Document structure",
// §7.8.2 "Content streams", §9.4 "Text objects",
// §9.6.2.2 "Standard Type 1 fonts (standard 14 fonts) (PDF 1.0-1.7)"

/**
 * One page of text, written through every layer this library has, into a real
 * file, and read back by tools that know nothing about it.
 *
 * The pieces below it each have their own tests against their own clause. What
 * none of them can show is that the pieces compose: that the string a caller
 * asked for is the string that comes out of a file assembled from a content
 * stream (§7.8.2), a standard font (§9.6.2.2), a page tree (§7.7.3) and a
 * cross-reference table (§7.5.4) at once. That is what this asserts, and it
 * asserts it the only way that means anything — through a reader that did not
 * write the file.
 *
 * qpdf and pdftotext are used as *tools* here; their output is what is compared,
 * never their source.
 */
class HelloWorldTest {
    /**
     * The whole of the library's current capability, in the shape a caller would
     * use it in.
     *
     * The text is placed by `Td` at 72 units — one inch (§8.3.2.3) — from the
     * left edge and one inch below the top of a US Letter page, in 24-point
     * Helvetica: "The PostScript language names of 14 Type 1 fonts, known as the
     * standard 14 fonts, are as follows: Times-Roman, Helvetica, …" (§9.6.2.2),
     * so the file carries no font program and every reader still has the font.
     *
     * The string is shown by `Tj`, whose operand "shall be interpreted as a
     * sequence of character codes" (§9.4.3) — one byte per code for a simple
     * font. Nothing here spells those codes out: the font comes from a
     * [FontCatalog], and the same resolved font supplies the name `Tf` selects
     * it by, the codes `Tj` shows, and the dictionary the page's `/Resources`
     * names. That is the wiring under test — a page cannot be drawn with one
     * font and written with another, because there is one object behind both.
     */
    @Test
    fun `a one page hello world document is read back as the string that was drawn`() {
        if (skipWithoutQpdf("§7.7")) return
        if (skipWithoutPdftotext("§9.6.2.2")) return

        val fonts = FontCatalog()
        val helvetica = fonts.resolve(FontRef.Standard14(Standard14Name.HELVETICA))

        val content = ContentStreamBuilder()
        content.beginText()
        content.textFont(helvetica.resourceName, 24.0)
        content.nextLine(72.0, 720.0)
        content.showText(helvetica.encode(HELLO))
        content.endText()

        withTemporaryFile { path ->
            Files.newOutputStream(path).asSink().buffered().use { sink ->
                val document = PdfDocumentWriter(sink)
                document.addPage(
                    mediaBox = pdfRectangle(0.0, 0.0, 612.0, 792.0),
                    resources = PdfDict(mapOf(PdfName("Font") to fonts.fontResources(document::define))),
                    content = content.build(),
                )
                document.finish(fileIdentifier = FILE_IDENTIFIER)
            }

            assertQpdfCheckIsClean(path)
            assertEquals(HELLO, extractedText(path))

            val pages = qpdf("--show-npages", path.toString())
            assertEquals(0, pages.status, "qpdf could not count the pages:\n${pages.output}")
            assertEquals("1", pages.output.trim(), "the document does not have exactly one page")

            val structure = qpdf("--show-object=trailer", path.toString())
            assertTrue(
                structure.output.contains("/Root"),
                "the trailer does not name a catalog:\n${structure.output}",
            )
        }
    }

    /**
     * What the `/Encoding` entry of Table 109 buys, asserted the only way it can
     * be: through a reader.
     *
     * Every character below sits above 177 octal, where WinAnsiEncoding and
     * UTF-8 disagree — `ü` is one byte, 374 octal, and three bytes in the string
     * this test wrote. The catalog encodes through Table D.2 and the font
     * dictionary declares that same table, so a reader that never saw this
     * library maps the bytes back to the characters that went in. Writing UTF-8
     * and declaring WinAnsiEncoding, or the reverse, would extract as mojibake
     * out of a file qpdf still calls clean.
     */
    @Test
    fun `text above the ASCII range round-trips through the declared encoding`() {
        if (skipWithoutQpdf("§9.6.2.1")) return
        if (skipWithoutPdftotext("Annex D")) return

        val fonts = FontCatalog()
        val times = fonts.resolve(FontRef.Standard14(Standard14Name.TIMES_ROMAN))

        val content = ContentStreamBuilder()
        content.beginText()
        content.textFont(times.resourceName, 24.0)
        content.nextLine(72.0, 720.0)
        content.showText(times.encode(LATIN_1_SUPPLEMENT))
        content.endText()

        withTemporaryFile { path ->
            Files.newOutputStream(path).asSink().buffered().use { sink ->
                val document = PdfDocumentWriter(sink)
                document.addPage(
                    mediaBox = pdfRectangle(0.0, 0.0, 612.0, 792.0),
                    resources = PdfDict(mapOf(PdfName("Font") to fonts.fontResources(document::define))),
                    content = content.build(),
                )
                document.finish(fileIdentifier = FILE_IDENTIFIER)
            }

            assertQpdfCheckIsClean(path)
            assertEquals(LATIN_1_SUPPLEMENT, extractedText(path))

            val bytes = Files.readAllBytes(path).decodeToString()
            assertTrue(
                bytes.contains("/BaseFont /Times-Roman /Encoding /WinAnsiEncoding"),
                "the file does not declare the encoding its character codes were written in",
            )
        }
    }

    /**
     * Runs [block] against a path in the directory the operating system hands
     * out for temporary files, and removes the file afterwards.
     *
     * The path is asked for rather than spelled out: a test that writes to a
     * fixed name races itself when the suite runs twice at once, and one that
     * writes to a fixed *directory* does not run on a machine without it.
     */
    private fun withTemporaryFile(block: (Path) -> Unit) {
        val path = Files.createTempFile("pdfkt-hello-world", ".pdf")
        try {
            block(path)
        } finally {
            path.deleteIfExists()
        }
    }

    private companion object {
        /** The string drawn on the page, and the string expected back out of it. */
        const val HELLO: String = "Hello, World!"

        /**
         * Characters Table D.2 encodes above 177 octal, all of them in the
         * twelve Latin fonts: `Grüße`, `naïve`, `¿cañón?`.
         */
        const val LATIN_1_SUPPLEMENT: String = "Grüße naïve ¿cañón?"

        /**
         * "Each PDF file identifier byte-string shall have a minimum length of
         * 16 bytes" (Table 15). §14.4 derives the real one from the time and the
         * size of the file; a test pins it so that the file it asserts about is
         * the same file every run.
         */
        val FILE_IDENTIFIER: ByteArray = ByteArray(16) { (0x10 + it).toByte() }
    }
}
