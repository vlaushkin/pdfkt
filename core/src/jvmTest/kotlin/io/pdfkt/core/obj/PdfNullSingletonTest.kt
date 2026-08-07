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
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

// ISO 32000-2:2020 §7.3.9 "Null object"

/**
 * "There shall be only one object of type null."
 *
 * A common test can compare the references it is handed, but it cannot show
 * that no second instance *could* exist. Reflection can: `PdfNull` is an
 * object declaration, so the language itself admits exactly one instance and
 * the rule cannot be broken by a later caller.
 *
 * It lives in `jvmTest` for the same reason `PdfObjectHierarchyTest` does —
 * reflection is not available in common code; the property it protects is.
 */
class PdfNullSingletonTest {
    @Test
    fun `the null type is an object declaration and has exactly one instance`() {
        assertTrue(
            PdfNull::class.objectInstance != null,
            "PdfNull stopped being an object declaration — a second null object can now be created",
        )
        assertSame(PdfNull, assertNotNull(PdfNull::class.objectInstance))
    }
}
