/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.api

import io.pdfkt.core.model.Pt
import io.pdfkt.core.model.Size

// No clause applies: the DSL is this library's own design. The paper is ISO 216 for the A series,
// and the units are the 1 ⁄ 72 inch
// of ISO 32000-2:2020 §8.3.2.3 "User space" — both of which io.pdfkt.core.model.Size
// already answers. This type is the name a caller writes, and nothing more.

/**
 * The paper a document is set on — `pageSize = PageSize.A4`.
 *
 * A DSL-surface type: the engine speaks [Size], and these are the four sheets worth a name of their own plus the
 * escape hatch. The A-series dimensions are converted from ISO 216 millimetres
 * rather than written out rounded, so the page is the paper and not an
 * approximation of it.
 */
public sealed interface PageSize {
    /** This paper as the size the engine is given. */
    public fun toSize(): Size

    /** A4, 210 mm × 297 mm — 595.276 × 841.890 units. */
    public data object A4 : PageSize {
        override fun toSize(): Size = Size.A4
    }

    /** A5, 148 mm × 210 mm — 419.528 × 595.276 units. */
    public data object A5 : PageSize {
        override fun toSize(): Size = Size.ofMillimetres(A5_WIDTH_MM, A5_HEIGHT_MM)
    }

    /** ANSI A ("US Letter"), 8.5 in × 11 in — exactly 612 × 792 units. */
    public data object Letter : PageSize {
        override fun toSize(): Size = Size.LETTER
    }

    /** US Legal, 8.5 in × 14 in — exactly 612 × 1008 units. */
    public data object Legal : PageSize {
        override fun toSize(): Size = Size(Pt(LEGAL_WIDTH), Pt(LEGAL_HEIGHT))
    }

    /** Any other sheet, stated in the units of §8.3.2.3. */
    public data class Custom(public val width: Pt, public val height: Pt) : PageSize {
        override fun toSize(): Size = Size(width, height)
    }
}

private const val A5_WIDTH_MM: Float = 148.0f
private const val A5_HEIGHT_MM: Float = 210.0f

private const val LEGAL_WIDTH: Float = 612.0f
private const val LEGAL_HEIGHT: Float = 1008.0f
