/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.api

import io.pdfkt.core.block.BreakRun
import io.pdfkt.core.block.InlineRun
import io.pdfkt.core.block.LinkRun
import io.pdfkt.core.block.StyledRun
import io.pdfkt.core.block.TextRun
import io.pdfkt.core.model.Pt
import io.pdfkt.core.model.Rgb

// No clause applies: the DSL is this library's own design. What these builders produce is
// io.pdfkt.core.block.InlineRun, and what a
// bold run then means is the engine's resolve stage.

/**
 * The inline-run builders shared by every scope that accepts inline content — a
 * paragraph or heading ([InlineScope]), a list item, a blockquote, a table cell.
 *
 * ```kotlin
 * paragraph {
 *     +"This is the "; bold("Q4 2026"); +" summary. See "
 *     link("https://example.com", "details")
 *     +"."
 * }
 * ```
 *
 * The styling builders come in two shapes: one taking the text directly, one
 * taking a nested block so styles can be combined — `bold { italic("both") }`
 * renders bold *and* italic, because [StyledRun] is a tree whose properties are
 * overrides and nesting composes them.
 *
 * Style can also be written as properties — `text("x") { bold = true }`, which
 * is the primary spelling and says several things at
 * once without nesting. The two mix freely inside one paragraph: both produce
 * the same [StyledRun].
 */
@PdfktDsl
public interface InlineRunSink {

    /** Append an already-built inline run — the single sink every other builder routes through. */
    public fun add(run: InlineRun)

    /** Append plain text: `+"Hello"`. */
    public operator fun String.unaryPlus() {
        add(TextRun(this))
    }

    /** Append plain text. */
    public fun text(value: String) {
        add(TextRun(value))
    }

    /**
     * Append text set the way [configure] says — the primary spelling of inline
     * style.
     *
     * ```kotlin
     * text("Report: ") { bold = true; color = Rgb.of(0xCC0000) }
     * ```
     *
     * The properties are [InlineStyleScope]'s, which are [StyledRun]'s. The run
     * this produces is a [StyledRun] whatever the block did — including nothing,
     * so that `text(value) { if (isTotal) bold = true }` builds the same shape of
     * tree on either branch. A [StyledRun] that overrides nothing is a run that
     * inherits everything, which is what "unstyled" means here.
     */
    public fun text(value: String, configure: InlineStyleScope.() -> Unit) {
        val style = InlineStyleScope()
        style.configure()
        add(style.styleOf(listOf(TextRun(value))))
    }

    /** Append bold text — CommonMark `**text**`. */
    public fun bold(text: String) {
        add(StyledRun(listOf(TextRun(text)), bold = true))
    }

    /** Append bold content built from nested runs. */
    public fun bold(block: InlineScope.() -> Unit) {
        add(StyledRun(collectInlineRuns(block), bold = true))
    }

    /** Append italic text — CommonMark `*text*`. */
    public fun italic(text: String) {
        add(StyledRun(listOf(TextRun(text)), italic = true))
    }

    /** Append italic content built from nested runs. */
    public fun italic(block: InlineScope.() -> Unit) {
        add(StyledRun(collectInlineRuns(block), italic = true))
    }

    /** Append inline code — CommonMark `` `text` `` — typeset in the theme's monospaced family. */
    public fun code(text: String) {
        add(StyledRun(listOf(TextRun(text)), code = true))
    }

    /** Append inline code built from nested runs. */
    public fun code(block: InlineScope.() -> Unit) {
        add(StyledRun(collectInlineRuns(block), code = true))
    }

    /** Append struck-through text — GFM `~~text~~`. */
    public fun strike(text: String) {
        add(StyledRun(listOf(TextRun(text)), strike = true))
    }

    /** Append struck-through content built from nested runs. */
    public fun strike(block: InlineScope.() -> Unit) {
        add(StyledRun(collectInlineRuns(block), strike = true))
    }

    /** Append underlined text. */
    public fun underline(text: String) {
        add(StyledRun(listOf(TextRun(text)), underline = true))
    }

    /** Append underlined content built from nested runs. */
    public fun underline(block: InlineScope.() -> Unit) {
        add(StyledRun(collectInlineRuns(block), underline = true))
    }

    /** Append text in [color]. */
    public fun color(color: Rgb, text: String) {
        add(StyledRun(listOf(TextRun(text)), color = color))
    }

    /** Append content in [color] built from nested runs. */
    public fun color(color: Rgb, block: InlineScope.() -> Unit) {
        add(StyledRun(collectInlineRuns(block), color = color))
    }

    /**
     * Append content combining several styles at once.
     *
     * ```kotlin
     * styled(bold = true, color = Rgb.of(0xCC0000)) { +"warning" }
     * ```
     */
    public fun styled(
        bold: Boolean = false,
        italic: Boolean = false,
        code: Boolean = false,
        strike: Boolean = false,
        underline: Boolean = false,
        color: Rgb? = null,
        fontSize: Pt? = null,
        block: InlineScope.() -> Unit,
    ) {
        add(
            StyledRun(
                children = collectInlineRuns(block),
                bold = bold,
                italic = italic,
                code = code,
                strike = strike,
                underline = underline,
                color = color,
                fontSize = fontSize,
            ),
        )
    }

    /**
     * Append a link — CommonMark `[text](href)`. The text is drawn in the theme's
     * link colour and the page carries an annotation with a URI action over it
     */
    public fun link(href: String, text: String) {
        add(LinkRun(href, listOf(TextRun(text))))
    }

    /** Append a link whose text is built from nested runs. */
    public fun link(href: String, block: InlineScope.() -> Unit) {
        add(LinkRun(href, collectInlineRuns(block)))
    }

    /**
     * Append a line break.
     *
     * @param soft `true` for CommonMark's softbreak — an opportunity to break,
     *   with the standing of a space; `false` (the default) for a hard break that
     *   always starts a new line, which is CommonMark's `\` or two trailing
     *   spaces.
     */
    public fun br(soft: Boolean = false) {
        add(BreakRun(hard = !soft))
    }
}

/** Runs a nested inline block and returns what it collected. */
private fun collectInlineRuns(block: InlineScope.() -> Unit): List<InlineRun> {
    val scope = InlineScope()
    scope.block()
    return scope.runs()
}
