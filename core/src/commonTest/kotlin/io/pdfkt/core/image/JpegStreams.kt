/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.image

// ITU-T T.81 (09/92) | ISO/IEC 10918-1 §B.1.1 "Markers and marker segments",
// §B.2.2 "Frame header syntax", §B.2.3 "Scan header syntax"
// ITU-T T.871 (05/2011) | ISO/IEC 10918-5, the JFIF APP0 marker segment

/**
 * JPEG data streams written field by field, so that a test states the bytes it
 * expects a header to be read out of.
 *
 * A stream assembled here is the structure of Annex B and nothing else: no
 * entropy-coded data worth decoding, no quantization or Huffman tables. That is
 * exactly the reach of what is under test — [JpegHeader] stops at SOS — and it
 * is what lets a test say "a frame header whose length disagrees with its
 * component count" by writing that header and no other.
 *
 * The real thing is tested against too, on the JVM, where a JPEG comes out of
 * an encoder that never saw this library.
 */
internal fun jpegStream(vararg parts: ByteArray): ByteArray {
    var size = 0
    for (part in parts) size += part.size
    val bytes = ByteArray(size)
    var at = 0
    for (part in parts) {
        part.copyInto(bytes, at)
        at += part.size
    }
    return bytes
}

/** A marker: X'FF' and the code, the two bytes of §B.1.1.2. */
internal fun marker(code: Int): ByteArray = byteArrayOf(0xFF.toByte(), code.toByte())

/**
 * A marker segment: the marker, the two-byte length parameter that counts
 * itself, and [payload] (§B.1.1.4).
 */
internal fun segment(code: Int, payload: ByteArray): ByteArray =
    jpegStream(marker(code), uint16(payload.size + 2), payload)

/** A marker segment whose declared length is [declaredLength] whatever [payload] is. */
internal fun segmentDeclaring(code: Int, declaredLength: Int, payload: ByteArray): ByteArray =
    jpegStream(marker(code), uint16(declaredLength), payload)

/**
 * A frame header, §B.2.2: the sample precision P, the number of lines Y, the
 * number of samples per line X, the number of components Nf, and three bytes
 * for each component — its identifier, its two sampling factors packed into one
 * byte, and its quantization table selector.
 */
internal fun frameHeader(
    code: Int = JpegMarker.BASELINE_DCT,
    width: Int,
    height: Int,
    componentCount: Int = 3,
    precision: Int = 8,
    components: ByteArray = defaultComponents(componentCount),
): ByteArray = segment(
    code,
    jpegStream(
        byteArrayOf(precision.toByte()),
        uint16(height),
        uint16(width),
        byteArrayOf(componentCount.toByte()),
        components,
    ),
)

/** Component-specification bytes for [componentCount] components, each 1:1 sampled off table 0. */
internal fun defaultComponents(componentCount: Int): ByteArray {
    val bytes = ByteArray(componentCount * 3)
    for (component in 0 until componentCount) {
        bytes[component * 3] = (component + 1).toByte()
        bytes[component * 3 + 1] = 0x11
        bytes[component * 3 + 2] = 0
    }
    return bytes
}

/**
 * A JFIF APP0 segment: the identifier `JFIF\0`, the version 1.02, the units
 * field, Xdensity and Ydensity, and a thumbnail of no pixels.
 */
internal fun jfifApp0(
    units: Int = JpegDensityUnit.DOTS_PER_INCH.code,
    horizontal: Int = 72,
    vertical: Int = 72,
): ByteArray = segment(
    JpegMarker.APP0,
    jpegStream(
        byteArrayOf(0x4A, 0x46, 0x49, 0x46, 0x00),
        byteArrayOf(0x01, 0x02),
        byteArrayOf(units.toByte()),
        uint16(horizontal),
        uint16(vertical),
        byteArrayOf(0x00, 0x00),
    ),
)

/**
 * The Adobe APP14 segment ISO 32000-2:2020 §7.4.8 Table 13 refers to: the
 * identifier `Adobe`, a version, two flag fields and the transform code.
 */
internal fun adobeApp14(transform: Int): ByteArray = segment(
    JpegMarker.APP14,
    jpegStream(
        byteArrayOf(0x41, 0x64, 0x6F, 0x62, 0x65),
        byteArrayOf(0x00, 0x64),
        byteArrayOf(0x00, 0x00),
        byteArrayOf(0x00, 0x00),
        byteArrayOf(transform.toByte()),
    ),
)

/**
 * The end of a stream: a scan header for [componentCount] components, one byte
 * standing in for the entropy-coded data, and EOI.
 *
 * Nothing after SOS is read — the header is complete before the first scan — so
 * what is here only has to be the thing a reader stops at.
 */
internal fun scanAndEnd(componentCount: Int = 3): ByteArray {
    val components = ByteArray(componentCount * 2)
    for (component in 0 until componentCount) {
        components[component * 2] = (component + 1).toByte()
        components[component * 2 + 1] = 0
    }
    return jpegStream(
        segment(
            JpegMarker.START_OF_SCAN,
            jpegStream(byteArrayOf(componentCount.toByte()), components, byteArrayOf(0x00, 0x3F, 0x00)),
        ),
        byteArrayOf(0x00),
        marker(JpegMarker.END_OF_IMAGE),
    )
}

/** A two-byte parameter, big-endian, as every multi-byte JPEG parameter is written. */
internal fun uint16(value: Int): ByteArray = byteArrayOf((value shr 8).toByte(), value.toByte())
