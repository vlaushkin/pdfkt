/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.obj

// ISO 32000-2:2020 §7.3.10 "Indirect objects"

/**
 * The source of object identifiers for one file.
 *
 * Numbering is not a property of an object — the same dictionary written into
 * two files is a different indirect object in each — so it cannot live on
 * [PdfObject], and it is not a property of the framing either, since
 * [writeIndirectObject] frames whatever identifier it is handed. It is a
 * property of the file being written, which is why the writer owns it:
 * the writer is what exists once per file, and
 * what already walks the objects in order to record their byte offsets.
 *
 * This interface is the seam between the two. What the clause requires of an
 * implementation is all of the following, and nothing else:
 *
 * - every identifier it returns is a valid one — a positive object number and a
 *   non-negative generation number, which [PdfRef] enforces on its own;
 * - "Together, the combination of an object number and a generation number
 *   shall uniquely identify an indirect object": no identifier is returned
 *   twice for two different objects, and the identifier an object was given is
 *   the identifier every reference to it carries;
 * - "In a newly created file, all indirect objects shall have generation
 *   numbers of 0", so a writer producing a new file returns generation 0
 *   throughout. Non-zero generations belong to incremental updates (§7.5.6).
 *
 * What the clause does *not* require is any particular order: "Indirect objects
 * may be numbered sequentially within a PDF file, but this is not required;
 * object numbers may be assigned in any arbitrary order." An implementation is
 * free to number in the order objects are written, which is the simplest thing
 * that satisfies the above and makes the cross-reference table one contiguous
 * subsection (§7.5.4).
 *
 * `io.pdfkt.core.file.PdfFileWriter` implements this, alongside the header, the
 * body, the cross-reference table and the trailer (§7.5.2–§7.5.5). There is
 * deliberately no implementation in this package: an object number is only
 * unique relative to the file that hands it out, and this layer knows nothing
 * about files.
 */
public interface PdfObjectNumbering {
    /**
     * Reserves an identifier for an object of this file.
     *
     * Reserving is separate from defining because the two happen in either
     * order: a page dictionary refers to its content stream before that stream
     * has been written, and a stream may declare its `/Length` as a reference
     * to an object that follows it (§7.3.10, Example 3). What comes back is
     * usable as a value — an indirect reference — from the moment it is
     * returned, whether or not the object it names has been framed yet.
     */
    public fun reserve(): PdfRef
}
