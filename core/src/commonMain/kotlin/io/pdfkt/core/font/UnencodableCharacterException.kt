/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.font

// ISO 32000-2:2020 Annex D "Character sets and encodings" (Table D.2),
// §9.6.5.1 "Character encoding — General", §9.7.1 "Composite fonts — General"

/**
 * A character the font it was to be drawn with has no code for.
 *
 * This is the "no silent loss" policy made into a
 * type: a glyph the current font cannot render is an error
 * carrying the offending character and its position — never an empty page or
 * a dropped character.
 *
 * Nothing about PDF forces this. A simple font selects a glyph with a single
 * byte (§9.6.5.1) and Annex D's encodings assign codes to 216 characters, so
 * every writer meets characters it cannot encode; what it does then is a
 * choice. Substituting a bullet, a question mark or nothing at all produces a
 * file that opens, prints, and says something other than what it was given —
 * a failure no assertion about "did it render" catches. Raising is the only
 * outcome that cannot be mistaken for success.
 *
 * The remedy is in [message] because it is always the same one, and it is not
 * "use a different character": a font supplied as bytes is embedded and reached
 * through a composite font, where "a sequence of one or more bytes are decoded
 * to select a glyph from the descendant CIDFont" (§9.7.1) and the repertoire is
 * the font program's rather than a single byte's.
 *
 * A subtype of [IllegalArgumentException] because that is what it is — the text
 * handed in cannot be drawn with the font handed in — and because it is what
 * [FontMetrics.widthOf] documents itself as raising, so a caller that only
 * wants "the measurement failed" needs to know nothing about encodings.
 *
 * @property text the string being encoded, whole, however long.
 * @property index where the character begins in [text]: an index into the
 *   string, so `text.substring(index)` starts at the offending character, and
 *   not a count of characters.
 * @property codePoint the character, as a Unicode code point.
 * @property character the character, as the text it is — one `Char`, or the
 *   surrogate pair standing for a character beyond the basic multilingual
 *   plane.
 * @property fontName the font this was to be drawn with, as `/BaseFont` spells
 *   it.
 * @property encodingName what was consulted for a code and had none: the byte
 *   encoding a simple font declares, or the character map of a supplied font
 *   program.
 */
public class UnencodableCharacterException private constructor(
    public val text: String,
    public val index: Int,
    public val codePoint: Int,
    public val fontName: String,
    public val encodingName: String,
    /**
     * Why the font had no code for it, in the words the factory below chose —
     * kept because the same refusal is sometimes told again against a longer
     * string, and re-deriving the reason from the message would be reading a
     * sentence back out of a paragraph.
     */
    internal val explanation: String,
) : IllegalArgumentException(
    message(text, index, codePoint, fontName, explanation),
) {
    public val character: String = codePointToString(codePoint)

    internal companion object {
        /**
         * A character Annex D's table assigns no code to, in one of the fourteen
         * fonts of §9.6.2.2 — the whole repertoire of which is that table.
         */
        fun outsideEncodingTable(
            text: String,
            index: Int,
            codePoint: Int,
            fontName: String,
            encodingName: String = WinAnsiEncoding.NAME,
        ): UnencodableCharacterException = UnencodableCharacterException(
            text = text,
            index = index,
            codePoint = codePoint,
            fontName = fontName,
            encodingName = encodingName,
            explanation =
            "$encodingName has no code for it (ISO 32000-2:2020 Annex D, Table D.2), and " +
                "$encodingName is the whole repertoire of a standard 14 font. Nothing was dropped " +
                "and nothing was substituted. To draw this character, supply a font that contains " +
                "it — FontRef.Supplied(bytes, id) — which this library embeds and addresses as a " +
                "composite font (§9.7), where a glyph is selected from the font program rather " +
                "than from a table of 216 characters",
        )

        /**
         * A character the supplied font program has no glyph for. The repertoire
         * here is the program's own, so there is no second encoding to fall back
         * to and the remedy is a font that covers the text.
         */
        fun outsideFontProgram(
            text: String,
            index: Int,
            codePoint: Int,
            fontName: String,
        ): UnencodableCharacterException = UnencodableCharacterException(
            text = text,
            index = index,
            codePoint = codePoint,
            fontName = fontName,
            encodingName = FONT_PROGRAM_CHARACTER_MAP,
            explanation =
            "the character map of the supplied font program maps it to glyph 0, \".notdef\" — the " +
                "program has no glyph for this character. Nothing was dropped and nothing was " +
                "substituted: .notdef is the missing-character box, and a page carrying one is a " +
                "page that renders and says the wrong thing. To draw this character, supply a font " +
                "program whose glyphs cover it",
        )

        /**
         * A character the colour-emoji font program maps to a glyph, of which
         * it stores no colour bitmap: the `cmap` table answered, the `CBLC`
         * table did not.
         *
         * A colour font is drawn from its bitmaps and from nothing else (a Type
         * 3 font's CharProcs are the whole of it, §9.6.4), so a glyph with no
         * bitmap is a code that would paint nothing at all — "If the name is not
         * present as a key in CharProcs, no glyph shall be painted".
         */
        fun withoutColorBitmap(
            text: String,
            index: Int,
            codePoint: Int,
            fontName: String,
            glyphId: Int,
        ): UnencodableCharacterException = UnencodableCharacterException(
            text = text,
            index = index,
            codePoint = codePoint,
            fontName = fontName,
            encodingName = COLOR_BITMAP_STRIKE,
            explanation =
            "the character map of the supplied colour font maps it to glyph $glyphId, and the " +
                "font's colour bitmap tables ('CBLC' and 'CBDT') store no bitmap for that glyph. A " +
                "Type 3 font is its CharProcs and nothing else, so this character has no picture to " +
                "be drawn from and nothing was substituted for it. To draw it, supply a colour font " +
                "whose strikes cover this character",
        )

        /**
         * The same refusal, told about the emoji the character belongs to: an
         * emoji policy that draws with a colour font hands that font one whole
         * emoji sequence at a time, and a sequence half of which has a picture
         * is a sequence with no picture.
         *
         * The character and the reason are [refused]'s, so what the font
         * actually answered survives; what is added is the sequence — written
         * out as Unicode writes one, since a family of four people and three
         * joiners is not something a reader can count off a page — and the
         * position of the whole of it in the string the caller wrote.
         */
        fun inEmojiSequence(
            text: String,
            index: Int,
            sequence: String,
            refused: UnencodableCharacterException,
        ): UnencodableCharacterException = UnencodableCharacterException(
            text = text,
            index = index,
            codePoint = refused.codePoint,
            fontName = refused.fontName,
            encodingName = refused.encodingName,
            explanation =
            "${refused.explanation}. The character stands in the emoji \"$sequence\" " +
                "(${codePointsOf(sequence)}), which the emoji policy of this document draws with the " +
                "colour font \"${refused.fontName}\"; an emoji is one picture, so nothing of it was " +
                "drawn and nothing was put in its place${composedNote(sequence)}. To draw it, supply " +
                "a colour font that covers the sequence — or choose EmojiPolicy.Strip or " +
                "EmojiPolicy.Placeholder, which draw emoji with no font at all",
        )

        /**
         * An emoji the caller's [EmojiRasterizer] returned null for: it has no
         * picture of this sequence, and a picture is the whole of what a Type 3
         * emoji font holds.
         *
         * The sequence, not a character of it: a rasterizer is asked for one
         * whole emoji and answers about the whole of it, so there is no code
         * point in it that is the one at fault. The code point carried is the
         * first, which is what a message has to point at somewhere.
         */
        fun withoutRasterizedPicture(
            text: String,
            index: Int,
            sequence: String,
            fontName: String,
            emPixels: Int,
        ): UnencodableCharacterException = UnencodableCharacterException(
            text = text,
            index = index,
            codePoint = codePointAt(sequence, 0),
            fontName = fontName,
            encodingName = EMOJI_RASTERIZER,
            explanation =
            "the emoji rasterizer of this document returned no picture for the emoji \"$sequence\" " +
                "(${codePointsOf(sequence)}) at $emPixels pixels to the em. A Type 3 font is its " +
                "CharProcs and nothing else (ISO 32000-2:2020 §9.6.4), so this emoji has nothing to be " +
                "drawn from; nothing was dropped and nothing was substituted. To draw it, supply a " +
                "rasterizer that covers the sequence — or choose EmojiPolicy.Strip or " +
                "EmojiPolicy.Placeholder, which draw emoji with no pictures at all",
        )

        /**
         * A character handed to an emoji font that is not part of an emoji.
         *
         * These fonts hold one picture per emoji and nothing else: the resolve
         * stage sends them the emoji it cut out of the text and leaves the rest
         * in the font it was set in, so a character
         * arriving here is text that was named for a font that cannot draw
         * text.
         */
        fun outsideEmojiFont(
            text: String,
            index: Int,
            codePoint: Int,
            fontName: String,
        ): UnencodableCharacterException = UnencodableCharacterException(
            text = text,
            index = index,
            codePoint = codePoint,
            fontName = fontName,
            encodingName = EMOJI_RASTERIZER,
            explanation =
            "this is an emoji font: it holds one picture per emoji sequence and no text glyphs at " +
                "all, and the character is not part of an emoji (Unicode Technical Standard #51 " +
                "§1.4.6). Nothing was dropped and nothing was substituted. Text is drawn with the font " +
                "it was set in; an emoji font is named by an emoji policy for the emoji alone",
        )

        /**
         * The same refusal, told against the caller's own string instead of the
         * fragment the resolve stage handed to a font.
         *
         * Everything about it survives — the character, the reason the font gave
         * and the font's name — because the refusal is the same one; what
         * changes is where it is reported, which is the string the caller can
         * see and an index into it.
         */
        fun relocated(text: String, index: Int, refused: UnencodableCharacterException): UnencodableCharacterException =
            UnencodableCharacterException(
                text = text,
                index = index,
                codePoint = refused.codePoint,
                fontName = refused.fontName,
                encodingName = refused.encodingName,
                explanation = refused.explanation,
            )

        /**
         * What is worth saying about a sequence of several code points and
         * nothing about one: a font substitutes its own single glyph for such a
         * sequence through `GSUB`, which this library does not read, so each of
         * the parts has to have a picture of its own.
         */
        private fun composedNote(sequence: String): String {
            if (codePointCount(sequence) <= 1) return ""
            return ". A sequence of several code points is drawn from the pictures of its parts, " +
                "because selecting the one glyph a font composes it into is the business of the " +
                "'GSUB' table and this library does not read it yet"
        }

        /** Every code point of [text], the way Unicode writes a sequence: `U+1F468 U+200D U+1F469`. */
        private fun codePointsOf(text: String): String {
            val names = mutableListOf<String>()
            forEachCodePoint(text) { _, codePoint -> names += codePointName(codePoint) }
            return names.joinToString(separator = " ")
        }

        /** What a composite font consults for a glyph: the program's own `cmap` table. */
        const val FONT_PROGRAM_CHARACTER_MAP: String = "the font program's character map"

        /** What a colour-emoji font consults for a picture: the strikes of its `CBLC` table. */
        const val COLOR_BITMAP_STRIKE: String = "the colour bitmap strike of the supplied font program"

        /** What a rasterized emoji font consults for a picture: the caller's own rasterizer. */
        const val EMOJI_RASTERIZER: String = "the emoji rasterizer supplied with the emoji policy"

        fun message(text: String, index: Int, codePoint: Int, fontName: String, explanation: String): String {
            val character = codePointToString(codePoint)
            return "$fontName cannot draw '$character' (${codePointName(codePoint)}), the character at " +
                "index $index of ${excerpt(text, index)}: $explanation"
        }

        /** `U+0416`, as Annex D and the Unicode standard spell a code point. */
        fun codePointName(codePoint: Int): String =
            "U+" + codePoint.toString(radix = 16).uppercase().padStart(CODE_POINT_DIGITS, '0')

        /**
         * [text] quoted, in full when it is short and as a window around [index]
         * when it is not.
         *
         * A message that prints a whole chapter to point at one character in it
         * is a message nobody reads to the end; a message that prints no context
         * at all leaves the reader counting characters. The window is cut on
         * character boundaries, never through a surrogate pair — an error about
         * a character is a poor place to produce half of one.
         */
        fun excerpt(text: String, index: Int): String {
            if (text.length <= EXCERPT_LENGTH) return "\"$text\""
            var start = maxOf(0, index - EXCERPT_BEFORE)
            if (start > 0 && text[start].isLowSurrogate()) start--
            var end = minOf(text.length, start + EXCERPT_LENGTH)
            if (end < text.length && text[end - 1].isHighSurrogate()) end--
            val opening = if (start > 0) ELLIPSIS else ""
            val closing = if (end < text.length) ELLIPSIS else ""
            return "\"$opening${text.substring(start, end)}$closing\" (of ${text.length} characters)"
        }

        /** Annex D writes code points to four digits; a wider one keeps its width. */
        const val CODE_POINT_DIGITS: Int = 4
        const val EXCERPT_LENGTH: Int = 60
        const val EXCERPT_BEFORE: Int = 24
        const val ELLIPSIS: String = "…"
    }
}
