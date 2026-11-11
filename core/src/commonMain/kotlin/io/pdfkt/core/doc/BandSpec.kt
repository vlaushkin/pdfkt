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
 * ordering possible, since the strip's size is settled before the pages are.
 *
 * A band with no [height] is as tall as what it draws. Its strip is measured
 * before the content is paginated, and measured again against the pages that
 * came out, since a footer saying "Page 9 of 10" may wrap where "Page 9 of 9"
 * did not; when the second measure is taller, the content is paginated again
 * against the taller strip, until the two agree. The strip is still one height
 * on every page — the tallest the band is anywhere it is drawn — so the
 * content area does not change size between pages.
 *
 * @property height the strip reserved for it, taken off the content area of
 *   every page, or null for a strip as tall as the band's content. A height
 *   that is given is a floor under [BandOverflow.Grow] and a ceiling under
 *   the other two.
 * @property pages which pages the band is drawn on. It does not change the
 *   reserve; see [DocumentSpec.headerArea].
 * @property overflow what happens when the content is taller than its strip.
 * @property gap the space kept clear between the strip and the content — below
 *   a header, above a footer. It is reserved with the strip on every page, and
 *   is what keeps a band cut to its content from reading as the first or the
 *   last line of the text.
 * @property content the blocks of the band for a given page.
 */
public class BandSpec(
    public val height: Pt? = null,
    public val pages: BandPages = BandPages.All,
    public val overflow: BandOverflow = BandOverflow.Grow,
    public val gap: Pt = Pt.ZERO,
    public val content: (PageNumbering) -> List<Block>,
) {
    init {
        require(gap >= Pt.ZERO) { "a band's gap is space kept clear, and there is no less than none of it: $gap" }
        require(height == null || height > Pt.ZERO) {
            "a band of no height is a band nothing can be drawn in; a document without a header or a " +
                "footer has none rather than an empty one, and one as tall as its content has no height"
        }
    }

    /** This band, reserving [reserve] — the strip its sizing settled on. */
    internal fun reserving(reserve: Pt): BandSpec = BandSpec(reserve, pages, overflow, gap, content)
}

/**
 * What a band does when its content is taller than its strip.
 *
 * A band that would not fit used to stop the document. That is the right
 * answer for a caller who sized the page by hand and wants to be told, and the
 * wrong one for every other: a header the reader of an app types in can be any
 * length, and the document it heads is not wrong for it. So refusing is one
 * choice of three, and not the one made when nobody chooses.
 */
public enum class BandOverflow {
    /**
     * The strip grows to the content, taking the room from the content area
     * of every page — the content is paginated against the taller strip.
     * The growth stops at a quarter of the printable height of the smallest
     * page, so that a band can never crowd the text off the paper; what is
     * still taller than that is clipped as under [Clip].
     */
    Grow,

    /**
     * The strip keeps its height, and the band draws the lines that fit it
     * and leaves out the rest. A band whose first line alone is taller than
     * the strip draws nothing on that page.
     */
    Clip,

    /**
     * The document is refused with a [BandOverflowException] before a byte of
     * it is written — for a caller who sized the strip and wants to know when
     * the content does not respect it. A band with no height grows to its
     * content first, and is refused only past the limit [Grow] stops at.
     */
    Fail,
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
