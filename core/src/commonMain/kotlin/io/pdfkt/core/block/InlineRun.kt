/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.block

import io.pdfkt.core.model.Pt
import io.pdfkt.core.model.Rgb

// No clause applies: L4 is this library's own design.

/**
 * Content inside a block: text and the things that modify it.
 *
 * The variants nest, and that is what makes them a tree rather than a list of
 * spans with flags: bold inside a link inside a paragraph is written the way it
 * reads. Resolving flattens the tree, inheriting downwards — the run knows it is
 * bold, the resolve stage knows which font that means.
 *
 * There is deliberately no emoji variant. Emoji live inside ordinary text and
 * are found there by the resolve stage, which is where a Unicode property
 * question belongs; asking a caller to mark them up
 * would be asking the caller to do the library's work and to get it wrong.
 */
public sealed interface InlineRun

/** Text, as written. Whatever it holds — Latin, Cyrillic, emoji — is text here. */
public data class TextRun(public val text: String) : InlineRun

/**
 * Its [children], set differently.
 *
 * Every property is an *override*: null or false leaves the surrounding style
 * alone, so nesting composes instead of replacing. Bold inside bold is bold.
 *
 * @property bold set in the family's bold face.
 * @property italic set in the family's italic face.
 * @property code set in the theme's monospaced font, at the theme's code size —
 *   inline code, the counterpart of a [CodeBlock].
 * @property underline a rule under the text, drawn to the run's width.
 * @property strike a rule through the text.
 * @property color the colour of the glyphs, or null to inherit.
 * @property fontSize the size, or null to inherit. Overrides the size [code]
 *   would otherwise impose.
 */
public data class StyledRun(
    public val children: List<InlineRun>,
    public val bold: Boolean = false,
    public val italic: Boolean = false,
    public val code: Boolean = false,
    public val underline: Boolean = false,
    public val strike: Boolean = false,
    public val color: Rgb? = null,
    public val fontSize: Pt? = null,
) : InlineRun

/**
 * Its [children], as a link to [href].
 *
 * The theme decides what a link looks like; the plan turns the box the text
 * ended up in into an annotation with a URI action, which is what makes it
 * clickable.
 */
public data class LinkRun(public val href: String, public val children: List<InlineRun>) : InlineRun

/**
 * A break in the flow of text.
 *
 * @property hard true to always start a new line here, false for an opportunity
 *   to break — the same standing as a space, which is what a newline in the
 *   caller's source usually means.
 */
public data class BreakRun(public val hard: Boolean = true) : InlineRun
