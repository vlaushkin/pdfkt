/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.image

// ISO 32000-2:2020 §7.4.8 "DCTDecode filter", §7.4.4 "LZWDecode and FlateDecode
// filters", §8.9.5.1 "Image dictionaries — General" (Table 87)
// ITU-T T.81 (09/92) | ISO/IEC 10918-1 §B.1.1.3 "Marker assignments" (Table B.1),
// §B.2.1 "High-level syntax"
// Portable Network Graphics (PNG) Specification (Third Edition),
// W3C Recommendation 24 June 2025 §5.2 "PNG signature"

/**
 * An image from bytes whose format is read off their first bytes, rather than
 * stated by the caller: a JPEG becomes [jpegImage], a PNG becomes [pngImage].
 *
 * The two files say what they are, in a fixed place, and both specifications put
 * it there for exactly this purpose. A PNG datastream begins with the eight
 * bytes of §5.2 — "this signature indicates that the remainder of the datastream
 * contains a single PNG image" — and a JPEG interchange format datastream begins
 * with SOI, `X'FFD8'`, which §B.2.1 makes the first marker of the first (and, by
 * Table B.1, uniquely coded) segment of every one. Neither prefix can be the
 * other's, so the choice is decided by two bytes and is not a guess.
 *
 * That the *DSL* needs this is the reason it is here and not there:
 * a DSL that takes a picture as `ByteArray` has no business knowing what
 * `X'FFD8'` means, while this package is where the two
 * formats are already read. What each format then *becomes* is unchanged and
 * documented where it happens: a JPEG is embedded undecoded under `/DCTDecode`
 * (§7.4.8), a PNG is decoded and re-encoded under `/FlateDecode` (§7.4.4), and
 * this function chooses between them and does nothing else.
 *
 * @param id what the image is called within one document, as [ImageRef.id].
 * @param bytes the file, from its first byte on.
 * @throws IllegalArgumentException if [bytes] begin as neither format does, or
 *   if they are a JPEG this library will not embed (see [jpegImage]).
 * @throws PngFormatException if [bytes] carry the PNG signature and are not a
 *   datastream this library decodes (see [pngImage]).
 */
public fun imageFromBytes(id: String, bytes: ByteArray): ImageRef = when {
    bytes.startsWith(PNG_SIGNATURE) -> pngImage(id, bytes)
    bytes.startsWith(JPEG_SOI) -> jpegImage(id, bytes)
    else -> throw IllegalArgumentException(
        "the image \"$id\" is neither of the two formats a page can be given bytes of: a PNG " +
            "datastream begins with the eight-byte signature of the PNG specification §5.2 and a JPEG " +
            "with the SOI marker 0xFFD8 (ITU-T T.81 §B.2.1), and these ${bytes.size} bytes begin " +
            "${bytes.firstBytes()}. Supply the file as it was encoded, whole and from its first byte",
    )
}

/** Whether these bytes begin with [prefix] — the whole of what detection is. */
private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
    if (size < prefix.size) return false
    for (at in prefix.indices) {
        if (this[at] != prefix[at]) return false
    }
    return true
}

/**
 * The first bytes of a file as hexadecimal, for the message a caller who
 * supplied the wrong thing reads: `0x50 0x4B` is a zip and `0x25 0x50` a PDF,
 * and either fact ends the search that "unsupported image" would begin.
 */
private fun ByteArray.firstBytes(): String = take(REPORTED_BYTES)
    .joinToString(" ") { byte -> "0x${JpegMarker.hexByte(byte.toInt() and BYTE_MASK)}" }
    .ifEmpty { "nothing: the array is empty" }

/**
 * SOI, `X'FFD8'` — "the first two bytes of every JPEG data stream"
 * ([JpegMarker.START_OF_IMAGE]), and what [JpegHeader] itself begins by
 * checking.
 */
private val JPEG_SOI: ByteArray = byteArrayOf(JpegMarker.FILL.toByte(), JpegMarker.START_OF_IMAGE.toByte())

/** How many bytes of an unrecognized file the refusal quotes back. */
private const val REPORTED_BYTES: Int = 4

private const val BYTE_MASK: Int = 0xFF
