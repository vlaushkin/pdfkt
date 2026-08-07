/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.api

import io.pdfkt.core.model.Insets
import io.pdfkt.core.model.Pt

// No clause applies: the DSL is this library's own design. The engine's own vocabulary for this is
// io.pdfkt.core.model.Insets.

/**
 * What is kept clear around the text — `margins = Margins.Fixed(top = 36.pt)`.
 *
 * A DSL-surface type over [Insets]: the sides are named in the order a caller
 * says them rather than in the order the engine keeps them, and the sealed shape
 * is where a margin that varies between pages would go if this library ever
 * grows one.
 */
public sealed interface Margins {
    /** These margins as the insets the engine is given. */
    public fun toInsets(): Insets

    /** The same margins on every page. Half an inch on all four sides by default. */
    public data class Fixed(
        public val top: Pt = DEFAULT_MARGIN,
        public val right: Pt = DEFAULT_MARGIN,
        public val bottom: Pt = DEFAULT_MARGIN,
        public val left: Pt = DEFAULT_MARGIN,
    ) : Margins {
        override fun toInsets(): Insets = Insets(left = left, bottom = bottom, right = right, top = top)
    }
}

/** Half an inch, which is what a page of running text is set in unless the caller says otherwise. */
private val DEFAULT_MARGIN: Pt = 36.pt
