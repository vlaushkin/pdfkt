/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.filter

import io.pdfkt.core.obj.PdfArray
import io.pdfkt.core.obj.PdfDict
import io.pdfkt.core.obj.PdfInt
import io.pdfkt.core.obj.PdfName
import io.pdfkt.core.obj.PdfRef
import io.pdfkt.core.obj.PdfStream
import io.pdfkt.core.obj.assertQpdfCheckIsClean
import io.pdfkt.core.obj.minimalPdfFile
import io.pdfkt.core.obj.qpdf
import io.pdfkt.core.obj.skipWithoutQpdf
import io.pdfkt.core.obj.withPdfFile
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.DataFormatException
import java.util.zip.Inflater
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

// ISO 32000-2:2020 §7.4.4 "LZWDecode and FlateDecode filters"
// RFC 1950 §2.2 "Data format", RFC 1951 §3.2.4 "Non-compressed blocks (BTYPE=00)",
//   §3.2.5 "Compressed blocks (length and distance codes)",
//   §3.2.6 "Compression with fixed Huffman codes (BTYPE=01)"

/**
 * A `/FlateDecode` stream read by decompressors that are not this one.
 *
 * `FlateTest` asserts the bytes against the RFCs; what it cannot show is that
 * those bytes are what an independent implementation of the two RFCs accepts —
 * and the filter is worth nothing unless every reader of a PDF gets the data
 * back. qpdf and `java.util.zip` are used as *tools* here, their output
 * compared and their sources never read.
 *
 * The two say different things. qpdf answers the question the acceptance asks —
 * a PDF processor takes the file, applies the filter the dictionary names and
 * returns the data. [Inflater] answers a narrower one that qpdf cannot be
 * relied on to: whether the Adler-32 trailer is *right*, since a decompressor
 * may well hand back correct bytes without checking the trailing sum. The last
 * test here shows that this one does check, so the tests above it mean what they
 * appear to.
 */
class FlateFileTest {
    /**
     * The round trip under test: a page whose `/Contents` is a
     * `/FlateDecode` stream, through `qpdf --qdf`.
     *
     * QDF mode writes the file back with its streams uncompressed, so the
     * content stream appears in the output as the text that went in — a check
     * that qpdf both accepted the filter and decoded it, and that the file
     * around it survived the rewrite.
     */
    @Test
    fun `qpdf --qdf writes back the content stream that was flate encoded`() {
        if (skipWithoutQpdf("§7.4.4")) return
        val content = "0 0 1 rg\n100 100 200 300 re\nf\n".encodeToByteArray()

        withPdfFile(documentWithContents(content)) { path ->
            assertQpdfCheckIsClean(path)
            val expanded = Files.createTempFile("pdfkt-qdf", ".pdf")
            try {
                val run = qpdf("--qdf", path.toString(), expanded.toString())
                assertEquals(0, run.status, "qpdf --qdf rejected the file:\n${run.output}")
                assertQpdfCheckIsClean(expanded)
                val written = Files.readAllBytes(expanded)
                assertTrue(
                    written.decodeToString().contains(content.decodeToString()),
                    "the content stream is not in the QDF output as it was written",
                )
            } finally {
                Files.deleteIfExists(expanded)
            }
        }
    }

    /**
     * The same file, asked directly for the result of applying the filter:
     * `--filtered-stream-data` is qpdf decoding the stream by the `/Filter` the
     * dictionary declares, and the bytes it returns are the bytes that went in.
     */
    @Test
    fun `qpdf decodes the filter back to the data it was given`() {
        if (skipWithoutQpdf("§7.4.4")) return
        val content = "BT /F1 12 Tf 72 720 Td (Flate) Tj ET\n".encodeToByteArray()

        withPdfFile(documentWithContents(content)) { path ->
            assertContentEquals(content, filteredStreamData(path, objectNumber = 4))
        }
    }

    /**
     * Data that does not fit one block, and data of every byte value: a
     * 100 000-sample image, which is a block of 65 535 and one of 34 465. A
     * reader that mishandled the block boundary — took the first block for the
     * whole stream, or lost the match index across it — would return something
     * other than the samples, and none of the 256 byte values is special to the
     * filter.
     */
    @Test
    fun `qpdf decodes a stream spanning several blocks`() {
        if (skipWithoutQpdf("§7.4.4")) return
        val samples = ByteArray(100_000) { (it % 256).toByte() }
        val image =
            flateStream(
                PdfDict(
                    mapOf(
                        PdfName("Type") to PdfName("XObject"),
                        PdfName("Subtype") to PdfName("Image"),
                        PdfName("Width") to PdfInt(250),
                        PdfName("Height") to PdfInt(400),
                        PdfName("ColorSpace") to PdfName("DeviceGray"),
                        PdfName("BitsPerComponent") to PdfInt(8),
                    ),
                ),
                samples,
            )
        val draw = "q 100 0 0 100 50 50 cm /Im0 Do Q\n".encodeToByteArray()

        withPdfFile(documentWithContents(draw, image)) { path ->
            assertQpdfCheckIsClean(path)
            assertContentEquals(samples, filteredStreamData(path, objectNumber = 5))
        }
    }

    /**
     * The wrapper and the checksum under a decompressor that verifies them.
     *
     * [Inflater] with the zlib wrapper enabled reads CMF and FLG, inflates, and
     * compares the Adler-32 of what it produced against the trailer; the last
     * assertion corrupts that trailer and shows the failure, so the passes above
     * it are evidence the checksum is right rather than evidence it is ignored.
     */
    @Test
    fun `an independent decompressor accepts the wrapper and the checksum`() {
        for (size in listOf(0, 1, 3, 1024, 65_535, 65_536, 200_000)) {
            val data = ByteArray(size) { (it * 31 % 251).toByte() }
            assertContentEquals(data, inflate(flateEncode(data), size), "$size bytes")
        }

        val corrupted = flateEncode("abc".encodeToByteArray())
        corrupted[corrupted.size - 1] = (corrupted[corrupted.size - 1] + 1).toByte()
        assertFailsWith<DataFormatException> { inflate(corrupted, 3) }
    }

    /**
     * The compressed blocks under an independent decompressor, over data shaped
     * to reach the corners of §3.2.5 that a fixed corpus would not: runs long
     * enough to need length 258 and the codes around it, matches at distances
     * from 1 to past 32 768, matches that straddle the boundary between one
     * block and the next, and stretches of noise where the encoder has to fall
     * back to stored blocks in the middle of a stream.
     *
     * A wrong extra-bit count, a code written least significant bit first, or a
     * match index that forgot the previous block would all survive the
     * hand-computed byte tests in `FlateTest` — those cover a few dozen bytes —
     * and none of them would survive this. The generator is deterministic, so a
     * failure here is reproducible rather than a once-seen shape.
     */
    @Test
    fun `an independent decompressor reads back data of every shape`() {
        var seed = 0x9E3779B9.toInt()
        fun next(bound: Int): Int {
            seed = seed * 1664525 + 1013904223
            return ((seed ushr 8) and 0x7FFFFF) % bound
        }

        for (round in 0 until 40) {
            val out = java.io.ByteArrayOutputStream()
            while (out.size() < 150_000) {
                when (next(4)) {
                    // A run of one byte: the longest matches the format allows,
                    // at distance 1, which is where length 258 and code 285 come up.
                    0 -> out.write(ByteArray(next(700) + 1) { next(4).toByte() })
                    // Repetition of a phrase, so distances land all over the tables.
                    1 -> {
                        val phrase = ByteArray(next(300) + 3) { next(256).toByte() }
                        repeat(next(30) + 1) { out.write(phrase) }
                    }
                    // Noise: nothing to match, and the fixed alphabet costs more
                    // than the bytes, so these stretches drive the stored fallback.
                    2 -> out.write(ByteArray(next(9000) + 1) { next(256).toByte() })
                    // A copy of what has already been written, from far enough
                    // back to exercise the far end of the distance alphabet.
                    else -> {
                        val whole = out.toByteArray()
                        if (whole.isEmpty()) {
                            out.write(next(256))
                        } else {
                            val from = next(whole.size)
                            out.write(whole, from, minOf(whole.size - from, next(40_000) + 1))
                        }
                    }
                }
            }
            val data = out.toByteArray()
            assertContentEquals(data, inflate(flateEncode(data), data.size), "round $round, ${data.size} bytes")
        }
    }

    /**
     * The acceptance, measured against a decompressor that is not this one: a
     * content stream of the kind this library writes goes into a PDF, qpdf
     * applies the `/Filter` the dictionary names, and what comes back is the
     * stream that went in — from a good deal fewer bytes than it occupies
     * uncompressed.
     */
    @Test
    fun `qpdf decodes a compressed content stream back from a fraction of its size`() {
        if (skipWithoutQpdf("§7.4.4")) return
        val content = StringBuilder()
        for (row in 0 until 60) {
            content.append("q 1 0 0 1 40 ").append(row * 12).append(" cm\n")
            content.append("BT /F1 10 Tf 0 0 Td (a line of text on the page) Tj ET\n")
            content.append("0.9 0.9 0.9 rg\n0 0 500 11 re\nf\nQ\n")
        }
        val data = content.toString().encodeToByteArray()

        val stream = flateStream(PdfDict(emptyMap()), data)
        assertTrue(
            stream.data.size < data.size / 4,
            "expected under a quarter of ${data.size} bytes, got ${stream.data.size}",
        )

        withPdfFile(documentWithContents(data)) { path ->
            assertQpdfCheckIsClean(path)
            assertContentEquals(data, filteredStreamData(path, objectNumber = 4))
        }
    }

    /** [encoded] decompressed by `java.util.zip`, checksum verification included. */
    private fun inflate(encoded: ByteArray, size: Int): ByteArray {
        val inflater = Inflater()
        try {
            inflater.setInput(encoded)
            val out = ByteArray(size)
            var at = 0
            while (at < size) {
                val read = inflater.inflate(out, at, size - at)
                if (read == 0 && (inflater.finished() || inflater.needsInput())) break
                at += read
            }
            // The trailer is only read once the last block has been inflated, so
            // the checksum failure surfaces here rather than at construction.
            inflater.inflate(ByteArray(1))
            assertTrue(inflater.finished(), "the stream did not end after $size bytes")
            return out.copyOf(at)
        } finally {
            inflater.end()
        }
    }

    /** The data of object [objectNumber] with its `/Filter` applied by qpdf. */
    private fun filteredStreamData(path: Path, objectNumber: Int): ByteArray {
        val shown = qpdf("--show-object=$objectNumber,0", "--filtered-stream-data", path.toString())
        assertEquals(0, shown.status, "qpdf could not decode object $objectNumber:\n${shown.output}")
        return shown.bytes
    }

    /**
     * A file whose page draws [contents], flate-encoded, and names [image]
     * `/Im0` in its resources when there is one.
     *
     * Object numbers follow the order of the list: 1 catalog, 2 pages, 3 page,
     * 4 contents, 5 image.
     */
    private fun documentWithContents(contents: ByteArray, image: PdfStream? = null): ByteArray {
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
                flateStream(PdfDict(emptyMap()), contents),
                image,
            ),
        )
    }
}
