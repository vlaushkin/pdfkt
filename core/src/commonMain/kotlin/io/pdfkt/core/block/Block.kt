/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.block

import io.pdfkt.core.font.FontRef
import io.pdfkt.core.image.ImageRef
import io.pdfkt.core.model.Insets
import io.pdfkt.core.model.Pt
import io.pdfkt.core.model.Rgb
import io.pdfkt.core.model.Size
import io.pdfkt.core.model.pt

// No clause applies: L4 is this library's own design. This is the tree the DSL builds and the engine
// consumes — no intermediate element layer in between.

/**
 * A block of a document: what the DSL builds and what the engine's resolve
 * stage takes in.
 *
 * Everything here is *what the caller asked for*, never *where it goes*. There
 * are no coordinates in this file and no measurements taken from a font: a
 * block says "a heading, level 2, keep it with what follows", and the theme
 * says what a level-2 heading is set in. Geometry appears two stages later, in
 * the page plan.
 *
 * The four members every block carries are the ones the fragmenter reads before
 * it reads anything type-specific — spacing above and below, and the two keep
 * flags, `keepWithNext` and `keepTogether`.
 *
 * @property spacingBefore space above this block, or null to take the theme's.
 * @property spacingAfter space below this block, or null to take the theme's.
 * @property keepWithNext keep this block on the page of the one that follows —
 *   what stops a heading being left alone at the foot of a page.
 * @property keepTogether do not split this block across pages when it would fit
 *   an empty one. On an empty page the progress guard overrides it, since the
 *   alternative is a document of blank pages.
 */
public sealed interface Block {
    public val spacingBefore: Pt?
    public val spacingAfter: Pt?
    public val keepWithNext: Boolean
    public val keepTogether: Boolean
}

/**
 * A run of inline content set as a paragraph.
 *
 * @property content the inline runs, in reading order.
 * @property align how the lines sit in the column.
 * @property indentLeft space kept clear on the left of every line.
 * @property indentRight space kept clear on the right of every line.
 * @property firstLineIndent extra indent for the first line only; negative
 *   hangs it out to the left, which is how a hanging indent is written.
 */
public data class ParagraphBlock(
    public val content: List<InlineRun>,
    public val align: TextAlign = TextAlign.Left,
    public val indentLeft: Pt = Pt.ZERO,
    public val indentRight: Pt = Pt.ZERO,
    public val firstLineIndent: Pt = Pt.ZERO,
    override val spacingBefore: Pt? = null,
    override val spacingAfter: Pt? = null,
    override val keepWithNext: Boolean = false,
    override val keepTogether: Boolean = false,
) : Block

/**
 * A heading of [level] 1 to 6, set in whatever the theme says that level is.
 *
 * Both keep flags default to true and that is the whole point of the type: a
 * heading that is separated from the text it introduces has failed at the one
 * job it has.
 */
public data class HeadingBlock(
    public val level: Int,
    public val content: List<InlineRun>,
    override val spacingBefore: Pt? = null,
    override val spacingAfter: Pt? = null,
    override val keepWithNext: Boolean = true,
    override val keepTogether: Boolean = true,
) : Block {
    init {
        require(level in MIN_LEVEL..MAX_LEVEL) {
            "a heading is one of levels $MIN_LEVEL..$MAX_LEVEL, which is what a Theme carries a " +
                "font, a size and a spacing for; got $level"
        }
    }

    public companion object {
        public const val MIN_LEVEL: Int = 1
        public const val MAX_LEVEL: Int = 6
    }
}

/**
 * A list, ordered or not. Each item holds blocks rather than runs, so an item
 * may carry a paragraph, a nested list or a table without a special path.
 *
 * @property startNumber what an ordered list counts from; ignored by the
 *   unordered marker styles.
 * @property indentPerLevel how far the items are indented from the block's own
 *   left edge, a quarter of an inch unless the caller says otherwise. Nesting
 *   compounds it, because a nested list is a block of an item and is indented
 *   again by its own value.
 */
public data class ListBlock(
    public val items: List<ListItem>,
    public val ordered: Boolean = false,
    public val startNumber: Int = 1,
    public val markerStyle: ListMarkerStyle = if (ordered) ListMarkerStyle.Decimal else ListMarkerStyle.Bullet,
    public val indentPerLevel: Pt = 18.pt,
    override val spacingBefore: Pt? = null,
    override val spacingAfter: Pt? = null,
    override val keepWithNext: Boolean = false,
    override val keepTogether: Boolean = false,
) : Block

/** One item of a [ListBlock]: any blocks at all, marked by the list's marker style. */
public data class ListItem(public val content: List<Block>)

/**
 * How the items of a list are marked. The unordered styles are a fixed
 * character each; the ordered ones count from [ListBlock.startNumber].
 *
 * A marker is *text*, drawn in the theme's body font like everything else — so
 * a marker whose character that font cannot draw is refused with the same error
 * as any other undrawable character, rather than quietly coming out blank.
 */
public enum class ListMarkerStyle {
    Bullet,
    Dash,
    Circle,
    Square,
    Decimal,
    LowerAlpha,
    UpperAlpha,
    LowerRoman,
    UpperRoman,
}

/**
 * A table of cells in a grid.
 *
 * @property columns how the column widths are decided.
 * @property header the header row, repeated on every page the table spans when
 *   [repeatHeader] is set, or null for a table without one.
 * @property rows the body rows, each as many cells as there are columns.
 * @property borders the rules drawn around and between the cells.
 * @property cellPadding space between a cell's border and its content.
 * @property keepHeaderWithFirstRow do not leave a header alone at the foot of a
 *   page — the [keepWithNext] of a header row.
 */
public data class TableBlock(
    public val columns: TableColumns,
    public val rows: List<List<TableCell>>,
    public val header: List<TableCell>? = null,
    public val borders: TableBorderStyle = TableBorderStyle.Default,
    public val cellPadding: Pt = 4.pt,
    public val repeatHeader: Boolean = true,
    public val keepHeaderWithFirstRow: Boolean = true,
    override val spacingBefore: Pt? = null,
    override val spacingAfter: Pt? = null,
    override val keepWithNext: Boolean = false,
    override val keepTogether: Boolean = false,
) : Block {
    init {
        requireRectangularTable(columns, rows, header)
    }
}

/**
 * Every row of a table, and its header, holds exactly as many cells as
 * [columns] says there are.
 *
 * A table is a grid, and the width of a cell comes from the column it is in — so
 * a row with a cell too many has a cell in no column at all, and there is
 * nothing to place it at. Refusing here rather than dropping it, or drawing it
 * over its neighbour, is the "no silent loss" policy applied to
 * the one shape a table can be malformed in.
 *
 * A row with too *few* cells is refused for the same reason read the other way:
 * a caller who wants an empty cell can write one, and a caller who forgot a cell
 * would otherwise see their table quietly reflowed by one column.
 */
internal fun requireRectangularTable(columns: TableColumns, rows: List<List<*>>, header: List<*>?) {
    if (header != null) {
        require(header.size == columns.count) {
            "the header of a table of ${columns.count} columns holds ${header.size} cells"
        }
    }
    for ((index, row) in rows.withIndex()) {
        require(row.size == columns.count) {
            "row $index of a table of ${columns.count} columns holds ${row.size} cells: a table is a " +
                "grid, and a cell in no column has no width to be set in"
        }
    }
}

/**
 * How the widths of a table's columns are decided.
 *
 * Each variant also says **how many** columns there are, which is the number
 * every row of the table is checked against: a grid whose rows disagree about
 * how wide they are is a grid with a cell nobody can place, and the three modes
 * are the only place that number can honestly come from.
 */
public sealed interface TableColumns {
    /** How many columns this describes. */
    public val count: Int

    /** [count] columns of one width, the available width divided between them. */
    public data class Equal(override val count: Int) : TableColumns {
        init {
            require(count > 0) { "a table has at least one column; got $count" }
        }
    }

    /** Columns in proportion to [weights] — a column of weight 2 is twice one of weight 1. */
    public data class Fractional(public val weights: List<Float>) : TableColumns {
        init {
            require(weights.isNotEmpty()) { "a table has at least one column; got no weights" }
            require(weights.all { weight -> weight > 0.0f }) {
                "a column of weight zero or less is a column of no width, which nothing fits in: " +
                    "got $weights"
            }
        }

        override val count: Int get() = weights.size
    }

    /** Columns of exactly these widths. */
    public data class Fixed(public val widths: List<Pt>) : TableColumns {
        init {
            require(widths.isNotEmpty()) { "a table has at least one column; got no widths" }
            require(widths.all { width -> width > Pt.ZERO }) {
                "a column of no width is one that nothing fits in: got $widths"
            }
        }

        override val count: Int get() = widths.size
    }
}

/**
 * One cell. Its content is blocks, not runs: a cell holds anything a document
 * holds, including another table, and the fragmenter plans it by recursing into
 * itself.
 */
public data class TableCell(
    public val content: List<Block>,
    public val align: TextAlign = TextAlign.Left,
    public val verticalAlign: VerticalAlign = VerticalAlign.Top,
    public val background: Rgb? = null,
)

/**
 * The rules a table is drawn with: around the table, between the cells, and
 * under the header. A width of zero draws nothing.
 */
public data class TableBorderStyle(
    public val outerWidth: Pt = HAIRLINE,
    public val innerWidth: Pt = HAIRLINE,
    public val headerSeparatorWidth: Pt = 1.pt,
    public val color: Rgb = Rgb.BLACK,
) {
    public companion object {
        private val HAIRLINE: Pt = 0.5.pt

        public val Default: TableBorderStyle = TableBorderStyle()

        public val None: TableBorderStyle = TableBorderStyle(
            outerWidth = Pt.ZERO,
            innerWidth = Pt.ZERO,
            headerSeparatorWidth = Pt.ZERO,
        )
    }
}

/**
 * Pre-formatted text: every line break in [text] is a line break on the page,
 * and it is set in the theme's monospaced font unless [font] says otherwise.
 *
 * @property font overrides the theme's code font for this block.
 * @property fontSize overrides the theme's code size for this block.
 * @property background the panel drawn behind the text, or null to take the
 *   theme's — which may itself be null, for no panel at all.
 * @property padding space between that panel's edge and the text.
 */
public data class CodeBlock(
    public val text: String,
    public val font: FontRef? = null,
    public val fontSize: Pt? = null,
    public val background: Rgb? = null,
    public val padding: Pt = 6.pt,
    override val spacingBefore: Pt? = null,
    override val spacingAfter: Pt? = null,
    override val keepWithNext: Boolean = false,
    override val keepTogether: Boolean = false,
) : Block

/**
 * Blocks set in from the left with a bar beside them.
 *
 * @property children what is quoted — blocks, so a quotation may hold several
 *   paragraphs, a list, or another quotation.
 */
public data class BlockquoteBlock(
    public val children: List<Block>,
    public val barColor: Rgb? = null,
    public val barWidth: Pt = 3.pt,
    public val indentLeft: Pt = 12.pt,
    override val spacingBefore: Pt? = null,
    override val spacingAfter: Pt? = null,
    override val keepWithNext: Boolean = false,
    override val keepTogether: Boolean = false,
) : Block

/** A rule across the column. Null takes the theme's thickness or colour. */
public data class HorizontalRuleBlock(
    public val thickness: Pt? = null,
    public val color: Rgb? = null,
    override val spacingBefore: Pt? = null,
    override val spacingAfter: Pt? = null,
    override val keepWithNext: Boolean = false,
    override val keepTogether: Boolean = false,
) : Block

/**
 * An image as a block of its own. An album-style document lowers to this
 * shape: each photo becomes an
 * `ImageBlock` with a fit policy, followed by a forced `PageBreakBlock`.
 *
 * The fitting itself is the engine's: this says which image, how it should sit
 * in the space it gets, and how far it is turned. What box it lands in is
 * decided when the page is planned, not here.
 *
 * @property width the width to draw at, or null to take the column's.
 * @property height the height to draw at, or null to take it from [fit].
 * @property rotation a turn about the centre of the box it lands in, in
 *   degrees, counter-clockwise — where an EXIF orientation ends up.
 */
public data class ImageBlock(
    public val image: ImageRef,
    public val fit: ImageFit = ImageFit.Contain,
    public val width: Pt? = null,
    public val height: Pt? = null,
    public val rotation: Float = 0.0f,
    override val spacingBefore: Pt? = null,
    override val spacingAfter: Pt? = null,
    override val keepWithNext: Boolean = false,
    override val keepTogether: Boolean = true,
) : Block

/** How an image fills the space it is given. */
public enum class ImageFit {
    /** As large as fits, aspect ratio kept — the rule a photo album wants. */
    Contain,

    /** Exactly the space given, aspect ratio not kept. */
    Stretch,
}

/**
 * Break the page here: whatever follows starts a new one — the other half of
 * what an album-style document lowers to.
 *
 * It carries no spacing of its own — spacing around a break is space at the
 * edge of a page, which is what margins are for.
 */
public data object PageBreakBlock : Block {
    override val spacingBefore: Pt? = null
    override val spacingAfter: Pt? = null
    override val keepWithNext: Boolean = false
    override val keepTogether: Boolean = false
}

/**
 * A run of pages set on paper of their own: [content] starts a new page of
 * [pageSize] with [margins], continues onto as many more of the same as it
 * needs, and whatever follows the section starts a new page on the document's
 * own paper again.
 *
 * This is what an album lowers a photograph to when every photograph is its own
 * page of its own size — the cover a square, each picture a sheet exactly as
 * wide and as tall as it is — and what a report lowers a landscape table to. It
 * is a statement about the sequence of pages, as a [PageBreakBlock] is, and so it
 * belongs at the top level of a document: one inside a list item or a table cell
 * is refused when the page is planned, because the box it would be planned into
 * is already on a page whose paper has been chosen.
 *
 * The document's bands, if it has any, are drawn on a section's pages too, in
 * strips taken from that page's own size and margins.
 *
 * @property pageSize the paper — the `/MediaBox` of every page of the section
 *   (ISO 32000-2:2020 §7.7.3.3).
 * @property margins what is kept clear on all four sides of it. Zero is a page
 *   whose content reaches its edges, which is what a photograph printed to the
 *   bleed is.
 * @property verticalAlign where the content of a page sits when it does not
 *   fill the page: [VerticalAlign.Middle] is a title page, centred top to
 *   bottom. A section that runs onto a second page aligns each page on its own.
 * @property content the blocks of the section, in order.
 */
public data class SectionBlock(
    public val pageSize: Size,
    public val margins: Insets,
    public val verticalAlign: VerticalAlign = VerticalAlign.Top,
    public val content: List<Block>,
) : Block {
    override val spacingBefore: Pt? get() = null
    override val spacingAfter: Pt? get() = null
    override val keepWithNext: Boolean get() = false
    override val keepTogether: Boolean get() = false
}

/** How the lines of a paragraph sit in their column. */
public enum class TextAlign { Left, Center, Right, Justify }

/** How a cell's content sits in a row taller than it is. */
public enum class VerticalAlign { Top, Middle, Bottom }
