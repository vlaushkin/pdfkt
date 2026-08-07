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
import kotlin.jvm.JvmInline

// ISO 32000-2:2020 §7.3.2 "Boolean objects"

/**
 * A boolean object: one of the two logical values of the clause, written with
 * the keyword reserved for it.
 *
 * The clause defines exactly two boolean objects, and this type has exactly
 * two values — not by interning two instances, but because a value class
 * carries no identity beyond its [value]. Every `PdfBool(true)` is the same
 * boolean object as every other; there is no third to construct and no way to
 * tell two `true`s apart.
 */
@JvmInline
public value class PdfBool(public val value: Boolean) : PdfObject

/**
 * Writes a boolean object as the keyword the clause reserves for its value.
 *
 * The keywords are ASCII (§7.2.2: the tokens that describe the structure of a
 * PDF file shall use the ASCII character set) and lowercase — §7.2.3 makes PDF
 * syntax case-sensitive, so `True` would not be a spelling of the keyword but
 * a different token entirely.
 *
 * Nothing is written around the keyword. Both are sequences of regular
 * characters, which §7.2.3 tokenizes as one token up to the next white-space
 * or delimiter, so separating a keyword from a neighbouring token is the job
 * of whoever writes the surrounding array, dictionary or indirect object.
 */
internal fun Sink.writePdfBool(obj: PdfBool) {
    writeString(if (obj.value) "true" else "false")
}
