/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.font

// ISO 32000-2:2020 §9.2.4 "Glyph positioning and metrics",
// §9.8.1 "Font descriptors — General" (Table 120)

/**
 * A font's measurements as the font itself keeps them: in glyph space, where
 * "the units of glyph space are one-thousandth of a unit of text space" for
 * every font type this library writes (§9.2.4), and where nothing depends on the
 * size text will be drawn at.
 *
 * This is the half of [FontMetrics] that differs between the two text-font
 * paths — the AFM table of a standard font, the `hmtx` and `head`
 * tables of a supplied font program. The other half, the scaling, is [TextSpaceMetrics]
 * and is written once.
 *
 * Values are `Double` rather than integers on the advice of §9.2.4 NOTE 3:
 * a TrueType program stores advances "in units of 1024 or 2048 to an Em" and
 * reaching 1000ths of an em from there is a division that does not come out
 * even. "Using real numbers instead of integers can mitigate rounding errors
 * caused by this difference, and is recommended when precise character
 * positioning is required."
 */
internal interface GlyphSpaceMetrics {
    /**
     * The sum of the displacements of the glyphs [text] is drawn with, in glyph
     * space.
     *
     * Mapping characters to glyphs is part of this: which glyph a character
     * selects is what separates a byte encoding (§9.6.5) from a CMap (§9.7.5),
     * and both live below this method. So does refusing a character the font
     * cannot render — see [FontMetrics.widthOf].
     */
    fun advanceOf(text: String): Double

    /** Table 120's `Ascent`, in glyph space. Positive. */
    val ascent: Double

    /** Table 120's `Descent`, in glyph space. Negative. */
    val descent: Double

    /** Table 120's `Leading`, in glyph space: the gap between lines. Default 0. */
    val leading: Double
}

/**
 * [FontMetrics] over a [GlyphSpaceMetrics] — the conversion §9.2.4 describes,
 * in the one place it is written:
 *
 * > For all font types except Type 3, the units of glyph space are
 * > one-thousandth of a unit of text space.
 *
 * Text space is scaled by the font size in force (`Tf`), so a quantity of *g*
 * glyph units drawn at size *s* measures `g × s / 1000` in text space. An extent
 * of 1000 glyph units — the em — is therefore exactly the font size, which is
 * what makes a "12 point" font 12 units tall.
 *
 * Type 3 fonts are the exception the clause names: their glyph space is defined
 * by an explicit `FontMatrix` instead (§9.6.4). This library writes one Type 3
 * font, for colour emoji, and it will bring its own
 * conversion rather than bend this one.
 */
internal class TextSpaceMetrics(private val glyphSpace: GlyphSpaceMetrics) : FontMetrics {
    override fun widthOf(text: String, size: Float): Float = inTextSpace(glyphSpace.advanceOf(text), size)

    override fun ascent(size: Float): Float = inTextSpace(glyphSpace.ascent, size)

    override fun descent(size: Float): Float = inTextSpace(glyphSpace.descent, size)

    /**
     * Above the baseline plus below it plus the gap between lines. The descent is
     * negative (Table 120), so the depth of the line is subtracted rather than
     * added.
     *
     * Table 120 calls `Leading` "the spacing between baselines of consecutive
     * lines of text" and gives it a default of 0. Read literally that would make
     * the default line height zero and stack every line of every font without an
     * explicit leading on top of the last one, so the entry is read here as the
     * spacing *added* to the font's extents — the reading its default is
     * consistent with.
     */
    override fun lineHeight(size: Float): Float =
        inTextSpace(glyphSpace.ascent - glyphSpace.descent + glyphSpace.leading, size)

    private fun inTextSpace(glyphSpaceUnits: Double, size: Float): Float =
        (glyphSpaceUnits * size / GLYPH_SPACE_UNITS_PER_EM).toFloat()

    private companion object {
        /**
         * "the units of glyph space are one-thousandth of a unit of text space"
         * (§9.2.4).
         */
        const val GLYPH_SPACE_UNITS_PER_EM: Double = 1000.0
    }
}
