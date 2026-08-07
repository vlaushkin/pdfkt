/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.doc

import io.pdfkt.core.obj.PdfDate

// ISO 32000-2:2020 §7.9.4 "Dates", §14.3.3 "Document information dictionary"

/**
 * Where the dates of a document come from.
 *
 * It is a parameter of [DocumentSpec] and not a default anywhere, which is
 * architecture rather than advice: a real clock, injected. `/CreationDate`
 * and `/ModDate` come from a clock
 * passed into the engine, so tests can pin it and production gets the truth.
 *
 * There is no clock in `commonMain` to default to, and that is the second
 * reason: reading the time is a platform capability, and this library has none.
 * The caller,
 * which is on a platform, hands one in:
 *
 * ```
 * // JVM
 * val clock = Clock { PdfDate.ofEpochSeconds(System.currentTimeMillis() / 1000, offsetMinutes = 60) }
 * // a test
 * val pinned = Clock { PdfDate(2014, 3, 14, 12, 42, 11, offsetMinutes = 60) }
 * ```
 *
 * [PdfDate.ofEpochSeconds] is there so that a platform only has to answer the
 * question every platform answers — how many seconds since the epoch — and the
 * calendar is the library's.
 */
public fun interface Clock {
    /**
     * The time now, in the local time it is to be written in.
     *
     * A renderer reads it once per document: a document has one creation time,
     * and two readings could fall either side of a second and produce a file
     * whose `/ModDate` precedes its `/CreationDate`.
     */
    public fun now(): PdfDate
}
