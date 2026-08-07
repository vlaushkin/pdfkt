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
import io.pdfkt.core.model.Rgb

// ISO 32000-2:2020 §8.4.3.2 "Line width", §8.5.3 "Path-painting operators"

/**
 * How the outline of a [PlacedItem.Rect] is drawn: a colour and a width.
 *
 * A border is a separate description attached where it is drawn
 * rather than part of the rectangle: a geometry
 * type that also knows what a border looks like is a type two layers have to
 * agree about.
 *
 * The dash patterns, caps and joins of §8.4.3 are deliberately absent until a
 * document needs them: they are further graphics state parameters, each one a
 * field here and an operator in the emitter, and none of them has a caller yet.
 *
 * @property width the line width `w` sets, "a non-negative number expressed in
 *   user space units" (§8.4.3.2). Zero is admitted because the clause defines it
 *   — "the thinnest line that can be rendered at device resolution: 1 device
 *   pixel wide" — while advising against it; that advice is the caller's to
 *   take.
 */
public data class Stroke(public val color: Rgb, public val width: Pt) {
    init {
        require(width >= Pt.ZERO) {
            "a line width shall be a non-negative number expressed in user space units; " +
                "$width is not (ISO 32000-2:2020 §8.4.3.2)"
        }
    }
}
