/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.file

import io.pdfkt.core.obj.PdfDict
import io.pdfkt.core.obj.PdfInt
import io.pdfkt.core.obj.PdfName
import io.pdfkt.core.obj.PdfObject
import io.pdfkt.core.obj.assertQpdfCheckIsClean
import io.pdfkt.core.obj.qpdf
import io.pdfkt.core.obj.skipWithoutQpdf
import io.pdfkt.core.obj.withPdfFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// ISO 32000-2:2020 §7.5 "File structure", §7.7.2 "Document catalog dictionary",
// §7.7.3 "Page tree"

/**
 * A whole file, opened by a PDF processor that is not this one.
 *
 * `PdfFileWriterTest` asserts the bytes of the header, the table and the trailer
 * against §7.5. What it cannot assert is that those bytes are what an
 * independent reader needs in order to find its way from the end of the file to
 * the catalog and down the page tree — the entire purpose of the structure. qpdf
 * is used as a *tool* for that; its output is compared, never its source
 *
 * The check has teeth. `qpdf --check` reads the file the way §7.5.5 prescribes,
 * from the end, and reports every disagreement it finds — a `startxref` that
 * does not land on the keyword, an entry whose offset is not the object it
 * claims, a `/Size` too small for the objects present — as a warning, before
 * falling back on reconstructing the table by scanning. [assertQpdfCheckIsClean]
 * fails on a warning, so a file that only opens *after* recovery does not pass.
 */
class PdfFileWriterQpdfTest {
    /**
     * The smallest document there is: a catalog (§7.7.2), a page tree (§7.7.3)
     * and one empty page, written by [PdfFileWriter] alone.
     *
     * The walk qpdf reports is the walk the structure exists for: the trailer
     * names `/Root`, the catalog names `/Pages`, the node names its `/Kids`.
     * Every step of it crosses an entry of the cross-reference table.
     */
    @Test
    fun `qpdf accepts a minimal document and walks it from the trailer to the page`() {
        if (skipWithoutQpdf("§7.5")) return

        withPdfFile(pdfFile(::writeMinimalDocument)) { path ->
            assertQpdfCheckIsClean(path)

            val pages = qpdf("--show-npages", path.toString())
            assertEquals(0, pages.status, "qpdf could not count the pages:\n${pages.output}")
            assertEquals("1", pages.output.trim(), "the page tree does not hold one page")

            val walked = qpdf("--show-pages", path.toString())
            assertEquals(0, walked.status, "qpdf could not walk the page tree:\n${walked.output}")
            assertTrue(
                walked.output.contains("page 1: ${PAGE.number} 0 R"),
                "the walk from the trailer did not arrive at the page:\n${walked.output}",
            )
        }
    }

    /**
     * The trailer as qpdf reads it back: `/Root`, `/Size` and the `/ID` array of
     * two identical byte strings that §14.4 requires of a file being written for
     * the first time.
     */
    @Test
    fun `qpdf reads back the trailer this writer wrote`() {
        if (skipWithoutQpdf("§7.5.5")) return

        withPdfFile(pdfFile(::writeMinimalDocument)) { path ->
            val trailer = qpdf("--show-object=trailer", path.toString())
            assertEquals(0, trailer.status, "qpdf could not show the trailer:\n${trailer.output}")
            assertTrue(
                trailer.output.contains("/Root ${CATALOG.number} 0 R"),
                "the trailer did not name the catalog:\n${trailer.output}",
            )
            assertTrue(
                trailer.output.contains("/Size 4"),
                "the trailer did not carry the size of the table:\n${trailer.output}",
            )
            // qpdf writes hexadecimal strings back in its own case, and which
            // case the digits are in is not part of the value (§7.3.4).
            assertTrue(
                trailer.output.contains(
                    "<$TEST_FILE_IDENTIFIER_HEX> <$TEST_FILE_IDENTIFIER_HEX>",
                    ignoreCase = true,
                ),
                "the trailer did not carry the file identifier twice:\n${trailer.output}",
            )
        }
    }

    /**
     * Enough objects that their offsets differ in more than the last digits, and
     * one of them padded to push the rest past the four-digit mark.
     *
     * A cross-reference table is only worth anything if the offsets in it are
     * exact: qpdf resolves each object by seeking to the offset its entry gives,
     * so an entry that is out by one byte is an object that does not parse. The
     * objects are deliberately of unequal length, which is what makes every
     * offset a different number and rules out a table that happens to be right
     * because everything in the file is the same size.
     */
    @Test
    fun `qpdf resolves every object of a file whose objects are of unequal length`() {
        if (skipWithoutQpdf("§7.5.4")) return

        val document =
            pdfFile { writer ->
                for (size in 1..FILLERS) writer.define(PdfDict(padding(size)))
                writeMinimalDocument(writer)
            }

        withPdfFile(document) { path ->
            assertQpdfCheckIsClean(path)

            for (number in 1..FILLERS) {
                val shown = qpdf("--show-object=$number,0", path.toString())
                assertEquals(0, shown.status, "qpdf could not reach object $number:\n${shown.output}")
                assertTrue(
                    shown.output.contains("/Filler$number "),
                    "the entry for object $number led to something else:\n${shown.output}",
                )
            }
        }
    }

    /** A dictionary whose written length grows with [size]. */
    private fun padding(size: Int): Map<PdfName, PdfObject> {
        val pad = (1..size).associate { index -> PdfName("Pad$index") to PdfInt(index.toLong()) }
        return mapOf(PdfName("Filler$size") to PdfInt(size.toLong())) + pad
    }

    private companion object {
        /** Enough objects that their offsets differ in more than the last digit. */
        const val FILLERS = 40
    }
}
