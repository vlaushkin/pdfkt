/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.content

import io.pdfkt.core.file.PdfFileWriter
import io.pdfkt.core.file.TEST_FILE_IDENTIFIER
import io.pdfkt.core.file.pdfFile
import io.pdfkt.core.obj.PdfArray
import io.pdfkt.core.obj.PdfDict
import io.pdfkt.core.obj.PdfInt
import io.pdfkt.core.obj.PdfName
import io.pdfkt.core.obj.PdfStream
import io.pdfkt.core.obj.assertQpdfCheckIsClean
import io.pdfkt.core.obj.extractedText
import io.pdfkt.core.obj.qpdf
import io.pdfkt.core.obj.skipWithoutPdftotext
import io.pdfkt.core.obj.skipWithoutQpdf
import io.pdfkt.core.obj.withPdfFile
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

// ISO 32000-2:2020 §7.8.2 "Content streams", §8.5 "Path construction and painting",
// §9.4 "Text objects"

/**
 * The builder's output inside a page, seen by an independent reader.
 *
 * The common tests assert the bytes against the operator summaries; what they
 * cannot show is that those bytes survive as the content of a page and are read
 * back as the instructions that went in. qpdf is used as a *tool* for that, and
 * its output is what is compared — never its source.
 */
class ContentStreamBuilderFileTest {
    /**
     * A page whose `/Contents` is built here: a filled rectangle and a stroked
     * triangle, each a path object between its construction operators and its
     * painting operator, wrapped in the `q`/`Q` of §8.4.2 so the page is left in
     * the graphics state it was entered with.
     */
    @Test
    fun `qpdf accepts a page whose content is built by the builder`() {
        if (skipWithoutQpdf("§8.5")) return
        val builder = ContentStreamBuilder()
        builder.save()
        builder.concat(1.0, 0.0, 0.0, 1.0, 72.0, 72.0)
        builder.rectangle(0.0, 0.0, 144.0, 72.0)
        builder.fill()
        builder.lineWidth(2.5)
        builder.moveTo(0.0, 100.0)
        builder.lineTo(72.0, 220.0)
        builder.lineTo(144.0, 100.0)
        builder.lineTo(0.0, 100.0)
        builder.stroke()
        builder.restore()
        val content = builder.build()

        withPdfFile(pageWithContent(content)) { path ->
            assertQpdfCheckIsClean(path)
            assertContentEquals(content, rawStreamData(path, objectNumber = 2))
        }
    }

    /**
     * A page of text, extracted back out by a reader that knows nothing of this
     * library.
     *
     * The content is §9.2.2 EXAMPLE 1 with the string of §9.4.3 that has
     * something to say: LEFT PARENTHESIS, RIGHT PARENTHESIS and REVERSE SOLIDUS,
     * the three bytes the clause requires to "be preceded by a REVERSE SOLIDUS
     * character", together with a balanced pair that needs no escape. Whether the
     * escaping is right is not a claim about the bytes written — the common tests
     * make that one — but about what comes back when they are read, which is what
     * this asserts.
     *
     * The font is the one §9.2.2 EXAMPLE 2 defines for the `/F13` of EXAMPLE 1:
     * "/Type /Font /Subtype /Type1 /BaseFont /Helvetica", reached through the
     * `/Font` subdictionary of the page's resources, which is where Table 103
     * says `Tf` looks for its operand.
     */
    @Test
    fun `text shown through the builder is extracted as the string that went in`() {
        if (skipWithoutQpdf("§9.4")) return
        if (skipWithoutPdftotext("§9.4")) return
        val shown = "A(B)C\\D(E"
        val builder = ContentStreamBuilder()
        builder.beginText()
        builder.textFont(PdfName("F13"), 12.0)
        builder.nextLine(72.0, 720.0)
        builder.showText(shown.encodeToByteArray())
        builder.endText()
        val content = builder.build()

        withPdfFile(pageOfText(content)) { path ->
            assertQpdfCheckIsClean(path)
            assertContentEquals(content, rawStreamData(path, objectNumber = 2))
            assertEquals(shown, extractedText(path))
        }
    }

    /** The bytes qpdf finds between the two markers of object [objectNumber]. */
    private fun rawStreamData(path: Path, objectNumber: Int): ByteArray {
        val shown = qpdf("--show-object=$objectNumber,0", "--raw-stream-data", path.toString())
        assertEquals(0, shown.status, "qpdf could not show object $objectNumber:\n${shown.output}")
        return shown.bytes
    }

    /**
     * A catalog, a page tree of one page, and that page's content stream —
     * objects 1 to 4, the page tree node reserved first and the content stream
     * defined as object 2, which is the smallest file a content stream can be
     * inspected in (§7.7.2, §7.7.3).
     */
    private fun pageWithContent(content: ByteArray): ByteArray = pdfFile { writer: PdfFileWriter ->
        val pages = writer.reserve()
        val stream = writer.define(PdfStream(PdfDict(emptyMap()), content))
        val page =
            writer.define(
                PdfDict(
                    mapOf(
                        PdfName("Type") to PdfName("Page"),
                        PdfName("Parent") to pages,
                        PdfName("Resources") to PdfDict(emptyMap()),
                        PdfName("MediaBox") to
                            PdfArray(listOf(PdfInt(0), PdfInt(0), PdfInt(612), PdfInt(792))),
                        PdfName("Contents") to stream,
                    ),
                ),
            )
        writer.define(
            pages,
            PdfDict(
                mapOf(
                    PdfName("Type") to PdfName("Pages"),
                    PdfName("Kids") to PdfArray(listOf(page)),
                    PdfName("Count") to PdfInt(1),
                ),
            ),
        )
        val catalog =
            writer.define(
                PdfDict(mapOf(PdfName("Type") to PdfName("Catalog"), PdfName("Pages") to pages)),
            )
        writer.finish(root = catalog, fileIdentifier = TEST_FILE_IDENTIFIER)
    }

    /**
     * The same file with one font resource added, named `/F13` in the `/Font`
     * subdictionary of the page's resource dictionary — the arrangement of
     * §9.2.2 EXAMPLE 2, which is what the `/F13` of EXAMPLE 1 resolves through.
     * The content stream stays object 2, so the raw-stream check reads the same
     * object here as in the file without a font.
     */
    private fun pageOfText(content: ByteArray): ByteArray = pdfFile { writer: PdfFileWriter ->
        val pages = writer.reserve()
        val stream = writer.define(PdfStream(PdfDict(emptyMap()), content))
        val font =
            writer.define(
                PdfDict(
                    mapOf(
                        PdfName("Type") to PdfName("Font"),
                        PdfName("Subtype") to PdfName("Type1"),
                        PdfName("BaseFont") to PdfName("Helvetica"),
                    ),
                ),
            )
        val page =
            writer.define(
                PdfDict(
                    mapOf(
                        PdfName("Type") to PdfName("Page"),
                        PdfName("Parent") to pages,
                        PdfName("Resources") to
                            PdfDict(
                                mapOf(
                                    PdfName("Font") to PdfDict(mapOf(PdfName("F13") to font)),
                                ),
                            ),
                        PdfName("MediaBox") to
                            PdfArray(listOf(PdfInt(0), PdfInt(0), PdfInt(612), PdfInt(792))),
                        PdfName("Contents") to stream,
                    ),
                ),
            )
        writer.define(
            pages,
            PdfDict(
                mapOf(
                    PdfName("Type") to PdfName("Pages"),
                    PdfName("Kids") to PdfArray(listOf(page)),
                    PdfName("Count") to PdfInt(1),
                ),
            ),
        )
        val catalog =
            writer.define(
                PdfDict(mapOf(PdfName("Type") to PdfName("Catalog"), PdfName("Pages") to pages)),
            )
        writer.finish(root = catalog, fileIdentifier = TEST_FILE_IDENTIFIER)
    }
}
