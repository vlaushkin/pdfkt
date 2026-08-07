/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.api

import io.pdfkt.core.filter.flateEncode

// ITU-T T.81 (09/92) | ISO/IEC 10918-1 §B.1.1.3 "Marker assignments",
// §B.2.2 "Frame header syntax", §B.2.3 "Scan header syntax"
// Portable Network Graphics (PNG) Specification (Third Edition),
// W3C Recommendation 24 June 2025
//   §5.2 "PNG signature", §5.3 "Chunk layout", §5.5 "CRC algorithm",
//   §7.3 "Filtering", §11.2.1 "IHDR Image header", §10.2 "Compression"

/**
 * The two pictures this module's tests hand to `image()`, written field by
 * field.
 *
 * They are built rather than committed for the reason every fixture in this
 * repository is: a test that states the bytes of the image it draws is a test
 * whose dimensions, colour type and format are readable in the test. What they
 * are *not* is a second implementation of either format — the engine's own
 * fixtures cover the formats (`:core`'s `JpegStreams` and `PngDatastreams`);
 * these two exist so that the facade can be asked which decoder it picked
 * without reaching into another module's test source set, which is not
 * published.
 */

/**
 * A JPEG data stream of [width] × [height] in three components, carrying no
 * scan data.
 *
 * Everything the engine reads of a photograph is stated before the first scan —
 * the frame header of §B.2.2 — so a stream that ends there is a fixture for
 * everything asked here, and its segment lengths are the ones the clauses give
 * them: a marker walk that tolerated a wrong length would not be the walk the
 * library performs on a real file.
 */
internal fun testJpeg(width: Int, height: Int): ByteArray = byteArrayOf(
    // SOI — "the first two bytes of every JPEG data stream", and what the
    // facade's detection reads.
    MARKER, 0xD8.toByte(),
    // SOF0 — baseline DCT frame header. Lf = 8 + 3 × Nf = 17 for three components.
    MARKER, 0xC0.toByte(),
    0x00, 0x11,
    // P: 8-bit samples, which is what DCTDecode delivers (Table 87).
    0x08,
    // Y, then X: the number of lines and the samples per line.
    (height shr 8).toByte(), height.toByte(),
    (width shr 8).toByte(), width.toByte(),
    // Nf = 3 — a colour image, so /ColorSpace is DeviceRGB.
    0x03,
    // Ci, Hi Vi, Tqi for each of the three components.
    0x01, 0x11, 0x00,
    0x02, 0x11, 0x01,
    0x03, 0x11, 0x01,
    // SOS — scan header. Ls = 6 + 2 × Ns = 8 for one component in the scan.
    MARKER, 0xDA.toByte(),
    0x00, 0x08,
    0x01,
    0x01, 0x00,
    0x00, 0x3F, 0x00,
    // EOI — end of image.
    MARKER, 0xD9.toByte(),
)

/**
 * A PNG datastream of [width] × [height] truecolour pixels at 8 bits a sample:
 * the signature, IHDR, one IDAT and IEND.
 *
 * A whole one, not a header: the facade's `image()` hands a PNG to a decoder
 * that reads the samples out (§7.3, §10.2), so a fixture stopping after IHDR
 * would test the detection against a file the decoder refuses. Every scanline
 * is filtered with type 0, "None", which the decoder still has to unfilter.
 */
internal fun testPng(width: Int, height: Int): ByteArray {
    val bytesPerRow = width * RGB_CHANNELS
    // "Each scanline is an equal length sequence of bytes preceded by a filter
    // type byte" (§7.3), the filter type being 0 for every row here.
    val scanlines = ByteArray(height * (1 + bytesPerRow))
    var at = 0
    for (y in 0 until height) {
        scanlines[at++] = 0
        for (x in 0 until width) {
            scanlines[at++] = (x * SAMPLE_STEP).toByte()
            scanlines[at++] = (y * SAMPLE_STEP).toByte()
            scanlines[at++] = MID_SAMPLE
        }
    }
    return concat(
        PNG_SIGNATURE,
        pngChunk(
            "IHDR",
            concat(
                uint32(width),
                uint32(height),
                // Bit depth 8, colour type 2 (truecolour), and the three methods
                // the format fixes: deflate, adaptive filtering, no interlace.
                byteArrayOf(BIT_DEPTH, TRUECOLOUR, 0, 0, 0),
            ),
        ),
        pngChunk("IDAT", flateEncode(scanlines)),
        pngChunk("IEND"),
    )
}

/**
 * A chunk, §5.3: "A four-byte unsigned integer giving the number of bytes in
 * the chunk's data field … A four-byte chunk type … The data bytes … A four-byte
 * CRC calculated on the preceding bytes in the chunk, including the chunk type
 * field and chunk data fields, but not including the length field."
 */
private fun pngChunk(type: String, data: ByteArray = ByteArray(0)): ByteArray {
    val typeAndData = concat(type.encodeToByteArray(), data)
    return concat(uint32(data.size), typeAndData, uint32(pngCrc(typeAndData)))
}

/**
 * The CRC of §5.5, computed straight from the sentence that defines it: "the
 * 32-bit CRC is initialized to all 1's, and then the data from each byte is
 * processed from the least significant bit (1) to the most significant bit
 * (128). After all the data bytes are processed, the CRC is inverted."
 *
 * Bit by bit and without a table, because a fixture is read more often than it
 * is run and there is nothing to be gained here by being quick.
 */
private fun pngCrc(bytes: ByteArray): Int {
    var register = -1 // all 1's
    for (byte in bytes) {
        register = register xor (byte.toInt() and BYTE_MASK)
        repeat(BITS_PER_BYTE) {
            register = if (register and 1 != 0) {
                (register ushr 1) xor CRC_POLYNOMIAL
            } else {
                register ushr 1
            }
        }
    }
    return register.inv()
}

/** A four-byte unsigned integer, most significant byte first (§7.1). */
private fun uint32(value: Int): ByteArray = byteArrayOf(
    (value ushr 24).toByte(),
    (value ushr 16).toByte(),
    (value ushr 8).toByte(),
    value.toByte(),
)

private fun concat(vararg parts: ByteArray): ByteArray {
    val joined = ByteArray(parts.sumOf { part -> part.size })
    var at = 0
    for (part in parts) {
        part.copyInto(joined, at)
        at += part.size
    }
    return joined
}

/** "X'FF' … marks the beginning of a marker" (T.81 §B.1.1.3). */
private const val MARKER: Byte = 0xFF.toByte()

/** The eight bytes every PNG datastream begins with (§5.2). */
private val PNG_SIGNATURE: ByteArray = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

/** Colour type 2, truecolour: "each pixel is an R,G,B triple" (Table 12). */
private const val TRUECOLOUR: Byte = 2

private const val BIT_DEPTH: Byte = 8

private const val RGB_CHANNELS: Int = 3

/** How much brighter each column and each row is than the one before it. */
private const val SAMPLE_STEP: Int = 7

private const val MID_SAMPLE: Byte = 0x40

/**
 * "The CRC polynomial employed is x32 + x26 + x23 + x22 + x16 + x12 + x11 + x10
 * + x8 + x7 + x5 + x4 + x2 + x + 1" (§5.5), with the bits reversed, which is
 * how a register processed least significant bit first consumes it.
 */
private const val CRC_POLYNOMIAL: Int = -0x12477CE0 // 0xEDB88320

private const val BITS_PER_BYTE: Int = 8

private const val BYTE_MASK: Int = 0xFF
