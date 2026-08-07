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
import kotlin.test.assertTrue

// ISO 32000-2:2020 §7.4.8 "DCTDecode filter", §7.4.4 "LZWDecode and FlateDecode
// filters", §8.9.5.1 "Image dictionaries" (Table 87)
// ITU-T T.81 (09/92) §B.2.1 "High-level syntax"
// PNG Specification (Third Edition) §5.2 "PNG signature"

/**
 * Which decoder a file gets, decided by the file: [imageFromBytes] over the two
 * formats a page may be handed bytes of, and over bytes that are neither.
 *
 * The two routes are asserted apart from each other by the one thing that
 * distinguishes them in the file — the filter of the image the caller gets back.
 * A JPEG keeps its own bytes under `/DCTDecode` (§7.4.8); a PNG is decoded and
 * deflated under `/FlateDecode` (§7.4.4). So a detection that dispatched both
 * formats to one decoder cannot pass, whichever decoder it picked.
 */
class ImageBytesTest {

    /** A JPEG is recognized by SOI and embedded as the frame header describes it. */
    @Test
    fun `a JPEG is recognized by its SOI marker and embedded undecoded`() {
        val bytes = jpegStream(
            marker(JpegMarker.START_OF_IMAGE),
            jfifApp0(),
            frameHeader(width = 47, height = 23, componentCount = 3),
            scanAndEnd(componentCount = 3),
        )

        val image = imageFromBytes("photo", bytes)

        assertEquals(ImageFilter.DCT_DECODE, image.filter, "a JPEG is not embedded under DCTDecode")
        assertEquals(47, image.width)
        assertEquals(23, image.height)
        assertEquals(ImageColorSpace.DEVICE_RGB, image.colorSpace)
    }

    /** A PNG is recognized by the eight bytes of §5.2 and decoded to samples. */
    @Test
    fun `a PNG is recognized by its signature and decoded to samples`() {
        val rows = listOf(byteArrayOf(0x10, 0x20, 0x30, 0x40, 0x50, 0x60))
        val bytes = pngOf(width = 2, height = 1, bitDepth = 8, colourType = 2, rows = rows)

        val image = imageFromBytes("badge", bytes)

        assertEquals(ImageFilter.FLATE_DECODE, image.filter, "a PNG is not written under FlateDecode")
        assertEquals(2, image.width)
        assertEquals(1, image.height)
        assertEquals(ImageColorSpace.DEVICE_RGB, image.colorSpace)
    }

    /**
     * Bytes that begin as neither format are refused with both prefixes named:
     * a caller who handed over a zip, a PDF or a decoded bitmap learns which
     * two things the parameter takes.
     */
    @Test
    fun `bytes that begin as neither format are refused naming both`() {
        val refused = assertFailsWith<IllegalArgumentException> {
            imageFromBytes("mystery", byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x00))
        }
        val message = refused.message.orEmpty()
        assertTrue(message.contains("PNG"), "the refusal does not name PNG: $message")
        assertTrue(message.contains("0xFFD8"), "the refusal does not name the SOI marker: $message")
        assertTrue(message.contains("0x50 0x4B"), "the refusal does not quote the bytes back: $message")
    }

    /**
     * A truncated signature is not a signature. Seven of the eight bytes of §5.2
     * describe no PNG, and the prefix test says so instead of running off the end
     * of the array.
     */
    @Test
    fun `a truncated PNG signature is not taken for a PNG`() {
        val truncated = PNG_SIGNATURE.copyOf(PNG_SIGNATURE.size - 1)

        assertFailsWith<IllegalArgumentException> { imageFromBytes("half a signature", truncated) }
    }

    /** No bytes at all name no format, and say so rather than indexing nothing. */
    @Test
    fun `an empty array is refused`() {
        assertFailsWith<IllegalArgumentException> { imageFromBytes("nothing", ByteArray(0)) }
    }
}
