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

// ISO 32000-2:2020 §7.3.7 "Dictionary objects"

/**
 * A dictionary object — "an associative table containing pairs of objects,
 * known as the dictionary's entries".
 *
 * Three rules of the clause are structural, and are carried by the type rather
 * than left to the writer:
 *
 * - "The key shall be a name … All keys shall be direct objects." The key type
 *   is [PdfName], which is both a name and, being a value, direct.
 * - "Multiple entries in the same dictionary shall not have the same key." A
 *   [Map] cannot hold two entries under one key, and [PdfName] compares by the
 *   character sequence the name denotes, which is the identity §7.3.5 gives a
 *   name object.
 * - "A dictionary entry whose value is null … shall be treated the same as if
 *   the entry does not exist." Such an entry is dropped here, at construction,
 *   so that the two things the clause calls the same are the same object and
 *   not merely the same bytes. A caller that assembles a dictionary with
 *   optional entries writes [PdfNull] for the ones it has nothing to say
 *   about, and what it gets back from [entries] is what the file will say.
 *
 * The remaining rule — "The entries in a dictionary … shall be unordered even
 * though an arbitrary order may be imposed upon them when written in a file.
 * That ordering shall be ignored" — is why [entries] is a [Map] and not a list
 * of pairs. The map's iteration order is the arbitrary order this dictionary
 * happens to be written in; it carries no meaning, and copying the map fixes
 * it so that one object is always written the same way.
 *
 * @throws IllegalArgumentException if a value is a [PdfStream]. "All streams
 *   shall be indirect objects" (§7.3.8.1), and this clause says how one is
 *   reached from a dictionary: "An indirect reference shall be used as the
 *   value to refer to a stream object" — so the value is a [PdfRef].
 */
public class PdfDict(entries: Map<PdfName, PdfObject>) : PdfObject {
    public val entries: Map<PdfName, PdfObject> = entries.filterValues { it != PdfNull }

    init {
        for ((key, value) in this.entries) {
            require(value !is PdfStream) {
                "the value of /${key.name} refers to a stream object by an indirect reference, " +
                    "not by containing it (ISO 32000-2:2020 §7.3.7, §7.3.8.1)"
            }
        }
    }
}

/**
 * Writes a dictionary object: "a sequence of key-value pairs enclosed in
 * double angle brackets (<<…>>) (using LESS-THAN SIGNs (3Ch) and GREATER-THAN
 * SIGNs (3Eh))".
 *
 * The key is written through [writePdfObject] as well, so the solidus and the
 * escaping of §7.3.5 are applied to keys by the same code that applies them to
 * names appearing as values. Values recurse likewise, which is what makes a
 * value that is "any kind of object, including another dictionary" cost
 * nothing here.
 *
 * A single SPACE separates a key from its value and one pair from the next —
 * the separation the clause's own example shows, laid out on one line. That
 * the example puts each pair on a line of its own is not a requirement:
 * "the examples' use of white-space for indentation is purely for clarity of
 * exposition and need not be included in practical use" (§7.2.3, NOTE 2).
 *
 * Entries whose value is the null object are not written, because [PdfDict]
 * does not hold them: this writer has no case for the rule, which is the point
 * of enforcing it in the type.
 */
internal fun Sink.writePdfDict(obj: PdfDict) {
    writeByte(LESS_THAN)
    writeByte(LESS_THAN)
    var first = true
    for ((key, value) in obj.entries) {
        if (!first) writeByte(SPACE)
        first = false
        writePdfObject(key)
        writeByte(SPACE)
        writePdfObject(value)
    }
    writeByte(GREATER_THAN)
    writeByte(GREATER_THAN)
}

private const val LESS_THAN: Byte = 0x3C
private const val GREATER_THAN: Byte = 0x3E

/** SPACE (20h), Table 1 of §7.2.3 — the separator between two written tokens. */
private const val SPACE: Byte = 0x20
