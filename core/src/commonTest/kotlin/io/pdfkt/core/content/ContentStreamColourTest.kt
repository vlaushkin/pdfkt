/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.content

import io.pdfkt.core.obj.PdfName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

// ISO 32000-2:2020 §8.6.8 "Colour operators" (Table 73),
// §8.5.3 "Path-painting operators" (Table 59, the B operator),
// §8.8 "External objects" (Table 86, the Do operator)

/**
 * The three operators the plan model needs and Table 56 does not carry: colour,
 * fill-and-stroke, and painting an XObject.
 *
 * Asserted the way the rest of this builder is — on the bytes, against the
 * operator summaries and against the position each operator is admitted in by
 * §8.2, Figure 9.
 */
class ContentStreamColourTest {
    /**
     * Table 73, `rg`: operands `rgb`, "Set the colour to use for nonstroking
     * operations" — three numbers, then the keyword.
     */
    @Test
    fun `rg writes the three components and the keyword`() {
        assertEquals("1 0.5 0 rg\n", content { nonStrokingColor(1.0, 0.5, 0.0) })
    }

    /** Table 73, `RG`: the same operands, the stroking colour. */
    @Test
    fun `RG is the stroking form of the same operator`() {
        assertEquals("0 0 1 RG\n", content { strokingColor(0.0, 0.0, 1.0) })
    }

    /**
     * "Each operand shall be a number between 0.0 (minimum intensity) and 1.0
     * (maximum intensity)" (Table 73). A component outside that has no meaning
     * in the clause, so it is refused rather than clamped.
     */
    @Test
    fun `a component outside zero to one is refused`() {
        assertFailsWith<IllegalArgumentException> { content { nonStrokingColor(1.5, 0.0, 0.0) } }
        assertFailsWith<IllegalArgumentException> { content { nonStrokingColor(0.0, -0.1, 0.0) } }
        assertFailsWith<IllegalArgumentException> { content { strokingColor(0.0, 0.0, 2.0) } }
    }

    /**
     * "Colour operators may appear at the page description level or inside text
     * objects (see Figure 9 — Graphics objects)" (§8.6.8) — so a run of text may
     * set its own colour between `BT` and `ET`.
     */
    @Test
    fun `a colour operator is admitted inside a text object`() {
        val stream =
            content {
                beginText()
                nonStrokingColor(0.2, 0.4, 0.6)
                textFont(PdfName("F1"), 12.0)
                showText("A".encodeToByteArray())
                endText()
            }
        assertEquals("BT\n0.2 0.4 0.6 rg\n/F1 12 Tf\n(A) Tj\nET\n", stream)
    }

    /**
     * A path object "admits path construction and clipping operators only"
     * (§8.2, the example after Figure 9), and a colour operator is neither.
     */
    @Test
    fun `a colour operator is refused inside a path object`() {
        assertFailsWith<IllegalStateException> {
            content {
                rectangle(0.0, 0.0, 10.0, 10.0)
                nonStrokingColor(0.0, 0.0, 0.0)
                fill()
            }
        }
    }

    /**
     * Table 59, `B`: "Fill and then stroke the path … This operator shall
     * produce the same result as constructing two identical path objects,
     * painting the first with f and the second with S."
     *
     * The two colours it consults are set before the path, which is what the
     * NOTE of that row is about — "The filling and stroking portions of the
     * operation consult different values of several graphics state parameters,
     * such as the current colour."
     */
    @Test
    fun `B paints one path in two colours`() {
        val stream =
            content {
                nonStrokingColor(1.0, 1.0, 0.0)
                strokingColor(0.0, 0.0, 0.0)
                lineWidth(2.0)
                rectangle(10.0, 20.0, 100.0, 50.0)
                fillAndStroke()
            }
        assertEquals("1 1 0 rg\n0 0 0 RG\n2 w\n10 20 100 50 re\nB\n", stream)
    }

    /**
     * "Attempting to execute a painting operator when the current path is
     * undefined … shall generate an error" (§8.5.3.1) — `B` is a painting
     * operator like the other two.
     */
    @Test
    fun `B with no current path is refused`() {
        assertFailsWith<IllegalStateException> { content { fillAndStroke() } }
    }

    /**
     * Table 86, `Do`: operand `name`, "Paint the specified XObject". The operand
     * is a name object, written with its SOLIDUS (§7.3.5).
     */
    @Test
    fun `Do names the XObject to paint`() {
        assertEquals("/Im1 Do\n", content { paintXObject(PdfName("Im1")) })
    }

    /**
     * The placement of §8.9.4: "An image can be placed on the output page in any
     * position, orientation, and size by using the cm operator … Typically, this
     * is done within a pair of q and Q operators to isolate the effect of the
     * transformation."
     *
     * The unit square of that clause is what the matrix maps, so a `150 0 0 80
     * 100 200 cm` paints the image 150 wide and 80 tall with its lower-left
     * corner at (100, 200) — the example's own numbers, less its rotation.
     */
    @Test
    fun `an image is placed by a matrix inside a save and restore`() {
        val stream =
            content {
                save()
                concat(150.0, 0.0, 0.0, 80.0, 100.0, 200.0)
                paintXObject(PdfName("Image1"))
                restore()
            }
        assertEquals("q\n150 0 0 80 100 200 cm\n/Image1 Do\nQ\n", stream)
    }

    /**
     * `Do` is an XObject graphics object of its own in Figure 9, reached from
     * the content stream level: a text object admits only the text operators and
     * "those related to the general graphics state, colour, and marked-content"
     * (§9.4.1), and a path object admits path construction only.
     */
    @Test
    fun `Do is refused inside a text object and inside a path object`() {
        assertFailsWith<IllegalStateException> {
            content {
                beginText()
                paintXObject(PdfName("Im1"))
                endText()
            }
        }
        assertFailsWith<IllegalStateException> {
            content {
                rectangle(0.0, 0.0, 1.0, 1.0)
                paintXObject(PdfName("Im1"))
                fill()
            }
        }
    }
}
