/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.plan

import io.pdfkt.core.image.ImageRef
import io.pdfkt.core.model.Pt
import io.pdfkt.core.model.Rgb

// No clause applies to the model: L4 is this library's own design.
// The clauses named per variant are the ones
// the emitter writes it through.

/**
 * One thing on a page, already positioned — the members of a [PagePlan].
 *
 * A sealed interface for the same reason `PdfObject` is one: the
 * emitter is a single `when` over these four variants, and the compiler is what
 * guarantees a fifth cannot appear without every emitter arm being written. A
 * variant that reached the emitter unhandled would be an item silently missing
 * from the page, which is the failure this library's "no silent loss" policy
 * exists to make impossible.
 *
 * Coordinates are user space (§8.3.2.3): the origin is the lower-left corner of
 * the page and y grows upwards. No item carries a transformation — the layout
 * resolved every fit, rotation and offset into final geometry before the plan
 * was built.
 *
 * The boxes are `io.pdfkt.core.model.Rect`, written out in full below because
 * [Rect] inside this interface means the item type, not the geometry type.
 */
public sealed interface PlacedItem {
    /**
     * A line of text, drawn at a baseline.
     *
     * ([x], [y]) is the origin of the *baseline*, not a corner of a box: it is
     * the point the text matrix is set to (§9.4.2), so what the plan carries is
     * what the operator takes. The line's runs sit along it at their own
     * [PlacedRun.xOffset]s.
     *
     * One item is one line. Line breaking happened before the plan existed, so
     * a `Text` never wraps and the emitter never measures anything.
     */
    public data class Text(public val x: Pt, public val y: Pt, public val runs: List<PlacedRun>) : PlacedItem

    /**
     * A rectangle: a table rule, a cell background, a horizontal line, the band
     * behind a heading.
     *
     * Emitted as the `re` operator of §8.5.2.1 painted by `f`, `S` or `B`
     * (§8.5.3) depending on which of [fill] and [stroke] is present.
     *
     * @throws IllegalArgumentException if both are absent. `re` with no painting
     *   operator is not a rectangle a reader would show, and an item in a plan
     *   that paints nothing is a layout that lost something without saying so.
     *   A caller who wants nothing drawn leaves the item out.
     */
    public data class Rect(
        public val box: io.pdfkt.core.model.Rect,
        public val fill: Rgb? = null,
        public val stroke: Stroke? = null,
    ) : PlacedItem {
        init {
            require(fill != null || stroke != null) {
                "a placed rectangle paints nothing: it has neither a fill nor a stroke, and a path " +
                    "with no painting operator marks no pixels (ISO 32000-2:2020 §8.5.3.1)"
            }
        }
    }

    /**
     * An image, filling a box.
     *
     * The box is where the image lands and the image is stretched to it: every
     * aspect-fit decision — centring, the EXIF orientation,
     * the "scale it down to fit the column" of an oversized picture — was made
     * by the layout. Decoding and recompression stay in
     * L3/platform adapters; the plan holds only a reference and geometry.
     *
     * @property rotation degrees, counter-clockwise, about the **centre** of
     *   [box]. The image is fitted to the box first and then turned, so a
     *   quarter turn of a non-square box reaches outside it — which is correct
     *   for the caller who asked for exactly that, and is why the planner that
     *   rotates a photo by 90° gives it the box it wants *after* the turn. Zero
     *   is the common case and costs nothing: the emitter writes the same single
     *   `cm` either way.
     */
    public data class Image(
        public val box: io.pdfkt.core.model.Rect,
        public val image: ImageRef,
        public val rotation: Float = 0.0f,
    ) : PlacedItem {
        init {
            require(!rotation.isNaN() && !rotation.isInfinite()) {
                "the rotation of an image is an angle in degrees; $rotation is not one"
            }
        }
    }

    /**
     * A clickable region pointing at a URI — the one item that is not painted at
     * all.
     *
     * It becomes a link annotation (§12.5.6.5) in the page's `/Annots` rather
     * than an operator in the content stream, because that is what a PDF link
     * is: annotations "shall be associated with … a location on a page", not
     * with the marks drawn there. Whatever the link *looks* like — the colour of
     * the text, the underline — is an ordinary [Text] item the layout also
     * emitted; this variant carries only the region and the target.
     *
     * @property target the URI to resolve, "encoded in UTF-8" (Table 210).
     */
    public data class Link(public val box: io.pdfkt.core.model.Rect, public val target: String) : PlacedItem {
        init {
            require(target.isNotEmpty()) {
                "a link with an empty target: a URI action shall carry the uniform resource " +
                    "identifier to resolve (ISO 32000-2:2020 §12.6.4.8, Table 210)"
            }
        }
    }
}
