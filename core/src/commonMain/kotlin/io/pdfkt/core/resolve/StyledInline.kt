/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.resolve

import io.pdfkt.core.font.FontRef
import io.pdfkt.core.model.Pt
import io.pdfkt.core.model.Rgb

// No clause applies: L4 is this library's own design.

/**
 * Inline content with the theme already applied, and nothing decided about
 * where it goes.
 *
 * What the resolve stage produces and the line breaker consumes: "`resolve`
 * turns inline runs into styled runs and splits them by renderability … The line
 * breaker then consumes styled runs against the column width"
 */
public sealed interface StyledInline

/**
 * A stretch of text with one font, one size, one colour and one set of
 * decorations — everything a [io.pdfkt.core.plan.PlacedRun] needs except where
 * it sits and how wide it is, which is the line breaker's answer and not this
 * stage's.
 *
 * The splitting rule is renderability: a run ends where the next character would
 * have to be drawn differently — a change of font, size, colour, decoration or
 * link. The colour-emoji policy needs no rule of its own:
 * an emoji drawn from a colour font is a change of font, and falls out of this
 * one. Text that would be drawn identically is *not*
 * split: two runs that survive an emoji being stripped out from between them
 * come back as one run, because that is what they are.
 *
 * @property text the characters, after the emoji policy has had its say.
 * @property font the font this text is drawn with, chosen from the family the
 *   theme names and the bold and italic flags of the run it came from.
 * @property size the size the text is drawn at.
 * @property color the colour the glyphs are filled with.
 * @property underline whether a rule is drawn under the run.
 * @property strike whether a rule is drawn through it.
 * @property link the URI this run links to, or null. The box it lands in
 *   becomes an annotation when the page is planned.
 */
public data class StyledTextRun(
    public val text: String,
    public val font: FontRef,
    public val size: Pt,
    public val color: Rgb,
    public val underline: Boolean = false,
    public val strike: Boolean = false,
    public val link: String? = null,
) : StyledInline

/**
 * A break in the flow of text, kept as its own thing rather than as a character
 * in a run — a newline is not something a font draws.
 *
 * @property hard start a new line here whatever the column width. A soft break
 *   is only an opportunity, and the line breaker takes it or leaves it exactly
 *   as it does the opportunity a space offers.
 */
public data class StyledBreak(public val hard: Boolean) : StyledInline
