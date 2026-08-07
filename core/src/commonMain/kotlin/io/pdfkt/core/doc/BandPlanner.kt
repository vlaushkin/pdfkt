/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.doc

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
 * - **It fits its strip or the document is refused.** See
 *   [BandOverflowException]; every page's bands are planned before the first
 *   page is emitted, which is what makes that "an error at document-spec
 *   validation, not at page 37".
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
     * @param pages how many pages the content was fragmented onto — the `of 12`
     *   of every band on every page.
     * @throws BandOverflowException if any page's band is taller than its strip.
     *   The whole document is planned before anything is returned, so a footer
     *   that only overflows once the page count reaches three digits is found
     *   before the file is written rather than while it is.
     */
    fun plan(spec: DocumentSpec, pages: Int): List<List<PlacedItem>> {
        if (spec.header == null && spec.footer == null) return List(pages) { emptyList() }
        val probe = spec.printableArea.height
        return (1..pages).map { page ->
            val numbering = PageNumbering(page, pages)
            planBand(spec.header, spec.headerArea, BandPosition.Header, numbering, probe) +
                planBand(spec.footer, spec.footerArea, BandPosition.Footer, numbering, probe)
        }
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
                // here the same fact has a better name and a better answer.
                throw overflow(position, numbering, strip, blocks, probe, tooTall)
            }
        return when (fit) {
            // A header hangs from the top of its strip; a footer stands on the
            // bottom of its own. Planning is top-down either way — the anchor
            // decides where the unused part of the strip ends up: below a
            // header's text, above a footer's. A footer floating at the top of
            // its strip reads as a wrong margin, which is how this was found.
            is FitResult.Fits -> {
                val drop = when (position) {
                    BandPosition.Header -> Pt.ZERO
                    BandPosition.Footer -> strip.height - fit.consumed
                }
                fit.plan.map { item -> item.translated(strip.left, strip.top - drop) }
            }
            // Both of the other answers say the same thing — not all of it went
            // in — and neither can be honoured: a band has no next page to
            // continue onto, since the page it is on is the one it names.
            is FitResult.Splits, FitResult.Defers -> throw overflow(position, numbering, strip, blocks, probe, null)
        }
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
}
