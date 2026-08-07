/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.api

import io.pdfkt.core.block.ListBlock
import io.pdfkt.core.block.ParagraphBlock
import io.pdfkt.core.block.TableBlock
import io.pdfkt.core.block.TableBorderStyle
import io.pdfkt.core.block.TableCell
import io.pdfkt.core.block.TableColumns
import io.pdfkt.core.block.TextAlign
import io.pdfkt.core.block.TextRun
import io.pdfkt.core.block.VerticalAlign
import io.pdfkt.core.model.Rgb
import io.pdfkt.core.model.pt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `table { header { cell {} } row { cell {} } }` against the table it builds. */
class TableScopeTest {

    @Test
    fun aTableCarriesItsColumnsHeaderRowsAndDecoration() {
        val document = document {
            table {
                columns = TableColumns.Fractional(listOf(2f, 1f, 1f))
                cellPadding = 6.pt
                borderStyle = TableBorderStyle(outerWidth = 1.pt, innerWidth = 0.5.pt)
                repeatHeader = true

                header {
                    cell("Item", align = TextAlign.Center)
                    cell("Qty", align = TextAlign.Right)
                    cell("Price", align = TextAlign.Right)
                }
                row {
                    cell { +"Apple" }
                    cell(align = TextAlign.Right) { +"3" }
                    cell(align = TextAlign.Right) { +"$1.50" }
                }
                row {
                    cell { +"Pear" }
                    cell(align = TextAlign.Right) { +"7" }
                    cell(align = TextAlign.Right) { +"$2.25" }
                }
            }
        }

        val table = document.blocks.single() as TableBlock
        assertEquals(TableColumns.Fractional(listOf(2f, 1f, 1f)), table.columns)
        assertEquals(6.pt, table.cellPadding)
        assertEquals(1.pt, table.borders.outerWidth)
        assertEquals(0.5.pt, table.borders.innerWidth)
        assertTrue(table.repeatHeader)

        val header = requireNotNull(table.header)
        assertEquals(listOf("Item", "Qty", "Price"), header.map { cell -> cell.plainText() })
        assertEquals(listOf(TextAlign.Center, TextAlign.Right, TextAlign.Right), header.map { cell -> cell.align })

        assertEquals(2, table.rows.size)
        assertEquals(listOf("Apple", "3", "$1.50"), table.rows[0].map { cell -> cell.plainText() })
        assertEquals(listOf("Pear", "7", "$2.25"), table.rows[1].map { cell -> cell.plainText() })
    }

    @Test
    fun equalAndFixedColumnsAreCarriedThrough() {
        val equal = buildTable {
            columns = TableColumns.Equal(2)
            row {
                cell("a")
                cell("b")
            }
        }
        assertEquals(TableColumns.Equal(2), equal.columns)

        val fixed = buildTable {
            columns = TableColumns.Fixed(listOf(120.pt, 60.pt))
            row {
                cell("a")
                cell("b")
            }
        }
        assertEquals(TableColumns.Fixed(listOf(120.pt, 60.pt)), fixed.columns)
    }

    @Test
    fun unstatedColumnsAreEqualOnesInferredFromTheFirstRow() {
        val withoutHeader = buildTable {
            row {
                cell("a")
                cell("b")
                cell("c")
            }
        }
        assertEquals(TableColumns.Equal(3), withoutHeader.columns)
        assertNull(withoutHeader.header)

        val withHeader = buildTable {
            header {
                cell("h1")
                cell("h2")
            }
            row {
                cell("a")
                cell("b")
            }
        }
        assertEquals(TableColumns.Equal(2), withHeader.columns)
    }

    @Test
    fun aCellCarriesItsAlignmentsAndBackground() {
        val table = buildTable {
            columns = TableColumns.Equal(1)
            row {
                cell(
                    align = TextAlign.Center,
                    verticalAlign = VerticalAlign.Middle,
                    backgroundColor = Rgb.gray(0.94f),
                ) { +"centred" }
            }
        }

        val cell = table.rows.single().single()
        assertEquals(TextAlign.Center, cell.align)
        assertEquals(VerticalAlign.Middle, cell.verticalAlign)
        assertEquals(Rgb.gray(0.94f), cell.background)
        // The implicit paragraph is set in the cell's alignment, so a right-aligned
        // number does not have to be said twice.
        assertEquals(TextAlign.Center, (cell.content.single() as ParagraphBlock).align)
    }

    @Test
    fun aCellHoldsBlocksAndNotOnlyRuns() {
        val table = buildTable {
            columns = TableColumns.Equal(1)
            row {
                cell {
                    +"Ingredients"
                    bulletList {
                        item("Flour")
                        item("Water")
                    }
                    paragraph("Serves 4")
                }
            }
        }

        val content = table.rows.single().single().content
        assertEquals(3, content.size)
        assertEquals(listOf(TextRun("Ingredients")), (content[0] as ParagraphBlock).content)
        assertEquals(2, (content[1] as ListBlock).items.size)
        assertEquals(listOf(TextRun("Serves 4")), (content[2] as ParagraphBlock).content)
    }

    @Test
    fun aRowThatIsNotAsWideAsTheTableIsRefused() {
        val tooFew = assertFailsWith<IllegalArgumentException> {
            buildTable {
                columns = TableColumns.Equal(3)
                row {
                    cell("a")
                    cell("b")
                }
            }
        }
        assertTrue(
            tooFew.message.orEmpty().contains("2 cells") && tooFew.message.orEmpty().contains("3 columns"),
            "the message should name both counts, got: ${tooFew.message}",
        )

        val headerMismatch = assertFailsWith<IllegalArgumentException> {
            buildTable {
                columns = TableColumns.Fractional(listOf(1f, 1f))
                header { cell("only one") }
                row {
                    cell("a")
                    cell("b")
                }
            }
        }
        assertTrue(
            headerMismatch.message.orEmpty().contains("header"),
            "the message should name the header row, got: ${headerMismatch.message}",
        )
    }

    @Test
    fun aTableWithNoRowsAndASecondHeaderAreRefused() {
        assertFailsWith<IllegalArgumentException> { buildTable { } }
        assertFailsWith<IllegalArgumentException> {
            buildTable {
                columns = TableColumns.Equal(1)
                header { cell("a") }
                header { cell("b") }
            }
        }
        // A column of no width is one nothing fits in — refused where the columns
        // are written, before there is a table to refuse.
        assertFailsWith<IllegalArgumentException> {
            buildTable {
                columns = TableColumns.Fractional(listOf(1f, 0f))
                row {
                    cell("a")
                    cell("b")
                }
            }
        }
    }

    @Test
    fun anAlreadyBuiltCellIsAcceptedAsItIs() {
        val prebuilt = TableCell(content = listOf(ParagraphBlock(content = listOf(TextRun("converted")))))
        val table = buildTable {
            columns = TableColumns.Equal(1)
            row { add(prebuilt) }
        }

        assertEquals(prebuilt, table.rows.single().single())
    }
}

/** Flattens a cell's paragraphs to their text, for readable assertions. */
internal fun TableCell.plainText(): String = content
    .filterIsInstance<ParagraphBlock>()
    .flatMap { paragraph -> paragraph.content }
    .filterIsInstance<TextRun>()
    .joinToString("") { run -> run.text }
