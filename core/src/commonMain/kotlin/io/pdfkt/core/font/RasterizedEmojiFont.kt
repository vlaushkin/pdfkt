/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.font

import io.pdfkt.core.obj.PdfDict
import io.pdfkt.core.obj.PdfObject
import io.pdfkt.core.obj.PdfRef
import io.pdfkt.core.text.TextSegment
import io.pdfkt.core.text.segmentEmoji

// ISO 32000-2:2020 §9.6.4 "Type 3 fonts" (Tables 110, 111),
// §9.6.1 "Simple fonts — General", §9.2.4 "Glyph positioning and metrics",
// §9.10.3 "ToUnicode CMaps"
// Unicode Technical Standard #51 §1.4.6 "Emoji Sequences"

/**
 * Emoji drawn by a caller's [EmojiRasterizer], written as the Type 3 font of
 * §9.6.4 — [Type3EmojiFont], which writes every byte of it.
 *
 * The same file [ColorEmojiFont] produces, from another source of pictures: a
 * PNG per emoji, its box and its advance, out of whatever the caller's platform
 * draws with instead of out of a font program's `CBDT` table. Everything the
 * clause fixes — the CharProcs, the one-byte encoding, the `Widths`, the
 * `ToUnicode` CMap — is shared, because it is fixed by the clause and not by
 * where the pictures came from.
 *
 * ## What a glyph is here
 *
 * **One whole emoji sequence**, not one code point. That is the difference the
 * seam buys: a rasterizer is asked for `👨‍👩‍👧` and answers with the one picture
 * of a family, where a font program would need `GSUB` to say that those three
 * characters and two joiners are one glyph. So a code of this font stands for
 * the whole sequence, and the `ToUnicode` CMap maps it back to every code point
 * of it — "no information would be available about what the glyphs mean"
 * otherwise (§9.10.3), and half a family is worse than none.
 *
 * Text handed to this font is cut into emoji the way the resolve stage cuts it,
 * by [segmentEmoji] per UTS #51: this font is asked for the run an emoji policy
 * sent to it, and two emoji that ended up in one run are two glyphs. Anything in
 * such a run that is *not* an emoji is refused — this font has no pictures but
 * the ones a rasterizer draws for emoji, and drawing nothing is the silent loss
 * this library forbids.
 *
 * ## The glyph coordinate system is the raster's pixel
 *
 * There is no font program here, so there are no design units: the em is
 * [emPixels] pixels, which is what the rasterizer was asked for, and the
 * `FontMatrix` is `[1/emPixels 0 0 1/emPixels 0 0]`. A number the rasterizer
 * states in pixels is therefore a number of glyph units unchanged — the shortest
 * road from what the caller measured to what the file says, with no rounding in
 * between.
 *
 * The [GlyphSpaceMetrics] the layout engine sees are in the thousandths of
 * §9.2.4 like every other font's, which is one multiplication away.
 *
 * ## The line an emoji stands on
 *
 * A font program states its ascent and descent; a rasterizer states neither, and
 * inventing them from the glyphs drawn so far would make a document's line
 * heights depend on the order its pages were built in. So this font states the
 * convention its contract already implies: an emoji is a picture of an em,
 * standing on the baseline — **ascent one em, descent zero**, no line gap. A
 * rasterizer whose picture reaches below the baseline still gets it drawn
 * exactly where its bearings put it, and the `FontBBox` still reports it; what
 * it does not get is a taller line.
 *
 * @param id what this font is called within one document; it names the images of
 *   its glyphs and appears in every message about it.
 * @param rasterizer what draws one emoji. Asked once per distinct sequence per
 *   document.
 * @param emPixels how many pixels of the rasterizer's output make an em.
 */
internal class RasterizedEmojiFont(
    private val id: String,
    private val rasterizer: EmojiRasterizer,
    private val emPixels: Int,
) : WrittenFont {
    private val type3 = Type3EmojiFont(id, emPixels)

    /**
     * What the rasterizer answered, by sequence — including for the sequences
     * only measured so far, since measuring a line the layout then breaks
     * elsewhere must not rasterize the same emoji twice.
     */
    private val rasterized: MutableMap<String, RasterGlyph> = mutableMapOf()

    // ---- Metrics (§9.2.4) --------------------------------------------------

    /**
     * The advance of [text] in the glyph space of §9.2.4 — thousandths of a unit
     * of text space — summed over the emoji it is made of.
     *
     * Measuring shows nothing: a line the layout measured and then broke
     * elsewhere must not leave a glyph in the font that no page draws.
     *
     * @throws UnencodableCharacterException at the first emoji the rasterizer
     *   has no picture for, or the first character that is not an emoji at all.
     */
    override fun advanceOf(text: String): Double {
        var advance = 0.0
        for (emoji in emojiOf(text)) {
            advance += inGlyphSpace(pictureOf(emoji, text).advance)
        }
        return advance
    }

    /** One em above the baseline — the convention of the class documentation. */
    override val ascent: Double get() = Type3EmojiFont.GLYPH_SPACE_UNITS_PER_EM

    /** Nothing below it, for the same reason. */
    override val descent: Double get() = 0.0

    /** No line gap: a rasterizer states none, and a made-up one would space every line of the page. */
    override val leading: Double get() = 0.0

    // ---- Showing text (§9.6.4) ---------------------------------------------

    /**
     * The character codes [text] is shown as: one byte per emoji, each the code
     * this font hands that emoji's picture.
     *
     * @throws UnencodableCharacterException at the first emoji the rasterizer
     *   has no picture for, naming the sequence, or at the first character of
     *   [text] that is not part of an emoji.
     * @throws IllegalStateException if [text] needs a glyph this font has not
     *   shown before and the font dictionary has already been written, or if it
     *   would take the font past the 255 codes a simple font's encoding holds.
     */
    override fun encode(text: String): ByteArray {
        val emoji = emojiOf(text)
        val codes = ByteArray(emoji.size)
        for ((at, segment) in emoji.withIndex()) {
            codes[at] = show(segment, text).code.toByte()
        }
        return codes
    }

    // ---- Writing the font (§9.6.4) -----------------------------------------

    /** Table 110 over the emoji the pages showed — [Type3EmojiFont]'s, whole. */
    override fun fontDictionary(define: (PdfObject) -> PdfRef): PdfDict = type3.fontDictionary(define)

    // ---- Internals ---------------------------------------------------------

    /**
     * [text] as the emoji it is made of, refusing the first character that is
     * not part of one.
     *
     * The cuts are [segmentEmoji]'s, the same ones the resolve stage made when
     * it sent this run to this font (UTS #51 §1.4.6). They are made again here
     * rather than carried, because a run reaching this font may be several
     * emoji: the resolve stage merges adjacent runs it would draw identically,
     * and two emoji beside each other are exactly that.
     */
    private fun emojiOf(text: String): List<TextSegment.Emoji> = segmentEmoji(text).map { segment ->
        when (segment) {
            is TextSegment.Emoji -> segment
            is TextSegment.Plain -> throw UnencodableCharacterException.outsideEmojiFont(
                text = text,
                index = segment.index,
                codePoint = codePointAt(segment.text, 0),
                fontName = id,
            )
        }
    }

    /**
     * The glyph [emoji] is shown as and the code it carries, registering both on
     * first use — the picture first, so that an emoji with none is refused
     * before a code is handed out for it.
     */
    private fun show(emoji: TextSegment.Emoji, text: String): Type3EmojiFont.Glyph {
        type3.shown(emoji.text)?.let { return it }
        val picture = pictureOf(emoji, text)
        val glyph = type3.show(
            key = emoji.text,
            name = "$GLYPH_NAME_PREFIX${type3.nextCode()}",
            text = emoji.text,
            png = picture.png,
            box = boxOf(picture),
        )
        requireSamplesMatch(glyph, picture, emoji)
        return glyph
    }

    /**
     * The picture of [emoji], asked for once per document and kept.
     *
     * @throws UnencodableCharacterException if the rasterizer has none — the
     *   no-silent-loss policy, told with the sequence and its
     *   place in the caller's text, because an emoji nobody can draw is not an
     *   emoji to leave a gap for.
     */
    private fun pictureOf(emoji: TextSegment.Emoji, text: String): RasterGlyph = rasterized.getOrPut(emoji.text) {
        rasterizer.rasterize(emoji.text, emPixels)
            ?: throw UnencodableCharacterException.withoutRasterizedPicture(
                text = text,
                index = emoji.index,
                sequence = emoji.text,
                fontName = id,
                emPixels = emPixels,
            )
    }

    /**
     * Where a rasterized picture goes in the glyph coordinate system: its own
     * pixels, since an em is [emPixels] of them and [emPixels] units.
     *
     * The bottom edge is `bearingY - height`, because [RasterGlyph.bearingY] is
     * where the *top* edge stands and §8.9.4's unit square is mapped onto the
     * rectangle from the bottom left.
     */
    private fun boxOf(picture: RasterGlyph): Type3EmojiFont.Box = Type3EmojiFont.Box(
        advance = picture.advance.toDouble(),
        left = picture.bearingX.toDouble(),
        bottom = (picture.bearingY - picture.height).toDouble(),
        width = picture.width.toDouble(),
        height = picture.height.toDouble(),
    )

    /**
     * Refuses a picture whose PNG is not the size the rasterizer said it was.
     *
     * The box the glyph description maps the image onto is built from the
     * numbers the rasterizer stated, and the samples are the PNG's: if they
     * disagree, the emoji is stretched into a box of the wrong size — a page
     * that renders, and renders it wrong. The decoded size is the image
     * catalog's, already read out of the datastream, so the check costs nothing.
     */
    private fun requireSamplesMatch(glyph: Type3EmojiFont.Glyph, picture: RasterGlyph, emoji: TextSegment.Emoji) {
        val image = glyph.image.ref
        require(image.width == picture.width && image.height == picture.height) {
            "the rasterizer of the emoji font \"$id\" answered for \"${emoji.text}\" with a glyph it " +
                "states is ${picture.width} x ${picture.height} pixels, and a PNG of " +
                "${image.width} x ${image.height} samples. The glyph description places the picture in " +
                "the box the stated size gives it (ISO 32000-2:2020 §8.9.4), so the two disagreeing is " +
                "an emoji drawn at the wrong size; state the size of the datastream"
        }
    }

    /**
     * [pixels] in the glyph space of §9.2.4, "one-thousandth of a unit of text
     * space" — the units [GlyphSpaceMetrics] is measured in, whatever a font's
     * own matrix says.
     */
    private fun inGlyphSpace(pixels: Int): Double = pixels * Type3EmojiFont.GLYPH_SPACE_UNITS_PER_EM / emPixels

    override fun toString(): String = "RasterizedEmojiFont($id, $emPixels px/em)"

    private companion object {
        /**
         * `/g1`, `/g2`, … — a rasterized glyph has no index in any program to be
         * named after, so it is named after the code it was handed, which is
         * unique within the font by construction.
         */
        const val GLYPH_NAME_PREFIX: String = "g"
    }
}
