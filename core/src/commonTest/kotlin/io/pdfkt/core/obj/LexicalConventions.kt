/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.obj

import kotlin.test.assertTrue

// ISO 32000-2:2020 §7.2.3 "Character set"

/** Table 1 — White-space characters. */
private val WHITE_SPACE = byteArrayOf(0x00, 0x09, 0x0A, 0x0C, 0x0D, 0x20)

/**
 * The delimiter characters of the clause: `(`, `)`, `<`, `>`, `[`, `]`, `/`
 * and `%`, plus `{` and `}`.
 */
private val DELIMITERS = byteArrayOf(0x28, 0x29, 0x3C, 0x3E, 0x5B, 0x5D, 0x2F, 0x25, 0x7B, 0x7D)

/**
 * Asserts that [bytes] is a single token — "a sequence of consecutive regular
 * characters comprises a single token", where regular characters are all
 * characters except white-space and delimiters.
 *
 * This is what makes a keyword the caller's to separate: a writer that emitted
 * a space of its own, or that ran a keyword into a delimiter, would change how
 * the byte sequence tokenizes. Tests for the keyword clauses assert it so that
 * padding cannot creep into a writer unnoticed.
 */
internal fun assertSingleRegularToken(bytes: ByteArray, what: String) {
    assertTrue(bytes.isNotEmpty(), "$what produced no bytes")
    for (byte in bytes) {
        assertTrue(
            byte !in WHITE_SPACE,
            "$what contains a white-space character (${byte.toInt()}) and is therefore not one token",
        )
        assertTrue(
            byte !in DELIMITERS,
            "$what contains a delimiter character (${byte.toInt()}) and is therefore not one token",
        )
    }
}
