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
 * The object identifier of an indirect object — "an object number and a
 * generation number" — which is both the way an object is referred to and the
 * head of its definition.
 *
 * The clause gives the two parts and their ranges:
 *
 * > A positive integer object number. Indirect objects may be numbered
 * > sequentially within a PDF file, but this is not required; object numbers
 * > may be assigned in any arbitrary order.
 *
 * > A non-negative integer generation number. In a newly created file, all
 * > indirect objects shall have generation numbers of 0.
 *
 * Both ranges are enforced at construction. An identifier outside them cannot
 * be written as anything a reader would resolve — object number 0 is the head
 * of the free-entry chain of the cross-reference table rather than an object,
 * and a negative number of either kind has no spelling in the grammar at all —
 * so the failure belongs where the wrong number was computed. That the default
 * generation is 0 is the clause's rule for a newly created file, which is every
 * file this library writes; a non-zero generation is what an incremental update
 * (§7.5.6) introduces, and the type carries it because a file being updated
 * refers to such objects.
 *
 * > Together, the combination of an object number and a generation number shall
 * > uniquely identify an indirect object.
 *
 * That sentence is why this is a data class: two identifiers of equal parts are
 * equal, so a caller can use one as a map key to reach the object it names, and
 * cannot end up with two identifiers that name one object yet compare
 * different.
 *
 * As a value, an identifier is an *indirect reference*, which is why this type
 * is a variant of [PdfObject]: "Except where documented to the contrary, any
 * object value may be a direct or an indirect reference; the semantics are
 * equivalent." The *definition* of an indirect object is not a value and is not
 * a variant — it is a framing, written by [writeIndirectObject], and the
 * numbering behind it belongs to the writer.
 *
 * A reference to an object this file never defines is legitimate:
 *
 * > An indirect reference to an undefined object shall not be considered an
 * > error by a PDF processor; it shall be treated as a reference to the null
 * > object.
 *
 * So nothing here knows which objects exist — that knowledge arrives only when
 * a whole file is written, and this type is used long before then.
 */
public data class PdfRef(public val number: Int, public val generation: Int = 0) : PdfObject {
    init {
        require(number > 0) {
            "an object number is a positive integer; $number is not (ISO 32000-2:2020 §7.3.10)"
        }
        require(generation >= 0) {
            "a generation number is a non-negative integer; $generation is not " +
                "(ISO 32000-2:2020 §7.3.10)"
        }
    }
}

/**
 * Writes an indirect reference:
 *
 * > Such indirect references shall consist of the object number, the generation
 * > number, and the keyword R (with white-space separating each part):
 * >
 * >     12 0 R
 *
 * One SPACE separates each part, which is the least white-space the rule
 * allows and what the clause's own example shows. All three parts are needed
 * for the reference to be a reference: nothing but the keyword distinguishes
 * `12 0 R` from the two integer objects `12` and `0` that precede it, so the
 * parts are written together and unconditionally — a generation of 0 is written
 * out like any other.
 *
 * The digits come from [Int.toString], a decimal expansion with no separators
 * and no locale in it on every target, exactly as for an integer object
 * (§7.3.3, [writePdfInt]). Neither part can be negative — [PdfRef] refuses such
 * an identifier — so no sign is ever written.
 */
internal fun Sink.writePdfRef(obj: PdfRef) {
    writeString(obj.number.toString())
    writeByte(SPACE)
    writeString(obj.generation.toString())
    writeByte(SPACE)
    writeByte(KEYWORD_R)
}

/** SPACE (20h), Table 1 of §7.2.3 — the separator between two written tokens. */
private const val SPACE: Byte = 0x20

/** LATIN CAPITAL LETTER R (52h) — the keyword that makes a reference one. */
private const val KEYWORD_R: Byte = 0x52
