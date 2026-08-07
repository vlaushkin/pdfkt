/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.font

import io.pdfkt.core.font.truetype.BitmapStrike
import io.pdfkt.core.font.truetype.ColorBitmapGlyph
import io.pdfkt.core.font.truetype.TrueTypeFont
import io.pdfkt.core.obj.PdfDict
import io.pdfkt.core.obj.PdfObject
import io.pdfkt.core.obj.PdfRef

// ISO 32000-2:2020 §9.6.4 "Type 3 fonts" (Tables 110, 111),
// §9.6.1 "Simple fonts — General", §9.2.4 "Glyph positioning and metrics",
// §9.10.3 "ToUnicode CMaps"
// OpenType 1.9.1 §"CBLC — Color Bitmap Location Table",
// §"CBDT — Color Bitmap Data Table"

/**
 * A colour-emoji font program, drawn as the Type 3 font of §9.6.4 —
 * [Type3EmojiFont], which writes every byte of it — out of the PNGs the
 * program's `CBDT` table stores: CBDT/CBLC PNG
 * glyphs become a Type 3 font (clause 9.6.4) whose CharProcs place image
 * XObjects, one per used emoji, with a ToUnicode CMap so they extract as
 * characters.
 *
 * This class is the *glyph source*: which glyph a character selects, which
 * bitmap is that glyph's picture, and what the picture measures. The file it
 * turns into is [Type3EmojiFont]'s, and [RasterizedEmojiFont] is the same file
 * from another source.
 *
 * ## The glyph coordinate system is the font's em
 *
 * `FontMatrix` "maps glyph space to text space", and this one is
 * `[1/unitsPerEm 0 0 1/unitsPerEm 0 0]` — so a unit of glyph space is a design
 * unit of the font program, the unit `head`, `hmtx` and every other table of the
 * program already measure in. A bitmap of a strike of *ppem* pixels to the em is
 * `unitsPerEm / ppem` units per pixel, one multiplication from what `CBLC`
 * states.
 *
 * This is not the glyph space of §9.2.4, where "the units of glyph space are
 * one-thousandth of a unit of text space" — that is a definition for the fonts
 * whose matrix is fixed, and Table 110 says of a Type 3 font's widths that "these
 * widths shall be interpreted in glyph space as specified by FontMatrix (unlike
 * the widths of a Type 1 font, which are in thousandths of a unit of text
 * space)". The [GlyphSpaceMetrics] this class implements for the layout engine
 * *are* in thousandths, because that is the contract the engine measures every
 * font by; the two conversions are one division apart and both are stated where
 * they are made.
 *
 * ## What a character selects
 *
 * One code point, through the program's `cmap`, to one glyph, to the colour
 * bitmap of the strike with the most pixels to the em. Sequences that Unicode
 * composes out of several code points — a ZWJ sequence, an emoji followed by
 * U+FE0F — are drawn as the characters they are made of, because selecting the
 * one glyph a font substitutes for such a sequence is `GSUB`'s business and this
 * library does not read that table yet. Nothing is dropped and nothing is
 * substituted either way: every character drawn is a character the font has a
 * picture for, and the `ToUnicode` CMap maps its code back to it.
 *
 * A rasterizer selects by the whole sequence instead ([RasterizedEmojiFont]),
 * which is the one behavioural difference between the two sources.
 *
 * @param id what this font is called within one document, the way
 *   `FontRef.Supplied.id` names a font program; it names the images of its
 *   glyphs and appears in every error message this class raises.
 * @param bytes the font program: an OpenType font with `CBLC` and `CBDT`
 *   tables. Parsed on first use, not on construction — a document that names a
 *   colour font it never draws with should not pay for parsing it.
 */
internal class ColorEmojiFont(private val id: String, bytes: ByteArray) : WrittenFont {
    private val program: TrueTypeFont by lazy(LazyThreadSafetyMode.NONE) { TrueTypeFont.parse(bytes) }

    /**
     * The strike every glyph of this font is drawn from: the one with the most
     * pixels to the em ([io.pdfkt.core.font.truetype.CblcTable.largestStrike]).
     *
     * One strike for the whole font rather than one per glyph, because the
     * choice is not per glyph: a page is placed on whatever device prints it, so
     * there is no raster size to match, and mixing strikes would put two
     * resolutions of the same picture in one file for no gain.
     */
    private val strike: BitmapStrike by lazy(LazyThreadSafetyMode.NONE) {
        val cblc = requireNotNull(program.cblc) {
            "the font program supplied as the colour font \"$id\" has no 'CBLC' table, so it stores no " +
                "colour bitmaps; a Type 3 colour font is written from the pictures of 'CBDT', and " +
                "there are none here. Supply a colour-emoji font program"
        }
        requireNotNull(cblc.largestStrike) {
            "the 'CBLC' table of the colour font \"$id\" describes no strike, so the font stores no " +
                "bitmap for any glyph at any size"
        }
    }

    /**
     * The bitmaps this font has looked up, by glyph index — a line measured
     * twice and then drawn should decode one PNG's worth of `CBDT` once.
     */
    private val bitmaps: MutableMap<Int, ColorBitmapGlyph> = mutableMapOf()

    /**
     * The Type 3 font this one is written as, in the glyph coordinate system of
     * the program's em.
     *
     * Built on first use, because the em is the program's and the program is
     * parsed on first use.
     */
    private val type3: Type3EmojiFont by lazy(LazyThreadSafetyMode.NONE) {
        Type3EmojiFont(id, program.unitsPerEm)
    }

    // ---- Metrics (§9.2.4) --------------------------------------------------

    /**
     * The advance of [text] in the glyph space of §9.2.4 — thousandths of a unit
     * of text space — summed over its characters.
     *
     * The number comes from the strike: `CBLC` states an advance "in pixels" of
     * a strike of [BitmapStrike.ppemX] pixels to the em, so a pixel is
     * `1000 / ppemX` of these units. It is the same distance `hmtx` states in
     * design units — the two are checked against each other in
     * `SystemColorBitmapFontTest` — and the strike's is the one used here,
     * because the strike is what is drawn.
     *
     * Measuring shows nothing: a line the layout measured and then broke
     * elsewhere must not leave a glyph in the font that no page draws.
     *
     * @throws UnencodableCharacterException at the first character this font has
     *   no colour bitmap for.
     */
    override fun advanceOf(text: String): Double {
        var advance = 0.0
        forEachCodePoint(text) { index, codePoint ->
            advance += inGlyphSpace(bitmapOf(codePoint, text, index).metrics.advance, strike.ppemX)
        }
        return advance
    }

    /**
     * How far this font's bitmaps reach above the baseline: the strike's own
     * `SbitLineMetrics` ascender, "in pixels" of the strike.
     *
     * The strike's line metrics rather than `hhea`'s, because a line of these
     * bitmaps is what this measures, and because the shared baseline of a mixed
     * text-and-emoji line is placed from exactly these two
     * numbers.
     */
    override val ascent: Double get() = inGlyphSpace(strike.lineMetrics.ascender, strike.ppemY)

    /** The same downwards — the strike's descender, which the record states negative. */
    override val descent: Double get() = inGlyphSpace(strike.lineMetrics.descender, strike.ppemY)

    /**
     * `hhea`'s lineGap, converted from design units: the gap the font asks to be
     * left between one line's descent and the next line's ascent.
     *
     * `SbitLineMetrics` carries no line gap of its own — the record is ascender,
     * descender and the caret and side-bearing fields — so this is the font
     * program's, which is a statement about the same font at any size.
     */
    override val leading: Double
        get() = program.hhea.lineGap * Type3EmojiFont.GLYPH_SPACE_UNITS_PER_EM / program.unitsPerEm

    // ---- Showing text (§9.6.4) ---------------------------------------------

    /**
     * The character codes [text] is shown as: one byte per character, each the
     * code this font hands that character's glyph.
     *
     * @throws UnencodableCharacterException at the first character the program
     *   has no glyph for, or has a glyph but no colour bitmap for, naming it and
     *   its position.
     * @throws IllegalStateException if [text] needs a glyph this font has not
     *   shown before and the font dictionary has already been written, or if it
     *   would take the font past the 255 codes a simple font's encoding holds.
     */
    override fun encode(text: String): ByteArray {
        val codes = ByteArray(codePointCount(text))
        var at = 0
        forEachCodePoint(text) { index, codePoint ->
            codes[at++] = show(codePoint, text, index).code.toByte()
        }
        return codes
    }

    // ---- Writing the font (§9.6.4) -----------------------------------------

    /** Table 110 over the glyphs the pages showed — [Type3EmojiFont]'s, whole. */
    override fun fontDictionary(define: (PdfObject) -> PdfRef): PdfDict = type3.fontDictionary(define)

    // ---- Internals ---------------------------------------------------------

    /**
     * The glyph the character [codePoint] selects and the code it is shown as,
     * registering both on first use.
     *
     * The glyph is keyed by its index in the program, so two characters the
     * `cmap` maps to one glyph are one code in the file; the picture, and hence
     * the refusal for a glyph that has none, comes before the code is handed
     * out.
     */
    private fun show(codePoint: Int, text: String, index: Int): Type3EmojiFont.Glyph {
        val glyphId = glyphIdOf(codePoint, text, index)
        type3.shown(key(glyphId))?.let { return it }
        val bitmap = colorBitmapOf(glyphId, codePoint, text, index)
        return type3.show(
            key = key(glyphId),
            name = "$GLYPH_NAME_PREFIX$glyphId",
            text = codePointToString(codePoint),
            png = bitmap.png,
            box = boxOf(bitmap),
        )
    }

    /**
     * Where a bitmap goes in the glyph coordinate system, converted out of the
     * pixels of the strike.
     *
     * `CBLC` states the left edge at `bearingX`, "distance in pixels from the
     * horizontal origin to the left edge of the bitmap", and the top edge at
     * `bearingY`, "distance in pixels from the horizontal origin to the top edge
     * of the bitmap" — so the bottom edge, which is what §8.9.4's unit square
     * needs, is `bearingY - height`.
     */
    private fun boxOf(bitmap: ColorBitmapGlyph): Type3EmojiFont.Box {
        val metrics = bitmap.metrics
        return Type3EmojiFont.Box(
            advance = inFontUnits(metrics.advance, strike.ppemX),
            left = inFontUnits(metrics.bearingX, strike.ppemX),
            bottom = inFontUnits(metrics.bearingY - metrics.height, strike.ppemY),
            width = inFontUnits(metrics.width, strike.ppemX),
            height = inFontUnits(metrics.height, strike.ppemY),
        )
    }

    /**
     * The colour bitmap of the character [codePoint], without registering
     * anything — what [advanceOf] measures from, and the same lookup [show]
     * makes.
     */
    private fun bitmapOf(codePoint: Int, text: String, index: Int): ColorBitmapGlyph {
        val glyphId = glyphIdOf(codePoint, text, index)
        return colorBitmapOf(glyphId, codePoint, text, index)
    }

    /**
     * The glyph the character [codePoint] selects, refusing the characters the
     * program has no glyph for.
     *
     * `cmap` answers 0 for a character it does not map, and 0 is `.notdef` —
     * which in a colour font is not even a missing-character box, since a Type 3
     * font paints only what its CharProcs hold. Refusing here names the
     * character and its position, per the no-silent-loss policy.
     */
    private fun glyphIdOf(codePoint: Int, text: String, index: Int): Int {
        val glyphId = program.glyphIdOf(codePoint)
        if (glyphId == NOTDEF) {
            throw UnencodableCharacterException.outsideFontProgram(text, index, codePoint, id)
        }
        return glyphId
    }

    /** The strike's bitmap for [glyphId], refusing a glyph that has none. */
    private fun colorBitmapOf(glyphId: Int, codePoint: Int, text: String, index: Int): ColorBitmapGlyph =
        bitmaps.getOrPut(glyphId) {
            program.colorBitmapOf(glyphId, strike)
                ?: throw UnencodableCharacterException.withoutColorBitmap(text, index, codePoint, id, glyphId)
        }

    /**
     * [pixels] of the strike in the glyph coordinate system of this font: an em
     * is [BitmapStrike.ppemX] or [BitmapStrike.ppemY] pixels of the strike and
     * `unitsPerEm` units of the program.
     */
    private fun inFontUnits(pixels: Int, ppem: Int): Double = pixels.toDouble() * program.unitsPerEm / ppem

    /**
     * [pixels] of the strike in the glyph space of §9.2.4, "one-thousandth of a
     * unit of text space" — the units [GlyphSpaceMetrics] is measured in,
     * whatever a font's own matrix says.
     */
    private fun inGlyphSpace(pixels: Int, ppem: Int): Double = pixels * Type3EmojiFont.GLYPH_SPACE_UNITS_PER_EM / ppem

    override fun toString(): String = "ColorEmojiFont($id)"

    private companion object {
        /** Glyph 0, ".notdef", "a special glyph representing a missing character". */
        const val NOTDEF: Int = 0

        /**
         * `/g12`, `/g4523`, … — a glyph is named after its index in the program,
         * which makes the name unique within the font and traceable back to the
         * program it came out of.
         */
        const val GLYPH_NAME_PREFIX: String = "g"

        /** What tells one glyph of this font from another: its index in the program. */
        fun key(glyphId: Int): String = glyphId.toString()
    }
}
