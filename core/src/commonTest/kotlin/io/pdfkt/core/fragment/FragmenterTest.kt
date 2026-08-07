/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.fragment

import io.pdfkt.core.block.ImageFit
import io.pdfkt.core.font.FontCatalog
import io.pdfkt.core.font.FontRef
import io.pdfkt.core.font.Standard14Name
import io.pdfkt.core.image.ImageColorSpace
import io.pdfkt.core.image.ImageRef
import io.pdfkt.core.model.Insets
import io.pdfkt.core.model.Pt
import io.pdfkt.core.model.Rect
import io.pdfkt.core.model.Rgb
import io.pdfkt.core.model.Size
import io.pdfkt.core.model.pt
import io.pdfkt.core.plan.PagePlan
import io.pdfkt.core.plan.PlacedItem
import io.pdfkt.core.resolve.StyledBlock
import io.pdfkt.core.resolve.StyledCode
import io.pdfkt.core.resolve.StyledImage
import io.pdfkt.core.resolve.StyledList
import io.pdfkt.core.resolve.StyledListItem
import io.pdfkt.core.resolve.StyledPageBreak
import io.pdfkt.core.resolve.StyledParagraph
import io.pdfkt.core.resolve.StyledQuote
import io.pdfkt.core.resolve.StyledRule
import io.pdfkt.core.resolve.StyledTextRun
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

// No clause applies: L4 is this library's own design. What is asserted is the plan — coordinates and order —
// which is the whole point of planning before drawing.

/**
 * The fragmenter against its three rules: the
 * [FitResult] protocol, the vertical cursor, and the progress guard.
 *
 * Everything here reads plans rather than files. The same behaviour is asserted
 * through a real PDF by `FragmentRenderTest`, where a text extractor that never
 * saw this library reports what actually reached the pages — the two are the
 * same claim checked from the two ends.
 */
class FragmenterTest {
    // ------------------------------------------------- the page sequence

    /**
     * Sixty paragraphs do not fit one page, and every one of them is on some page
     * in the order it was written.
     *
     * This is the core guarantee, asserted on the plan: the extraction
     * half of it is `FragmentRenderTest`.
     */
    @Test
    fun `sixty paragraphs fill more than one page and every paragraph is placed in order`() {
        val paragraphs = (1..SIXTY).map { number -> paragraph("Paragraph number $number of the document") }

        val pages = fragmenter().fragment(paragraphs)

        assertTrue(pages.size >= 2, "sixty paragraphs came out on ${pages.size} page")
        assertEquals(
            paragraphs.joinToString(separator = " ") { block -> textOf(block) },
            pages.joinToString(separator = " ") { page -> textOf(page) }.collapseSpaces(),
        )
    }

    /** No page is left empty on the way: a page exists because something was placed on it. */
    @Test
    fun `every page of a long document carries something`() {
        val pages = fragmenter().fragment((1..SIXTY).map { number -> paragraph("Paragraph $number") })

        assertTrue(pages.all { page -> page.items.isNotEmpty() }, "a page came out with nothing on it")
    }

    /** A document of no blocks is one page with nothing on it, not a file with no pages. */
    @Test
    fun `a document of no blocks is a single blank page`() {
        val pages = fragmenter().fragment(emptyList())

        assertEquals(1, pages.size)
        assertTrue(pages.single().items.isEmpty())
    }

    // ----------------------------------------------- the vertical cursor

    /** The cursor only ever moves down a page: baseline after baseline, in reading order. */
    @Test
    fun `baselines descend down every page`() {
        val pages = fragmenter().fragment((1..SIXTY).map { number -> paragraph("Paragraph $number") })

        for (page in pages) {
            val baselines = page.items.filterIsInstance<PlacedItem.Text>().map { text -> text.y.value }
            assertEquals(baselines.sortedDescending(), baselines, "the cursor went back up a page")
        }
    }

    /** Nothing is placed outside the content area the template reserved. */
    @Test
    fun `every line sits inside the content area`() {
        val template = template()
        val pages = Fragmenter(FontCatalog(), template).fragment(
            (1..SIXTY).map { number -> paragraph("Paragraph number $number of the document") },
        )

        for (page in pages) {
            for (text in page.items.filterIsInstance<PlacedItem.Text>()) {
                assertTrue(
                    text.y >= template.content.bottom && text.y <= template.content.top,
                    "a baseline at ${text.y} is outside ${template.content}",
                )
                assertTrue(text.x >= template.content.left, "a line starts at ${text.x}, left of the column")
            }
        }
    }

    /**
     * The gap between two blocks is the larger of the space one asks for after it
     * and the next asks for before it, not the sum of the two.
     */
    @Test
    fun `spacing between two blocks collapses to the larger of the two`() {
        val first = paragraph("One", spacingAfter = 20.pt)
        val second = paragraph("Two", spacingBefore = 8.pt)
        val alone = paragraph("Two")

        val collapsed = baselines(fragmenter().fragment(listOf(first, second)))
        val plain = baselines(fragmenter().fragment(listOf(first.copy(spacingAfter = Pt.ZERO), alone)))

        assertEquals(2, collapsed.size)
        assertEquals(
            20.0f,
            plain[1] - collapsed[1],
            TOLERANCE,
            "the gap was neither the larger of the two spacings nor even their sum",
        )
    }

    /** Spacing before a block is dropped at the top of a page: the first line sits at the top margin. */
    @Test
    fun `a block that starts a page starts at the top of it`() {
        val template = template()
        val spaced = paragraph("Paragraph", spacingBefore = 30.pt)
        val pages = Fragmenter(FontCatalog(), template).fragment(listOf(spaced))

        val first = pages.single().items.filterIsInstance<PlacedItem.Text>().first()
        assertTrue(
            template.content.top - first.y < 30.pt,
            "the first line of the page was pushed down by the spacing before its block",
        )
    }

    // --------------------------------------------------- the page break

    @Test
    fun `a page break starts a new page`() {
        val pages = fragmenter().fragment(listOf(paragraph("Before"), StyledPageBreak, paragraph("After")))

        assertEquals(2, pages.size)
        assertEquals("Before", textOf(pages[0]).collapseSpaces())
        assertEquals("After", textOf(pages[1]).collapseSpaces())
    }

    /** A break at the top of an empty page asks for a page that is already there. */
    @Test
    fun `a page break at the top of an empty page leaves no blank page`() {
        val pages = fragmenter().fragment(listOf(StyledPageBreak, paragraph("Only")))

        assertEquals(1, pages.size)
        assertEquals("Only", textOf(pages.single()).collapseSpaces())
    }

    /**
     * A trailing break adds no page — which is what keeps a photo-per-page
     * document, where each photo becomes an ImageBlock followed by a forced
     * PageBreakBlock, from ending in a blank page.
     */
    @Test
    fun `a trailing page break leaves no blank page`() {
        val pages = fragmenter().fragment(listOf(paragraph("Only"), StyledPageBreak))

        assertEquals(1, pages.size)
    }

    // ------------------------------------------------- the split of a block

    /** A paragraph longer than a page continues on the next one, with every word kept. */
    @Test
    fun `a paragraph taller than the page splits and keeps every word`() {
        val text = (1..120).joinToString(separator = " ") { number -> "word$number" }
        val paragraph = paragraph(text)

        val pages = fragmenter().fragment(listOf(paragraph))

        assertTrue(pages.size >= 2, "a paragraph of 120 words fitted one ${template().content.height} page")
        assertEquals(text, pages.joinToString(separator = " ") { page -> textOf(page) }.collapseSpaces())
    }

    /** The lines of a split paragraph do not overlap the split: page two starts where page one stopped. */
    @Test
    fun `a split paragraph keeps its lines in order`() {
        val words = (1..120).map { number -> "word$number" }
        val pages = fragmenter().fragment(listOf(paragraph(words.joinToString(separator = " "))))

        val placed = pages.flatMap { page -> textOf(page).collapseSpaces().split(" ") }.filter { it.isNotEmpty() }
        assertContentEquals(words, placed)
    }

    /** Half a paragraph at the foot of a page is the Splits answer of the protocol. */
    @Test
    fun `a paragraph that half fits answers Splits`() {
        val planner = BlockPlanner(FontCatalog())
        val paragraph = paragraph((1..40).joinToString(separator = " ") { number -> "word$number" })

        val fit = planner.planBlock(paragraph, COLUMN, 40.pt, atPageTop = false)

        val splits = assertIs<FitResult.Splits>(fit)
        assertTrue(splits.consumed > Pt.ZERO, "the head of the split took no height")
        assertTrue(splits.head.isNotEmpty(), "the head of the split placed nothing")
        assertEquals(1, splits.tail.size)
        assertIs<StyledParagraph>(splits.tail.single())
    }

    /** A paragraph whose first line does not fit what is left answers Defers, and moves whole. */
    @Test
    fun `a paragraph that does not fit at all answers Defers`() {
        val planner = BlockPlanner(FontCatalog())

        val fit = planner.planBlock(paragraph("One line"), COLUMN, 3.pt, atPageTop = false)

        assertEquals(FitResult.Defers, fit)
    }

    /** Everything that fits answers Fits, and reports how far down it reached. */
    @Test
    fun `a paragraph that fits answers Fits and reports what it took`() {
        val planner = BlockPlanner(FontCatalog())

        val fit = planner.planBlock(paragraph("One line"), COLUMN, 200.pt, atPageTop = true)

        val fits = assertIs<FitResult.Fits>(fit)
        assertEquals(1, fits.plan.size)
        assertTrue(fits.consumed > Pt.ZERO && fits.consumed < 20.pt, "one 12pt line took ${fits.consumed}")
    }

    // ------------------------------------------------- the progress guard

    /**
     * A line taller than the whole content area is an error and not a run of
     * blank pages — the progress guard. The block consumes
     * zero height on a page that is empty, there is no emptier page to move it
     * to, and a fragmenter without the guard would add pages for ever.
     */
    @Test
    fun `a line taller than the whole page is refused rather than looping`() {
        val tiny = PageTemplate(Size(200.pt, 100.pt), Rect(20.pt, 20.pt, 160.pt, 6.pt))

        val refused = assertFailsWith<UnplaceableBlockException> {
            Fragmenter(FontCatalog(), tiny).fragment(listOf(paragraph("Too tall", size = 24.pt)))
        }

        assertTrue(refused.message!!.contains("empty page"), "the guard did not say what it refused: $refused")
        assertEquals(6.pt, refused.available)
    }

    /**
     * An image whose height the caller fixed cannot be split and may not be
     * quietly resized: it is refused, explicitly, which is the other half of the
     * guard — "if it cannot split (an oversized image), it scales or errors".
     */
    @Test
    fun `an image the caller sized taller than the page is refused`() {
        val block = StyledImage(image = image(), fit = ImageFit.Stretch, width = 100.pt, height = 500.pt)

        assertFailsWith<UnplaceableBlockException> { fragmenter().fragment(listOf(block)) }
    }

    /** An image left to Contain is scaled to the page instead of being refused. */
    @Test
    fun `an image left to Contain is scaled down to the page`() {
        val template = template()
        val block = StyledImage(image = image(width = 200, height = 400), fit = ImageFit.Contain)

        val pages = Fragmenter(FontCatalog(), template).fragment(listOf(block))

        val placed = pages.single().items.filterIsInstance<PlacedItem.Image>().single()
        assertTrue(placed.box.height <= template.content.height + Pt(TOLERANCE), "it is ${placed.box.height} high")
        assertTrue(placed.box.width <= template.content.width + Pt(TOLERANCE), "it is ${placed.box.width} wide")
        assertEquals(0.5f, placed.box.width / placed.box.height, TOLERANCE, "the aspect ratio was not kept")
    }

    // ------------------------------------------------------ the other blocks

    /** A rule takes its thickness out of the page and draws exactly that. */
    @Test
    fun `a rule takes its thickness and draws across the column`() {
        val template = template()
        val rule = StyledRule(thickness = 2.pt, color = Rgb.BLACK)

        val pages = Fragmenter(FontCatalog(), template).fragment(listOf(rule))

        val box = pages.single().items.filterIsInstance<PlacedItem.Rect>().single().box
        assertEquals(template.content.width.value, box.width.value, TOLERANCE)
        assertEquals(2.0f, box.height.value, TOLERANCE)
        assertEquals(template.content.top.value, box.top.value, TOLERANCE)
    }

    /** A list draws a marker beside the first line of every item, in order. */
    @Test
    fun `a list places a marker on the first line of each item`() {
        val list = StyledList(
            items = (1..3).map { number ->
                StyledListItem(marker = run("$number."), indent = 18.pt, content = listOf(paragraph("Item $number")))
            },
        )

        val pages = fragmenter().fragment(listOf(list))

        val texts = pages.single().items.filterIsInstance<PlacedItem.Text>()
        assertContentEquals(
            listOf("1.", "Item 1", "2.", "Item 2", "3.", "Item 3"),
            texts.map { text -> textOf(text).trim() },
        )
        for (index in texts.indices step 2) {
            assertEquals(texts[index].y, texts[index + 1].y, "a marker is not on the baseline of its item")
            assertTrue(texts[index].x < texts[index + 1].x, "a marker is not to the left of its item")
        }
    }

    /**
     * Two items are set apart by the spacing of their own blocks, collapsed the
     * way the spacing between two blocks is.
     *
     * Without it the items sit flush, since what a block consumes is measured to
     * the ink of its last line rather than to the leading below it: the
     * descenders of one item and the ascenders of the next then land in the same
     * strip of the page, which a rendered page shows as one line where the
     * document has two.
     */
    @Test
    fun `a list sets its items apart by the spacing their blocks ask for`() {
        fun listOfItems(spacingAfter: Pt) = StyledList(
            items = (1..3).map { number ->
                StyledListItem(
                    marker = run("$number."),
                    indent = 18.pt,
                    content = listOf(paragraph("Item $number", spacingAfter = spacingAfter)),
                )
            },
        )

        val flush = itemBaselines(fragmenter().fragment(listOf(listOfItems(Pt.ZERO))))
        val spaced = itemBaselines(fragmenter().fragment(listOf(listOfItems(10.pt))))

        assertEquals(3, spaced.size, "an item went missing")
        assertEquals(flush[0], spaced[0], TOLERANCE, "the first item moved, and nothing is above it")
        for (index in 1..2) {
            assertEquals(
                10.0f * index,
                flush[index] - spaced[index],
                TOLERANCE,
                "item ${index + 1} was not set ${10 * index} points further down by the spacing its " +
                    "paragraph asked for",
            )
        }
    }

    /** A list too long for the page continues on the next one, with no item repeated or lost. */
    @Test
    fun `a list splits between pages with every item kept once`() {
        val list = StyledList(
            items = (1..40).map { number ->
                StyledListItem(marker = run("$number."), indent = 18.pt, content = listOf(paragraph("Item $number")))
            },
        )

        val pages = fragmenter().fragment(listOf(list))

        assertTrue(pages.size >= 2, "forty items fitted one page")
        val texts = pages.flatMap { page -> page.items.filterIsInstance<PlacedItem.Text>() }
        assertContentEquals(
            (1..40).flatMap { number -> listOf("$number.", "Item $number") },
            texts.map { text -> textOf(text).trim() },
        )
    }

    /** A quotation's bar is as tall as the part of it that was placed, and no taller. */
    @Test
    fun `a quotation draws its bar down to where its text stops`() {
        val quote = StyledQuote(
            children = listOf(paragraph("Quoted text")),
            barColor = Rgb.BLACK,
            barWidth = 3.pt,
            indentLeft = 12.pt,
        )
        val template = template()

        val pages = Fragmenter(FontCatalog(), template).fragment(listOf(quote))

        val bar = pages.single().items.filterIsInstance<PlacedItem.Rect>().single()
        val text = pages.single().items.filterIsInstance<PlacedItem.Text>().single()
        assertEquals(3.0f, bar.box.width.value, TOLERANCE)
        assertEquals(template.content.top.value, bar.box.top.value, TOLERANCE)
        assertTrue(bar.box.bottom < text.y, "the bar stops above the baseline of the text it marks")
        assertEquals(template.content.left.value + 12.0f, text.x.value, TOLERANCE)
    }

    /** A code block keeps the caller's line endings and draws its panel behind the text. */
    @Test
    fun `a code block keeps its own line endings on a panel`() {
        val code = StyledCode(
            lines = listOf(run("fun main {"), run("    println"), run("}")),
            lineHeight = 1.2f,
            background = Rgb.gray(0.9f),
            padding = 6.pt,
        )

        val pages = fragmenter().fragment(listOf(code))

        val texts = pages.single().items.filterIsInstance<PlacedItem.Text>()
        assertContentEquals(listOf("fun main {", "    println", "}"), texts.map { text -> textOf(text) })
        val panel = pages.single().items.filterIsInstance<PlacedItem.Rect>().single()
        assertTrue(panel.box.top > texts.first().y, "the panel does not reach above the first line")
        assertTrue(panel.box.bottom < texts.last().y, "the panel does not reach below the last line")
    }

    // ------------------------------------------------------------ fixtures

    private fun fragmenter(): Fragmenter = Fragmenter(FontCatalog(), template())

    private fun template(): PageTemplate = PageTemplate.of(Size(340.pt, 260.pt), Insets.uniform(20.pt))

    private fun paragraph(
        text: String,
        size: Pt = 12.pt,
        spacingBefore: Pt = Pt.ZERO,
        spacingAfter: Pt = Pt.ZERO,
    ): StyledParagraph = StyledParagraph(
        content = listOf(run(text, size)),
        lineHeight = 1.2f,
        spacingBefore = spacingBefore,
        spacingAfter = spacingAfter,
    )

    private fun run(text: String, size: Pt = 12.pt): StyledTextRun =
        StyledTextRun(text, FontRef.Standard14(Standard14Name.HELVETICA), size, Rgb.BLACK)

    private fun image(width: Int = 100, height: Int = 100): ImageRef = ImageRef(
        id = "fixture-$width-$height",
        width = width,
        height = height,
        colorSpace = ImageColorSpace.DEVICE_GRAY,
        data = ByteArray(width * height),
    )

    private fun textOf(block: StyledBlock): String =
        (block as StyledParagraph).content.filterIsInstance<StyledTextRun>().joinToString("") { run -> run.text }

    private fun textOf(page: PagePlan): String =
        page.items.filterIsInstance<PlacedItem.Text>().joinToString(separator = " ") { text -> textOf(text) }

    private fun textOf(text: PlacedItem.Text): String = text.runs.joinToString(separator = "") { run -> run.text }

    /**
     * The baseline of each item of a list, once each: a marker and the first
     * line of the item it marks share one, which is what makes them one line of
     * the page.
     */
    private fun itemBaselines(pages: List<PagePlan>): List<Float> = baselines(pages).distinct()

    private fun baselines(pages: List<PagePlan>): List<Float> =
        pages.flatMap { page -> page.items.filterIsInstance<PlacedItem.Text>().map { text -> text.y.value } }

    private fun String.collapseSpaces(): String = split(" ").filter { part -> part.isNotEmpty() }.joinToString(" ")

    private companion object {
        const val SIXTY: Int = 60

        val COLUMN: Pt = 300.pt

        const val TOLERANCE: Float = 1.0e-2f
    }
}
