/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.doc

import io.pdfkt.core.block.Block
import io.pdfkt.core.block.ParagraphBlock
import io.pdfkt.core.block.TextRun
import io.pdfkt.core.font.FontCatalog
import io.pdfkt.core.model.Insets
import io.pdfkt.core.model.Pt
import io.pdfkt.core.model.Rect
import io.pdfkt.core.model.Size
import io.pdfkt.core.model.pt
import io.pdfkt.core.obj.PdfDate
import io.pdfkt.core.plan.PlacedItem
import io.pdfkt.core.resolve.Resolver
import io.pdfkt.core.style.Theme
import kotlinx.io.Buffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

// No clause applies: L4 is this library's own design.

/**
 * Bands, against the plan rather than against a file: where they land, what
 * their text says on each page, and what happens when they are given more than
 * their strip holds.
 *
 * The file the same document becomes is `BandRenderTest`'s question. This one
 * asks the geometry directly, which is what the page plan exists for — a test
 * asserts on coordinates directly instead of decompressing a content stream and
 * parsing operators back out.
 */
class BandPlanTest {
    /**
     * A band is drawn inside the strip reserved for it and nowhere else.
     *
     * The claim is the one that makes a band a band: the header sits above the
     * content area and the footer below it, so neither can print over a line of
     * text however the text was laid out. Both edges are checked because a band
     * planned from the wrong corner would still be *near* its strip.
     */
    @Test
    fun `a band lands inside its own strip and outside the content area`() {
        val spec = spec(header = band(BAND_HEIGHT) { listOf(paragraph("Running header")) }, footer = numbered())
        val page = plan(spec, pages = 1).single()
        val header = requireNotNull(spec.headerArea)
        val footer = requireNotNull(spec.footerArea)

        val baselines = page.filterIsInstance<PlacedItem.Text>()
        assertEquals(2, baselines.size, "a one-line header and a one-line footer are two baselines: $page")
        assertWithin(header, baselines[0])
        assertWithin(footer, baselines[1])
        assertTrue(
            baselines[0].y >= spec.contentArea.top,
            "the header baseline at ${baselines[0].y} is inside the content area, which reaches " +
                "${spec.contentArea.top}",
        )
        assertTrue(
            baselines[1].y <= spec.contentArea.bottom,
            "the footer baseline at ${baselines[1].y} is inside the content area, which starts at " +
                "${spec.contentArea.bottom}",
        )
    }

    /**
     * Every page's band says which page it is and how many there are.
     *
     * This is the page-number substitution made at resolve time, seen from
     * the outside: one `BandSpec` planned three times gives three different
     * strings, and the count is the same in all of them because the content was
     * paginated before any band was planned.
     */
    @Test
    fun `the page number is substituted for the page the band is planned for`() {
        val pages = plan(spec(footer = numbered()), pages = 3)
        assertEquals(
            listOf("Page 1 of 3", "Page 2 of 3", "Page 3 of 3"),
            pages.map { items -> textOf(items) },
        )
    }

    /** A rule that names some pages puts the band on those and leaves the rest bare. */
    @Test
    fun `a band is drawn only on the pages its rule names`() {
        val first = plan(spec(header = band(BAND_HEIGHT, BandPages.FirstOnly) { listOf(paragraph("Cover")) }), 3)
        assertEquals(listOf("Cover", "", ""), first.map { items -> textOf(items) })

        val rest = plan(spec(header = band(BAND_HEIGHT, BandPages.ExceptFirst) { listOf(paragraph("Running")) }), 3)
        assertEquals(listOf("", "Running", "Running"), rest.map { items -> textOf(items) })
    }

    /**
     * A band with more in it than its strip holds, that asked to be refused for
     * it, is refused — and refused before a byte of the document has been
     * written.
     *
     * The buffer is the assertion. The rule is an error at document-spec
     * validation, not at page 37 — and a file whose first thirty-six
     * pages were already on their way to the sink would be exactly the second
     * thing — the caller would be holding a truncated PDF and an exception.
     */
    @Test
    fun `a strict band taller than its strip is refused before anything is written`() {
        val spec = spec(footer = band(SHORT_BAND, overflow = BandOverflow.Fail) { listOf(paragraph(LONG_TEXT)) })
        val sink = Buffer()

        val refused = assertFailsWith<BandOverflowException> { render(spec, Theme.Default, sink) }

        assertEquals(0L, sink.size, "the file had already started to go out when the band was refused")
        assertEquals(BandPosition.Footer, refused.position)
        assertEquals(1, refused.numbering.page)
        assertEquals(SHORT_BAND, refused.reserved)
        val required = requireNotNull(refused.required) { "the height it needs is measurable: it fits a page" }
        assertTrue(
            required > SHORT_BAND,
            "the band was refused although the $required it needs fits the $SHORT_BAND it was given",
        )
        assertTrue(
            refused.message?.contains("footer of page 1 of 1") == true,
            "the refusal does not say which band of which page it is: ${refused.message}",
        )
    }

    /**
     * A band that fits on the pages before it is still refused on the page it
     * does not fit.
     *
     * Which is the reason the check is per page and not per document: the content
     * of a band is a function of the numbering, so a band can be one line on nine
     * pages and two on the tenth. A validation that planned page 1 and assumed
     * the rest would look like it would pass this document and then write it
     * wrong.
     */
    @Test
    fun `a strict band that only overflows on a later page is refused too`() {
        val footer = BandSpec(height = SHORT_BAND, overflow = BandOverflow.Fail) { numbering ->
            val lastPage = numbering.page == numbering.pages
            listOf(paragraph(if (lastPage) LONG_TEXT else "Page ${numbering.page}"))
        }
        val refused = assertFailsWith<BandOverflowException> {
            render(spec(footer = footer, content = twoPages()), Theme.Default, Buffer())
        }
        assertTrue(refused.numbering.pages >= 2, "the document was written onto one page, so nothing was proved")
        assertEquals(
            refused.numbering.pages,
            refused.numbering.page,
            "the band was refused on a page it fits: only the last one is given the long text",
        )
    }

    /**
     * A band whose single line is taller than its strip is refused the same way.
     *
     * It arrives by another road — the planner's progress guard raises before it
     * can answer at all, since a line that fits nothing is a block that would ask
     * for an emptier page for ever — and the caller is told the same thing about
     * the same band rather than being handed the guard's message about pages.
     */
    @Test
    fun `a strict band whose one line does not fit is refused as a band`() {
        val spec = spec(footer = band(HAIRLINE, overflow = BandOverflow.Fail) { listOf(paragraph("Page 1")) })
        val refused = assertFailsWith<BandOverflowException> { render(spec, Theme.Default, Buffer()) }
        assertEquals(HAIRLINE, refused.reserved)
        assertEquals(BandPosition.Footer, refused.position)
    }

    /**
     * A band with no height of its own is as tall as what it draws: one line,
     * two, five — the strip is that many lines of the theme's body text, and
     * every line of it is drawn.
     */
    @Test
    fun `a band with no height is as tall as its content`() {
        fun reserved(count: Int): Pt? {
            val spec = spec(footer = band(null) { List(count) { index -> line("Line ${index + 1}") } })
            val (settled, pages) = planSettled(spec)
            assertEquals(count, lines(pages.single()), "a $count-line footer drew ${lines(pages.single())} lines")
            return settled.footer?.height
        }
        val one = requireNotNull(reserved(1))
        assertTrue(one > Pt.ZERO, "a one-line footer reserved nothing")
        assertNear(one * 2.0f, reserved(2), "two lines")
        assertNear(one * 5.0f, reserved(5), "five lines")
        assertNear(one * 7.0f, reserved(7), "seven lines")
    }

    /**
     * A line long enough to wrap is measured as the lines it wraps to — the
     * height is taken from the band set across its strip, not from counting
     * paragraphs.
     */
    @Test
    fun `a band with no height grows with the lines its text wraps to`() {
        val (one, _) = settle(spec(header = band(null) { listOf(line("Running")) }))
        val (wrapped, pages) = planSettled(spec(header = band(null) { listOf(line(LONG_TEXT)) }))
        val drawn = lines(pages.single())
        assertTrue(drawn > 1, "the long line did not wrap, so nothing was proved")
        val line = requireNotNull(one.header?.height)
        assertNear(paragraphOf(drawn, line), wrapped.header?.height, "$drawn wrapped lines")
        assertTrue(wrapped.contentArea.height < one.contentArea.height, "the taller header took no room from the text")
    }

    /**
     * A band given a height its content does not fit grows past it rather than
     * stop the document — the default answer to content of any length — and
     * the content area gives up the difference.
     */
    @Test
    fun `a band taller than its given height grows rather than refuse the document`() {
        val spec = spec(footer = band(SHORT_BAND) { listOf(line(LONG_TEXT)) })
        val (settled, pages) = planSettled(spec)
        val (sized, _) = planSettled(spec(footer = band(null) { listOf(line(LONG_TEXT)) }))
        val grown = requireNotNull(settled.footer?.height)
        assertTrue(grown > SHORT_BAND, "the footer kept its $SHORT_BAND")
        assertEquals(sized.footer?.height, grown, "the footer grew to other than its content")
        assertEquals(wrappedLines(), lines(pages.single()), "a line of the footer was left out")
        val sink = Buffer()
        render(spec, Theme.Default, sink)
        assertTrue(sink.size > 0L, "the document was not written")
    }

    /** A given height is a floor: content shorter than it leaves the strip as it was given. */
    @Test
    fun `a band shorter than its given height keeps the height`() {
        val (settled, _) = settle(spec(footer = band(BAND_HEIGHT) { listOf(line("pdfkt")) }))
        assertEquals(BAND_HEIGHT, settled.footer?.height)
    }

    /** A clipped band keeps its strip and draws the lines that fit it. */
    @Test
    fun `a clipped band keeps its height and draws what fits`() {
        val spec = spec(footer = band(SHORT_BAND, overflow = BandOverflow.Clip) { listOf(line(LONG_TEXT)) })
        val (settled, pages) = planSettled(spec)
        assertEquals(SHORT_BAND, settled.footer?.height)
        assertEquals(1, lines(pages.single()), "a strip one line tall drew ${lines(pages.single())} lines")
        render(spec, Theme.Default, Buffer())
    }

    /** A clipped band whose one line does not fit its strip draws nothing, and the document is written. */
    @Test
    fun `a clipped band too short for one line draws nothing`() {
        val spec = spec(footer = band(HAIRLINE, overflow = BandOverflow.Clip) { listOf(line("Page 1")) })
        val (_, pages) = planSettled(spec)
        assertEquals(0, lines(pages.single()))
        render(spec, Theme.Default, Buffer())
    }

    /**
     * A strict band with no height grows to its content too: it is refused only
     * when it does not fit the strip, and a strip cut to the content fits it.
     */
    @Test
    fun `a strict band with no height is sized to its content and not refused`() {
        val spec = spec(footer = band(null, overflow = BandOverflow.Fail) { listOf(line(LONG_TEXT)) })
        render(spec, Theme.Default, Buffer())
    }

    /**
     * Growth stops at a quarter of the printable height, so a band can never
     * push the text off the page — and what is taller than that is clipped, not
     * refused.
     */
    @Test
    fun `a band grows no further than a quarter of the page`() {
        val spec = spec(header = band(null) { List(EXCESSIVE_LINES) { index -> line("Line $index") } })
        val (settled, pages) = planSettled(spec)
        val limit = spec.printableArea.height / 4.0f
        assertNear(limit, settled.header?.height, "the header grew past the limit")
        assertTrue(lines(pages.single()) in 1 until EXCESSIVE_LINES, "the header drew ${lines(pages.single())} lines")
        render(spec, Theme.Default, Buffer())
    }

    /**
     * A band whose text depends on the page count is measured again on the
     * pages that came out: it is one line on every page but the last, several
     * on the last, and the strip is those lines on every page.
     */
    @Test
    fun `a band that only grows on a later page is measured on that page`() {
        val footer = band(null) { numbering ->
            listOf(line(if (numbering.page == numbering.pages) LONG_TEXT else "Page ${numbering.page}"))
        }
        val (one, _) = settle(spec(footer = band(null) { listOf(line("Page 1")) }, content = twoPages()))
        val (settled, pages) = planSettled(spec(footer = footer, content = twoPages()))
        assertTrue(pages.size >= 2, "the document was written onto one page, so nothing was proved")
        val last = wrappedLines()
        val line = requireNotNull(one.footer?.height)
        assertNear(paragraphOf(last, line), settled.footer?.height, "the last page's lines")
        assertEquals(last, lines(pages.last()), "the last page's footer lost lines")
        assertEquals(1, lines(pages.first()))
    }

    /** A document with no bands at all plans none, and every page is content. */
    @Test
    fun `a document without bands has nothing in its bands`() {
        assertEquals(listOf(emptyList<PlacedItem>(), emptyList()), plan(spec(), pages = 2))
    }

    // ------------------------------------------------------------- the fixture

    private fun plan(spec: DocumentSpec, pages: Int): List<List<PlacedItem>> =
        BandPlanner(Theme.Default, FontCatalog()).plan(spec, List(pages) { PageSetup(spec.pageSize, spec.margins) })

    /** What a page's bands say, as one string per page. */
    private fun textOf(items: List<PlacedItem>): String = items
        .filterIsInstance<PlacedItem.Text>()
        .joinToString(separator = " ") { line -> line.runs.joinToString(separator = "") { run -> run.text } }

    private fun assertWithin(strip: Rect, line: PlacedItem.Text) {
        assertTrue(
            line.y in strip.bottom..strip.top && line.x >= strip.left && line.x <= strip.right,
            "a band line at (${line.x}, ${line.y}) is outside the strip reserved for it: $strip",
        )
    }

    private fun paragraph(text: String): Block = ParagraphBlock(listOf(TextRun(text)))

    private fun band(
        height: Pt?,
        pages: BandPages = BandPages.All,
        overflow: BandOverflow = BandOverflow.Grow,
        content: (PageNumbering) -> List<Block>,
    ): BandSpec = BandSpec(height, pages, overflow, content = content)

    /** [spec] with its strips settled, and the pages its content came out on. */
    private fun settle(spec: DocumentSpec): Pair<DocumentSpec, List<PageSetup>> {
        val fonts = FontCatalog()
        val styled = Resolver(Theme.Default, fonts).resolve(spec.content)
        val (settled, pages) = BandPlanner(Theme.Default, fonts).reserve(spec) { reserved ->
            layOut(reserved, styled, fonts)
        }
        return settled to pages.map { it.second }
    }

    /** [settle] and plan: what each page's bands draw, in the strips that were settled. */
    private fun planSettled(spec: DocumentSpec): Pair<DocumentSpec, List<List<PlacedItem>>> {
        val (settled, pages) = settle(spec)
        return settled to BandPlanner(Theme.Default, FontCatalog()).plan(settled, pages)
    }

    /** How many lines [LONG_TEXT] wraps to across the strip of [spec]'s page. */
    private fun wrappedLines(): Int =
        lines(planSettled(spec(footer = band(null) { listOf(line(LONG_TEXT)) })).second.single())

    /**
     * How tall a paragraph of [count] lines is, one of which is [line] tall: the
     * theme's line height lies between baselines, and the last line takes only
     * its own extent.
     */
    private fun paragraphOf(count: Int, line: Pt): Pt = line + line * (Theme.Default.lineHeight * (count - 1))

    /** How many lines a page's bands draw. */
    private fun lines(items: List<PlacedItem>): Int = items.count { it is PlacedItem.Text }

    /** A colophon line: one paragraph with no spacing of its own, as the DSL writes one. */
    private fun line(text: String): Block =
        ParagraphBlock(listOf(TextRun(text)), spacingBefore = Pt.ZERO, spacingAfter = Pt.ZERO)

    private fun assertNear(expected: Pt, actual: Pt?, message: String) {
        val value = requireNotNull(actual) { "$message: the strip was not settled" }
        assertTrue(kotlin.math.abs(expected.value - value.value) < EPSILON, "$message: expected $expected, got $value")
    }

    /** The footer of the acceptance: "Page N of M", on every page. */
    private fun numbered(): BandSpec = BandSpec(height = BAND_HEIGHT) { numbering ->
        listOf(paragraph("Page ${numbering.page} of ${numbering.pages}"))
    }

    /** Enough text to need a second page of a page whose content area is what [spec] gives. */
    private fun twoPages(): List<Block> = List(TWO_PAGES_OF_PARAGRAPHS) { index ->
        paragraph((1..WORDS_PER_PARAGRAPH).joinToString(separator = " ") { word -> "para${index}word$word" })
    }

    private fun spec(
        header: BandSpec? = null,
        footer: BandSpec? = null,
        content: List<Block> = listOf(paragraph("body")),
    ): DocumentSpec = DocumentSpec(
        pageSize = Size.A4,
        margins = Insets.uniform(MARGIN),
        header = header,
        footer = footer,
        content = content,
        clock = PINNED,
    )

    private companion object {
        val MARGIN: Pt = 56.pt
        val BAND_HEIGHT: Pt = 24.pt

        /** A strip that holds one line of the default theme's body text and no more. */
        val SHORT_BAND: Pt = 14.pt

        /** A strip no line of any size fits: the planner's guard answers before the band does. */
        val HAIRLINE: Pt = 2.pt

        /** Several lines of text at the width of an A4 page between 56-point margins. */
        val LONG_TEXT: String = (1..40).joinToString(separator = " ") { word -> "overflowing$word" }

        /** More lines than a quarter of an A4 page holds. */
        const val EXCESSIVE_LINES: Int = 40

        const val EPSILON: Float = 0.01f

        const val TWO_PAGES_OF_PARAGRAPHS: Int = 40
        const val WORDS_PER_PARAGRAPH: Int = 24

        val PINNED: Clock = Clock { PdfDate(2026, 8, 5, 12, 0, 0) }
    }
}
