/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.api

import io.pdfkt.core.block.HeadingBlock
import io.pdfkt.core.block.HorizontalRuleBlock
import io.pdfkt.core.block.ParagraphBlock
import io.pdfkt.core.model.Rgb
import io.pdfkt.core.model.pt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** `hr()` against the rule it builds. */
class HorizontalRuleScopeTest {

    @Test
    fun anUnstatedRuleIsTheThemes() {
        val blocks = document { hr() }.blocks

        assertEquals(1, blocks.size)
        val rule = assertIs<HorizontalRuleBlock>(blocks[0])
        assertNull(rule.thickness)
        assertNull(rule.color)
        assertNull(rule.spacingBefore)
        assertNull(rule.spacingAfter)
    }

    @Test
    fun aRuleTakesAThicknessAColourAndASpacingOfItsOwn() {
        val red = Rgb.of(0xFF0000)
        val blocks = document {
            hr(thickness = 2.pt, color = red, verticalSpacing = 12.pt)
        }.blocks

        val rule = assertIs<HorizontalRuleBlock>(blocks[0])
        assertEquals(2.pt, rule.thickness)
        assertEquals(red, rule.color)
        // The space a rule asks for is the block's own spacing, above and below,
        // so it collapses against its neighbours' the ordinary way.
        assertEquals(12.pt, rule.spacingBefore)
        assertEquals(12.pt, rule.spacingAfter)
    }

    @Test
    fun aRuleKeepsItsPlaceAmongTheBlocksAroundIt() {
        val blocks = document {
            heading(level = 1, "Quarterly Report")
            hr()
            paragraph("Body")
        }.blocks

        assertEquals(3, blocks.size)
        assertIs<HeadingBlock>(blocks[0])
        assertIs<HorizontalRuleBlock>(blocks[1])
        assertIs<ParagraphBlock>(blocks[2])
    }
}
