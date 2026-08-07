/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.image

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

// ISO 32000-2:2020 §7.4.8 "DCTDecode filter" (Table 13)
// ITU-T T.81 (09/92) | ISO/IEC 10918-1 §B.1.1 "Markers and marker segments",
// §B.2.2 "Frame header syntax"
// ITU-T T.871 (05/2011) | ISO/IEC 10918-5, the JFIF APP0 marker segment

/**
 * What the marker structure requires, written as the bytes it requires.
 *
 * Each test assembles a data stream out of the fields Annex B defines and
 * asserts what a reader is entitled to conclude from them — or, for the
 * malformed ones, that the refusal names the marker the fault is in. The last
 * part is not decoration: a caller holding a JPEG their pipeline produced can
 * act on "SOF3 (0xFFC3)" and cannot act on "malformed JPEG".
 */
class JpegHeaderTest {
    @Test
    fun `the frame header gives the dimensions and the sample precision`() {
        val header = JpegHeader.parse(
            jpegStream(
                marker(JpegMarker.START_OF_IMAGE),
                frameHeader(width = 47, height = 23),
                scanAndEnd(),
            ),
        )

        assertEquals(47, header.width)
        assertEquals(23, header.height)
        assertEquals(8, header.bitsPerComponent)
        assertEquals(3, header.componentCount)
        assertEquals(JpegEncoding.BASELINE, header.encoding)
    }

    @Test
    fun `one component is DeviceGray three are DeviceRGB four are DeviceCMYK`() {
        assertEquals(JpegColourSpace.DEVICE_GRAY, colourSpaceOfFrameWith(componentCount = 1))
        assertEquals(JpegColourSpace.DEVICE_RGB, colourSpaceOfFrameWith(componentCount = 3))
        assertEquals(JpegColourSpace.DEVICE_CMYK, colourSpaceOfFrameWith(componentCount = 4))

        assertEquals("DeviceGray", JpegColourSpace.DEVICE_GRAY.pdfName.name)
        assertEquals("DeviceRGB", JpegColourSpace.DEVICE_RGB.pdfName.name)
        assertEquals("DeviceCMYK", JpegColourSpace.DEVICE_CMYK.pdfName.name)
    }

    /** §7.4.8: samples consist "of one, three, or four colour components". */
    @Test
    fun `a frame of two components is refused naming the frame marker`() {
        val message = refusalFor(
            jpegStream(
                marker(JpegMarker.START_OF_IMAGE),
                frameHeader(width = 8, height = 8, componentCount = 2),
                scanAndEnd(componentCount = 2),
            ),
        )

        assertNames("SOF0 (0xFFC0)", message)
        assertTrue("two components" in message || "2 colour components" in message, message)
    }

    @Test
    fun `SOF2 is the progressive extension and SOF1 the extended sequential frame`() {
        assertEquals(JpegEncoding.PROGRESSIVE, encodingOfFrameStartedBy(JpegMarker.PROGRESSIVE_DCT))
        assertEquals(
            JpegEncoding.EXTENDED_SEQUENTIAL,
            encodingOfFrameStartedBy(JpegMarker.EXTENDED_SEQUENTIAL_DCT),
        )
    }

    /** Table B.2: a baseline frame has 8 bits per sample, the other two 8 or 12. */
    @Test
    fun `12 bits per sample are a progressive frame and not a baseline one`() {
        val progressive = JpegHeader.parse(
            jpegStream(
                marker(JpegMarker.START_OF_IMAGE),
                frameHeader(code = JpegMarker.PROGRESSIVE_DCT, width = 8, height = 8, precision = 12),
                scanAndEnd(),
            ),
        )
        assertEquals(12, progressive.bitsPerComponent)

        val message = refusalFor(
            jpegStream(
                marker(JpegMarker.START_OF_IMAGE),
                frameHeader(width = 8, height = 8, precision = 12),
                scanAndEnd(),
            ),
        )
        assertNames("SOF0 (0xFFC0)", message)
        assertTrue("precision" in message, message)
    }

    /** §7.4.8 reaches SOF0, SOF1 and SOF2; the other frames are legal JPEG a PDF may not carry. */
    @Test
    fun `a lossless frame is refused naming SOF3`() {
        val message = refusalFor(
            jpegStream(
                marker(JpegMarker.START_OF_IMAGE),
                frameHeader(code = 0xC3, width = 8, height = 8),
                scanAndEnd(),
            ),
        )

        assertNames("SOF3 (0xFFC3)", message)
    }

    @Test
    fun `an arithmetic-coded progressive frame is refused naming SOF10`() {
        val message = refusalFor(
            jpegStream(
                marker(JpegMarker.START_OF_IMAGE),
                frameHeader(code = 0xCA, width = 8, height = 8),
                scanAndEnd(),
            ),
        )

        assertNames("SOF10 (0xFFCA)", message)
    }

    @Test
    fun `the JFIF APP0 segment states the density and the unit it is counted in`() {
        val header = JpegHeader.parse(
            jpegStream(
                marker(JpegMarker.START_OF_IMAGE),
                jfifApp0(units = 1, horizontal = 300, vertical = 150),
                frameHeader(width = 8, height = 8),
                scanAndEnd(),
            ),
        )

        val density = header.density
        assertEquals(JpegDensityUnit.DOTS_PER_INCH, density?.unit)
        assertEquals(300, density?.horizontal)
        assertEquals(150, density?.vertical)
    }

    @Test
    fun `units of zero make the two densities a pixel aspect ratio`() {
        val header = JpegHeader.parse(
            jpegStream(
                marker(JpegMarker.START_OF_IMAGE),
                jfifApp0(units = 0, horizontal = 1, vertical = 1),
                frameHeader(width = 8, height = 8),
                scanAndEnd(),
            ),
        )

        assertEquals(JpegDensity(JpegDensityUnit.ASPECT_RATIO, 1, 1), header.density)
    }

    @Test
    fun `a stream with no JFIF segment states no density`() {
        val header = JpegHeader.parse(
            jpegStream(
                marker(JpegMarker.START_OF_IMAGE),
                frameHeader(width = 8, height = 8),
                scanAndEnd(),
            ),
        )

        assertNull(header.density)
    }

    /** An APP0 segment carrying some other identifier says nothing about the density. */
    @Test
    fun `an APP0 segment that is not JFIF is walked over`() {
        val header = JpegHeader.parse(
            jpegStream(
                marker(JpegMarker.START_OF_IMAGE),
                segment(JpegMarker.APP0, byteArrayOf(0x4A, 0x46, 0x58, 0x58, 0x00, 0x10)),
                frameHeader(width = 8, height = 8),
                scanAndEnd(),
            ),
        )

        assertNull(header.density)
        assertEquals(8, header.width)
    }

    @Test
    fun `a JFIF density of zero is refused naming APP0`() {
        val message = refusalFor(
            jpegStream(
                marker(JpegMarker.START_OF_IMAGE),
                jfifApp0(units = 1, horizontal = 0, vertical = 72),
                frameHeader(width = 8, height = 8),
                scanAndEnd(),
            ),
        )

        assertNames("APP0 (0xFFE0)", message)
    }

    @Test
    fun `a JFIF units field of three denotes no unit and is refused naming APP0`() {
        val message = refusalFor(
            jpegStream(
                marker(JpegMarker.START_OF_IMAGE),
                jfifApp0(units = 3),
                frameHeader(width = 8, height = 8),
                scanAndEnd(),
            ),
        )

        assertNames("APP0 (0xFFE0)", message)
    }

    /**
     * Table 13: absent an APP14 segment the default "shall be 1 if the image has
     * three components and 0 otherwise".
     */
    @Test
    fun `without APP14 the colour transform is 1 for three components and 0 otherwise`() {
        assertNull(headerOfFrameWith(componentCount = 3).adobeColourTransform)
        assertEquals(1, headerOfFrameWith(componentCount = 3).colourTransform)
        assertEquals(0, headerOfFrameWith(componentCount = 1).colourTransform)
        assertEquals(0, headerOfFrameWith(componentCount = 4).colourTransform)
    }

    /** With APP14 present the value in the encoded data is the one that holds. */
    @Test
    fun `APP14 states the colour transform and displaces the default`() {
        val header = JpegHeader.parse(
            jpegStream(
                marker(JpegMarker.START_OF_IMAGE),
                adobeApp14(transform = 0),
                frameHeader(width = 8, height = 8),
                scanAndEnd(),
            ),
        )

        assertEquals(0, header.adobeColourTransform)
        assertEquals(0, header.colourTransform)
    }

    @Test
    fun `an APP14 transform code of three is refused naming APP14`() {
        val message = refusalFor(
            jpegStream(
                marker(JpegMarker.START_OF_IMAGE),
                adobeApp14(transform = 3),
                frameHeader(width = 8, height = 8),
                scanAndEnd(),
            ),
        )

        assertNames("APP14 (0xFFEE)", message)
    }

    @Test
    fun `an APP14 segment of some other identifier is walked over`() {
        val header = JpegHeader.parse(
            jpegStream(
                marker(JpegMarker.START_OF_IMAGE),
                segment(JpegMarker.APP14, byteArrayOf(0x44, 0x75, 0x63, 0x6B, 0x79, 0x00)),
                frameHeader(width = 8, height = 8),
                scanAndEnd(),
            ),
        )

        assertNull(header.adobeColourTransform)
        assertEquals(1, header.colourTransform)
    }

    /** §B.1.1.2: "any marker may optionally be preceded by any number of fill bytes". */
    @Test
    fun `fill bytes before a marker are skipped`() {
        val header = JpegHeader.parse(
            jpegStream(
                marker(JpegMarker.START_OF_IMAGE),
                byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()),
                frameHeader(width = 5, height = 6),
                scanAndEnd(),
            ),
        )

        assertEquals(5, header.width)
        assertEquals(6, header.height)
    }

    /** SOI, EOI, TEM and the restart markers carry no segment to walk over. */
    @Test
    fun `a standalone marker is followed straight by the next marker`() {
        val header = JpegHeader.parse(
            jpegStream(
                marker(JpegMarker.START_OF_IMAGE),
                marker(JpegMarker.TEMPORARY),
                frameHeader(width = 5, height = 6),
                scanAndEnd(),
            ),
        )

        assertEquals(5, header.width)
    }

    @Test
    fun `segments of no interest are walked over by their length`() {
        val header = JpegHeader.parse(
            jpegStream(
                marker(JpegMarker.START_OF_IMAGE),
                segment(0xDB, ByteArray(65) { it.toByte() }),
                segment(0xFE, byteArrayOf(0x68, 0x69)),
                frameHeader(width = 5, height = 6),
                segment(JpegMarker.DEFINE_HUFFMAN_TABLES, ByteArray(29)),
                scanAndEnd(),
            ),
        )

        assertEquals(5, header.width)
        assertEquals(6, header.height)
    }

    @Test
    fun `data that do not start with SOI are refused naming the marker they start with`() {
        val message = refusalFor(
            jpegStream(
                frameHeader(width = 8, height = 8),
                scanAndEnd(),
            ),
        )

        assertNames("SOF0 (0xFFC0)", message)
        assertNames("SOI (0xFFD8)", message)
    }

    @Test
    fun `data that do not start with a marker at all are refused`() {
        val message = refusalFor(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47))

        assertTrue("0x89" in message, message)
        assertTrue("X'FF'" in message, message)
    }

    @Test
    fun `data with no frame header before the scan are refused naming the frames it looked for`() {
        val message = refusalFor(
            jpegStream(
                marker(JpegMarker.START_OF_IMAGE),
                jfifApp0(),
                scanAndEnd(),
            ),
        )

        assertNames("SOF0 (0xFFC0)", message)
        assertNames("SOF2 (0xFFC2)", message)
    }

    /** §B.1.1.4: the length parameter "includes the two bytes of the length parameter". */
    @Test
    fun `a segment length below two is refused naming its marker`() {
        val message = refusalFor(
            jpegStream(
                marker(JpegMarker.START_OF_IMAGE),
                segmentDeclaring(0xDB, declaredLength = 1, payload = ByteArray(8)),
                frameHeader(width = 8, height = 8),
                scanAndEnd(),
            ),
        )

        assertNames("DQT (0xFFDB)", message)
    }

    @Test
    fun `a segment running past the end of the data is refused naming its marker`() {
        val message = refusalFor(
            jpegStream(
                marker(JpegMarker.START_OF_IMAGE),
                segmentDeclaring(JpegMarker.APP0, declaredLength = 400, payload = ByteArray(8)),
            ),
        )

        assertNames("APP0 (0xFFE0)", message)
    }

    @Test
    fun `a frame header whose length disagrees with its component count is refused`() {
        val message = refusalFor(
            jpegStream(
                marker(JpegMarker.START_OF_IMAGE),
                // Nf says three components; the segment carries the bytes of two.
                frameHeader(width = 8, height = 8, componentCount = 3, components = defaultComponents(2)),
                scanAndEnd(),
            ),
        )

        assertNames("SOF0 (0xFFC0)", message)
    }

    /** Y = 0 defers the height to a DNL marker at the end of the first scan. */
    @Test
    fun `a frame that leaves its height to DNL is refused naming DNL`() {
        val message = refusalFor(
            jpegStream(
                marker(JpegMarker.START_OF_IMAGE),
                frameHeader(width = 8, height = 0),
                scanAndEnd(),
            ),
        )

        assertNames("SOF0 (0xFFC0)", message)
        assertNames("DNL (0xFFDC)", message)
    }

    @Test
    fun `a frame of no columns is refused`() {
        val message = refusalFor(
            jpegStream(
                marker(JpegMarker.START_OF_IMAGE),
                frameHeader(width = 0, height = 8),
                scanAndEnd(),
            ),
        )

        assertNames("SOF0 (0xFFC0)", message)
    }

    @Test
    fun `a sampling factor of zero is refused naming the frame marker`() {
        val message = refusalFor(
            jpegStream(
                marker(JpegMarker.START_OF_IMAGE),
                frameHeader(
                    width = 8,
                    height = 8,
                    componentCount = 1,
                    components = byteArrayOf(0x01, 0x01, 0x00),
                ),
                scanAndEnd(componentCount = 1),
            ),
        )

        assertNames("SOF0 (0xFFC0)", message)
        assertTrue("sampling" in message, message)
    }

    @Test
    fun `a second frame header is refused`() {
        val message = refusalFor(
            jpegStream(
                marker(JpegMarker.START_OF_IMAGE),
                frameHeader(width = 8, height = 8),
                frameHeader(width = 9, height = 9),
                scanAndEnd(),
            ),
        )

        assertNames("SOF0 (0xFFC0)", message)
        assertTrue("second frame" in message, message)
    }

    @Test
    fun `data that end where a marker must start are refused`() {
        val message = refusalFor(
            jpegStream(
                marker(JpegMarker.START_OF_IMAGE),
                frameHeader(width = 8, height = 8),
            ),
        )

        assertTrue("end" in message, message)
        assertNames("SOF0 (0xFFC0)", message)
    }

    /** X'FF00' is the stuffed pair inside entropy-coded data, never a marker. */
    @Test
    fun `a stuffed byte pair where a marker must start is refused`() {
        val message = refusalFor(
            jpegStream(
                marker(JpegMarker.START_OF_IMAGE),
                byteArrayOf(0xFF.toByte(), 0x00, 0x00, 0x00),
            ),
        )

        assertTrue("X'00'" in message, message)
    }

    @Test
    fun `empty data are refused`() {
        assertTrue("end" in refusalFor(ByteArray(0)))
    }

    private fun headerOfFrameWith(componentCount: Int): JpegHeader = JpegHeader.parse(
        jpegStream(
            marker(JpegMarker.START_OF_IMAGE),
            frameHeader(width = 8, height = 8, componentCount = componentCount),
            scanAndEnd(componentCount = componentCount),
        ),
    )

    private fun colourSpaceOfFrameWith(componentCount: Int): JpegColourSpace =
        headerOfFrameWith(componentCount).colourSpace

    private fun encodingOfFrameStartedBy(code: Int): JpegEncoding = JpegHeader.parse(
        jpegStream(
            marker(JpegMarker.START_OF_IMAGE),
            frameHeader(code = code, width = 8, height = 8),
            scanAndEnd(),
        ),
    ).encoding

    private fun refusalFor(bytes: ByteArray): String =
        assertFailsWith<IllegalArgumentException> { JpegHeader.parse(bytes) }.message ?: ""

    private fun assertNames(marker: String, message: String) =
        assertTrue(marker in message, "the refusal does not name $marker: $message")
}
