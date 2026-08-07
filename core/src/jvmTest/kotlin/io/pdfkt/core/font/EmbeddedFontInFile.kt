/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.font

import io.pdfkt.core.obj.filteredStreamBytes
import io.pdfkt.core.obj.firstPageOf
import io.pdfkt.core.obj.referenceIn
import io.pdfkt.core.obj.showObject
import java.nio.file.Path
import kotlin.test.assertNotNull

// ISO 32000-2:2020 §7.8.3 "Resource dictionaries", §9.7 "Composite fonts"
// (Tables 115, 119), §9.8 "Font descriptors" (Table 120),
// §9.9 "Embedded font programs" (Tables 124, 125)

/**
 * The chain of §9.7 as a reader follows it, in a written file:
 *
 * ```
 * page → /Resources /Font /F1 → Type 0 font → /DescendantFonts [ CIDFont ]
 *                                             → /FontDescriptor → /FontFile2
 * ```
 *
 * Every step is an indirect reference the file has to carry, so a test that
 * asks for the program at the bottom has asserted the whole chain by getting
 * there. Shared by the jvm tests of the embedded path so that both of them mean
 * the same thing by "the font in the file".
 */

/**
 * The Type 0 font (Table 119) the first page names [resourceName] — the name a
 * content stream's `Tf` operator selected it by, looked up in the page's
 * resource dictionary as §7.8.3 requires.
 */
internal fun type0FontIn(path: Path, resourceName: String = FIRST_FONT_RESOURCE): String {
    val page = showObject(path, firstPageOf(path))
    val font = assertNotNull(referenceIn(page, resourceName), "the page names no font /$resourceName:\n$page")
    return showObject(path, font)
}

/**
 * The one entry of `/DescendantFonts` — "A one-element array specifying the
 * CIDFont dictionary that is the descendant of this Type 0 font" (Table 119).
 */
internal fun descendantFontIn(type0: String): String = assertNotNull(
    Regex("/DescendantFonts\\s*\\[\\s*(\\d+ \\d+ R)\\s*]").find(type0)?.groupValues?.get(1),
    "the Type 0 font names no descendant CIDFont:\n$type0",
)

/**
 * The font program the file carries for [resourceName], as qpdf decodes it:
 * the `FontFile2` of Table 125, "A stream containing a TrueType font program".
 */
internal fun embeddedProgramIn(path: Path, resourceName: String = FIRST_FONT_RESOURCE): ByteArray {
    val cidFont = showObject(path, descendantFontIn(type0FontIn(path, resourceName)))
    val descriptor = showObject(path, assertNotNull(referenceIn(cidFont, "FontDescriptor"), cidFont))
    return filteredStreamBytes(path, assertNotNull(referenceIn(descriptor, "FontFile2"), descriptor))
}

/** The name a [FontCatalog] gives the first font a document resolves. */
private const val FIRST_FONT_RESOURCE: String = "F1"
