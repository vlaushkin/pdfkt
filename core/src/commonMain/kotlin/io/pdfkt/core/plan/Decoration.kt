/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.plan

import io.pdfkt.core.model.Pt

// No clause applies: L4 is this library's own design.

/**
 * A rule drawn along a [PlacedRun] — an underline or a strike.
 *
 * PDF has no such thing: no font entry, no text state parameter and no operator
 * draws one. A decoration is a filled rectangle the width of the run,
 * and this type is where the layout says which rectangle.
 *
 * The geometry is carried, not named. A decoration knows its [offset] and
 * [thickness] because the stage that produced it — resolve, which has the theme
 * and the font metrics — is the only stage that can compute them; the emitter
 * fills the rectangle it is given and asks nothing. The variants exist so the
 * layout and its tests can say *which* rule they mean and so a third kind
 * (an overline, a link rule of its own colour) is a variant rather than a
 * convention about numbers.
 *
 * @property offset the distance from the baseline of the run to the **bottom
 *   edge** of the rule, upwards. Negative for an underline, which sits below the
 *   baseline; positive for a strike, which crosses the glyphs.
 * @property thickness how tall the rule is. It is filled, not stroked, so this
 *   is the full height of the rectangle and not a line width — a stroked line of
 *   width *w* would straddle its path by *w* ⁄ 2 either side (§8.4.3.2) and put
 *   half the ink where the layout did not ask for it.
 */
public sealed interface Decoration {
    public val offset: Pt
    public val thickness: Pt

    /** A rule under the run: [offset] below the baseline, so negative. */
    public data class Underline(override val offset: Pt, override val thickness: Pt) : Decoration

    /** A rule across the run, at [offset] above the baseline. */
    public data class Strike(override val offset: Pt, override val thickness: Pt) : Decoration
}
