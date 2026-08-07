/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.image

import io.pdfkt.core.filter.flateEncode

// Portable Network Graphics (PNG) Specification (Third Edition),
// W3C Recommendation 24 June 2025
//   §5.2 "PNG signature", §5.3 "Chunk layout", §5.5 "CRC algorithm",
//   §7.1 "Integers and byte order", §9.2 "Filter types for filter method 0"
//   (Table 11), §10.2 "Compression of the sequence of filtered scanlines",
//   §11.2.1 "IHDR Image header"

/**
 * PNG datastreams written field by field, so that a test states the bytes it
 * expects an image to be read out of.
 *
 * Everything here is the *encoder* side of the clauses under test, which is
 * what makes it worth writing rather than committing files: a test can say "a
 * scanline filtered with type 4" or "a palette of five entries whose bit depth
 * admits four" by writing that datastream and nothing else. The filtering in
 * [filterScanlines] is the Filter Function column of Table 11, the column the
 * decoder never reads, so a round trip through it is the two halves of the
 * table checked against each other rather than one half against itself.
 *
 * The CRC in [pngChunk] is computed bit by bit from the sentence of §5.5 —
 * "the 32-bit CRC is initialized to all 1's, and then the data from each byte
 * is processed from the least significant bit (1) to the most significant bit
 * (128). After all the data bytes are processed, the CRC is inverted" — where
 * the decoder computes it a byte at a time through a table. Real files, written
 * by an encoder that never saw this library, are decoded in `PngForeignTest`,
 * and they are what proves both of those agree with the rest of the world.
 */
internal fun concat(vararg parts: ByteArray): ByteArray {
    var size = 0
    for (part in parts) size += part.size
    val joined = ByteArray(size)
    var at = 0
    for (part in parts) {
        part.copyInto(joined, at)
        at += part.size
    }
    return joined
}

/** A PNG datastream: the signature of §5.2 followed by [chunks]. */
internal fun pngDatastream(vararg chunks: ByteArray): ByteArray = concat(PNG_SIGNATURE, concat(*chunks))

/**
 * A chunk, §5.3: the four-byte length of the data field, the four-byte type,
 * the data, and the CRC of the type and the data.
 */
internal fun pngChunk(type: String, data: ByteArray = ByteArray(0)): ByteArray {
    val typeAndData = concat(type.encodeToByteArray(), data)
    return concat(uint32(data.size), typeAndData, uint32(crcBitByBit(typeAndData)))
}

/** A chunk whose CRC field is [crc] whatever its data are — a corrupted chunk. */
internal fun pngChunkWithCrc(type: String, data: ByteArray, crc: Int): ByteArray =
    concat(uint32(data.size), type.encodeToByteArray(), data, uint32(crc))

/** A chunk whose length field is [declaredLength] whatever its data are. */
internal fun pngChunkDeclaring(type: String, declaredLength: Int, data: ByteArray): ByteArray {
    val typeAndData = concat(type.encodeToByteArray(), data)
    return concat(uint32(declaredLength), typeAndData, uint32(crcBitByBit(typeAndData)))
}

/**
 * An IHDR chunk, §11.2.1: "Width 4 bytes, Height 4 bytes, Bit depth 1 byte,
 * Color type 1 byte, Compression method 1 byte, Filter method 1 byte, Interlace
 * method 1 byte."
 */
internal fun ihdr(
    width: Int,
    height: Int,
    bitDepth: Int,
    colourType: Int,
    compressionMethod: Int = 0,
    filterMethod: Int = 0,
    interlaceMethod: Int = 0,
): ByteArray = pngChunk(
    "IHDR",
    concat(
        uint32(width),
        uint32(height),
        byteArrayOf(
            bitDepth.toByte(),
            colourType.toByte(),
            compressionMethod.toByte(),
            filterMethod.toByte(),
            interlaceMethod.toByte(),
        ),
    ),
)

/** An IDAT chunk carrying [data] — the compressed image data, or a part of them (§10.2). */
internal fun idat(data: ByteArray): ByteArray = pngChunk("IDAT", data)

/** The IEND chunk, §11.2.4, whose "data field is empty". */
internal fun iend(): ByteArray = pngChunk("IEND")

/**
 * A whole image: the datastream for [rows] of samples, each row filtered with
 * the filter type at the same index of [filterTypes] and the lot compressed
 * into one IDAT chunk.
 */
internal fun pngOf(
    width: Int,
    height: Int,
    bitDepth: Int,
    colourType: Int,
    rows: List<ByteArray>,
    filterTypes: List<Int> = List(height) { 0 },
    extraChunks: List<ByteArray> = emptyList(),
    bytesPerPixel: Int = maxOf(1, channelsOf(colourType) * bitDepth / 8),
): ByteArray = pngDatastream(
    ihdr(width, height, bitDepth, colourType),
    concat(*extraChunks.toTypedArray()),
    idat(flateEncode(filterScanlines(rows, filterTypes, bytesPerPixel))),
    iend(),
)

/**
 * [rows] filtered per Table 11 — the Filter Function column — and serialized as
 * §7.3 requires: an "equal length sequence of bytes preceded by a filter type
 * byte".
 *
 * `Orig(a)`, `Orig(b)` and `Orig(c)` are the bytes named in Table 10, and the
 * zeroes for the bytes outside the image are the clause's: "the bytes 'to the
 * left of' the first pixel in a scanline shall be treated as being zero … the
 * entire prior scanline … shall be treated as being zeroes for the first
 * scanline".
 */
internal fun filterScanlines(rows: List<ByteArray>, filterTypes: List<Int>, bytesPerPixel: Int): ByteArray {
    val bytesPerRow = if (rows.isEmpty()) 0 else rows[0].size
    val filtered = ByteArray(rows.size * (1 + bytesPerRow))
    var at = 0
    for (y in rows.indices) {
        val filterType = filterTypes[y]
        val row = rows[y]
        val prior = if (y > 0) rows[y - 1] else ByteArray(bytesPerRow)
        filtered[at++] = filterType.toByte()
        for (index in 0 until bytesPerRow) {
            val x = row[index].toInt() and 0xFF
            val a = if (index >= bytesPerPixel) row[index - bytesPerPixel].toInt() and 0xFF else 0
            val b = prior[index].toInt() and 0xFF
            val c = if (index >= bytesPerPixel) prior[index - bytesPerPixel].toInt() and 0xFF else 0
            val value = when (filterType) {
                0 -> x
                1 -> x - a
                2 -> x - b
                3 -> x - (a + b) / 2
                4 -> x - paethPredictor(a, b, c)
                else -> x
            }
            filtered[at++] = value.toByte()
        }
    }
    return filtered
}

/** How many samples a pixel of colour type [colourType] carries (Table 12). */
internal fun channelsOf(colourType: Int): Int = when (colourType) {
    0, 3 -> 1
    2 -> 3
    4 -> 2
    else -> 4
}

/** A PNG four-byte unsigned integer, "network byte order … most significant byte first" (§7.1). */
internal fun uint32(value: Int): ByteArray = byteArrayOf(
    (value ushr 24).toByte(),
    (value ushr 16).toByte(),
    (value ushr 8).toByte(),
    value.toByte(),
)

/**
 * The CRC of [data] per §5.5, a bit at a time.
 *
 * The register holds the remainder with the coefficient of x31 in its least
 * significant bit, so it shifts right and the polynomial — x32 + x26 + x23 +
 * x22 + x16 + x12 + x11 + x10 + x8 + x7 + x5 + x4 + x2 + x + 1 — is exclusive-
 * ored in when the bit shifted out disagrees with the bit of data going in.
 */
internal fun crcBitByBit(data: ByteArray): Int {
    var polynomial = 0
    for (exponent in intArrayOf(26, 23, 22, 16, 12, 11, 10, 8, 7, 5, 4, 2, 1, 0)) {
        polynomial = polynomial or (1 shl (31 - exponent))
    }
    var register = -1
    for (byte in data) {
        for (bit in 0 until 8) {
            val input = (byte.toInt() ushr bit) and 1
            val output = register and 1
            register = register ushr 1
            if (input xor output != 0) register = register xor polynomial
        }
    }
    return register.inv()
}
