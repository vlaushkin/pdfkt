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
     * A band with more in it than its strip holds is refused, and refused before
     * a byte of the document has been written.
     *
     * The buffer is the assertion. The rule is an error at document-spec
     * validation, not at page 37 — and a file whose first thirty-six
     * pages were already on their way to the sink would be exactly the second
     * thing — the caller would be holding a truncated PDF and an exception.
     */
    @Test
    fun `a band taller than its strip is refused before anything is written`() {
        val spec = spec(footer = band(SHORT_BAND) { listOf(paragraph(LONG_TEXT)) })
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
    fun `a band that only overflows on a later page is refused too`() {
        val footer = BandSpec(height = SHORT_BAND) { numbering ->
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
    fun `a band whose one line does not fit is refused as a band`() {
        val spec = spec(footer = band(HAIRLINE) { listOf(paragraph("Page 1")) })
        val refused = assertFailsWith<BandOverflowException> { render(spec, Theme.Default, Buffer()) }
        assertEquals(HAIRLINE, refused.reserved)
        assertEquals(BandPosition.Footer, refused.position)
    }

    /** A document with no bands at all plans none, and every page is content. */
    @Test
    fun `a document without bands has nothing in its bands`() {
        assertEquals(listOf(emptyList<PlacedItem>(), emptyList()), plan(spec(), pages = 2))
    }

    // ------------------------------------------------------------- the fixture

    private fun plan(spec: DocumentSpec, pages: Int): List<List<PlacedItem>> =
        BandPlanner(Theme.Default, FontCatalog()).plan(spec, pages)

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

    private fun band(height: Pt, pages: BandPages = BandPages.All, content: (PageNumbering) -> List<Block>): BandSpec =
        BandSpec(height, pages, content)

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

        /** Three lines of text at the width of an A4 page between 56-point margins. */
        val LONG_TEXT: String = (1..40).joinToString(separator = " ") { word -> "overflowing$word" }

        const val TWO_PAGES_OF_PARAGRAPHS: Int = 40
        const val WORDS_PER_PARAGRAPH: Int = 24

        val PINNED: Clock = Clock { PdfDate(2026, 8, 5, 12, 0, 0) }
    }
}
