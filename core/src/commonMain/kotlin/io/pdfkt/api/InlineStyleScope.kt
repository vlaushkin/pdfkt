/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.api

import io.pdfkt.core.block.InlineRun
import io.pdfkt.core.block.StyledRun
import io.pdfkt.core.model.Pt
import io.pdfkt.core.model.Rgb

// No clause applies: the DSL is this library's own design. What these properties describe is
// io.pdfkt.core.block.StyledRun, and what a
// bold run then means is the engine's resolve stage.

/**
 * How one run is set, written as properties:
 *
 * ```kotlin
 * paragraph {
 *     text("Report: ") { bold = true; color = Rgb.of(0xCC0000) }
 *     text("plain tail")
 * }
 * ```
 *
 * This is the primary spelling of inline style, and
 * the convention it follows is the one the paired library uses: a setter that
 * takes a single value is a bare `var`, a setter that needs more than one value
 * is a function. Everything here takes one value, so everything here is a `var`.
 *
 * The properties are [StyledRun]'s, one for one and named the same — a caller
 * who reads the blocks back finds what they wrote. There is deliberately no font
 * family: `StyledRun` carries no such field,
 * and a property that quietly did nothing would be worse than its absence.
 *
 * Like the fields it mirrors, every property is an *override*: `false` and
 * `null` leave the surrounding style alone, so a run inside a `bold { }` is
 * still bold.
 */
@PdfktDsl
public class InlineStyleScope internal constructor() {

    /** Set in the family's bold face. */
    public var bold: Boolean = false

    /** Set in the family's italic face. */
    public var italic: Boolean = false

    /** Set in the theme's monospaced font, at the theme's code size. */
    public var code: Boolean = false

    /** A rule under the text, drawn to the run's width. */
    public var underline: Boolean = false

    /** A rule through the text. */
    public var strike: Boolean = false

    /** The colour of the glyphs, or null to inherit. */
    public var color: Rgb? = null

    /** The size, or null to inherit. Overrides the size [code] would impose. */
    public var fontSize: Pt? = null

    /** [children], set the way these properties say. */
    internal fun styleOf(children: List<InlineRun>): StyledRun = StyledRun(
        children = children,
        bold = bold,
        italic = italic,
        code = code,
        underline = underline,
        strike = strike,
        color = color,
        fontSize = fontSize,
    )
}
