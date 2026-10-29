/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.resolve

import io.pdfkt.core.block.ImageFit
import io.pdfkt.core.block.TableBorderStyle
import io.pdfkt.core.block.TableColumns
import io.pdfkt.core.block.TextAlign
import io.pdfkt.core.block.TextDirection
import io.pdfkt.core.block.VerticalAlign
import io.pdfkt.core.block.requireRectangularTable
import io.pdfkt.core.image.ImageRef
import io.pdfkt.core.model.Insets
import io.pdfkt.core.model.Pt
import io.pdfkt.core.model.Rgb
import io.pdfkt.core.model.Size

// No clause applies: L4 is this library's own design. This is the "styled blocks; no geometry yet" the resolve
// stage produces and the fragmenter's `planBlock(block: StyledBlock, …)` takes.

/**
 * A block with the theme applied and nothing measured.
 *
 * The fragmenter's input: every question of
 * *what it looks like* has been answered, and every question of *where it goes*
 * is still open. There is no coordinate in this file, no line, and no page —
 * only sizes and colours that were a theme's a moment ago.
 *
 * Two properties differ from [io.pdfkt.core.block.Block]'s, and the difference
 * is the stage's whole job: spacing is no longer nullable. A block says "take
 * the theme's spacing" by leaving it null, and after resolving there is no theme
 * left to take it from.
 *
 * Dispatch over these variants is one exhaustive `when`, "the same shape as the
 * L1 serializer, for the same reason: the compiler proves coverage".
 */
public sealed interface StyledBlock {
    public val spacingBefore: Pt
    public val spacingAfter: Pt
    public val keepWithNext: Boolean
    public val keepTogether: Boolean
}

/**
 * A paragraph of styled inline content — what a paragraph, and also a heading,
 * resolves to.
 *
 * A heading keeps no type of its own past this stage: it was a request for
 * particular fonts, sizes, spacing and keep flags, and once those are chosen it
 * is a paragraph like any other. That is one less variant for the fragmenter to
 * dispatch on and one less way for a heading to be laid out differently from
 * the text it introduces.
 *
 * @property content the runs and breaks, in reading order.
 * @property lineHeight the multiple of the font's own line height to set
 *   consecutive baselines apart by (the theme's, already chosen).
 * @property align how the lines sit in the column.
 * @property indentLeft space kept clear on the left of every line.
 * @property indentRight space kept clear on the right of every line.
 * @property firstLineIndent extra indent on the first line only.
 * @property direction which way the paragraph runs; see [TextDirection].
 */
public data class StyledParagraph(
    public val content: List<StyledInline>,
    public val lineHeight: Float,
    public val align: TextAlign = TextAlign.Start,
    public val indentLeft: Pt = Pt.ZERO,
    public val indentRight: Pt = Pt.ZERO,
    public val firstLineIndent: Pt = Pt.ZERO,
    override val spacingBefore: Pt = Pt.ZERO,
    override val spacingAfter: Pt = Pt.ZERO,
    override val keepWithNext: Boolean = false,
    override val keepTogether: Boolean = false,
    public val direction: TextDirection = TextDirection.Auto,
) : StyledBlock

/**
 * Pre-formatted text, already cut into lines at the breaks the caller wrote.
 *
 * The cutting happens here because it is a property of the text and not of the
 * column: a code block's lines are the caller's lines whatever width the page
 * turns out to be. What the line breaker may still do is break a line too long
 * for the column — which is its business, not this stage's.
 *
 * @property lines one run per line, empty runs included: a blank line in the
 *   source is a blank line on the page.
 * @property background the panel drawn behind the text, or null for none.
 * @property padding space between that panel's edge and the text.
 */
public data class StyledCode(
    public val lines: List<StyledTextRun>,
    public val lineHeight: Float,
    public val background: Rgb? = null,
    public val padding: Pt = Pt.ZERO,
    override val spacingBefore: Pt = Pt.ZERO,
    override val spacingAfter: Pt = Pt.ZERO,
    override val keepWithNext: Boolean = false,
    override val keepTogether: Boolean = false,
) : StyledBlock

/**
 * A list whose markers are already text: the numbering has been counted, the
 * bullet has been chosen, and both are runs in a font like any other text
 */
public data class StyledList(
    public val items: List<StyledListItem>,
    override val spacingBefore: Pt = Pt.ZERO,
    override val spacingAfter: Pt = Pt.ZERO,
    override val keepWithNext: Boolean = false,
    override val keepTogether: Boolean = false,
) : StyledBlock

/**
 * One item: its marker, how far its content is set in, and that content.
 *
 * @property marker the text drawn in the margin beside the first line —
 *   `•`, `2.`, `iv.` — or null for an item that takes none.
 * @property indent how far the content is set in from the list's left edge. A
 *   nested list indents again by its own value, which is what makes nesting
 *   compound without anything counting levels.
 * @property content any blocks at all, resolved by the same stage.
 */
public data class StyledListItem(
    public val marker: StyledTextRun?,
    public val indent: Pt,
    public val content: List<StyledBlock>,
)

/**
 * A table whose cells hold resolved blocks. Widths, row heights and where the
 * rows break are the fragmenter's two passes over plans, not this stage's.
 *
 * The grid is rectangular here as it is in [io.pdfkt.core.block.TableBlock], and
 * for the same reason: the fragmenter reads a cell's width off the column it is
 * in, so a row that disagrees with [columns] about how many there are carries a
 * cell nothing can be measured against.
 */
public data class StyledTable(
    public val columns: TableColumns,
    public val rows: List<List<StyledCell>>,
    public val header: List<StyledCell>? = null,
    public val borders: TableBorderStyle = TableBorderStyle.Default,
    public val cellPadding: Pt = Pt.ZERO,
    public val repeatHeader: Boolean = true,
    public val keepHeaderWithFirstRow: Boolean = true,
    override val spacingBefore: Pt = Pt.ZERO,
    override val spacingAfter: Pt = Pt.ZERO,
    override val keepWithNext: Boolean = false,
    override val keepTogether: Boolean = false,
) : StyledBlock {
    init {
        requireRectangularTable(columns, rows, header)
    }
}

/**
 * One cell of a [StyledTable], holding blocks the fragmenter recurses into.
 *
 * @property align what the cell asked its text to be set flush to. The resolve
 *   stage has already passed it on to the paragraphs directly in [content]; it
 *   stays here as the record of what was asked, and the fragmenter reads
 *   [verticalAlign] instead — that one is a question only a planned row can
 *   answer, since it is about a height nothing has measured yet.
 */
public data class StyledCell(
    public val content: List<StyledBlock>,
    public val align: TextAlign = TextAlign.Start,
    public val verticalAlign: VerticalAlign = VerticalAlign.Top,
    public val background: Rgb? = null,
)

/** Blocks set in from the left with a bar beside them. */
public data class StyledQuote(
    public val children: List<StyledBlock>,
    public val barColor: Rgb,
    public val barWidth: Pt,
    public val indentLeft: Pt,
    override val spacingBefore: Pt = Pt.ZERO,
    override val spacingAfter: Pt = Pt.ZERO,
    override val keepWithNext: Boolean = false,
    override val keepTogether: Boolean = false,
) : StyledBlock

/** A rule across the column, at the theme's thickness and colour unless the block gave its own. */
public data class StyledRule(
    public val thickness: Pt,
    public val color: Rgb,
    override val spacingBefore: Pt = Pt.ZERO,
    override val spacingAfter: Pt = Pt.ZERO,
    override val keepWithNext: Boolean = false,
    override val keepTogether: Boolean = false,
) : StyledBlock

/**
 * An image and how it should sit in the space it gets. The fitting arithmetic
 * runs when the page is planned, against a box this stage knows nothing about.
 */
public data class StyledImage(
    public val image: ImageRef,
    public val fit: ImageFit,
    public val width: Pt? = null,
    public val height: Pt? = null,
    public val rotation: Float = 0.0f,
    override val spacingBefore: Pt = Pt.ZERO,
    override val spacingAfter: Pt = Pt.ZERO,
    override val keepWithNext: Boolean = false,
    override val keepTogether: Boolean = true,
) : StyledBlock

/** Whatever follows starts a new page. */
public data object StyledPageBreak : StyledBlock {
    override val spacingBefore: Pt = Pt.ZERO
    override val spacingAfter: Pt = Pt.ZERO
    override val keepWithNext: Boolean = false
    override val keepTogether: Boolean = false
}

/**
 * A run of pages on paper of their own, its content resolved — see
 * `SectionBlock`. The fragmenter is what acts on it; a planner asked to place
 * one inside a box refuses.
 */
public data class StyledSection(
    public val pageSize: Size,
    public val margins: Insets,
    public val verticalAlign: VerticalAlign,
    public val content: List<StyledBlock>,
) : StyledBlock {
    override val spacingBefore: Pt get() = Pt.ZERO
    override val spacingAfter: Pt get() = Pt.ZERO
    override val keepWithNext: Boolean get() = false
    override val keepTogether: Boolean get() = false
}
