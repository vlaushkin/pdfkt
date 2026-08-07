/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.obj

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

// ISO 32000-2:2020 §7.3.8 "Stream objects"

/**
 * A stream, in a file, read back by a PDF processor that is not this one.
 *
 * The byte-level claims of §7.3.8 are asserted against the clause in
 * `PdfStreamTest`; what cannot be asserted there is that the framing and the
 * `/Length` they produce are what an independent reader needs in order to find
 * the data again. qpdf is used as a *tool* for that — see [minimalPdfFile],
 * which builds the file the objects go into.
 *
 * The check has teeth: qpdf reports a `/Length` that disagrees with the framing
 * as a warning ("expected endstream", "attempting to recover stream length")
 * and exits non-zero, so a stream declaring the wrong count could not pass.
 */
class PdfStreamFileTest {
    /**
     * A page whose `/Contents` is a stream of content-stream text: the file is
     * structurally sound, and the bytes qpdf hands back are the bytes that went
     * in — the trailing end-of-line marker of the framing among them not, since
     * "this marker shall not be included in the stream length".
     */
    @Test
    fun `qpdf accepts a file whose page content is a stream and reads the data back`() {
        if (skipWithoutQpdf("§7.3.8")) return
        val data = "0 0 1 rg\n100 100 200 300 re\nf\n".encodeToByteArray()
        val document = minimalDocument(contents = PdfStream(PdfDict(emptyMap()), data), image = null)

        withPdfFile(document) { path ->
            assertQpdfCheckIsClean(path)
            assertContentEquals(data, rawStreamData(path, objectNumber = 4))
        }
    }

    /**
     * A stream of arbitrary bytes — an unfiltered 16 by 16 greyscale image, one
     * byte per sample, so the 256 samples are the 256 byte values.
     *
     * Nothing is escaped and nothing is a sentinel: "the stream dictionary
     * specifies the exact number of bytes", and that is the whole of how the
     * data is delimited. The first sample is a byte of value zero and the
     * seventeenth is a LINE FEED, either of which would end a token anywhere
     * else in the file.
     */
    @Test
    fun `qpdf reads back every byte value from an unfiltered stream`() {
        if (skipWithoutQpdf("§7.3.8")) return
        val samples = ByteArray(256) { it.toByte() }
        val image =
            PdfStream(
                PdfDict(
                    mapOf(
                        PdfName("Type") to PdfName("XObject"),
                        PdfName("Subtype") to PdfName("Image"),
                        PdfName("Width") to PdfInt(16),
                        PdfName("Height") to PdfInt(16),
                        PdfName("ColorSpace") to PdfName("DeviceGray"),
                        PdfName("BitsPerComponent") to PdfInt(8),
                    ),
                ),
                samples,
            )
        val draw = "q 100 0 0 100 50 50 cm /Im0 Do Q\n".encodeToByteArray()
        val document = minimalDocument(contents = PdfStream(PdfDict(emptyMap()), draw), image = image)

        withPdfFile(document) { path ->
            assertQpdfCheckIsClean(path)
            assertContentEquals(draw, rawStreamData(path, objectNumber = 4))
            assertContentEquals(samples, rawStreamData(path, objectNumber = 5))
        }
    }

    /**
     * The bytes qpdf finds between the two markers of object [objectNumber] —
     * the data as the file describes it, which is what the assertion is about.
     */
    private fun rawStreamData(path: Path, objectNumber: Int): ByteArray {
        val shown = qpdf("--show-object=$objectNumber,0", "--raw-stream-data", path.toString())
        assertEquals(0, shown.status, "qpdf could not show object $objectNumber:\n${shown.output}")
        return shown.bytes
    }

    /**
     * A file with a catalog, a page tree of one page, the page's content stream
     * and, when given, an image XObject the page's resources name `/Im0`.
     *
     * Object numbers are fixed by the order of the list: 1 catalog, 2 pages,
     * 3 page, 4 contents, 5 image.
     */
    private fun minimalDocument(contents: PdfStream, image: PdfStream?): ByteArray {
        val resources =
            if (image == null) {
                PdfDict(emptyMap())
            } else {
                PdfDict(mapOf(PdfName("XObject") to PdfDict(mapOf(PdfName("Im0") to PdfRef(5, 0)))))
            }
        return minimalPdfFile(
            listOfNotNull(
                PdfDict(mapOf(PdfName("Type") to PdfName("Catalog"), PdfName("Pages") to PdfRef(2, 0))),
                PdfDict(
                    mapOf(
                        PdfName("Type") to PdfName("Pages"),
                        PdfName("Kids") to PdfArray(listOf(PdfRef(3, 0))),
                        PdfName("Count") to PdfInt(1),
                    ),
                ),
                PdfDict(
                    mapOf(
                        PdfName("Type") to PdfName("Page"),
                        PdfName("Parent") to PdfRef(2, 0),
                        PdfName("MediaBox") to
                            PdfArray(listOf(PdfInt(0), PdfInt(0), PdfInt(612), PdfInt(792))),
                        PdfName("Resources") to resources,
                        PdfName("Contents") to PdfRef(4, 0),
                    ),
                ),
                contents,
                image,
            ),
        )
    }
}
