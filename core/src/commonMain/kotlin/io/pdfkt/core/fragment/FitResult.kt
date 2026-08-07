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
import io.pdfkt.core.plan.PlacedItem
import io.pdfkt.core.resolve.StyledBlock

// No clause applies: L4 is this library's own design.

/**
 * What a planner answers when it is asked to put a block into the space that is
 * left on a page — the planning protocol, in sketch form:
 *
 * > ```
 * > sealed interface FitResult {
 * >     data class Fits(val plan: List<PlacedItem>, val consumed: Float) : FitResult
 * >     data class Splits(val head: List<PlacedItem>, val consumed: Float,
 * >                       val tail: Block) : FitResult          // tail continues next page
 * >     object Defers : FitResult                               // whole block moves on
 * > }
 * > ```
 *
 * Three answers and no fourth: everything fitted, part of it fitted and the rest
 * continues, or none of it fitted and the whole block moves to the next page.
 * The fragmenter dispatches over them with an exhaustive `when` — the same shape
 * as the L1 serializer, for the same reason — so an answer added later cannot be
 * forgotten by a caller.
 *
 * ## The coordinates a plan comes back in
 *
 * A planner is told a width and a height and nothing about where on the page
 * they are: `planBlock(block, width, available)` carries no origin. So the items
 * come back in the block's own coordinates — the origin is the **top left of the
 * space the planner was given**, x grows right and y grows *up*, as everywhere
 * else in PDF (§8.3.2.3). The block therefore lies at negative y, its first
 * baseline a little below zero, and [consumed] is how far down the last of it
 * reaches, as a positive length.
 *
 * The fragmenter, which is the one that knows where the space was, translates
 * every item once by the top-left corner of it. That is what lets the same
 * planner plan a paragraph on a page, inside a quotation, and inside a table
 * cell without any of them telling it where they are.
 */
public sealed interface FitResult {
    /**
     * All of the block was placed, and it reached [consumed] down from the top
     * of the space it was given.
     *
     * A `Fits` that placed nothing and consumed nothing is a legitimate answer —
     * a list with no items, a paragraph whose text the emoji policy emptied —
     * and it is not what the progress guard is about: the block is done with,
     * so the fragmenter moves on to the next one and no page repeats.
     */
    public data class Fits(public val plan: List<PlacedItem>, public val consumed: Pt) : FitResult

    /**
     * Part of the block was placed and [tail] is what is left of it.
     *
     * The tail is planned again on the next page, from the top, exactly as if the
     * caller had written it there — which is why it is styled blocks and not some
     * private continuation of the planner that produced it.
     *
     * @property head the items of the part that was placed, in the block's own
     *   coordinates.
     * @property consumed how far down [head] reaches. The progress guard reads
     *   this: a split that placed nothing at the top of an empty page would be a
     *   page that never fills and a document that never ends.
     * @property tail what continues on the next page. A *list* of blocks, where
     *   the sketch above had one: what is left of a list whose
     *   third item split is the rest of that item **and** the items after it, and
     *   what is left of a page is every block the page did not reach. One type
     *   then serves both, and the fragmenter plans a whole page through the same
     *   protocol as a single block.
     */
    public data class Splits(
        public val head: List<PlacedItem>,
        public val consumed: Pt,
        public val tail: List<StyledBlock>,
    ) : FitResult {
        init {
            require(tail.isNotEmpty()) {
                "a split with nothing left over is a block that fitted: answer Fits, or the " +
                    "fragmenter starts a page for a tail that has nothing in it " +
                    ""
            }
        }
    }

    /**
     * None of the block was placed; all of it moves to the next page.
     *
     * On a page that is already partly used this is ordinary — a paragraph whose
     * first line does not fit the four points that are left says so. On an
     * **empty** page it is the failure the progress guard exists for: there is no
     * emptier page to move to, and answering `Defers` again there would be a
     * document of blank pages. The fragmenter turns it into an
     * [UnplaceableBlockException] naming the block and the space it refused.
     */
    public data object Defers : FitResult
}
