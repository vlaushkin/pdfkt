/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.api

import io.pdfkt.core.block.ImageFit
import kotlinx.io.files.Path

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
    direction: TextDirection,
    block: InlineScope.() -> Unit,
) {
    paragraph(
        align = align,
        indentLeft = indentLeft.pt,
        indentRight = indentRight.pt,
        spacingBefore = spacingBefore?.pt,
        spacingAfter = spacingAfter?.pt,
        direction = direction,
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

/**
 * [DocumentScope.image] from the file at [path], for a caller on the
 * Objective-C bridge, which has no `kotlinx.io.files.Path` to hand over: a JPEG
 * is copied into the PDF when the document is written, and not held until then.
 */
public fun DocumentScope.imageFile(path: String, fit: ImageFit, rotationDegrees: Int) {
    image(Path(path), fit, rotationDegrees)
}

/** [SectionScope.image] from the file at [path], for the same caller. */
public fun SectionScope.imageFile(path: String, fit: ImageFit, rotationDegrees: Int) {
    image(Path(path), fit, rotationDegrees)
}

/**
 * [Document.writeTo] the file at [path], for the same caller: the PDF goes to
 * the file as it is rendered, and never whole through a `Data`.
 */
@Throws(Exception::class)
public fun Document.writeToFile(path: String) {
    writeTo(Path(path))
}

/**
 * This theme with [policy] for emoji, for a caller on the Objective-C bridge,
 * where a data class's `copy` arrives with every parameter and no defaults.
 */
public fun Theme.withEmojiPolicy(policy: EmojiPolicy): Theme = copy(emojiPolicy = policy)
