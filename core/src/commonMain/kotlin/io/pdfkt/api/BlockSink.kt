/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.api

import io.pdfkt.core.block.Block
import io.pdfkt.core.block.BlockquoteBlock
import io.pdfkt.core.block.CodeBlock
import io.pdfkt.core.block.HeadingBlock
import io.pdfkt.core.block.HorizontalRuleBlock
import io.pdfkt.core.block.InlineRun
import io.pdfkt.core.block.ListBlock
import io.pdfkt.core.block.ListMarkerStyle
import io.pdfkt.core.block.ParagraphBlock
import io.pdfkt.core.block.TableBlock
import io.pdfkt.core.block.TableColumns
import io.pdfkt.core.block.TextAlign
import io.pdfkt.core.block.TextDirection
import io.pdfkt.core.block.TextRun
import io.pdfkt.core.model.Pt
import io.pdfkt.core.model.Rgb

// No clause applies: the DSL is this library's own design. This is the bridge
// that lets any block be nested inside containers, built as one interface of
// default implementations rather than as a second element model.

/**
 * The block builders shared by every container — the document itself, a
 * blockquote, a list item, a table cell.
 *
 * Any block nests inside any container, and this interface is the whole of that
 * claim: the containers differ in where their blocks end up, never in which
 * blocks they accept, so a code block inside a quotation inside a cell needs
 * nothing written for it. The engine plans all four with the same
 * function, and the DSL mirrors
 * that arrangement instead of enumerating what may go where.
 *
 * [add] is the one member an implementation writes. It is also where a container
 * that accumulates inline content flushes its implicit leading paragraph, which
 * is why every builder here routes through it rather than touching a list of its
 * own.
 *
 * The block-level code builder is named [codeBlock] and not `code`, because a
 * container is usually an [InlineRunSink] too and that interface's `code` is the
 * inline span. Two builders one letter apart, where one silently wins overload
 * resolution, is not a DSL anybody should have to debug.
 */
@PdfktDsl
public interface BlockSink {

    /** Append an already-built block — the sink every builder here routes through. */
    public fun add(block: Block)

    /**
     * Append a plain-text paragraph.
     *
     * @param indentLeft how far in from the left of the column its lines start.
     * @param indentRight how far in from the right they end — where a
     *   right-aligned line stops short of the edge.
     * @param spacingBefore the gap above it, or null for the theme's.
     * @param spacingAfter the gap below it, or null for the theme's.
     * @param direction which way it runs: [TextDirection.Auto] takes it from
     *   its first letter that has one, so Hebrew and Arabic run right to left
     *   and [TextAlign.Start] puts them flush right.
     */
    public fun paragraph(
        text: String,
        align: TextAlign = TextAlign.Start,
        indentLeft: Pt = Pt.ZERO,
        indentRight: Pt = Pt.ZERO,
        spacingBefore: Pt? = null,
        spacingAfter: Pt? = null,
        direction: TextDirection = TextDirection.Auto,
    ) {
        add(
            ParagraphBlock(
                content = listOf(TextRun(text)),
                align = align,
                indentLeft = indentLeft,
                indentRight = indentRight,
                spacingBefore = spacingBefore,
                spacingAfter = spacingAfter,
                direction = direction,
            ),
        )
    }

    /**
     * Append a paragraph built from inline runs, on the terms of the plain-text
     * one.
     *
     * ```kotlin
     * paragraph { +"This is the Q4 2026 summary." }
     * paragraph(align = TextAlign.Right, indentRight = 5.pt, spacingBefore = 10.pt) {
     *     text("Фото: Анна") { fontSize = 8.pt; color = Rgb.of(0x616161) }
     * }
     * ```
     */
    public fun paragraph(
        align: TextAlign = TextAlign.Start,
        indentLeft: Pt = Pt.ZERO,
        indentRight: Pt = Pt.ZERO,
        spacingBefore: Pt? = null,
        spacingAfter: Pt? = null,
        direction: TextDirection = TextDirection.Auto,
        block: InlineScope.() -> Unit,
    ) {
        add(
            ParagraphBlock(
                content = collectRuns(block),
                align = align,
                indentLeft = indentLeft,
                indentRight = indentRight,
                spacingBefore = spacingBefore,
                spacingAfter = spacingAfter,
                direction = direction,
            ),
        )
    }

    /** Append a heading of the given [level] (1..6) with plain text content. */
    public fun heading(level: Int, text: String) {
        add(HeadingBlock(level = level, content = listOf(TextRun(text))))
    }

    /** Append a heading of the given [level] (1..6) built from inline runs. */
    public fun heading(level: Int, block: InlineScope.() -> Unit) {
        add(HeadingBlock(level = level, content = collectRuns(block)))
    }

    /**
     * Append an unordered list.
     *
     * ```kotlin
     * bulletList {
     *     item { +"Revenue: +12%" }
     *     item {
     *         +"Departments"
     *         bulletList { item { +"Sales — 30%" } }
     *     }
     * }
     * ```
     */
    public fun bulletList(
        markerStyle: ListMarkerStyle = ListMarkerStyle.Bullet,
        indentPerLevel: Pt = DEFAULT_LIST_INDENT,
        block: ListScope.() -> Unit,
    ) {
        add(buildBulletList(markerStyle, indentPerLevel, block))
    }

    /**
     * Append an ordered list counting from [startNumber] in the given
     * [markerStyle] — Decimal, LowerAlpha, UpperAlpha, LowerRoman or UpperRoman.
     */
    public fun numberedList(
        startNumber: Int = 1,
        markerStyle: ListMarkerStyle = ListMarkerStyle.Decimal,
        indentPerLevel: Pt = DEFAULT_LIST_INDENT,
        block: ListScope.() -> Unit,
    ) {
        add(buildNumberedList(startNumber, markerStyle, indentPerLevel, block))
    }

    /**
     * Append a table.
     *
     * ```kotlin
     * table {
     *     columns = TableColumns.Fractional(listOf(2f, 1f))
     *     header {
     *         cell("Item")
     *         cell("Qty", align = TextAlign.Right)
     *     }
     *     row {
     *         cell { +"Apple" }
     *         cell(align = TextAlign.Right) { +"3" }
     *     }
     * }
     * ```
     */
    public fun table(block: TableScope.() -> Unit) {
        add(buildTable(block))
    }

    /**
     * Append a fenced code block — the ` ``` ` block of CommonMark.
     *
     * Every line break in [text] is a line break on the page, and the text is set
     * in the theme's monospaced font over a filled panel spanning the column.
     *
     * ```kotlin
     * codeBlock("fun main() {\n    println(\"hi\")\n}")
     * ```
     *
     * @param text the code, `\n`-separated.
     * @param backgroundColor the panel behind it; null takes the theme's, which
     *   may itself be none.
     * @param fontSize the monospaced size; null takes the theme's.
     * @param padding the gap between the panel's edge and the code.
     */
    public fun codeBlock(
        text: String,
        backgroundColor: Rgb? = null,
        fontSize: Pt? = null,
        padding: Pt = DEFAULT_CODE_PADDING,
    ) {
        add(CodeBlock(text = text, background = backgroundColor, fontSize = fontSize, padding = padding))
    }

    /**
     * Append a blockquote — the `>` block of CommonMark.
     *
     * The children are set in past a coloured bar drawn in the gutter.
     *
     * ```kotlin
     * blockquote {
     *     +"Everything should be made as simple as possible,"
     *     paragraph("but not simpler.")
     * }
     * ```
     *
     * @param barColor the bar's colour; null takes the theme's.
     * @param barWidth the bar's width.
     * @param indentLeft the gap between the bar and the quoted content.
     */
    public fun blockquote(
        barColor: Rgb? = null,
        barWidth: Pt = DEFAULT_QUOTE_BAR_WIDTH,
        indentLeft: Pt = DEFAULT_QUOTE_INDENT,
        block: BlockquoteScope.() -> Unit,
    ) {
        add(buildBlockquote(barColor, barWidth, indentLeft, block))
    }

    /**
     * Append a horizontal rule — the `---` separator of CommonMark.
     *
     * ```kotlin
     * hr()                                        // the theme's thickness and colour
     * hr(thickness = 2.pt, color = Rgb.BLACK)     // this one's own
     * ```
     *
     * @param thickness the rule's stroke width; null takes the theme's.
     * @param color the rule's colour; null takes the theme's.
     * @param verticalSpacing the space kept above and below it; null takes the
     *   theme's. It is the block's spacing rather than a property of the rule,
     *   which is what lets it collapse against a neighbour's the ordinary way
     */
    public fun hr(thickness: Pt? = null, color: Rgb? = null, verticalSpacing: Pt? = null) {
        add(
            HorizontalRuleBlock(
                thickness = thickness,
                color = color,
                spacingBefore = verticalSpacing,
                spacingAfter = verticalSpacing,
            ),
        )
    }
}

/** Runs a nested inline block and returns what it collected. */
internal fun collectRuns(block: InlineScope.() -> Unit): List<InlineRun> {
    val scope = InlineScope()
    scope.block()
    return scope.runs()
}

// The defaults a DSL parameter needs are the blocks' own, read off the blocks
// rather than restated: a second copy of "a quotation bar is 3pt" is a second
// place for it to be changed in.

/** The default inner padding of a [CodeBlock]. */
public val DEFAULT_CODE_PADDING: Pt = CodeBlock(text = "").padding

/** The default width of a [BlockquoteBlock]'s bar. */
public val DEFAULT_QUOTE_BAR_WIDTH: Pt = BlockquoteBlock(children = emptyList()).barWidth

/** The default gap between a [BlockquoteBlock]'s bar and its content. */
public val DEFAULT_QUOTE_INDENT: Pt = BlockquoteBlock(children = emptyList()).indentLeft

/** The default indent of one level of a [ListBlock]. */
public val DEFAULT_LIST_INDENT: Pt = ListBlock(items = emptyList()).indentPerLevel

/** The default padding between a [TableBlock] cell's edge and its content. */
public val DEFAULT_CELL_PADDING: Pt = TableBlock(columns = TableColumns.Equal(1), rows = emptyList()).cellPadding
