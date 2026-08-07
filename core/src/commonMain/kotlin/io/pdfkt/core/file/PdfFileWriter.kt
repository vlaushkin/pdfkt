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
import io.pdfkt.core.obj.PdfObject
import io.pdfkt.core.obj.PdfObjectNumbering
import io.pdfkt.core.obj.PdfRef
import io.pdfkt.core.obj.PdfString
import io.pdfkt.core.obj.writeIndirectObject
import io.pdfkt.core.obj.writePdfObject
import kotlinx.io.Buffer
import kotlinx.io.Sink
import kotlinx.io.writeString

// ISO 32000-2:2020 §7.5.1 "File structure — General", §7.5.2 "File header",
// §7.5.3 "File body", §7.5.4 "Cross-reference table", §7.5.5 "File trailer"

/**
 * Writes one PDF file: the four elements §7.5.1 says a basic conforming file is
 * constructed of.
 *
 * > - A one-line header identifying the version of the PDF specification to
 * >   which the PDF file conforms
 * > - A body containing the objects that make up the document contained in the
 * >   PDF file
 * > - A cross-reference table containing information about the indirect objects
 * >   in the PDF file
 * > - A trailer giving the location of the cross-reference table and of certain
 * >   special objects within the body of the PDF file
 *
 * Three of the four are about *where things are*, which is why they are one
 * class and not four functions: the cross-reference table is the byte offset of
 * every object, the trailer is the byte offset of the cross-reference table, and
 * an offset only means anything relative to a file that starts somewhere. This
 * is the component that knows about offsets, and the only one.
 * Everything below it — [writePdfObject] for the
 * value of an object, [writeIndirectObject] for its framing — writes bytes that
 * do not depend on where they land.
 *
 * Offsets are counted here rather than asked of [sink], because "byte offsets
 * shall be calculated from the PERCENT SIGN (25h)" of the header (§7.5.2): they
 * are relative to the first byte *this writer* produced, and a sink may have had
 * bytes in it beforehand or buffer them afterwards. Every byte of the file
 * passes through one staging buffer, whose size before it is handed on is the
 * offset of what comes next.
 *
 * ## Using it
 *
 * ```
 * val writer = PdfFileWriter(sink)          // the header is now written
 * val pages = writer.reserve()              // referred to before it exists
 * val page = writer.define(PdfDict(mapOf(… PdfName("Parent") to pages …)))
 * writer.define(pages, PdfDict(mapOf(… PdfName("Kids") to PdfArray(listOf(page)) …)))
 * val catalog = writer.define(PdfDict(mapOf(… PdfName("Pages") to pages …)))
 * writer.finish(root = catalog, fileIdentifier = …)
 * ```
 *
 * [reserve] and [define] are separate because a document is a graph and not a
 * tree: a page names its parent and the parent names the page, so whichever is
 * written second was referred to before it was written. Reserving hands out the
 * identifier that makes such a reference writable; defining says where it is.
 *
 * One instance writes one file, is not thread-safe, and is finished exactly
 * once. It does not close [sink] — the caller owns it — but [finish] flushes it,
 * so the last byte of the file has reached the sink when [finish] returns.
 *
 * Incremental updates (§7.5.6), cross-reference streams (§7.5.8) and object
 * streams (§7.5.7) are not written here. Each is a different structure with a
 * different clause; this writer produces the initial structure of §7.5.1, in
 * which every object is a body object and the whole cross-reference table is one
 * section of one subsection.
 */
public class PdfFileWriter(private val sink: Sink) : PdfObjectNumbering {
    /**
     * Where the bytes are assembled before they are handed to [sink].
     *
     * The buffer is emptied into the sink after every piece, so it holds one
     * object at a time and never the file.
     */
    private val staging = Buffer()

    /** How many bytes of the file exist — equivalently, the offset of the next. */
    private var length: Long = 0

    /** The lowest object number not yet handed out; 1 because 0 is not an object. */
    private var nextObjectNumber: Int = 1

    /** The byte offset of the definition of each object defined so far. */
    private val offsets: MutableMap<Int, Long> = mutableMapOf()

    private var finished: Boolean = false

    init {
        writeHeader()
    }

    /**
     * Reserves the identifier of an object of this file, per [PdfObjectNumbering].
     *
     * Numbers are handed out sequentially from 1 with generation 0. The clause
     * requires neither — "object numbers may be assigned in any arbitrary order"
     * — but it requires uniqueness, and a counter is the cheapest thing that
     * gives it. Generation 0 is required: "In a newly created file, all indirect
     * objects shall have generation numbers of 0" (§7.3.10).
     *
     * Sequential numbering also makes the cross-reference table one subsection
     * of entries for a contiguous range (§7.5.4) with nothing missing from it.
     */
    override fun reserve(): PdfRef {
        check(!finished) { FINISHED }
        val number = nextObjectNumber
        check(number != Int.MAX_VALUE) { "this file has run out of object numbers" }
        nextObjectNumber = number + 1
        return PdfRef(number, 0)
    }

    /**
     * Defines the object [id] names: writes its definition into the body and
     * records the byte offset at which the definition begins, which is what the
     * cross-reference table entry for [id] will report — "the byte offset of
     * that object within the body of the PDF file" (§7.5.4).
     *
     * The definition is framed by [writeIndirectObject] and followed by a LINE
     * FEED, so the `endobj` that ends it cannot run into the digits of whatever
     * is written next and make one token of two. §7.5.1 gives the reason for
     * preferring an end-of-line here to a SPACE: "As a matter of convention, the
     * tokens in a PDF file are arranged into lines."
     *
     * @throws IllegalArgumentException if [id] was not reserved by this writer,
     *   if it names an object this writer has already defined, or if its
     *   generation number is not 0.
     */
    public fun define(id: PdfRef, value: PdfObject) {
        check(!finished) { FINISHED }
        require(id.generation == 0) {
            "a newly created file defines every object with generation number 0; " +
                "${id.number} ${id.generation} is an object of an incremental update " +
                "(ISO 32000-2:2020 §7.3.10, §7.5.6)"
        }
        require(id.number < nextObjectNumber) {
            "object number ${id.number} was not reserved by this writer, so nothing keeps it " +
                "from being handed out again (ISO 32000-2:2020 §7.3.10)"
        }
        require(id.number !in offsets) {
            "object ${id.number} is already defined, and an object number shall not have an entry " +
                "in more than one subsection within a single section (ISO 32000-2:2020 §7.5.4)"
        }
        offsets[id.number] = length
        emit {
            writeIndirectObject(id, value)
            writeByte(LINE_FEED)
        }
    }

    /**
     * Reserves an identifier for [value] and defines it there and then, for the
     * common case of an object nothing has referred to yet.
     *
     * @return the identifier, which is how the object is referred to afterwards.
     */
    public fun define(value: PdfObject): PdfRef {
        val id = reserve()
        define(id, value)
        return id
    }

    /**
     * Ends the file: the cross-reference table, the trailer dictionary,
     * `startxref` with the offset of the table, and `%%EOF`.
     *
     * @param root the document catalog (§7.7.2). Table 15 makes it required, and
     *   an indirect reference, which is why it is this parameter and not an
     *   entry a caller might forget.
     * @param info the document information dictionary (§14.3.3), or null for a
     *   file that carries none. Table 15 makes it Optional and, like `/Root`,
     *   requires an indirect reference — so it is an identifier of an object
     *   this writer has defined and not a dictionary written inline.
     * @param fileIdentifier the byte string of the `/ID` array. "Required in PDF
     *   2.0 and later"; "Each PDF file identifier byte-string shall have a
     *   minimum length of 16 bytes". It is a parameter rather than something
     *   computed here because §14.4 derives an identifier from "the current
     *   time", "a string representation of the PDF file's location" and "the
     *   size of the PDF file in bytes" — none of which this layer has, and the
     *   first of which is a clock this project injects rather than reads
     *
     *   Both strings of the array are this one: "When a PDF file is first
     *   written, both identifiers shall be set to the same value", and a file
     *   this writer produces is a file being first written.
     *
     * @throws IllegalArgumentException if [root] or [info] names no object of
     *   this file — a trailer whose `/Root` resolves to the null object is not a
     *   document — or if [fileIdentifier] is shorter than 16 bytes.
     * @throws IllegalStateException if the file is already finished.
     */
    public fun finish(root: PdfRef, fileIdentifier: ByteArray, info: PdfRef? = null) {
        check(!finished) { FINISHED }
        require(root.number in offsets && root.generation == 0) {
            "the trailer's /Root shall be the catalog dictionary of this file, and " +
                "${root.number} ${root.generation} is not an object this file defines " +
                "(ISO 32000-2:2020 §7.5.5, Table 15)"
        }
        require(info == null || (info.number in offsets && info.generation == 0)) {
            "the trailer's /Info shall be an indirect reference to the information dictionary of " +
                "this file, and ${info?.number} ${info?.generation} is not an object this file " +
                "defines (ISO 32000-2:2020 §7.5.5, Table 15)"
        }
        require(fileIdentifier.size >= FILE_IDENTIFIER_MIN_BYTES) {
            "a PDF file identifier byte-string shall have a minimum length of " +
                "$FILE_IDENTIFIER_MIN_BYTES bytes; this one has ${fileIdentifier.size} " +
                "(ISO 32000-2:2020 §7.5.5, Table 15)"
        }

        val size = nextObjectNumber
        val crossReferenceOffset = length
        writeCrossReferenceSection(size)
        writeTrailer(size, root, fileIdentifier, info, crossReferenceOffset)
        finished = true
        sink.flush()
    }

    /**
     * Writes the header (§7.5.2).
     *
     * > The file header shall consist of "%PDF-1.n" or "%PDF-2.n" followed by a
     * > single EOL marker, where 'n' is a single digit number between 0 (30h)
     * > and 9 (39h).
     *
     * The version is not a choice offered to a caller: "A PDF processor that
     * writes a file that conforms to this document shall identify the version …
     * as 2.0". A document needing to declare a later version does so with the
     * `/Version` entry of its catalog (§7.7.2), which is an object and not this
     * writer's business.
     *
     * The header is followed by the comment the clause requires of a file with
     * binary data in it:
     *
     * > If a PDF file contains binary data, as most do (see 7.2, "Lexical
     * > conventions"), the header line shall be immediately followed by a
     * > comment line containing at least four binary characters–that is,
     * > characters whose codes are 128 or greater. This ensures proper behaviour
     * > of file transfer applications that inspect data near the beginning of a
     * > file to determine whether to treat the file's contents as text or as
     * > binary.
     *
     * It is written unconditionally. Whether the file will contain binary data
     * is not known when the header is: a stream of arbitrary bytes may be
     * defined at any point afterwards, and by then the header has been handed to
     * the sink and every offset that follows it fixed. A comment costs six bytes
     * and is ignored by a reader — "any occurrence of the PERCENT SIGN outside a
     * string or stream introduces a comment" — whereas a missing one is a file
     * that arrives corrupted through a transfer that thought it was text.
     *
     * The four bytes are the least byte the sentence admits, four times over.
     * Which values they are carries nothing beyond being 128 or greater.
     */
    private fun writeHeader() {
        emit {
            write(HEADER, 0, HEADER.size)
            write(BINARY_MARKER_COMMENT, 0, BINARY_MARKER_COMMENT.size)
        }
    }

    /**
     * Writes the cross-reference section (§7.5.4): the `xref` keyword, one
     * subsection header, and [size] entries of exactly twenty bytes.
     *
     * > For a PDF file that has never been incrementally updated, the
     * > cross-reference section shall contain only one subsection, whose object
     * > numbering begins at 0.
     *
     * so the subsection header is `0` and the entry count, "two integers
     * separated by a SPACE (20h) and terminated by an end-of-line marker", and
     * the entries run over a contiguous range from object 0 upwards:
     *
     * > The cross-reference table … shall contain one entry for each object
     * > number from 0 to the maximum object number defined in the PDF file, even
     * > if one or more of the object numbers in this range do not actually occur
     * > in the PDF file.
     *
     * Object 0 is free, and so is every number that was reserved and never
     * defined — a legitimate outcome, since "An indirect reference to an
     * undefined object shall not be considered an error by a PDF processor; it
     * shall be treated as a reference to the null object" (§7.3.10). Their
     * entries link back to object 0 with generation 65535, which the clause
     * allows outright:
     *
     * > Using the second mechanism, the table may contain other free entries
     * > that link back to object number 0 and have a generation number of
     * > 65,535, even though these entries are not in the linked list itself.
     *
     * The head entry of object 0 links back to itself, which is what a linked
     * list of free objects containing none looks like: "The last free entry (the
     * tail of the linked list) links back to object number 0."
     *
     * No comment is written anywhere in here: "PDF comments shall not be
     * included in a cross-reference table between the keywords xref and
     * trailer."
     */
    private fun writeCrossReferenceSection(size: Int) {
        emit {
            write(XREF_KEYWORD, 0, XREF_KEYWORD.size)
            writeByte(LINE_FEED)
            writeString(FIRST_OBJECT_NUMBER.toString())
            writeByte(SPACE)
            writeString(size.toString())
            writeByte(LINE_FEED)
        }
        for (number in FIRST_OBJECT_NUMBER until size) {
            val offset = offsets[number]
            emit {
                if (offset == null) {
                    writeCrossReferenceEntry(HEAD_OF_FREE_LIST.toLong(), FREE_GENERATION, FREE)
                } else {
                    writeCrossReferenceEntry(offset, 0, IN_USE)
                }
            }
        }
    }

    /**
     * Writes the trailer (§7.5.5), which is the whole of the rest of the file:
     *
     * > trailer
     * >    <<key1 value1
     * >       …
     * >    >>
     * > startxref
     * > Byte_offset_of_last_cross-reference_section
     * > %%EOF
     *
     * > The last line of the file shall contain only the end-of-file marker,
     * > %%EOF. The two preceding lines shall contain, one per line and in order,
     * > the keyword startxref and the byte offset in the decoded stream from the
     * > beginning of the PDF file to the beginning of the xref keyword in the
     * > last cross-reference section.
     *
     * Of Table 15 three entries are written, which is every entry required of a
     * file that is not encrypted and has never been updated:
     *
     * - `/Size`, "The total number of entries in the PDF file's cross-reference
     *   table" — [size], the count this writer just wrote entries for, which is
     *   also "1 greater than the highest object number defined in the PDF file"
     *   whenever the highest number was in fact defined. It is not an indirect
     *   reference, as the entry requires;
     * - `/Root`, the catalog, as an indirect reference;
     * - `/ID`, the two byte strings of §14.4, written in hexadecimal — a form
     *   every byte survives unaltered, which matters for a value whose content
     *   is a digest rather than text.
     *
     * A fourth, `/Info`, is written when the caller supplied one. The entry is
     * Optional and carries a warning — "this method for specifying document
     * metadata has been deprecated in PDF 2.0 and should therefore only be used
     * to encode information that is stated as required elsewhere in this
     * document" — but the deprecation is of the dictionary's *other* entries:
     * §14.3.3 keeps two, "In PDF 2.0 such use is deprecated except for two
     * entries, CreationDate and ModDate". A file that says when it was made says
     * it here.
     *
     * `/Prev` is absent: it is "present only if the file has more than one
     * cross-reference section", and this file has one. `/Encrypt` is absent
     * because nothing here encrypts.
     */
    private fun writeTrailer(
        size: Int,
        root: PdfRef,
        fileIdentifier: ByteArray,
        info: PdfRef?,
        crossReferenceOffset: Long,
    ) {
        val identifier = PdfString(fileIdentifier, hex = true)
        val entries =
            mutableMapOf<PdfName, PdfObject>(
                PdfName("Size") to PdfInt(size.toLong()),
                PdfName("Root") to root,
                PdfName("ID") to PdfArray(listOf(identifier, identifier)),
            )
        if (info != null) entries[PdfName("Info")] = info
        val trailer = PdfDict(entries)
        emit {
            write(TRAILER_KEYWORD, 0, TRAILER_KEYWORD.size)
            writeByte(LINE_FEED)
            writePdfObject(trailer)
            writeByte(LINE_FEED)
            write(STARTXREF_KEYWORD, 0, STARTXREF_KEYWORD.size)
            writeByte(LINE_FEED)
            writeString(crossReferenceOffset.toString())
            writeByte(LINE_FEED)
            write(END_OF_FILE_MARKER, 0, END_OF_FILE_MARKER.size)
            writeByte(LINE_FEED)
        }
    }

    /**
     * Assembles a piece of the file and hands it to [sink], counting it.
     *
     * The count is taken from the staging buffer rather than from the bytes any
     * caller thinks it wrote, so an offset cannot drift from the file: whatever
     * [block] put in the buffer is what goes out and what is counted.
     */
    private fun emit(block: Buffer.() -> Unit) {
        staging.block()
        val piece = staging.size
        sink.write(staging, piece)
        length += piece
    }

    private companion object {
        const val FINISHED: String = "this file is finished; a writer writes one file (§7.5.1)"

        /**
         * "The first entry in the table (object number 0) shall always be free",
         * which is why a subsection of a file that has never been updated begins
         * here and why no object may carry this number ([PdfRef] refuses it).
         */
        const val FIRST_OBJECT_NUMBER: Int = 0

        /** The object number a free entry outside the linked list points at. */
        const val HEAD_OF_FREE_LIST: Int = 0

        /**
         * "The first entry in the table (object number 0) … shall have a
         * generation number of 65,535"; the same value marks the free entries of
         * the second mechanism.
         */
        const val FREE_GENERATION: Int = 65535

        /** Table 15 — "Each PDF file identifier byte-string". */
        const val FILE_IDENTIFIER_MIN_BYTES: Int = 16
    }
}

/**
 * Writes one cross-reference entry (§7.5.4).
 *
 * > Each entry shall be exactly 20 bytes long, including the end-of-line marker.
 *
 * ```
 * nnnnnnnnnn ggggg n eol
 * ```
 *
 * The two fields are fixed-width decimal, "padded with leading zeros if
 * necessary", separated by single SPACEs, and followed by the keyword and a
 * "2-character end-of-line sequence consisting of one of the following: SP CR,
 * SP LF, or CR LF". SP LF is written: the first two spell the end of a line with
 * a byte to spare, and of those two the one ending in LINE FEED leaves no
 * CARRIAGE RETURN as the last byte of a line, matching the end-of-line this
 * writer uses everywhere else.
 *
 * 10 + 1 + 5 + 1 + 1 + 2 is the 20 bytes the clause fixes, which is what lets a
 * reader compute where an entry is instead of scanning for it — "the only part
 * of a PDF file with a fixed format, which permits entries in the table to be
 * accessed randomly".
 *
 * [firstField] is the byte offset of the object for an in-use entry and the
 * object number of the next free object for a free one; the two kinds "have
 * similar basic formats, distinguished by the keyword n … or f", and the
 * difference in meaning is the caller's.
 */
private fun Buffer.writeCrossReferenceEntry(firstField: Long, generation: Int, keyword: Byte) {
    require(firstField <= MAX_FIRST_FIELD) {
        "a cross-reference entry holds a 10-digit number and $firstField has more digits; " +
            "the file has outgrown a cross-reference table (ISO 32000-2:2020 §7.5.4)"
    }
    writeString(firstField.toString().padStart(FIRST_FIELD_DIGITS, '0'))
    writeByte(SPACE)
    writeString(generation.toString().padStart(GENERATION_DIGITS, '0'))
    writeByte(SPACE)
    writeByte(keyword)
    writeByte(SPACE)
    writeByte(LINE_FEED)
}

/** "%PDF-2.0" (25h 50h 44h 46h 2Dh 32h 2Eh 30h) and the single EOL marker after it. */
private val HEADER = byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x2D, 0x32, 0x2E, 0x30, 0x0A)

/** A comment (25h) of four bytes of value 128, and the end of its line. */
private val BINARY_MARKER_COMMENT =
    byteArrayOf(0x25, 0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x0A)

private val XREF_KEYWORD = byteArrayOf(0x78, 0x72, 0x65, 0x66) // xref
private val TRAILER_KEYWORD = byteArrayOf(0x74, 0x72, 0x61, 0x69, 0x6C, 0x65, 0x72) // trailer
private val STARTXREF_KEYWORD =
    byteArrayOf(0x73, 0x74, 0x61, 0x72, 0x74, 0x78, 0x72, 0x65, 0x66) // startxref
private val END_OF_FILE_MARKER = byteArrayOf(0x25, 0x25, 0x45, 0x4F, 0x46) // %%EOF

/** "n shall be a keyword identifying this as an in-use entry." */
private const val IN_USE: Byte = 0x6E

/** "f shall be a keyword identifying this as a free entry." */
private const val FREE: Byte = 0x66

/** "nnnnnnnnnn shall be a 10-digit byte offset in the decoded stream". */
private const val FIRST_FIELD_DIGITS: Int = 10

/** "ggggg shall be a 5-digit generation number". */
private const val GENERATION_DIGITS: Int = 5

/** The largest number ten decimal digits hold. */
private const val MAX_FIRST_FIELD: Long = 9_999_999_999

/** SPACE (20h), Table 1 of §7.2.3 — the separator between two written tokens. */
private const val SPACE: Byte = 0x20

/** LINE FEED (0Ah), Table 1 of §7.2.3 — an end-of-line marker on its own. */
private const val LINE_FEED: Byte = 0x0A
