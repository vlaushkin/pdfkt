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
import kotlin.test.assertNotEquals
import kotlin.test.assertSame

// ISO 32000-2:2020 §7.3.9 "Null object"

/**
 * What the clause requires of the null object:
 *
 * > The null object has a type and value that are unequal to those of any
 * > other object. There shall be only one object of type null, denoted by the
 * > keyword null.
 *
 * The two remaining sentences of the clause are enforced elsewhere and tested
 * there: a reference to a nonexistent object reads as null (§7.3.10, the
 * writer), and a dictionary entry whose value is null is equivalent to no
 * entry at all (§7.3.7, the dictionary writer).
 */
class PdfNullTest {
    @Test
    fun `the null keyword is the ASCII bytes n u l l`() {
        assertContentEquals(byteArrayOf(0x6E, 0x75, 0x6C, 0x6C), serialize(PdfNull))
    }

    /**
     * As with the boolean keywords, the token carries no white-space of its
     * own; the surrounding container separates it from its neighbours.
     */
    @Test
    fun `the null keyword is a single token`() {
        assertSingleRegularToken(serialize(PdfNull), "the null keyword")
    }

    @Test
    fun `the null object is unequal to every other object`() {
        for (other in PdfObjectSamples.all) {
            if (other === PdfNull) continue
            assertNotEquals<PdfObject>(PdfNull, other, "${other::class.simpleName} compared equal to the null object")
            assertNotEquals<PdfObject>(other, PdfNull, "the null object compared equal to ${other::class.simpleName}")
        }
    }

    @Test
    fun `every reference to the null object is the same object`() {
        val viaSamples: PdfObject = PdfObjectSamples.nullObject
        val viaList: PdfObject = listOf<PdfObject>(PdfNull).single()
        assertSame(PdfNull, viaSamples)
        assertSame(PdfNull, viaList)
    }
}
