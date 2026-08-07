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

// ISO 32000-2:2020 §9.2.2 "Basics of showing text",
// §9.3.1 "Text state parameters and operators — General" (Table 103),
// §9.4.1 "Text objects — General" (Table 105),
// §9.4.2 "Text-positioning operators" (Table 106),
// §9.4.3 "Text-showing operators" (Table 107)

/**
 * What the clauses require of the text operators.
 *
 * The bytes are asserted against the operator summaries of Table 103, Table 105,
 * Table 106 and Table 107, and against the examples the clauses themselves print
 * — §9.2.2 EXAMPLE 1, which is the smallest complete piece of text in the
 * standard, and the four `q`/`Q` sequences of §9.4.1, two of which "are valid"
 * and two of which "are not valid".
 */
class ContentStreamTextTest {
    /**
     * §9.2.2 EXAMPLE 1, the whole of it: "This example illustrates the most
     * straightforward use of a font. The text ABC is placed 10 inches from the
     * bottom of the page and 4 inches from the left edge, using 12-point
     * Helvetica."
     *
     * ```
     * BT
     *       /F13 12 Tf
     *       288 720 Td
     *       (ABC) Tj
     * ET
     * ```
     *
     * The five steps the clause lists for it — begin a text object, set the font
     * and size, specify a starting position, paint the glyphs, end the text
     * object — are five calls here, and the bytes are the five lines.
     */
    @Test
    fun `the example of the clause is written line for line`() {
        val stream =
            content {
                beginText()
                textFont(PdfName("F13"), 12.0)
                nextLine(288.0, 720.0)
                showText("ABC".encodeToByteArray())
                endText()
            }
        assertEquals("BT\n/F13 12 Tf\n288 720 Td\n(ABC) Tj\nET\n", stream)
    }

    /**
     * Table 105: `BT` and `ET` take no operands — the column reads "—" for both.
     * "A text object begins with the BT operator and ends with the ET operator"
     * (§9.4.1), and the clause's own example of one contains "zero or more text
     * operators", so an empty text object is a text object.
     */
    @Test
    fun `BT and ET are written as the bare keywords`() {
        val stream =
            content {
                beginText()
                endText()
            }
        assertEquals("BT\nET\n", stream)
    }

    /**
     * Table 103, `Tf`, operands `font size`: "Set the text font, Tf, to font and
     * the text font size, Tfs, to size." The font is a name object, written with
     * the introducing SOLIDUS of §7.3.5; the size is a number.
     */
    @Test
    fun `Tf writes the font resource name and the size`() {
        assertEquals("/F13 12 Tf\n", content { textFont(PdfName("F13"), 12.0) })
    }

    /**
     * "font shall be the name of a font resource in the Font subdictionary of the
     * current resource dictionary" (Table 103) — a name object, so a resource
     * name that is not a single printable token is written by the escaping of
     * §7.3.5 rather than breaking the operand into two.
     */
    @Test
    fun `the font operand is written as a name object`() {
        assertEquals("/Font#201 9 Tf\n", content { textFont(PdfName("Font 1"), 9.0) })
    }

    /**
     * "The text state operators may appear outside text objects, and the values
     * they set are retained across text objects in a single content stream"
     * (§9.3.1). Both halves are asserted: the `Tf` stands before the first `BT`,
     * and the second text object shows text without repeating it.
     */
    @Test
    fun `a font selected outside a text object serves the text objects after it`() {
        val stream =
            content {
                textFont(PdfName("F1"), 10.0)
                beginText()
                showText("one".encodeToByteArray())
                endText()
                beginText()
                showText("two".encodeToByteArray())
                endText()
            }
        assertEquals("/F1 10 Tf\nBT\n(one) Tj\nET\nBT\n(two) Tj\nET\n", stream)
    }

    /**
     * "NOTE Negative text font size is permitted" (§9.3.1), and Table 103 gives
     * zero a meaning rather than refusing it: "Zero sized text shall not mark or
     * clip any pixels (depending on text render mode)." Both are written.
     */
    @Test
    fun `a negative and a zero font size are written`() {
        assertEquals("/F1 -12 Tf\n", content { textFont(PdfName("F1"), -12.0) })
        assertEquals("/F1 0 Tf\n", content { textFont(PdfName("F1"), 0.0) })
    }

    /**
     * Table 106, `Td`, operands `tx ty`: "Move to the start of the next line,
     * offset from the start of the current line by (tx, ty). tx and ty shall
     * denote numbers expressed in unscaled text space units."
     */
    @Test
    fun `Td writes the offset of the next line`() {
        val stream =
            content {
                beginText()
                nextLine(72.0, 720.0)
                nextLine(0.0, -14.5)
                endText()
            }
        assertEquals("BT\n72 720 Td\n0 -14.5 Td\nET\n", stream)
    }

    /**
     * Table 106, `Tm`, operands `abcdef`: "Although the operands specify a
     * matrix, they shall be passed to Tm as six separate numbers, not as an
     * array." The matrix here is the identity one the clause names as the initial
     * value, `[1 0 0 1 0 0]`, translated to a position on the page.
     */
    @Test
    fun `Tm writes six separate numbers and not an array`() {
        val stream =
            content {
                beginText()
                textMatrix(1.0, 0.0, 0.0, 1.0, 100.0, 200.0)
                endText()
            }
        assertEquals("BT\n1 0 0 1 100 200 Tm\nET\n", stream)
        assertTrue('[' !in stream && ']' !in stream, "the operands were written as an array: $stream")
    }

    /** The six operands reach the stream in the order of the matrix `[a b c d e f]`. */
    @Test
    fun `the operands of Tm are written in the order of the matrix`() {
        val stream =
            content {
                beginText()
                textMatrix(1.0, 2.0, 3.0, 4.0, 5.0, 6.0)
                endText()
            }
        assertEquals("BT\n1 2 3 4 5 6 Tm\nET\n", stream)
    }

    /**
     * Table 107, `Tj`, operand `string`: "Show a text string." The operand is a
     * string object, and "The strings shall conform to the syntax for string
     * objects" (§9.4.3), which is the literal form of §7.3.4.2.
     */
    @Test
    fun `Tj writes the string operand`() {
        assertEquals("(Hello) Tj\n", showing { showText("Hello".encodeToByteArray()) })
    }

    /**
     * §9.4.3 states the escaping of a text-showing operand itself, and it is the
     * escaping of §7.3.4: "bytes whose values are equal to those of the ASCII
     * characters LEFT PARENTHESIS (28h), RIGHT PARENTHESIS (29h), and REVERSE
     * SOLIDUS (5Ch) (backslash) shall be preceded by a REVERSE SOLIDUS
     * character."
     *
     * Balanced parentheses need no escape — "Balanced pairs of parentheses within
     * a string require no special treatment" (§7.3.4.2) — and the string is read
     * back as the bytes that went in either way, which is what the rule protects.
     */
    @Test
    fun `the operand of Tj is escaped by the rules for string objects`() {
        assertEquals("(a\\(b) Tj\n", showing { showText("a(b".encodeToByteArray()) })
        assertEquals("(a\\)b) Tj\n", showing { showText("a)b".encodeToByteArray()) })
        assertEquals("(a(b)c) Tj\n", showing { showText("a(b)c".encodeToByteArray()) })
        assertEquals("(a\\\\b) Tj\n", showing { showText("a\\b".encodeToByteArray()) })
    }

    /**
     * "Strings presented to the text-showing operators may be of any length —
     * even an empty string or a single character code per string" (§9.4.3), and
     * NOTE 3 says it again: "Empty strings are valid."
     */
    @Test
    fun `an empty string is shown`() {
        assertEquals("() Tj\n", showing { showText(ByteArray(0)) })
        assertEquals("(A) Tj\n", showing { showText(byteArrayOf(0x41)) })
    }

    /**
     * "A string operand of a text-showing operator shall be interpreted as a
     * sequence of character codes identifying the glyphs to be painted" (§9.4.3)
     * — bytes, not characters, and every one of the 256 values may be one: "All
     * other byte values between 0 and 255 may be used in a string object." A code
     * with no printable spelling reaches the stream through the octal notation of
     * §7.3.4.2, which is the same string object it would be anywhere else.
     */
    @Test
    fun `character codes outside the printable range are shown`() {
        val stream = showing { showText(byteArrayOf(0x01, 0x02, 0x41, 0xFF.toByte())) }
        assertEquals("(\\001\\002A\\377) Tj\n", stream)
    }

    /**
     * Table 107, `TJ`, operand `Array`: "Show zero or more text strings, allowing
     * individual glyph positioning. Each element of array shall be either a
     * string or a number. If the element is a string, this operator shall show
     * the string. If it is a number, the operator shall adjust the text position
     * by that amount … The number shall be expressed in thousandths of a unit of
     * text space."
     */
    @Test
    fun `TJ writes an array of strings and numbers`() {
        val stream =
            showing {
                showTextWithPositioning(
                    listOf(
                        TextArrayElement.Codes("A".encodeToByteArray()),
                        TextArrayElement.Adjustment(-120.0),
                        TextArrayElement.Codes("WAY".encodeToByteArray()),
                        TextArrayElement.Adjustment(62.5),
                        TextArrayElement.Codes("!".encodeToByteArray()),
                    ),
                )
            }
        assertEquals("[(A) -120 (WAY) 62.5 (!)] TJ\n", stream)
    }

    /** The strings of a `TJ` array are string objects, escaped as such (§9.4.3). */
    @Test
    fun `the strings of a TJ array are escaped by the rules for string objects`() {
        val stream =
            showing {
                showTextWithPositioning(
                    listOf(
                        TextArrayElement.Codes("a(b".encodeToByteArray()),
                        TextArrayElement.Adjustment(-10.0),
                        TextArrayElement.Codes("c\\d".encodeToByteArray()),
                    ),
                )
            }
        assertEquals("[(a\\(b) -10 (c\\\\d)] TJ\n", stream)
    }

    /** "Show zero or more text strings" (Table 107) — zero of them included. */
    @Test
    fun `TJ writes an empty array`() {
        assertEquals("[] TJ\n", showing { showTextWithPositioning(emptyList()) })
    }

    /**
     * "The text-positioning operators shall only appear within text objects"
     * (§9.4.2) — so neither may be written at the content stream level, where
     * there is no text matrix for them to alter.
     */
    @Test
    fun `text-positioning operators outside a text object are rejected`() {
        assertFailsWith<IllegalStateException> { ContentStreamBuilder().nextLine(0.0, 0.0) }
        assertFailsWith<IllegalStateException> {
            ContentStreamBuilder().textMatrix(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)
        }
    }

    /** "The text-showing operators shall only appear within text objects" (§9.4.3). */
    @Test
    fun `text-showing operators outside a text object are rejected`() {
        val string = ContentStreamBuilder()
        string.textFont(PdfName("F1"), 12.0)
        assertFailsWith<IllegalStateException> { string.showText("A".encodeToByteArray()) }

        val array = ContentStreamBuilder()
        array.textFont(PdfName("F1"), 12.0)
        assertFailsWith<IllegalStateException> { array.showTextWithPositioning(emptyList()) }
    }

    /** The same, after a text object has been ended: `ET` leaves the text object (§9.4.1). */
    @Test
    fun `text operators after ET are rejected`() {
        val builder = ContentStreamBuilder()
        builder.textFont(PdfName("F1"), 12.0)
        builder.beginText()
        builder.endText()
        assertFailsWith<IllegalStateException> { builder.nextLine(0.0, 0.0) }
        assertFailsWith<IllegalStateException> { builder.showText("A".encodeToByteArray()) }
    }

    /**
     * "Text objects shall not be nested; a second BT shall not appear before an
     * ET" (Table 105).
     */
    @Test
    fun `a second BT before an ET is rejected`() {
        val builder = ContentStreamBuilder()
        builder.beginText()
        assertFailsWith<IllegalStateException> { builder.beginText() }
    }

    /**
     * A text object "begins with the BT operator and ends with the ET operator"
     * (§9.4.1); an `ET` alone ends nothing, and there is no text matrix for it to
     * discard.
     */
    @Test
    fun `an ET without a BT is rejected`() {
        assertFailsWith<IllegalStateException> { ContentStreamBuilder().endText() }

        val ended = ContentStreamBuilder()
        ended.beginText()
        ended.endText()
        assertFailsWith<IllegalStateException> { ended.endText() }
    }

    /**
     * A stream that stops between `BT` and `ET` ends inside a graphics object,
     * which Figure 9 has no arrow out of but the operator that ends it (§8.2,
     * §9.4.1).
     */
    @Test
    fun `a stream ending inside a text object is rejected`() {
        val builder = ContentStreamBuilder()
        builder.textFont(PdfName("F1"), 12.0)
        builder.beginText()
        builder.showText("A".encodeToByteArray())
        assertFailsWith<IllegalStateException> { builder.build() }
    }

    /**
     * "There is no initial value for either font or size; they shall be specified
     * explicitly by using Tf before any text is shown" (Table 103), the text
     * state having been "initialised to [its] default values at the beginning of
     * each page" (§9.3.1). A stream that shows text without a `Tf` has named no
     * font, and no font is not a font.
     */
    @Test
    fun `showing text before any Tf is rejected`() {
        val string = ContentStreamBuilder()
        string.beginText()
        assertFailsWith<IllegalStateException> { string.showText("A".encodeToByteArray()) }

        val array = ContentStreamBuilder()
        array.beginText()
        assertFailsWith<IllegalStateException> {
            array.showTextWithPositioning(listOf(TextArrayElement.Codes("A".encodeToByteArray())))
        }
    }

    /**
     * §9.4.1: "The other operators that may appear in a text object are those
     * related to the general graphics state, colour, and marked-content." The
     * path operators are not among them, and a path object is not a graphics
     * object that Figure 9 lets begin inside another.
     */
    @Test
    fun `path operators inside a text object are rejected`() {
        for ((keyword, write) in pathOperators) {
            val builder = ContentStreamBuilder()
            builder.beginText()
            assertFailsWith<IllegalStateException>("$keyword inside a text object") { write(builder) }
        }
    }

    /**
     * The special graphics state category of Table 50 holds `cm` alone, and
     * §9.4.1 does not name it among the operators that may appear in a text
     * object — it names the *general* graphics state, colour and marked-content.
     */
    @Test
    fun `cm inside a text object is rejected`() {
        val builder = ContentStreamBuilder()
        builder.beginText()
        assertFailsWith<IllegalStateException> { builder.concat(1.0, 0.0, 0.0, 1.0, 0.0, 0.0) }
    }

    /**
     * The mirror image: a path object "admits path construction operators and
     * nothing else" (§8.2, the example after Figure 9), so a text object cannot
     * begin inside one and a font cannot be selected there either.
     */
    @Test
    fun `text operators inside a path object are rejected`() {
        val begun = ContentStreamBuilder()
        begun.moveTo(0.0, 0.0)
        assertFailsWith<IllegalStateException> { begun.beginText() }

        val font = ContentStreamBuilder()
        font.rectangle(0.0, 0.0, 10.0, 10.0)
        assertFailsWith<IllegalStateException> { font.textFont(PdfName("F1"), 12.0) }
    }

    /**
     * §9.2.3 EXAMPLE 2 sets a line width inside the text object — "The w operator
     * sets the line width to 2 units in user space. Given those graphics state
     * parameters, the Tj operator strokes the glyph outlines with a line 2 units
     * thick" — which is §9.4.1 admitting "those related to the general graphics
     * state" into a text object, shown in the standard's own content stream.
     */
    @Test
    fun `general graphics state operators are permitted inside a text object`() {
        val stream =
            content {
                beginText()
                textFont(PdfName("F13"), 48.0)
                nextLine(20.0, 38.0)
                lineWidth(2.0)
                showText("ABC".encodeToByteArray())
                endText()
            }
        assertEquals("BT\n/F13 48 Tf\n20 38 Td\n2 w\n(ABC) Tj\nET\n", stream)
    }

    /**
     * The first sequence §9.4.1 prints as valid: the text object entirely inside
     * the saved state.
     *
     * ```
     * q
     *     BT
     *     …
     *     ET
     * Q
     * ```
     */
    @Test
    fun `a text object inside a saved graphics state is valid`() {
        val stream =
            content {
                save()
                beginText()
                endText()
                restore()
            }
        assertEquals("q\nBT\nET\nQ\n", stream)
    }

    /**
     * The second sequence §9.4.1 prints as valid: the saved state entirely inside
     * the text object.
     *
     * ```
     * BT
     *     q
     *     …
     *     Q
     * ET
     * ```
     */
    @Test
    fun `a saved graphics state inside a text object is valid`() {
        val stream =
            content {
                beginText()
                save()
                lineWidth(2.0)
                restore()
                endText()
            }
        assertEquals("BT\nq\n2 w\nQ\nET\n", stream)
    }

    /**
     * The first sequence §9.4.1 prints as invalid — `q`, `BT`, …, `Q`, `ET` — in
     * which the pairs interleave instead of nesting. The `Q` is the operator that
     * commits the violation, so it is the call that throws.
     */
    @Test
    fun `restoring inside a text object a state saved before it is rejected`() {
        val builder = ContentStreamBuilder()
        builder.save()
        builder.beginText()
        assertFailsWith<IllegalStateException> { builder.restore() }
    }

    /**
     * The second sequence §9.4.1 prints as invalid — `BT`, `q`, …, `ET`, `Q` —
     * the same interleaving the other way round. Here the `ET` is what commits
     * it: the `q` inside the text object is still unmatched.
     */
    @Test
    fun `ending a text object with a q inside it unmatched is rejected`() {
        val builder = ContentStreamBuilder()
        builder.beginText()
        builder.save()
        assertFailsWith<IllegalStateException> { builder.endText() }
    }

    /**
     * A whole page of text: the state saved, the font selected once outside the
     * text objects as §9.3.1 permits, two lines placed by an absolute `Tm` and a
     * relative `Td`, one shown with individual glyph positioning, and the state
     * restored — every operator of Table 103, Table 105, Table 106 and Table 107
     * this builder has, in the arrangement §9.4.1 requires of them.
     */
    @Test
    fun `a complete text object is written operator by operator`() {
        val stream =
            content {
                save()
                textFont(PdfName("F1"), 12.0)
                beginText()
                textMatrix(1.0, 0.0, 0.0, 1.0, 72.0, 720.0)
                showText("Hello".encodeToByteArray())
                nextLine(0.0, -14.0)
                showTextWithPositioning(
                    listOf(
                        TextArrayElement.Codes("W".encodeToByteArray()),
                        TextArrayElement.Adjustment(-80.0),
                        TextArrayElement.Codes("orld".encodeToByteArray()),
                    ),
                )
                endText()
                restore()
            }
        assertEquals(
            "q\n" +
                "/F1 12 Tf\n" +
                "BT\n" +
                "1 0 0 1 72 720 Tm\n" +
                "(Hello) Tj\n" +
                "0 -14 Td\n" +
                "[(W) -80 (orld)] TJ\n" +
                "ET\n" +
                "Q\n",
            stream,
        )
    }

    /** The path operators of Table 58 and Table 59, none of which belongs in a text object. */
    private val pathOperators: List<Pair<String, (ContentStreamBuilder) -> Unit>> =
        listOf(
            "m" to { builder: ContentStreamBuilder -> builder.moveTo(0.0, 0.0) },
            "l" to { builder: ContentStreamBuilder -> builder.lineTo(0.0, 0.0) },
            "re" to { builder: ContentStreamBuilder -> builder.rectangle(0.0, 0.0, 10.0, 10.0) },
            "f" to { builder: ContentStreamBuilder -> builder.fill() },
            "S" to { builder: ContentStreamBuilder -> builder.stroke() },
        )
}

/**
 * The instructions of a text-showing operator alone: what [show] writes inside a
 * text object whose font has been selected, with the `Tf`, `BT` and `ET` around
 * it stripped off again.
 *
 * The frame is required — a text-showing operator "shall only appear within text
 * objects" (§9.4.3) and needs the `Tf` of Table 103 before it — but it is not
 * what these tests are about, and repeating it in every expected string would
 * bury the operand being asserted.
 */
private fun showing(show: ContentStreamBuilder.() -> Unit): String {
    val stream =
        content {
            textFont(PdfName("F1"), 12.0)
            beginText()
            show()
            endText()
        }
    val prefix = "/F1 12 Tf\nBT\n"
    val suffix = "ET\n"
    return stream.substring(prefix.length, stream.length - suffix.length)
}
