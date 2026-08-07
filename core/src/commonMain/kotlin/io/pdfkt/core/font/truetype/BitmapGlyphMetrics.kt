/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.font.truetype

// OpenType 1.9.1 §"EBLC — Embedded Bitmap Location Table": "BigGlyphMetrics record",
// "SmallGlyphMetrics record"; §"CBDT — Color Bitmap Data Table": "Glyph metrics"
// ISO 32000-2:2020 §9.2.4 "Glyph positioning and metrics"

/**
 * `SmallGlyphMetrics` — where a bitmap glyph sits relative to the origin, and
 * how far the origin then moves, in **one** direction.
 *
 * > Small glyph metrics define metrics information for one layout direction
 * > only. Which direction applies, horizontal or vertical, is determined by the
 * > flags field of BitmapSize records within the CBLC table.
 *
 * So the same five numbers mean different things in a strike flagged vertical;
 * [BitmapStrike.metricsAreHorizontal] is the field that says which, and
 * [BigGlyphMetrics.inDirection] is how the two-direction record is reduced to
 * this one.
 *
 * ## The unit is a pixel of this strike, not a design unit
 *
 * Everything here is "in pixels", of a bitmap drawn at the strike's
 * [BitmapStrike.ppemX] × [BitmapStrike.ppemY] pixels per em. Nothing in this
 * package converts them: an em is [BitmapStrike.ppemY] pixels tall, and the
 * caller that places the glyph on a page divides by that, the same way §9.2.4's
 * glyph space is reached from the design units of `hmtx`.
 *
 * @property height "Number of rows in the bitmap."
 * @property width "Number of columns in the bitmap."
 * @property bearingX "Distance in pixels from the horizontal origin to the left
 *   edge of the bitmap (for horizontal text); or distance in pixels from the
 *   vertical origin to the top edge of the bitmap (for vertical text)."
 * @property bearingY "Distance in pixels from the horizontal origin to the top
 *   edge of the bitmap (for horizontal text); or distance in pixels from the
 *   vertical origin to the left edge of the bitmap (for vertical text)."
 * @property advance "Horizontal or vertical advance width in pixels."
 */
internal data class SmallGlyphMetrics(
    val height: Int,
    val width: Int,
    val bearingX: Int,
    val bearingY: Int,
    val advance: Int,
) {
    companion object {
        /** `uint8`, `uint8`, `int8`, `int8`, `uint8`. */
        const val SIZE: Int = 5

        /** Reads the record at the cursor of [reader], advancing it by [SIZE]. */
        fun read(reader: BigEndianReader): SmallGlyphMetrics = SmallGlyphMetrics(
            height = reader.uint8(),
            width = reader.uint8(),
            bearingX = reader.int8(),
            bearingY = reader.int8(),
            advance = reader.uint8(),
        )
    }
}

/**
 * `BigGlyphMetrics` — the same as [SmallGlyphMetrics] for both directions at
 * once.
 *
 * > Big glyph metrics define metrics information for both horizontal and
 * > vertical layouts. This is important in fonts (such as Kanji) where both
 * > types of layout may be used.
 *
 * This is the record the constant-metrics index subtable formats keep one copy
 * of ([CblcIndexSubtable.Format2], [CblcIndexSubtable.Format5]) and the record
 * image format 18 stores per glyph.
 *
 * @property height "Number of rows in the bitmap."
 * @property width "Number of columns in the bitmap."
 * @property horiBearingX "Distance in pixels from the horizontal origin to the
 *   left edge of the bitmap."
 * @property horiBearingY "Distance in pixels from the horizontal origin to the
 *   top edge of the bitmap."
 * @property horiAdvance "Horizontal advance width in pixels."
 * @property vertBearingX "Distance in pixels from the vertical origin to the
 *   left edge of the bitmap."
 * @property vertBearingY "Distance in pixels from the vertical origin to the top
 *   edge of the bitmap."
 * @property vertAdvance "Vertical advance width in pixels."
 */
internal data class BigGlyphMetrics(
    val height: Int,
    val width: Int,
    val horiBearingX: Int,
    val horiBearingY: Int,
    val horiAdvance: Int,
    val vertBearingX: Int,
    val vertBearingY: Int,
    val vertAdvance: Int,
) {
    /**
     * The five numbers of [SmallGlyphMetrics] for the direction a strike is
     * flagged for — the horizontal half when [horizontal], the vertical half
     * otherwise.
     */
    fun inDirection(horizontal: Boolean): SmallGlyphMetrics = SmallGlyphMetrics(
        height = height,
        width = width,
        bearingX = if (horizontal) horiBearingX else vertBearingX,
        bearingY = if (horizontal) horiBearingY else vertBearingY,
        advance = if (horizontal) horiAdvance else vertAdvance,
    )

    companion object {
        /** `uint8`, `uint8`, `int8`, `int8`, `uint8`, `int8`, `int8`, `uint8`. */
        const val SIZE: Int = 8

        /** Reads the record at the cursor of [reader], advancing it by [SIZE]. */
        fun read(reader: BigEndianReader): BigGlyphMetrics = BigGlyphMetrics(
            height = reader.uint8(),
            width = reader.uint8(),
            horiBearingX = reader.int8(),
            horiBearingY = reader.int8(),
            horiAdvance = reader.uint8(),
            vertBearingX = reader.int8(),
            vertBearingY = reader.int8(),
            vertAdvance = reader.uint8(),
        )
    }
}

/**
 * `SbitLineMetrics` — the line metrics of a strike, one direction's worth.
 *
 * > The horizontal and vertical line metrics contain the ascender, descender,
 * > linegap, and advance information for the strike. … All of the line metrics
 * > are one byte in length. The line metrics are not used directly by the
 * > rasterizer, but are available to applications that want to parse the EBLC
 * > table.
 *
 * This library is such an application: [ascender] and [descender] are how far a
 * line of these bitmaps reaches above and below the baseline in the strike's own
 * pixels, which is what the shared-baseline placement of a mixed
 * text-and-emoji line needs.
 *
 * The record ends with `pad1` and `pad2`, which are read past and not kept.
 */
internal data class SbitLineMetrics(
    val ascender: Int,
    val descender: Int,
    val widthMax: Int,
    val caretSlopeNumerator: Int,
    val caretSlopeDenominator: Int,
    val caretOffset: Int,
    val minOriginSB: Int,
    val minAdvanceSB: Int,
    val maxBeforeBL: Int,
    val minAfterBL: Int,
) {
    companion object {
        /** Ten one-byte fields, then `pad1` and `pad2`. */
        const val SIZE: Int = 12

        /** Reads the record at the cursor of [reader], advancing it by [SIZE]. */
        fun read(reader: BigEndianReader): SbitLineMetrics {
            val metrics = SbitLineMetrics(
                ascender = reader.int8(),
                descender = reader.int8(),
                widthMax = reader.uint8(),
                caretSlopeNumerator = reader.int8(),
                caretSlopeDenominator = reader.int8(),
                caretOffset = reader.int8(),
                minOriginSB = reader.int8(),
                minAdvanceSB = reader.int8(),
                maxBeforeBL = reader.int8(),
                minAfterBL = reader.int8(),
            )
            reader.skip(2) // pad1, pad2
            return metrics
        }
    }
}
