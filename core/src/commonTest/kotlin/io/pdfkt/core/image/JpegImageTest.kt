/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.image

import io.pdfkt.core.obj.PdfInt
import io.pdfkt.core.obj.PdfName
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

// ISO 32000-2:2020 §7.4.8 "DCTDecode filter" (Table 13),
// §8.9.5.1 "Image dictionaries — General" (Table 87)
// ITU-T T.81 (09/92) | ISO/IEC 10918-1 §B.2.2 "Frame header syntax"

/**
 * A JPEG becoming an image of a document: what of Table 87 is read out of the
 * frame header, and what of the file reaches the stream.
 *
 * The streams here are assembled field by field ([jpegStream]) so that the test
 * states the bytes the dictionary is derived from. The same function is put to a
 * real encoder's output in `JpegImageRenderTest` on the JVM, where the file also
 * has to come back out of a written PDF.
 */
class JpegImageTest {
    /**
     * §7.4.8: the parameters "include the dimensions of the image and the number
     * of components per sample" and "shall be stored in the encoded data" — so
     * the image dictionary is the frame header, restated in the vocabulary of
     * Table 87.
     */
    @Test
    fun `a colour JPEG becomes a DCTDecode image of the frame's dimensions`() {
        val image = jpegImage("photo", colourJpeg(width = 47, height = 23))

        assertEquals("photo", image.id)
        assertEquals(47, image.width)
        assertEquals(23, image.height)
        assertEquals(ImageColorSpace.DEVICE_RGB, image.colorSpace)
        assertEquals(8, image.bitsPerComponent)
        assertEquals(ImageFilter.DCT_DECODE, image.filter)
    }

    /** One component is `/DeviceGray` and four are `/DeviceCMYK` (§7.4.8, §8.6.4). */
    @Test
    fun `the colour space is the one the component count names`() {
        val grey = jpegImage("grey", jpegOf(width = 8, height = 8, componentCount = 1))
        val cmyk = jpegImage("press", jpegOf(width = 8, height = 8, componentCount = 4))

        assertEquals(ImageColorSpace.DEVICE_GRAY, grey.colorSpace)
        assertEquals(ImageColorSpace.DEVICE_CMYK, cmyk.colorSpace)
    }

    /**
     * The space the image dictionary names is the space the JPEG reader named:
     * the two enumerations meet here, and a mapping that drifted would write a
     * three-component photograph as `/DeviceCMYK` without anything failing.
     */
    @Test
    fun `the space written is the space the header reported`() {
        for (componentCount in listOf(1, 3, 4)) {
            val bytes = jpegOf(width = 8, height = 8, componentCount = componentCount)
            val header = JpegHeader.parse(bytes)

            assertEquals(
                header.colourSpace.pdfName,
                jpegImage("image-$componentCount", bytes).colorSpace.pdfObject,
                "a $componentCount-component frame",
            )
        }
    }

    /**
     * The stream is the file: "PDF embeds it as-is".
     * Nothing is trimmed off the front, no
     * marker is rewritten, and the JPEG the caller holds is byte for byte the
     * JPEG a reader takes back out of the file.
     */
    @Test
    fun `the bytes of the file are the bytes of the stream`() {
        val bytes = colourJpeg(width = 16, height = 9)

        val stream = ImageCatalog().resolve(jpegImage("photo", bytes)).imageXObject(NUMBERING)

        assertContentEquals(bytes, stream.data)
    }

    /**
     * The image XObject a page paints: Table 87's required entries taken from the
     * frame, and `/Filter /DCTDecode` (§7.4, Table 5) saying what the samples are
     * encoded in.
     */
    @Test
    fun `the image XObject declares DCTDecode and the frame's dimensions`() {
        val stream = ImageCatalog().resolve(
            jpegImage("photo", colourJpeg(width = 47, height = 23)),
        ).imageXObject(NUMBERING)
        val entries = stream.dict.entries

        assertEquals(PdfName("Image"), entries[PdfName("Subtype")])
        assertEquals(PdfName("DCTDecode"), entries[PdfName("Filter")])
        assertEquals(PdfInt(47L), entries[PdfName("Width")])
        assertEquals(PdfInt(23L), entries[PdfName("Height")])
        assertEquals(PdfName("DeviceRGB"), entries[PdfName("ColorSpace")])
        assertEquals(PdfInt(8L), entries[PdfName("BitsPerComponent")])
    }

    /**
     * No `/DecodeParms`, in either direction of Table 13's rule.
     *
     * With an Adobe APP14 segment "the value of this dictionary entry shall be
     * ignored"; without one the default is "1 if the image has three components
     * and 0 otherwise", which is what the encoded data already mean. An entry
     * that is either ignored or equal to the default is one this library has no
     * reason to write.
     */
    @Test
    fun `no decode parameters are written for either kind of file`() {
        val withAdobe =
            jpegStream(
                marker(JpegMarker.START_OF_IMAGE),
                adobeApp14(transform = 2),
                frameHeader(width = 8, height = 8),
                scanAndEnd(),
            )

        for (bytes in listOf(colourJpeg(width = 8, height = 8), withAdobe)) {
            val stream = ImageCatalog().resolve(jpegImage("photo", bytes)).imageXObject(NUMBERING)
            assertNull(stream.dict.entries[PdfName("DecodeParms")], "a /DecodeParms nobody asked for")
        }
    }

    /**
     * "A RunLengthDecode or DCTDecode filter shall always deliver 8-bit samples"
     * (Table 87) — a 12-bit extended-sequential frame is legal JPEG and not an
     * image this library writes, and it is refused where the precision is read
     * rather than by the bit depths Table 87 lists further up.
     */
    @Test
    fun `a twelve bit JPEG is refused by the filter's sample size`() {
        val twelveBit =
            jpegStream(
                marker(JpegMarker.START_OF_IMAGE),
                frameHeader(
                    code = JpegMarker.EXTENDED_SEQUENTIAL_DCT,
                    width = 8,
                    height = 8,
                    precision = 12,
                ),
                scanAndEnd(),
            )

        val refused = assertFailsWith<IllegalArgumentException> { jpegImage("deep", twelveBit) }

        assertTrue(refused.message!!.contains("8-bit samples"), "it did not say why: ${refused.message}")
        assertTrue(refused.message!!.contains("deep"), "it did not say which image: ${refused.message}")
    }

    /** A progressive frame is one PDF 1.3 onwards requires a reader to decode (§7.4.8). */
    @Test
    fun `a progressive JPEG is embedded like any other`() {
        val progressive =
            jpegStream(
                marker(JpegMarker.START_OF_IMAGE),
                frameHeader(code = JpegMarker.PROGRESSIVE_DCT, width = 20, height = 10),
                scanAndEnd(),
            )

        val image = jpegImage("progressive", progressive)

        assertEquals(20, image.width)
        assertEquals(ImageFilter.DCT_DECODE, image.filter)
    }

    /** What is not a JPEG is refused there and then, not written into a file. */
    @Test
    fun `bytes that are not a JPEG are refused`() {
        assertFailsWith<IllegalArgumentException> { jpegImage("nonsense", ByteArray(64) { 0x41 }) }
    }

    private fun colourJpeg(width: Int, height: Int): ByteArray = jpegOf(width, height, componentCount = 3)

    /** A stream of the shape a JFIF encoder writes: SOI, APP0, the frame, a scan. */
    private fun jpegOf(width: Int, height: Int, componentCount: Int): ByteArray = jpegStream(
        marker(JpegMarker.START_OF_IMAGE),
        jfifApp0(),
        frameHeader(width = width, height = height, componentCount = componentCount),
        scanAndEnd(componentCount),
    )
}
