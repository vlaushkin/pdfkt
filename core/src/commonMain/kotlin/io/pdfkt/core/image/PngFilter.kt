/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.image

// Portable Network Graphics (PNG) Specification (Third Edition),
// W3C Recommendation 24 June 2025
//   §7.3 "Filtering", §9.1 "Filter methods and filter types",
//   §9.2 "Filter types for filter method 0" (Table 10, Table 11),
//   §9.3 "Filter type 3: Average", §9.4 "Filter type 4: Paeth",
//   §13.9 "Filtering"

/**
 * Reverses the filtering of §9.2 over [filtered], the decompressed image data:
 * [height] scanlines, each a filter type byte followed by [bytesPerRow] bytes.
 *
 * What comes back is the scanlines with the filter type bytes gone and each
 * byte reconstructed — "the filter type byte is not considered part of the
 * image data, but it is included in the datastream sent to the compression
 * step" (§7.3).
 *
 * Every filter works on bytes and never on pixels: "Filters are applied to
 * bytes, not to pixels, regardless of the bit depth or color type of the image
 * … If the image includes an alpha channel, the alpha data is filtered in the
 * same way as the image data." So the whole of this function's knowledge of the
 * image is [bytesPerRow] and [bytesPerPixel], and every colour type and bit
 * depth goes through it unchanged.
 *
 * The four named bytes of Table 10 are `x`, the byte being filtered; `a`, "the
 * byte corresponding to x in the pixel immediately before the pixel containing
 * x (or the byte immediately before x, when the bit depth is less than 8)";
 * `b`, "the byte corresponding to x in the previous scanline"; and `c`, the
 * byte before `b` in the same sense that `a` is the byte before `x`. Where
 * those fall outside the image the clause fixes the value: "For all filters,
 * the bytes 'to the left of' the first pixel in a scanline shall be treated as
 * being zero. For filters that refer to the prior scanline, the entire prior
 * scanline and bytes 'to the left of' the first pixel in the prior scanline
 * shall be treated as being zeroes for the first scanline of a reduced image."
 *
 * Reconstruction is in place and forwards, because each Recon() of Table 11
 * reads bytes that are already reconstructed — `Recon(a)`, `Recon(b)`,
 * `Recon(c)`, never `Filt` of anything — which is also why a decoder "will
 * always need to store each scanline as it is decoded, since the next scanline
 * might use a filter type that refers to it" (§13.9).
 *
 * @param bytesPerPixel the distance from `x` to `a`, which is the size of a
 *   pixel in bytes, or 1 where a pixel is smaller than a byte.
 * @param imageDataAt where the image data begin in the datastream, used only to
 *   place a refusal — a position inside the decompressed data is not a position
 *   in the file (§13.8).
 * @throws PngFormatException if a scanline declares a filter type that filter
 *   method 0 does not define.
 */
internal fun pngReconstruct(
    filtered: ByteArray,
    height: Int,
    bytesPerRow: Int,
    bytesPerPixel: Int,
    imageDataAt: Int,
): ByteArray {
    val raster = ByteArray(height * bytesPerRow)
    var source = 0
    var row = 0

    for (y in 0 until height) {
        val filterType = filtered[source].toInt() and BYTE_MASK
        source++
        filtered.copyInto(raster, row, source, source + bytesPerRow)
        source += bytesPerRow

        val prior = row - bytesPerRow
        fun left(index: Int): Int =
            if (index >= bytesPerPixel) raster[row + index - bytesPerPixel].toInt() and BYTE_MASK else 0
        fun above(index: Int): Int = if (y > 0) raster[prior + index].toInt() and BYTE_MASK else 0
        fun upperLeft(index: Int): Int =
            if (y > 0 && index >= bytesPerPixel) raster[prior + index - bytesPerPixel].toInt() and BYTE_MASK else 0

        // Table 11, the Reconstruction Function column. "Unsigned arithmetic
        // modulo 256 is used, so that both the inputs and outputs fit into
        // bytes" — which is what storing the sum back into a byte does.
        when (filterType) {
            // 0 None: Recon(x) = Filt(x). The copy above is the whole of it.
            FILTER_NONE -> Unit

            // 1 Sub: Recon(x) = Filt(x) + Recon(a)
            FILTER_SUB ->
                for (index in bytesPerPixel until bytesPerRow) {
                    raster[row + index] = (raster[row + index] + left(index)).toByte()
                }

            // 2 Up: Recon(x) = Filt(x) + Recon(b)
            FILTER_UP ->
                if (y > 0) {
                    for (index in 0 until bytesPerRow) {
                        raster[row + index] = (raster[row + index] + above(index)).toByte()
                    }
                }

            // 3 Average: Recon(x) = Filt(x) + floor((Recon(a) + Recon(b)) / 2).
            // "The sum Orig(a) + Orig(b) shall be performed without overflow
            // (using at least nine-bit arithmetic)" (§9.3) — the addition below
            // is of two values already widened to Int, so it is exact, and the
            // division of a non-negative Int truncates towards zero, which for
            // a non-negative value is the floor the clause asks for.
            FILTER_AVERAGE ->
                for (index in 0 until bytesPerRow) {
                    raster[row + index] = (raster[row + index] + (left(index) + above(index)) / 2).toByte()
                }

            // 4 Paeth: Recon(x) = Filt(x) + PaethPredictor(Recon(a), Recon(b), Recon(c))
            FILTER_PAETH ->
                for (index in 0 until bytesPerRow) {
                    val predictor = paethPredictor(left(index), above(index), upperLeft(index))
                    raster[row + index] = (raster[row + index] + predictor).toByte()
                }

            else -> throw PngFormatException(
                imageDataAt,
                "scanline $y of the decompressed image data declares the filter type $filterType, at byte " +
                    "${source - bytesPerRow - 1} of those data; \"filter method 0 specifies exactly this set of " +
                    "five filter types and this shall not be extended\" — 0 None, 1 Sub, 2 Up, 3 Average and " +
                    "4 Paeth",
                FILTER_CLAUSE,
            )
        }

        row += bytesPerRow
    }

    return raster
}

/**
 * The PaethPredictor function of §9.4, which "computes a simple linear function
 * of the three neighboring pixels (left, above, upper left), then chooses as
 * predictor the neighboring pixel closest to the computed value".
 *
 * The clause prints it as code, and this is that code:
 *
 * ```
 * p = a + b - c
 * pa = abs(p - a)
 * pb = abs(p - b)
 * pc = abs(p - c)
 * if pa <= pb and pa <= pc then Pr = a
 * else if pb <= pc then Pr = b
 * else Pr = c
 * return Pr
 * ```
 *
 * Two sentences of the clause are the reason it is written out rather than
 * simplified. "The calculations within the PaethPredictor function shall be
 * performed exactly, without overflow" — so the arithmetic is in [Int], where
 * `a + b - c` of three bytes cannot overflow, and never in bytes. And "the
 * order in which the comparisons are performed is critical and shall not be
 * altered": where two of the three distances are equal the earlier branch wins,
 * so `pa <= pb` and `pb <= pc` are the comparisons, not `<`, and `a` is
 * preferred to `b` and `b` to `c`. "Exactly the same PaethPredictor function is
 * used by both encoder and decoder", so a decoder that broke a tie the other
 * way would not merely predict worse — it would reconstruct different bytes.
 */
internal fun paethPredictor(a: Int, b: Int, c: Int): Int {
    val p = a + b - c
    val pa = abs(p - a)
    val pb = abs(p - b)
    val pc = abs(p - c)
    return if (pa <= pb && pa <= pc) {
        a
    } else if (pb <= pc) {
        b
    } else {
        c
    }
}

private fun abs(value: Int): Int = if (value < 0) -value else value

/** Table 11, "Type" — the five filter types filter method 0 defines. */
private const val FILTER_NONE: Int = 0
private const val FILTER_SUB: Int = 1
private const val FILTER_UP: Int = 2
private const val FILTER_AVERAGE: Int = 3
private const val FILTER_PAETH: Int = 4

private const val BYTE_MASK: Int = 0xFF

private const val FILTER_CLAUSE: String =
    "PNG Specification (Third Edition) §9.2 \"Filter types for filter method 0\""
