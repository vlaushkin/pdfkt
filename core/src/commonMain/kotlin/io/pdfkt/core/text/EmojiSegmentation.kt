/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.text

import io.pdfkt.core.font.codePointAt

// Unicode Technical Standard #51 "Unicode Emoji" §1.4.2 "Emoji Properties and
// Data Files", §1.4.6 "Emoji Sequences" (ED-13 … ED-17), §2.1 "Emoji
// Presentation", §2.2 "Diversity", §2.3 "Emoji ZWJ Sequences".
// No PDF clause applies: detection is a Unicode property question, answered in
// L4's resolve stage where text splits into runs.

/**
 * A stretch of a string that is either all emoji or none.
 *
 * The unit an emoji policy acts on. It carries [index] because everything
 * downstream is about positions in the text the caller wrote: an emoji replaced
 * by a placeholder has to be replaced *where it was*, and a character the font
 * cannot draw is reported with its index in that same string, per the
 * no-silent-loss policy.
 *
 * @property text the characters of the segment, exactly as they appear in the
 *   source — never normalised, never reordered.
 * @property index where the segment begins in the source string, as a string
 *   offset and not a count of characters.
 */
internal sealed interface TextSegment {
    val text: String
    val index: Int

    /** Text that is not emoji. What a font is asked to draw. */
    data class Plain(override val text: String, override val index: Int) : TextSegment

    /**
     * One emoji, whole: a single character, or the sequence of up to a dozen
     * that stands for one picture — a flag, a skin-toned hand, a family joined
     * by zero-width joiners. What an [io.pdfkt.core.style.EmojiPolicy] acts on,
     * and it acts on the whole of it: a family emoji stripped code point by code
     * point would leave three joiners and half a family behind.
     */
    data class Emoji(override val text: String, override val index: Int) : TextSegment
}

/**
 * [text] cut into runs of emoji and runs of everything else, in order, covering
 * every character exactly once.
 *
 * The cuts are made where UTS #51 §1.4.6 puts them. An `emoji_sequence` is one
 * of:
 *
 * - an `emoji_presentation_sequence` — a character with `Emoji_Presentation`, or
 *   any `Emoji` character followed by the emoji variation selector U+FE0F (ED-9,
 *   ED-15). The selector matters in both directions: it is what makes ™ an emoji
 *   and, as U+FE0E, what makes ☀ text (§2.1, "the emoji variation sequence … or
 *   the text variation sequence").
 * - an `emoji_modifier_sequence` — an emoji followed by one of the five skin
 *   tone modifiers U+1F3FB…U+1F3FF (§2.2, ED-13).
 * - an `emoji_flag_sequence` — a pair of regional indicators (ED-14). A single
 *   one is not a flag and stays text.
 * - an `emoji_keycap_sequence` — `0`…`9`, `#` or `*`, the selector, and the
 *   combining enclosing keycap U+20E3 (ED-14a).
 * - an `emoji_tag_sequence` — a base followed by tag characters and the tag
 *   terminator U+E007F, which is how subdivision flags are written (ED-14c).
 * - an `emoji_zwj_sequence` — any of the above joined by U+200D (§2.3, ED-16).
 *
 * Anything else is [TextSegment.Plain], including a lone joiner, an unterminated
 * tag sequence and a variation selector with nothing to select — those are
 * malformed text, and text is what a font gets asked to draw, which is where
 * they will be refused with their position rather than silently swallowed here.
 *
 * An empty string yields no segments.
 */
internal fun segmentEmoji(text: String): List<TextSegment> {
    val segments = mutableListOf<TextSegment>()
    var plainStart = 0
    var index = 0
    while (index < text.length) {
        val end = emojiSequenceEnd(text, index)
        if (end < 0) {
            index += charCount(codePointAt(text, index))
            continue
        }
        if (index > plainStart) segments += TextSegment.Plain(text.substring(plainStart, index), plainStart)
        segments += TextSegment.Emoji(text.substring(index, end), index)
        index = end
        plainStart = end
    }
    if (plainStart < text.length) segments += TextSegment.Plain(text.substring(plainStart), plainStart)
    return segments
}

/**
 * Where the emoji sequence starting at [start] ends, or -1 if none starts there
 * — `emoji_zwj_sequence := emoji_core_sequence (ZWJ emoji_core_sequence)+`
 * (ED-16), with the single core sequence as the degenerate case.
 *
 * A joiner with nothing joinable after it ends the sequence before itself, so
 * the joiner stays in the surrounding text rather than dangling off the emoji.
 */
private fun emojiSequenceEnd(text: String, start: Int): Int {
    var end = emojiCoreEnd(text, start)
    if (end < 0) return -1
    while (end < text.length && text[end].code == ZERO_WIDTH_JOINER) {
        val joined = emojiCoreEnd(text, end + 1)
        if (joined < 0) break
        end = joined
    }
    return end
}

/**
 * Where the `emoji_core_sequence` starting at [start] ends, or -1 if none does
 * (ED-17): a keycap, a flag, or an emoji character with whatever selector,
 * modifier and tags belong to it.
 */
private fun emojiCoreEnd(text: String, start: Int): Int {
    if (start >= text.length) return -1
    val codePoint = codePointAt(text, start)
    val afterBase = start + charCount(codePoint)
    return when {
        isKeycapBase(codePoint) -> keycapEnd(text, afterBase)
        isRegionalIndicator(codePoint) -> flagEnd(text, afterBase)
        // Text presentation was asked for explicitly: this is text, not emoji.
        selectorAt(text, afterBase) == TEXT_VARIATION_SELECTOR -> -1
        inEmojiRanges(EmojiTables.EMOJI_PRESENTATION, codePoint) -> trailersEnd(text, afterBase)
        inEmojiRanges(EmojiTables.EMOJI, codePoint) &&
            selectorAt(text, afterBase) == EMOJI_VARIATION_SELECTOR -> trailersEnd(text, afterBase)

        else -> -1
    }
}

/**
 * Where what may follow an emoji character ends: the emoji variation selector
 * (ED-9), then a skin tone modifier (ED-13), then a tag sequence (ED-14c). Each
 * is optional, and each may appear at most once — a second modifier belongs to
 * nothing and stays text.
 */
private fun trailersEnd(text: String, from: Int): Int {
    var end = from
    if (selectorAt(text, end) == EMOJI_VARIATION_SELECTOR) end++
    if (end < text.length) {
        val codePoint = codePointAt(text, end)
        if (codePoint in EMOJI_MODIFIER_FIRST..EMOJI_MODIFIER_LAST) end += charCount(codePoint)
    }
    return tagSequenceEnd(text, end)
}

/**
 * Where the tag sequence starting at [from] ends, or [from] itself when there is
 * none.
 *
 * ED-14c requires the terminator U+E007F: an unterminated run of tag characters
 * is not a sequence, so it is left to the text — where it will be reported as
 * undrawable rather than absorbed into an emoji that did not ask for it.
 */
private fun tagSequenceEnd(text: String, from: Int): Int {
    var end = from
    while (end < text.length) {
        val codePoint = codePointAt(text, end)
        if (codePoint !in TAG_FIRST..TAG_TERMINATOR) return from
        end += charCount(codePoint)
        if (codePoint == TAG_TERMINATOR) return end
    }
    return from
}

/**
 * Where the keycap sequence whose base ended at [from] ends, or -1 when the
 * enclosing keycap does not follow — `1` on its own is the digit one.
 *
 * The variation selector is accepted but not required: ED-14a's fully-qualified
 * form carries it, and the minimally-qualified form without it is the one that
 * turns up in text people actually wrote.
 */
private fun keycapEnd(text: String, from: Int): Int {
    var end = from
    if (selectorAt(text, end) == EMOJI_VARIATION_SELECTOR) end++
    return if (end < text.length && text[end].code == COMBINING_ENCLOSING_KEYCAP) end + 1 else -1
}

/** Where the flag whose first regional indicator ended at [from] ends, or -1 for a lone indicator. */
private fun flagEnd(text: String, from: Int): Int {
    if (from >= text.length) return -1
    val codePoint = codePointAt(text, from)
    return if (isRegionalIndicator(codePoint)) from + charCount(codePoint) else -1
}

/** The variation selector at [index], or 0 where there is none. */
private fun selectorAt(text: String, index: Int): Int {
    if (index >= text.length) return 0
    val code = text[index].code
    return if (code == EMOJI_VARIATION_SELECTOR || code == TEXT_VARIATION_SELECTOR) code else 0
}

/** `0`…`9`, `#` and `*` — the bases ED-14a allows a keycap to enclose. */
private fun isKeycapBase(codePoint: Int): Boolean =
    codePoint in DIGIT_ZERO..DIGIT_NINE || codePoint == NUMBER_SIGN || codePoint == ASTERISK

private fun isRegionalIndicator(codePoint: Int): Boolean = codePoint in REGIONAL_FIRST..REGIONAL_LAST

/** How many `Char`s a code point takes in UTF-16: two above the basic multilingual plane. */
private fun charCount(codePoint: Int): Int = if (codePoint > BMP_MAX) 2 else 1

private const val BMP_MAX: Int = 0xFFFF

/** U+200D ZERO WIDTH JOINER — what joins the parts of a ZWJ sequence (§2.3). */
private const val ZERO_WIDTH_JOINER: Int = 0x200D

/** U+FE0F VARIATION SELECTOR-16, "emoji presentation selector" (ED-8). */
private const val EMOJI_VARIATION_SELECTOR: Int = 0xFE0F

/** U+FE0E VARIATION SELECTOR-15, "text presentation selector" (ED-7). */
private const val TEXT_VARIATION_SELECTOR: Int = 0xFE0E

/** U+20E3 COMBINING ENCLOSING KEYCAP (ED-14a). */
private const val COMBINING_ENCLOSING_KEYCAP: Int = 0x20E3

/** U+1F3FB … U+1F3FF, the five skin tone modifiers of §2.2 (`Emoji_Modifier`). */
private const val EMOJI_MODIFIER_FIRST: Int = 0x1F3FB
private const val EMOJI_MODIFIER_LAST: Int = 0x1F3FF

/** U+1F1E6 … U+1F1FF, the regional indicator symbols a flag is a pair of (ED-14). */
private const val REGIONAL_FIRST: Int = 0x1F1E6
private const val REGIONAL_LAST: Int = 0x1F1FF

/** U+E0020 … U+E007F, the tag characters and their terminator (ED-14b, ED-14c). */
private const val TAG_FIRST: Int = 0xE0020
private const val TAG_TERMINATOR: Int = 0xE007F

private const val DIGIT_ZERO: Int = 0x30
private const val DIGIT_NINE: Int = 0x39
private const val NUMBER_SIGN: Int = 0x23
private const val ASTERISK: Int = 0x2A
