/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.fragment

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
import io.pdfkt.core.resolve.StyledBlock
import io.pdfkt.core.resolve.StyledPageBreak
import io.pdfkt.core.resolve.StyledParagraph
import io.pdfkt.core.resolve.StyledQuote
import io.pdfkt.core.resolve.StyledTextRun
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

// No clause applies: L4 is this library's own design.

/**
 * The two keep flags, against plans.
 *
 * > **keepTogether** means a block answers `Defers` instead of `Splits` while
 * > the current page is partly used; on an empty page the guard above overrides
 * > it.
 * >
 * > **keepWithNext** is a grouping pass *before* planning: consecutive blocks
 * > chained by the flag form a group; if the group's measured height fits the
 * > remaining space, plan normally; if not and it fits a fresh page, break
 * > first. Measuring reuses `planBlock` against a probe height — measurement is
 * > planning, per the page-plan decision.
 *
 * The sweeps here move the block under test past a page boundary one line at a
 * time instead of hand-picking the one position where the boundary falls: where
 * it falls is a function of the page and the font metrics, and a single position
 * would stop exercising the boundary the moment either moves. Each sweep is run
 * twice — with the flags off, which is what proves the sweep crosses a boundary
 * at all, and with them on, where the invariant has to hold at every step.
 *
 * `KeepFlagsRenderTest` makes the same two claims about a real file, read back
 * by a text extractor that never saw this library.
 */
class KeepFlagsTest {
    // --------------------------------------------------------- keepTogether

    /**
     * The rule itself, asked of a planner directly: a block that would be cut in
     * half says it would rather move on, while the page is partly used.
     */
    @Test
    fun `a keepTogether block answers Defers where it would otherwise answer Splits`() {
        val planner = BlockPlanner(FontCatalog())
        val long = paragraph(HALF_A_PAGE_OF_WORDS, keepTogether = true)

        assertEquals(FitResult.Defers, planner.planBlock(long, COLUMN, HALF_THE_BLOCK, atPageTop = false))
        assertIs<FitResult.Splits>(
            planner.planBlock(long.copy(keepTogether = false), COLUMN, HALF_THE_BLOCK, atPageTop = false),
            "without the flag the same block splits — otherwise the test above proves nothing",
        )
    }

    /**
     * On an empty page the progress guard overrides the flag: there is no
     * emptier page to move to, so the block is split where it is rather than
     * being asked to move for ever.
     */
    @Test
    fun `keepTogether is overridden at the top of an empty page`() {
        val planner = BlockPlanner(FontCatalog())
        val long = paragraph(HALF_A_PAGE_OF_WORDS, keepTogether = true)

        assertIs<FitResult.Splits>(planner.planBlock(long, COLUMN, HALF_THE_BLOCK, atPageTop = true))
    }

    /**
     * The guard holds through the fragmenter too: a keepTogether block taller
     * than a whole page is cut across pages instead of deferring for ever.
     */
    @Test
    fun `a keepTogether block taller than a page is split rather than looping`() {
        val tall = paragraph(
            (1..200).joinToString(separator = " ") { number -> "word$number" },
            keepTogether = true,
        )

        val pages = fragmenter().fragment(listOf(paragraph("Above the block"), tall))

        assertTrue(pages.size >= 3, "two hundred words came out on ${pages.size} pages")
    }

    /** A keepTogether block that fits a page is never cut across two, at any position. */
    @Test
    fun `a keepTogether block is not split anywhere in the sweep`() {
        val split = sweep { fillers -> keptBlockDocument(fillers, keepTogether = false) }
        val kept = sweep { fillers -> keptBlockDocument(fillers, keepTogether = true) }

        assertTrue(
            split.any { (_, pages) -> pageOf(pages, KEEP_FIRST) != pageOf(pages, KEEP_LAST) },
            "the sweep never split the block with keepTogether off, so the kept run below proves " +
                "nothing:\n${describe(split, KEEP_FIRST)}",
        )

        for ((fillers, pages) in kept) {
            val first = pageOf(pages, KEEP_FIRST)
            for (word in KEEP_WORDS) {
                assertEquals(
                    first,
                    pageOf(pages, word),
                    "with $fillers fillers the keepTogether block left '$word' on another page than " +
                        "its first word:\n${describe(pages)}",
                )
            }
        }

        assertTrue(
            kept.map { (_, pages) -> pageOf(pages, KEEP_FIRST) }.toSet().size > 1,
            "the kept sweep never moved the block off page 1, so no page boundary was exercised",
        )
    }

    // -------------------------------------------------------- keepWithNext

    /** A heading is never left at the foot of a page without the text it introduces. */
    @Test
    fun `keepWithNext keeps a heading on the page of its body anywhere in the sweep`() {
        val split = sweep { fillers -> headingDocument(fillers, keeps = false) }
        val kept = sweep { fillers -> headingDocument(fillers, keeps = true) }

        assertTrue(
            split.any { (_, pages) -> pageOf(pages, HEADING) != pageOf(pages, BODY) },
            "the sweep never orphaned the heading with keepWithNext off, so the kept run below " +
                "proves nothing:\n${describe(split, HEADING)}",
        )

        for ((fillers, pages) in kept) {
            assertEquals(
                pageOf(pages, BODY),
                pageOf(pages, HEADING),
                "with $fillers fillers the heading sits alone at the foot of its page:\n${describe(pages)}",
            )
        }

        assertTrue(
            kept.map { (_, pages) -> pageOf(pages, HEADING) }.toSet().size > 1,
            "the kept sweep never moved the heading off page 1, so no page boundary was exercised",
        )
    }

    /**
     * The group is measured against a probe of the whole container, and a group
     * that does not fit even an empty one is planned where it is: breaking
     * before it would move it to a page it does not fit either, and the page it
     * left would be the shorter for nothing.
     */
    @Test
    fun `a group taller than a whole page is planned where it stands`() {
        val heading = paragraph("Heading of the group", size = 20.pt, keepWithNext = true)
        val body = paragraph((1..300).joinToString(separator = " ") { number -> "word$number" })

        val pages = fragmenter().fragment(listOf(paragraph("Above the group"), heading, body))

        assertEquals(
            0,
            pageOf(pages, "Heading of the group"),
            "a group that cannot fit any page was moved off the page it had room on",
        )
    }

    /** A `keepWithNext` on the last block of a document asks to be kept with nothing, and is ignored. */
    @Test
    fun `a keepWithNext block at the end of a document is not moved`() {
        val fillers = (1..FILLERS_PER_PAGE).map { number -> paragraph(filler(number)) }
        val last = paragraph("The last block of the document", keepWithNext = true)

        val pages = fragmenter().fragment(fillers + last)

        assertEquals(1, pages.size, "a block kept with nothing started a page of its own")
    }

    /**
     * A forced page break ends the chain: a caller who asks both to keep a block
     * with what follows and to start a new page after it gets the request that
     * is about pages, and the block is not moved for a group of one.
     */
    @Test
    fun `a page break ends a keepWithNext chain`() {
        val fillers = (1..FILLERS_PER_PAGE).map { number -> paragraph(filler(number)) }
        val kept = paragraph("Kept with the break", keepWithNext = true)

        val pages = fragmenter().fragment(fillers + kept + StyledPageBreak + paragraph("After the break"))

        assertEquals(0, pageOf(pages, "Kept with the break"), "the block was moved to be kept with a page break")
        assertEquals(1, pageOf(pages, "After the break"))
    }

    /**
     * The flags are read wherever blocks are stacked, not only on a page: a
     * quotation plans its children through the same [BlockPlanner.planSequence],
     * so a heading inside one is kept with the paragraph inside one.
     */
    @Test
    fun `keepWithNext holds inside a quotation`() {
        val heading = paragraph("Quoted heading", size = 24.pt, keepWithNext = true)
        val children = listOf(paragraph("Quoted intro"), heading, paragraph("Quoted body text"))
        val quote = StyledQuote(children = children, barColor = Rgb.BLACK, barWidth = 3.pt, indentLeft = 12.pt)
        val planner = BlockPlanner(FontCatalog())

        // Room for the intro and the heading, but not for the body after it: the
        // heading and the body move on together, so only the intro is placed.
        val kept = assertIs<FitResult.Splits>(planner.planBlock(quote, COLUMN, QUOTE_ROOM, atPageTop = false))

        assertTrue(
            "Quoted heading" !in textOf(kept.head),
            "the heading stayed behind without the body it is kept with: ${textOf(kept.head)}",
        )

        val loose = quote.copy(children = listOf(children[0], heading.copy(keepWithNext = false), children[2]))
        val orphaned = assertIs<FitResult.Splits>(planner.planBlock(loose, COLUMN, QUOTE_ROOM, atPageTop = false))
        assertTrue(
            "Quoted heading" in textOf(orphaned.head),
            "without the flag the heading is left behind alone — otherwise the assertion above " +
                "proves nothing: ${textOf(orphaned.head)}",
        )
    }

    // ------------------------------------------------------------ fixtures

    /**
     * The pages of the document [build] makes, for every filler count of the
     * sweep — the block under test walked past a page boundary one filler line
     * at a time.
     */
    private fun sweep(build: (Int) -> List<StyledBlock>): List<Pair<Int, List<PagePlan>>> =
        SWEEP.map { fillers -> fillers to fragmenter().fragment(build(fillers)) }

    /** [fillers] filler lines, then a block of several lines that must not be cut. */
    private fun keptBlockDocument(fillers: Int, keepTogether: Boolean): List<StyledBlock> =
        (1..fillers).map { number -> paragraph(filler(number)) } +
            paragraph(KEEP_WORDS.joinToString(separator = " "), keepTogether = keepTogether)

    /** [fillers] filler lines, then a heading and the body text it introduces. */
    private fun headingDocument(fillers: Int, keeps: Boolean): List<StyledBlock> =
        (1..fillers).map { number -> paragraph(filler(number)) } +
            paragraph(HEADING, size = 20.pt, spacingBefore = 14.pt, spacingAfter = 24.pt, keepWithNext = keeps) +
            paragraph(BODY)

    private fun fragmenter(): Fragmenter = Fragmenter(FontCatalog(), template())

    private fun template(): PageTemplate = PageTemplate.of(Size(340.pt, 260.pt), Insets.uniform(20.pt))

    private fun paragraph(
        text: String,
        size: Pt = 12.pt,
        spacingBefore: Pt = Pt.ZERO,
        spacingAfter: Pt = Pt.ZERO,
        keepTogether: Boolean = false,
        keepWithNext: Boolean = false,
    ): StyledParagraph = StyledParagraph(
        content = listOf(StyledTextRun(text, FontRef.Standard14(Standard14Name.HELVETICA), size, Rgb.BLACK)),
        lineHeight = 1.2f,
        spacingBefore = spacingBefore,
        spacingAfter = spacingAfter,
        keepTogether = keepTogether,
        keepWithNext = keepWithNext,
    )

    /** Which page [needle] landed on; a needle that reached no page at all fails loudly. */
    private fun pageOf(pages: List<PagePlan>, needle: String): Int {
        val index = pages.indexOfFirst { page -> textOf(page).contains(needle) }
        if (index < 0) throw AssertionError("'$needle' reached no page at all:\n${describe(pages)}")
        return index
    }

    private fun textOf(page: PagePlan): String = textOf(page.items)

    private fun textOf(items: List<PlacedItem>): String =
        items.filterIsInstance<PlacedItem.Text>().joinToString(separator = " ") { text ->
            text.runs.joinToString(separator = "") { run -> run.text }
        }

    private fun describe(pages: List<PagePlan>): String =
        pages.withIndex().joinToString(separator = "\n") { (index, page) -> "page ${index + 1}: ${textOf(page)}" }

    /** One line per step of a sweep naming the page [needle] landed on — the message of a sweep that failed. */
    private fun describe(sweep: List<Pair<Int, List<PagePlan>>>, needle: String): String =
        sweep.joinToString(separator = "\n") { (fillers, pages) ->
            "$fillers fillers -> '$needle' on page ${pageOf(pages, needle) + 1} of ${pages.size}"
        }

    private fun filler(index: Int): String = "Filler line $index holding the position"

    private companion object {
        /**
         * Filler counts the sweep lays out. A 220pt column of 12pt Helvetica
         * takes a little over twenty filler lines, so a sweep this long walks
         * the block under test through more than one page's worth of positions —
         * which is what makes it certain to pass through the band where the
         * block fits the page but its continuation does not.
         */
        val SWEEP: IntRange = 1..24

        const val FILLERS_PER_PAGE: Int = 6

        val COLUMN: Pt = 300.pt

        /**
         * Room inside a quotation for a 12pt line and a 24pt one but not the
         * 12pt line after them, and room for the last two on their own — which
         * is the state `keepWithNext` is about, stated in points.
         */
        val QUOTE_ROOM: Pt = 38.pt

        /** Room for about half of [HALF_A_PAGE_OF_WORDS], so the paragraph splits without the flag. */
        val HALF_THE_BLOCK: Pt = 40.pt

        val HALF_A_PAGE_OF_WORDS: String = (1..40).joinToString(separator = " ") { number -> "word$number" }

        const val HEADING: String = "Section Marker Heading"

        const val BODY: String = "Body sentence bound to the heading above it"

        /** Six words, which the column takes as three lines: enough to be cut in half. */
        val KEEP_WORDS: List<String> = listOf(
            "keptAlphaWordOne",
            "keptBravoWordTwo",
            "keptCharlieWordThree",
            "keptDeltaWordFour",
            "keptEchoWordFive",
            "keptFoxtrotWordSix",
        )

        val KEEP_FIRST: String = KEEP_WORDS.first()

        val KEEP_LAST: String = KEEP_WORDS.last()
    }
}
