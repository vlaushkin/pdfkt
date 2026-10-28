/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.fragment

import io.pdfkt.core.block.VerticalAlign
import io.pdfkt.core.font.FontCatalog
import io.pdfkt.core.model.Insets
import io.pdfkt.core.model.Pt
import io.pdfkt.core.model.Rect
import io.pdfkt.core.model.Size
import io.pdfkt.core.plan.PagePlan
import io.pdfkt.core.resolve.StyledBlock

// No clause applies: L4 is this library's own design. §8.3.2.3 is where the coordinates it places into come
// from — the origin at the bottom left of the page, y growing upwards.

/**
 * The page a document is laid out on: how big it is, and the part of it the
 * content goes in.
 *
 * The two are separate on purpose. The fragmenter is given the area *between*
 * the bands and never knows they exist: a header is a block tree planned into the strip above [content] by
 * whoever reserved it, and to the fragmenter a reserved strip and a wide margin
 * are the same thing.
 *
 * @property size the page — `/MediaBox` (§7.7.3.3) once the plan is emitted.
 * @property content where the blocks go, in page coordinates: origin at the
 *   bottom left of the page, y growing upwards.
 */
public data class PageTemplate(public val size: Size, public val content: Rect) {
    init {
        require(content.width > Pt.ZERO && content.height > Pt.ZERO) {
            "the content area of a page is ${content.width} by ${content.height}: a column with no " +
                "width or no height is one that nothing can be laid out in " +
                ""
        }
        require(content.left >= Pt.ZERO && content.bottom >= Pt.ZERO) {
            "the content area starts at (${content.left}, ${content.bottom}), which is off the page"
        }
        require(content.right <= size.width && content.top <= size.height) {
            "the content area reaches (${content.right}, ${content.top}) on a page that is $size: " +
                "text laid out there would be outside the media box and a reader would clip it " +
                "(ISO 32000-2:2020 §7.7.3.3)"
        }
    }

    public companion object {
        /** A page of [size] with [margins] kept clear on all four sides. */
        public fun of(size: Size, margins: Insets): PageTemplate = PageTemplate(size, size.toRect().deflate(margins))
    }
}

/**
 * Styled blocks in, page plans out — the fragment stage of the pipeline, and
 * the owner of the vertical cursor and the page sequence.
 *
 * ```
 * val pages = Fragmenter(fonts, PageTemplate.of(Size.A4, Insets.uniform(56.pt)))
 *     .fragment(Resolver(theme, fonts).resolve(blocks))
 * ```
 *
 * The loop is the whole of it: plan as much of what is left as fits the content
 * area, move what was placed onto the page, and start another page with the
 * rest. The arrangement *within* a page — the stacking, the spacing, the
 * splitting — is [BlockPlanner.planSequence], which is the same code a quotation
 * and a list item are planned by; the fragmenter adds the two things only a page
 * has, which are the page break and the progress guard.
 *
 * ## The progress guard
 *
 * The rule is plain — if a page is empty and the block still answers `Defers`
 * or consumes zero height, that is an error, not an
 * infinite loop of blank pages — and it is enforced in two places for one
 * reason: a loop that produces blank pages for ever is the failure that cannot
 * be debugged from the output, because there is no output.
 *
 * The planner raises [UnplaceableBlockException] the moment a block asks for a
 * page emptier than an empty one, naming the block. The loop here re-checks that
 * every turn actually placed something or shortened what is left, and raises
 * before it would go round a second time on the same input. The second check
 * cannot fire while the first one holds; it is there because "cannot" is a claim
 * about today's planners and the guard has to hold for tomorrow's.
 *
 * @param fonts the document's font catalog, which is also the emitter's: a font
 *   measured from one catalog and written from another would be two fonts.
 * @param template the page and the area on it that the content goes in.
 * @param verticalAlign where a page's content sits in the content area when it
 *   does not fill it: hung from the top, as running text is, or centred or
 *   stood on the bottom, as a title page is. Each page is aligned on its own,
 *   by what was placed on it.
 */
public class Fragmenter(
    fonts: FontCatalog,
    private val template: PageTemplate,
    private val verticalAlign: VerticalAlign = VerticalAlign.Top,
) {
    private val planner: BlockPlanner = BlockPlanner(fonts)

    /**
     * [blocks] laid out, one [PagePlan] per page, in reading order.
     *
     * Every block reaches a page: what does not fit one page continues on the
     * next, and what cannot be placed on a page of its own raises rather than
     * being dropped.
     *
     * @return at least one page. A document of no blocks is a blank page rather
     *   than no pages at all — a PDF file "shall" have a page tree with pages in
     *   it (§7.7.3.2), so an empty document is a page with nothing on it and not
     *   a file a reader refuses.
     * @throws UnplaceableBlockException if a block cannot be placed on an empty
     *   page.
     */
    public fun fragment(blocks: List<StyledBlock>): List<PagePlan> {
        val pages = mutableListOf<PagePlan>()
        var pending = blocks
        while (pending.isNotEmpty()) {
            val fit = planner.planSequence(
                blocks = pending,
                width = template.content.width,
                available = template.content.height,
                atPageTop = true,
            )
            val placed = when (fit) {
                is FitResult.Fits -> fit.plan
                is FitResult.Splits -> fit.head
                FitResult.Defers -> throw UnplaceableBlockException.deferredOnEmptyPage(
                    pending.first(),
                    template.content.width,
                    template.content.height,
                )
            }
            val rest = if (fit is FitResult.Splits) fit.tail else emptyList()
            check(placed.isNotEmpty() || rest.size < pending.size) {
                "the page took nothing and left as much behind as it was given: laying out " +
                    "${pending.size} blocks starting with a ${pending.first()::class.simpleName} " +
                    "would add a blank page for ever. This is the progress guard " +
                    "holding where a planner did not"
            }
            val consumed = when (fit) {
                is FitResult.Fits -> fit.consumed
                is FitResult.Splits -> fit.consumed
                FitResult.Defers -> Pt.ZERO
            }
            val drop = slack(consumed)
            val onThePage = placed.map { item -> item.translated(template.content.left, template.content.top - drop) }
            pages += PagePlan(template.size, onThePage)
            pending = rest
        }
        return pages.ifEmpty { listOf(PagePlan(template.size)) }
    }

    /**
     * How far below the top of the content area a page whose content reaches
     * [consumed] down starts, for [verticalAlign].
     *
     * Content that overfills the area — by no more than the planners' tolerance,
     * since nothing else is placed — is hung from the top rather than pushed up
     * past it.
     */
    private fun slack(consumed: Pt): Pt {
        val room = maxOf(template.content.height - consumed, Pt.ZERO)
        return when (verticalAlign) {
            VerticalAlign.Top -> Pt.ZERO
            VerticalAlign.Middle -> room / 2.0f
            VerticalAlign.Bottom -> room
        }
    }
}
