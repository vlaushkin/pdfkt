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
