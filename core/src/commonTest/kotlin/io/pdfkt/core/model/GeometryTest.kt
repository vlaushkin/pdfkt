/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.model

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// ISO 32000-2:2020 §7.9.5 "Rectangles", §8.3.2.3 "User space",
// §8.6.8 "Colour operators" (Table 73)

/**
 * The vocabulary `:core` owns, shared by the engine and the `document { }` DSL.
 *
 * No clause defines these types — they are this library's design — but two
 * clauses constrain them, and those are what is asserted: the coordinate
 * convention of §8.3.2.3 ("the positive x axis extends horizontally to the right
 * and the positive y axis vertically upward"), and the component range of Table
 * 73.
 */
class GeometryTest {
    /** A length adds, subtracts and scales; the results are lengths. */
    @Test
    fun `points add subtract and scale`() {
        assertEquals(Pt(30.0f), Pt(12.0f) + Pt(18.0f))
        assertEquals(Pt(6.0f), Pt(18.0f) - Pt(12.0f))
        assertEquals(Pt(18.0f), Pt(12.0f) * 1.5f)
        assertEquals(Pt(6.0f), Pt(12.0f) / 2.0f)
        assertEquals(Pt(-12.0f), -Pt(12.0f))
    }

    /** The ratio of two lengths is a factor and not a length. */
    @Test
    fun `dividing a length by a length gives a factor`() {
        assertEquals(2.5f, Pt(100.0f) / Pt(40.0f))
    }

    /** Lengths order, which is what a fragmenter compares against a page. */
    @Test
    fun `points compare`() {
        assertTrue(Pt(10.0f) < Pt(11.0f))
        assertTrue(Pt.ZERO <= Pt(0.0f))
        assertEquals(Pt(36.0f), 36.pt)
        assertEquals(Pt(841.89f), 841.89.pt)
    }

    /**
     * US Letter is 8.5 × 11 inches and a default user space unit is 1 ⁄ 72 inch
     * (§8.3.2.3), so the page is exactly 612 × 792 units — the numbers §7.9.5's
     * own rectangles are printed with. A4 is 210 mm × 297 mm, which is
     * 595.2756 × 841.8898 of the same units.
     */
    @Test
    fun `the standard page sizes are their paper sizes in user space units`() {
        assertEquals(Pt(612.0f), Size.LETTER.width)
        assertEquals(Pt(792.0f), Size.LETTER.height)
        assertTrue(
            abs(Size.A4.width.value - 595.2756f) < TOLERANCE && abs(Size.A4.height.value - 841.8898f) < TOLERANCE,
            "A4 came out as ${Size.A4.width} x ${Size.A4.height}",
        )
    }

    /** A page turned on its side is the same two numbers, exchanged. */
    @Test
    fun `a size rotates by exchanging its dimensions`() {
        assertEquals(Size(Pt(792.0f), Pt(612.0f)), Size.LETTER.rotated())
    }

    /** A negative extent is not a size — mirroring is a transformation, not a page. */
    @Test
    fun `a negative dimension is refused`() {
        assertFailsWith<IllegalArgumentException> { Size(Pt(-1.0f), Pt(10.0f)) }
        assertFailsWith<IllegalArgumentException> { Rect(Pt.ZERO, Pt.ZERO, Pt(10.0f), Pt(-10.0f)) }
        assertFailsWith<IllegalArgumentException> { Insets(Pt(-1.0f), Pt.ZERO, Pt.ZERO, Pt.ZERO) }
    }

    /**
     * §7.9.5 admits the degenerate cases outright — "NOTE Rectangles can have a
     * width of zero or height of zero" — so a rule of no thickness is a
     * rectangle and not an error.
     */
    @Test
    fun `a rectangle may be degenerate`() {
        val rule = Rect(Pt(72.0f), Pt(700.0f), Pt(468.0f), Pt.ZERO)
        assertEquals(Pt(700.0f), rule.top)
    }

    /**
     * The four edges, with y growing upwards (§8.3.2.3): the `[llx lly urx ury]`
     * of §7.9.5 read off a corner and an extent.
     */
    @Test
    fun `the edges of a rectangle are its corner plus its extent`() {
        val box = Rect(Pt(100.0f), Pt(200.0f), Pt(300.0f), Pt(50.0f))
        assertEquals(Pt(100.0f), box.left)
        assertEquals(Pt(200.0f), box.bottom)
        assertEquals(Pt(400.0f), box.right)
        assertEquals(Pt(250.0f), box.top)
        assertEquals(Size(Pt(300.0f), Pt(50.0f)), box.size)
    }

    /** The two corners of §7.9.5 and the corner-plus-extent form are one rectangle. */
    @Test
    fun `a rectangle built from two corners is the same rectangle`() {
        assertEquals(
            Rect(Pt(100.0f), Pt(200.0f), Pt(300.0f), Pt(50.0f)),
            Rect.between(Pt(100.0f), Pt(200.0f), Pt(400.0f), Pt(250.0f)),
        )
    }

    /** A page minus its margins, with the bottom-left origin: both edges move inwards. */
    @Test
    fun `insets deflate a rectangle on all four sides`() {
        val content = Size.LETTER.toRect().deflate(Insets.uniform(72.pt))
        assertEquals(Rect(Pt(72.0f), Pt(72.0f), Pt(468.0f), Pt(648.0f)), content)
        assertEquals(Pt(540.0f), content.right)
        assertEquals(Pt(720.0f), content.top)
    }

    /**
     * Insets larger than the rectangle would turn it inside out. What that means
     * is the caller's decision, so it is refused rather than guessed at.
     */
    @Test
    fun `insets that do not fit are refused`() {
        assertFailsWith<IllegalArgumentException> {
            Rect(Pt.ZERO, Pt.ZERO, Pt(100.0f), Pt(100.0f)).deflate(Insets.uniform(60.pt))
        }
    }

    /** Moving a box keeps its extent — what placing a planned block does. */
    @Test
    fun `a rectangle translates`() {
        assertEquals(
            Rect(Pt(20.0f), Pt(5.0f), Pt(10.0f), Pt(10.0f)),
            Rect(Pt(10.0f), Pt(15.0f), Pt(10.0f), Pt(10.0f)).translated(10.pt, -(10.pt)),
        )
    }

    /** Containment includes the boundary: a point on an edge is inside the box. */
    @Test
    fun `a rectangle contains its own corners`() {
        val box = Rect(Pt(10.0f), Pt(10.0f), Pt(20.0f), Pt(20.0f))
        assertTrue(box.contains(Pt(10.0f), Pt(10.0f)))
        assertTrue(box.contains(Pt(30.0f), Pt(30.0f)))
        assertFalse(box.contains(Pt(30.1f), Pt(30.0f)))
    }

    /**
     * Table 73: "Each operand shall be a number between 0.0 (minimum intensity)
     * and 1.0 (maximum intensity)" — the range of the operator, enforced where
     * the colour is built rather than where it is written.
     */
    @Test
    fun `a colour component outside zero to one is refused`() {
        assertFailsWith<IllegalArgumentException> { Rgb(1.2f, 0.0f, 0.0f) }
        assertFailsWith<IllegalArgumentException> { Rgb(0.0f, -0.5f, 0.0f) }
        assertFailsWith<IllegalArgumentException> { Rgb(0.0f, 0.0f, Float.NaN) }
    }

    /**
     * The initial colour of a page: "In a DeviceGray, DeviceRGB, CalGray, or
     * CalRGB colour space, the initial colour shall have all components equal to
     * 0.0" (§8.6.8).
     */
    @Test
    fun `black is the colour a page starts in`() {
        assertEquals(Rgb(0.0f, 0.0f, 0.0f), Rgb.BLACK)
        assertEquals(Rgb(1.0f, 1.0f, 1.0f), Rgb.WHITE)
        assertEquals(Rgb(0.5f, 0.5f, 0.5f), Rgb.gray(0.5f))
    }

    /** The three bytes everyone writes a colour as, scaled to the operator's range. */
    @Test
    fun `a colour can be given as three bytes`() {
        assertEquals(Rgb.BLACK, Rgb.of(0x000000))
        assertEquals(Rgb.WHITE, Rgb.of(0xFFFFFF))
        assertEquals(Rgb(1.0f, 0.0f, 0.0f), Rgb.of(0xFF0000))
        assertEquals(Rgb(0.0f, 128.0f / 255.0f, 1.0f), Rgb.of(0x0080FF))
    }
}

/** A ten-thousandth of a unit: far finer than any reader resolves a page to. */
private const val TOLERANCE: Float = 0.0001f
