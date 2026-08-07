/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.font

import io.pdfkt.core.font.truetype.BitmapSizeSpec
import io.pdfkt.core.font.truetype.cbdtTableBytes
import io.pdfkt.core.font.truetype.cblcTableBytes
import io.pdfkt.core.font.truetype.colorBitmapFontBytes
import io.pdfkt.core.font.truetype.glyphBitmapFormat17
import io.pdfkt.core.font.truetype.indexSubtableFormat1
import io.pdfkt.core.font.truetype.sbitLineMetricsBytes
import io.pdfkt.core.font.truetype.smallGlyphMetricsBytes
import io.pdfkt.core.image.pngOf

// OpenType 1.9.1 §"CBLC — Color Bitmap Location Table",
// §"CBDT — Color Bitmap Data Table", §"EBLC — Embedded Bitmap Location Table"
// (the `sbitLineMetrics` and `smallGlyphMetrics` records CBLC shares with it)

/**
 * The colour font the colour-emoji tests are written against, built out of the
 * OpenType field tables so that every number a test asserts is a number a test
 * put in a field.
 *
 * Three glyphs, at glyph indices 1, 2 and 3, selected by U+1F600, U+1F601 and
 * U+1F602 through a `cmap` format 12 subtable:
 *
 * | Glyph | Bitmap | Metrics, in pixels of the strike |
 * |---|---|---|
 * | 1 | 4 × 4 | bearingX 1, bearingY 3, advance 5 |
 * | 2 | 6 × 2 | bearingX 0, bearingY 2, advance 7 |
 * | 3 | 4 × 4 | bearingX -1, bearingY 5, advance 4 |
 *
 * The one strike is [COLOR_EMOJI_PPEM] pixels to the em and states an ascender
 * of 101 pixels and a descender of -27; the program counts `unitsPerEm` units to
 * the em, 2048 by default, so a pixel is 64 units of glyph space. Glyph 3
 * reaches left of the origin and above the others, which is what a bounding box
 * over the union of the glyphs has to notice.
 *
 * Nothing else has a picture: U+1F44B, the waving hand, is the emoji this font
 * is asked for when a test wants it to refuse.
 *
 * @param lastGlyphInStrike how far the strike's index subtable reaches, so a
 *   test can ask for a glyph the `cmap` maps and the strike does not cover.
 */
internal fun colorEmojiFontBytes(unitsPerEm: Int = 2048, lastGlyphInStrike: Int = 3): ByteArray {
    val bitmaps = listOf(GRIN_PIXELS, BEAM_PIXELS, TEARS_PIXELS)
    val cbdt = cbdtTableBytes(
        (0 until lastGlyphInStrike).map { index ->
            glyphBitmapFormat17(colorEmojiPngBytes(bitmaps[index]), COLOR_EMOJI_METRICS[index])
        },
    )
    val base = cbdt.offsets.first()
    return colorBitmapFontBytes(
        cblc = cblcTableBytes(
            listOf(
                BitmapSizeSpec(
                    indexSubtables = listOf(
                        indexSubtableFormat1(
                            firstGlyphIndex = 1,
                            lastGlyphIndex = lastGlyphInStrike,
                            imageFormat = 17,
                            imageDataOffset = base.toLong(),
                            sbitOffsets = cbdt.offsets.map { (it - base).toLong() } +
                                (cbdt.bytes.size - base).toLong(),
                        ),
                    ),
                    startGlyphIndex = 1,
                    endGlyphIndex = lastGlyphInStrike,
                    ppemX = COLOR_EMOJI_PPEM,
                    ppemY = COLOR_EMOJI_PPEM,
                    hori = sbitLineMetricsBytes(
                        ascender = COLOR_EMOJI_ASCENDER,
                        descender = COLOR_EMOJI_DESCENDER,
                    ),
                ),
            ),
        ),
        cbdt = cbdt.bytes,
        unitsPerEm = unitsPerEm,
    )
}

/** A truecolour PNG of [pixels], one row per element and three bytes per pixel. */
internal fun colorEmojiPngBytes(pixels: ColorEmojiPixels): ByteArray = pngOf(
    width = pixels.width,
    height = pixels.height,
    bitDepth = 8,
    colourType = 2,
    rows = List(pixels.height) { row ->
        ByteArray(pixels.width * 3) { at -> (pixels.seed + row * pixels.width * 3 + at).toByte() }
    },
)

/** A picture of a size, filled with bytes nothing else in these tests produces. */
internal class ColorEmojiPixels(val width: Int, val height: Int, val seed: Int)

/** The strike of the font above: 32 pixels to the em. */
internal const val COLOR_EMOJI_PPEM: Int = 32

/** The strike's `sbitLineMetrics`, in pixels — what a shared baseline is placed from. */
internal const val COLOR_EMOJI_ASCENDER: Int = 101
internal const val COLOR_EMOJI_DESCENDER: Int = -27

/** U+1F600 GRINNING FACE, glyph 1 of the font above. */
internal const val COLOR_EMOJI_GRIN: String = "😀"

/** U+1F601 GRINNING FACE WITH SMILING EYES, glyph 2. */
internal const val COLOR_EMOJI_BEAM: String = "😁"

/** U+1F602 FACE WITH TEARS OF JOY, glyph 3. */
internal const val COLOR_EMOJI_TEARS: String = "😂"

internal val GRIN_PIXELS: ColorEmojiPixels = ColorEmojiPixels(width = 4, height = 4, seed = 0x10)
internal val BEAM_PIXELS: ColorEmojiPixels = ColorEmojiPixels(width = 6, height = 2, seed = 0x40)
internal val TEARS_PIXELS: ColorEmojiPixels = ColorEmojiPixels(width = 4, height = 4, seed = 0x70)

/** The `smallGlyphMetrics` of the three glyphs, in pixels of the strike. */
internal val COLOR_EMOJI_METRICS: List<ByteArray> = listOf(
    smallGlyphMetricsBytes(height = 4, width = 4, bearingX = 1, bearingY = 3, advance = 5),
    smallGlyphMetricsBytes(height = 2, width = 6, bearingX = 0, bearingY = 2, advance = 7),
    smallGlyphMetricsBytes(height = 4, width = 4, bearingX = -1, bearingY = 5, advance = 4),
)
