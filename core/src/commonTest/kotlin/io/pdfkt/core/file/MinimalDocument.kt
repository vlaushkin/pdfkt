/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.file

import io.pdfkt.core.obj.PdfArray
import io.pdfkt.core.obj.PdfDict
import io.pdfkt.core.obj.PdfInt
import io.pdfkt.core.obj.PdfName
import io.pdfkt.core.obj.PdfRef
import kotlinx.io.Buffer
import kotlinx.io.readByteArray

// ISO 32000-2:2020 §7.7.2 "Document catalog dictionary",
// §7.7.3 "Page tree"

/**
 * The smallest document there is: a catalog, a page tree of one node, and one
 * empty page.
 *
 * Every entry of the three dictionaries is one the tables mark *Required*, and
 * no entry is present that is not:
 *
 * - the catalog (Table 29) — `/Type /Catalog` and `/Pages`, "shall be an
 *   indirect reference", the root of the page tree;
 * - the root page tree node (Table 30) — `/Type /Pages`, `/Kids` "an array of
 *   indirect references to the immediate children", and `/Count`, "The number of
 *   leaf nodes (page objects) that are descendants of this node". `/Parent` is
 *   absent because it is "not permitted in the root node";
 * - the page (Table 31) — `/Type /Page`, `/Parent`, `/Resources` ("If the page
 *   requires no resources, the value of this entry shall be an empty
 *   dictionary") and `/MediaBox`. `/Contents` is optional, and its absence is
 *   what makes the page empty.
 *
 * The page tree node is *reserved before* the page and *defined after* it, so
 * the file exercises the case the writer exists for: the page names its parent
 * and the parent names the page, and whichever is written second is referred to
 * by an identifier handed out before either was written.
 */
internal fun writeMinimalDocument(writer: PdfFileWriter) {
    val pages = writer.reserve()
    val page =
        writer.define(
            PdfDict(
                mapOf(
                    PdfName("Type") to PdfName("Page"),
                    PdfName("Parent") to pages,
                    PdfName("Resources") to PdfDict(emptyMap()),
                    PdfName("MediaBox") to
                        PdfArray(listOf(PdfInt(0), PdfInt(0), PdfInt(612), PdfInt(792))),
                ),
            ),
        )
    writer.define(
        pages,
        PdfDict(
            mapOf(
                PdfName("Type") to PdfName("Pages"),
                PdfName("Kids") to PdfArray(listOf(page)),
                PdfName("Count") to PdfInt(1),
            ),
        ),
    )
    val catalog =
        writer.define(
            PdfDict(mapOf(PdfName("Type") to PdfName("Catalog"), PdfName("Pages") to pages)),
        )
    writer.finish(root = catalog, fileIdentifier = TEST_FILE_IDENTIFIER)
}

/** The object numbers [writeMinimalDocument] hands out, in the order it does. */
internal val PAGES: PdfRef = PdfRef(1)
internal val PAGE: PdfRef = PdfRef(2)
internal val CATALOG: PdfRef = PdfRef(3)

/**
 * A file identifier for tests: sixteen bytes, the minimum Table 15 allows, and
 * the same ones every time.
 *
 * §14.4 wants an identifier unique to the file and derived from the time it was
 * written; a test wants the opposite, since a file that differs from run to run
 * cannot be asserted about byte for byte. Uniqueness is the caller's business
 * either way — the writer takes the bytes it is given.
 */
internal val TEST_FILE_IDENTIFIER: ByteArray = ByteArray(16) { (0xA0 + it).toByte() }

/** [TEST_FILE_IDENTIFIER] as the hexadecimal string object §7.3.4 writes. */
internal val TEST_FILE_IDENTIFIER_HEX: String =
    TEST_FILE_IDENTIFIER.joinToString("") { byte ->
        val code = byte.toInt() and 0xFF
        val digits = "0123456789ABCDEF"
        "${digits[code shr 4]}${digits[code and 0x0F]}"
    }

/** Runs [block] against a writer and returns the file it produced. */
internal fun pdfFile(block: (PdfFileWriter) -> Unit): ByteArray {
    val sink = Buffer()
    block(PdfFileWriter(sink))
    return sink.readByteArray()
}
