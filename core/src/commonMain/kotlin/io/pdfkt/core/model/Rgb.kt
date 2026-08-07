/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.model

// ISO 32000-2:2020 §8.6.4.3 "DeviceRGB colour space", §8.6.8 "Colour operators"

/**
 * A colour in the `DeviceRGB` space, which is what this library paints in.
 *
 * The three components are exactly the operands of the `rg` and `RG` operators
 * of Table 73: "Each operand shall be a number between 0.0 (minimum intensity)
 * and 1.0 (maximum intensity)" — so the range is the clause's and is enforced
 * here rather than at the operator, where a caller would already have computed
 * the wrong colour.
 *
 * ## Why only RGB
 *
 * §8.6.4 gives three device spaces, and a document could mix them. One space in
 * the vocabulary means the
 * plan carries one kind of colour and the emitter has one operator to write.
 * Grey is [gray], which is the RGB colour with three equal components — a value
 * `DeviceGray` would render identically per §8.6.4.2's conversion. When a
 * document that needs `DeviceCMYK` appears, this becomes a sealed type with a
 * variant per space and the emitter gains a `when`; nothing above it changes,
 * which is why the plan holds a colour type and not three numbers.
 *
 * @throws IllegalArgumentException if a component is outside 0.0 … 1.0, or is
 *   not a number.
 */
public data class Rgb(public val red: Float, public val green: Float, public val blue: Float) {
    init {
        require(red.isComponent() && green.isComponent() && blue.isComponent()) {
            "each operand of rg shall be a number between 0.0 and 1.0 " +
                "(ISO 32000-2:2020 §8.6.8, Table 73): got ($red, $green, $blue)"
        }
    }

    public companion object {
        /**
         * The colour every page starts in: "In a DeviceGray, DeviceRGB, CalGray,
         * or CalRGB colour space, the initial colour shall have all components
         * equal to 0.0" (§8.6.8).
         */
        public val BLACK: Rgb = Rgb(0.0f, 0.0f, 0.0f)

        public val WHITE: Rgb = Rgb(1.0f, 1.0f, 1.0f)

        /** A grey of the given intensity — 0.0 is black, 1.0 is white. */
        public fun gray(level: Float): Rgb = Rgb(level, level, level)

        /**
         * The colour written the way it is written everywhere but in a PDF: three
         * bytes, `0xRRGGBB`, each scaled to the 0.0 … 1.0 the operator takes.
         */
        public fun of(rgb: Int): Rgb = Rgb(
            red = ((rgb shr 16) and BYTE) / MAX_BYTE,
            green = ((rgb shr 8) and BYTE) / MAX_BYTE,
            blue = (rgb and BYTE) / MAX_BYTE,
        )

        private const val BYTE: Int = 0xFF

        private const val MAX_BYTE: Float = 255.0f

        private fun Float.isComponent(): Boolean = !isNaN() && this >= 0.0f && this <= 1.0f
    }
}
