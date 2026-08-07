/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.model

// ISO 32000-2:2020 §8.3.2.3 "User space"

/**
 * How much is taken off each side of a rectangle: page margins, the padding of a
 * table cell, the band a header is given.
 *
 * The four sides are named the way [Rect] names its edges — left, bottom, right,
 * top — and not the way a stylesheet names them, because this library works in
 * PDF coordinates throughout: the origin is at the lower left and y grows upwards
 * (§8.3.2.3, "the positive x axis extends horizontally to the right and the
 * positive y axis vertically upward"). One convention, stated once, is what keeps
 * a margin from being applied upside down two layers away.
 *
 * @throws IllegalArgumentException if any side is negative. An inset that grows
 *   a rectangle is not a margin; a caller who wants one has a different
 *   rectangle in mind and should say so.
 */
public data class Insets(public val left: Pt, public val bottom: Pt, public val right: Pt, public val top: Pt) {
    init {
        require(left >= Pt.ZERO && bottom >= Pt.ZERO && right >= Pt.ZERO && top >= Pt.ZERO) {
            "an inset is not negative: got left=$left bottom=$bottom right=$right top=$top"
        }
    }

    /** What the two horizontal sides take together. */
    public val horizontal: Pt get() = left + right

    /** What the two vertical sides take together. */
    public val vertical: Pt get() = bottom + top

    public companion object {
        /** The same amount on all four sides — the usual page margin. */
        public fun uniform(amount: Pt): Insets = Insets(amount, amount, amount, amount)
    }
}
