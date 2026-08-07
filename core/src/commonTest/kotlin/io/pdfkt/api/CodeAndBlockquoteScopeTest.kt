/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.api

import io.pdfkt.core.block.BlockquoteBlock
import io.pdfkt.core.block.CodeBlock
import io.pdfkt.core.block.HeadingBlock
import io.pdfkt.core.block.ParagraphBlock
import io.pdfkt.core.block.TextRun
import io.pdfkt.core.model.Rgb
import io.pdfkt.core.model.pt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

/** `code()` / `codeBlock()` and `blockquote { }` against the blocks they build. */
class CodeAndBlockquoteScopeTest {

    @Test
    fun codeLeavesToTheThemeEverythingItWasNotTold() {
        val blocks = document { code("val x = 1") }.blocks

        assertEquals(1, blocks.size)
        val code = assertIs<CodeBlock>(blocks[0])
        assertEquals("val x = 1", code.text)
        // Null is not "no panel" and not "11pt": it is "whatever the theme says",
        // resolved when the document is rendered rather than captured when the
        // block was written.
        assertNull(code.background)
        assertNull(code.fontSize)
        assertNull(code.font)
        assertEquals(6.pt, code.padding)
    }

    @Test
    fun codeTakesABackgroundASizeAndAPaddingOfItsOwn() {
        val blocks = document {
            code("SELECT 1", backgroundColor = Rgb.BLACK, fontSize = 8.pt, padding = 2.pt)
        }.blocks

        val code = assertIs<CodeBlock>(blocks[0])
        assertEquals(Rgb.BLACK, code.background)
        assertEquals(8.pt, code.fontSize)
        assertEquals(2.pt, code.padding)
    }

    @Test
    fun theContainersSpellTheCodeBuilderCodeBlock() {
        // `code` is the inline span in every scope that takes inline content, so
        // the block-level builder is named apart from it — the document, which is
        // not an inline sink, keeps both spellings.
        val blocks = document { blockquote { codeBlock("val x = 1") } }.blocks

        val quote = assertIs<BlockquoteBlock>(blocks[0])
        assertEquals("val x = 1", assertIs<CodeBlock>(quote.children.single()).text)
    }

    @Test
    fun blockquoteLeavesItsBarColourToTheThemeAndSetsItsOwnGeometry() {
        val blocks = document { blockquote { +"Quoted" } }.blocks

        val quote = assertIs<BlockquoteBlock>(blocks[0])
        assertNull(quote.barColor, "an unstated bar colour is the theme's")
        assertEquals(3.pt, quote.barWidth)
        assertEquals(12.pt, quote.indentLeft)
        assertEquals(listOf(ParagraphBlock(content = listOf(TextRun("Quoted")))), quote.children)
    }

    @Test
    fun blockquoteClosesItsLeadingParagraphBeforeANestedBlock() {
        val blocks = document {
            blockquote {
                +"Leading "
                text("text")
                paragraph("Second paragraph")
                heading(level = 3, "Quoted heading")
                add(ParagraphBlock(content = listOf(TextRun("Converted"))))
            }
        }.blocks

        val quote = assertIs<BlockquoteBlock>(blocks[0])
        assertEquals(4, quote.children.size)
        assertEquals(
            ParagraphBlock(content = listOf(TextRun("Leading "), TextRun("text"))),
            quote.children[0],
        )
        assertEquals(ParagraphBlock(content = listOf(TextRun("Second paragraph"))), quote.children[1])
        assertEquals(3, assertIs<HeadingBlock>(quote.children[2]).level)
        assertEquals(ParagraphBlock(content = listOf(TextRun("Converted"))), quote.children[3])
    }

    @Test
    fun aQuotationsBarIsTheCallersToChoose() {
        val blue = Rgb.of(0x0066CC)
        val blocks = document {
            blockquote(barColor = blue, barWidth = 6.pt, indentLeft = 24.pt) { +"Quoted" }
        }.blocks

        val quote = assertIs<BlockquoteBlock>(blocks[0])
        assertEquals(blue, quote.barColor)
        assertEquals(6.pt, quote.barWidth)
        assertEquals(24.pt, quote.indentLeft)
    }

    @Test
    fun aQuotationOfNothingIsRefused() {
        assertFailsWith<IllegalArgumentException> {
            document { blockquote { } }
        }
    }

    @Test
    fun aQuotationRefusesAHeadingLevelThatIsNotOneOfSix() {
        assertFailsWith<IllegalArgumentException> {
            document { blockquote { heading(level = 7, "Too deep") } }
        }
    }

    @Test
    fun decoratedBlocksKeepTheOrderTheyWereWrittenIn() {
        val blocks = document {
            heading(level = 1, "Release notes")
            blockquote { +"Breaking change ahead." }
            code("api.render()")
            paragraph("Done.")
        }.blocks

        assertEquals(4, blocks.size)
        assertIs<HeadingBlock>(blocks[0])
        assertIs<BlockquoteBlock>(blocks[1])
        assertIs<CodeBlock>(blocks[2])
        assertIs<ParagraphBlock>(blocks[3])
    }
}
