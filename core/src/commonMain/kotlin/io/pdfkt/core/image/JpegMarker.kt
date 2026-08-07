/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.image

// ITU-T T.81 (09/92) | ISO/IEC 10918-1 §B.1.1.2 "Markers",
// §B.1.1.3 "Marker assignments" (Table B.1), §B.1.1.4 "Marker segments"
// ISO 32000-2:2020 §7.4.8 "DCTDecode filter"

/**
 * The marker codes of the JPEG interchange format, and the names Table B.1
 * gives them.
 *
 * A marker is two bytes: X'FF' followed by a code that is neither X'00' nor
 * X'FF'. X'FF00' is not a marker at all — it is the stuffed byte pair that
 * carries a data value of X'FF' through entropy-coded data — and X'FFFF' is a
 * fill byte followed by whatever comes next, since any marker may be preceded
 * by any number of fill bytes.
 *
 * Most markers introduce a segment whose first parameter is a two-byte length
 * that counts itself. The exceptions are the markers that stand alone —
 * SOI, EOI, TEM and the eight restart markers — and they are the reason this
 * file exists as more than a handful of constants: the length of a segment can
 * only be read once it is known that the marker has one.
 *
 * The names are here because every refusal in this package has to say which
 * marker it refused. "SOF3 (0xFFC3)" is a fact a caller can check against a hex
 * dump of their own file; "malformed JPEG" is not.
 */
internal object JpegMarker {
    /** TEM — temporary use in arithmetic coding; carries no segment. */
    const val TEMPORARY: Int = 0x01

    /** SOF0 — baseline DCT, the frame ISO 32000-2:2020 §7.4.8 is written over. */
    const val BASELINE_DCT: Int = 0xC0

    /** SOF1 — extended sequential DCT, Huffman coding. */
    const val EXTENDED_SEQUENTIAL_DCT: Int = 0xC1

    /** SOF2 — progressive DCT, Huffman coding; §7.4.8's "progressive JPEG extension". */
    const val PROGRESSIVE_DCT: Int = 0xC2

    /** DHT — define Huffman tables. Inside the SOFn code range but not a frame. */
    const val DEFINE_HUFFMAN_TABLES: Int = 0xC4

    /** JPG — reserved for JPEG extensions. Inside the SOFn code range but not a frame. */
    const val JPEG_EXTENSIONS: Int = 0xC8

    /** DAC — define arithmetic coding conditioning. Inside the SOFn code range but not a frame. */
    const val DEFINE_ARITHMETIC_CONDITIONING: Int = 0xCC

    /** RST0 — the first of the eight restart markers, which carry no segment. */
    const val FIRST_RESTART: Int = 0xD0

    /** RST7 — the last of them. */
    const val LAST_RESTART: Int = 0xD7

    /** SOI — start of image; the first two bytes of every JPEG data stream. */
    const val START_OF_IMAGE: Int = 0xD8

    /** EOI — end of image. */
    const val END_OF_IMAGE: Int = 0xD9

    /** SOS — start of scan; entropy-coded data follow its segment. */
    const val START_OF_SCAN: Int = 0xDA

    /** APP0 — the application segment JFIF puts the density in. */
    const val APP0: Int = 0xE0

    /** APP14 — "X'FFEE, sometimes called 'APPE' or 'APP14'" (§7.4.8, Table 13). */
    const val APP14: Int = 0xEE

    /** X'FF' — the first byte of every marker, and the fill byte. */
    const val FILL: Int = 0xFF

    /**
     * Whether [code] starts a frame — one of SOFn.
     *
     * The frame markers occupy X'FFC0' to X'FFCF' with three holes in it: DHT,
     * JPG and DAC sit in the same range and start something else. Every SOFn
     * has the frame header syntax of §B.2.2, whether or not this library can do
     * anything with the frame it starts.
     */
    fun isStartOfFrame(code: Int): Boolean = code in BASELINE_DCT..0xCF &&
        code != DEFINE_HUFFMAN_TABLES &&
        code != JPEG_EXTENSIONS &&
        code != DEFINE_ARITHMETIC_CONDITIONING

    /**
     * Whether [code] is a marker that carries no segment, and is therefore
     * followed immediately by the next marker or by entropy-coded data.
     */
    fun isStandalone(code: Int): Boolean = code == TEMPORARY ||
        code == START_OF_IMAGE ||
        code == END_OF_IMAGE ||
        code in FIRST_RESTART..LAST_RESTART

    /**
     * How a refusal names the marker [code]: its abbreviation from Table B.1 and
     * the two bytes it is written as, as in `SOF3 (0xFFC3)`.
     */
    fun nameOf(code: Int): String = "${abbreviationOf(code)} (0xFF${hexByte(code)})"

    /** A single byte the way the specifications write one, as in `C3`. */
    fun hexByte(value: Int): String = value.toString(16).uppercase().padStart(2, '0')

    private fun abbreviationOf(code: Int): String = when (code) {
        in BASELINE_DCT..0xCF -> when (code) {
            DEFINE_HUFFMAN_TABLES -> "DHT"
            JPEG_EXTENSIONS -> "JPG"
            DEFINE_ARITHMETIC_CONDITIONING -> "DAC"
            else -> "SOF${code - BASELINE_DCT}"
        }
        in FIRST_RESTART..LAST_RESTART -> "RST${code - FIRST_RESTART}"
        START_OF_IMAGE -> "SOI"
        END_OF_IMAGE -> "EOI"
        START_OF_SCAN -> "SOS"
        0xDB -> "DQT"
        0xDC -> "DNL"
        0xDD -> "DRI"
        0xDE -> "DHP"
        0xDF -> "EXP"
        in APP0..0xEF -> "APP${code - APP0}"
        in 0xF0..0xFD -> "JPG${code - 0xF0}"
        0xFE -> "COM"
        TEMPORARY -> "TEM"
        else -> "a reserved marker"
    }
}
