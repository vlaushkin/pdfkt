/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.obj

import kotlinx.io.Sink
import kotlinx.io.writeString

// ISO 32000-2:2020 §7.3.10 "Indirect objects"

/**
 * Writes the definition of an indirect object — the labelling that gives a
 * value an identifier other objects can refer to:
 *
 * > The definition of an indirect object in a PDF file shall consist of its
 * > object number and generation number (separated by white-space), followed by
 * > the value of the object bracketed between the keywords obj and endobj.
 *
 * ```
 * 12 0 obj
 * (Brillig)
 * endobj
 * ```
 *
 * The bytes written are `id`'s two numbers, `obj`, the value, and `endobj`,
 * each separated from the next by a single LINE FEED — the layout the clause's
 * own examples use, and the smallest amount of white-space the rule permits.
 * The separators are not decoration: `obj` and `endobj` are keywords, made of
 * regular characters, so without them `12 0 obj77 endobj` of Example 3 would be
 * one token where two are meant. A delimiter-led value such as `(Brillig)` or a
 * dictionary would need no separator, but the framing does not vary with what
 * it frames — one shape means the byte offset this definition starts at, which
 * is what the cross-reference table records (§7.5.4), is the only thing the
 * writer has to track.
 *
 * The two numbers are the decimal digits of [Int.toString], with no separator
 * and no locale in them on any target — the same digits [writePdfRef] writes,
 * so a definition and every reference that resolves to it carry the identifier
 * byte for byte alike.
 *
 * The value is written through [writePdfObject], so an indirect object is the
 * same bytes as the same value written anywhere else, wrapped. Any object may
 * be the value, including [PdfRef]:
 *
 * > NOTE (2020) Any object outside of an object stream (see 7.5.7, "Object
 * > streams") can consist solely of an object reference. PDF syntax thus
 * > permits chains of such objects.
 *
 * and including [PdfStream] — "All streams shall be indirect objects" (§7.3.8.1)
 * makes this framing the only way a stream reaches a file, and the LINE FEED
 * written before `endobj` is the white-space §7.3.8 allows there: "There shall
 * not be any extra bytes, other than white-space, between endstream and endobj."
 *
 * Nothing is written after `endobj` — not even the end-of-line that will
 * separate this definition from the next. Where one object ends and the next
 * begins is a decision about the file body, taken together with the byte
 * offsets that decision moves, and both belong to the writer.
 * This function frames one object and reports
 * nothing, so a caller that needs the offset takes it before calling.
 *
 * Two things this deliberately does not do:
 *
 * - it does not choose `id`. Numbering is the writer's, per
 *   [PdfObjectNumbering], because uniqueness is a property of a whole file and
 *   not of any one object;
 * - it does not write the object-stream form. An object inside an object stream
 *   is defined without this framing — "their definition shall not include the
 *   keywords obj and endobj, and their generation number shall be zero"
 *   (§7.5.7) — which is a different clause and a different writer.
 */
public fun Sink.writeIndirectObject(id: PdfRef, value: PdfObject) {
    writeString(id.number.toString())
    writeByte(SPACE)
    writeString(id.generation.toString())
    writeByte(SPACE)
    write(OBJ_KEYWORD, 0, OBJ_KEYWORD.size)
    writeByte(LINE_FEED)
    writePdfObject(value)
    writeByte(LINE_FEED)
    write(ENDOBJ_KEYWORD, 0, ENDOBJ_KEYWORD.size)
}

private val OBJ_KEYWORD = byteArrayOf(0x6F, 0x62, 0x6A) // obj
private val ENDOBJ_KEYWORD = byteArrayOf(0x65, 0x6E, 0x64, 0x6F, 0x62, 0x6A) // endobj

/** SPACE (20h), Table 1 of §7.2.3 — the separator between two written tokens. */
private const val SPACE: Byte = 0x20

/** LINE FEED (0Ah), Table 1 of §7.2.3 — an end-of-line marker on its own. */
private const val LINE_FEED: Byte = 0x0A
