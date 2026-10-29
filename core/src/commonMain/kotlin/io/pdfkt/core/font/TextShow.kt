/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.font

import io.pdfkt.core.content.TextArrayElement

// ISO 32000-2:2020 §9.4.3 "Text-showing operators" (TJ), §9.3.7 "Text rise",
// §14.9.4 "Replacement text" (ActualText).

/**
 * One step of drawing a run of text, as a font lays it out: what the emitter
 * writes between the run's `Tm` and the next run.
 *
 * Glyph positions are in glyph space — thousandths of the text size — so a
 * [Rise] becomes a `Ts` of [Rise.amount] × size ÷ 1000.
 */
internal sealed interface TextShow {
    /** A `TJ`: codes, and the adjustments between them that place each glyph where shaping put it. */
    class Show(val elements: List<TextArrayElement>) : TextShow

    /** A `Ts` that raises the glyphs after it — a mark set above or below its base. */
    class Rise(val amount: Double) : TextShow

    /** Opens a `Span` whose `ActualText` is [text]: what the glyphs inside it stand for. */
    class BeginActualText(val text: String) : TextShow

    data object EndActualText : TextShow
}
