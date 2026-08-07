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

// ISO 32000-2:2020 §7.3.6 "Array objects"

/**
 * An array object — "a one-dimensional collection of objects arranged
 * sequentially".
 *
 * Heterogeneous, as the clause insists: "an array's elements may be any
 * combination of numbers, strings, dictionaries, or any other objects,
 * including other arrays", and "An array may have zero elements". Only one
 * dimension exists in the syntax; higher ones "can be constructed by using
 * arrays as elements of arrays, nested to any depth", which is the same
 * [PdfObject] recursion and needs nothing of its own here.
 *
 * [items] is copied, so an array is the sequence it was built from however the
 * caller treats the list afterwards. Without that, what the file says would
 * depend on when it was written rather than on which object was written.
 *
 * @throws IllegalArgumentException if an element is a [PdfStream]. "All
 *   streams shall be indirect objects" (§7.3.8.1), and this clause says how
 *   one is reached from an array: "An indirect reference shall be used to
 *   refer to a stream object" — so the element is a [PdfRef], and a stream
 *   placed here directly is a document that no reader is required to accept.
 */
public class PdfArray(items: List<PdfObject>) : PdfObject {
    public val items: List<PdfObject> = items.toList()

    init {
        for (item in this.items) {
            require(item !is PdfStream) {
                "an array refers to a stream object by an indirect reference, not by containing it " +
                    "(ISO 32000-2:2020 §7.3.6, §7.3.8.1)"
            }
        }
    }
}

/**
 * Writes an array object: "a sequence of objects enclosed in SQUARE BRACKETS
 * (using LEFT SQUARE BRACKET (5Bh) and RIGHT SQUARE BRACKET (5Dh))".
 *
 * Every element goes back through [writePdfObject]; the recursion has exactly
 * one entry point, so a variant can never acquire a second serialization that
 * applies only inside containers. Nesting therefore costs nothing: an array
 * element that is itself an array is written by this same function.
 *
 * Between two elements the clause's example — `[549 3.14 false (Ralph)
 * /SomeName]` — writes a single SPACE, and that is what is written here.
 * White-space is what "separat[es] syntactic constructs such as names and
 * numbers from each other" (§7.2.3), and two of the five elements of that
 * example, `549` and `3.14`, are tokens that would merge into one number
 * without it. A separator is emitted unconditionally rather than only where
 * the neighbouring tokens would merge: the cases where it can be dropped are
 * exactly the cases where a delimiter already ends the token, and a rule that
 * has to know which those are buys one byte per element for the risk of being
 * wrong about one of them.
 *
 * Nothing surrounds the brackets. An array is written where an object is
 * expected, and separating it from what comes before or after belongs to
 * whatever is doing the enclosing — the same division that leaves the keyword
 * writers unpadded.
 */
internal fun Sink.writePdfArray(obj: PdfArray) {
    writeByte(LEFT_SQUARE_BRACKET)
    obj.items.forEachIndexed { index, item ->
        if (index > 0) writeByte(SPACE)
        writePdfObject(item)
    }
    writeByte(RIGHT_SQUARE_BRACKET)
}

private const val LEFT_SQUARE_BRACKET: Byte = 0x5B
private const val RIGHT_SQUARE_BRACKET: Byte = 0x5D

/** SPACE (20h), Table 1 of §7.2.3 — the separator between two written tokens. */
private const val SPACE: Byte = 0x20
