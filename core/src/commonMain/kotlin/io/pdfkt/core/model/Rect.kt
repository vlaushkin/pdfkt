/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.model

// ISO 32000-2:2020 §7.9.5 "Rectangles", §8.3.2.3 "User space",
// §8.5.2.1 "Path construction operators — General" (the re operator)

/**
 * A rectangle on a page, in PDF coordinates: the origin is at the lower left of
 * the page and y grows upwards (§8.3.2.3).
 *
 * **The** rectangle type of this library: one immutable rectangle type in PDF
 * coordinates, origin bottom-left, y growing upwards. Border styling is
 * **not** part of geometry; a border is a separate description attached where
 * it is drawn.
 *
 * So there is no `isUndefined`, no mutable variant and no styling: a border lives
 * on the item that draws one (`PlacedItem.Rect`), never here.
 *
 * It is stored as a corner and an extent — `x y width height` — which is the
 * operand list of the `re` operator that paints one (§8.5.2.1) and the shape a
 * layout thinks in ("this box, 40 units shorter"). The `[llx lly urx ury]` form
 * §7.9.5 writes a rectangle in is the *other* two corners, reachable through
 * [right] and [top], and the emitter converts once when a `/MediaBox` or an
 * annotation `/Rect` needs it.
 *
 * @property x the left edge.
 * @property y the bottom edge.
 * @throws IllegalArgumentException if either extent is negative. §7.9.5 admits
 *   the degenerate cases — "NOTE Rectangles can have a width of zero or height
 *   of zero" — and they are allowed here; a negative extent would make [x] no
 *   longer the left edge, and every reader of this type assumes it is.
 */
public data class Rect(public val x: Pt, public val y: Pt, public val width: Pt, public val height: Pt) {
    init {
        require(width >= Pt.ZERO && height >= Pt.ZERO) {
            "a rectangle is stored as its lower-left corner and a non-negative extent: " +
                "got width=$width height=$height at ($x, $y)"
        }
    }

    /** The left edge, which is [x] — named for symmetry with [right]. */
    public val left: Pt get() = x

    /** The bottom edge, which is [y]. */
    public val bottom: Pt get() = y

    /** The right edge: `x + width`, the `urx` of §7.9.5. */
    public val right: Pt get() = x + width

    /** The top edge: `y + height`, the `ury` of §7.9.5. */
    public val top: Pt get() = y + height

    /** The extent, without the position. */
    public val size: Size get() = Size(width, height)

    /**
     * This rectangle with [insets] taken off each side — a page minus its
     * margins, a cell minus its padding.
     *
     * @throws IllegalArgumentException if the insets take more than there is,
     *   which would be a rectangle turned inside out rather than an empty one.
     *   A caller who can face insets that large has to decide what that means;
     *   guessing an empty rectangle here would hide it.
     */
    public fun deflate(insets: Insets): Rect {
        require(insets.horizontal <= width && insets.vertical <= height) {
            "insets of $insets do not fit a rectangle of ${width}x$height"
        }
        return Rect(
            x + insets.left,
            y + insets.bottom,
            width - insets.horizontal,
            height - insets.vertical,
        )
    }

    /** This rectangle moved by [dx] to the right and [dy] up. */
    public fun translated(dx: Pt, dy: Pt): Rect = Rect(x + dx, y + dy, width, height)

    /** Whether ([px], [py]) is inside this rectangle or on its boundary. */
    public fun contains(px: Pt, py: Pt): Boolean = px >= left && px <= right && py >= bottom && py <= top

    public companion object {
        /**
         * A rectangle given as the two opposite corners §7.9.5 writes — for
         * callers who have `llx lly urx ury` rather than a corner and an extent.
         */
        public fun between(left: Pt, bottom: Pt, right: Pt, top: Pt): Rect =
            Rect(left, bottom, right - left, top - bottom)
    }
}
