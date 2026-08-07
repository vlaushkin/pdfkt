/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.doc

import io.pdfkt.core.obj.PdfArray
import io.pdfkt.core.obj.PdfDict
import io.pdfkt.core.obj.PdfInt
import io.pdfkt.core.obj.PdfName
import io.pdfkt.core.obj.PdfRef
import io.pdfkt.core.obj.serializeToText
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

// ISO 32000-2:2020 §7.7.2 "Document catalog dictionary",
// §7.7.3.2 "Page tree nodes", §7.7.3.3 "Page objects", §7.9.5 "Rectangles"

/**
 * The document structure this writer builds, asserted against the three tables
 * that define it: Table 29 for the catalog, Table 30 for a page tree node and
 * Table 31 for a page object.
 *
 * The assertions are on the definitions as they appear in the body, because the
 * entries are the whole of the claim — a page tree whose `/Count` disagrees with
 * its `/Kids`, or a page whose `/Parent` names something else, is a file that
 * opens and shows the wrong thing.
 */
class PdfDocumentWriterTest {
    /**
     * A one-page document, object by object.
     *
     * The root node is reserved first (object 1) because both directions of the
     * parent-child link have to be writable before either dictionary exists;
     * then the content stream (2) and the page (3), and the catalog last (4),
     * since it can only be written once the tree it names is complete.
     */
    @Test
    fun `a one page document is a catalog a page tree node and a page`() {
        val file = document { writer ->
            writer.addPage(
                mediaBox = pdfRectangle(0.0, 0.0, 612.0, 792.0),
                resources = PdfDict(emptyMap()),
                content = "BT ET\n".encodeToByteArray(),
            )
        }

        assertDefines(
            file,
            "3 0 obj\n" +
                "<</Type /Page /Parent 1 0 R /Resources <<>> /MediaBox [0 0 612 792] " +
                "/Contents 2 0 R>>\n" +
                "endobj",
        )
        assertDefines(file, "1 0 obj\n<</Type /Pages /Kids [3 0 R] /Count 1>>\nendobj")
        assertDefines(file, "4 0 obj\n<</Type /Catalog /Pages 1 0 R>>\nendobj")
    }

    /**
     * Table 30 on `/Count`: "The number of leaf nodes (page objects) that are
     * descendants of this node within the page tree", and "A PDF writer shall
     * ensure that the value of the Count key is consistent with the number of
     * entries in the Kids array and its descendants."
     *
     * In a flat tree the descendants are the children, so the two are the same
     * number and the pages appear in `/Kids` in the order they were added — the
     * order §7.7.3.1 gives the document, since the tree is what "the pages of a
     * document are accessed through".
     */
    @Test
    fun `the count of the root node is the number of pages added`() {
        val pages = mutableListOf<PdfRef>()
        val file = document { writer ->
            repeat(3) {
                pages +=
                    writer.addPage(
                        mediaBox = pdfRectangle(0.0, 0.0, 612.0, 792.0),
                        resources = PdfDict(emptyMap()),
                        content = "BT ET\n".encodeToByteArray(),
                    )
            }
        }

        assertEquals(listOf(PdfRef(3), PdfRef(5), PdfRef(7)), pages)
        assertDefines(file, "1 0 obj\n<</Type /Pages /Kids [3 0 R 5 0 R 7 0 R] /Count 3>>\nendobj")
    }

    /**
     * "If the page requires no resources, the value of this entry shall be an
     * empty dictionary" (Table 31) — so `/Resources` is written either way, and
     * a page with a font resource carries the `/Font` subdictionary §7.8.3 says
     * a text operator's name is looked up in.
     */
    @Test
    fun `a page carries the resource dictionary its content stream names`() {
        val file = document { writer ->
            val font = writer.define(PdfDict(mapOf(PdfName("Type") to PdfName("Font"))))
            writer.addPage(
                mediaBox = pdfRectangle(0.0, 0.0, 612.0, 792.0),
                resources = PdfDict(mapOf(PdfName("Font") to PdfDict(mapOf(PdfName("F1") to font)))),
                content = "BT ET\n".encodeToByteArray(),
            )
        }

        assertTrue(
            file.contains("/Resources <</Font <</F1 2 0 R>>>>"),
            "the page does not carry the resource dictionary it was given:\n$file",
        )
    }

    /**
     * The content of a page is "A content stream (see 7.8.2)" (Table 31), and
     * "all streams shall be indirect objects" (§7.3.8) — so the bytes reach the
     * file as an object of their own, with the `/Length` of §7.3.8.2 computed
     * from them, and the page refers to it.
     */
    @Test
    fun `the content of a page is an indirect stream object`() {
        val content = "0 0 100 100 re\nf\n".encodeToByteArray()
        val file = document { writer ->
            writer.addPage(
                mediaBox = pdfRectangle(0.0, 0.0, 612.0, 792.0),
                resources = PdfDict(emptyMap()),
                content = content,
            )
        }

        assertDefines(
            file,
            "2 0 obj\n<</Length ${content.size}>>\nstream\n" + content.decodeToString() + "\nendstream\nendobj",
        )
    }

    /**
     * §7.9.5: "A rectangle shall be written as an array of four numbers giving
     * the coordinates of a pair of diagonally opposite corners", in the order
     * `[llx lly urx ury]`. A real whose value is integral is written as an
     * integer (§7.3.3), which is why the letter-size box comes out as `0 0 612
     * 792` and the A4 one keeps its fraction.
     */
    @Test
    fun `a rectangle is four numbers in the order the clause gives`() {
        assertEquals("[0 0 612 792]", serializeToText(pdfRectangle(0.0, 0.0, 612.0, 792.0)))
        assertEquals("[10 20 30 40]", serializeToText(pdfRectangle(10.0, 20.0, 30.0, 40.0)))
        val file = document { writer ->
            writer.addPage(
                mediaBox = pdfRectangle(0.0, 0.0, 595.276, 841.89),
                resources = PdfDict(emptyMap()),
                content = "BT ET\n".encodeToByteArray(),
            )
        }
        assertTrue(
            file.contains("/MediaBox [0 0 595.276 841.89]"),
            "the media box is not the rectangle that was given:\n$file",
        )
    }

    /** An array that is not four numbers is not a rectangle (§7.9.5). */
    @Test
    fun `a media box that is not a rectangle is refused`() {
        for (notARectangle in listOf(
            PdfArray(emptyList()),
            PdfArray(listOf(PdfInt(0), PdfInt(0), PdfInt(612))),
            PdfArray(listOf(PdfInt(0), PdfInt(0), PdfInt(612), PdfInt(792), PdfInt(0))),
            PdfArray(listOf(PdfInt(0), PdfInt(0), PdfInt(612), PdfName("792"))),
        )) {
            assertFailsWith<IllegalArgumentException>("$notARectangle was accepted as a media box") {
                PdfDocumentWriter(Buffer()).addPage(notARectangle, PdfDict(emptyMap()), ByteArray(0))
            }
        }
    }

    /**
     * A page tree with no leaves is refused: `/Count 0` is a document with
     * nothing to display, and this writer produces documents.
     */
    @Test
    fun `a document with no pages is refused`() {
        assertFailsWith<IllegalStateException> {
            PdfDocumentWriter(Buffer()).finish(TEST_IDENTIFIER)
        }
    }

    /** One instance writes one document — the same rule the file writer has. */
    @Test
    fun `a finished document takes no further pages`() {
        val writer = PdfDocumentWriter(Buffer())
        writer.addPage(pdfRectangle(0.0, 0.0, 612.0, 792.0), PdfDict(emptyMap()), ByteArray(0))
        writer.finish(TEST_IDENTIFIER)

        assertFailsWith<IllegalStateException> {
            writer.addPage(pdfRectangle(0.0, 0.0, 612.0, 792.0), PdfDict(emptyMap()), ByteArray(0))
        }
        assertFailsWith<IllegalStateException> { writer.finish(TEST_IDENTIFIER) }
    }

    /** Runs [block] against a document writer and returns the file, as text. */
    private fun document(block: (PdfDocumentWriter) -> Unit): String {
        val sink = Buffer()
        val writer = PdfDocumentWriter(sink)
        block(writer)
        writer.finish(TEST_IDENTIFIER)
        return sink.readByteArray().decodeToString()
    }

    private fun assertDefines(file: String, definition: String) {
        assertTrue(file.contains(definition), "the file does not define\n$definition\nbut is\n$file")
    }

    private companion object {
        /** Sixteen bytes, the minimum Table 15 allows of a file identifier. */
        val TEST_IDENTIFIER: ByteArray = ByteArray(16) { (0xA0 + it).toByte() }
    }
}
