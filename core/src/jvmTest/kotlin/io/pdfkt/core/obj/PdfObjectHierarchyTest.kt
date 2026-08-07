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
import kotlin.test.assertTrue

// ISO 32000-2:2020 §7.3.1 "Objects — General"

/**
 * Keeps the contract test honest.
 *
 * `PdfSerializerContractTest` can only assert things about the variants it is
 * handed. This test asks the hierarchy itself which variants exist and fails
 * if [PdfObjectSamples] is missing one — so adding a variant later cannot
 * leave it untested, only unbuildable until it is listed.
 *
 * It lives in `jvmTest` because reflection over a sealed hierarchy is not
 * available in common code; the property it protects is common.
 */
class PdfObjectHierarchyTest {
    @Test
    fun `the sample set covers every variant of the sealed hierarchy`() {
        val declared = PdfObject::class.sealedSubclasses.toSet()
        assertTrue(declared.isNotEmpty(), "reflection reported no variants of PdfObject")

        val sampled = PdfObjectSamples.all.map { it::class }.toSet()

        assertEquals(
            emptySet(),
            declared - sampled,
            "variants missing from PdfObjectSamples.all — the contract test does not cover them",
        )
        assertEquals(
            emptySet(),
            sampled - declared,
            "PdfObjectSamples.all holds something that is not a direct variant of PdfObject",
        )
    }

    @Test
    fun `the hierarchy is closed so the serializer when stays exhaustive`() {
        assertTrue(
            PdfObject::class.isSealed,
            "PdfObject stopped being sealed — writePdfObject is no longer compiler-checked",
        )
    }
}
