/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.obj

import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// ISO 32000-2:2020 §7.3.1 "Objects — General"

/**
 * The contract of [writePdfObject], as fixed by the design stage.
 *
 * These tests assert the *dispatch*, not the bytes. The byte-level rules of
 * each clause are asserted by the per-clause tests that queue tasks 2–8 add
 * next to their own writer; asserting them here as well would only duplicate
 * them somewhere the clause is not cited.
 */
class PdfSerializerContractTest {
    @Test
    fun `every variant serializes through the single entry point`() {
        for (obj in PdfObjectSamples.all) {
            val bytes = serialize(obj)
            assertTrue(
                bytes.isNotEmpty(),
                "${obj::class.simpleName} produced no bytes through writePdfObject",
            )
        }
    }

    @Test
    fun `serialization is a pure function of the object`() {
        for (obj in PdfObjectSamples.all) {
            assertEquals(
                serializeToText(obj),
                serializeToText(obj),
                "${obj::class.simpleName} did not serialize identically twice",
            )
        }
    }

    @Test
    fun `array elements are serialized by the same entry point`() {
        val text = serializeToText(PdfObjectSamples.array)
        for (item in PdfObjectSamples.array.items) {
            assertContains(text, serializeToText(item))
        }
    }

    @Test
    fun `dictionary keys and values are serialized by the same entry point`() {
        val text = serializeToText(PdfObjectSamples.dict)
        for ((key, value) in PdfObjectSamples.dict.entries) {
            assertContains(text, serializeToText(key))
            assertContains(text, serializeToText(value))
        }
    }

    @Test
    fun `a stream carries its dictionary through the same entry point`() {
        val text = serializeToText(PdfObjectSamples.stream)
        assertContains(text, serializeToText(PdfObjectSamples.stream.dict))
    }

    @Test
    fun `the entry point appends to the sink and never rewinds it`() {
        val separately = serialize(PdfObjectSamples.int) + serialize(PdfObjectSamples.name)

        val buffer = Buffer()
        buffer.writePdfObject(PdfObjectSamples.int)
        buffer.writePdfObject(PdfObjectSamples.name)

        assertEquals(separately.decodeToString(), buffer.readByteArray().decodeToString())
    }
}
