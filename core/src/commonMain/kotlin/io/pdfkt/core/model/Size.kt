/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.model

// ISO 32000-2:2020 §7.7.3.3 "Page objects" (Table 31, MediaBox),
// §8.3.2.3 "User space"

/**
 * How wide and how tall something is, with no statement about where it is — a
 * page size, the extent of an image, the space a block asked for.
 *
 * Part of the vocabulary `:core` owns and the DSL and the engine share.
 *
 * @throws IllegalArgumentException if either dimension is negative. §7.9.5
 *   allows a *rectangle* to be degenerate — "NOTE Rectangles can have a width of
 *   zero or height of zero" — so zero is admitted; a negative extent is not a
 *   size but a mirrored one, and mirroring is a transformation (§8.3.3), not a
 *   property of a page.
 */
public data class Size(public val width: Pt, public val height: Pt) {
    init {
        require(width >= Pt.ZERO && height >= Pt.ZERO) {
            "a size is not negative: got ${width}x$height"
        }
    }

    /** This size placed with its lower-left corner at the origin of user space. */
    public fun toRect(): Rect = Rect(Pt.ZERO, Pt.ZERO, width, height)

    /** The same size with the two dimensions exchanged — a page turned on its side. */
    public fun rotated(): Size = Size(height, width)

    public companion object {
        /**
         * ISO 216 A4, 210 mm × 297 mm, in the 1 ⁄ 72 inch units of §8.3.2.3 —
         * converted rather than written out, so the page is the paper and not a
         * rounding of it.
         */
        public val A4: Size = ofMillimetres(210.0f, 297.0f)

        /** ANSI A ("US Letter"), 8.5 in × 11 in, which is exactly 612 × 792 units. */
        public val LETTER: Size = Size(Pt(612.0f), Pt(792.0f))

        /**
         * A size given the way paper is measured outside the United States, in
         * the units a page is written in: "the default value of 1 ⁄ 72 inch"
         * (§8.3.2.3), and 25.4 mm to the inch.
         */
        public fun ofMillimetres(width: Float, height: Float): Size = Size(
            Pt(width / MILLIMETRES_PER_INCH * UNITS_PER_INCH),
            Pt(height / MILLIMETRES_PER_INCH * UNITS_PER_INCH),
        )

        private const val MILLIMETRES_PER_INCH: Float = 25.4f

        private const val UNITS_PER_INCH: Float = 72.0f
    }
}
