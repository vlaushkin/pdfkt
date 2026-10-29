/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.api

import io.pdfkt.core.block.Block
import io.pdfkt.core.block.TextAlign
import io.pdfkt.core.doc.BandSpec
import io.pdfkt.core.doc.PageNumbering

// No clause applies: the DSL is this library's own design. What a band is and which pages
// carry it belongs to :core (io.pdfkt.core.doc.BandSpec); nothing here reaches
// past the seam.

/**
 * The content of one `header { }` or `footer { }`, on the page it is drawn on.
 *
 * A colophon is somewhere blocks go, and this is that container: the builders
 * come from [BlockSink], inline content written straight into it becomes a
 * leading paragraph ([BlockContainerScope]), and so a running head may be a bold
 * line, a rule under a title, a two-cell table — anything the body of the
 * document may be.
 *
 * ```kotlin
 * header { paragraph { text("Q1 Report") { bold = true } } }
 * footer(pages = BandPages.ExceptFirst) { paragraph(pageOfPages(), align = TextAlign.Right) }
 * ```
 *
 * The scope is built afresh for every page the band is drawn on, because
 * [BandSpec.content] is a function of the page numbering. That is what [numbering]
 * is: the page this run of the block is writing, and how many pages the document
 * turned out to have. A block that ignores it draws the same colophon on every
 * page, at the cost of one rebuild per page, and keeps the two spellings one.
 *
 * Its content is set flush left unless a paragraph says otherwise — for the foot
 * as much as for the head. The text-form [DocumentScope.header] and
 * [DocumentScope.footer] keep the left/right split they were carried over
 * with; a block form has no side of its own to prefer.
 */
@PdfktDsl
public class ColophonScope internal constructor(
    /**
     * Which page of how many this colophon is being written for, counted as a
     * reader counts — page 1 is the first.
     *
     * The count is known before any band is planned: the strip's size does not
     * depend on what goes in it, so the document is paginated first and the
     * colophons are written afterwards. A head may therefore say how many pages
     * the document has without the document being rendered twice.
     */
    public val numbering: PageNumbering,
) : BlockContainerScope(TextAlign.Start) {

    /**
     * "3 / 12" — the page and the count, as the reader of this page sees them.
     *
     * It hands the line back rather than writing it, so that the caller says
     * where it goes and how it is set:
     *
     * ```kotlin
     * footer { paragraph(pageOfPages(), align = TextAlign.Right) }
     * footer { paragraph(align = TextAlign.Right) { bold(this@footer.pageOfPages()) } }
     * ```
     *
     * The second spelling names the receiver because [PdfktDsl] is a
     * `@DslMarker`: inside a nested `paragraph { }` the colophon's own members are
     * no longer reachable implicitly, which is the annotation doing its job — it
     * is what stops a `cell { }` from being written into the paragraph around it
     * by accident. A label is the way through it.
     *
     * There are no words in it. "Page 3 of 12" is English, and a document set in
     * Cyrillic wants "Стр. 3 из 12"; both are one interpolation of [numbering]
     * away, and neither belongs in a library that does not know the language of
     * the document it is setting. What is here is the part that is the same in
     * every language.
     *
     * @param separator what goes between the two numbers.
     */
    public fun pageOfPages(separator: String = PAGE_OF_PAGES_SEPARATOR): String =
        "${numbering.page}$separator${numbering.pages}"
}

/**
 * The blocks a `header { }` or `footer { }` draws on the page [numbering] names:
 * [block] run against a scope of its own, and whatever it collected.
 *
 * It is called from inside the band's content function rather than when the
 * document is built, which is what lets a colophon read the page it lands on —
 * when the document was being written there was no page to read.
 *
 * A block that collected nothing is refused here rather than drawn as a blank
 * strip. A head is drawn or it is not, and which pages carry one is the band's
 * `pages` rule; a block that writes nothing on some page is a block that lost its
 * content on the way. It is also what a caller who writes
 * `footer { "one line" }` — a lambda whose last expression is a string — meets:
 * that string would be
 * discarded by a `ColophonScope.() -> Unit` without a word, and a colophon silently coming out blank is the loss
 * this library forbids.
 */
internal fun colophonContent(numbering: PageNumbering, block: ColophonScope.() -> Unit): List<Block> {
    val scope = ColophonScope(numbering)
    scope.block()
    val blocks = scope.build()
    require(blocks.isNotEmpty()) {
        "a colophon draws at least one block, and this one wrote nothing on page ${numbering.page} of " +
            "${numbering.pages}. A band that belongs on some pages only is `pages = BandPages.FirstOnly` " +
            "or `BandPages.ExceptFirst`; a line of text is `paragraph(\"...\")`, and a lambda that only " +
            "hands a string back writes nothing at all"
    }
    return blocks
}

/** What [ColophonScope.pageOfPages] puts between the page and the count. */
private const val PAGE_OF_PAGES_SEPARATOR: String = " / "
