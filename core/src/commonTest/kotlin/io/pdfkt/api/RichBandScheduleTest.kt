/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.api

import io.pdfkt.core.block.ParagraphBlock
import io.pdfkt.core.block.StyledRun
import io.pdfkt.core.block.TextAlign
import io.pdfkt.core.block.TextRun
import io.pdfkt.core.doc.BandSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// No clause applies: which pages a running head is drawn on and what it says
// there is this library's own design.
// The band this lowers to is `:core`'s BandSpec, whose rules are asserted there.

/**
 * When a colophon is drawn, and what it says on the page it is drawn on.
 *
 * Four cases cover the useful schedules — the same
 * band everywhere, a cover-only band, a band on everything but the cover, and one
 * that differs between the first page and the rest — and without the DSL a caller
 * who wanted any of them out of `document { }` would have to build a
 * `BandSpec` by hand. These are those four through the DSL, plus the one thing a band's
 * content function is really for: a line that reads the page it is drawn on.
 *
 * Asserted against the band the scope hands the seam rather than against a
 * rendered file: a facade's job is to build that, and which page a band with a
 * given rule ends up on is decided in :core, where `BandPages.appliesTo` is
 * tested against the planner.
 */
class RichBandScheduleTest {

    @Test
    fun aColophonWithNoPageRuleIsDrawnOnEveryPage() {
        val spec = document {
            clock = PINNED_CLOCK
            header(HEAD)
            footer(FOOT)
            paragraph(BODY)
        }.toDocumentSpec()

        // The defaults, which must hold: both bands on every
        // page, the head flush left and the foot flush right.
        val head = assertNotNull(spec.header)
        val foot = assertNotNull(spec.footer)
        assertEquals(BandPages.All, head.pages)
        assertEquals(BandPages.All, foot.pages)
        assertEquals(listOf(TextRun(HEAD)), head.lineOn(page = 1, of = 3).content)
        assertEquals(TextAlign.Start, head.lineOn(page = 1, of = 3).align)
        assertEquals(TextAlign.Right, foot.lineOn(page = 1, of = 3).align)
    }

    @Test
    fun aCoverColophonIsDrawnOnTheFirstPageOnly() {
        val spec = document {
            clock = PINNED_CLOCK
            header(HEAD, pages = BandPages.FirstOnly)
            paragraph(BODY)
        }.toDocumentSpec()

        val head = assertNotNull(spec.header)
        assertEquals(BandPages.FirstOnly, head.pages)
        assertTrue(head.pages.appliesTo(1), "a cover band belongs on the cover")
        assertTrue(!head.pages.appliesTo(2), "a cover band does not follow the document")

        // The reserve is not the rule: the strip comes off every page whether or
        // not the band is drawn there, which is what keeps the text of page 2
        // beginning where the text of page 1 begins.
        assertEquals(DEFAULT_BAND_HEIGHT, head.height)
        assertTrue(spec.contentArea.height < spec.printableArea.height, "the strip should be reserved on every page")
    }

    @Test
    fun aColophonAfterTheCoverIsDrawnOnEveryPageButTheFirst() {
        val spec = document {
            clock = PINNED_CLOCK
            footer(FOOT, pages = BandPages.ExceptFirst)
            paragraph(BODY)
        }.toDocumentSpec()

        val foot = assertNotNull(spec.footer)
        assertEquals(BandPages.ExceptFirst, foot.pages)
        assertTrue(!foot.pages.appliesTo(1), "a title page carries no running foot")
        assertTrue(foot.pages.appliesTo(2), "every page after the cover carries it")
        assertEquals(listOf(TextRun(FOOT)), foot.lineOn(page = 2, of = 2).content)
    }

    @Test
    fun aColophonThatDiffersOnTheFirstPageSaysBothLinesFromOneBand() {
        val spec = document {
            clock = PINNED_CLOCK
            header(firstPage = TITLE, otherPages = HEAD, align = TextAlign.Center)
            footer(firstPage = FIRST_FOOT, otherPages = FOOT)
            paragraph(BODY)
        }.toDocumentSpec()

        val head = assertNotNull(spec.header)
        // One band, drawn on every page — the two lines are the content function's
        // answer, not two rules (BandPages: "there is no fourth case here").
        assertEquals(BandPages.All, head.pages)
        assertEquals(listOf(TextRun(TITLE)), head.lineOn(page = 1, of = 4).content)
        assertEquals(listOf(TextRun(HEAD)), head.lineOn(page = 2, of = 4).content)
        assertEquals(listOf(TextRun(HEAD)), head.lineOn(page = 4, of = 4).content)
        // Both lines are set to the side the band was given, and the head's own
        // default is not the foot's.
        assertEquals(TextAlign.Center, head.lineOn(page = 1, of = 4).align)
        assertEquals(TextAlign.Center, head.lineOn(page = 2, of = 4).align)

        val foot = assertNotNull(spec.footer)
        assertEquals(listOf(TextRun(FIRST_FOOT)), foot.lineOn(page = 1, of = 4).content)
        assertEquals(listOf(TextRun(FOOT)), foot.lineOn(page = 3, of = 4).content)
        assertEquals(TextAlign.Right, foot.lineOn(page = 3, of = 4).align)
    }

    @Test
    fun aColophonWrittenForItsPageSaysWhichPageOfHowMany() {
        val spec = document {
            clock = PINNED_CLOCK
            footer { paragraph("Page ${numbering.page} of ${numbering.pages}", align = TextAlign.Right) }
            paragraph(BODY)
        }.toDocumentSpec()

        val foot = assertNotNull(spec.footer)
        assertEquals(BandPages.All, foot.pages)
        assertEquals(listOf(TextRun("Page 1 of 12")), foot.lineOn(page = 1, of = 12).content)
        assertEquals(listOf(TextRun("Page 7 of 12")), foot.lineOn(page = 7, of = 12).content)
        assertEquals(listOf(TextRun("Page 12 of 12")), foot.lineOn(page = 12, of = 12).content)
        assertEquals(TextAlign.Right, foot.lineOn(page = 1, of = 12).align)
    }

    @Test
    fun aColophonWrittenForItsPageTakesAPageRuleAndAStripLikeAnyOther() {
        val spec = document {
            clock = PINNED_CLOCK
            header(height = TALL_STRIP, pages = BandPages.ExceptFirst) {
                paragraph("$HEAD — ${numbering.page}", align = TextAlign.Center)
            }
            paragraph(BODY)
        }.toDocumentSpec()

        val head = assertNotNull(spec.header)
        assertEquals(BandPages.ExceptFirst, head.pages)
        assertEquals(TALL_STRIP, head.height)
        assertEquals(listOf(TextRun("$HEAD — 2")), head.lineOn(page = 2, of = 5).content)
        assertEquals(TextAlign.Center, head.lineOn(page = 2, of = 5).align)
    }

    @Test
    fun theLastColophonWins() {
        // One head per document, whichever of the three spellings described it —
        // the same rule the single-line overload already had, held across them.
        val spec = document {
            clock = PINNED_CLOCK
            header(HEAD, pages = BandPages.FirstOnly)
            header(firstPage = TITLE, otherPages = HEAD)
            header(pages = BandPages.ExceptFirst) { paragraph("page ${numbering.page}") }

            footer { paragraph("counted") }
            footer(FOOT)
            paragraph(BODY)
        }.toDocumentSpec()

        val head = assertNotNull(spec.header)
        assertEquals(BandPages.ExceptFirst, head.pages)
        assertEquals(listOf(TextRun("page 3")), head.lineOn(page = 3, of = 3).content)

        val foot = assertNotNull(spec.footer)
        assertEquals(BandPages.All, foot.pages)
        assertEquals(listOf(TextRun(FOOT)), foot.lineOn(page = 1, of = 3).content)
    }

    @Test
    fun aBandBuiltFromBlocksReachesTheSeamAsItWasWritten() {
        // The raw form, which is to a band what add(block) is to a block. What
        // the one-line overloads cannot express:
        // a line at a size and a weight of its own.
        val line = ParagraphBlock(
            content = listOf(StyledRun(listOf(TextRun(HEAD)), bold = true, fontSize = 18.pt)),
            align = TextAlign.Center,
        )
        val spec = document {
            clock = PINNED_CLOCK
            headerBand(height = TALL_STRIP, pages = BandPages.ExceptFirst) { listOf(line) }
            footerBand { numbering -> listOf(ParagraphBlock(listOf(TextRun("${numbering.page}/${numbering.pages}")))) }
            paragraph(BODY)
        }.toDocumentSpec()

        val head = assertNotNull(spec.header)
        assertEquals(TALL_STRIP, head.height)
        assertEquals(BandPages.ExceptFirst, head.pages)
        assertEquals(
            listOf(line),
            head.content(PageNumbering(page = 2, pages = 5)),
            "the blocks are handed over as they were given, not re-wrapped in a colophon's paragraph",
        )

        val foot = assertNotNull(spec.footer)
        assertEquals(DEFAULT_BAND_HEIGHT, foot.height, "the same strip a colophon gets when nobody says")
        assertEquals(BandPages.All, foot.pages)
        assertEquals(listOf(TextRun("3/7")), foot.lineOn(page = 3, of = 7).content)
    }

    /** The one paragraph this band draws on page [page] of a document of [of] pages. */
    private fun BandSpec.lineOn(page: Int, of: Int): ParagraphBlock =
        content(PageNumbering(page = page, pages = of)).single() as ParagraphBlock

    private companion object {
        const val TITLE = "Отчёт за IV квартал 2026"
        const val HEAD = "Отчёт за квартал"
        const val FOOT = "Черновик"
        const val FIRST_FOOT = "Составлено 6 августа"
        const val BODY = "body"

        val TALL_STRIP: Pt = 40.pt
    }
}
