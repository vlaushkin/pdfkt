/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.fragment

import io.pdfkt.core.block.TableBorderStyle
import io.pdfkt.core.block.TableColumns
import io.pdfkt.core.block.VerticalAlign
import io.pdfkt.core.font.FontCatalog
import io.pdfkt.core.font.FontRef
import io.pdfkt.core.font.Standard14Name
import io.pdfkt.core.model.Insets
import io.pdfkt.core.model.Pt
import io.pdfkt.core.model.Rgb
import io.pdfkt.core.model.Size
import io.pdfkt.core.model.pt
import io.pdfkt.core.plan.PagePlan
import io.pdfkt.core.plan.PlacedItem
import io.pdfkt.core.resolve.StyledCell
import io.pdfkt.core.resolve.StyledList
import io.pdfkt.core.resolve.StyledListItem
import io.pdfkt.core.resolve.StyledParagraph
import io.pdfkt.core.resolve.StyledTable
import io.pdfkt.core.resolve.StyledTextRun
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

// No clause applies: L4 is this library's own design. What is asserted is the plan —
// coordinates, order and how far a table reached — which is what planning
// before drawing is for.

/**
 * Tables against the two measuring passes and the splitting rule:
 *
 * > 1. column widths from the requested mode (fixed, equal, fractional)
 * > 2. row heights by planning each cell's content at its column width and
 * >    taking the tallest
 * >
 * > Then rows are placed, splitting across pages and repeating header rows.
 * > Cell content is planned by the same fragmenter, so a cell can hold any
 * > block — including another table — without a special path.
 *
 * > **Tables** speak the same `FitResult` protocol: `Splits` between rows, never
 * > inside a row; the repeated header rows are part of every `head`.
 *
 * `TableRenderTest` makes the same claims about a real file, read back by a text
 * extractor that never saw this library.
 */
class TableTest {
    // -------------------------------------------------- pass one: the widths

    /** Equal columns are the width divided between them, and the cells start at those edges. */
    @Test
    fun `equal columns divide the width between them`() {
        val table = table(TableColumns.Equal(THREE), listOf(listOf("a", "b", "c")))

        val fit = assertIs<FitResult.Fits>(planner().planBlock(table, COLUMN, ROOM, atPageTop = true))

        assertContentEquals(
            listOf(PADDING.value, 100.0f + PADDING.value, 200.0f + PADDING.value),
            fit.plan.filterIsInstance<PlacedItem.Text>().map { text -> text.x.value },
        )
    }

    /** Fractional columns are in the proportions asked for: weight 3 is three times weight 1. */
    @Test
    fun `fractional columns take the width in the proportions asked for`() {
        val table = table(TableColumns.Fractional(listOf(1.0f, THREE.toFloat())), listOf(listOf("a", "b")))

        val fit = assertIs<FitResult.Fits>(planner().planBlock(table, COLUMN, ROOM, atPageTop = true))

        assertContentEquals(
            listOf(PADDING.value, 75.0f + PADDING.value),
            fit.plan.filterIsInstance<PlacedItem.Text>().map { text -> text.x.value },
        )
    }

    /** Fixed columns are exactly the widths given, and the table is only as wide as their sum. */
    @Test
    fun `fixed columns are the widths the caller gave`() {
        val table = table(TableColumns.Fixed(listOf(80.pt, 120.pt)), listOf(listOf("a", "b")))

        val fit = assertIs<FitResult.Fits>(planner().planBlock(table, COLUMN, ROOM, atPageTop = true))

        assertContentEquals(
            listOf(PADDING.value, 80.0f + PADDING.value),
            fit.plan.filterIsInstance<PlacedItem.Text>().map { text -> text.x.value },
        )
        val right = fit.plan.filterIsInstance<PlacedItem.Rect>().maxOf { rect -> rect.box.right.value }
        assertTrue(right < COLUMN.value, "a 200pt table was drawn out to $right in a ${COLUMN.value}pt column")
    }

    /**
     * Fixed widths that do not fit are refused rather than scaled down: a length
     * the caller fixed is not this library's to change.
     */
    @Test
    fun `fixed columns wider than the space are refused rather than scaled`() {
        val table = table(TableColumns.Fixed(listOf(200.pt, 200.pt)), listOf(listOf("a", "b")))

        val refused = assertFailsWith<UnplaceableBlockException> {
            planner().planBlock(table, COLUMN, ROOM, atPageTop = true)
        }

        assertTrue(refused.message!!.contains("Fractional"), "the refusal did not name the remedy: $refused")
    }

    /** A row with a cell in no column is refused where it is written, not reflowed. */
    @Test
    fun `a row with the wrong number of cells is refused`() {
        assertFailsWith<IllegalArgumentException> {
            StyledTable(columns = TableColumns.Equal(THREE), rows = listOf(listOf(cell("only"))))
        }
    }

    // ------------------------------------------------- pass two: the heights

    /** A row is as tall as the tallest cell in it, plus the padding above and below. */
    @Test
    fun `a row is as tall as its tallest cell`() {
        val short = table(TableColumns.Equal(2), listOf(listOf("one", "one")))
        val tall = StyledTable(
            columns = TableColumns.Equal(2),
            rows = listOf(listOf(cell("one"), StyledCell(listOf(paragraph("one"), paragraph("two"))))),
            cellPadding = PADDING,
        )

        val shortFit = assertIs<FitResult.Fits>(planner().planBlock(short, COLUMN, ROOM, atPageTop = true))
        val tallFit = assertIs<FitResult.Fits>(planner().planBlock(tall, COLUMN, ROOM, atPageTop = true))

        val line = tallFit.consumed - shortFit.consumed
        assertTrue(line > Pt.ZERO, "a cell of two paragraphs made no taller a row than a cell of one")
        assertEquals(
            shortFit.consumed.value + line.value,
            tallFit.consumed.value,
            TOLERANCE,
            "the row was not measured by its tallest cell",
        )
    }

    /** The padding of a cell is space on all four sides of its content, inside its column. */
    @Test
    fun `cell padding is taken off every side`() {
        val padded = table(TableColumns.Equal(1), listOf(listOf("text")), padding = 10.pt)
        val bare = table(TableColumns.Equal(1), listOf(listOf("text")), padding = Pt.ZERO)

        val paddedFit = assertIs<FitResult.Fits>(planner().planBlock(padded, COLUMN, ROOM, atPageTop = true))
        val bareFit = assertIs<FitResult.Fits>(planner().planBlock(bare, COLUMN, ROOM, atPageTop = true))

        assertEquals(bareFit.consumed.value + 20.0f, paddedFit.consumed.value, TOLERANCE)
        assertEquals(
            bareFit.plan.filterIsInstance<PlacedItem.Text>().single().x.value + 10.0f,
            paddedFit.plan.filterIsInstance<PlacedItem.Text>().single().x.value,
            TOLERANCE,
        )
    }

    /** A cell shorter than its row sits where its vertical alignment says, and only then. */
    @Test
    fun `vertical alignment moves a short cell down its row`() {
        val baselines = listOf(VerticalAlign.Top, VerticalAlign.Middle, VerticalAlign.Bottom).map { align ->
            val table = StyledTable(
                columns = TableColumns.Equal(2),
                rows = listOf(
                    listOf(
                        StyledCell(listOf(paragraph("short")), verticalAlign = align),
                        StyledCell(listOf(paragraph("one"), paragraph("two"), paragraph("three"))),
                    ),
                ),
                cellPadding = PADDING,
            )
            val fit = assertIs<FitResult.Fits>(planner().planBlock(table, COLUMN, ROOM, atPageTop = true))
            fit.plan.filterIsInstance<PlacedItem.Text>().first { text -> textOf(text) == "short" }.y.value
        }

        assertTrue(baselines[0] > baselines[1], "a middle-aligned cell did not move down from the top")
        assertTrue(baselines[1] > baselines[2], "a bottom-aligned cell did not move down from the middle")
    }

    // ------------------------------------------------------- the split

    /** A table too long for the page continues on the next, with every cell placed once. */
    @Test
    fun `a long table splits across pages with every cell kept once`() {
        val rows = (1..TWENTY).map { number -> listOf("r${number}c1", "r${number}c2") }

        val pages = fragmenter().fragment(listOf(table(TableColumns.Equal(2), rows)))

        assertTrue(pages.size >= 2, "twenty rows fitted one page")
        assertContentEquals(rows.flatten(), pages.flatMap { page -> textsOf(page) })
    }

    /**
     * The cut falls between rows and never inside one: both cells of a row are
     * always on the same page as each other.
     */
    @Test
    fun `a table is cut between rows and never through one`() {
        val rows = (1..TWENTY).map { number -> listOf("r${number}c1", "r${number}c2") }

        val pages = fragmenter().fragment(listOf(table(TableColumns.Equal(2), rows)))

        for (number in 1..TWENTY) {
            assertEquals(
                pages.indexOfFirst { page -> "r${number}c1" in textsOf(page) },
                pages.indexOfFirst { page -> "r${number}c2" in textsOf(page) },
                "row $number was cut in half across a page boundary",
            )
        }
    }

    /** A row that does not fit what is left of the page moves whole to the next one. */
    @Test
    fun `a table whose next row does not fit answers Splits between the rows`() {
        val table = table(TableColumns.Equal(1), (1..TWENTY).map { number -> listOf("row $number") })

        val fit = planner().planBlock(table, COLUMN, HALF_A_PAGE, atPageTop = false)

        val splits = assertIs<FitResult.Splits>(fit)
        val tail = assertIs<StyledTable>(splits.tail.single())
        assertTrue(splits.consumed <= HALF_A_PAGE, "the head took ${splits.consumed} out of $HALF_A_PAGE")
        assertTrue(tail.rows.isNotEmpty() && tail.rows.size < TWENTY, "the tail holds ${tail.rows.size} rows")
        assertEquals(TWENTY, splits.head.filterIsInstance<PlacedItem.Text>().size + tail.rows.size)
    }

    /** A single row taller than a whole page is refused rather than cut through. */
    @Test
    fun `a row taller than the page is refused rather than split`() {
        val text = (1..200).joinToString(separator = " ") { number -> "word$number" }
        val table = table(TableColumns.Equal(1), listOf(listOf(text)))

        val refused = assertFailsWith<UnplaceableBlockException> { fragmenter().fragment(listOf(table)) }

        assertTrue(refused.message!!.contains("between"), "the refusal did not say why: $refused")
    }

    // ------------------------------------------------------- the header

    /** The header is part of every head: it is drawn again at the top of each page the table reaches. */
    @Test
    fun `a repeated header is drawn on every page the table spans`() {
        val rows = (1..TWENTY).map { number -> listOf("r${number}c1", "r${number}c2") }
        val table = table(TableColumns.Equal(2), rows, header = listOf("H1", "H2"))

        val pages = fragmenter().fragment(listOf(table))

        assertTrue(pages.size >= 2, "a table of twenty rows and a header fitted one page")
        for ((index, page) in pages.withIndex()) {
            assertContentEquals(
                listOf("H1", "H2"),
                textsOf(page).take(2),
                "page ${index + 1} does not begin with the header",
            )
        }
    }

    /** A header that is not to be repeated is drawn once, on the part the table starts in. */
    @Test
    fun `a header that does not repeat is drawn once`() {
        val rows = (1..TWENTY).map { number -> listOf("r${number}c1", "r${number}c2") }
        val table = StyledTable(
            columns = TableColumns.Equal(2),
            rows = rows.map { row -> row.map { text -> cell(text) } },
            header = listOf(cell("H1"), cell("H2")),
            repeatHeader = false,
            cellPadding = PADDING,
        )

        val pages = fragmenter().fragment(listOf(table))

        assertTrue(pages.size >= 2, "a table of twenty rows fitted one page")
        assertEquals(1, pages.sumOf { page -> textsOf(page).count { text -> text == "H1" } })
        assertContentEquals(listOf("H1", "H2"), textsOf(pages.first()).take(2))
    }

    /**
     * A header is never left alone at the foot of a page: with room for the
     * header and not for the first row, the whole table moves on.
     */
    @Test
    fun `a header is not left behind by its first row`() {
        val table = table(TableColumns.Equal(1), listOf(listOf("row")), header = listOf("head"))
        val whole = assertIs<FitResult.Fits>(planner().planBlock(table, COLUMN, ROOM, atPageTop = true))
        val headerOnly = whole.consumed / 2.0f

        assertEquals(
            FitResult.Defers,
            planner().planBlock(table, COLUMN, headerOnly + Pt(TOLERANCE), atPageTop = false),
            "the header was placed at the foot of a page with none of its rows",
        )
    }

    // ----------------------------------------------------- what is in a cell

    /**
     * The core guarantee: a cell holding a bullet list and a nested
     * table is planned by the same fragmenter as everything else. Nothing in the
     * table planner knows what either of them is.
     */
    @Test
    fun `a cell holding a list and a nested table is planned by the same fragmenter`() {
        val inner = table(TableColumns.Equal(2), listOf(listOf("inner one", "inner two")))
        val list = StyledList(
            items = (1..2).map { number ->
                StyledListItem(marker = run("•"), indent = 10.pt, content = listOf(paragraph("item $number")))
            },
        )
        val outer = StyledTable(
            columns = TableColumns.Equal(2),
            rows = listOf(listOf(StyledCell(listOf(list, inner)), cell("beside"))),
            cellPadding = PADDING,
        )

        val pages = fragmenter().fragment(listOf(outer))

        assertContentEquals(
            listOf("•", "item 1", "•", "item 2", "inner one", "inner two", "beside"),
            textsOf(pages.single()),
        )
    }

    /** A nested table is drawn inside its cell, padding and all — not over the cell beside it. */
    @Test
    fun `a nested table stays inside the cell that holds it`() {
        val inner = table(TableColumns.Equal(1), listOf(listOf("inner")))
        val outer = StyledTable(
            columns = TableColumns.Equal(2),
            rows = listOf(listOf(StyledCell(listOf(inner)), cell("beside"))),
            cellPadding = PADDING,
        )

        val fit = assertIs<FitResult.Fits>(planner().planBlock(outer, COLUMN, ROOM, atPageTop = true))

        val nested = fit.plan.filterIsInstance<PlacedItem.Text>().first { text -> textOf(text) == "inner" }
        assertTrue(nested.x > PADDING, "the nested table's text is not inside the padding of its cell")
        assertTrue(nested.x < COLUMN / 2.0f, "the nested table's text spilled into the column beside it")
    }

    // ------------------------------------------------------------ the ink

    /** A table with no borders draws no rules — and a table with them draws its own outline. */
    @Test
    fun `rules are drawn on the boundaries and nowhere when there are none`() {
        val rows = listOf(listOf("a", "b"), listOf("c", "d"))
        val bare = StyledTable(
            columns = TableColumns.Equal(2),
            rows = rows.map { row -> row.map { text -> cell(text) } },
            borders = TableBorderStyle.None,
            cellPadding = PADDING,
        )
        val ruled = table(TableColumns.Equal(2), rows)

        val bareFit = assertIs<FitResult.Fits>(planner().planBlock(bare, COLUMN, ROOM, atPageTop = true))
        val ruledFit = assertIs<FitResult.Fits>(planner().planBlock(ruled, COLUMN, ROOM, atPageTop = true))

        assertEquals(0, bareFit.plan.filterIsInstance<PlacedItem.Rect>().size)
        assertEquals(bareFit.consumed, ruledFit.consumed, "the rules took height out of the rows")
        // Four outer rules, one vertical between the columns, one horizontal
        // between the rows.
        assertEquals(6, ruledFit.plan.filterIsInstance<PlacedItem.Rect>().size)
    }

    /** A rule is centred on the boundary it marks, as a stroked path is (§8.4.3.2). */
    @Test
    fun `the outer rule is centred on the edge of the table`() {
        val table = table(TableColumns.Equal(1), listOf(listOf("a")))

        val fit = assertIs<FitResult.Fits>(planner().planBlock(table, COLUMN, ROOM, atPageTop = true))

        val top = fit.plan.filterIsInstance<PlacedItem.Rect>().maxByOrNull { rect -> rect.box.top }!!
        val outer = TableBorderStyle.Default.outerWidth
        assertEquals(outer.value / 2.0f, top.box.top.value, TOLERANCE)
        assertEquals(outer.value, top.box.height.value, TOLERANCE)
    }

    /** A cell's background covers the whole cell — its padding as well as its text. */
    @Test
    fun `a cell background covers the cell it belongs to`() {
        val table = StyledTable(
            columns = TableColumns.Equal(2),
            rows = listOf(listOf(StyledCell(listOf(paragraph("tinted")), background = Rgb.gray(0.9f)), cell("plain"))),
            borders = TableBorderStyle.None,
            cellPadding = PADDING,
        )

        val fit = assertIs<FitResult.Fits>(planner().planBlock(table, COLUMN, ROOM, atPageTop = true))

        val panel = fit.plan.filterIsInstance<PlacedItem.Rect>().single()
        assertEquals(Pt.ZERO, panel.box.x)
        assertEquals(COLUMN.value / 2.0f, panel.box.width.value, TOLERANCE)
        assertEquals(fit.consumed.value, panel.box.height.value, TOLERANCE)
        assertEquals(0, fit.plan.indexOf(panel), "a background was drawn after the text of another cell")
    }

    // ------------------------------------------------------------ fixtures

    private fun planner(): BlockPlanner = BlockPlanner(FontCatalog())

    private fun fragmenter(): Fragmenter = Fragmenter(FontCatalog(), template())

    private fun template(): PageTemplate = PageTemplate.of(Size(340.pt, 260.pt), Insets.uniform(20.pt))

    private fun table(
        columns: TableColumns,
        rows: List<List<String>>,
        header: List<String>? = null,
        padding: Pt = PADDING,
    ): StyledTable = StyledTable(
        columns = columns,
        rows = rows.map { row -> row.map { text -> cell(text) } },
        header = header?.map { text -> cell(text) },
        cellPadding = padding,
    )

    private fun cell(text: String): StyledCell = StyledCell(listOf(paragraph(text)))

    private fun paragraph(text: String): StyledParagraph = StyledParagraph(listOf(run(text)), lineHeight = 1.2f)

    private fun run(text: String): StyledTextRun =
        StyledTextRun(text, FontRef.Standard14(Standard14Name.HELVETICA), 12.pt, Rgb.BLACK)

    private fun textOf(text: PlacedItem.Text): String = text.runs.joinToString(separator = "") { run -> run.text }

    private fun textsOf(page: PagePlan): List<String> =
        page.items.filterIsInstance<PlacedItem.Text>().map { text -> textOf(text) }

    private companion object {
        const val THREE: Int = 3
        const val TWENTY: Int = 20

        val COLUMN: Pt = 300.pt
        val ROOM: Pt = 400.pt
        val HALF_A_PAGE: Pt = 110.pt
        val PADDING: Pt = 4.pt

        const val TOLERANCE: Float = 1.0e-2f
    }
}
