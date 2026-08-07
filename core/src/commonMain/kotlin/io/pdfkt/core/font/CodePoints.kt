/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.font

// ISO 32000-2:2020 Annex D "Character sets and encodings",
// §7.9.2.2 "Text string type"

/**
 * The characters of [text], one at a time, with where each begins.
 *
 * A Kotlin `String` is a sequence of UTF-16 code units, and Annex D maps
 * *characters* to codes — so a text has to be walked by character, not by
 * `Char`, or a character outside the basic multilingual plane is seen as two
 * halves of nothing. §7.9.2.2 is where the specification meets the same problem
 * from the other side: a text string may be "encoded in … the UTF-16BE Unicode
 * character encoding scheme", and UTF-16 represents such a character as a
 * surrogate pair.
 *
 * The pairing rule is the one that scheme is defined by: a high surrogate
 * (U+D800–U+DBFF) followed by a low surrogate (U+DC00–U+DFFF) is one character,
 * `0x10000 + ((high - 0xD800) << 10) + (low - 0xDC00)`.
 *
 * An unpaired surrogate is passed through as itself rather than repaired or
 * skipped. It encodes no character in any of the encodings this library writes,
 * so it will be refused — with its own position, which is the point — by
 * whoever is doing the encoding.
 *
 * @param action receives the index in [text] where the character begins, and
 *   the character's code point. The index is the string offset a caller can act
 *   on, not a count of characters.
 */
internal inline fun forEachCodePoint(text: String, action: (index: Int, codePoint: Int) -> Unit) {
    var index = 0
    while (index < text.length) {
        val codePoint = codePointAt(text, index)
        action(index, codePoint)
        index += if (codePoint > BMP_MAX) SURROGATE_PAIR_LENGTH else 1
    }
}

/**
 * The character beginning at [index] of [text], as a code point — a surrogate
 * pair read as the one character it stands for, anything else as itself.
 */
internal fun codePointAt(text: String, index: Int): Int {
    val high = text[index]
    if (!high.isHighSurrogate() || index + 1 >= text.length) return high.code
    val low = text[index + 1]
    if (!low.isLowSurrogate()) return high.code
    return BMP_MAX + 1 + ((high.code - HIGH_SURROGATE_FIRST) shl SURROGATE_BITS) + (low.code - LOW_SURROGATE_FIRST)
}

/** How many characters [text] holds, which is its length only if it has no surrogate pairs. */
internal fun codePointCount(text: String): Int {
    var count = 0
    forEachCodePoint(text) { _, _ -> count++ }
    return count
}

/**
 * [codePoint] as the text it is — one `Char`, or the surrogate pair that stands
 * for a character beyond the basic multilingual plane.
 *
 * For error messages: an error about a character that does not print the
 * character is an error the reader has to decode by hand.
 */
internal fun codePointToString(codePoint: Int): String {
    if (codePoint <= BMP_MAX) return codePoint.toChar().toString()
    val offset = codePoint - (BMP_MAX + 1)
    return charArrayOf(
        (HIGH_SURROGATE_FIRST + (offset shr SURROGATE_BITS)).toChar(),
        (LOW_SURROGATE_FIRST + (offset and LOW_SURROGATE_MASK)).toChar(),
    ).concatToString()
}

/** The last code point of the basic multilingual plane: above it, UTF-16 needs a pair. */
private const val BMP_MAX: Int = 0xFFFF
private const val HIGH_SURROGATE_FIRST: Int = 0xD800
private const val LOW_SURROGATE_FIRST: Int = 0xDC00

/** Ten bits of the offset go in the high surrogate and ten in the low one. */
private const val SURROGATE_BITS: Int = 10
private const val LOW_SURROGATE_MASK: Int = 0x3FF
private const val SURROGATE_PAIR_LENGTH: Int = 2
