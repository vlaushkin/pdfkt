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
import io.pdfkt.core.model.Pt

// No clause applies: L4 is this library's own design.

/**
 * A band: a block tree planned into a strip of reserved space, and which pages
 * it is drawn on.
 *
 * A header is not a mechanism of its own — it is blocks, planned by the same fragmenter into a smaller area,
 * and this type is only the two things that are not true of the content: the
 * space it is given, and that its content depends on which page it is being
 * drawn on.
 *
 * That dependency is why [content] is a function. Page-number substitution
 * happens at resolve
 * time for the page being planned — which is possible precisely because planning
 * is per page — and a function from the numbering to blocks is that, without a
 * page-number variant in the block tree that every caller, every planner and
 * every test over blocks would then have to handle. A band that says nothing
 * about the page number ignores its argument; one that says "Page 3 of 12" is
 * written the way it reads:
 *
 * ```
 * BandSpec(height = 24.pt) { numbering ->
 *     listOf(ParagraphBlock(listOf(TextRun("Page ${numbering.page} of ${numbering.pages}"))))
 * }
 * ```
 *
 * The count is known before any band is planned because the bands are planned
 * after the content has been paginated — the reserve is what makes that
 * ordering possible, since the strip's size does not depend on what goes in it.
 *
 * @property height the strip reserved for it, taken off the content area of
 *   every page.
 * @property pages which pages the band is drawn on. It does not change the
 *   reserve; see [DocumentSpec.headerArea].
 * @property content the blocks of the band for a given page.
 */
public class BandSpec(
    public val height: Pt,
    public val pages: BandPages = BandPages.All,
    public val content: (PageNumbering) -> List<Block>,
) {
    init {
        require(height > Pt.ZERO) {
            "a band of no height is a band nothing can be drawn in; a document without a header or a " +
                "footer has none rather than an empty one"
        }
    }
}

/**
 * Which pages a band is drawn on.
 *
 * The three cases are what a cover page needs: a title page with no header, a
 * running header everywhere else, or the same band throughout. A band that
 * differs between the first page and the rest is one [BandSpec.content] that
 * reads [PageNumbering.page], which is why there is no fourth case here.
 */
public enum class BandPages {
    /** Every page. */
    All,

    /** The first page only — a cover. */
    FirstOnly,

    /** Every page but the first. */
    ExceptFirst,

    ;

    /** Whether a band with this rule is drawn on page [page], counted from 1. */
    public fun appliesTo(page: Int): Boolean = when (this) {
        All -> true
        FirstOnly -> page == FIRST_PAGE
        ExceptFirst -> page > FIRST_PAGE
    }

    private companion object {
        const val FIRST_PAGE: Int = 1
    }
}

/**
 * Which of the two bands a band is — the top of the page or the foot of it.
 *
 * A [BandSpec] does not carry it: the same band description put at the top and
 * at the bottom of a page is the same description, and which strip it is planned
 * into is the caller's word. It exists so that the planner is one function for
 * both bands and so that [BandOverflowException] can say which of them did not
 * fit.
 */
public enum class BandPosition {
    /** The strip at the top of the printable area, above the content. */
    Header,

    /** The strip at the foot of the printable area, below the content. */
    Footer,

    ;

    /** How the band is named in a message a caller reads. */
    internal val label: String get() = name.lowercase()
}

/**
 * Which page of how many — what a band is planned against.
 *
 * @property page the page being planned, counted from 1 as a reader counts.
 * @property pages how many pages the document has.
 */
public data class PageNumbering(public val page: Int, public val pages: Int) {
    init {
        require(page in 1..pages) {
            "page $page of $pages: a document is numbered from 1 and has as many pages as it has"
        }
    }
}
