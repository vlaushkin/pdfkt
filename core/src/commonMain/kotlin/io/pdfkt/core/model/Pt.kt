/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.model

import kotlin.jvm.JvmInline

// ISO 32000-2:2020 §8.3.2.3 "User space"

/**
 * A length on a page, in the unit the page is measured in: "The length of a unit
 * along both the x and y axes is set by the UserUnit entry … If that entry is not
 * present or supported, the default value of 1 ⁄ 72 inch is used" (§8.3.2.3).
 *
 * The name is the one the printing industry uses for that length, with the
 * caveat the clause itself attaches to it:
 *
 * > NOTE 2 The default for the size of the unit in default user space (1 ⁄ 72
 * > inch) is approximately the same as a point, a unit widely used in the
 * > printing industry. It is not exactly the same, however; there is no
 * > universal definition of a point.
 *
 * So a [Pt] is a default user space unit, and it is called a point because that
 * is what everyone calls it. This library writes no `UserUnit`, which is what
 * makes the two the same thing here.
 *
 * ## Why a type and not a `Float`
 *
 * Every number in the layout is one of three things — a length, a factor, or an
 * index — and only the first is a length. Wrapping it costs nothing at runtime
 * (`@JvmInline`, so a `Pt` in a field is a `float` in the class file) and buys
 * the one distinction that geometry bugs live in: a width added to a size, a
 * margin subtracted from a scale factor. The arithmetic that is meaningful for
 * lengths is here; the arithmetic that is not — `Pt * Pt` is an area and nothing
 * on a page is an area — is absent on purpose.
 *
 * `Float` and not `Double` because that is what the font metrics answer in
 * (`FontMetrics`) and what the plan carries. The conversion to
 * the `Double` the content stream operators take happens once, at the emitter.
 *
 * There is no validation in this class. A NaN or an infinity would be a
 * coordinate that cannot be written, and it is refused where it would be
 * written — `PdfReal` rejects both at construction (§7.3.3) — rather than at
 * every intermediate sum a layout takes.
 */
@JvmInline
public value class Pt(public val value: Float) : Comparable<Pt> {
    public operator fun plus(other: Pt): Pt = Pt(value + other.value)

    public operator fun minus(other: Pt): Pt = Pt(value - other.value)

    public operator fun unaryMinus(): Pt = Pt(-value)

    /** Scaling a length by a factor — a line height of one and a half lines. */
    public operator fun times(factor: Float): Pt = Pt(value * factor)

    /** Dividing a length by a factor — half a width, a third of a column. */
    public operator fun div(divisor: Float): Pt = Pt(value / divisor)

    /**
     * The ratio of two lengths, which is a factor and not a length: how many
     * times a column width fits a page, how much an image has to be scaled.
     */
    public operator fun div(other: Pt): Float = value / other.value

    override fun compareTo(other: Pt): Int = value.compareTo(other.value)

    override fun toString(): String = "${value}pt"

    public companion object {
        public val ZERO: Pt = Pt(0.0f)
    }
}

/** A whole number of points, so that a margin reads `36.pt`. */
public val Int.pt: Pt get() = Pt(toFloat())

/** The same for a fractional length — `841.89.pt` is the height of A4. */
public val Double.pt: Pt get() = Pt(toFloat())
