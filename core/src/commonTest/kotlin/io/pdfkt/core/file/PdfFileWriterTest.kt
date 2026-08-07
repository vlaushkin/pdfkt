/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.file

import io.pdfkt.core.obj.PdfDict
import io.pdfkt.core.obj.PdfInt
import io.pdfkt.core.obj.PdfName
import io.pdfkt.core.obj.PdfRef
import kotlinx.io.Buffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

// ISO 32000-2:2020 §7.5.2 "File header", §7.5.3 "File body",
// §7.5.4 "Cross-reference table", §7.5.5 "File trailer"

/**
 * The four elements of a file, asserted against the clauses that fix their
 * bytes, over the smallest document there is ([writeMinimalDocument]).
 *
 * The assertions are made the way a reader reads: from `startxref` to the
 * cross-reference section, from an entry to the object it points at. What is
 * being checked is not that certain bytes appear somewhere but that the offsets
 * are right, since an offset that is wrong by one is a file that still contains
 * every object and no longer opens.
 */
class PdfFileWriterTest {
    /**
     * > The file header shall consist of "%PDF-1.n" or "%PDF-2.n" followed by a
     * > single EOL marker
     *
     * > If a PDF file contains binary data, as most do …, the header line shall
     * > be immediately followed by a comment line containing at least four
     * > binary characters–that is, characters whose codes are 128 or greater.
     *
     * The version is 2.0 because "A PDF processor that writes a file that
     * conforms to this document shall identify the version … as 2.0", and the
     * PERCENT SIGN it opens with is byte 0 — "byte offsets shall be calculated
     * from the PERCENT SIGN (25h)", so every offset in the file is relative to
     * this byte.
     */
    @Test
    fun `the file begins with the version header and a binary marker comment`() {
        val file = pdfFile(::writeMinimalDocument)

        assertEquals("%PDF-2.0", file.textAt(0, 8), "the header is not the one §7.5.2 fixes")
        assertEquals(LINE_FEED, file[8], "the header is not followed by a single EOL marker")
        assertEquals(PERCENT_SIGN, file[9], "no comment line immediately follows the header line")
        for (index in 10..13) {
            val code = file[index].toInt() and 0xFF
            assertTrue(code >= 128, "byte $index of the comment is $code, which is not binary")
        }
        assertEquals(LINE_FEED, file[14], "the comment line does not end")
    }

    /**
     * > Each cross-reference section shall begin with a line containing the
     * > keyword xref.
     *
     * > The subsection shall begin with a line containing only two integers
     * > separated by a SPACE (20h) and terminated by an end-of-line marker …
     * > The two integers denote (respectively) the object number of the first
     * > object in this subsection and the number of entries in the subsection.
     *
     * The section is found where `startxref` says it is and nowhere else: "The
     * two preceding lines shall contain, one per line and in order, the keyword
     * startxref and the byte offset … to the beginning of the xref keyword in
     * the last cross-reference section."
     *
     * The document defines three objects, so the subsection covers four numbers
     * — object 0 and the three — because "the cross-reference section shall
     * contain only one subsection, whose object numbering begins at 0" and the
     * table "shall contain one entry for each object number from 0 to the
     * maximum object number defined in the PDF file".
     */
    @Test
    fun `startxref gives the offset of the one subsection of the one section`() {
        val file = WrittenFile(pdfFile(::writeMinimalDocument))

        assertEquals(
            "xref",
            file.bytes.textAt(file.startCrossReference, 4),
            "startxref does not point at the xref keyword",
        )
        assertEquals("xref", file.crossReferenceKeyword)
        assertEquals("0 4", file.subsectionHeader)
    }

    /**
     * > Each entry shall be exactly 20 bytes long, including the end-of-line
     * > marker.
     *
     * ```
     * nnnnnnnnnn ggggg n eol
     * ```
     *
     * The region between the subsection header and the `trailer` keyword holds
     * the entries and nothing else, so its size is the count times twenty — the
     * property that makes the table "the only part of a PDF file with a fixed
     * format, which permits entries in the table to be accessed randomly".
     */
    @Test
    fun `every cross reference entry is exactly twenty bytes in the format of the clause`() {
        val file = WrittenFile(pdfFile(::writeMinimalDocument))

        assertEquals(
            4 * ENTRY_BYTES,
            file.entryBytes.size,
            "the entries do not occupy exactly twenty bytes each",
        )
        for (index in 0 until file.entryCount) {
            val entry = file.entry(index)
            assertTrue(
                CROSS_REFERENCE_ENTRY.matches(entry),
                "entry $index is not of the form the clause fixes: <$entry>",
            )
        }
    }

    /**
     * > The first entry in the table (object number 0) shall always be free and
     * > shall have a generation number of 65,535; this entry shall be the head
     * > of the linked list of free objects. The last free entry (the tail of the
     * > linked list) links back to object number 0.
     *
     * No object of this file has been deleted, so the list is empty and its head
     * is its tail: the entry links back to object number 0, which is itself.
     */
    @Test
    fun `the entry for object zero is the free head of an empty list`() {
        val file = WrittenFile(pdfFile(::writeMinimalDocument))

        assertEquals("0000000000 65535 f \n", file.entry(0))
    }

    /**
     * > nnnnnnnnnn shall be a 10-digit byte offset in the decoded stream …
     * > giving the number of bytes from the beginning of the PDF file to the
     * > beginning of the object.
     *
     * Every in-use entry is followed to where it points, and what is found there
     * is the beginning of the definition of exactly that object — its object
     * number, its generation number and the keyword `obj` (§7.3.10). Nothing
     * else in this test knows where the objects are.
     */
    @Test
    fun `an in use entry gives the offset at which that object definition begins`() {
        val file = WrittenFile(pdfFile(::writeMinimalDocument))

        for (number in 1 until file.entryCount) {
            val entry = file.entry(number)
            assertTrue(entry.endsWith("n \n"), "object $number is not in use: <$entry>")
            val offset = entry.substring(0, 10).toInt()
            val definition = "$number 0 obj"
            assertEquals(
                definition,
                file.bytes.textAt(offset, definition.length),
                "the entry for object $number points at byte $offset, which is not its definition",
            )
        }
    }

    /**
     * An object number is not a position in the file. The page tree node is
     * reserved first and defined third, so object 1 sits in the body *after*
     * object 2 — and the table still finds both, because an entry is an offset
     * and not an ordinal.
     *
     * This is the case the split between [PdfFileWriter.reserve] and
     * [PdfFileWriter.define] exists for: "Indirect objects may be numbered
     * sequentially within a PDF file, but this is not required" (§7.3.10), and a
     * page and its parent refer to each other, so one of the two is always
     * referred to before it is written.
     */
    @Test
    fun `an object defined after one with a higher number is still found by its offset`() {
        val file = WrittenFile(pdfFile(::writeMinimalDocument))

        val pages = file.entry(PAGES.number).substring(0, 10).toInt()
        val page = file.entry(PAGE.number).substring(0, 10).toInt()
        val catalog = file.entry(CATALOG.number).substring(0, 10).toInt()
        assertTrue(page < pages, "the body is not in the order the document was defined in")
        assertTrue(pages < catalog, "the body is not in the order the document was defined in")

        assertTrue(
            file.bytes
                .textAt(page, catalog - page)
                .contains("/Parent ${PAGES.number} 0 R"),
            "the page does not name the parent whose number was reserved before it",
        )
    }

    /**
     * > Size … (Required; shall not be an indirect reference) The total number
     * > of entries in the PDF file's cross-reference table … Equivalently, this
     * > value shall be 1 greater than the highest object number defined in the
     * > PDF file.
     *
     * > Root … (Required; shall be an indirect reference) The catalog dictionary
     * > for the PDF file
     *
     * > ID … (Required in PDF 2.0 and later …) An array of two byte-strings
     * > constituting a PDF file identifier … When a PDF file is first written,
     * > both identifiers shall be set to the same value (§14.4).
     *
     * `/Prev` is absent, which is how the clause's own example describes "a PDF
     * file that has never been updated".
     */
    @Test
    fun `the trailer dictionary names the catalog the table size and the file identifier`() {
        val file = WrittenFile(pdfFile(::writeMinimalDocument))

        assertEquals(
            "<</Size 4 /Root ${CATALOG.number} 0 R " +
                "/ID [<$TEST_FILE_IDENTIFIER_HEX> <$TEST_FILE_IDENTIFIER_HEX>]>>",
            file.trailerDictionary,
        )
    }

    /**
     * > The last line of the file shall contain only the end-of-file marker,
     * > %%EOF.
     */
    @Test
    fun `the last line of the file is the end of file marker`() {
        val file = WrittenFile(pdfFile(::writeMinimalDocument))

        assertEquals("%%EOF\n", file.afterStartCrossReference)
    }

    /**
     * A number that was reserved and never defined names no object, and "An
     * indirect reference to an undefined object shall not be considered an error
     * by a PDF processor; it shall be treated as a reference to the null object"
     * (§7.3.10). The table still has to cover it — "one entry for each object
     * number from 0 to the maximum object number defined in the PDF file, even
     * if one or more of the object numbers in this range do not actually occur"
     * — and the entry it gets is a free one of the second mechanism:
     *
     * > the table may contain other free entries that link back to object number
     * > 0 and have a generation number of 65,535, even though these entries are
     * > not in the linked list itself.
     */
    @Test
    fun `a number that was reserved and never defined gets a free entry`() {
        val file =
            WrittenFile(
                pdfFile { writer ->
                    val undefined = writer.reserve()
                    val catalog =
                        writer.define(
                            PdfDict(
                                mapOf(
                                    PdfName("Type") to PdfName("Catalog"),
                                    PdfName("Pages") to undefined,
                                ),
                            ),
                        )
                    writer.finish(root = catalog, fileIdentifier = TEST_FILE_IDENTIFIER)
                },
            )

        assertEquals("0 3", file.subsectionHeader)
        assertEquals("0000000000 65535 f \n", file.entry(1))
        assertTrue(file.entry(2).endsWith("n \n"), "the catalog is not in use: <${file.entry(2)}>")
        assertTrue(file.trailerDictionary.startsWith("<</Size 3 "), file.trailerDictionary)
    }

    /**
     * Numbering is the writer's, and it hands each number out once: "Together,
     * the combination of an object number and a generation number shall uniquely
     * identify an indirect object" (§7.3.10). An identifier from anywhere else
     * carries no such guarantee, so it is refused rather than written into a
     * table where two objects could end up sharing a number.
     */
    @Test
    fun `an identifier this writer never handed out is refused`() {
        val writer = PdfFileWriter(Buffer())

        assertFailsWith<IllegalArgumentException> {
            writer.define(PdfRef(7), PdfInt(1))
        }
    }

    /**
     * > A given object number shall not have an entry in more than one
     * > subsection within a single section.
     *
     * One entry per object is one offset per object, so defining an object twice
     * has no spelling in the table: whichever offset were recorded, one of the
     * two definitions would be unreachable.
     */
    @Test
    fun `defining the same object twice is refused`() {
        val writer = PdfFileWriter(Buffer())
        val id = writer.reserve()
        writer.define(id, PdfInt(1))

        assertFailsWith<IllegalArgumentException> {
            writer.define(id, PdfInt(2))
        }
    }

    /**
     * > In a newly created file, all indirect objects shall have generation
     * > numbers of 0.
     *
     * A non-zero generation belongs to an incremental update (§7.5.6), which
     * this writer does not produce.
     */
    @Test
    fun `a generation number other than zero is refused`() {
        val writer = PdfFileWriter(Buffer())
        writer.reserve()

        assertFailsWith<IllegalArgumentException> {
            writer.define(PdfRef(1, 1), PdfInt(1))
        }
    }

    /**
     * The trailer's `/Root` "shall be an indirect reference" to "The catalog
     * dictionary for the PDF file". A reference to an object the file does not
     * define resolves to the null object (§7.3.10) — legitimate as a value
     * anywhere else, and not a document here.
     */
    @Test
    fun `a trailer root that this file never defined is refused`() {
        val writer = PdfFileWriter(Buffer())
        writer.define(PdfInt(1))

        assertFailsWith<IllegalArgumentException> {
            writer.finish(root = PdfRef(2), fileIdentifier = TEST_FILE_IDENTIFIER)
        }
    }

    /**
     * > Each PDF file identifier byte-string shall have a minimum length of 16
     * > bytes.
     */
    @Test
    fun `a file identifier shorter than sixteen bytes is refused`() {
        val writer = PdfFileWriter(Buffer())
        val catalog = writer.define(PdfInt(1))

        assertFailsWith<IllegalArgumentException> {
            writer.finish(root = catalog, fileIdentifier = ByteArray(15))
        }
    }

    /**
     * A writer writes one file. After `%%EOF` there is nowhere left to put an
     * object: what follows the end-of-file marker of a file that has been added
     * to is a second cross-reference section and a second trailer, which is an
     * incremental update (§7.5.6) and not this.
     */
    @Test
    fun `a finished file accepts nothing further`() {
        val writer = PdfFileWriter(Buffer())
        val catalog = writer.define(PdfInt(1))
        writer.finish(root = catalog, fileIdentifier = TEST_FILE_IDENTIFIER)

        assertFailsWith<IllegalStateException> { writer.define(PdfInt(2)) }
        assertFailsWith<IllegalStateException> { writer.reserve() }
        assertFailsWith<IllegalStateException> {
            writer.finish(root = catalog, fileIdentifier = TEST_FILE_IDENTIFIER)
        }
    }
}

private const val LINE_FEED: Byte = 0x0A
private const val PERCENT_SIGN: Byte = 0x25
