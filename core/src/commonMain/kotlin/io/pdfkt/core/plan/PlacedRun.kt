/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.plan

import io.pdfkt.core.font.FontRef
import io.pdfkt.core.model.Pt
import io.pdfkt.core.model.Rgb

// No clause applies: L4 is this library's own design.

/**
 * One stretch of text sharing a font, a size and a colour, already positioned —
 * the unit inside a [PlacedItem.Text].
 *
 * A run never spans lines: "Line breaking happens *before* runs are placed, so a
 * `PlacedRun` never spans lines and emission never re-measures anything."
 *
 * That second half is why [width] is here and not computed at emit time. The
 * line breaker measured this text to decide where the line ended; measuring it
 * again in the emitter would be the same question asked twice, with two chances
 * to answer it differently — and it is the answer a decoration is drawn to and a
 * link box is cut to. So the measurement travels with the run.
 *
 * @property text what is drawn, as characters. Encoding them into the character
 *   codes a text-showing operator takes (§9.4.3) is the font's business and
 *   happens at emit time, through the same resolved font that names the `/Font`
 *   resource — so a run cannot be drawn with one font and written with another.
 * @property font which font, as the caller names it.
 * @property size the text font size `Tf` sets (Table 103), in points.
 * @property color the colour the glyphs are filled with — `rg` (Table 73).
 * @property xOffset where this run starts, relative to the origin of the
 *   [PlacedItem.Text] that holds it. The first run of a line is usually at zero;
 *   the rest are wherever the line breaker put them, which is what allows a
 *   justified or a bidi line later without changing this type.
 * @property width how far the text position moves when this run is drawn — the
 *   sum of the glyphs' displacements at [size], as `FontMetrics.widthOf`
 *   answered it (§9.2.4).
 * @property decorations rules drawn along this run — underline, strike. They are
 *   paths, not glyphs, so the emitter draws them outside the text object
 *   (§8.2, Figure 9); the plan just says where.
 * @property rightToLeft whether the run is at an odd embedding level of the
 *   Unicode Bidirectional Algorithm (UAX #9 §3.3.7): its characters are drawn
 *   from right to left, and [text] stays in the order they are read in.
 */
public data class PlacedRun(
    public val text: String,
    public val font: FontRef,
    public val size: Pt,
    public val color: Rgb,
    public val xOffset: Pt,
    public val width: Pt,
    public val decorations: List<Decoration> = emptyList(),
    public val rightToLeft: Boolean = false,
)
