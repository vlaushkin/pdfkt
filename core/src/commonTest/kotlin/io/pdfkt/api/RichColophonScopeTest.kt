/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.api

import io.pdfkt.core.block.HorizontalRuleBlock
import io.pdfkt.core.block.ParagraphBlock
import io.pdfkt.core.block.StyledRun
import io.pdfkt.core.block.TextAlign
import io.pdfkt.core.block.TextRun
import io.pdfkt.core.doc.BandSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// No clause applies: a colophon written as blocks is this library's own design.
// The band
// it lowers to is `:core`'s BandSpec, whose rules are asserted there.

/**
 * `header { }` and `footer { }`, the block forms — what they build, and for which
 * page.
 *
 * A colophon is a container of blocks like any other, and the point of the form
 * is that a running head may be more than one line of theme-set text: a bold
 * title, a rule under it, a table of two cells. What is asserted here is the band
 * the scope hands the seam, page by page; that the marks land in the strip and on
 * the side they were given is `RichColophonBlockGateTest`'s, on paper.
 *
 * The two properties the form adds over `headerBand { }` are that the blocks are
 * written in the document's own vocabulary, and that the scope knows which page
 * it is writing — `numbering`, and the `pageOfPages()` built out of it.
 */
class RichColophonScopeTest {

    @Test
    fun aBlockHeadIsTheBlocksItWasWritten() {
        val spec = document {
            clock = PINNED_CLOCK
            header {
                paragraph { text(HEAD) { bold = true } }
                hr()
            }
            paragraph(BODY)
        }.toDocumentSpec()

        val head = assertNotNull(spec.header)
        assertEquals(DEFAULT_BAND_HEIGHT, head.height, "the same strip a colophon gets when nobody says")
        assertEquals(BandPages.All, head.pages)

        val blocks = head.content(PageNumbering(page = 1, pages = 3))
        assertEquals(2, blocks.size, "the head was written as a paragraph and a rule, and is $blocks")
        val line = blocks.first() as ParagraphBlock
        assertEquals(
            listOf(StyledRun(listOf(TextRun(HEAD)), bold = true)),
            line.content,
            "the run the property form built did not reach the band",
        )
        assertTrue(blocks[1] is HorizontalRuleBlock, "the rule under the title is not in the band")
    }

    /**
     * Head and foot behave the same: the *block* forms set their
     * content flush left, foot as well as head. The text forms keep their
     * left/right split, which the test beside this one holds.
     */
    @Test
    fun bothBlockFormsAreFlushLeftAndTheTextFormsKeepTheirOwnSides() {
        val spec = document {
            clock = PINNED_CLOCK
            header { +HEAD }
            footer { +FOOT }
            paragraph(BODY)
        }.toDocumentSpec()

        assertEquals(TextAlign.Left, assertNotNull(spec.header).lineOn(page = 1, of = 1).align)
        assertEquals(
            TextAlign.Left,
            assertNotNull(spec.footer).lineOn(page = 1, of = 1).align,
            "a block foot has no side of its own to prefer; the text form's Right does not carry over",
        )

        val text = document {
            clock = PINNED_CLOCK
            header(HEAD)
            footer(FOOT)
            paragraph(BODY)
        }.toDocumentSpec()

        assertEquals(TextAlign.Left, assertNotNull(text.header).lineOn(page = 1, of = 1).align)
        assertEquals(TextAlign.Right, assertNotNull(text.footer).lineOn(page = 1, of = 1).align)
    }

    /** A paragraph inside the block says its own side, and that is how a page number goes right. */
    @Test
    fun aParagraphInsideTheBlockSaysItsOwnSide() {
        val spec = document {
            clock = PINNED_CLOCK
            footer { paragraph(pageOfPages(), align = TextAlign.Right) }
            paragraph(BODY)
        }.toDocumentSpec()

        val foot = assertNotNull(spec.footer)
        assertEquals(TextAlign.Right, foot.lineOn(page = 1, of = 12).align)
    }

    /**
     * The colophon's own members inside a nested `paragraph { }`, reached through
     * the label of the lambda they belong to.
     *
     * The DSL marker hides the outer receiver there, which is what stops a cell
     * from being written into the paragraph around it; `this@footer` is the way
     * through, and this holds the spelling `ColophonScope.pageOfPages` documents.
     */
    @Test
    fun theColophonsOwnMembersAreReachableInsideAParagraphThroughItsLabel() {
        val spec = document {
            clock = PINNED_CLOCK
            footer { paragraph(align = TextAlign.Right) { bold(this@footer.pageOfPages()) } }
            paragraph(BODY)
        }.toDocumentSpec()

        val line = assertNotNull(spec.footer).lineOn(page = 2, of = 5)
        assertEquals(listOf(StyledRun(listOf(TextRun("2 / 5")), bold = true)), line.content)
        assertEquals(TextAlign.Right, line.align)
    }

    @Test
    fun theBlockIsWrittenAfreshForEveryPageAndKnowsWhichOne() {
        val spec = document {
            clock = PINNED_CLOCK
            footer { paragraph(pageOfPages(), align = TextAlign.Right) }
            header { paragraph("$HEAD — ${numbering.page}/${numbering.pages}") }
            paragraph(BODY)
        }.toDocumentSpec()

        val foot = assertNotNull(spec.footer)
        assertEquals(listOf(TextRun("1 / 12")), foot.lineOn(page = 1, of = 12).content)
        assertEquals(listOf(TextRun("7 / 12")), foot.lineOn(page = 7, of = 12).content)
        assertEquals(listOf(TextRun("12 / 12")), foot.lineOn(page = 12, of = 12).content)

        val head = assertNotNull(spec.header)
        assertEquals(listOf(TextRun("$HEAD — 1/12")), head.lineOn(page = 1, of = 12).content)
        assertEquals(listOf(TextRun("$HEAD — 12/12")), head.lineOn(page = 12, of = 12).content)
    }

    /** The separator is the caller's; the two numbers are not. */
    @Test
    fun pageOfPagesPutsWhatTheCallerAsksForBetweenTheNumbers() {
        val spec = document {
            clock = PINNED_CLOCK
            footer { paragraph(pageOfPages(separator = " из ")) }
            paragraph(BODY)
        }.toDocumentSpec()

        assertEquals(listOf(TextRun("3 из 9")), assertNotNull(spec.footer).lineOn(page = 3, of = 9).content)
    }

    @Test
    fun aBlockColophonTakesAPageRuleAndAStripLikeAnyOther() {
        val spec = document {
            clock = PINNED_CLOCK
            header(height = TALL_STRIP, pages = BandPages.ExceptFirst) { +HEAD }
            footer(height = TALL_STRIP, pages = BandPages.FirstOnly) { +FOOT }
            paragraph(BODY)
        }.toDocumentSpec()

        val head = assertNotNull(spec.header)
        assertEquals(TALL_STRIP, head.height)
        assertEquals(BandPages.ExceptFirst, head.pages)

        val foot = assertNotNull(spec.footer)
        assertEquals(TALL_STRIP, foot.height)
        assertEquals(BandPages.FirstOnly, foot.pages)
    }

    /** One head per document, whichever of the three spellings described it last. */
    @Test
    fun theLastColophonWinsAcrossTheBlockFormToo() {
        val spec = document {
            clock = PINNED_CLOCK
            header(HEAD)
            header { paragraph(TITLE) }

            footer { paragraph(FOOT) }
            footer(FOOT, align = TextAlign.Center)
            paragraph(BODY)
        }.toDocumentSpec()

        assertEquals(listOf(TextRun(TITLE)), assertNotNull(spec.header).lineOn(page = 1, of = 1).content)
        assertEquals(TextAlign.Center, assertNotNull(spec.footer).lineOn(page = 1, of = 1).align)
    }

    /**
     * A block that writes nothing is refused on the page it writes nothing on,
     * rather than drawn as a blank strip.
     *
     * It is the removed `footer { "Page 1" }` overload's landing place: that
     * lambda hands a string back and writes nothing, and `ColophonScope.() -> Unit`
     * would discard it without a word. The band is a function of the page, so the
     * refusal comes when the page is planned.
     */
    @Test
    fun aColophonThatWritesNothingIsRefused() {
        val spec = document {
            clock = PINNED_CLOCK
            @Suppress("UNUSED_EXPRESSION")
            footer { "Page ${numbering.page}" }
            paragraph(BODY)
        }.toDocumentSpec()

        val refusal = assertFailsWith<IllegalArgumentException> {
            assertNotNull(spec.footer).content(PageNumbering(page = 1, pages = 1))
        }
        assertTrue(
            "page 1 of 1" in (refusal.message ?: ""),
            "the refusal should say which page came out blank, and says: ${refusal.message}",
        )
    }

    /** The one paragraph this band draws on page [page] of a document of [of] pages. */
    private fun BandSpec.lineOn(page: Int, of: Int): ParagraphBlock =
        content(PageNumbering(page = page, pages = of)).single() as ParagraphBlock

    private companion object {
        const val HEAD = "Q1 Report"
        const val TITLE = "Отчёт за IV квартал 2026"
        const val FOOT = "Draft"
        const val BODY = "body"

        val TALL_STRIP: Pt = 40.pt
    }
}
