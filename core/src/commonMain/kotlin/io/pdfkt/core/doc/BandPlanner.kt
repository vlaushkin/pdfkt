/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.doc

import io.pdfkt.core.block.SectionBlock
import io.pdfkt.core.font.FontCatalog
import io.pdfkt.core.fragment.BlockPlanner
import io.pdfkt.core.fragment.FitResult
import io.pdfkt.core.fragment.UnplaceableBlockException
import io.pdfkt.core.fragment.translated
import io.pdfkt.core.model.Pt
import io.pdfkt.core.model.Rect
import io.pdfkt.core.plan.PlacedItem
import io.pdfkt.core.resolve.Resolver
import io.pdfkt.core.resolve.StyledBlock
import io.pdfkt.core.style.Theme

// No clause applies: L4 is this library's own design.

/**
 * The bands of every page, planned into the strips reserved for them.
 *
 * What a band is, is mostly what this class does *not* do. A band is not a
 * special mechanism: a header is a block tree planned into a reserved
 * band at the top of the page; a footer likewise at the bottom. The fragmenter
 * receives the content area *between* the bands and never knows the bands
 * exist. Page-number substitution happens at resolve time for the page being
 * planned — which is possible precisely because planning is per page.
 *
 * So there is no band layout here. A band is resolved by the same [Resolver] and
 * stacked by the same [BlockPlanner] as the body of the document, against the
 * width and the height of its strip instead of those of the content area; the
 * only thing this class adds is the three things that are true of a band and of
 * nothing else.
 *
 * - **It is planned per page, from the page numbering.** [BandSpec.content] is a
 *   function of [PageNumbering], and calling it is the substitution
 *   that happens at resolve time: the blocks that come back go through
 *   `resolve` like any others, so a page number is ordinary text by the time
 *   anything measures it.
 * - **It is planned after the content has been paginated**, because the count in
 *   "Page 3 of 12" is not known before then. This is what the reserve buys: the
 *   strip's height does not depend on what goes in it, so the content area — and
 *   with it the number of pages — is fixed before a band is looked at.
 * - **Its strip is settled before the content is paginated** ([reserve]): a
 *   band with no height of its own, or one that may grow, is measured and the
 *   content paginated against the strip that came out, as many times as it
 *   takes the strip to stop growing.
 * - **What does not fit its strip is [BandSpec.overflow]'s to decide** —
 *   clipped, or the document refused ([BandOverflowException]); every page's
 *   bands are planned before the first page is emitted, so a refusal comes
 *   before a byte of the file, not at page 37.
 *
 * @param theme the theme the document is set in — a band is set in it too, so a
 *   footer picks up the body font without being told it.
 * @param fonts the document's font catalog, which is also the fragmenter's and
 *   the emitter's: a font measured from one catalog and written from another
 *   would be two fonts.
 */
internal class BandPlanner(theme: Theme, fonts: FontCatalog) {
    private val resolver = Resolver(theme, fonts)
    private val planner = BlockPlanner(fonts)

    /**
     * What each page of [spec] carries in its bands, in page order: entry `i` is
     * the header and then the footer of page `i + 1`, already in page
     * coordinates, and is empty for a page [BandPages] draws neither on.
     *
     * @param pages the paper of each page the content was fragmented onto, in
     *   order: how many there are is the `of 12` of every band on every page,
     *   and each page's strips are cut from its own size and margins.
     * @throws BandOverflowException if any page's band is taller than its strip.
     *   The whole document is planned before anything is returned, so a footer
     *   that only overflows once the page count reaches three digits is found
     *   before the file is written rather than while it is.
     */
    fun plan(spec: DocumentSpec, pages: List<PageSetup>): List<List<PlacedItem>> {
        if (spec.header == null && spec.footer == null) return List(pages.size) { emptyList() }
        return pages.mapIndexed { index, setup ->
            val numbering = PageNumbering(index + 1, pages.size)
            val probe = spec.printableArea(setup.size, setup.margins).height
            planBand(spec.header, spec.headerArea(setup.size, setup.margins), BandPosition.Header, numbering, probe) +
                planBand(spec.footer, spec.footerArea(setup.size, setup.margins), BandPosition.Footer, numbering, probe)
        }
    }

    /**
     * [spec] with the strip of each band settled, and its content paginated
     * against them by [layOut].
     *
     * A band that may not grow ([BandOverflow.Clip] or [BandOverflow.Fail]
     * with a height of its own) keeps its height, and a document of only such
     * bands is laid out once. Any other band starts from its height, or from
     * what it measures on the first page it is drawn on, and the content is
     * paginated against that; then the band is measured on every page that
     * came out, with that page's numbering and against that page's width, and
     * when it needs more than it was given the content is paginated again.
     * The strip only ever grows, so the passes end — the growth is capped
     * ([growthLimit]) and the passes counted ([MAX_PASSES]); a band still
     * taller than its strip after that is clipped or refused when it is
     * planned, as its [BandSpec.overflow] says.
     */
    fun <P> reserve(
        spec: DocumentSpec,
        layOut: (DocumentSpec) -> List<Pair<P, PageSetup>>,
    ): Pair<DocumentSpec, List<Pair<P, PageSetup>>> {
        if (!spec.header.grows && !spec.footer.grows) return spec to layOut(spec)
        val limit = growthLimit(spec)
        val document = PageSetup(spec.pageSize, spec.margins)
        var head = seed(spec.header, spec, document, limit)
        var foot = seed(spec.footer, spec, document, limit)
        var settled = spec.reserving(head, foot)
        var pages = layOut(settled)
        repeat(MAX_PASSES) {
            val numbered = pages.map { it.second }
            val nextHead = grown(spec.header, head, settled, numbered, limit)
            val nextFoot = grown(spec.footer, foot, settled, numbered, limit)
            if (nextHead == head && nextFoot == foot) return settled to pages
            head = nextHead
            foot = nextFoot
            settled = spec.reserving(head, foot)
            pages = layOut(settled)
        }
        return settled to pages
    }

    /**
     * The strip [band] starts from: its own height, or — when it has none, or
     * may grow past it — what its content measures on the first page it is
     * drawn on, numbered as though the document were that long.
     *
     * The guess is only a start: a band that reads the page count is measured
     * again once the count is known. Content that cannot be measured here — a
     * band block that refuses the numbering it is given — starts from nothing
     * and is measured against the real pages instead, where it is planned.
     */
    private fun seed(band: BandSpec?, spec: DocumentSpec, document: PageSetup, limit: Pt): Pt {
        if (band == null) return Pt.ZERO
        val floor = band.height ?: Pt.ZERO
        if (!band.grows) return floor
        val first = if (band.pages == BandPages.ExceptFirst) PageNumbering(2, 2) else PageNumbering(1, 1)
        val measured = runCatching {
            measure(band, first, spec.printableArea(document.size, document.margins))
        }.getOrNull() ?: Pt.ZERO
        return minOf(limit, maxOf(floor, measured))
    }

    /**
     * The strip [band] needs on [pages], given that it has [current]: the
     * tallest it measures on any page it is drawn on, capped at [limit], and
     * never less than [current] — a strip that shrank could let the content
     * back onto fewer pages, and a band that reads the count could then
     * measure taller again.
     */
    private fun grown(band: BandSpec?, current: Pt, spec: DocumentSpec, pages: List<PageSetup>, limit: Pt): Pt {
        if (band == null || !band.grows) return current
        var needed = current
        for ((index, setup) in pages.withIndex()) {
            val numbering = PageNumbering(index + 1, pages.size)
            if (!band.pages.appliesTo(numbering.page)) continue
            val printable = spec.printableArea(setup.size, setup.margins)
            // A band taller than the whole printable height has no single
            // height to grow to; the limit is as far as it may.
            needed = maxOf(needed, measure(band, numbering, printable) ?: limit)
            if (needed >= limit) return limit
        }
        return minOf(needed, limit)
    }

    /**
     * How tall [band]'s content is on page [numbering], set across the width
     * of [printable] and against its whole height, or null when it does not fit
     * even that.
     */
    private fun measure(band: BandSpec, numbering: PageNumbering, printable: Rect): Pt? {
        val blocks = resolver.resolve(band.content(numbering))
        val fit =
            try {
                planner.planSequence(blocks, width = printable.width, available = printable.height, atPageTop = true)
            } catch (tooTall: UnplaceableBlockException) {
                return null
            }
        return (fit as? FitResult.Fits)?.consumed
    }

    /**
     * How tall a growing band may become: a quarter of the printable height of
     * the smallest page the document is set on — its own paper, or a section's
     * that is not cut to its content. A header and a footer at the limit still
     * leave half the page to the text.
     */
    private fun growthLimit(spec: DocumentSpec): Pt {
        val papers = listOf(PageSetup(spec.pageSize, spec.margins)) +
            spec.content.filterIsInstance<SectionBlock>().filterNot { it.fitHeight }
                .map { section -> PageSetup(section.pageSize, section.margins) }
        val smallest = papers.minOf { setup -> spec.printableArea(setup.size, setup.margins).height }
        return smallest / GROWTH_SHARE
    }

    /**
     * One band on one page: its blocks for [numbering], planned into [area].
     *
     * The items come back in the coordinates every planner works in — the origin
     * at the top left of the space it was given, the content hanging below it at
     * negative y ([FitResult]) — and are translated once by the top-left corner
     * of the strip, exactly as the fragmenter translates a page's content by the
     * corner of the content area. A band is therefore set from the top of its
     * strip downwards: a footer taller than its one line leaves the gap below the
     * line, where the edge of the paper is, and not above it, where the text is.
     */
    private fun planBand(
        band: BandSpec?,
        area: Rect?,
        position: BandPosition,
        numbering: PageNumbering,
        probe: Pt,
    ): List<PlacedItem> {
        if (band == null || !band.pages.appliesTo(numbering.page)) return emptyList()
        // Non-null wherever the band is: the two are the same `let` over the same
        // property in DocumentSpec.
        val strip = requireNotNull(area) { "a $position band with no strip reserved for it" }
        val blocks = resolver.resolve(band.content(numbering))
        val fit =
            try {
                planner.planSequence(blocks, width = strip.width, available = strip.height, atPageTop = true)
            } catch (tooTall: UnplaceableBlockException) {
                // A band whose first line alone is taller than the strip. The
                // guard that raised is about pages that would repeat for ever;
                // here the same fact has a better name and a better answer —
                // and, unless the band asked to be refused, a page that draws
                // nothing of it.
                if (band.overflow == BandOverflow.Fail) {
                    throw overflow(position, numbering, strip, blocks, probe, tooTall)
                }
                return emptyList()
            }
        return when (fit) {
            is FitResult.Fits -> anchored(fit.plan, fit.consumed, strip, position)
            // Both of the other answers say the same thing — not all of it went
            // in — and neither can be honoured as it is anywhere else: a band
            // has no next page to continue onto, since the page it is on is the
            // one it names. What fitted is drawn, unless the band asked to be
            // refused instead.
            is FitResult.Splits ->
                if (band.overflow == BandOverflow.Fail) {
                    throw overflow(position, numbering, strip, blocks, probe, null)
                } else {
                    anchored(fit.head, fit.consumed, strip, position)
                }
            FitResult.Defers ->
                if (band.overflow == BandOverflow.Fail) {
                    throw overflow(position, numbering, strip, blocks, probe, null)
                } else {
                    emptyList()
                }
        }
    }

    /**
     * [plan], [consumed] tall, put in its [strip]. A header hangs from the top
     * of its strip; a footer stands on the bottom of its own. Planning is
     * top-down either way — the anchor decides where the unused part of the
     * strip ends up: below a header's text, above a footer's. A footer floating
     * at the top of its strip reads as a wrong margin, which is how this was
     * found.
     */
    private fun anchored(plan: List<PlacedItem>, consumed: Pt, strip: Rect, position: BandPosition): List<PlacedItem> {
        val drop = when (position) {
            BandPosition.Header -> Pt.ZERO
            BandPosition.Footer -> strip.height - consumed
        }
        return plan.map { item -> item.translated(strip.left, strip.top - drop) }
    }

    /**
     * The refusal, with the height the band actually needs where that can be
     * measured.
     *
     * Measuring is planning once more, against the whole printable height of the
     * page rather than the strip — the same trick `keepWithNext` measures a group
     * with, and honest in the same way: it answers [FitResult.Fits] exactly when
     * the content fits a container of that height, and its `consumed` is then the
     * number the caller has to raise [BandSpec.height] to. Content that does not
     * fit even a whole page has no such number, and the message says so instead
     * of inventing one.
     */
    private fun overflow(
        position: BandPosition,
        numbering: PageNumbering,
        strip: Rect,
        blocks: List<StyledBlock>,
        probe: Pt,
        cause: Throwable?,
    ): BandOverflowException {
        val measured =
            try {
                planner.planSequence(blocks, width = strip.width, available = probe, atPageTop = true)
            } catch (tooTall: UnplaceableBlockException) {
                FitResult.Defers
            }
        return BandOverflowException(
            position = position,
            numbering = numbering,
            reserved = strip.height,
            required = (measured as? FitResult.Fits)?.consumed,
            cause = cause,
        )
    }

    private companion object {
        /**
         * How many times the content is paginated again after the first,
         * against a strip that grew. Two settle every band that reads the page
         * count — the count can only grow so far before its digits stop
         * changing — and the rest are margin.
         */
        const val MAX_PASSES: Int = 4

        /** The share of the smallest printable height a band may grow to: one in four. */
        const val GROWTH_SHARE: Float = 4.0f
    }
}

/** Whether this band's strip is settled by measuring its content. */
private val BandSpec?.grows: Boolean
    get() = this != null && (height == null || overflow == BandOverflow.Grow)
