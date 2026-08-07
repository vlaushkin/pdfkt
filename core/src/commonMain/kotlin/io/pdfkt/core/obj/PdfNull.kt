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
import kotlinx.io.writeString

// ISO 32000-2:2020 §7.3.9 "Null object"

/**
 * The null object, denoted by the keyword `null`.
 *
 * "There shall be only one object of type null" — hence an object declaration
 * rather than a class: the language admits no second instance, so the rule
 * holds by construction instead of by convention. Its type and value are
 * unequal to those of any other object, which the hierarchy gives for free
 * since no other variant of [PdfObject] is this type.
 *
 * Two further rules of the clause are about *where* a null object appears
 * rather than how it is written, and are therefore enforced by the writers
 * that own those places:
 *
 * - a reference to a nonexistent object is treated the same as the null
 *   object — §7.3.10, and so the writer's business;
 * - the null object as the value of a dictionary entry is equivalent to
 *   omitting the entry entirely — §7.3.7, and so [writePdfDict]'s.
 */
public data object PdfNull : PdfObject

/**
 * Writes the null object as its keyword.
 *
 * As with the boolean keywords: ASCII per §7.2.2, lowercase because §7.2.3
 * makes PDF syntax case-sensitive, and no surrounding white-space — the
 * keyword is a single token of regular characters and the container that holds
 * it separates it from its neighbours.
 */
internal fun Sink.writePdfNull() {
    writeString("null")
}
