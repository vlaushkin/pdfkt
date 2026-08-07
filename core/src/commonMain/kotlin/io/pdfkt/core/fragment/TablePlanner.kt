/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.fragment

import io.pdfkt.core.block.TableColumns
import io.pdfkt.core.block.VerticalAlign
import io.pdfkt.core.model.Pt
import io.pdfkt.core.model.Rect
import io.pdfkt.core.model.Rgb
import io.pdfkt.core.plan.PlacedItem
import io.pdfkt.core.resolve.StyledCell
import io.pdfkt.core.resolve.StyledTable

// No clause applies: L4 is this library's own design. §8.4.3.2 "Line width" is where the one thing a rule
// borrows from the standard comes from — a stroke lies centred on the line it
// marks, and so does every rule drawn here.

/**
 * The table half of the fragmenter: two passes, both over plans, speaking the
 * ordinary [FitResult] protocol.
 *
 * > Two passes, both over plans:
 * >
 * > 1. column widths from the requested mode (fixed, equal, fractional)
 * > 2. row heights by planning each cell's content at its column width and
 * >    taking the tallest
 * >
 * > Then rows are placed, splitting across pages and repeating header rows.
 * > Cell content is planned by the same fragmenter, so a cell can hold any
 * > block — including another table — without a special path.
 *
 * The last sentence is the whole design. A cell is planned by
 * [BlockPlanner.planSequence], which is what a page, a quotation and a list item
 * are planned by, so a cell holding a bullet list and a nested table is not a
 * case anybody wrote code for: it is the same recursion arriving one level
 * deeper. Nothing in this file knows what a paragraph is.
 *
 * The second rule the design fixes is where a table may be cut: tables speak
 * the same `FitResult` protocol — `Splits` between rows, never inside a row —
 * and the repeated header rows are part of every `head`.
 *
 * So a row is measured against the room there is, and either all of it is placed
 * or none of it is. A row that fits nowhere — not even a page with nothing else
 * on it — is refused by the progress guard rather than cut, because cutting it
 * is the one thing the rule forbids.
 *
 * @param blocks the planner cells recurse into. It is the one that owns this
 *   planner, which is what makes the recursion a cycle back into the same code
 *   rather than a second layout engine for the inside of a cell.
 */
internal class TablePlanner(private val blocks: BlockPlanner) {
    /**
     * How much of [table] fits [available], set in a column of [width].
     *
     * @param atPageTop whether the space offered is a container with nothing
     *   else in it. It decides only what happens when even the first row does
     *   not fit: on a page that is partly used the table moves on, and on an
     *   empty one there is nowhere left to move it to.
     * @throws UnplaceableBlockException if a row cannot be placed even in an
     *   empty container, or if the caller fixed column widths that do not fit.
     */
    fun planTable(table: StyledTable, width: Pt, available: Pt, atPageTop: Boolean): FitResult {
        val widths = columnWidths(table, width, available)
        val header = table.header

        // Pass two, first application: the header is a row like any other, and
        // it is planned before any body row because it is what every part of a
        // split table begins with.
        val headerPlan = if (header == null) null else planRow(table, header, widths, available)
        if (header != null && headerPlan == null) {
            if (atPageTop) throw UnplaceableBlockException.rowTallerThanThePage(table, width, available)
            return FitResult.Defers
        }

        val items = mutableListOf<PlacedItem>()
        var used = Pt.ZERO
        if (headerPlan != null) {
            items += headerPlan.items
            used = headerPlan.height
        }
        val headerHeight = if (headerPlan == null) null else used

        val bottoms = mutableListOf<Pt>()
        for ((index, row) in table.rows.withIndex()) {
            val plan = planRow(table, row, widths, available - used)
            if (plan == null) {
                if (bottoms.isEmpty()) return withoutARow(table, width, available, atPageTop, items, used, headerPlan)
                return FitResult.Splits(
                    head = ruled(table, widths, used, headerHeight, bottoms.dropLast(1), items),
                    consumed = used,
                    tail = listOf(continuation(table, table.rows.subList(index, table.rows.size))),
                )
            }
            items += plan.items.map { item -> item.translated(Pt.ZERO, -used) }
            used += plan.height
            bottoms += used
        }
        val separator = if (bottoms.isEmpty()) null else headerHeight
        return FitResult.Fits(ruled(table, widths, used, separator, bottoms.dropLast(1), items), used)
    }

    /**
     * What a table answers when not one of its body rows fitted.
     *
     * A header with nothing under it is not half a table, so the ordinary answer
     * is that the whole table moves on — which is also what
     * `keepHeaderWithFirstRow` asks for, "the [keepWithNext] of a header row".
     *
     * The one case where the header *is* placed on its own is a table that both
     * turned that flag off and does not repeat its header: the caller has said
     * the header may be left behind and that it will not be drawn again, so
     * leaving it here is the only way it appears at all. With
     * [StyledTable.repeatHeader] on there is nothing for the flag to change —
     * the next part would draw the header again, and a lone header above the
     * break would be a duplicate rather than a heading.
     */
    private fun withoutARow(
        table: StyledTable,
        width: Pt,
        available: Pt,
        atPageTop: Boolean,
        items: List<PlacedItem>,
        used: Pt,
        headerPlan: RowPlan?,
    ): FitResult {
        if (atPageTop) throw UnplaceableBlockException.rowTallerThanThePage(table, width, available)
        val orphanHeader = headerPlan != null && !table.keepHeaderWithFirstRow && !table.repeatHeader
        if (!orphanHeader) return FitResult.Defers
        return FitResult.Splits(
            head = ruled(
                table,
                columnWidths(table, width, available),
                used,
                headerSeparator = null,
                emptyList(),
                items,
            ),
            consumed = used,
            tail = listOf(continuation(table, table.rows).copy(header = null)),
        )
    }

    /**
     * What is left of [table] once [rows] are all that remain of it.
     *
     * The header comes with it when the table repeats one — which is the whole
     * of "the repeated header rows are part of every `head`": the tail is an
     * ordinary table that begins with a header, and the next page plans it the
     * way this one did. A table that does not repeat its header loses it here,
     * so that the continuation is a continuation and not a second table.
     */
    private fun continuation(table: StyledTable, rows: List<List<StyledCell>>): StyledTable = table.copy(
        rows = rows.toList(),
        header = if (table.repeatHeader) table.header else null,
        spacingBefore = Pt.ZERO,
    )

    // ------------------------------------------------------- pass one: widths

    /**
     * Pass one: what each column is wide, from the mode the caller asked for.
     *
     * [TableColumns.Equal] and [TableColumns.Fractional] are shares of the width
     * the table was given, so they always fit it — the second is the first with
     * the shares written out. [TableColumns.Fixed] is a width the caller chose,
     * and one that does not fit is refused rather than scaled: a length the
     * caller fixed is not this library's to change, which is the rule an image
     * the caller sized is held to as well.
     */
    private fun columnWidths(table: StyledTable, width: Pt, available: Pt): List<Pt> = when (
        val columns = table.columns
    ) {
        is TableColumns.Equal -> List(columns.count) { width / columns.count.toFloat() }

        is TableColumns.Fractional -> {
            val total = columns.weights.fold(0.0f) { sum, weight -> sum + weight }
            columns.weights.map { weight -> width * (weight / total) }
        }

        is TableColumns.Fixed -> {
            val total = columns.widths.fold(Pt.ZERO) { sum, column -> sum + column }
            if (total > width + BlockPlanner.TOLERANCE) {
                throw UnplaceableBlockException.columnsWiderThanTheSpace(table, width, available, total)
            }
            columns.widths
        }
    }

    // ------------------------------------------------------ pass two: heights

    /** One row, planned: everything in it, and how far down it reaches. */
    private class RowPlan(val items: List<PlacedItem>, val height: Pt)

    /**
     * Pass two: [cells] planned at their columns' widths, as tall as the tallest
     * of them, or null if the row does not fit [available].
     *
     * Every cell is planned against the whole room there is rather than against
     * what the cells before it left, because they are beside each other and not
     * below each other; the row's height is then the tallest answer plus the
     * padding above and below. A cell that cannot place all of its content in
     * that room makes the row not fit — the row is never cut, so a cell that
     * would have to be is the row saying it belongs further down.
     *
     * The cells are planned with `atPageTop = false` whatever the page is doing.
     * A cell is not a page: a block that answers `Defers` inside one is not a
     * block with nowhere to go, it is this row saying it wants more room than
     * this space has left — and the progress guard is asked about the row, once,
     * by the caller that knows whether the space was empty.
     */
    private fun planRow(table: StyledTable, cells: List<StyledCell>, widths: List<Pt>, available: Pt): RowPlan? {
        val padding = table.cellPadding
        val room = available - padding * 2.0f
        if (room <= Pt.ZERO) return null
        val plans = mutableListOf<List<PlacedItem>>()
        val heights = mutableListOf<Pt>()
        for ((index, cell) in cells.withIndex()) {
            val column = widths[index] - padding * 2.0f
            if (column <= Pt.ZERO) {
                throw UnplaceableBlockException.columnNarrowerThanItsPadding(table, widths[index], available, padding)
            }
            val fit = blocks.planSequence(cell.content, column, room, atPageTop = false)
            if (fit !is FitResult.Fits) return null
            plans += fit.plan
            heights += fit.consumed
        }
        val content = heights.fold(Pt.ZERO) { tallest, height -> if (height > tallest) height else tallest }
        return RowPlan(placeRow(cells, widths, plans, heights, content, padding), content + padding * 2.0f)
    }

    /**
     * The planned cells of one row, moved to their columns and to their
     * vertical alignment within a row that may be taller than any of them.
     *
     * Every background is drawn before any content, and not each one before its
     * own cell: the fills are a band across the row, and a cell whose background
     * was emitted after its neighbour's text would paint over it.
     *
     * @param content how tall the tallest cell's content is — the row's height
     *   without its padding, which is what a middle- or bottom-aligned cell is
     *   measured against.
     */
    private fun placeRow(
        cells: List<StyledCell>,
        widths: List<Pt>,
        plans: List<List<PlacedItem>>,
        heights: List<Pt>,
        content: Pt,
        padding: Pt,
    ): List<PlacedItem> {
        val height = content + padding * 2.0f
        val items = mutableListOf<PlacedItem>()
        var x = Pt.ZERO
        for ((index, cell) in cells.withIndex()) {
            val background = cell.background
            if (background != null) items += PlacedItem.Rect(Rect(x, -height, widths[index], height), fill = background)
            x += widths[index]
        }
        x = Pt.ZERO
        for ((index, cell) in cells.withIndex()) {
            val slack = content - heights[index]
            val shift = when (cell.verticalAlign) {
                VerticalAlign.Top -> Pt.ZERO
                VerticalAlign.Middle -> slack / 2.0f
                VerticalAlign.Bottom -> slack
            }
            items += plans[index].map { item -> item.translated(x + padding, -(padding + shift)) }
            x += widths[index]
        }
        return items
    }

    // ------------------------------------------------------------- the rules

    /**
     * [content] with the table's rules drawn over it.
     *
     * A rule lies **centred on the boundary it marks**, which is where a stroked
     * path lies: "stroking a path shall entail painting all points whose
     * perpendicular distance from the path in user space is less than or equal
     * to half the line width" (§8.4.3.2). And it takes no room — a row is as
     * tall as its content and its padding, and the ink of a rule is not a row of
     * its own — so the outer rules overhang the table's box by half their width,
     * exactly as a stroked rectangle of the same box would.
     *
     * They are drawn as filled rectangles rather than stroked paths for the
     * reason [io.pdfkt.core.resolve.StyledRule] is, and the clause gives it: a
     * width of zero is a stroke "1 device pixel wide", device-dependent and
     * "nearly invisible" on a high-resolution device, where a filled rectangle
     * of no width is nothing at all. A caller who set a border width to zero
     * asked for no border, not for the thinnest one the printer has.
     *
     * Rules come last so that nothing can be drawn over them — a cell's
     * background is a band under its text, and the grid is over both.
     *
     * @param headerSeparator how far below the top of the table the header ends,
     *   or null for a part with no header or with nothing under it. A header
     *   with no row beneath it in this part ends at the table's own bottom edge,
     *   where the outer rule already is.
     * @param innerBottoms how far down each boundary *between* two placed rows
     *   is. The last row's bottom is not one of them: it is the table's edge.
     */
    private fun ruled(
        table: StyledTable,
        widths: List<Pt>,
        height: Pt,
        headerSeparator: Pt?,
        innerBottoms: List<Pt>,
        content: List<PlacedItem>,
    ): List<PlacedItem> {
        val style = table.borders
        val width = widths.fold(Pt.ZERO) { total, column -> total + column }
        if (width <= Pt.ZERO || height <= Pt.ZERO) return content
        val rules = mutableListOf<PlacedItem>()
        for (bottom in innerBottoms) rules += horizontal(-bottom, width, style.innerWidth, style.color)
        if (headerSeparator != null) {
            rules += horizontal(-headerSeparator, width, style.headerSeparatorWidth, style.color)
        }
        var x = Pt.ZERO
        for (column in widths.dropLast(1)) {
            x += column
            rules += vertical(x, height, style.innerWidth, style.color)
        }
        rules += horizontal(Pt.ZERO, width, style.outerWidth, style.color)
        rules += horizontal(-height, width, style.outerWidth, style.color)
        rules += vertical(Pt.ZERO, height, style.outerWidth, style.color)
        rules += vertical(width, height, style.outerWidth, style.color)
        return content + rules
    }

    private companion object {
        /** A rule along the boundary at [y], centred on it. A width of zero draws nothing. */
        fun horizontal(y: Pt, width: Pt, thickness: Pt, color: Rgb): List<PlacedItem> = if (thickness <= Pt.ZERO) {
            emptyList()
        } else {
            listOf(PlacedItem.Rect(Rect(Pt.ZERO, y - thickness / 2.0f, width, thickness), fill = color))
        }

        /** A rule down the boundary at [x], centred on it, as tall as the part of the table that was placed. */
        fun vertical(x: Pt, height: Pt, thickness: Pt, color: Rgb): List<PlacedItem> = if (thickness <= Pt.ZERO) {
            emptyList()
        } else {
            listOf(PlacedItem.Rect(Rect(x - thickness / 2.0f, -height, thickness, height), fill = color))
        }
    }
}
