/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.font

// ISO 32000-2:2020 §9.6.2.2 "Standard Type 1 fonts (standard 14 fonts) (PDF 1.0-1.7)",
// §9.6.4 "Type 3 fonts", §9.7.1 "Composite fonts — General",
// §9.9 "Embedded font programs"

/**
 * Which font a piece of text is drawn with, as the caller names it — the one
 * vocabulary type the DSL, the layout engine and the writer share.
 *
 * Four variants over three ways clause 9 gives this library to put a mark on a
 * page; the choice is made here, once, by the reference:
 *
 * - [Standard14] — one of the fourteen fonts §9.6.2.2 names, whose glyphs and
 *   metrics the reader is required to have: "These fonts, or their font metrics
 *   and suitable substitution fonts, shall be available to the PDF processor."
 *   Nothing is embedded and the file stays small; the price is the repertoire of
 *   a single-byte encoding.
 * - [Supplied] — a font program handed over as bytes, embedded in the file
 *   (§9.9) and reached through a composite font (§9.7), where "a sequence of one
 *   or more bytes are decoded to select a glyph from the descendant CIDFont"
 *   (§9.7.1) and the repertoire is therefore the font program's, not a byte's.
 *   Everything outside the standard fourteen — Cyrillic, Greek, CJK — is this.
 * - [ColorEmoji] — a colour-bitmap font program, drawn as the Type 3 font of
 *   §9.6.4, whose "font dictionary contains the glyph descriptions" because a
 *   colour emoji has no outline to embed and its picture is a PNG. It is not a
 *   font a caller sets text in: the resolve stage names it for the emoji an
 *   [io.pdfkt.core.style.EmojiPolicy.EmbeddedColorFont] policy hands it.
 * - [RasterizedEmoji] — the same Type 3 font, with the pictures drawn by the
 *   caller's [EmojiRasterizer] rather than read out of a font program.
 *
 * The first two are the two *text* paths and there is no
 * third of those: a `Supplied` font goes through the composite machinery even
 * for pure ASCII, because one path that always works beats two that have to
 * agree. [ColorEmoji] is not a third way to draw text but the one way PDF has
 * to draw a picture per character, and it is a reference rather than something
 * hidden inside the resolve stage because everything below — the line breaker
 * measuring a run, the emitter naming a font in `/Resources` — asks the same
 * catalog the same question about it as about any other font.
 *
 * ## Why no reference by name or by path
 *
 * There is deliberately no variant naming a font by file path, resource name or
 * family. A path is a promise about the machine the code runs on, and this
 * library runs in a browser as well as on a server; bytes are the only form of a font program that means the same
 * thing on all of them. Embedded default fonts live in a separate optional
 * artifact that hands out [Supplied] instances, so a build that does not want
 * megabytes of font data does not carry them.
 */
public sealed interface FontRef {
    /**
     * One of the fourteen fonts of §9.6.2.2, named by [Standard14Name] — the
     * simple path: a Type 1 font dictionary, a byte encoding, widths from the
     * AFM metrics of the font, and no font program in the file.
     *
     * A data class: the reference carries nothing but the choice of font, so two
     * references to Helvetica are the same reference, and a catalog asked for
     * both resolves once.
     */
    public data class Standard14(public val name: Standard14Name) : FontRef

    /**
     * A font program supplied by the caller as the bytes of the file — the
     * embedded path: a subset of the program is written into the document
     * (§9.9) and addressed as a composite font (§9.7).
     *
     * @property id names this program within one document, and is what a
     *   [FontCatalog] resolves by. Two references carrying the same id are the
     *   same font however many instances the caller builds, which is what lets
     *   a layout name its fonts freely without holding on to objects; two
     *   different programs under one id are an error rather than a document
     *   drawn with the wrong glyphs.
     * @property index which font of a font collection — "a TTC file can
     *   contain multiple OpenType fonts" — the reference is to, counting from
     *   0. A file that is a single font has only font 0.
     *
     * Not a data class, and the bytes are not readable back: equality over a
     * multi-megabyte array is a cost paid at every comparison for an answer [id]
     * already gives, and an array a caller can still reach into is a font
     * program that can change after it was parsed and subset. The array is
     * handed over to this reference; parsing it is the business of this package
     * (clauses 9.7 and 9.9, the OpenType specification), and nothing else in the
     * library reads it.
     */
    public class Supplied(internal val bytes: ByteArray, public val id: String, public val index: Int) : FontRef {
        /** The font of a file that holds one font. */
        public constructor(bytes: ByteArray, id: String) : this(bytes, id, 0)

        init {
            require(id.isNotEmpty()) {
                "a supplied font needs an id: it is what names the program within one document " +
                    "and what its resolution is cached by"
            }
            require(bytes.isNotEmpty()) {
                "a supplied font needs the bytes of a font program; the id \"$id\" arrived with none " +
                    "(ISO 32000-2:2020 §9.9 \"Embedded font programs\")"
            }
            require(index >= 0) {
                "the font \"$id\" was given the index $index; an index picks one font of a font " +
                    "collection (a .ttc file), counting from 0"
            }
        }

        /** The id, since the bytes are not something to print. */
        override fun toString(): String = "FontRef.Supplied(id=$id)"
    }

    /**
     * A colour-bitmap font program supplied as the bytes of the file — the
     * Type 3 path of §9.6.4, where the font dictionary carries the glyph
     * descriptions and each of them paints the PNG the program's `CBDT` table
     * stores for one emoji.
     *
     * The reference a caller writes is not this one but
     * [io.pdfkt.core.style.EmojiPolicy.EmbeddedColorFont], the policy that says
     * what a document does with emoji; this is what the resolve stage turns that
     * policy into so that the stages below it — measuring a run, naming a font
     * in a page's resources, writing the font dictionary — go on asking the
     * [FontCatalog] the only question they ever ask.
     *
     * Its own variant rather than a [Supplied] with a flag: the two are
     * different clauses with different machinery behind them (a subset of the
     * glyf program addressed as a composite font, against a dictionary of
     * content streams), and the `when` that picks between them is what keeps
     * the choice in one place.
     *
     * @property id names this program within one document, exactly as
     *   [Supplied.id] does, in a namespace of its own: a colour font and a text
     *   font may both be called `noto` and stay two fonts.
     */
    public class ColorEmoji(internal val bytes: ByteArray, public val id: String) : FontRef {
        init {
            require(id.isNotEmpty()) {
                "a colour font needs an id: it is what names the program within one document " +
                    "and what its resolution is cached by"
            }
            require(bytes.isNotEmpty()) {
                "a colour font needs the bytes of a font program with 'CBLC' and 'CBDT' tables; the " +
                    "id \"$id\" arrived with none (ISO 32000-2:2020 §9.6.4 \"Type 3 fonts\")"
            }
        }

        /** The id, since the bytes are not something to print. */
        override fun toString(): String = "FontRef.ColorEmoji(id=$id)"
    }

    /**
     * Emoji drawn by a caller's [EmojiRasterizer] — the same Type 3 path of
     * §9.6.4 as [ColorEmoji], with the pictures coming from the caller instead
     * of from a font program's `CBDT` table.
     *
     * Two variants rather than one with a nullable program, because the choice
     * of glyph source is exactly what the `when` in [FontCatalog] resolves by
     * and a source is either a font program or a rasterizer, never both.
     * Everything below the choice is shared, down to the bytes: see
     * [io.pdfkt.core.font.Type3EmojiFont].
     *
     * The reference a caller writes is not this one but
     * [io.pdfkt.core.style.EmojiPolicy.Rasterized], the policy that says what a
     * document does with emoji; this is what the resolve stage turns that policy
     * into.
     *
     * @property id names this rasterizer within one document, in a namespace of
     *   its own, exactly as [Supplied.id] and [ColorEmoji.id] do.
     * @property emPixels how many pixels of the rasterizer's output make an em —
     *   the resolution of every picture in this font, and part of what the id
     *   names: the same rasterizer asked for two sizes is two fonts.
     */
    public class RasterizedEmoji(
        internal val rasterizer: EmojiRasterizer,
        public val id: String,
        public val emPixels: Int,
    ) : FontRef {
        init {
            require(id.isNotEmpty()) {
                "a rasterized emoji font needs an id: it is what names the rasterizer within one " +
                    "document and what its resolution is cached by"
            }
            require(emPixels > 0) {
                "a rasterized emoji font is drawn at $emPixels pixels to the em: the em of a picture " +
                    "is what its FontMatrix is built from, and a matrix over zero pixels maps no glyph " +
                    "anywhere (ISO 32000-2:2020 §9.6.4 \"Type 3 fonts\")"
            }
        }

        override fun toString(): String = "FontRef.RasterizedEmoji(id=$id, emPixels=$emPixels)"
    }
}
