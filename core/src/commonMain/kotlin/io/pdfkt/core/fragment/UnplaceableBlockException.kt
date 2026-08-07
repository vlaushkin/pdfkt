/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.fragment

import io.pdfkt.core.model.Pt
import io.pdfkt.core.resolve.StyledBlock

// No clause applies: L4 is this library's own design.

/**
 * A block that could not be placed on a page it had entirely to itself.
 *
 * This is the progress guard speaking. If a page is empty and the block still
 * answers `Defers` or consumes zero height, that is an error, not an infinite
 * loop of blank pages. A block taller than an entire empty page is
 * force-split; if it cannot split (an oversized image), it scales or errors —
 * explicitly, per the no-silent-loss policy.
 *
 * There are exactly three ways to get here, and all three are the same failure
 * seen from different sides:
 *
 * - a block answered [FitResult.Defers] on an empty page — a line taller than
 *   the whole content area, a rule thicker than it;
 * - a block answered [FitResult.Splits] on an empty page having consumed
 *   nothing, so the next page would begin exactly where this one did;
 * - a block that cannot be cut and cannot be scaled is taller than the page —
 *   an image with a height the caller fixed.
 *
 * The alternative to raising is a file of blank pages, or a loop that never
 * returns, or a silently dropped block. The first two waste the caller's time
 * and the third is the failure this library refuses on principle,
 * so the guard reports which block
 * it was, how much room it had, and what would have to change.
 *
 * A subtype of `IllegalArgumentException` because that is what it is: the
 * document and the page size handed in do not fit each other, and neither is
 * something the layout may quietly alter.
 *
 * @property block the block that could not be placed, as the resolve stage
 *   produced it.
 * @property width the column it was offered.
 * @property available the height it was offered — the whole content area of an
 *   empty page.
 */
public class UnplaceableBlockException internal constructor(
    public val block: StyledBlock,
    public val width: Pt,
    public val available: Pt,
    explanation: String,
) : IllegalArgumentException(
    "a ${block::class.simpleName} cannot be placed on a page of its own: $explanation. " +
        "The block was offered the whole content area of an empty page — $width wide and " +
        "$available high — and nothing smaller will be offered to it, so the page would repeat " +
        "empty for ever",
) {
    internal companion object {
        /** A block that answered `Defers` where there is nothing left to defer to. */
        fun deferredOnEmptyPage(block: StyledBlock, width: Pt, available: Pt): UnplaceableBlockException =
            UnplaceableBlockException(
                block = block,
                width = width,
                available = available,
                explanation =
                "it took none of the height it was offered and asked for the next page instead. " +
                    "Nothing was dropped and nothing was drawn outside the page: give the page a " +
                    "taller content area, smaller margins, or a smaller font — or, for a block that " +
                    "the caller sized, a size that fits",
            )

        /** A block that split off a head of no height at all: the next page would start where this one did. */
        fun splitWithoutProgress(block: StyledBlock, width: Pt, available: Pt): UnplaceableBlockException =
            UnplaceableBlockException(
                block = block,
                width = width,
                available = available,
                explanation =
                "it split with a first part of no height, so the page after it would begin exactly " +
                    "where this one did. A split has to leave something behind to be a split",
            )

        /** An image the caller sized, taller than the page, which the layout may not resize. */
        fun oversizedImage(block: StyledBlock, width: Pt, available: Pt, height: Pt): UnplaceableBlockException =
            UnplaceableBlockException(
                block = block,
                width = width,
                available = available,
                explanation =
                "it is $height high, an image cannot be cut in half across a page break, and the " +
                    "size it is drawn at was fixed by the caller rather than left to the fit " +
                    "policy — so scaling it down would be this library overruling that. Either " +
                    "leave the height to ImageFit.Contain, which scales it to the page, or give a " +
                    "height that fits",
            )

        /**
         * A table row that fits neither the space left nor an empty page, and
         * may not be cut: "`Splits` between rows, never inside a row"
         */
        fun rowTallerThanThePage(block: StyledBlock, width: Pt, available: Pt): UnplaceableBlockException =
            UnplaceableBlockException(
                block = block,
                width = width,
                available = available,
                explanation =
                "one of its rows is taller than the whole page, and a table is only ever cut " +
                    "*between* rows — a row split down the middle would put the top half of one " +
                    "cell's text beside the bottom half of another's. Shorten the row's content, " +
                    "widen its columns so the content takes fewer lines, or give the page a taller " +
                    "content area",
            )

        /** Columns the caller fixed the widths of, adding up to more than the table was given. */
        fun columnsWiderThanTheSpace(
            block: StyledBlock,
            width: Pt,
            available: Pt,
            requested: Pt,
        ): UnplaceableBlockException = UnplaceableBlockException(
            block = block,
            width = width,
            available = available,
            explanation =
            "its columns were fixed at $requested in total, which is wider than the $width it " +
                "was given, and a width the caller fixed is not one this library may scale down " +
                "— the same rule an image the caller sized is held to. Either give the columns " +
                "widths that fit, or ask for TableColumns.Fractional, which is the mode that " +
                "means \"in these proportions, whatever the width turns out to be\"",
        )

        /** A column with no room left in it once the cell padding is taken off both sides. */
        fun columnNarrowerThanItsPadding(
            block: StyledBlock,
            width: Pt,
            available: Pt,
            padding: Pt,
        ): UnplaceableBlockException = UnplaceableBlockException(
            block = block,
            width = width,
            available = available,
            explanation =
            "one of its columns is no wider than the $padding of cell padding taken off each " +
                "side of it, leaving no column for the cell's content. A cell is a page in " +
                "miniature and is laid out by the same code, which needs a positive width to " +
                "break a line against",
        )

        /** A column narrower than the indents taken out of it: there is no text column left. */
        fun narrowerThanItsIndents(block: StyledBlock, width: Pt, available: Pt): UnplaceableBlockException =
            UnplaceableBlockException(
                block = block,
                width = width,
                available = available,
                explanation =
                "the indents it carries take away at least the whole width it was given, leaving no " +
                    "column for its content. A line is broken against a width, and a width that is " +
                    "not positive is a column nothing fits in",
            )
    }
}
