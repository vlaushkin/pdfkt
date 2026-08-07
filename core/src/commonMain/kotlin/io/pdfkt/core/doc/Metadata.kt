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
import io.pdfkt.core.obj.PdfObject
import io.pdfkt.core.obj.pdfTextString

// ISO 32000-2:2020 §14.3.3 "Document information dictionary" (Table 349),
// §7.9.2.2 "Text string type", §7.9.4 "Dates"

/**
 * What a document says about itself: the entries of Table 349 that a caller
 * supplies, without the two a clock supplies.
 *
 * The dates are deliberately absent. `/CreationDate` and `/ModDate` are not
 * something a caller states about a document but something that is true of it
 * when it is written, and they come from [DocumentSpec.clock] — which is the
 * whole of the "real clock, injected" policy expressed in a type: there is no
 * field here for a caller to hardcode a date into.
 *
 * ## On writing entries the clause deprecates
 *
 * §14.3.3 is explicit:
 *
 * > Earlier versions of the PDF file format used the document information
 * > dictionary to represent document level metadata. In PDF 2.0 such use is
 * > deprecated except for two entries, CreationDate and ModDate. For any other
 * > document level metadata, a metadata stream (see 14.3.2 "Metadata streams")
 * > should be used instead.
 *
 * They are written anyway, and this is the reasoning rather than an oversight: a
 * title that no reader displays is worse than a deprecated entry that every
 * reader displays, and a metadata stream is XMP — a second document format,
 * carried in the file, which this library does not yet write. When it does, this
 * type is what it will be built from as well, and the deprecated entries can go.
 * Nothing here is required to be present, so a caller who wants none of it
 * passes [Metadata] with nothing set and gets an information dictionary of the
 * two date entries the clause does not deprecate.
 *
 * @property title "The document's title" — the entry a reader puts in its window
 *   title bar.
 * @property author "The name of the person who created the document."
 * @property subject "The subject of the document."
 * @property keywords "Keywords associated with the document", which Table 349
 *   types as one text string; they are held as a list because that is what they
 *   are, and joined with ", " when written.
 * @property creator "If the document was converted to PDF from another format,
 *   the name of the PDF processor that created the original document from which
 *   it was converted" — so it is the caller's application, not this library.
 * @property producer "the name of the PDF processor that converted it to PDF",
 *   which *is* this library, and so is defaulted to [PRODUCER] rather than left
 *   to a caller to get wrong.
 */
public data class Metadata(
    public val title: String? = null,
    public val author: String? = null,
    public val subject: String? = null,
    public val keywords: List<String> = emptyList(),
    public val creator: String? = null,
    public val producer: String? = PRODUCER,
) {
    public companion object {
        /** This library, as the `/Producer` of every file it writes. */
        public const val PRODUCER: String = "pdfkt"

        /** What Table 349's one-string `/Keywords` puts between two keywords. */
        public const val KEYWORD_SEPARATOR: String = ", "
    }
}

/**
 * The document information dictionary of §14.3.3: [metadata] as text strings,
 * and the two date entries.
 *
 * Every entry of Table 349 is Optional and only the ones with something to say
 * are written — an absent `/Title` and a `/Title` of an empty string are
 * different claims, and a caller who set nothing has made neither. `/Trapped` is
 * not written at all: its default is `Unknown`, which is exactly what this
 * library knows about trapping.
 *
 * The dates are the same instant when a file is first written, which is what
 * [modificationDate] defaults to. Both are "in human-readable form (see 7.9.4,
 * 'Dates')" — the form [PdfDate.toPdfString] produces, and the entry the clause
 * kept when it deprecated the rest of this dictionary.
 *
 * The result is a plain dictionary rather than something written straight into a
 * file: Table 15 requires `/Info` to "be an indirect reference", and which
 * object number it gets belongs to whoever is writing the file.
 */
public fun documentInformation(
    metadata: Metadata,
    creationDate: PdfDate,
    modificationDate: PdfDate = creationDate,
): PdfDict {
    val entries = mutableMapOf<PdfName, PdfObject>()
    entries.putText("Title", metadata.title)
    entries.putText("Author", metadata.author)
    entries.putText("Subject", metadata.subject)
    entries.putText("Keywords", metadata.keywords.joinToString(Metadata.KEYWORD_SEPARATOR).ifEmpty { null })
    entries.putText("Creator", metadata.creator)
    entries.putText("Producer", metadata.producer)
    entries[PdfName("CreationDate")] = creationDate.toPdfString()
    entries[PdfName("ModDate")] = modificationDate.toPdfString()
    return PdfDict(entries)
}

/**
 * Writes [value] under [key] as a text string, or nothing at all if there is
 * nothing to write.
 *
 * "Where a document information dictionary contains keys other than
 * CreationDate and ModDate, the value associated with any such key shall be a
 * text string" (§14.3.3) — which is [pdfTextString] and its two encodings, so a
 * Cyrillic title is a title and not a row of question marks.
 */
private fun MutableMap<PdfName, PdfObject>.putText(key: String, value: String?) {
    if (value.isNullOrEmpty()) return
    this[PdfName(key)] = pdfTextString(value)
}
