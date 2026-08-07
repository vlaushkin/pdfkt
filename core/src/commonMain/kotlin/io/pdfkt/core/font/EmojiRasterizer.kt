/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.font

// ISO 32000-2:2020 §9.6.4 "Type 3 fonts" (Tables 110, 111),
// §8.9.4 "Image coordinate system", §9.2.4 "Glyph positioning and metrics"
// ISO/IEC 15948:2004 (PNG) §5 "Datastream structure"
// Unicode Technical Standard #51 §1.4.6 "Emoji Sequences"

/**
 * What draws one emoji, supplied by the caller.
 *
 * The seam exists because the picture of an emoji is not something this library
 * can produce: it is either in a font program the caller supplies
 * ([io.pdfkt.core.style.EmojiPolicy.EmbeddedColorFont]) or drawn by something
 * that already knows how — a platform's text stack, a bitmap set shipped beside
 * the application, a canvas in a browser. Everything below this interface is the
 * same either way: the PNG becomes a glyph description of the Type 3 font of
 * §9.6.4, exactly as a `CBDT` bitmap does.
 *
 * What it buys over the font-program path is the one thing that path cannot do:
 * a rasterizer is asked for a whole *sequence*, so a family joined by
 * zero-width joiners or an emoji with a skin tone is one picture and one code,
 * rather than the parts it is composed of. Selecting the composed glyph inside a
 * font program is `GSUB`'s business, which this library does not read; asking
 * whoever drew it is not.
 *
 * A `fun interface`, so a lambda is one — the implementation is per platform by
 * nature (`java.awt` on the JVM, a canvas on the web), and it stays out of
 * `commonMain`, which is platform-free by design.
 *
 * ## The contract
 *
 * - the glyph is drawn **on the baseline at the origin**: [RasterGlyph.bearingX]
 *   and [RasterGlyph.bearingY] say where the picture stands relative to that
 *   point, and are the numbers that put it there;
 * - every distance is **in pixels of the em that was asked for**, so a
 *   rasterizer that produces 2 × the pixels for the same emPixels draws it twice
 *   as large on the page. Nothing rescales behind the caller's back;
 * - one call per distinct sequence per document: the font keeps what it was
 *   given, so a page of a hundred identical emoji rasterizes once;
 * - **null means "no picture"**, and it stops the document naming the sequence.
 * Returning a blank image
 *   instead would be a page that renders and says nothing.
 */
public fun interface EmojiRasterizer {
    /**
     * The picture of [sequence] at [emPixels] pixels to the em, or null if this
     * rasterizer has none for it.
     *
     * @param sequence one whole emoji, as the text it is: a single character, or
     *   the code points UTS #51 §1.4.6 composes into one picture — a flag, a
     *   skin-toned hand, a family and its joiners.
     * @param emPixels how many pixels stand for an em. Always positive; it is
     *   the resolution the picture is measured in and not a size on the page,
     *   which is the font size the emoji is drawn at.
     */
    public fun rasterize(sequence: String, emPixels: Int): RasterGlyph?
}

/**
 * One rasterized emoji: the picture, and where it goes relative to the glyph
 * origin.
 *
 * The picture is a **PNG datastream**, because that is what the rest of this
 * library already turns into an image XObject of §8.9 — the same path a `CBDT`
 * bitmap takes — and because it carries its own
 * alpha, which an emoji beside text needs.
 *
 * Every distance is an integer number of pixels of the em the rasterizer was
 * asked for, which is the coordinate system of the picture itself. The Type 3
 * font converts them once, into the glyph space its `FontMatrix` fixes (§9.6.4);
 * nothing here is in points, in text space or in thousandths.
 *
 * @property width the width of the picture in pixels, and [height] its height —
 *   the samples of the PNG, and refused if they disagree with it: a glyph placed
 *   in a box of the wrong size is an emoji quietly stretched.
 * @property bearingX where the left edge of the picture stands relative to the
 *   glyph origin, positive to the right and free to be negative for a picture
 *   that reaches left of it.
 * @property bearingY where the **top** edge stands relative to the baseline,
 *   positive upwards — the direction `CBLC` states it in, and the one that makes
 *   a picture descending below the baseline the case where `bearingY < height`.
 * @property advance how far the pen moves after drawing it: `wx` of the `d0`
 *   operator (Table 111) and the entry this glyph gets in `Widths`. Not the
 *   width of the picture — the two differ by the space an emoji leaves around
 *   itself.
 */
public class RasterGlyph(
    png: ByteArray,
    public val width: Int,
    public val height: Int,
    public val bearingX: Int,
    public val bearingY: Int,
    public val advance: Int,
) {
    /**
     * The PNG datastream of the picture.
     *
     * Not readable back, for the reason `FontRef.Supplied` does not hand its
     * program back either: an array a caller can still reach into is a picture
     * that can change after it was decoded and written into the file.
     */
    internal val png: ByteArray = png

    init {
        require(png.isNotEmpty()) {
            "a rasterized emoji needs the bytes of a PNG datastream; this one arrived with none " +
                "(ISO 32000-2:2020 §9.6.4 \"Type 3 fonts\" — a glyph description paints an image, and " +
                "there is nothing here to paint). A rasterizer with no picture for an emoji returns " +
                "null, which is reported with the sequence rather than drawn as a gap"
        }
        require(width > 0 && height > 0) {
            "a rasterized emoji is $width x $height pixels: a picture with a dimension of zero is no " +
                "picture (ISO 32000-2:2020 §8.9.5.1, Table 87 — Width and Height are the size of the " +
                "sample data)"
        }
        require(advance >= 0) {
            "a rasterized emoji advances $advance pixels: a negative advance would draw the next glyph " +
                "over this one, and 'wx' of d0 is \"the horizontal displacement in the glyph coordinate " +
                "system\" (ISO 32000-2:2020 §9.6.4, Table 111)"
        }
    }

    override fun toString(): String = "RasterGlyph(${width}x$height px, bearing $bearingX $bearingY, advance $advance)"
}
