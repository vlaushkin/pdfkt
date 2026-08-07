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
//   §6.1 "Color types and values" (Table 9), §6.2 "Alpha representation",
//   §11.2.1 "IHDR Image header" (Table 12)

/**
 * What one pixel of a PNG image is made of — the IHDR colour type, and with it
 * the number of samples a pixel carries and the bit depths that pixel may use.
 *
 * The code is not an arbitrary tag: §6.1 says it "is the sum of the following
 * values: 1 (palette used), 2 (truecolor used) and 4 (alpha used)", which is
 * why 1, 5 and 7 are missing — a palette with an alpha channel in the image
 * array is not a combination PNG defines, and alpha for an indexed image is
 * carried by a tRNS chunk instead (§6.2).
 *
 * [channels] is what the filters and the scanline layout are counted in: a
 * scanline is [channels] samples per pixel at the image's bit depth, packed
 * with no wasted bits between pixels (§7.2).
 *
 * @property code the byte written in IHDR (Table 9).
 * @property channels how many samples one pixel carries, in the order Table 12
 *   gives them: greyscale, or R then G then B, followed by alpha where there is
 *   an alpha channel.
 * @property allowedBitDepths the bit depths Table 12 admits for this colour
 *   type. "Bit depth restrictions for each color type are imposed to simplify
 *   implementations and to prohibit combinations that do not compress well."
 */
public enum class PngColourType(
    public val code: Int,
    public val channels: Int,
    public val allowedBitDepths: Set<Int>,
) {
    /** "Each pixel is a greyscale sample" (Table 12). */
    GREYSCALE(0, 1, setOf(1, 2, 4, 8, 16)),

    /** "Each pixel is an R,G,B triple". */
    TRUECOLOUR(2, 3, setOf(8, 16)),

    /** "Each pixel is a palette index; a PLTE chunk shall appear." */
    INDEXED(3, 1, setOf(1, 2, 4, 8)),

    /** "Each pixel is a greyscale sample followed by an alpha sample." */
    GREYSCALE_ALPHA(4, 2, setOf(8, 16)),

    /** "Each pixel is an R,G,B triple followed by an alpha sample." */
    TRUECOLOUR_ALPHA(6, 4, setOf(8, 16)),
    ;

    /**
     * Whether the image array itself carries alpha — the first of the four ways
     * §6.2 lists of representing transparency, and the only one that is part of
     * a pixel. The others are a tRNS chunk, and there being no transparency at
     * all.
     */
    public val hasAlpha: Boolean get() = code and ALPHA_USED != 0

    /** Whether pixels are palette indices rather than colour values (§6.1, "palette used"). */
    public val isIndexed: Boolean get() = code and PALETTE_USED != 0

    public companion object {
        /** The colour type [code] denotes, or null if the byte is none of the five of Table 9. */
        public fun forCode(code: Int): PngColourType? = entries.firstOrNull { it.code == code }

        /** The 1 of "1 (palette used)" (§6.1). */
        private const val PALETTE_USED: Int = 1

        /** The 4 of "4 (alpha used)" (§6.1). */
        private const val ALPHA_USED: Int = 4
    }
}
