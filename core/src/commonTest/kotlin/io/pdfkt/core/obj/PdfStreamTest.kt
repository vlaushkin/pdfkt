/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.obj

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

// ISO 32000-2:2020 §7.3.8 "Stream objects"

/**
 * What the clause requires of a stream object:
 *
 * > A stream shall consist of a dictionary followed by zero or more bytes
 * > bracketed between the keywords stream (followed by newline) and endstream:
 * >
 * >     dictionary
 * >     stream
 * >     …Zero or more bytes…
 * >     endstream
 *
 * > The keyword stream that follows the stream dictionary shall be followed by
 * > an end-of-line marker consisting of either a CARRIAGE RETURN and a LINE
 * > FEED or just a LINE FEED, and not by a CARRIAGE RETURN alone. The sequence
 * > of bytes that make up a stream lie between the end-of-line marker following
 * > the stream keyword and the endstream keyword; the stream dictionary
 * > specifies the exact number of bytes. There should be an end-of-line marker
 * > after the data and before endstream; this marker shall not be included in
 * > the stream length. There shall not be any extra bytes, other than
 * > white-space, between endstream and endobj.
 *
 * And of `/Length`, §7.3.8.2 with Table 5:
 *
 * > Every stream dictionary shall have a Length entry that indicates how many
 * > bytes of the PDF file are used for the stream's data. … The number of bytes
 * > from the beginning of the line following the keyword stream to the last
 * > byte just before the keyword endstream. (There may be an additional EOL
 * > marker, preceding endstream, that is not included in the count and is not
 * > logically part of the stream data.)
 *
 * `/Filter` is optional in Table 5, so the streams here — which declare none —
 * are complete stream objects and not a stage on the way to one.
 */
class PdfStreamTest {
    /**
     * The framing of the clause, byte for byte: the dictionary, a LINE FEED,
     * `stream`, the end-of-line marker the clause requires after the keyword,
     * the data, the marker before `endstream`, and the keyword.
     */
    @Test
    fun `a stream is its dictionary then its bytes between the keywords`() {
        val stream = PdfStream(PdfDict(mapOf(PdfName("Type") to PdfName("Example"))), DATA)
        assertContentEquals(
            "<</Length 12 /Type /Example>>\nstream\nBT /F1 12 Tf\nendstream".encodeToByteArray(),
            serialize(stream),
        )
    }

    /** "…zero or more bytes…", and a dictionary that then says so. */
    @Test
    fun `a stream of no bytes is a length of zero and nothing between the keywords`() {
        assertEquals(
            "<</Length 0>>\nstream\n\nendstream",
            serializeToText(PdfStream(PdfDict(emptyMap()), ByteArray(0))),
        )
    }

    /**
     * > The number of bytes from the beginning of the line following the
     * > keyword stream to the last byte just before the keyword endstream.
     *
     * Which is the count of the data and nothing else, at every size.
     */
    @Test
    fun `the length entry is the number of bytes of the data`() {
        for (size in listOf(0, 1, 2, 255, 256, 4096, 100_000)) {
            val stream = PdfStream(PdfDict(emptyMap()), ByteArray(size) { 0x41 })
            assertEquals(
                PdfInt(size.toLong()),
                stream.dict.entries[PdfName("Length")],
                "a stream of $size bytes declared a different length",
            )
            assertEquals(size, dataOf(serialize(stream)).size)
        }
    }

    /**
     * > (There may be an additional EOL marker, preceding endstream, that is
     * > not included in the count and is not logically part of the stream
     * > data.)
     *
     * The marker this writer always emits is that additional one. Data whose
     * own last byte is a LINE FEED therefore ends with two of them in the file,
     * and only the first belongs to the stream.
     */
    @Test
    fun `the marker before endstream is not part of the data or of the length`() {
        val data = "line\n".encodeToByteArray()
        val stream = PdfStream(PdfDict(emptyMap()), data)

        assertEquals(PdfInt(5), stream.dict.entries[PdfName("Length")])
        assertEquals("<</Length 5>>\nstream\nline\n\nendstream", serializeToText(stream))
        assertContentEquals(data, dataOf(serialize(stream)))
    }

    /**
     * > …and not by a CARRIAGE RETURN alone.
     *
     * NOTE 2 gives the reason: a lone CARRIAGE RETURN could not be told from a
     * CARRIAGE RETURN ending the line followed by a LINE FEED that is the first
     * byte of the data. The marker written is a lone LINE FEED, so the byte
     * that follows the keyword decides nothing else.
     */
    @Test
    fun `the keyword stream is followed by a line feed`() {
        val bytes = serialize(PdfStream(PdfDict(emptyMap()), DATA))
        val keyword = "stream".encodeToByteArray()
        val start = indexOf(bytes, keyword)

        assertTrue(start >= 0, "the keyword stream was not written")
        assertEquals(LINE_FEED, bytes[start + keyword.size], "the keyword stream is not followed by a LINE FEED")
        assertTrue(
            CARRIAGE_RETURN !in bytes.copyOfRange(0, start + keyword.size + 1),
            "a CARRIAGE RETURN was written into the framing before the data",
        )
    }

    /**
     * > There shall not be any extra bytes, other than white-space, between
     * > endstream and endobj.
     *
     * Nothing at all is written after the keyword: what follows a stream is the
     * `endobj` of the indirect object framing it, which §7.3.10 leaves to the
     * writer, and a trailing byte invented here would be a byte that framing
     * could not account for.
     */
    @Test
    fun `nothing is written after the keyword endstream`() {
        val bytes = serialize(PdfStream(PdfDict(emptyMap()), DATA))
        assertContentEquals("endstream".encodeToByteArray(), bytes.copyOfRange(bytes.size - 9, bytes.size))
    }

    /**
     * A stream is delimited by a count, not by a sentinel: "the stream
     * dictionary specifies the exact number of bytes". So the data is written
     * as it stands — all 256 byte values, each as itself — and the keyword
     * spelled out inside the data is data, counted by `/Length` like the rest.
     */
    @Test
    fun `every byte value reaches the file as itself`() {
        val data = ByteArray(256) { it.toByte() }
        val stream = PdfStream(PdfDict(emptyMap()), data)

        assertEquals(PdfInt(256), stream.dict.entries[PdfName("Length")])
        assertContentEquals(data, dataOf(serialize(stream)))
    }

    @Test
    fun `bytes that spell endstream are data and are counted as data`() {
        val data = "before\nendstream\nafter".encodeToByteArray()
        val stream = PdfStream(PdfDict(emptyMap()), data)

        assertEquals(PdfInt(data.size.toLong()), stream.dict.entries[PdfName("Length")])
        assertEquals(
            "<</Length 22>>\nstream\nbefore\nendstream\nafter\nendstream",
            serializeToText(stream),
        )
        assertContentEquals(data, dataOf(serialize(stream)))
    }

    /**
     * `/Length` is computed rather than accepted. It is "the number of bytes
     * from the beginning of the line following the keyword stream to the last
     * byte just before the keyword endstream" — a fact about the data, and the
     * one entry of Table 5 a caller has nothing to say about.
     */
    @Test
    fun `a length supplied by the caller is refused`() {
        val refused =
            assertFailsWith<IllegalArgumentException> {
                PdfStream(PdfDict(mapOf(PdfName("Length") to PdfInt(999))), DATA)
            }
        assertTrue(
            refused.message?.contains("/Length") == true,
            "the refusal does not say what it is about: ${refused.message}",
        )
    }

    /**
     * A stream does not copy the bytes it was built from, unlike [PdfArray] and
     * [PdfDict] — it is the object the clause offers for "potentially large
     * amounts of data, such as images and page descriptions", and copying every
     * image into the object model would double the peak memory of a document.
     * What keeps it honest instead is that its dictionary is derived from those
     * bytes, so the declared length is never the length of something else.
     */
    @Test
    fun `a stream holds the bytes it was given and declares their length`() {
        val data = "revised".encodeToByteArray()
        val stream = PdfStream(PdfDict(emptyMap()), data)
        data[0] = 'd'.code.toByte()

        assertSame(data, stream.data)
        assertEquals(PdfInt(7), stream.dict.entries[PdfName("Length")])
        assertEquals("<</Length 7>>\nstream\ndevised\nendstream", serializeToText(stream))
    }

    /**
     * Every other entry is the caller's, and survives untouched — Table 5's
     * optional entries and whatever the clause describing a particular kind of
     * stream adds to them.
     */
    @Test
    fun `the entries the caller declared are written beside the length`() {
        val stream =
            PdfStream(
                PdfDict(
                    mapOf(
                        PdfName("Type") to PdfName("XObject"),
                        PdfName("Subtype") to PdfName("Image"),
                        PdfName("Width") to PdfInt(20),
                        PdfName("Height") to PdfInt(10),
                        PdfName("Filter") to PdfName("DCTDecode"),
                    ),
                ),
                DATA,
            )
        assertEquals(
            "<</Length 12 /Type /XObject /Subtype /Image /Width 20 /Height 10 /Filter /DCTDecode>>" +
                "\nstream\nBT /F1 12 Tf\nendstream",
            serializeToText(stream),
        )
    }

    /**
     * `/Filter` is optional in Table 5. A stream that declares none is a
     * complete stream object whose bytes are the data, and its dictionary holds
     * exactly the one entry the clause requires.
     */
    @Test
    fun `a stream with no filter declares nothing but its length`() {
        val stream = PdfStream(PdfDict(emptyMap()), DATA)
        assertEquals(setOf(PdfName("Length")), stream.dict.entries.keys)
        assertEquals("<</Length 12>>\nstream\nBT /F1 12 Tf\nendstream", serializeToText(stream))
    }

    /**
     * The dictionary of a stream is a dictionary object, so §7.3.7 applies to
     * it whole — including "A dictionary entry whose value is null … shall be
     * treated the same as if the entry does not exist".
     */
    @Test
    fun `a null-valued entry of the stream dictionary is absent as everywhere else`() {
        val stream =
            PdfStream(
                PdfDict(mapOf(PdfName("Type") to PdfName("XObject"), PdfName("DecodeParms") to PdfNull)),
                DATA,
            )
        assertEquals(setOf(PdfName("Length"), PdfName("Type")), stream.dict.entries.keys)
    }

    /**
     * Finds the data between the two markers by the rules a reader would use:
     * the data starts after the end-of-line marker that follows the keyword
     * `stream`, and runs for the number of bytes `/Length` declares.
     *
     * The count comes out of the written dictionary rather than out of the
     * object, so a test that uses this is checking that the file describes
     * itself — not that the writer agrees with itself.
     */
    private fun dataOf(bytes: ByteArray): ByteArray {
        val text = bytes.decodeToString(0, bytes.indexOf(LINE_FEED))
        val declared = text.substringAfter("/Length ").substringBefore(' ').substringBefore('>').toInt()
        val start = indexOf(bytes, "stream".encodeToByteArray()) + "stream".length + 1
        return bytes.copyOfRange(start, start + declared)
    }

    private fun ByteArray.indexOf(byte: Byte): Int = indexOfFirst { it == byte }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        outer@ for (start in 0..haystack.size - needle.size) {
            for (offset in needle.indices) {
                if (haystack[start + offset] != needle[offset]) continue@outer
            }
            return start
        }
        return -1
    }

    private companion object {
        /** Twelve bytes of content-stream text, ending in a regular character. */
        val DATA: ByteArray = "BT /F1 12 Tf".encodeToByteArray()

        const val LINE_FEED: Byte = 0x0A
        const val CARRIAGE_RETURN: Byte = 0x0D
    }
}
