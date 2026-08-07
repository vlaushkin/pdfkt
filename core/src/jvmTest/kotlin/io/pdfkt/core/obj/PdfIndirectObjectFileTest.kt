/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.obj

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// ISO 32000-2:2020 §7.3.10 "Indirect objects"

/**
 * Indirect objects and the references between them, in a file, resolved by a
 * PDF processor that is not this one.
 *
 * `PdfIndirectObjectTest` and `PdfRefTest` assert the bytes against the clause.
 * What they cannot assert is that those bytes are an object *identifier* — that
 * a reader following `12 0 R` arrives at what `12 0 obj … endobj` frames. qpdf
 * is used as a tool for that; the file it is given comes from [minimalPdfFile].
 */
class PdfIndirectObjectFileTest {
    /**
     * "This gives the object a unique object identifier by which other objects
     * can refer to it (for example, as an element of an array or as the value
     * of a dictionary entry)."
     *
     * The document is nothing but that: a catalog referring to a page tree, a
     * page tree whose `/Kids` array refers to a page, a page referring back to
     * its parent and out to its content stream. Every one of those links is an
     * `N G R` written by this library and resolved by qpdf against an
     * `N G obj … endobj` written by this library — if either spelling of the
     * identifier were wrong, the page tree would not be a tree.
     */
    @Test
    fun `qpdf resolves the references between objects this writer framed`() {
        if (skipWithoutQpdf("§7.3.10")) return

        withPdfFile(minimalPdfFile(catalogWith(extra = emptyMap()))) { path ->
            assertQpdfCheckIsClean(path)

            val pages = showObject(path, 2)
            assertTrue(pages.contains("/Kids [ 3 0 R ]"), "the page tree did not reach its page:\n$pages")

            val page = showObject(path, 3)
            assertTrue(page.contains("/Parent 2 0 R"), "the page did not reach its parent:\n$page")
            assertTrue(page.contains("/Contents 4 0 R"), "the page did not reach its contents:\n$page")

            // --show-pages walks the tree from the catalog and prints what it
            // found: one page, whose content stream is object 4.
            val walked = qpdf("--show-pages", path.toString())
            assertEquals(0, walked.status, "qpdf could not walk the page tree:\n${walked.output}")
            assertTrue(
                walked.output.contains("page 1: 3 0 R") &&
                    walked.output.substringAfter("content").contains("4 0 R"),
                "the walk from the catalog did not arrive at the page and its stream:\n${walked.output}",
            )
        }
    }

    /**
     * > An indirect reference to an undefined object shall not be considered an
     * > error by a PDF processor; it shall be treated as a reference to the null
     * > object.
     *
     * > EXAMPLE 2  If a file contains the indirect reference 17 0 R but does not
     * > contain the corresponding definition then the indirect reference is
     * > considered to refer to the null object.
     *
     * The catalog here carries `/OpenAction 99 0 R` and the file defines four
     * objects. qpdf accepts it without a word — the reference is not an error —
     * and the entry it reads back is gone, which is the null object arriving
     * where the reference was and §7.3.7's "a dictionary entry whose value is
     * null shall be treated the same as if the entry does not exist" applying
     * to it.
     */
    @Test
    fun `a reference to an object the file never defines is not an error`() {
        if (skipWithoutQpdf("§7.3.10")) return

        val undefined = PdfRef(99, 0)
        val document = minimalPdfFile(catalogWith(extra = mapOf(PdfName("OpenAction") to undefined)))

        withPdfFile(document) { path ->
            assertQpdfCheckIsClean(path)

            val catalog = showObject(path, 1)
            assertFalse(catalog.contains("99 0 R"), "the dangling reference survived as a reference:\n$catalog")
            assertFalse(catalog.contains("/OpenAction"), "the entry did not become the null object:\n$catalog")
            assertTrue(catalog.contains("/Pages 2 0 R"), "the defined references did not survive:\n$catalog")
        }
    }

    /** What qpdf reads back for object [objectNumber], generation 0. */
    private fun showObject(path: Path, objectNumber: Int): String {
        val shown = qpdf("--show-object=$objectNumber,0", path.toString())
        assertEquals(0, shown.status, "qpdf could not show object $objectNumber:\n${shown.output}")
        return shown.output
    }

    /**
     * A catalog carrying [extra] beside its `/Pages`, a page tree of one page,
     * that page, and a content stream for it — objects 1 to 4 in that order.
     */
    private fun catalogWith(extra: Map<PdfName, PdfObject>): List<PdfObject> = listOf(
        PdfDict(mapOf(PdfName("Type") to PdfName("Catalog"), PdfName("Pages") to PdfRef(2, 0)) + extra),
        PdfDict(
            mapOf(
                PdfName("Type") to PdfName("Pages"),
                PdfName("Kids") to PdfArray(listOf(PdfRef(3, 0))),
                PdfName("Count") to PdfInt(1),
            ),
        ),
        PdfDict(
            mapOf(
                PdfName("Type") to PdfName("Page"),
                PdfName("Parent") to PdfRef(2, 0),
                PdfName("MediaBox") to PdfArray(listOf(PdfInt(0), PdfInt(0), PdfInt(612), PdfInt(792))),
                PdfName("Resources") to PdfDict(emptyMap()),
                PdfName("Contents") to PdfRef(4, 0),
            ),
        ),
        PdfStream(PdfDict(emptyMap()), "0 0 1 rg\n100 100 200 300 re\nf\n".encodeToByteArray()),
    )
}
