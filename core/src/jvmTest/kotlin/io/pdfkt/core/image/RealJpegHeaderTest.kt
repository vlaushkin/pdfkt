/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.image

import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// ISO 32000-2:2020 §7.4.8 "DCTDecode filter" (Table 13)
// ITU-T T.81 (09/92) | ISO/IEC 10918-1 §B.2.2 "Frame header syntax"
// ITU-T T.871 (05/2011) | ISO/IEC 10918-5, the JFIF APP0 marker segment

/**
 * A JPEG produced by an encoder that never saw this library, read back field by
 * field.
 *
 * `JpegHeaderTest` asserts against streams this repository assembles out of the
 * fields of Annex B; that proves the reader understands the structure as
 * written down, and says nothing about whether real encoders write it the way
 * the reading assumes. Here the fixture comes out of the JDK's image encoder —
 * used as a tool, never as a source — so the markers,
 * their order and the segments between them are somebody else's.
 *
 * No JPEG is committed for this. The bytes are made when the test runs, which
 * is also why the test can state the dimensions it expects: it chose them.
 */
class RealJpegHeaderTest {
    /**
     * The dimensions and the component count of a colour photograph, plus what
     * §7.4.8 Table 13 concludes about the colour transform when no Adobe APP14
     * segment is present.
     */
    @Test
    fun `a colour JPEG reports its dimensions and three components as DeviceRGB`() {
        val bytes = encodedJpeg(colourImage(width = 47, height = 23))

        val header = JpegHeader.parse(bytes)

        assertEquals(47, header.width)
        assertEquals(23, header.height)
        assertEquals(3, header.componentCount)
        assertEquals(JpegColourSpace.DEVICE_RGB, header.colourSpace)
        assertEquals("DeviceRGB", header.colourSpace.pdfName.name)
        assertEquals(8, header.bitsPerComponent)
        assertEquals(JpegEncoding.BASELINE, header.encoding)
        assertNull(header.adobeColourTransform, "the encoder wrote no APP14 segment")
        assertEquals(1, header.colourTransform, "Table 13: the default is 1 for three components")
    }

    /** The same file read by the encoder's own reader: the two agree on the dimensions. */
    @Test
    fun `the dimensions are the ones the encoder was given`() {
        val bytes = encodedJpeg(colourImage(width = 130, height = 71))

        val header = JpegHeader.parse(bytes)
        val decoded = assertNotNull(ImageIO.read(bytes.inputStream()))

        assertEquals(decoded.width, header.width)
        assertEquals(decoded.height, header.height)
    }

    @Test
    fun `a greyscale JPEG reports one component as DeviceGray`() {
        val grey = BufferedImage(16, 9, BufferedImage.TYPE_BYTE_GRAY)
        for (y in 0 until 9) {
            for (x in 0 until 16) grey.raster.setSample(x, y, 0, (x * 16) % 256)
        }

        val header = JpegHeader.parse(encodedJpeg(grey))

        assertEquals(16, header.width)
        assertEquals(9, header.height)
        assertEquals(1, header.componentCount)
        assertEquals(JpegColourSpace.DEVICE_GRAY, header.colourSpace)
        assertEquals(0, header.colourTransform, "Table 13: the default is 0 for anything but three components")
    }

    /** The JFIF segment the encoder writes: square pixels, stated as a ratio and not a size. */
    @Test
    fun `the JFIF segment of a written JPEG states the density`() {
        val header = JpegHeader.parse(encodedJpeg(colourImage(width = 32, height = 32)))

        val density = assertNotNull(header.density, "the encoder writes a JFIF APP0 segment")
        assertTrue(density.horizontal > 0 && density.vertical > 0, "$density")
        if (density.unit == JpegDensityUnit.ASPECT_RATIO) {
            assertEquals(density.horizontal, density.vertical, "square pixels: $density")
        }
    }

    /**
     * §7.4.8: "the DCTDecode filter shall support the progressive JPEG
     * extension … the distinction between baseline and progressive JPEG shall be
     * represented in the encoded data". Here it is, in a file written both ways.
     */
    @Test
    fun `a progressive JPEG is distinguished from a baseline one by its frame marker`() {
        val image = colourImage(width = 64, height = 40)

        val baseline = JpegHeader.parse(encodedJpeg(image))
        val progressive = JpegHeader.parse(encodedJpeg(image, progressive = true))

        assertEquals(JpegEncoding.BASELINE, baseline.encoding)
        assertEquals(JpegEncoding.PROGRESSIVE, progressive.encoding)
        assertEquals(baseline.width, progressive.width)
        assertEquals(baseline.height, progressive.height)
    }
}
