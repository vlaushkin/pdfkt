/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.api

// No clause applies: interop sugar is this library's own design.

/**
 * [InlineStyleScope.fontSize], for a caller on the Objective-C bridge.
 *
 * A nullable [Pt] crosses that bridge as an opaque box Swift cannot
 * construct, so the property is unassignable from there; a `Double` of
 * points is not. Kotlin callers have no reason to prefer this over
 * `fontSize = 12.pt`.
 */
public fun InlineStyleScope.fontSize(points: Double) {
    fontSize = points.pt
}

/**
 * [InlineStyleScope.color], for the same caller: a nullable [Rgb] is a box
 * Swift cannot fill, an `0xRRGGBB` integer is not.
 */
public fun InlineStyleScope.color(rgb: Int) {
    color = Rgb.of(rgb)
}

/**
 * [BlockSink.paragraph] with its indents and spacing, for a caller on the
 * Objective-C bridge: a nullable [Pt] is a box Swift cannot fill, a nullable
 * `Double` of points arrives as a `KotlinDouble` it can. Null spacing is the
 * theme's, as it is in the Kotlin form.
 */
public fun BlockSink.spacedParagraph(
    align: TextAlign,
    indentLeft: Double,
    indentRight: Double,
    spacingBefore: Double?,
    spacingAfter: Double?,
    block: InlineScope.() -> Unit,
) {
    paragraph(
        align = align,
        indentLeft = indentLeft.pt,
        indentRight = indentRight.pt,
        spacingBefore = spacingBefore?.pt,
        spacingAfter = spacingAfter?.pt,
        block = block,
    )
}

/**
 * A [Theme] that sets the whole document in [family] — body text and every
 * level of heading — at [fontSize], [lineHeight] times the size apart, in
 * [textColor] (`0xRRGGBB`), and is otherwise [Theme.Default].
 *
 * It is here for the Objective-C bridge, which has no default arguments and so
 * cannot reach the data class's constructor without naming all two dozen of
 * them; a Kotlin caller writes `Theme(bodyFamily = ...)` instead.
 */
public fun themeOf(family: FontFamily, fontSize: Double, lineHeight: Double, textColor: Int): Theme = Theme(
    bodyFamily = family,
    bodyFontSize = fontSize.pt,
    lineHeight = lineHeight.toFloat(),
    textColor = Rgb.of(textColor),
    headingFamilies = (HeadingBlock.MIN_LEVEL..HeadingBlock.MAX_LEVEL).associateWith { family },
)
