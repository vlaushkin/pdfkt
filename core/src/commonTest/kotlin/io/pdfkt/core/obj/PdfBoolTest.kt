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
import kotlin.test.assertNotEquals

// ISO 32000-2:2020 §7.3.2 "Boolean objects"

/**
 * What the clause requires of a boolean object:
 *
 * > Boolean objects represent the logical values of true and false. They
 * > appear in PDF files using the keywords true and false.
 *
 * Two keywords, two objects. The bytes are asserted numerically rather than
 * through a Kotlin string literal, because "the keyword is spelled `true`" and
 * "the keyword is these four ASCII bytes" are different claims and §7.2.2 —
 * the tokens that describe the structure of a PDF file shall use the ASCII
 * character set — makes the second one the one that matters.
 */
class PdfBoolTest {
    @Test
    fun `the true keyword is the ASCII bytes t r u e`() {
        assertContentEquals(byteArrayOf(0x74, 0x72, 0x75, 0x65), serialize(PdfBool(true)))
    }

    @Test
    fun `the false keyword is the ASCII bytes f a l s e`() {
        assertContentEquals(byteArrayOf(0x66, 0x61, 0x6C, 0x73, 0x65), serialize(PdfBool(false)))
    }

    /**
     * §7.2.3: "PDF syntax is case-sensitive; corresponding uppercase and
     * lowercase letters are distinct." `True` is therefore not a spelling of
     * the keyword but a different token altogether.
     */
    @Test
    fun `the keywords are lowercase`() {
        for (value in listOf(true, false)) {
            val text = serializeToText(PdfBool(value))
            assertEquals(text.lowercase(), text, "the keyword for $value is not written in lowercase")
        }
    }

    /**
     * The keyword carries no white-space of its own: separating it from a
     * neighbouring token belongs to whoever writes the surrounding array,
     * dictionary or indirect object.
     */
    @Test
    fun `a boolean keyword is a single token`() {
        for (value in listOf(true, false)) {
            assertSingleRegularToken(serialize(PdfBool(value)), "the keyword for $value")
        }
    }

    @Test
    fun `the two keywords are distinct`() {
        assertNotEquals(serializeToText(PdfBool(true)), serializeToText(PdfBool(false)))
    }

    /**
     * The clause admits exactly two boolean objects, so however many
     * `PdfBool`s are constructed, there are two of them — separately built
     * `true`s are one and the same boolean object, not two that happen to
     * agree.
     */
    @Test
    fun `there are exactly two boolean objects`() {
        val distinct = setOf(PdfBool(true), PdfBool(false), PdfBool(true), PdfBool(false))
        assertEquals(2, distinct.size, "PdfBool has more than the two values the clause defines")
        assertEquals(PdfBool(true), PdfBool(true))
    }

    @Test
    fun `a boolean object carries the logical value it was built from`() {
        assertEquals(true, PdfBool(true).value)
        assertEquals(false, PdfBool(false).value)
    }
}
