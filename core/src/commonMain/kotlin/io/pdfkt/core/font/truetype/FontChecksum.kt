/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.font.truetype

// OpenType 1.9.1 §"OpenType font file" — "Calculating Checksums";
// §"head — Font header table" (checksumAdjustment)

/**
 * "Subtract that value from 0xB1B0AFBA" — the constant the whole-font checksum
 * is taken away from to arrive at the `head` table's `checksumAdjustment`.
 *
 * Adding the difference back into the font is what makes the sum over the
 * finished file come to exactly this value, which is how a font's own arithmetic
 * can be checked without knowing anything about what is in it.
 */
internal const val CHECKSUM_ADJUSTMENT_MAGIC: Long = 0xB1B0AFBAL

/** A `uint32` is four bytes, and a checksum is a sum of `uint32` units. */
private const val CHECKSUM_UNIT: Int = 4

/** `uint32` arithmetic in a type that has more room than one: the sum wraps. */
private const val UINT32_MASK: Long = 0xFFFFFFFFL

/**
 * The checksum of [length] bytes of [bytes] starting at [from]:
 *
 * > Table checksums are the unsigned sum of the uint32 units of a given table.
 *
 * The clause gives the calculation as a fragment of C that adds `uint32` units
 * until it reaches `Table+((Length+3) & ~3) / sizeof(uint32)`, followed by the
 * note that says what the rounding up in that expression means for a table whose
 * length is not a multiple of four:
 *
 * > To accommodate data with a length that is not a multiple of four, the above
 * > algorithm must be modified to treat the data as though it contains zero
 * > padding to a length that is a multiple of four.
 *
 * Which is why the last unit is assembled byte by byte with zeros for the bytes
 * past the end rather than read as a `uint32`. In a font this library writes the
 * distinction never shows — "All tables must begin on four-byte boundaries, and
 * any remaining space between tables must be padded with zeros", so the padding
 * is really there — but the recorded length is "the actual length of data, not
 * the padded length", and it is the recorded length a checksum is computed over.
 *
 * The sum is returned as a `Long` holding a `uint32`: it wraps at 2^32, which a
 * signed `Int` would express as a negative number and no `uint32` field can
 * hold.
 */
internal fun tableChecksum(bytes: ByteArray, from: Int = 0, length: Int = bytes.size - from): Long {
    require(from >= 0 && length >= 0 && from + length <= bytes.size) {
        "a checksum was asked for over bytes $from..${from + length} of a ${bytes.size}-byte font"
    }
    val end = from + length
    var sum = 0L
    var at = from
    while (at < end) {
        var unit = 0L
        for (index in 0 until CHECKSUM_UNIT) {
            val byte = if (at + index < end) bytes[at + index].toLong() and 0xFF else 0L
            unit = (unit shl 8) or byte
        }
        sum = (sum + unit) and UINT32_MASK
        at += CHECKSUM_UNIT
    }
    return sum
}
