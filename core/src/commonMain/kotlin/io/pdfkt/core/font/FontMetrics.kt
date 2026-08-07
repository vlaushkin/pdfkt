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
 * What a font tells the layout engine, and the whole of it
 *
 * Everything is answered in **text space** — the space the layout works in and
 * the space page coordinates are expressed in — for text drawn at [size]. Font
 * programs measure in glyph space, where "the units of glyph space are
 * one-thousandth of a unit of text space" (§9.2.4); that conversion happens
 * behind this interface, so a caller never divides by 1000 and never has to know
 * how the font stored its numbers.
 *
 * Sizes and results are `Float`, matching the geometry of the page plan.
 * The arithmetic behind them is done in
 * `Double` and rounded once, at the boundary: §9.2.4 NOTE 3 warns that TrueType
 * stores widths "in units of 1024 or 2048 to an Em" against the 1000 of a PDF
 * `Widths` array, and recommends real numbers over integers where "precise
 * character positioning is required".
 *
 * ## What it does not do
 *
 * It does not substitute. A character the font cannot render raises, naming the
 * character and what would fix it, and never widens into a blank or a question
 * mark — the "no silent loss" policy: silent substitution is how documents
 * come out empty with every test green.
 *
 * An implementation is obtained from a [FontCatalog], which resolves each
 * [FontRef] once per document.
 */
public interface FontMetrics {
    /**
     * How far the text position moves when [text] is drawn at [size] — the sum
     * of the glyphs' displacements, which is what §9.2.4 defines a width to be:
     * "it is the distance the current text position shall move (by translating
     * text space) when the glyph is painted".
     *
     * It is not the width of the ink: "NOTE 1 The width is distinct from the
     * dimensions of the glyph outline." A line of text is broken against this
     * number because this number is where the next glyph starts.
     *
     * Kerning and the adjustments of the `TJ` operator are not applied — those
     * are decisions of whoever draws, and §9.2.4 NOTE 4 puts them beside the
     * standard widths rather than inside them: "The operators for showing text
     * are designed on the assumption that glyphs are ordinarily positioned
     * according to their standard widths."
     *
     * @throws IllegalArgumentException if the font cannot render a character of
     *   [text], naming it and its position.
     */
    public fun widthOf(text: String, size: Float): Float

    /**
     * How far the glyphs of this font reach above the baseline at [size] —
     * Table 120's `Ascent`, "The maximum height above the baseline reached by
     * glyphs in this font. The height of glyphs for accented characters shall be
     * excluded." Positive.
     */
    public fun ascent(size: Float): Float

    /**
     * How far the glyphs of this font reach below the baseline at [size] —
     * Table 120's `Descent`, "The maximum depth below the baseline reached by
     * glyphs in this font. The value shall be a negative number."
     *
     * Negative, as the table requires: a caller adds it to a baseline and moves
     * down the page, without knowing which convention this library picked.
     */
    public fun descent(size: Float): Float

    /**
     * The baseline-to-baseline distance of consecutive lines set in this font at
     * [size]: how far it reaches above the baseline, plus how far below, plus
     * the spacing the font itself asks to be left between lines (Table 120's
     * `Leading`, default 0).
     *
     * It is the font's own line height. Line spacing a *theme* asks for — one
     * and a half lines, double — is a multiple of this applied in L4, where the
     * theme lives.
     */
    public fun lineHeight(size: Float): Float
}
