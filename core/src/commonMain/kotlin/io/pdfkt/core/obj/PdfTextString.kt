/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.obj

// ISO 32000-2:2020 §7.9.2.2 "Text string type", §7.11.5 "URL specifications"

/**
 * [value] as the string object §7.9.2.2 calls a text string — "character strings
 * that contain information intended to be human-readable", which is what a
 * document's title, an outline entry and an annotation's contents are.
 *
 * The clause offers three encodings — "PDFDocEncoding, the UTF-16BE Unicode
 * character encoding scheme, or (PDF 2.0) the UTF-8 Unicode character encoding
 * scheme" — and this picks between two of them by what the text holds:
 *
 * - text that is entirely printable ASCII is written as itself, because "7-bit
 *   U.S. ASCII is a strict subset of PDFDocEncoding" (§7.11.5). A title of Latin
 *   letters then reads in the file as it reads in the source, which is worth
 *   something for a value whose whole purpose is being read by a human;
 * - anything else is written in UTF-16BE, which "can encode all Unicode
 *   characters", after the marker the clause requires: "For text strings encoded
 *   in UTF-16BE, the first two bytes shall be 254 followed by 255."
 *
 * The remaining choice, PDFDocEncoding for the characters it does cover beyond
 * ASCII, is not taken: it would encode a subset of Latin-1 in one byte each and
 * everything else in two, at the cost of carrying Annex D's table for a saving
 * of one byte per accented character. UTF-16BE covers every string this library
 * can be handed, including the ones NOTE 5 warns about — "PDF readers … shall be
 * prepared to handle supplementary characters; that is, characters requiring
 * more than two bytes to represent" — which arrive as the surrogate pairs a
 * Kotlin string already holds them as and are copied through unchanged.
 *
 * The written form follows the same split: ASCII text goes in the literal form
 * of §7.3.4.2, where it stays legible, and UTF-16BE goes in the hexadecimal form
 * of §7.3.4.3, where a sequence of code units is not dressed up as escaped text.
 * Both forms denote the same bytes, so this is a matter of what the file looks
 * like when it is opened and nothing more.
 */
public fun pdfTextString(value: String): PdfString =
    if (value.all { character -> character.code in FIRST_ASCII..LAST_ASCII }) {
        PdfString(value.encodeToByteArray())
    } else {
        PdfString(utf16BigEndian(value), hex = true)
    }

/**
 * [value] in UTF-16BE, behind the byte order marker of §7.9.2.2.
 *
 * A Kotlin `Char` is a UTF-16 code unit already, so the encoding is the pair of
 * bytes of each unit, most significant first — including the two units of a
 * surrogate pair, which are two code units of UTF-16 and are written as such.
 */
private fun utf16BigEndian(value: String): ByteArray {
    val bytes = ByteArray(BYTE_ORDER_MARKER.size + value.length * BYTES_PER_UNIT)
    BYTE_ORDER_MARKER.copyInto(bytes)
    for (index in value.indices) {
        val unit = value[index].code
        val at = BYTE_ORDER_MARKER.size + index * BYTES_PER_UNIT
        bytes[at] = (unit shr Byte.SIZE_BITS).toByte()
        bytes[at + 1] = (unit and BYTE_MASK).toByte()
    }
    return bytes
}

/**
 * "the first two bytes shall be 254 followed by 255. These two bytes represent
 * the Unicode byte order marker, ZERO WIDTH NO-BREAK SPACE (U+FEFF)".
 */
private val BYTE_ORDER_MARKER: ByteArray = byteArrayOf(254.toByte(), 255.toByte())

/** SPACE (20h), the first printable character of ASCII. */
private const val FIRST_ASCII: Int = 0x20

/** TILDE (7Eh), the last printable character of ASCII. */
private const val LAST_ASCII: Int = 0x7E

private const val BYTES_PER_UNIT: Int = 2

private const val BYTE_MASK: Int = 0xFF
