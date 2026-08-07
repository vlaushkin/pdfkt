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
import io.pdfkt.core.block.ListItem
import io.pdfkt.core.block.ListMarkerStyle
import io.pdfkt.core.block.ParagraphBlock
import io.pdfkt.core.block.TextRun
import io.pdfkt.core.model.pt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `bulletList { }` and `numberedList { }` against the lists they build.
 *
 * What a marker *reads* is not asked here: marker text is the engine's,
 * and asking the
 * same question of two implementations is how they come to disagree.
 */
class ListScopeTest {

    @Test
    fun aListNestsInsideItsOwnItems() {
        val document = document {
            bulletList {
                item { +"Revenue: +12%" }
                item {
                    +"Departments"
                    bulletList {
                        item { +"Sales — 30%" }
                        item { +"Engineering — 50%" }
                    }
                }
            }
        }

        val list = document.blocks.single() as ListBlock
        assertFalse(list.ordered)
        assertEquals(ListMarkerStyle.Bullet, list.markerStyle)
        assertEquals(18.pt, list.indentPerLevel)
        assertEquals(2, list.items.size)

        val first = list.items[0].content.single() as ParagraphBlock
        assertEquals(listOf(TextRun("Revenue: +12%")), first.content)

        val second = list.items[1].content
        assertEquals(2, second.size, "the inline text closes into a paragraph before the nested list")
        assertEquals(listOf(TextRun("Departments")), (second[0] as ParagraphBlock).content)

        val nested = second[1] as ListBlock
        assertEquals(2, nested.items.size)
        assertEquals(
            listOf(TextRun("Sales — 30%")),
            (nested.items[0].content.single() as ParagraphBlock).content,
        )
    }

    @Test
    fun listsNestFourDeepAndEachKeepsItsOwnStyle() {
        val document = document {
            bulletList {
                item {
                    +"L1"
                    numberedList {
                        item {
                            +"L2"
                            bulletList(markerStyle = ListMarkerStyle.Square) {
                                item {
                                    +"L3"
                                    numberedList(markerStyle = ListMarkerStyle.LowerRoman) {
                                        item("L4")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        val level1 = document.blocks.single() as ListBlock
        val level2 = level1.items.single().content[1] as ListBlock
        val level3 = level2.items.single().content[1] as ListBlock
        val level4 = level3.items.single().content[1] as ListBlock

        assertEquals(ListMarkerStyle.Bullet, level1.markerStyle)
        assertEquals(ListMarkerStyle.Decimal, level2.markerStyle)
        assertEquals(ListMarkerStyle.Square, level3.markerStyle)
        assertEquals(ListMarkerStyle.LowerRoman, level4.markerStyle)

        assertTrue(level2.ordered)
        assertFalse(level3.ordered)

        assertEquals(listOf(TextRun("L1")), (level1.items.single().content[0] as ParagraphBlock).content)
        assertEquals(listOf(TextRun("L4")), (level4.items.single().content.single() as ParagraphBlock).content)
    }

    @Test
    fun anItemHoldsParagraphsAndAlreadyBuiltBlocks() {
        val document = document {
            numberedList(startNumber = 3, markerStyle = ListMarkerStyle.UpperAlpha) {
                item {
                    +"lead-in"
                    paragraph("second paragraph")
                    add(ParagraphBlock(content = listOf(TextRun("third paragraph"))))
                }
                add(ListItem(listOf(ParagraphBlock(content = listOf(TextRun("prebuilt item"))))))
            }
        }

        val list = document.blocks.single() as ListBlock
        assertTrue(list.ordered)
        assertEquals(3, list.startNumber)
        assertEquals(ListMarkerStyle.UpperAlpha, list.markerStyle)

        val content = list.items[0].content
        assertEquals(3, content.size)
        assertEquals(listOf(TextRun("lead-in")), (content[0] as ParagraphBlock).content)
        assertEquals(listOf(TextRun("second paragraph")), (content[1] as ParagraphBlock).content)
        assertEquals(listOf(TextRun("third paragraph")), (content[2] as ParagraphBlock).content)

        assertEquals(
            listOf(TextRun("prebuilt item")),
            (list.items[1].content.single() as ParagraphBlock).content,
        )
    }

    @Test
    fun anIndentPerLevelOfTheCallersOwnIsCarriedThrough() {
        val list = document { bulletList(indentPerLevel = 24.pt) { item("x") } }
            .blocks
            .single() as ListBlock

        assertEquals(24.pt, list.indentPerLevel)
    }

    @Test
    fun aMarkerStyleTheBuilderCannotCountWithIsRefused() {
        // Neither builder guesses: a counted style in bulletList and an uncounted
        // one in numberedList are each a caller who wanted the other builder, and
        // drawing the wrong marker silently is what the message exists to avoid.
        val counted = assertFailsWith<IllegalArgumentException> {
            document { bulletList(markerStyle = ListMarkerStyle.Decimal) { item("x") } }
        }
        assertTrue(
            counted.message.orEmpty().contains("numberedList"),
            "the message should name the builder that does count, got: ${counted.message}",
        )

        val uncounted = assertFailsWith<IllegalArgumentException> {
            document { numberedList(markerStyle = ListMarkerStyle.Bullet) { item("x") } }
        }
        assertTrue(
            uncounted.message.orEmpty().contains("bulletList"),
            "the message should name the builder that does not count, got: ${uncounted.message}",
        )
    }

    @Test
    fun aListNumberedFromBeforeItsFirstItemIsRefused() {
        assertFailsWith<IllegalArgumentException> {
            document { numberedList(startNumber = 0) { item("x") } }
        }
    }

    @Test
    fun theTwoBuildersSplitTheMarkerStylesBetweenThem() {
        assertTrue(ListMarkerStyle.Decimal.isOrdered)
        assertTrue(ListMarkerStyle.LowerAlpha.isOrdered)
        assertTrue(ListMarkerStyle.UpperAlpha.isOrdered)
        assertTrue(ListMarkerStyle.LowerRoman.isOrdered)
        assertTrue(ListMarkerStyle.UpperRoman.isOrdered)
        assertFalse(ListMarkerStyle.Bullet.isOrdered)
        assertFalse(ListMarkerStyle.Dash.isOrdered)
        assertFalse(ListMarkerStyle.Circle.isOrdered)
        assertFalse(ListMarkerStyle.Square.isOrdered)
    }
}
