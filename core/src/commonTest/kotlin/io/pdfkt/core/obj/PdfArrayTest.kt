/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.obj

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

// ISO 32000-2:2020 §7.3.6 "Array objects"

/**
 * What the clause requires of an array object:
 *
 * > An array object is a one-dimensional collection of objects arranged
 * > sequentially. Unlike arrays in many other computer languages, PDF arrays
 * > may be heterogeneous; that is, an array's elements may be any combination
 * > of numbers, strings, dictionaries, or any other objects, including other
 * > arrays. An indirect reference … shall be used to refer to a stream object,
 * > and may also be used to refer to any other kind of object. An array may
 * > have zero elements.
 *
 * > An array shall be written as a sequence of objects enclosed in SQUARE
 * > BRACKETS (using LEFT SQUARE BRACKET (5Bh) and RIGHT SQUARE BRACKET (5Dh)).
 */
class PdfArrayTest {
    /**
     * The clause's own example:
     *
     * > [549 3.14 false (Ralph) /SomeName]
     *
     * Five elements of five different types — the heterogeneity the clause
     * makes a point of — and a form this writer reproduces byte for byte,
     * having no layout white-space to differ in.
     */
    @Test
    fun `the example of the clause is written byte for byte`() {
        val example =
            PdfArray(
                listOf(
                    PdfInt(549),
                    PdfReal(3.14),
                    PdfBool(false),
                    PdfString("Ralph".encodeToByteArray()),
                    PdfName("SomeName"),
                ),
            )
        assertEquals("[549 3.14 false (Ralph) /SomeName]", serializeToText(example))
    }

    /** "An array may have zero elements." */
    @Test
    fun `an array with no elements is the two brackets`() {
        assertEquals("[]", serializeToText(PdfArray(emptyList())))
    }

    @Test
    fun `an array is enclosed in square brackets whatever it holds`() {
        for (element in PdfObjectSamples.all) {
            if (element is PdfStream) continue // an indirect reference stands for it; see below
            val bytes = serialize(PdfArray(listOf(element)))
            assertEquals(LEFT_SQUARE_BRACKET, bytes.first(), "the array holding a ${element::class.simpleName}")
            assertEquals(RIGHT_SQUARE_BRACKET, bytes.last(), "the array holding a ${element::class.simpleName}")
        }
    }

    /**
     * "a sequence of objects": the elements in order, each in the written form
     * its own clause gives it, and none of them changed by having a neighbour.
     */
    @Test
    fun `the elements are written in order in their own written form`() {
        val elements = PdfObjectSamples.all.filter { it !is PdfStream }
        val expected = elements.joinToString(separator = " ", prefix = "[", postfix = "]") { serializeToText(it) }
        assertEquals(expected, serializeToText(PdfArray(elements)))
    }

    /**
     * White-space "can be used to separate syntactic constructs such as names
     * and numbers from each other" (§7.2.3). Two numbers written with nothing
     * between them would be one number, so the separator is what makes the
     * count of elements survive being written at all.
     */
    @Test
    fun `neighbouring elements are separated so that no two merge into one token`() {
        assertEquals("[1 2 3]", serializeToText(PdfArray(listOf(PdfInt(1), PdfInt(2), PdfInt(3)))))
        assertEquals("[0.5 0.25]", serializeToText(PdfArray(listOf(PdfReal(0.5), PdfReal(0.25)))))
        assertEquals("[true false]", serializeToText(PdfArray(listOf(PdfBool(true), PdfBool(false)))))
        assertEquals("[null null]", serializeToText(PdfArray(listOf(PdfNull, PdfNull))))
        assertEquals("[/A /B]", serializeToText(PdfArray(listOf(PdfName("A"), PdfName("B")))))
    }

    /**
     * > PDF syntax directly supports only one-dimensional arrays. Arrays of
     * > higher dimension can be constructed by using arrays as elements of
     * > arrays, nested to any depth.
     */
    @Test
    fun `arrays nest as elements of arrays`() {
        val matrix =
            PdfArray(
                listOf(
                    PdfArray(listOf(PdfInt(1), PdfInt(2))),
                    PdfArray(listOf(PdfInt(3), PdfInt(4))),
                ),
            )
        assertEquals("[[1 2] [3 4]]", serializeToText(matrix))
    }

    @Test
    fun `nesting to a depth of many arrays is written as that many brackets`() {
        val depth = 64
        var nested: PdfObject = PdfInt(549)
        repeat(depth) { nested = PdfArray(listOf(nested)) }
        assertEquals("[".repeat(depth) + "549" + "]".repeat(depth), serializeToText(nested))
    }

    /** "any combination of numbers, strings, dictionaries, or any other objects". */
    @Test
    fun `an array holds containers of every kind at once`() {
        val mixed =
            PdfArray(
                listOf(
                    PdfDict(mapOf(PdfName("Kids") to PdfArray(listOf(PdfRef(3, 0))))),
                    PdfArray(emptyList()),
                    PdfString("x".encodeToByteArray(), hex = true),
                ),
            )
        assertEquals("[<</Kids [3 0 R]>> [] <78>]", serializeToText(mixed))
    }

    /**
     * The null object is an element like any other. It is only "the value of a
     * dictionary entry" that §7.3.9 makes equivalent to an absent entry —
     * dropping a null from an array would renumber every element after it, and
     * §7.3.6 says nothing of the kind.
     */
    @Test
    fun `a null element is written and keeps the position of the elements after it`() {
        val array = PdfArray(listOf(PdfInt(1), PdfNull, PdfInt(3)))
        assertEquals(3, array.items.size)
        assertEquals("[1 null 3]", serializeToText(array))
    }

    /**
     * > An indirect reference … shall be used to refer to a stream object
     *
     * and "All streams shall be indirect objects" (§7.3.8.1). So the element
     * that stands for a stream is the reference; a stream put in its place is
     * refused where it is written down rather than serialized into a file no
     * reader is required to accept.
     */
    @Test
    fun `a stream is referred to by an indirect reference and cannot be an element`() {
        assertEquals("[3 0 R]", serializeToText(PdfArray(listOf(PdfRef(3, 0)))))
        assertFailsWith<IllegalArgumentException> { PdfArray(listOf(PdfObjectSamples.stream)) }
        assertFailsWith<IllegalArgumentException> {
            PdfArray(listOf(PdfInt(1), PdfObjectSamples.stream))
        }
    }

    /**
     * An array is "a collection of objects arranged sequentially" — the
     * sequence it was built from, not whatever list the caller goes on to
     * edit. Otherwise what a file says would depend on when it was written.
     */
    @Test
    fun `an array keeps the sequence it was built from`() {
        val source = mutableListOf<PdfObject>(PdfInt(1))
        val array = PdfArray(source)
        source.add(PdfInt(2))
        assertEquals(1, array.items.size)
        assertEquals("[1]", serializeToText(array))
    }

    private companion object {
        const val LEFT_SQUARE_BRACKET: Byte = 0x5B
        const val RIGHT_SQUARE_BRACKET: Byte = 0x5D
    }
}
