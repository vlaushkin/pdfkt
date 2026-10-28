/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.api

import io.pdfkt.core.doc.BandPlanner
import io.pdfkt.core.doc.PageSetup
import io.pdfkt.core.doc.layOut
import io.pdfkt.core.font.FontCatalog
import io.pdfkt.core.model.Rect
import io.pdfkt.core.plan.PagePlan
import io.pdfkt.core.plan.PlacedItem
import io.pdfkt.core.resolve.Resolver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

// No clause applies: the DSL and L4 are this library's own design. §7.7.3.3 is
// what a page's size becomes — its /MediaBox.

/**
 * [DocumentScope.section] against the page plans it becomes: pages of their own
 * paper in one document, a picture that fills its page exactly, a line under it
 * on the same page, and a title page centred top to bottom.
 *
 * The document these tests write is the one a photo album is — a square cover,
 * then each photograph on a sheet of its own proportions with a signature under
 * it — because that is the document sections were added for, and its geometry
 * is the claim most worth checking to the thousandth of a point.
 */
class SectionScopeTest {

    @Test
    fun `each section is set on its own paper and the document's resumes after it`() {
        val pages = pagesOf(
            document {
                clock = PINNED_CLOCK
                section(PageSize.Custom(COVER, COVER)) { paragraph("Cover") }
                section(PageSize.Custom(300.pt, 500.pt), margins = NO_MARGINS) { image(testJpeg(300, 500)) }
                paragraph("Back on A4")
            },
        )

        assertEquals(
            listOf(Size(COVER, COVER), Size(300.pt, 500.pt), Size.A4),
            pages.map { (page, _) -> page.size },
        )
    }

    @Test
    fun `a photograph on a page of its own size fills the page exactly`() {
        val page = pagesOf(
            document {
                clock = PINNED_CLOCK
                section(PageSize.Custom(640.pt, 480.pt), margins = NO_MARGINS) { image(testJpeg(640, 480)) }
            },
        ).single().first

        assertBox(Rect(0.pt, 0.pt, 640.pt, 480.pt), page.images().single().box)
    }

    /**
     * The arithmetic an album does in doubles before it hands the page over —
     * a 3000 × 4000 portrait scaled to the width of A4 — lands the picture in
     * the page it computed, on that one page, with nothing moved to a second.
     */
    @Test
    fun `a photograph scaled to a page computed in doubles stays on that page`() {
        val width = A4_WIDTH
        val height = 4000.0 * (A4_WIDTH / 3000.0)
        val pages = pagesOf(
            document {
                clock = PINNED_CLOCK
                section(PageSize.Custom(width.pt, height.pt), margins = NO_MARGINS) { image(testJpeg(3000, 4000)) }
            },
        )

        val page = pages.single().first
        assertBox(Rect(0.pt, 0.pt, width.pt, height.pt), page.images().single().box)
    }

    /**
     * A signature under the photograph, on the same page: the page is the
     * picture plus ten points above the line, one and a half sizes for it and
     * ten below — the bottom margin — and the line ends five points short of
     * the right edge.
     */
    @Test
    fun `a signature line sits under the photograph on the same page`() {
        val signatureSize = 8.42
        val photoHeight = 400.0
        val pageHeight = photoHeight + 2 * 10.0 + signatureSize * 1.5
        val pages = pagesOf(
            document {
                clock = PINNED_CLOCK
                theme = Theme(lineHeight = 1.5f)
                section(
                    PageSize.Custom(300.pt, pageHeight.pt),
                    margins = Margins.Fixed(top = 0.pt, right = 0.pt, bottom = 10.pt, left = 0.pt),
                ) {
                    image(testJpeg(300, 400))
                    paragraph(align = TextAlign.Right, indentRight = 5.pt, spacingBefore = 10.pt) {
                        text("Photo: Anna") {
                            fontSize = signatureSize.pt
                            color = Rgb.of(0x616161)
                        }
                    }
                }
            },
        )

        val page = pages.single().first
        val photo = page.images().single().box
        assertBox(Rect(0.pt, (pageHeight - photoHeight).pt, 300.pt, photoHeight.pt), photo)
        val line = page.items.filterIsInstance<PlacedItem.Text>().single()
        val run = line.runs.single()
        assertClose(295.0f, (line.x + run.xOffset + run.width).value, "the line ends five points short of the edge")
        assertTrue(line.y > 10.pt, "the baseline at ${line.y} is in the bottom margin")
        assertTrue(line.y < photo.bottom - 10.pt, "the baseline at ${line.y} is less than ten points under the photo")
        assertEquals(Rgb.of(0x616161), run.color)
    }

    /**
     * A title page centred top to bottom. The heights of the lines are the
     * font's business, so the claim is made without them: content stood on the
     * bottom drops by the room the page has left, and content centred drops by
     * exactly half of that.
     */
    @Test
    fun `a section aligned to the middle centres its content top to bottom`() {
        fun baseline(align: VerticalAlign): Pt = pagesOf(
            document {
                clock = PINNED_CLOCK
                section(PageSize.Custom(COVER, COVER), margins = NO_MARGINS, verticalAlign = align) {
                    paragraph("Title", align = TextAlign.Center)
                }
            },
        ).single().first.items.filterIsInstance<PlacedItem.Text>().single().y

        val top = baseline(VerticalAlign.Top)
        val middle = baseline(VerticalAlign.Middle)
        val bottom = baseline(VerticalAlign.Bottom)
        assertTrue(top > middle && middle > bottom, "top $top, middle $middle, bottom $bottom")
        assertClose((top - bottom).value / 2.0f, (top - middle).value, "the middle is half the drop to the bottom")
    }

    @Test
    fun `a page break next to a section adds no blank page`() {
        val pages = pagesOf(
            document {
                clock = PINNED_CLOCK
                paragraph("Before")
                pageBreak()
                section(PageSize.Custom(COVER, COVER)) {
                    paragraph("Inside")
                    pageBreak()
                }
                pageBreak()
                section(PageSize.Custom(COVER, COVER)) { paragraph("Next") }
            },
        )

        assertEquals(listOf(Size.A4, Size(COVER, COVER), Size(COVER, COVER)), pages.map { (page, _) -> page.size })
    }

    @Test
    fun `a page break inside a section starts another page of its paper`() {
        val pages = pagesOf(
            document {
                clock = PINNED_CLOCK
                section(PageSize.Custom(COVER, COVER)) {
                    paragraph("One")
                    pageBreak()
                    paragraph("Two")
                }
            },
        )

        assertEquals(listOf(Size(COVER, COVER), Size(COVER, COVER)), pages.map { (page, _) -> page.size })
    }

    @Test
    fun `an empty section is one blank page of its paper`() {
        val pages = pagesOf(
            document {
                clock = PINNED_CLOCK
                section(PageSize.Custom(COVER, COVER)) {}
            },
        )

        assertEquals(listOf(Size(COVER, COVER)), pages.map { (page, _) -> page.size })
        assertTrue(pages.single().first.items.isEmpty())
    }

    @Test
    fun `the document's footer is drawn on a section's page in that page's own strip`() {
        val doc = document {
            clock = PINNED_CLOCK
            margins = NO_MARGINS
            footer("Footer", height = 20.pt)
            section(PageSize.Custom(200.pt, 300.pt)) { paragraph("Body") }
        }
        val pages = pagesOf(doc)

        val setup = pages.single().second
        assertEquals(PageSetup(Size(200.pt, 300.pt), NO_MARGINS.toInsets()), setup)
        val bands = BandPlanner(doc.theme, FontCatalog()).plan(doc.toDocumentSpec(), listOf(setup)).single()
        val footer = bands.filterIsInstance<PlacedItem.Text>().single()
        assertTrue(footer.y in 0.pt..20.pt, "the footer baseline at ${footer.y} is outside the 20 pt strip")
        val body = pages.single().first.items.filterIsInstance<PlacedItem.Text>().single()
        assertTrue(body.y > 20.pt, "the body baseline at ${body.y} is in the footer's strip")
    }

    @Test
    fun `the same picture in two sections is one image`() {
        val photo = testJpeg(100, 100)
        val pages = pagesOf(
            document {
                clock = PINNED_CLOCK
                section(PageSize.Custom(100.pt, 100.pt), margins = NO_MARGINS) { image(photo) }
                section(PageSize.Custom(100.pt, 100.pt), margins = NO_MARGINS) { image(photo) }
            },
        )

        val refs = pages.map { (page, _) -> page.images().single().image }
        assertTrue(refs[0] === refs[1], "the same array became two images: $refs")
    }

    @Test
    fun `a section inside a container is refused`() {
        val doc = document {
            clock = PINNED_CLOCK
            blockquote {
                add(SectionBlock(Size(COVER, COVER), NO_MARGINS.toInsets(), content = emptyList()))
            }
        }

        assertFailsWith<IllegalArgumentException> { pagesOf(doc) }
    }

    @Test
    fun `margins that leave a section no room are refused`() {
        val doc = document {
            clock = PINNED_CLOCK
            section(PageSize.Custom(50.pt, 50.pt), margins = Margins.Fixed()) { paragraph("Squeezed") }
        }

        assertFailsWith<IllegalArgumentException> { doc.toDocumentSpec() }
    }

    private fun pagesOf(doc: Document): List<Pair<PagePlan, PageSetup>> {
        val spec = doc.toDocumentSpec()
        val fonts = FontCatalog()
        return layOut(spec, Resolver(doc.theme, fonts).resolve(spec.content), fonts)
    }

    private fun PagePlan.images(): List<PlacedItem.Image> = items.filterIsInstance<PlacedItem.Image>()

    private fun assertBox(expected: Rect, actual: Rect) {
        assertClose(expected.x.value, actual.x.value, "x of $actual")
        assertClose(expected.y.value, actual.y.value, "y of $actual")
        assertClose(expected.width.value, actual.width.value, "width of $actual")
        assertClose(expected.height.value, actual.height.value, "height of $actual")
    }

    private fun assertClose(expected: Float, actual: Float, what: String) {
        val difference = if (expected > actual) expected - actual else actual - expected
        assertTrue(difference < TOLERANCE, "$what: expected $expected, got $actual")
    }
}

private val NO_MARGINS: Margins = Margins.Fixed(0.pt, 0.pt, 0.pt, 0.pt)

/** The width of A4 in points, as an application computing in doubles has it. */
private const val A4_WIDTH: Double = 595.2755905511812

private val COVER: Pt = A4_WIDTH.pt

/** A thousandth of a point. */
private const val TOLERANCE: Float = 0.001f
