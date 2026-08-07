/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.style

import io.pdfkt.core.font.EmojiRasterizer
import io.pdfkt.core.font.FontRef

// The policies are this library's design. What an
// emoji *is* comes from Unicode Technical Standard #51, in io.pdfkt.core.text;
// what draws one comes from ISO 32000-2:2020 §9.6.4 "Type 3 fonts".

/**
 * What a document does with an emoji.
 *
 * A colour emoji is not a glyph an ordinary font has. Drawing one means either a
 * font that carries colour bitmaps or a decision not to draw it, and the
 * decision is the caller's — which is why this is a policy and not a default
 * buried in the engine.
 *
 * [Strip] and [Placeholder] are pure text transforms in resolve — L3
 * uninvolved: they rewrite the text before any font
 * is consulted, so no font ever sees an emoji and no font has to have one.
 * [EmbeddedColorFont] and [Rasterized] are the two that do involve L3: each
 * draws the emoji from a font of its own — the same Type 3 font of §9.6.4, out
 * of a supplied font program's bitmaps or out of a supplied rasterizer's
 * pictures — and the resolve stage splits the text where that font takes over.
 */
public sealed interface EmojiPolicy {
    /**
     * Leave emoji in the text and draw them with the run's own font.
     *
     * The right policy for a supplied font that actually covers them, and the
     * honest one everywhere else: a font without the glyph refuses the character
     * with its position, instead of producing a page that quietly says something
     * different from what it was given.
     */
    public data object None : EmojiPolicy

    /**
     * Remove emoji from the text.
     *
     * The whole emoji goes, as one — a family joined by zero-width joiners is a
     * single picture and leaves nothing behind. Surrounding text, spaces
     * included, is untouched: stripping is not tidying.
     */
    public data object Strip : EmojiPolicy

    /**
     * Replace each emoji with its code points, written the way Unicode writes
     * them: `[U+1F44B]`, and `[U+1F468 U+200D U+1F469]` for a sequence.
     *
     * Lossy by construction, like [Strip], but lossy in a way the reader of the
     * page can see and undo — which is what makes it the policy for a document
     * that has to render everywhere and lose nothing silently. The text is drawn
     * in the run's own font, and it is all ASCII, so every font has it.
     */
    public data object Placeholder : EmojiPolicy

    /**
     * Draw the emoji in colour, out of a font program supplied as bytes.
     *
     * The program is a colour-bitmap font — one with the `CBLC` and `CBDT`
     * tables, where an emoji is a PNG rather than an outline — and it is written
     * into the document as the Type 3 font of §9.6.4, one glyph description per
     * emoji the document actually drew. The text
     * around the emoji is untouched and stays in the font it was set in: the
     * resolve stage splits a run where the emoji font takes over, which is the
     * splitting rule it already had, and the line breaker sets both halves on
     * one baseline out of the metrics each font states — for this one, the
     * ascender and descender of the strike being drawn.
     *
     * Nothing is lost quietly here either. An emoji the supplied font has no
     * picture for stops the document, naming the sequence and where in the
     * caller's text it stands, exactly as an unencodable character
     * does; the remedies are a font that
     * covers it, or [Strip] or [Placeholder], which need no font at all.
     *
     * @param fontBytes the colour font program. Parsed on first use.
     * @property id what the program is called within one document — the name its
     *   glyphs' images carry and the name every message about it uses. Two
     *   policies with the same id and different bytes in one document are an
     *   error, as for any other supplied program.
     *
     * Not a data class, for the reasons [FontRef.Supplied] is not one: equality
     * over a font program is a cost paid for an answer [id] already gives, and
     * bytes that stay reachable are a program that can change after it was
     * parsed.
     */
    public class EmbeddedColorFont(fontBytes: ByteArray, public val id: String) : EmojiPolicy {
        /**
         * The reference the resolve stage draws the emoji with — built once, so
         * that a theme copied a dozen times still names one font program to the
         * document's catalog.
         */
        internal val font: FontRef.ColorEmoji = FontRef.ColorEmoji(fontBytes, id)

        /** The id, since the bytes are not something to print. */
        override fun toString(): String = "EmojiPolicy.EmbeddedColorFont(id=$id)"
    }

    /**
     * Draw the emoji in colour, out of pictures the caller's [EmojiRasterizer]
     * draws.
     *
     * The same file as [EmbeddedColorFont] — the Type 3 font of §9.6.4, one
     * glyph description per emoji the document drew, each painting a PNG, with a
     * `ToUnicode` CMap so the emoji extract as characters — from another source
     * of pictures. Everything the resolve stage does is the same too: the emoji
     * becomes a run of its own in a font of its own, and the text around it
     * stays in the font it was set in.
     *
     * Two things this policy has that a font program does not:
     *
     * - it needs **no colour font**, so a document is not tied to whether a
     *   `CBDT`/`CBLC` program is at hand or to what format an emoji font is in
     *   this year. Whatever the caller's platform already draws emoji with will
     *   do;
     * - a rasterizer is asked for a **whole sequence**, so a family joined by
     *   zero-width joiners or an emoji with a skin tone is one picture and one
     *   code. Out of a font program those are drawn as the characters they are
     *   composed of, because composing them is `GSUB`'s business and this
     *   library does not read that table.
     *
     * Nothing is lost quietly. An emoji the rasterizer returns null for stops the
     * document, naming the sequence and where in the caller's text it stands,
     * exactly as an unencodable character does; the remedies are a rasterizer that covers it, or [Strip] or
     * [Placeholder], which need no picture at all.
     *
     * @param rasterizer what draws one emoji. Asked once per distinct sequence
     *   per document, never for anything that is not an emoji.
     * @property id what this rasterizer is called within one document — the name
     *   its pictures carry and the name every message about it uses. Two
     *   policies with the same id and different rasterizers in one document are
     *   an error, as for two font programs under one id.
     * @property emPixels how many pixels of the rasterizer's output make an em:
     *   the resolution every picture of the document is drawn at. A page states
     *   no resolution of its own — it is placed on whatever device prints it —
     *   so this is a choice between how large an emoji may be printed before it
     *   softens and how many bytes the file carries, and [DEFAULT_EM_PIXELS] is
     *   this library's default rather than anything the standard fixes.
     *
     * Not a data class, for the reasons [EmbeddedColorFont] is not one: two
     * rasterizers are two functions, which nothing but identity compares, and
     * [id] is the answer equality would be asked for.
     */
    public class Rasterized(
        rasterizer: EmojiRasterizer,
        public val id: String,
        public val emPixels: Int = DEFAULT_EM_PIXELS,
    ) : EmojiPolicy {
        /**
         * The reference the resolve stage draws the emoji with — built once, so
         * that a theme copied a dozen times still names one rasterizer to the
         * document's catalog.
         */
        internal val font: FontRef.RasterizedEmoji = FontRef.RasterizedEmoji(rasterizer, id, emPixels)

        /** The id and the size; the rasterizer is a function and prints as one. */
        override fun toString(): String = "EmojiPolicy.Rasterized(id=$id, emPixels=$emPixels)"

        public companion object {
            /**
             * 128 pixels to the em, the default resolution of a rasterized
             * emoji.
             *
             * An emoji set at 12 points and printed from a picture of 128 pixels
             * to the em is 128 pixels over a sixth of an inch — some 750 pixels
             * to the inch, past what any printer resolves, and a PNG of a few
             * kilobytes. A document that sets emoji far larger, or one that
             * cares more about its size than about the print, says so.
             */
            public const val DEFAULT_EM_PIXELS: Int = 128
        }
    }
}
