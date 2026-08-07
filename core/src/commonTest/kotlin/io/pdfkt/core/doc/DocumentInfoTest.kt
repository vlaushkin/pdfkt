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
import io.pdfkt.core.obj.PdfDict
import io.pdfkt.core.obj.PdfName
import io.pdfkt.core.obj.PdfString
import io.pdfkt.core.obj.pdfTextString
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// ISO 32000-2:2020 §14.3.3 "Document information dictionary" (Table 349),
// §7.9.4 "Dates"

/**
 * The information dictionary a document is written with: what a caller said
 * about it, and the two entries a clock says.
 */
class DocumentInfoTest {
    /**
     * A pinned clock reaches `/CreationDate` and `/ModDate` unchanged, as the
     * exact string §7.9.4 spells that instant with.
     *
     * This is the assertion the "real clock, injected" policy exists for:
     * pinning the clock pins the bytes, and
     * a date that came from anywhere else — a platform call inside the engine, a
     * constant left in during development — would not be this string.
     */
    @Test
    fun `the dates of the dictionary are the pinned clock and nothing else`() {
        val clock = Clock { PdfDate(2014, 3, 14, 12, 42, 11, offsetMinutes = 60) }
        val info = documentInformation(Metadata(title = "Report"), creationDate = clock.now())

        assertEquals("D:20140314124211+01'00", text(info, "CreationDate"))
        assertEquals("D:20140314124211+01'00", text(info, "ModDate"))
    }

    /**
     * A file being first written has one instant in it — nothing has modified it
     * since — so `/ModDate` defaults to `/CreationDate`, and says something else
     * only when a caller has something else to say.
     */
    @Test
    fun `the modification date defaults to the creation date and can differ`() {
        val created = PdfDate(2026, 1, 2, 3, 4, 5)
        val modified = PdfDate(2026, 8, 5, 9, 30, 0, offsetMinutes = 3 * 60)
        assertEquals(
            text(documentInformation(Metadata(), created), "CreationDate"),
            text(documentInformation(Metadata(), created), "ModDate"),
        )
        val both = documentInformation(Metadata(), created, modified)
        assertEquals("D:20260102030405Z00'00", text(both, "CreationDate"))
        assertEquals("D:20260805093000+03'00", text(both, "ModDate"))
    }

    /** Table 349's text entries, written as the text strings §14.3.3 requires. */
    @Test
    fun `the entries a caller supplied are written as text strings`() {
        val info =
            documentInformation(
                Metadata(
                    title = "Отчёт",
                    author = "A. Author",
                    subject = "Measurements",
                    keywords = listOf("pdf", "layout"),
                    creator = "an application",
                ),
                PdfDate(2026, 8, 5),
            )

        assertContentEquals(pdfTextString("Отчёт").bytes, string(info, "Title").bytes)
        assertEquals("A. Author", string(info, "Author").bytes.decodeToString())
        assertEquals("Measurements", string(info, "Subject").bytes.decodeToString())
        assertEquals("pdf, layout", string(info, "Keywords").bytes.decodeToString())
        assertEquals("an application", string(info, "Creator").bytes.decodeToString())
        assertEquals(Metadata.PRODUCER, string(info, "Producer").bytes.decodeToString())
    }

    /**
     * Every entry of Table 349 is Optional, and one nobody set is absent rather
     * than empty: "an absent /Title and a /Title of an empty string are
     * different claims".
     */
    @Test
    fun `an entry with nothing to say is not written`() {
        val info = documentInformation(Metadata(producer = null), PdfDate(2026, 8, 5))
        for (key in listOf("Title", "Author", "Subject", "Keywords", "Creator", "Producer", "Trapped")) {
            assertNull(info.entries[PdfName(key)], "/$key was written for a document that says nothing about it")
        }
        assertEquals(2, info.entries.size, "only the two entries §14.3.3 did not deprecate are left")
    }

    /** The producer of a file this library wrote is this library, without being asked. */
    @Test
    fun `the producer defaults to this library`() {
        assertEquals("pdfkt", Metadata().producer)
        assertTrue(
            documentInformation(Metadata(), PdfDate(2026, 8, 5)).entries.containsKey(PdfName("Producer")),
        )
    }

    private fun string(info: PdfDict, key: String): PdfString = info.entries[PdfName(key)] as PdfString

    private fun text(info: PdfDict, key: String): String = string(info, key).bytes.decodeToString()
}
