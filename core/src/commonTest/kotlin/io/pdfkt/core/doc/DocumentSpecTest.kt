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
import io.pdfkt.core.model.Insets
import io.pdfkt.core.model.Pt
import io.pdfkt.core.model.Size
import io.pdfkt.core.model.pt
import io.pdfkt.core.obj.PdfDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

// No clause applies: the seam is this library's own design.

/** The seam's own arithmetic: what a page gives the content, and what it reserves. */
class DocumentSpecTest {
    /** With no bands, the content is the page between its margins. */
    @Test
    fun `without bands the content area is the page between the margins`() {
        val spec = spec()
        assertEquals(Size.A4.width.value - 2 * MARGIN.value, spec.contentArea.width.value, TOLERANCE)
        assertEquals(Size.A4.height.value - 2 * MARGIN.value, spec.contentArea.height.value, TOLERANCE)
        assertEquals(MARGIN, spec.contentArea.left)
        assertEquals(MARGIN, spec.contentArea.bottom)
        assertNull(spec.headerArea)
        assertNull(spec.footerArea)
    }

    /**
     * A band is reserved inside the margins: the header sits below the top
     * margin and the content starts below the header.
     *
     * The fragmenter is handed the result and nothing else — "The fragmenter
     * receives the content area *between* the bands and never knows the bands
     * exist" — so this arithmetic is the whole of what
     * a band does to the layout of the text.
     */
    @Test
    fun `a band is reserved inside the margins and shortens the content area`() {
        val spec = spec(header = band(BAND_HEIGHT), footer = band(FOOTER_HEIGHT))
        val printable = spec.printableArea

        assertEquals(printable.top - BAND_HEIGHT, spec.contentArea.top)
        assertEquals(printable.bottom + FOOTER_HEIGHT, spec.contentArea.bottom)
        assertEquals(printable.width, spec.contentArea.width)
        assertEquals(
            printable.height - BAND_HEIGHT - FOOTER_HEIGHT,
            spec.contentArea.height,
        )

        val header = requireNotNull(spec.headerArea)
        assertEquals(printable.top, header.top)
        assertEquals(BAND_HEIGHT, header.height)
        assertEquals(spec.contentArea.top, header.bottom)

        val footer = requireNotNull(spec.footerArea)
        assertEquals(printable.bottom, footer.bottom)
        assertEquals(FOOTER_HEIGHT, footer.height)
        assertEquals(spec.contentArea.bottom, footer.top)
    }

    /** The template the fragmenter is given is the page and that content area. */
    @Test
    fun `the page template carries the page and the area between the bands`() {
        val spec = spec(header = band(BAND_HEIGHT))
        val template = spec.pageTemplate()
        assertEquals(Size.A4, template.size)
        assertEquals(spec.contentArea, template.content)
    }

    /**
     * The reserve does not depend on which pages the band is drawn on: a content
     * area that changed between pages would reflow the document around where the
     * header happens to appear.
     */
    @Test
    fun `the reserve is the same whichever pages the band is drawn on`() {
        val everywhere = spec(header = band(BAND_HEIGHT, BandPages.All))
        val exceptFirst = spec(header = band(BAND_HEIGHT, BandPages.ExceptFirst))
        assertEquals(everywhere.contentArea, exceptFirst.contentArea)
    }

    /** Which pages each rule covers, counted from 1 as a reader counts. */
    @Test
    fun `the page rules cover the pages they name`() {
        assertTrue(BandPages.All.appliesTo(1) && BandPages.All.appliesTo(7))
        assertTrue(BandPages.FirstOnly.appliesTo(1) && !BandPages.FirstOnly.appliesTo(2))
        assertTrue(!BandPages.ExceptFirst.appliesTo(1) && BandPages.ExceptFirst.appliesTo(2))
    }

    /** A band is planned against the page it is on, out of how many there are. */
    @Test
    fun `a band builds its blocks from the numbering of the page`() {
        val footer = BandSpec(height = BAND_HEIGHT) { numbering ->
            listOf(ParagraphBlock(listOf(TextRun("Page ${numbering.page} of ${numbering.pages}"))))
        }
        val blocks = footer.content(PageNumbering(3, 12))
        assertEquals(
            "Page 3 of 12",
            ((blocks.single() as ParagraphBlock).content.single() as TextRun).text,
        )
        assertFailsWith<IllegalArgumentException> { PageNumbering(0, 3) }
        assertFailsWith<IllegalArgumentException> { PageNumbering(4, 3) }
    }

    /**
     * A page whose margins and bands take all of it is refused where it is
     * described, not on the page that would have had no room for a line of text.
     */
    @Test
    fun `a page with no room left for the content is refused`() {
        assertFailsWith<IllegalArgumentException> {
            spec(margins = Insets.uniform(Size.A4.width / 2.0f))
        }
        assertFailsWith<IllegalArgumentException> {
            spec(header = band(Size.A4.height - MARGIN * 2.0f))
        }
        assertFailsWith<IllegalArgumentException> {
            spec(header = band(HALF_PAGE), footer = band(HALF_PAGE))
        }
    }

    /** A band of no height reserves nothing and can hold nothing. */
    @Test
    fun `a band of no height is refused`() {
        assertFailsWith<IllegalArgumentException> { band(Pt.ZERO) }
        assertFailsWith<IllegalArgumentException> { band(-BAND_HEIGHT) }
    }

    private fun spec(
        margins: Insets = Insets.uniform(MARGIN),
        header: BandSpec? = null,
        footer: BandSpec? = null,
        content: List<Block> = listOf(ParagraphBlock(listOf(TextRun("body")))),
    ): DocumentSpec = DocumentSpec(
        pageSize = Size.A4,
        margins = margins,
        header = header,
        footer = footer,
        content = content,
        metadata = Metadata(),
        clock = PINNED,
    )

    private fun band(height: Pt, pages: BandPages = BandPages.All): BandSpec = BandSpec(height, pages) { emptyList() }

    private companion object {
        val MARGIN: Pt = 56.pt
        val BAND_HEIGHT: Pt = 24.pt
        val FOOTER_HEIGHT: Pt = 18.pt
        val HALF_PAGE: Pt = Size.A4.height / 2.0f

        val PINNED: Clock = Clock { PdfDate(2026, 8, 5, 12, 0, 0) }

        /** Points, to a thousandth — the page sizes are millimetres converted. */
        const val TOLERANCE: Float = 0.001f
    }
}
