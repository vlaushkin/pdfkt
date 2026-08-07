/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.text

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Unicode Technical Standard #51 "Unicode Emoji" §1.4.6 "Emoji Sequences"
// (ED-13 … ED-17), §2.1 "Emoji Presentation", §2.2 "Diversity", §2.3 "Emoji ZWJ
// Sequences".

/**
 * Where a string splits into emoji and text, and what counts as one emoji.
 *
 * The cases are the sequence forms UTS #51 defines, plus the two its definitions
 * exclude and a careless splitter gets wrong: © — an `Emoji` character that is
 * text by default — and a watch followed by the text presentation selector.
 *
 * Every sequence under test is a named constant whose doc lists its code points,
 * because half of them — joiners, variation selectors, tag characters — are
 * invisible in source. The names are what the assertions read as; the code
 * points beside them are what makes the assertion checkable against the clause.
 */
class EmojiSegmentationTest {
    /** A string with no emoji is one plain segment, whole. */
    @Test
    fun `text without emoji is a single plain segment`() {
        assertEquals(listOf(TextSegment.Plain("Hello, world", 0)), segmentEmoji("Hello, world"))
    }

    /** An empty string has nothing in it, not even an empty segment. */
    @Test
    fun `empty text has no segments`() {
        assertEquals(emptyList(), segmentEmoji(""))
    }

    /**
     * U+1F44B has `Emoji_Presentation`, so it is an emoji wherever it appears —
     * no selector needed (§2.1) — and the text around it stays as it was, spaces
     * included.
     */
    @Test
    fun `a default-presentation emoji splits the text around it`() {
        assertEquals(
            listOf(
                TextSegment.Plain("Hello ", 0),
                TextSegment.Emoji(WAVE, 6),
                TextSegment.Plain(" world", 8),
            ),
            segmentEmoji("Hello $WAVE world"),
        )
    }

    /**
     * ED-15: `emoji_presentation_sequence := emoji_character emoji_presentation_selector`.
     * © is an `Emoji` character without `Emoji_Presentation`, so U+FE0F is what
     * decides — and a copyright line that never asked for a picture keeps its ©.
     */
    @Test
    fun `the variation selector decides for a character that is text by default`() {
        assertEquals(listOf(TextSegment.Plain("© 2026", 0)), segmentEmoji("© 2026"))
        assertEquals(
            listOf(TextSegment.Emoji("©$EMOJI_SELECTOR", 0), TextSegment.Plain(" 2026", 2)),
            segmentEmoji("©$EMOJI_SELECTOR 2026"),
        )
    }

    /**
     * ED-7: the text presentation selector U+FE0E asks for text, and asking is
     * enough — U+231A ⌚ has `Emoji_Presentation`, is an emoji on its own, and
     * comes out as text once the selector says so.
     */
    @Test
    fun `the text variation selector keeps a default emoji as text`() {
        assertEquals(listOf(TextSegment.Emoji(WATCH, 0)), segmentEmoji(WATCH))
        assertEquals(listOf(TextSegment.Plain(WATCH + TEXT_SELECTOR, 0)), segmentEmoji(WATCH + TEXT_SELECTOR))
    }

    /** §2.2, ED-13: a skin tone modifier belongs to the emoji before it, not to itself. */
    @Test
    fun `a skin tone modifier is part of the emoji it follows`() {
        val toned = WAVE + SKIN_TONE_4
        assertEquals(listOf(TextSegment.Emoji(toned, 0)), segmentEmoji(toned))
    }

    /**
     * §2.3, ED-16: a ZWJ sequence is one picture. A family is four people and
     * three joiners and it segments as a single emoji — which is what lets a
     * policy strip it without leaving joiners behind.
     */
    @Test
    fun `a zero-width-joiner sequence is one emoji`() {
        assertEquals(FAMILY_LENGTH, FAMILY.length, "four characters of two Chars each and three joiners")
        assertEquals(listOf(TextSegment.Emoji(FAMILY, 0)), segmentEmoji(FAMILY))
    }

    /** ED-14: a flag is a *pair* of regional indicators. */
    @Test
    fun `a flag is a pair of regional indicators`() {
        assertEquals(listOf(TextSegment.Emoji(FLAG_GB, 0)), segmentEmoji(FLAG_GB))
    }

    /** One regional indicator is not a flag, so it stays text — and a font will refuse it. */
    @Test
    fun `a lone regional indicator is not a flag`() {
        assertEquals(listOf(TextSegment.Plain(REGIONAL_G, 0)), segmentEmoji(REGIONAL_G))
    }

    /**
     * ED-14a: `emoji_keycap_sequence := [0-9#*] FE0F 20E3`. The digit alone is a
     * digit — a paragraph numbered "1." must not lose its number to a policy.
     */
    @Test
    fun `a keycap sequence is an emoji and a bare digit is not`() {
        val keycap = "1$EMOJI_SELECTOR$ENCLOSING_KEYCAP"
        assertEquals(listOf(TextSegment.Emoji(keycap, 0)), segmentEmoji(keycap))
        assertEquals(listOf(TextSegment.Plain("1. Introduction", 0)), segmentEmoji("1. Introduction"))
    }

    /** The minimally-qualified keycap, without the selector, is the one people type. */
    @Test
    fun `a keycap without the variation selector is still a keycap`() {
        val keycap = "7$ENCLOSING_KEYCAP"
        assertEquals(listOf(TextSegment.Emoji(keycap, 0)), segmentEmoji(keycap))
    }

    /**
     * ED-14c: a tag sequence is a base, tag characters, and the terminator
     * U+E007F — the flag of Scotland, six tag characters after its base.
     */
    @Test
    fun `a tag sequence is part of the emoji it qualifies`() {
        assertEquals(listOf(TextSegment.Emoji(FLAG_SCOTLAND, 0)), segmentEmoji(FLAG_SCOTLAND))
    }

    /** Unterminated tag characters are not a sequence, so they stay outside the emoji. */
    @Test
    fun `an unterminated tag run is left as text`() {
        val tags = TAG_G + TAG_B
        assertEquals(
            listOf(TextSegment.Emoji(BLACK_FLAG, 0), TextSegment.Plain(tags, BLACK_FLAG.length)),
            segmentEmoji(BLACK_FLAG + tags),
        )
    }

    /** A joiner with nothing joinable after it belongs to the text, not to the emoji. */
    @Test
    fun `a dangling joiner is not swallowed`() {
        assertEquals(
            listOf(TextSegment.Emoji(WAVE, 0), TextSegment.Plain(JOINER, WAVE.length)),
            segmentEmoji(WAVE + JOINER),
        )
    }

    /** Several emoji in a row are several segments: each is its own picture. */
    @Test
    fun `adjacent emoji are separate segments`() {
        assertEquals(
            listOf(TextSegment.Emoji(WAVE, 0), TextSegment.Emoji(GLOBE, WAVE.length)),
            segmentEmoji(WAVE + GLOBE),
        )
    }

    /**
     * Whatever the input, the segments concatenate back to it, in order, with no
     * gaps: the splitter partitions and never edits. Everything downstream leans
     * on that — a policy that rewrites only the emoji cannot lose a character it
     * never saw.
     */
    @Test
    fun `segments cover the text exactly`() {
        val samples = listOf(
            "Hello $WAVE world",
            FLAG_GB + FLAG_SCOTLAND,
            "© 2026 ✓ ☀$TEXT_SELECTOR",
            "1$EMOJI_SELECTOR$ENCLOSING_KEYCAP 2 3",
            "$FAMILY & co",
            "Привет $WAVE$SKIN_TONE_4",
            WAVE + JOINER,
        )
        samples.forEach { sample ->
            val segments = segmentEmoji(sample)
            assertEquals(sample, segments.joinToString("") { it.text }, "concatenation of $segments")
            var next = 0
            segments.forEach { segment ->
                assertEquals(next, segment.index, "segment index in $segments")
                next += segment.text.length
            }
            assertTrue(segments.none { it.text.isEmpty() }, "no empty segments in $segments")
        }
    }

    /** The tables the split is made from, and the Unicode release the generator recorded. */
    @Test
    fun `the emoji tables carry the unicode release they came from`() {
        assertEquals("17.0", EmojiTables.UNICODE_VERSION)
        // U+1FAEA is Unicode 16: the stale JDK-derived tables classified it as
        // plain text, and a Strip policy let it through to the font.
        assertTrue(inEmojiRanges(EmojiTables.EMOJI, 0x1FAEA), "U+1FAEA is an emoji since Unicode 16")
        assertTrue(inEmojiRanges(EmojiTables.EMOJI_PRESENTATION, 0x1F44B), "U+1F44B has emoji presentation")
        assertTrue(inEmojiRanges(EmojiTables.EMOJI, 0x00A9), "U+00A9 is an Emoji character")
        assertFalse(inEmojiRanges(EmojiTables.EMOJI_PRESENTATION, 0x00A9), "U+00A9 is text by default")
        assertFalse(inEmojiRanges(EmojiTables.EMOJI, 'A'.code), "a letter is not an emoji")
    }

    private companion object {
        /** U+1F44B WAVING HAND SIGN. */
        const val WAVE: String = "👋"

        /** U+1F30D EARTH GLOBE EUROPE-AFRICA. */
        const val GLOBE: String = "🌍"

        /** U+231A WATCH — an emoji of the basic multilingual plane, emoji by default. */
        const val WATCH: String = "⌚"

        /** U+1F3FD EMOJI MODIFIER FITZPATRICK TYPE-4, one of the five skin tones. */
        const val SKIN_TONE_4: String = "🏽"

        /** U+200D ZERO WIDTH JOINER. */
        const val JOINER: String = "‍"

        /** U+FE0F VARIATION SELECTOR-16, the emoji presentation selector. */
        const val EMOJI_SELECTOR: String = "️"

        /** U+FE0E VARIATION SELECTOR-15, the text presentation selector. */
        const val TEXT_SELECTOR: String = "︎"

        /** U+20E3 COMBINING ENCLOSING KEYCAP. */
        const val ENCLOSING_KEYCAP: String = "⃣"

        /** U+1F468 U+200D U+1F469 U+200D U+1F467 U+200D U+1F466 — family: man, woman, girl, boy. */
        const val FAMILY: String =
            "👨‍👩‍👧‍👦"

        /** Four characters of two `Char`s each, and three joiners of one. */
        const val FAMILY_LENGTH: Int = 11

        /** U+1F1EC U+1F1E7 — the regional indicators G and B: the flag of the United Kingdom. */
        const val FLAG_GB: String = "🇬🇧"

        /** U+1F1EC alone — a letter of a flag that was never finished. */
        const val REGIONAL_G: String = "🇬"

        /** U+1F3F4 WAVING BLACK FLAG, the base of a subdivision flag. */
        const val BLACK_FLAG: String = "🏴"

        /** U+E0067 and U+E0062 — the tag characters `g` and `b`. */
        const val TAG_G: String = "󠁧"
        const val TAG_B: String = "󠁢"

        /**
         * The flag of Scotland: U+1F3F4, the tag characters `g`, `b`, `s`, `c`,
         * `t` (U+E0067, U+E0062, U+E0073, U+E0063, U+E0074) and the tag
         * terminator U+E007F.
         */
        const val FLAG_SCOTLAND: String =
            "🏴󠁧󠁢󠁳󠁣󠁴󠁿"
    }
}
