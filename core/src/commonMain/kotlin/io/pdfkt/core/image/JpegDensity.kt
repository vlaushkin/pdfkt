/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.image

// ITU-T T.871 (05/2011) | ISO/IEC 10918-5, the JFIF APP0 marker segment
// (the fields Xdensity, Ydensity and the units they are counted in)

/**
 * What one pixel of a JPEG image measures, as its JFIF APP0 segment states it.
 *
 * JFIF carries two numbers and the unit they are counted in. When the unit is
 * [JpegDensityUnit.ASPECT_RATIO] the numbers are not a physical size at all —
 * they are the ratio of the horizontal to the vertical extent of a pixel, and
 * the usual 1:1 says only that the pixels are square. That is why this type
 * keeps the unit instead of normalising everything to dots per inch: two thirds
 * of the values it can carry cannot be converted into one.
 *
 * A JPEG says nothing about how large it should be drawn on a page. Placement
 * is the layout engine's decision; the density is
 * an input to it — an image of 600 dots per inch asks for a sixth of the width
 * that the same pixels at 100 dots per inch do — and never a decision made here.
 *
 * @property unit what [horizontal] and [vertical] are counted in.
 * @property horizontal Xdensity, the horizontal pixel density; never zero.
 * @property vertical Ydensity, the vertical pixel density; never zero.
 */
public class JpegDensity internal constructor(
    public val unit: JpegDensityUnit,
    public val horizontal: Int,
    public val vertical: Int,
) {
    override fun toString(): String = when (unit) {
        JpegDensityUnit.ASPECT_RATIO -> "$horizontal:$vertical pixel aspect ratio"
        JpegDensityUnit.DOTS_PER_INCH -> "$horizontal×$vertical dots per inch"
        JpegDensityUnit.DOTS_PER_CENTIMETRE -> "$horizontal×$vertical dots per centimetre"
    }

    override fun equals(other: Any?): Boolean = other is JpegDensity &&
        other.unit == unit &&
        other.horizontal == horizontal &&
        other.vertical == vertical

    override fun hashCode(): Int = (unit.ordinal * 31 + horizontal) * 31 + vertical
}

/**
 * The unit of a [JpegDensity] — the JFIF APP0 `units` field, whose three
 * defined values are the three entries here.
 *
 * @property code the value of the field this entry is written as.
 */
public enum class JpegDensityUnit(public val code: Int) {
    /** 0 — no units: the two densities are a pixel aspect ratio and nothing more. */
    ASPECT_RATIO(0),

    /** 1 — dots per inch. */
    DOTS_PER_INCH(1),

    /** 2 — dots per centimetre. */
    DOTS_PER_CENTIMETRE(2),

    ;

    internal companion object {
        /** The unit [code] denotes, or null if the field carries no defined value. */
        fun forCode(code: Int): JpegDensityUnit? = entries.firstOrNull { it.code == code }
    }
}
