/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.obj

import kotlinx.io.Sink

// ISO 32000-2:2020 §7.3.1 "Objects — General"

/**
 * Writes [obj] in its direct written representation — the form in which an
 * object appears as the value of an array element, of a dictionary entry, or
 * as the body of an indirect object definition.
 *
 * **This is the only entry point.** Serialization is one `when` over the
 * sealed hierarchy rather than a method on each variant, so that:
 *
 * - the compiler proves no variant is missed, now and after every later
 *   variant is added;
 * - the recursion inside containers comes back here, so a variant cannot
 *   acquire a second representation that applies only when nested;
 * - the byte-level rules of a clause live in exactly one function, which is
 *   the function a test for that clause points at.
 *
 * What this function deliberately does *not* do is emit the `N G obj … endobj`
 * framing of an indirect object definition or any inter-object whitespace of
 * the file body. Those depend on byte offsets, and offsets belong to the
 * writer.
 *
 * The per-variant writers are stubs at this stage: each carries the clause it
 * must be completed against and the queue task that will do it. The contract
 * fixed here is the dispatch, not yet the bytes.
 */
public fun Sink.writePdfObject(obj: PdfObject) {
    when (obj) {
        is PdfBool -> writePdfBool(obj)
        is PdfInt -> writePdfInt(obj)
        is PdfReal -> writePdfReal(obj)
        is PdfName -> writePdfName(obj)
        is PdfString -> writePdfString(obj)
        is PdfArray -> writePdfArray(obj)
        is PdfDict -> writePdfDict(obj)
        is PdfStream -> writePdfStream(obj)
        is PdfRef -> writePdfRef(obj)
        PdfNull -> writePdfNull()
    }
}
