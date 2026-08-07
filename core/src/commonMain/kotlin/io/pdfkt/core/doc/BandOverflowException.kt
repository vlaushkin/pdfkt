/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.doc

import io.pdfkt.core.model.Pt

// No clause applies: L4 is this library's own design.

/**
 * A header or footer whose content is taller than the strip reserved for it.
 *
 * The rule, and when it fires: header and footer bands are planned per page
 * with the page number substituted at resolve; a band that does not fit its
 * reserved space is an error at document-spec validation, not at page 37.
 *
 * The reason it cannot be anything softer is the order the stages run in. The
 * reserve is what fixes the content area; the content area is what fixes how
 * many pages there are; and how many pages there are is what a band saying
 * "Page 3 of 12" is planned from. A band allowed to grow into the space below it
 * would therefore change the pagination it is numbering, and one allowed to
 * spill over that space would print itself across the first line of the text.
 * Neither is a document anyone asked for, so the band is refused instead —
 * before a byte of the file is written, since every page's band is planned
 * before the first page is emitted.
 *
 * A subtype of `IllegalArgumentException` for the same reason
 * [io.pdfkt.core.fragment.UnplaceableBlockException] is one: what was handed in
 * does not fit itself, and the layout may not quietly alter either half.
 *
 * @property position which band it was.
 * @property numbering the page it was being planned for. A band whose content
 *   depends on the page number can fit on page 9 and not on page 10, which is
 *   why this is a page and not just a document.
 * @property reserved the height of the strip, as [BandSpec.height] gave it.
 * @property required how tall the content came out when it was measured against
 *   the whole printable height of the page, or null when it does not fit even
 *   that — a band whose content is longer than a page has no single number to
 *   report.
 */
public class BandOverflowException internal constructor(
    public val position: BandPosition,
    public val numbering: PageNumbering,
    public val reserved: Pt,
    public val required: Pt?,
    cause: Throwable? = null,
) : IllegalArgumentException(
    "the ${position.label} of page ${numbering.page} of ${numbering.pages} does not fit the " +
        "$reserved reserved for it: " +
        (required?.let { "its content is $it tall" } ?: "its content is taller than the page it is on") +
        ". A band is planned into a strip taken out of the content area of every page, and the size " +
        "of that strip is what fixed how many pages the document has — so a band cannot be given " +
        "more room without renumbering the document it is numbering, and is refused rather than " +
        "drawn over the text below it. Give the " +
        "band a greater height, less content, or a smaller font",
    cause,
)
