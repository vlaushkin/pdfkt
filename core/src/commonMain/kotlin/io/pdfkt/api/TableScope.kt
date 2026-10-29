/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.api

import io.pdfkt.core.block.TableBlock
import io.pdfkt.core.block.TableBorderStyle
import io.pdfkt.core.block.TableCell
import io.pdfkt.core.block.TableColumns
import io.pdfkt.core.block.TextAlign
import io.pdfkt.core.block.VerticalAlign
import io.pdfkt.core.model.Pt
import io.pdfkt.core.model.Rgb

// No clause applies: the DSL is this library's own design. Column widths, row heights, the
// repeated header and the rules are the
// engine's two passes; this file
// collects the grid and hands it over as one io.pdfkt.core.block.TableBlock.

/**
 * A `table { }`: the columns, at most one header row, and the body rows.
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
 *
 * [columns] may be left unset, in which case the table is as many equal columns
 * as the first row written has cells. That is a convenience for the common
 * table and not a repair: every row is still checked against the count, so a
 * ragged grid is refused rather than reflowed (io.pdfkt.core.block.TableBlock).
 */
@PdfktDsl
public class TableScope internal constructor() {
    private var header: List<TableCell>? = null
    private val rows = mutableListOf<List<TableCell>>()

    /** How the column widths are decided; null infers equal columns from the first row. */
    public var columns: TableColumns? = null

    /** The space between a cell's edge and its content. */
    public var cellPadding: Pt = DEFAULT_CELL_PADDING

    /** The rules drawn around and between the cells. */
    public var borderStyle: TableBorderStyle = TableBorderStyle.Default

    /** Whether the header is repeated on every page a split table reaches. */
    public var repeatHeader: Boolean = true

    /** Whether the header is kept off the foot of a page, with its first body row. */
    public var keepHeaderWithFirstRow: Boolean = true

    /**
     * The header row, at most one per table. A second one is refused rather than
     * quietly replacing the first: two headers is a caller who meant [row].
     */
    public fun header(block: RowScope.() -> Unit) {
        require(header == null) { "a table has one header row; this one was given a second" }
        header = collectCells(block)
    }

    /** One body row. */
    public fun row(block: RowScope.() -> Unit) {
        rows.add(collectCells(block))
    }

    internal fun build(): TableBlock {
        val first = header ?: rows.firstOrNull()
        requireNotNull(first) {
            "a table needs a row: there is nothing to set in columns, and nothing to draw rules around"
        }
        return TableBlock(
            columns = columns ?: TableColumns.Equal(first.size),
            rows = rows.toList(),
            header = header,
            borders = borderStyle,
            cellPadding = cellPadding,
            repeatHeader = repeatHeader,
            keepHeaderWithFirstRow = keepHeaderWithFirstRow,
        )
    }
}

/** The cells of one row, in the order the columns are in. */
@PdfktDsl
public class RowScope internal constructor() {
    private val cells = mutableListOf<TableCell>()

    /** A cell of one plain-text paragraph. */
    public fun cell(
        text: String,
        align: TextAlign = TextAlign.Start,
        verticalAlign: VerticalAlign = VerticalAlign.Top,
        backgroundColor: Rgb? = null,
    ) {
        cell(align, verticalAlign, backgroundColor) { +text }
    }

    /**
     * A cell built from inline content and blocks.
     *
     * A cell holds blocks — a list, a nested table, a quotation — because the
     * engine plans one with the same function it plans a page with.
     */
    public fun cell(
        align: TextAlign = TextAlign.Start,
        verticalAlign: VerticalAlign = VerticalAlign.Top,
        backgroundColor: Rgb? = null,
        block: CellScope.() -> Unit,
    ) {
        val scope = CellScope(align)
        scope.block()
        cells.add(
            TableCell(
                content = scope.build(),
                align = align,
                verticalAlign = verticalAlign,
                background = backgroundColor,
            ),
        )
    }

    /** An already-built cell — the entry point a converter uses. */
    public fun add(cell: TableCell) {
        cells.add(cell)
    }

    internal fun build(): List<TableCell> = cells.toList()
}

/**
 * One cell's content. Every block builder of [BlockSink] applies.
 *
 * The cell's alignment is the one its implicit paragraph is set in, so
 * `cell(align = TextAlign.Right) { +"3" }` is a right-aligned number without the
 * caller saying it twice.
 */
public class CellScope internal constructor(align: TextAlign) : BlockContainerScope(align)

/** The table a `table { }` describes. */
internal fun buildTable(block: TableScope.() -> Unit): TableBlock {
    val scope = TableScope()
    scope.block()
    return scope.build()
}

private fun collectCells(block: RowScope.() -> Unit): List<TableCell> {
    val scope = RowScope()
    scope.block()
    return scope.build()
}
