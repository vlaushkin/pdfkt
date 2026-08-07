/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.content

import kotlin.jvm.JvmInline

// ISO 32000-2:2020 §9.4.3 "Text-showing operators" (Table 107)

/**
 * One element of the array operand of `TJ`.
 *
 * Table 107 admits exactly two kinds and gives each its own behaviour: "Each
 * element of array shall be either a string or a number. If the element is a
 * string, this operator shall show the string. If it is a number, the operator
 * shall adjust the text position by that amount." A sealed pair of variants is
 * that sentence, so an array that is neither cannot be built and the emitter has
 * no third case to answer for.
 */
public sealed interface TextArrayElement {
    /**
     * A string element — "a sequence of character codes identifying the glyphs
     * to be painted" (§9.4.3), shown as [ContentStreamBuilder.showText] shows
     * one.
     *
     * The bytes are character codes, not characters: "With a simple font, each
     * byte of the string shall be treated as a separate character code", and
     * with a composite font "one or more consecutive bytes of the string shall
     * be treated as a single character code". Which of the two applies is a
     * property of the font the codes were produced against, and belongs to the
     * clause 9.6/9.7 layer that produced them.
     */
    public class Codes(public val codes: ByteArray) : TextArrayElement

    /**
     * A number element: "it shall adjust the text position by that amount; that
     * is, it shall translate the text matrix, Tm" (Table 107).
     *
     * @property amount the adjustment, "expressed in thousandths of a unit of
     *   text space". Table 107 states its sign convention rather than leaving it
     *   to be inferred: "This amount shall be subtracted from the current
     *   horizontal or vertical coordinate, depending on the writing mode. In the
     *   default coordinate system, a positive adjustment has the effect of
     *   moving the next glyph painted either to the left or down by the given
     *   amount."
     */
    @JvmInline
    public value class Adjustment(public val amount: Double) : TextArrayElement
}
