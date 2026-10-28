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
import io.pdfkt.core.block.SectionBlock
import io.pdfkt.core.fragment.PageTemplate
import io.pdfkt.core.model.Insets
import io.pdfkt.core.model.Pt
import io.pdfkt.core.model.Rect
import io.pdfkt.core.model.Size

// No clause applies: L4 and L5 are this library's own design.
// §8.3.2.3 is where the
// coordinates the areas are given in come from.

/**
 * A whole document, as the one thing the engine is asked to render.
 *
 * There is a single engine entry point, and `document { }` already builds
 * `List<Block>`. Any other vocabulary **lowers** to the same shape: a photo
 * becomes an `ImageBlock` with a fit policy, followed by a forced
 * `PageBreakBlock`. A lowering is a thin layer with no rendering knowledge of
 * its own.
 *
 * So this type is the seam, and there is deliberately nothing on the other side
 * of it: the DSL builds one of these and calls [render], and everything beyond
 * that call — fitting images, grouping blocks, composing marker text —
 * is inside the engine, where it is tested against plans.
 *
 * The theme is not part of it. It is the second parameter of [render] because it
 * is the one input a caller reasonably swaps while the document stays the same:
 * the same blocks set in a different theme are the same document.
 *
 * Two instances are equal when they carry the same blocks, the same metadata,
 * the same clock and the same bands — the same clock, not an equivalent one,
 * since a [Clock] and a [BandSpec] are behaviour and behaviour has no equality
 * beyond identity.
 *
 * @property pageSize the page — the `/MediaBox` of every page of the document
 *   (§7.7.3.3).
 * @property margins what is kept clear on all four sides of it. The bands are
 *   reserved *inside* the margins, so a header does not eat into the top margin
 *   but sits below it.
 * @property header the band at the top of the page, or null for none.
 * @property footer the band at the bottom of the page, or null for none.
 * @property content the document, as the block tree the DSL builds.
 * @property metadata what the document says about itself (§14.3.3).
 * @property clock where `/CreationDate` and `/ModDate` come from — injected,
 *   never defaulted.
 */
public data class DocumentSpec(
    public val pageSize: Size,
    public val margins: Insets,
    public val header: BandSpec? = null,
    public val footer: BandSpec? = null,
    public val content: List<Block>,
    public val metadata: Metadata = Metadata(),
    public val clock: Clock,
) {
    init {
        requireRoom(pageSize, margins, "the document's")
        for (section in content.filterIsInstance<SectionBlock>()) {
            requireRoom(section.pageSize, section.margins, "a section's")
        }
    }

    private fun requireRoom(size: Size, insets: Insets, whose: String) {
        require(insets.horizontal < size.width) {
            "margins of ${insets.horizontal} across $whose page ${size.width} wide leave no column " +
                "for the text"
        }
        require(insets.vertical + bandHeight < size.height) {
            "margins of ${insets.vertical} and bands of $bandHeight down $whose page ${size.height} " +
                "tall leave no room for the content: a document is written in order to be read"
        }
    }

    /**
     * The area between the margins, before any band is taken out of it — what
     * the page would give the content if it had neither header nor footer.
     */
    public val printableArea: Rect get() = printableArea(pageSize, margins)

    /**
     * Where the blocks go: the printable area with the bands taken off the top
     * and the bottom.
     *
     * The fragmenter is given this and nothing else: it receives the content
     * area *between* the bands and never knows the bands exist. A reserved
     * strip and a wider margin are the same thing to it.
     */
    public val contentArea: Rect get() = contentArea(pageSize, margins)

    /**
     * The strip the header is planned into, at the top of the printable area, or
     * null when there is no header.
     *
     * The strip is reserved on *every* page even when [BandSpec.pages] draws the
     * band on some of them: a content area that changed size between pages would
     * reflow the document around where the header happens to appear, which is a
     * worse surprise than a page with a blank strip at the top.
     */
    public val headerArea: Rect? get() = headerArea(pageSize, margins)

    /** The strip the footer is planned into, at the foot of the printable area. */
    public val footerArea: Rect? get() = footerArea(pageSize, margins)

    /** The page and its content area, as the fragmenter takes them. */
    public fun pageTemplate(): PageTemplate = pageTemplate(pageSize, margins)

    /**
     * [printableArea] of a page of [size] with [margins] — the document's own
     * page, or one of a `SectionBlock`'s. Every area below is a function of the
     * page it is on for the same reason: a section's pages carry the
     * document's bands in strips cut from their own paper.
     */
    public fun printableArea(size: Size, margins: Insets): Rect = size.toRect().deflate(margins)

    /** [contentArea] of a page of [size] with [margins]. */
    public fun contentArea(size: Size, margins: Insets): Rect = printableArea(size, margins).deflate(
        Insets(Pt.ZERO, footer?.height ?: Pt.ZERO, Pt.ZERO, header?.height ?: Pt.ZERO),
    )

    /** [headerArea] of a page of [size] with [margins]. */
    public fun headerArea(size: Size, margins: Insets): Rect? = header?.let { band ->
        val printable = printableArea(size, margins)
        Rect(printable.x, printable.top - band.height, printable.width, band.height)
    }

    /** [footerArea] of a page of [size] with [margins]. */
    public fun footerArea(size: Size, margins: Insets): Rect? = footer?.let { band ->
        val printable = printableArea(size, margins)
        Rect(printable.x, printable.y, printable.width, band.height)
    }

    /** [pageTemplate] of a page of [size] with [margins]. */
    public fun pageTemplate(size: Size, margins: Insets): PageTemplate = PageTemplate(size, contentArea(size, margins))

    private val bandHeight: Pt get() = (header?.height ?: Pt.ZERO) + (footer?.height ?: Pt.ZERO)
}
