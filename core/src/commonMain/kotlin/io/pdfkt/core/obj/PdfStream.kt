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

// ISO 32000-2:2020 §7.3.8 "Stream objects"

/**
 * A stream object — "a dictionary followed by zero or more bytes bracketed
 * between the keywords stream (followed by newline) and endstream".
 *
 * [data] holds the bytes exactly as they are to appear in the file, already
 * encoded by whatever filters the dictionary declares: "If the stream has a
 * filter, Length shall be the number of bytes of encoded data" (§7.3.8.2).
 * Filtering is a separate concern from framing, and this type is responsible
 * for the framing only. A stream that declares no `/Filter` is complete as it
 * stands — the entry is optional in Table 5, and its absence says the bytes
 * between the keywords are the data.
 *
 * Unlike [PdfArray] and [PdfDict], this type does not copy what it was built
 * from. A stream is the object the clause introduces for "potentially large
 * amounts of data, such as images and page descriptions", and copying every
 * image on the way into the object model would double the peak memory of a
 * document for a guarantee the other containers can afford and this one
 * cannot. What is guaranteed instead is that the object never contradicts
 * itself: [dict] is derived from [data] whenever it is asked for, so the
 * `/Length` a caller reads and the `/Length` that is written are both the
 * length of the bytes as they are now.
 *
 * @param dict the entries this stream declares, without `/Length`.
 * @throws IllegalArgumentException if [dict] carries a `/Length` entry. The
 *   entry is required of every stream dictionary and its value is "the number
 *   of bytes from the beginning of the line following the keyword stream to
 *   the last byte just before the keyword endstream" — a property of [data]
 *   rather than a decision, so it is computed here and not accepted from a
 *   caller who could get it wrong.
 */
public class PdfStream(dict: PdfDict, public val data: ByteArray) : PdfObject {
    /** The entries the caller declared: the stream dictionary less `/Length`. */
    private val declared: Map<PdfName, PdfObject> = dict.entries

    init {
        require(LENGTH !in declared) {
            "the /Length of a stream is the number of bytes it holds and is computed from them " +
                "(ISO 32000-2:2020 §7.3.8.2, Table 5)"
        }
    }

    /**
     * The stream dictionary as it will be written: the declared entries with
     * the required `/Length` (Table 5) in front of them.
     *
     * `/Length` is written first because it is the entry a reader needs in
     * order to find the end of the data; where it sits among the others
     * carries nothing, since "the entries in a dictionary … shall be
     * unordered" (§7.3.7).
     */
    public val dict: PdfDict
        get() = PdfDict(mapOf(LENGTH to PdfInt(data.size.toLong())) + declared)
}

/**
 * Writes a stream object — the dictionary, then the data between the keywords:
 *
 * ```
 * dictionary
 * stream
 * …Zero or more bytes…
 * endstream
 * ```
 *
 * The two end-of-line markers the clause legislates about are the whole of the
 * framing:
 *
 * - "The keyword stream that follows the stream dictionary shall be followed
 *   by an end-of-line marker consisting of either a CARRIAGE RETURN and a LINE
 *   FEED or just a LINE FEED, and not by a CARRIAGE RETURN alone." A lone LINE
 *   FEED is written. NOTE 2 explains why the third spelling is barred — a
 *   CARRIAGE RETURN alone cannot be told from a CARRIAGE RETURN that ends the
 *   line plus a LINE FEED that is the first byte of the data — and the same
 *   reasoning is why the two-byte marker is not chosen here either: it costs a
 *   byte per stream to write a marker whose second half only exists to be
 *   distinguished from data.
 * - "There should be an end-of-line marker after the data and before
 *   endstream; this marker shall not be included in the stream length." One
 *   LINE FEED is written after the data, and `/Length` counts [PdfStream.data]
 *   alone. Without it a stream whose last byte is a regular character would
 *   run into the keyword and make one token of the two.
 *
 * The dictionary is separated from the `stream` keyword by a LINE FEED as
 * well. That one is ordinary token separation rather than a rule of this
 * clause — `>>` already ends the dictionary — and it is what puts the keyword
 * at the start of a line, as the clause's layout shows it.
 *
 * Nothing is written after `endstream`: "There shall not be any extra bytes,
 * other than white-space, between endstream and endobj", and `endobj` belongs
 * to the indirect-object framing that the writer applies (§7.3.10).
 * Nothing precedes the dictionary either, for
 * the same reason it does not for any other object.
 *
 * The data is written byte for byte with nothing examined and nothing escaped.
 * A stream is delimited by a count and not by a sentinel, so bytes that spell
 * `endstream` are data like any other, and every one of the 256 values reaches
 * the file as itself.
 */
internal fun Sink.writePdfStream(obj: PdfStream) {
    writePdfObject(obj.dict)
    writeByte(LINE_FEED)
    write(STREAM_KEYWORD, 0, STREAM_KEYWORD.size)
    writeByte(LINE_FEED)
    write(obj.data, 0, obj.data.size)
    writeByte(LINE_FEED)
    write(ENDSTREAM_KEYWORD, 0, ENDSTREAM_KEYWORD.size)
}

/** Table 5 of §7.3.8.2 — the one entry required of every stream dictionary. */
private val LENGTH = PdfName("Length")

private val STREAM_KEYWORD = byteArrayOf(0x73, 0x74, 0x72, 0x65, 0x61, 0x6D) // stream
private val ENDSTREAM_KEYWORD =
    byteArrayOf(0x65, 0x6E, 0x64, 0x73, 0x74, 0x72, 0x65, 0x61, 0x6D) // endstream

/** LINE FEED (0Ah), Table 1 of §7.2.3 — an end-of-line marker on its own. */
private const val LINE_FEED: Byte = 0x0A
