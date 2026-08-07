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
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

// ISO 32000-2:2020 §7.3.10 "Indirect objects"

/**
 * What the clause requires of an indirect reference:
 *
 * > The object may be referred to from elsewhere in the file by an indirect
 * > reference. Such indirect references shall consist of the object number, the
 * > generation number, and the keyword R (with white-space separating each
 * > part):
 * >
 * >     12 0 R
 *
 * and of the identifier those two numbers make:
 *
 * > •  A positive integer object number. …
 * > •  A non-negative integer generation number. In a newly created file, all
 * >    indirect objects shall have generation numbers of 0. …
 * >
 * > Together, the combination of an object number and a generation number shall
 * > uniquely identify an indirect object.
 */
class PdfRefTest {
    /** The clause's own example, byte for byte. */
    @Test
    fun `a reference is the two numbers and the keyword R`() {
        assertContentEquals("12 0 R".encodeToByteArray(), serialize(PdfRef(12, 0)))
    }

    /**
     * > …with white-space separating each part
     *
     * One SPACE in each of the two places the rule asks for one, and no
     * white-space anywhere else: what precedes and follows a written object is
     * the business of whoever writes the surrounding container.
     */
    @Test
    fun `each part is separated by one space and nothing surrounds the reference`() {
        val text = serializeToText(PdfRef(1234, 56))
        assertEquals("1234 56 R", text)
        assertEquals(2, text.count { it == ' ' })
        assertTrue(text.first().isDigit() && text.last() == 'R')
    }

    /**
     * The keyword is what makes a reference a reference — without it the same
     * digits are two integer objects — so it is written whatever the numbers
     * are, and so is a generation of zero.
     */
    @Test
    fun `a generation of zero is written out`() {
        assertEquals("7 0 R", serializeToText(PdfRef(7)))
        assertEquals("7 0 R", serializeToText(PdfRef(7, 0)))
    }

    /**
     * > A non-negative integer generation number. … Non-zero generation numbers
     * > may be introduced when the file is later updated.
     */
    @Test
    fun `a non-zero generation is written as it stands`() {
        assertEquals("17 5 R", serializeToText(PdfRef(17, 5)))
        assertEquals("1 65535 R", serializeToText(PdfRef(1, 65535)))
    }

    /**
     * The digits are those of an integer object (§7.3.3): decimal, no
     * separators, no locale — the same on every target, at every magnitude an
     * object number can take.
     */
    @Test
    fun `the numbers are written as plain decimal digits`() {
        assertEquals("1 0 R", serializeToText(PdfRef(1)))
        assertEquals("1000000 0 R", serializeToText(PdfRef(1_000_000)))
        assertEquals("2147483647 0 R", serializeToText(PdfRef(Int.MAX_VALUE)))
    }

    /**
     * > A positive integer object number.
     *
     * Zero is not one: §7.5.4 gives object number 0 to the head of the free
     * chain of the cross-reference table, which is not an object at all.
     */
    @Test
    fun `an object number that is not positive is refused`() {
        for (number in listOf(0, -1, Int.MIN_VALUE)) {
            val refused = assertFailsWith<IllegalArgumentException> { PdfRef(number, 0) }
            assertTrue(
                refused.message?.contains("object number") == true,
                "the refusal of $number does not say what it is about: ${refused.message}",
            )
        }
    }

    /** > A non-negative integer generation number. */
    @Test
    fun `a negative generation number is refused`() {
        for (generation in listOf(-1, Int.MIN_VALUE)) {
            val refused = assertFailsWith<IllegalArgumentException> { PdfRef(1, generation) }
            assertTrue(
                refused.message?.contains("generation number") == true,
                "the refusal of $generation does not say what it is about: ${refused.message}",
            )
        }
    }

    /**
     * > Together, the combination of an object number and a generation number
     * > shall uniquely identify an indirect object.
     *
     * So identifiers of equal parts are one identifier, and differing in either
     * part makes two — which is what lets a writer key a table of objects by
     * the identifier it handed out.
     */
    @Test
    fun `the two numbers together identify the object`() {
        assertEquals(PdfRef(12, 0), PdfRef(12))
        assertEquals(PdfRef(12, 0).hashCode(), PdfRef(12).hashCode())
        assertNotEquals(PdfRef(12, 0), PdfRef(12, 1))
        assertNotEquals(PdfRef(12, 0), PdfRef(13, 0))
    }

    /**
     * > Except where documented to the contrary, any object value may be a
     * > direct or an indirect reference; the semantics are equivalent.
     *
     * A reference is therefore written by the one entry point wherever a value
     * may appear, and the containers of §7.3.6 and §7.3.7 need no case for it.
     * The dictionary here is Example 3's, less the stream it belongs to.
     */
    @Test
    fun `a reference is a value like any other`() {
        assertEquals(
            "<</Length 8 0 R>>",
            serializeToText(PdfDict(mapOf(PdfName("Length") to PdfRef(8, 0)))),
        )
        assertEquals(
            "[3 0 R 4 0 R]",
            serializeToText(PdfArray(listOf(PdfRef(3, 0), PdfRef(4, 0)))),
        )
    }

    /**
     * > An indirect reference to an undefined object shall not be considered an
     * > error by a PDF processor; it shall be treated as a reference to the null
     * > object.
     *
     * Example 2 is about a file containing `17 0 R` and no object 17. Nothing
     * here can know whether an object is defined — that is settled only when a
     * whole file is written — so a reference to an object number that will
     * never be defined is written like any other, and the reader supplies the
     * null. The counterpart rule is that a reference is *not* the null object
     * as far as this layer is concerned: §7.3.7 drops null-valued entries, and
     * a reference survives.
     */
    @Test
    fun `a reference to an object that is never defined is written like any other`() {
        assertEquals("17 0 R", serializeToText(PdfRef(17, 0)))
        assertEquals(
            setOf(PdfName("Dangling")),
            PdfDict(mapOf(PdfName("Dangling") to PdfRef(17, 0), PdfName("Absent") to PdfNull)).entries.keys,
        )
    }
}
