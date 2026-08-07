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
import kotlin.test.assertTrue

// ISO 32000-2:2020 §8.4.2 "Graphics state stack",
// §8.4.4 "Graphics state operators" (Table 56),
// §8.5.2 "Path construction operators" (Table 58),
// §8.5.3 "Path-painting operators" (Table 59)

/**
 * What the clauses require of the operators this builder writes.
 *
 * The assertions are on the bytes that come out, against the operator summaries
 * of Table 56, Table 58 and Table 59, and against the two ordering rules stated
 * as requirements: the balance of §8.4.2 and the graphics object nesting of
 * §8.2, "Only those operators that are listed in Figure 9 — Graphics objects for
 * each type of graphics object or in the intervals between graphics objects …
 * shall be used in that context."
 */
class ContentStreamBuilderTest {
    /**
     * Table 56: `q` and `Q` take no operands — the column reads "—" for both —
     * so each is a keyword on its own.
     */
    @Test
    fun `q and Q are written as the bare keywords`() {
        val stream =
            content {
                save()
                restore()
            }
        assertEquals("q\nQ\n", stream)
    }

    /**
     * "The q operator shall push a copy of the entire graphics state onto the
     * stack. The Q operator shall restore the entire graphics state to its
     * former value by popping it from the stack" (§8.4.2) — a LIFO stack, so
     * saves nest, and the nesting is exactly what the emitted sequence shows.
     */
    @Test
    fun `saves nest and each is matched in the reverse order`() {
        val stream =
            content {
                save()
                save()
                lineWidth(3.0)
                restore()
                restore()
            }
        assertEquals("q\nq\n3 w\nQ\nQ\n", stream)
    }

    /**
     * Table 56, `cm`, operands `a b c d e f`: "Although the operands specify a
     * matrix, they shall be written as six separate numbers, not as an array."
     *
     * The matrix is the translation of §8.3.3, `[1 0 0 1 tx ty]`, written the
     * way the operator requires rather than the way the subclause prints it.
     */
    @Test
    fun `cm writes six separate numbers and not an array`() {
        val stream = content { concat(1.0, 0.0, 0.0, 1.0, 100.0, 200.0) }
        assertEquals("1 0 0 1 100 200 cm\n", stream)
        assertTrue('[' !in stream && ']' !in stream, "the operands were written as an array: $stream")
    }

    /** The six operands reach the stream in the order of the matrix `[a b c d e f]`. */
    @Test
    fun `the operands of cm are written in the order of the matrix`() {
        assertEquals("1 2 3 4 5 6 cm\n", content { concat(1.0, 2.0, 3.0, 4.0, 5.0, 6.0) })
    }

    /** Table 56, `w`, operand `lineWidth`: "Set the line width in the graphics state." */
    @Test
    fun `w writes the line width`() {
        assertEquals("0.5 w\n", content { lineWidth(0.5) })
    }

    /**
     * "A line width of 0 shall denote the thinnest line that can be rendered at
     * device resolution" (§8.4.3.2) — a defined width, and so a writable one,
     * whatever the same paragraph advises about using it.
     */
    @Test
    fun `a line width of zero is written`() {
        assertEquals("0 w\n", content { lineWidth(0.0) })
    }

    /** "It shall be a non-negative number expressed in user space units" (§8.4.3.2). */
    @Test
    fun `a negative line width is rejected`() {
        assertFailsWith<IllegalArgumentException> { ContentStreamBuilder().lineWidth(-1.0) }
    }

    /**
     * Table 58, `m`, operands `x y`: "Begin a new subpath by moving the current
     * point to coordinates (x, y)", and `l`, operands `x y`: "Append a straight
     * line segment from the current point to the point (x, y)". Table 59, `S`:
     * "Stroke the path."
     */
    @Test
    fun `m and l and S write a stroked line`() {
        val stream =
            content {
                moveTo(10.0, 20.0)
                lineTo(30.0, 40.0)
                stroke()
            }
        assertEquals("10 20 m\n30 40 l\nS\n", stream)
    }

    /**
     * Table 58, `re`, operands `x y width height`: "Append a rectangle to the
     * current path as a complete subpath, with lower-left corner (x, y) and
     * dimensions width and height in user space." Table 59, `f`: "Fill the
     * path, using the non-zero winding number rule."
     */
    @Test
    fun `re and f write a filled rectangle`() {
        val stream =
            content {
                rectangle(100.0, 100.0, 200.0, 300.0)
                fill()
            }
        assertEquals("100 100 200 300 re\nf\n", stream)
    }

    /**
     * The equivalence Table 58 defines `re` by — `x y m`, `(x + width) y l`,
     * `(x + width) (y + height) l`, `x (y + height) l`, `h` — is arithmetic that
     * holds for any values, so a negative dimension denotes the rectangle on the
     * other side of the corner rather than an error: `x + width` with a negative
     * width is a coordinate like any other.
     */
    @Test
    fun `a rectangle with negative dimensions is written as given`() {
        val stream =
            content {
                rectangle(100.0, 100.0, -50.0, -60.0)
                fill()
            }
        assertEquals("100 100 -50 -60 re\nf\n", stream)
    }

    /**
     * "The path construction operators may be invoked in any sequence, but the
     * first one invoked shall be m or re to begin a new subpath" (§8.5.2.1); a
     * further `m` inside the path object begins a further subpath, and one path
     * object may hold several.
     */
    @Test
    fun `a second m inside a path object begins a further subpath`() {
        val stream =
            content {
                moveTo(0.0, 0.0)
                lineTo(10.0, 0.0)
                moveTo(0.0, 20.0)
                lineTo(10.0, 20.0)
                stroke()
            }
        assertEquals("0 0 m\n10 0 l\n0 20 m\n10 20 l\nS\n", stream)
    }

    /**
     * "After the current path has been painted, it shall become no longer
     * defined; there is then no current path until a new one is begun with the m
     * or re operator" (§8.5.2.1) — so a painting operator returns the stream to
     * the content stream level, where the next path object may start.
     */
    @Test
    fun `a painting operator ends the path object and the next one may begin`() {
        val stream =
            content {
                rectangle(0.0, 0.0, 10.0, 10.0)
                fill()
                rectangle(20.0, 0.0, 10.0, 10.0)
                stroke()
            }
        assertEquals("0 0 10 10 re\nf\n20 0 10 10 re\nS\n", stream)
    }

    /**
     * "Occurrences of the q and Q operators shall be balanced within a given
     * content stream" (§8.4.2). An unmatched `q` is only visible once the stream
     * is finished, so that is where it is reported.
     */
    @Test
    fun `a q left unmatched at the end of the stream is rejected`() {
        val builder = ContentStreamBuilder()
        builder.save()
        builder.rectangle(0.0, 0.0, 10.0, 10.0)
        builder.fill()
        assertFailsWith<IllegalStateException> { builder.build() }
    }

    /**
     * The other half of the balance: `Q` "restore[s] the graphics state by
     * removing the most recently saved state from the stack", and there is no
     * such state to remove. The stack is empty at the start of the stream, so
     * this one is caught where it is written.
     */
    @Test
    fun `a Q with nothing saved is rejected`() {
        assertFailsWith<IllegalStateException> { ContentStreamBuilder().restore() }
    }

    /** The same, one level down: three saves and four restores are not balanced. */
    @Test
    fun `more restores than saves are rejected`() {
        val builder = ContentStreamBuilder()
        repeat(3) { builder.save() }
        repeat(3) { builder.restore() }
        assertFailsWith<IllegalStateException> { builder.restore() }
    }

    /**
     * §8.2, the example following Figure 9: "Inside the path object, additional
     * path construction operators are permitted, as are the clipping path
     * operators W and W*, but not general graphics state operators such as w or
     * J." The example names `w`, and Table 50 puts `q`, `Q` and `cm` in the same
     * two graphics state categories.
     */
    @Test
    fun `graphics state operators inside a path object are rejected`() {
        for ((keyword, write) in graphicsStateOperators) {
            val builder = ContentStreamBuilder()
            builder.save() // so that a Q here is refused for its position, not for an empty stack
            builder.moveTo(0.0, 0.0)
            assertFailsWith<IllegalStateException>("$keyword inside a path object begun by m") {
                write(builder)
            }
        }
    }

    /** A rectangle begins a path object as much as a move does (Table 58). */
    @Test
    fun `graphics state operators inside a path object begun by re are rejected`() {
        for ((keyword, write) in graphicsStateOperators) {
            val builder = ContentStreamBuilder()
            builder.save()
            builder.rectangle(0.0, 0.0, 10.0, 10.0)
            assertFailsWith<IllegalStateException>("$keyword inside a path object begun by re") {
                write(builder)
            }
        }
    }

    /**
     * "Most operators that add a segment to the current path start at the
     * current point; if the current point is undefined, an error shall be
     * generated" (§8.5.2.1). At the content stream level there is no current
     * path and so no current point.
     */
    @Test
    fun `a line segment with no current point is rejected`() {
        assertFailsWith<IllegalStateException> { ContentStreamBuilder().lineTo(10.0, 10.0) }
    }

    /**
     * "Attempting to execute a painting operator when the current path is
     * undefined (at the beginning of a new page or immediately after a painting
     * operator has been executed) shall generate an error" (§8.5.3.1). Both
     * positions the clause names are tried here.
     */
    @Test
    fun `painting with no current path is rejected`() {
        assertFailsWith<IllegalStateException> { ContentStreamBuilder().fill() }
        assertFailsWith<IllegalStateException> { ContentStreamBuilder().stroke() }

        val painted = ContentStreamBuilder()
        painted.rectangle(0.0, 0.0, 10.0, 10.0)
        painted.fill()
        assertFailsWith<IllegalStateException> { painted.fill() }
        assertFailsWith<IllegalStateException> { painted.stroke() }
    }

    /**
     * "A path definition is not complete until a path-painting operator has been
     * applied to it" (§8.5.2.1, NOTE), and Figure 9 has the stream begin and end
     * at the content stream level. A stream that stops inside a path object is
     * not a description of anything.
     */
    @Test
    fun `a stream ending inside a path object is rejected`() {
        val builder = ContentStreamBuilder()
        builder.moveTo(0.0, 0.0)
        builder.lineTo(10.0, 10.0)
        assertFailsWith<IllegalStateException> { builder.build() }
    }

    /**
     * The operands are the numeric objects of §7.3.3, written by the serializer
     * that clause 7 is implemented in: an integral value carries no period —
     * "it is not necessary to write the number 1.0 in real format; the integer 1
     * is sufficient" — and no magnitude produces the exponential form the clause
     * forbids a writer to use.
     */
    @Test
    fun `operands are written as the numeric objects of the clause`() {
        assertEquals("0.001 w\n", content { lineWidth(0.001) })
        val stream = content { concat(1e-8, -0.5, 1e20, 1e-20, 0.0, 0.25) }
        assertEquals(
            "0.00000001 -0.5 100000000000000000000 0.00000000000000000001 0 0.25 cm\n",
            stream,
        )
        assertTrue(
            'E' !in stream && 'e' !in stream,
            "a writer shall not use the exponential format for numbers: $stream",
        )
    }

    /**
     * A coordinate that is not a number has no representation in §7.3.3 and is
     * rejected where it is written, rather than reaching a file as something a
     * reader would take for a different number.
     */
    @Test
    fun `a coordinate that is not a real number is rejected`() {
        assertFailsWith<IllegalArgumentException> { ContentStreamBuilder().moveTo(Double.NaN, 0.0) }
        assertFailsWith<IllegalArgumentException> {
            ContentStreamBuilder().moveTo(0.0, Double.POSITIVE_INFINITY)
        }
        assertFailsWith<IllegalArgumentException> { ContentStreamBuilder().lineWidth(Double.NaN) }
    }

    /**
     * Table 111: "wx wy d0 — Set width information for the glyph and declare
     * that the glyph description specifies both its shape and its colour", with
     * `wy` fixed — "wy shall be 0 (see 9.2.4, Glyph positioning and metrics)".
     *
     * The glyph description of a Type 3 font (§9.6.4) begins with it and then
     * paints, which for a colour bitmap is an image placed by a `cm`.
     */
    @Test
    fun `d0 states the glyph width before the description paints anything`() {
        val stream =
            content {
                glyphWidth(1000.0)
                save()
                concat(750.0, 0.0, 0.0, 750.0, 0.0, 0.0)
                paintXObject(PdfName("Im1"))
                restore()
            }
        assertEquals("1000 0 d0\nq\n750 0 0 750 0 0 cm\n/Im1 Do\nQ\n", stream)
    }

    /**
     * "This operator shall only be permitted as the first operator in a content
     * stream appearing in a Type 3 font's CharProcs dictionary" (Table 111) —
     * and a stream that has written anything at all is past that position,
     * whatever it wrote.
     */
    @Test
    fun `d0 after any other operator is rejected`() {
        val builder = ContentStreamBuilder()
        builder.save()

        val rejected = assertFailsWith<IllegalStateException> { builder.glyphWidth(1000.0) }

        assertTrue("first operator" in rejected.message.orEmpty(), rejected.message)
    }

    /** An empty content stream is empty: a page may be described by no instructions at all. */
    @Test
    fun `a content stream with no operators is empty`() {
        assertEquals("", content { })
    }

    /** One instance builds one content stream. */
    @Test
    fun `a builder is finished by building and takes no more operators`() {
        val builder = ContentStreamBuilder()
        builder.rectangle(0.0, 0.0, 10.0, 10.0)
        builder.fill()
        builder.build()
        assertFailsWith<IllegalStateException> { builder.build() }
        assertFailsWith<IllegalStateException> { builder.save() }
        assertFailsWith<IllegalStateException> { builder.moveTo(0.0, 0.0) }
    }

    /**
     * A whole graphical element: the graphics state saved, a transformation
     * concatenated, a line width set, a path built from a move and two segments,
     * stroked, and the state restored. Every operator of Table 56 and Table 58
     * this builder has, in the arrangement Figure 9 describes — the graphics
     * state changed at the content stream level, the path object entire between
     * its first construction operator and its painting operator.
     */
    @Test
    fun `a complete graphical element is written operator by operator`() {
        val stream =
            content {
                save()
                concat(2.0, 0.0, 0.0, 2.0, 50.0, 50.0)
                lineWidth(1.5)
                moveTo(0.0, 0.0)
                lineTo(100.0, 0.0)
                lineTo(100.0, 100.0)
                stroke()
                rectangle(10.0, 10.0, 20.0, 20.0)
                fill()
                restore()
            }
        assertEquals(
            "q\n" +
                "2 0 0 2 50 50 cm\n" +
                "1.5 w\n" +
                "0 0 m\n" +
                "100 0 l\n" +
                "100 100 l\n" +
                "S\n" +
                "10 10 20 20 re\n" +
                "f\n" +
                "Q\n",
            stream,
        )
    }

    /**
     * The operators of the "general graphics state" and "special graphics state"
     * categories of Table 50 that this builder writes, each as a call that
     * cannot appear inside a path object.
     */
    private val graphicsStateOperators: List<Pair<String, (ContentStreamBuilder) -> Unit>> =
        listOf(
            "q" to { builder: ContentStreamBuilder -> builder.save() },
            "Q" to { builder: ContentStreamBuilder -> builder.restore() },
            "cm" to { builder: ContentStreamBuilder -> builder.concat(1.0, 0.0, 0.0, 1.0, 0.0, 0.0) },
            "w" to { builder: ContentStreamBuilder -> builder.lineWidth(1.0) },
        )
}

/**
 * The content stream [instructions] describe, decoded — the operators and their
 * operands are ASCII, so the bytes read back as the text of the clause's tables.
 */
internal fun content(instructions: ContentStreamBuilder.() -> Unit): String {
    val builder = ContentStreamBuilder()
    builder.instructions()
    return builder.build().decodeToString()
}
