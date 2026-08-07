/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.fragment

import io.pdfkt.core.model.Pt
import io.pdfkt.core.plan.PlacedItem
import io.pdfkt.core.plan.PlacedRun

// No clause applies: L4 is this library's own design.

/**
 * One line of text, measured and cut, and not yet anywhere on a page.
 *
 * What the line breaker answers with and what the fragmenter turns into a
 * [PlacedItem.Text]: the runs are final — "Line breaking happens *before* runs
 * are placed, so a `PlacedRun` never spans lines" — and the only thing still missing is the baseline they sit on,
 * which is the vertical cursor's answer and not this stage's.
 *
 * The three measurements are here because the stage that could compute them has
 * already measured the text once. A fragmenter that had to ask a font how tall a
 * line is would be measuring a second time, with a second chance to disagree
 *
 * @property runs the text of the line, left to right, each at its own
 *   [PlacedRun.xOffset] from the line's left edge. A run that the paragraph
 *   indented starts at the indent rather than at zero.
 * @property width how far the line reaches from its left edge, **without** the
 *   white space hanging off its end (see [LineBreaker] — a space at a break sits
 *   in the margin). This is the number an alignment shifts by: a centred line
 *   moves by half the difference between the column and this.
 * @property ascent how far the tallest run of the line reaches above the
 *   baseline (Table 120's `Ascent`, at the size the run is drawn at). Positive.
 * @property descent how far the deepest run reaches below it. Negative, as
 *   Table 120 has it, so that `baseline + descent` is the bottom of the line
 *   whatever the font.
 * @property height the baseline-to-baseline distance to the next line: the
 *   tallest run's own line height, times the multiple the theme asked for. It is
 *   not `ascent - descent`: a theme setting text one and a half lines apart is
 *   asking for exactly this number to grow and for the glyphs not to.
 * @property links the stretches of this line that carry a link target, already
 *   joined across the runs that share one. The fragmenter cuts each into
 *   a [PlacedItem.Link] once it knows the baseline.
 */
public data class TextLine(
    public val runs: List<PlacedRun>,
    public val width: Pt,
    public val ascent: Pt,
    public val descent: Pt,
    public val height: Pt,
    public val links: List<LinkSpan> = emptyList(),
) {
    /**
     * The characters of this line, in reading order.
     *
     * Every character the line breaker was given comes back out of one line or
     * another: joining the [text] of every line of a paragraph reproduces the
     * text that went in, except for the soft hyphens — which are not characters
     * a font draws but marks saying where a hyphen may appear — and plus the
     * hyphens those marks turned into where a line actually broke.
     */
    public val text: String get() = runs.joinToString(separator = "") { run -> run.text }
}

/**
 * A stretch of one line that links somewhere: which target, and where it sits
 * along the line.
 *
 * A link is not a property of a run — `PlacedRun` carries none — because in PDF
 * it is not a mark on the page at all but an annotation over a region of it
 * (§12.5.6.5, and [PlacedItem.Link]). So the line breaker reports the region in
 * the only terms it knows, along the line, and the fragmenter gives it a
 * baseline and a height.
 *
 * Adjacent runs pointing at the same target are one span: a link whose middle
 * word is bold is one link, and two annotations over the two halves would be a
 * seam the caller never asked for. A link broken across two lines is two spans,
 * one on each line, which is what a reader needs in order to make both halves
 * clickable.
 *
 * @property target the URI, as the caller wrote it.
 * @property xOffset where the span starts, from the line's left edge.
 * @property width how far it reaches.
 */
public data class LinkSpan(public val target: String, public val xOffset: Pt, public val width: Pt)
