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
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

// ISO 32000-2:2020 §7.3.10 "Indirect objects", §7.5.5 "File trailer",
// §7.7.2 "Document catalog dictionary", §7.7.3 "Page tree"

/**
 * Walking the object graph of a written file the way a reader has to: from the
 * trailer's `/Root` down, following indirect references, with qpdf doing the
 * reading.
 *
 * "Any object in a PDF file may be labelled as an indirect object … and be
 * referred to from elsewhere in the file by an indirect reference" (§7.3.10).
 * A test that asserts an entry exists has asserted nothing unless it reached
 * that entry the way a processor does — through the references, starting at
 * "the Root entry in the trailer" (§7.7.2). These are that walk, shared by
 * every jvm test that makes a structural claim, so no test carries a second
 * idea of how the graph is traversed.
 *
 * qpdf is a *tool* here: what is compared is its report of the bytes, never its
 * source.
 */

/** The object [reference] — `N G R`, or `trailer` — as qpdf prints it. */
internal fun showObject(path: Path, reference: String): String {
    val shown = qpdf("--show-object=${reference.removeSuffix(" R").trim()}", path.toString())
    assertEquals(0, shown.status, "qpdf could not show $reference:\n${shown.output}")
    return shown.output
}

/** The value of `/key` in [dictionary], when it is an indirect reference; null when it is not. */
internal fun referenceIn(dictionary: String, key: String): String? =
    Regex("/$key\\s+(\\d+ \\d+ R)").find(dictionary)?.groupValues?.get(1)

/**
 * The data of the stream [reference] carries, with its filters undone — the
 * bytes a processor sees rather than the bytes on disc, so a claim about a
 * stream's content is not also a claim about this library's own `FlateDecode`.
 */
internal fun filteredStreamBytes(path: Path, reference: String): ByteArray {
    val shown =
        qpdf(
            "--show-object=${reference.removeSuffix(" R").trim()}",
            "--filtered-stream-data",
            path.toString(),
        )
    assertEquals(0, shown.status, "qpdf could not decode the stream $reference")
    return shown.bytes
}

/** [filteredStreamBytes], read as the text a CMap or a content stream is. */
internal fun filteredStream(path: Path, reference: String): String =
    filteredStreamBytes(path, reference).decodeToString()

/**
 * The first page of the document, as `N G R`: trailer → `/Root` (§7.7.2) →
 * `/Pages` → the first entry of the root node's `/Kids` (Table 30).
 */
internal fun firstPageOf(path: Path): String {
    val catalog = showObject(path, assertNotNull(referenceIn(showObject(path, "trailer"), "Root")))
    val pages = showObject(path, assertNotNull(referenceIn(catalog, "Pages"), catalog))
    return assertNotNull(Regex("/Kids\\s*\\[\\s*(\\d+ \\d+ R)").find(pages)?.groupValues?.get(1), pages)
}
