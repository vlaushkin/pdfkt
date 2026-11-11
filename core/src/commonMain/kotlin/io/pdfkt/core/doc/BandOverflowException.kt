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
 * A header or footer whose content is taller than the strip reserved for it,
 * in a band that asked to be refused for it ([BandOverflow.Fail]).
 *
 * It is the strict one of the three answers to a band that does not fit, and
 * the only one that is not the default: a band grows to its content unless it
 * is told otherwise ([BandOverflow.Grow]), or draws what fits ([BandOverflow.Clip]).
 * A caller who sized the strip by hand — to line the text up with a printed
 * form, say — chooses this to be told instead.
 *
 * When it fires: every page's band is planned before the first page is emitted,
 * so the document is refused before a byte of the file is written, not at page
 * 37. The band is not drawn over the space below it either way: the strip fixed
 * the content area, and the content area fixed how many pages a band saying
 * "Page 3 of 12" is numbering.
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
        ". The band was set to be refused when it does not fit (BandOverflow.Fail): give it a " +
        "greater height or none — a band with no height is as tall as its content — less content, " +
        "a smaller font, or let it grow (BandOverflow.Grow) or clip (BandOverflow.Clip)",
    cause,
)
