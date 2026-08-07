/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.filter

import io.pdfkt.core.obj.PdfDict
import io.pdfkt.core.obj.PdfName
import io.pdfkt.core.obj.PdfStream

// ISO 32000-2:2020 §7.4.4 "LZWDecode and FlateDecode filters"
// RFC 1950 "ZLIB Compressed Data Format Specification version 3.3" §2.2 "Data format"
// RFC 1951 "DEFLATE Compressed Data Format Specification version 1.3"
//   §3.2.3 "Details of block format", §3.2.4 "Non-compressed blocks (BTYPE=00)",
//   §3.2.5 "Compressed blocks (length and distance codes)",
//   §3.2.6 "Compression with fixed Huffman codes (BTYPE=01)"

/**
 * Encodes [data] as a `/FlateDecode` stream.
 *
 * §7.4.4.1 defines the filter by reference and nothing else — "The Flate method
 * is based on the public-domain zlib/deflate compression method … It is fully
 * defined in Internet RFC 1950, and Internet RFC 1951" — so the two RFCs are
 * the whole of what a conforming `/FlateDecode` stream has to satisfy:
 *
 * - the zlib wrapper of RFC 1950 §2.2 — a two-byte header, the deflate data,
 *   and the Adler-32 checksum of the *uncompressed* data;
 * - deflate blocks of RFC 1951 §3.2.3, each either stored (§3.2.4) or coded
 *   with the fixed Huffman alphabets of §3.2.6 over the literal, length and
 *   distance symbols of §3.2.5.
 *
 * The input is cut into chunks of at most [MAX_STORED_LENGTH] bytes, which is
 * the most a stored block can declare, and **each chunk is measured both ways
 * and written the shorter way**. Compression is not free of risk of expansion:
 * under the fixed alphabet a literal byte of 144 or above costs nine bits, so
 * data with no redundancy — an already-compressed image, an encrypted
 * stream — comes out larger coded than stored. Measuring instead of guessing
 * bounds the output at the stored size, `2 + 5 × ⌈n / 65535⌉ + n + 4` bytes for
 * an input of `n`, while data that repeats gets a fraction of that.
 *
 * Redundancy is found by the LZ77 stage of [Lz77], and it is not confined to a
 * chunk: "a duplicated string reference may refer to a string in a previous
 * block" (§3.2.3), so a match may point up to 32 768 bytes back regardless of
 * which block, or which kind of block, holds the bytes it points at.
 *
 * Nothing here is platform code, and nothing here can be: the web has no
 * synchronous compression API, so an `expect`/`actual` pair for this would have
 * no JS `actual` at all. The arithmetic is
 * the same on every target because it is all integer arithmetic on bytes.
 */
public fun flateEncode(data: ByteArray): ByteArray {
    val sink = BitSink(data.size / 4 + ZLIB_HEADER_SIZE + ADLER32_SIZE + 8)
    sink.writeByte(CMF)
    sink.writeByte(FLG)

    // At least one block: an empty deflate stream is not the empty string but
    // one empty block, since the decoding loop of §3.2.3 is a do-while that
    // reads a block header before it can see BFINAL.
    val blocks = if (data.isEmpty()) 1 else (data.size + MAX_STORED_LENGTH - 1) / MAX_STORED_LENGTH
    val lz77 = Lz77(data)
    // One symbol per input byte is the worst case, when nothing matches.
    val tokens = IntArray(if (data.size < MAX_STORED_LENGTH) data.size else MAX_STORED_LENGTH)

    for (block in 0 until blocks) {
        val start = block * MAX_STORED_LENGTH
        val end = if (data.size - start < MAX_STORED_LENGTH) data.size else start + MAX_STORED_LENGTH
        // "BFINAL is set if and only if this is the last block of the data set."
        val last = block == blocks - 1
        val count = lz77.tokenize(start, end, tokens)
        if (fixedBlockBits(tokens, count) < storedBlockBits(sink.bits, end - start)) {
            writeFixedBlock(sink, tokens, count, last)
        } else {
            writeStoredBlock(sink, data, start, end, last)
        }
    }

    // A coded block ends wherever its last code ended, which need not be a byte
    // boundary; the checksum after it is four whole bytes of the wrapper, not
    // part of the deflate stream, so the remainder of the byte is padded out.
    sink.alignToByte()
    // "The Adler-32 checksum is stored as s2*65536 + s1 in most-significant-byte
    // first (network) order" — of the uncompressed data, so it spans the whole
    // input rather than any one block.
    val checksum = adler32(data)
    sink.writeByte(checksum ushr 24)
    sink.writeByte(checksum ushr 16)
    sink.writeByte(checksum ushr 8)
    sink.writeByte(checksum)
    return sink.toByteArray()
}

/**
 * A stream object holding [data] Flate-encoded, with the `/Filter` entry that
 * says so and the [dict] entries the caller declares.
 *
 * The point of the factory is that the encoding and the entry announcing it are
 * decided together. "The filter or filters for a stream shall be specified by
 * the Filter entry in the stream's dictionary" (§7.4.1), and a stream whose
 * bytes and whose `/Filter` disagree is unreadable in a way no later check can
 * catch — the same reasoning that makes [PdfStream] compute `/Length` from its
 * bytes instead of accepting one.
 *
 * [data] is the data *before* encoding: what a reader gets back after applying
 * the filter. No `/DecodeParms` is written — the entry is optional (Table 6),
 * and the only parameters §7.4.4.4 defines for `/FlateDecode` are the predictor,
 * which this encoder does not apply.
 *
 * @throws IllegalArgumentException if [dict] already declares a `/Filter`.
 *   Cascading this filter onto another is a decision about a pipeline
 *   ("Filters may be cascaded to form a pipeline", §7.4.1) that this function
 *   cannot make for a caller: `/Filter` is an ordered array applied in the
 *   order given, and which end of it this filter belongs at depends on what the
 *   other filter is.
 */
public fun flateStream(dict: PdfDict, data: ByteArray): PdfStream {
    require(FILTER !in dict.entries) {
        "the stream already declares a /Filter; cascading filters is the caller's decision " +
            "(ISO 32000-2:2020 §7.4.1)"
    }
    return PdfStream(PdfDict(mapOf(FILTER to FLATE_DECODE) + dict.entries), flateEncode(data))
}

/**
 * The Adler-32 checksum of [data], as the 32-bit value `s2 * 65536 + s1`
 * (RFC 1950 §2.2, and the rationale in §8.2).
 *
 * > Adler-32 is composed of two sums accumulated per byte: s1 is the sum of all
 * > bytes, s2 is the sum of all s1 values. Both sums are done modulo 65521. s1
 * > is initialized to 1, s2 to zero.
 *
 * The reduction is a conditional subtraction rather than a division: entering
 * each step both sums are below 65521, so `s1` cannot exceed 65520 + 255 and
 * `s2` cannot exceed 65520 + 65520, and one subtraction of the modulus brings
 * either back into range.
 *
 * The result is returned as a [Int] holding all 32 bits, which is negative
 * whenever `s2` is 32 768 or more; it is a bit pattern to be written out, not a
 * number to be compared.
 */
internal fun adler32(data: ByteArray): Int {
    var s1 = 1
    var s2 = 0
    for (byte in data) {
        s1 += byte.toInt() and 0xFF
        if (s1 >= ADLER32_BASE) s1 -= ADLER32_BASE
        s2 += s1
        if (s2 >= ADLER32_BASE) s2 -= ADLER32_BASE
    }
    return (s2 shl 16) or s1
}

/** Table 6 of §7.4.1 — the filter name this encoder produces data for. */
private val FILTER = PdfName("Filter")
private val FLATE_DECODE = PdfName("FlateDecode")

/**
 * "CM = 8 denotes the 'deflate' compression method with a window size up to
 * 32K" — the only method RFC 1950 defines, and the one §7.4.4.1 names.
 */
internal const val DEFLATE_METHOD = 8

/**
 * "For CM = 8, CINFO is the base-2 logarithm of the LZ77 window size, minus
 * eight (CINFO=7 indicates a 32K window size)." "Values of CINFO above 7 are
 * not allowed in this version of the specification."
 *
 * 7 is the truth about this encoder: [MAX_DISTANCE] is 32 768, so a match may
 * reach that far back and a decompressor sizing its buffer from CINFO needs the
 * whole window. It is also the largest value the decoder accepts.
 */
internal const val WINDOW_INFO = 7

/**
 * FLEVEL, "bits 6 to 7" of FLG: 0 is "compressor used fastest algorithm", which
 * is the truth about an encoder that takes the first good match it finds rather
 * than searching for the best one. "The information in FLEVEL is not needed for
 * decompression."
 */
private const val FASTEST_ALGORITHM = 0

/** CMF: "bits 0 to 3 CM Compression method", "bits 4 to 7 CINFO Compression info". */
private const val CMF = (WINDOW_INFO shl 4) or DEFLATE_METHOD

/** FLG before FCHECK: FLEVEL in bits 6–7, and FDICT (bit 5) clear — no preset dictionary. */
private const val FLG_WITHOUT_CHECK = FASTEST_ALGORITHM shl 6

/**
 * FLG: the flags with the check bits filled in.
 *
 * > The FCHECK value must be such that CMF and FLG, when viewed as a 16-bit
 * > unsigned integer stored in MSB order (CMF*256 + FLG), is a multiple of 31.
 *
 * FCHECK occupies bits 0 to 4, so adding it to FLG adds its own value to the
 * 16-bit integer, and the value needed is what completes the remainder to the
 * modulus — never more than 30, so it always fits in the five bits. The outer
 * remainder covers the case of a remainder that is already zero, where nothing
 * is to be added rather than a whole modulus.
 */
private const val FLG = FLG_WITHOUT_CHECK or ((31 - (CMF * 256 + FLG_WITHOUT_CHECK) % 31) % 31)

/** CMF and FLG (RFC 1950 §2.2). */
internal const val ZLIB_HEADER_SIZE = 2

/** The trailing checksum, "s2*65536 + s1", four bytes. */
internal const val ADLER32_SIZE = 4

/** "Both sums are done modulo 65521" — the largest prime below 65536 (§8.2). */
private const val ADLER32_BASE = 65521
