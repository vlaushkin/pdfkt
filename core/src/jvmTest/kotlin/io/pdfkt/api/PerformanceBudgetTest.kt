/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.api

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertTrue

// No clause applies: how long a file takes to write is this repository's own
// contract, not the standard's.

/**
 * The performance budget: ten A4 pages, each carrying a JPEG, written in
 * under 1.5 s at the 95th percentile.
 *
 * The photograph is a real one — 200 × 200, about 6 KB, committed with the
 * suite — rather than a synthetic header.
 * A stub with no scan in it embeds as almost nothing and
 * measures almost nothing; the cost this budget is about is the document that
 * carries the bytes.
 *
 * The measurement is the whole of `document { }.toByteArray()`, which is how
 * an album is written: ten pictures and the page breaks between them.
 */
class PerformanceBudgetTest {

    @Test
    @DisplayName("ten photographs are written in under 1.5 seconds at the 95th percentile")
    fun tenPhotographsAreWrittenWithinTheBudget() {
        repeat(WARMUP_RUNS) { album() }

        var written = 0
        val durations = (0 until BENCHMARK_RUNS).map {
            val started = System.nanoTime()
            written = album().size
            (System.nanoTime() - started) / NANOS_PER_MILLI
        }.sorted()

        // One photograph and not ten: the same bytes drawn on ten pages are one
        // image XObject named on each of them (`ImageCatalog`), so the file is
        // ten pages around a single scan. The floor is still what says the run
        // measured a document with a photograph in it rather than nothing.
        assertTrue(
            written > photograph.size,
            "an album of $PHOTOGRAPHS photographs came out $written bytes long, less than the ${photograph.size} " +
                "bytes of the photograph in it — a run that measured nothing measures no budget",
        )

        val p95 = durations[(durations.size * P95).toInt()]
        assertTrue(
            p95 < BUDGET_MILLIS,
            "the performance budget gives $PHOTOGRAPHS photographs $BUDGET_MILLIS ms at the 95th " +
                "percentile; this run took $p95 ms, over $BENCHMARK_RUNS runs of " +
                "${durations.first()}..${durations.last()} ms",
        )
    }

    /** Ten photographs, one to a page, as a caller writes them. */
    private fun album(): ByteArray = document {
        pageSize = PageSize.A4
        margins = Margins.Fixed()
        clock = Clock { PdfDate.ofEpochSeconds(PINNED_EPOCH_SECONDS, offsetMinutes = OFFSET_MINUTES) }
        repeat(PHOTOGRAPHS) { index ->
            if (index > 0) pageBreak()
            image(photograph)
        }
    }.toByteArray()

    /**
     * The photograph, read from this module's own test resources.
     *
     * Committed rather than encoded at run time, unlike the fixtures of the
     * gates around it: what is measured here is a cost, and a cost measured
     * against a different picture on every run is not a baseline.
     */
    private val photograph: ByteArray by lazy {
        val fixture = File(FIXTURE).absoluteFile
        assertTrue(fixture.exists(), "the photograph this budget was measured against is missing: $fixture")
        fixture.readBytes()
    }

    private companion object {
        const val FIXTURE = "src/jvmTest/resources/fixtures/sample-photo.jpg"

        const val PHOTOGRAPHS = 10

        /** Enough to let the JIT settle; the budget is about steady state, not the first call. */
        const val WARMUP_RUNS = 3
        const val BENCHMARK_RUNS = 20

        const val P95 = 0.95
        const val BUDGET_MILLIS = 1500L
        const val NANOS_PER_MILLI = 1_000_000L

        /** 2026-08-06T09:30:00 three hours east of UT, so the file carries a non-zero offset. */
        const val PINNED_EPOCH_SECONDS = 1_785_997_800L
        const val OFFSET_MINUTES = 180
    }
}
