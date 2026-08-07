/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.filter

// ISO 32000-2:2020 §7.4.4 "LZWDecode and FlateDecode filters"
// RFC 1950 "ZLIB Compressed Data Format Specification version 3.3" §2.2 "Data format"
// RFC 1951 "DEFLATE Compressed Data Format Specification version 1.3"
//   §3.1.1 "Packing into bytes", §3.2.2 "Use of Huffman coding in the 'deflate' format",
//   §3.2.3 "Details of block format", §3.2.4 "Non-compressed blocks (BTYPE=00)",
//   §3.2.5 "Compressed blocks (length and distance codes)",
//   §3.2.6 "Compression with fixed Huffman codes (BTYPE=01)",
//   §3.2.7 "Compression with dynamic Huffman codes (BTYPE=10)", §3.3 "Compliance"
//
// What the format says, on the reading side; InflateBits.kt holds what saying it
// costs — the bit stream, the code tables and the output buffer — which is the
// same split the writing side is under between Flate.kt and Deflate.kt.

/**
 * Decodes a `/FlateDecode` stream: the zlib wrapper of RFC 1950 §2.2 around the
 * deflate blocks of RFC 1951 §3.2.
 *
 * This is the other half of [flateEncode], and it is deliberately not its
 * mirror. An encoder picks: which block types it writes, how far back it
 * searches, whether it bothers with dynamic alphabets at all. A decoder gets no
 * such latitude — §3.3 is explicit that "a compliant decompressor must accept
 * the full range of possible values defined in the previous section, and must
 * accept blocks of arbitrary size" — so all three block types of §3.2.3 are
 * implemented here, including the dynamic Huffman alphabets of §3.2.7 that
 * nothing in this library writes. Streams to be read come from elsewhere: an
 * image handed in already compressed, a page from a file being amended, the
 * `IDAT` of a PNG.
 *
 * What comes back is the data the filter was applied to, whole or not at all.
 * A stream that stops early, or contradicts itself, raises
 * [FlateFormatException] carrying the offset of the fault rather than returning
 * the prefix decoded so far — see that class for why.
 *
 * The trailing Adler-32 is checked. It is the one part of the format that
 * covers the *output*, and skipping it — which costs nothing and is invisible
 * in every test that feeds a valid stream — turns a corrupted stream into
 * plausible wrong bytes.
 *
 * Termination is a property of the loops, not of a guard bolted on: every
 * iteration of every loop here consumes at least one bit of [data] and running
 * out of bits raises. So the work is bounded by `8 × data.size` iterations, and
 * the output by 1032 bytes per input byte — a length-258 match costs at least
 * two bits, one for the length code and one for the distance code, and no
 * shorter encoding of more output exists in the format.
 *
 * Bytes after the checksum are ignored: "any data which may appear after
 * ADLER32 are not part of the zlib stream" (RFC 1950 §2.2), which is what makes
 * this safe to hand the raw bytes of a PDF stream object, where the `/Length`
 * may legitimately count an end-of-line marker after the data (§7.3.8.2).
 *
 * @throws FlateFormatException if [data] is not a zlib stream of deflate data,
 *   or if its checksum does not match what came out.
 */
public fun flateDecode(data: ByteArray): ByteArray {
    if (data.size < ZLIB_HEADER_SIZE) {
        throw FlateFormatException(
            data.size,
            "a zlib stream begins with the two bytes CMF and FLG, and this one is ${data.size} byte" +
                (if (data.size == 1) "" else "s") + " long",
            RFC1950_DATA_FORMAT,
        )
    }
    val cmf = data[0].toInt() and 0xFF
    val flg = data[1].toInt() and 0xFF

    // "CM = 8 denotes the 'deflate' compression method … Other compressed data
    // formats are not specified in this version of the zlib specification."
    val method = cmf and 0x0F
    if (method != DEFLATE_METHOD) {
        throw FlateFormatException(
            0,
            "CM is $method; the only compression method this format defines is 8, deflate",
            RFC1950_DATA_FORMAT,
        )
    }
    // "Values of CINFO above 7 are not allowed in this version of the specification."
    val windowInfo = cmf shr 4
    if (windowInfo > WINDOW_INFO) {
        throw FlateFormatException(
            0,
            "CINFO is $windowInfo, a window of ${1 shl (windowInfo + 8)} bytes; values above 7 are not allowed",
            RFC1950_DATA_FORMAT,
        )
    }
    // "The FCHECK value must be such that CMF and FLG, when viewed as a 16-bit
    // unsigned integer stored in MSB order (CMF*256 + FLG), is a multiple of 31."
    if ((cmf * 256 + flg) % 31 != 0) {
        throw FlateFormatException(
            1,
            "FCHECK does not check out: CMF*256 + FLG is ${cmf * 256 + flg}, which is not a multiple of 31",
            RFC1950_DATA_FORMAT,
        )
    }
    // "If FDICT is set, a DICT dictionary identifier is present immediately
    // after the FLG byte", and the dictionary itself is not: it is "a sequence
    // of bytes which are initially fed to the compressor" and which the stream
    // does not carry. Without it the data cannot be decoded, and §7.4.4 gives a
    // PDF reader nowhere to obtain one from.
    if (flg and FDICT != 0) {
        throw FlateFormatException(
            1,
            "FDICT is set: the data was compressed against a preset dictionary, which is not part of " +
                "the stream and which /FlateDecode has no way to supply",
            RFC1950_DATA_FORMAT,
        )
    }

    val source = BitSource(data, ZLIB_HEADER_SIZE)
    // Compressed data that expands is the exception; starting at twice the
    // input saves a few doublings on the common case and costs nothing when the
    // guess is wrong.
    val out = ByteSink(data.size * 2)
    inflate(source, out)

    // "Adler-32 checksum … in most-significant-byte first (network) order", four
    // whole bytes of the wrapper, so whatever bits the last block left in the
    // current byte are behind us.
    source.alignToByte()
    val checksumOffset = source.offset
    if (checksumOffset + ADLER32_SIZE > data.size) {
        throw FlateFormatException(
            data.size,
            "the deflate data ends at byte $checksumOffset and the four bytes of ADLER32 do not follow it",
            RFC1950_DATA_FORMAT,
        )
    }
    var declared = 0
    repeat(ADLER32_SIZE) { declared = (declared shl 8) or source.readByte() }
    val decoded = out.toByteArray()
    val computed = adler32(decoded)
    if (declared != computed) {
        throw FlateFormatException(
            checksumOffset,
            "ADLER32 is ${hex(declared)}, but the Adler-32 of the ${decoded.size} bytes decoded is " +
                "${hex(computed)}: the data does not check out against its own checksum",
            RFC1950_DATA_FORMAT,
        )
    }
    return decoded
}

/**
 * The deflate stream itself (RFC 1951 §3.2.3): blocks read from [source] into
 * [out] until one of them declares itself the last.
 *
 * The loop is the clause's own, which is a do-while over block headers:
 *
 * > do
 * >    read block header from input stream.
 * >    if stored with no compression
 * >       skip any remaining bits in current partially processed byte
 * >       read LEN and NLEN (see next section)
 * >       copy LEN bytes of data to output
 * >    otherwise
 * >       if compressed with dynamic Huffman codes
 * >          read representation of code trees (see subsection below)
 * >       loop (until end of block code recognized)
 * >          decode literal/length value from input stream
 * >          …
 * >       end loop
 * > while not last block
 *
 * BFINAL is what ends it and nothing else: a stream whose blocks run out before
 * one of them sets that bit is truncated, and [BitSource] says so when the bits
 * run out. Counting blocks, or stopping at the end of the input, would turn a
 * truncated stream into a short answer.
 */
internal fun inflate(source: BitSource, out: ByteSink) {
    while (true) {
        val blockOffset = source.offset
        // "first bit BFINAL, next 2 bits BTYPE"; both are data elements, so
        // §3.1.1 puts them least significant bit first.
        val last = source.readBits(1) == 1
        when (val type = source.readBits(BLOCK_TYPE_BITS)) {
            STORED_BLOCK -> readStoredBlock(source, out)
            FIXED_HUFFMAN_BLOCK -> readCompressedBlock(source, out, FIXED_LITERAL_TABLE, FIXED_DISTANCE_TABLE)
            DYNAMIC_HUFFMAN_BLOCK -> {
                val alphabets = readDynamicAlphabets(source)
                readCompressedBlock(source, out, alphabets.literal, alphabets.distance)
            }
            else ->
                throw FlateFormatException(
                    blockOffset,
                    "BTYPE is ${type.toString(radix = 2).padStart(BLOCK_TYPE_BITS, '0')}, which is reserved",
                    RFC1951_BLOCK_FORMAT,
                )
        }
        if (last) return
    }
}

/**
 * A block with BTYPE=00 (§3.2.4):
 *
 * > Any bits of input up to the next byte boundary are ignored. The rest of the
 * > block consists of the following information:
 * >
 * >      0   1   2   3   4...
 * >    +---+---+---+---+================================+
 * >    |  LEN  | NLEN  |... LEN bytes of literal data...|
 * >    +---+---+---+---+================================+
 * >
 * > LEN is the number of data bytes in the block. NLEN is the one's complement
 * > of LEN.
 *
 * NLEN is checked. It carries no information a decoder needs — LEN alone says
 * how many bytes follow — so the only reason the format spends two bytes on it
 * is to be checked, and a stream that fails the check is one whose LEN cannot
 * be trusted either.
 */
private fun readStoredBlock(source: BitSource, out: ByteSink) {
    source.alignToByte()
    val offset = source.offset
    val length = source.readByte() or (source.readByte() shl 8)
    val complement = source.readByte() or (source.readByte() shl 8)
    if (complement != (length.inv() and 0xFFFF)) {
        throw FlateFormatException(
            offset,
            "LEN is $length and NLEN is $complement, which is not its one's complement " +
                "(${length.inv() and 0xFFFF})",
            RFC1951_STORED_BLOCK,
        )
    }
    repeat(length) { out.append(source.readByte()) }
}

/**
 * The body of a block coded with [literal] and [distance], read until the
 * end-of-block symbol (§3.2.3, §3.2.5).
 *
 * > decode literal/length value from input stream
 * > if value < 256
 * >    copy value (literal byte) to output stream
 * > otherwise
 * >    if value = end of block (256)
 * >       break from loop
 * >    otherwise (value = 257..285)
 * >       decode distance from input stream
 * >       move backwards distance bytes in the output stream, and copy length
 * >       bytes from this position to the output stream.
 *
 * The two alphabets are the whole difference between BTYPE=01 and BTYPE=10 —
 * "the only difference between the two compressed cases is how the Huffman
 * codes for the literal/length and distance alphabets are defined" (§3.2.3) —
 * so this function is the whole of both, and the fixed case is the dynamic one
 * with the tables of §3.2.6 handed in.
 */
private fun readCompressedBlock(source: BitSource, out: ByteSink, literal: HuffmanTable, distance: HuffmanTable) {
    while (true) {
        val symbolOffset = source.offset
        val symbol = literal.decode(source)
        if (symbol < END_OF_BLOCK) {
            out.append(symbol)
            continue
        }
        if (symbol == END_OF_BLOCK) return

        // "values 257..285 represent length codes"; 286 and 287 have codes in
        // the fixed alphabet because they "participate in the code
        // construction" (§3.2.6) and can be spelled by a dynamic one, but
        // neither stands for anything.
        val lengthIndex = symbol - LITERAL_LENGTH_CODE_BASE
        if (lengthIndex >= LENGTH_BASE.size) {
            throw FlateFormatException(
                symbolOffset,
                "the literal/length symbol $symbol stands for nothing: the alphabet ends at 285, and " +
                    "286-287 never occur in compressed data",
                RFC1951_LENGTH_AND_DISTANCE,
            )
        }
        val length = LENGTH_BASE[lengthIndex] + source.readBits(LENGTH_EXTRA_BITS[lengthIndex])

        val distanceOffset = source.offset
        // "One distance code of zero bits means that there are no distance
        // codes used at all (the data is all literals)" (§3.2.7) — and a length
        // symbol has just asked for one.
        if (distance.isEmpty) {
            throw FlateFormatException(
                distanceOffset,
                "a length of $length was coded, but the block declares no distance codes at all",
                RFC1951_DYNAMIC_BLOCK,
            )
        }
        val distanceSymbol = distance.decode(source)
        if (distanceSymbol >= DISTANCE_BASE.size) {
            throw FlateFormatException(
                distanceOffset,
                "the distance symbol $distanceSymbol stands for nothing: the alphabet ends at 29, and " +
                    "30-31 never occur in compressed data",
                RFC1951_LENGTH_AND_DISTANCE,
            )
        }
        val back = DISTANCE_BASE[distanceSymbol] + source.readBits(DISTANCE_EXTRA_BITS[distanceSymbol])

        // "However a distance cannot refer past the beginning of the output
        // stream." (The clause allows one that does when a preset dictionary
        // was discarded from the front of the output; FDICT is refused before
        // any of this runs, so here there is no such case.)
        if (back > out.size) {
            throw FlateFormatException(
                distanceOffset,
                "a distance of $back reaches past the beginning of the output stream, which holds " +
                    "${out.size} byte" + (if (out.size == 1) "" else "s"),
                RFC1951_BLOCK_FORMAT,
            )
        }
        out.copyBack(back, length)
    }
}

/** The two alphabets a block codes its symbols with. */
private class Alphabets(val literal: HuffmanTable, val distance: HuffmanTable)

/**
 * The code trees of a BTYPE=10 block, read from the head of the block (§3.2.7).
 *
 * > 5 Bits: HLIT, # of Literal/Length codes - 257 (257 - 286)
 * > 5 Bits: HDIST, # of Distance codes - 1        (1 - 32)
 * > 4 Bits: HCLEN, # of Code Length codes - 4     (4 - 19)
 * >
 * > (HCLEN + 4) x 3 bits: code lengths for the code length alphabet given just
 * >    above, in the order: 16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13,
 * >    2, 14, 1, 15
 * >
 * > HLIT + 257 code lengths for the literal/length alphabet, encoded using the
 * >    code length Huffman code
 * >
 * > HDIST + 1 code lengths for the distance alphabet, encoded using the code
 * >    length Huffman code
 *
 * Three Huffman codes are therefore in play, one of which exists only to
 * transmit the other two: the code length alphabet, whose own lengths arrive as
 * plain 3-bit integers in an order chosen so that the lengths likeliest to be
 * unused sit at the end and can be left out.
 *
 * > The code length repeat codes can cross from HLIT + 257 to the HDIST + 1
 * > code lengths. In other words, all code lengths form a single sequence of
 * > HLIT + HDIST + 258 values.
 *
 * That sentence is why the two alphabets' lengths are read into one array and
 * split afterwards, rather than read one alphabet at a time: a run of zeroes
 * may begin among the literal lengths and end among the distance lengths, and a
 * reader that stopped the run at the boundary would place every distance length
 * one symbol wrong.
 */
private fun readDynamicAlphabets(source: BitSource): Alphabets {
    val headerOffset = source.offset
    val literalCount = source.readBits(HLIT_BITS) + MIN_LITERAL_CODES
    val distanceCount = source.readBits(HDIST_BITS) + 1
    val codeLengthCount = source.readBits(HCLEN_BITS) + MIN_CODE_LENGTH_CODES
    // "HLIT, # of Literal/Length codes - 257 (257 - 286)": five bits reach 288,
    // but the alphabet has 286 symbols and the two beyond it stand for nothing.
    // HDIST needs no such check — its range is "1 - 32" and five bits reach
    // exactly 32, the two beyond the thirty codes of §3.2.5 being the ones that
    // never occur; a block that declares them and leaves them unused is a block,
    // and one that uses them is refused where the symbol is decoded.
    if (literalCount > MAX_LITERAL_CODES) {
        throw FlateFormatException(
            headerOffset,
            "HLIT declares $literalCount literal/length codes; the alphabet has at most $MAX_LITERAL_CODES",
            RFC1951_DYNAMIC_BLOCK,
        )
    }

    val codeLengthLengths = IntArray(CODE_LENGTH_ALPHABET_SIZE)
    for (position in 0 until codeLengthCount) {
        codeLengthLengths[CODE_LENGTH_ORDER[position]] = source.readBits(CODE_LENGTH_BITS)
    }
    val codeLengths = HuffmanTable.of(codeLengthLengths, "code length", source.offset, incompleteAllowed = false)

    val lengths = IntArray(literalCount + distanceCount)
    var at = 0
    while (at < lengths.size) {
        val offset = source.offset
        val symbol = codeLengths.decode(source)
        // "0 - 15: Represent code lengths of 0 - 15".
        if (symbol < CODE_LENGTH_REPEAT_PREVIOUS) {
            lengths[at++] = symbol
            continue
        }
        val repeated: Int
        val count: Int
        when (symbol) {
            // "16: Copy the previous code length 3 - 6 times. The next 2 bits
            // indicate repeat length (0 = 3, … , 3 = 6)".
            CODE_LENGTH_REPEAT_PREVIOUS -> {
                if (at == 0) {
                    throw FlateFormatException(
                        offset,
                        "code 16 copies the previous code length, and it is the first code of the sequence",
                        RFC1951_DYNAMIC_BLOCK,
                    )
                }
                repeated = lengths[at - 1]
                count = source.readBits(REPEAT_PREVIOUS_EXTRA_BITS) + MIN_REPEAT_PREVIOUS
            }
            // "17: Repeat a code length of 0 for 3 - 10 times. (3 bits of length)".
            CODE_LENGTH_REPEAT_ZERO_SHORT -> {
                repeated = 0
                count = source.readBits(REPEAT_ZERO_SHORT_EXTRA_BITS) + MIN_REPEAT_ZERO_SHORT
            }
            // "18: Repeat a code length of 0 for 11 - 138 times (7 bits of length)".
            else -> {
                repeated = 0
                count = source.readBits(REPEAT_ZERO_LONG_EXTRA_BITS) + MIN_REPEAT_ZERO_LONG
            }
        }
        if (at + count > lengths.size) {
            throw FlateFormatException(
                offset,
                "a repeat of $count code lengths at position $at runs past the ${lengths.size} lengths " +
                    "the block declares",
                RFC1951_DYNAMIC_BLOCK,
            )
        }
        repeat(count) { lengths[at++] = repeated }
    }

    val tableOffset = source.offset
    return Alphabets(
        // A literal/length alphabet with a code left over is a block that can be
        // handed bits it cannot decode; there is no reading of §3.2.2 under
        // which that is a stream, so it is refused here rather than at the bit
        // that happens to hit the gap.
        literal = HuffmanTable.of(
            lengths.copyOfRange(0, literalCount),
            "literal/length",
            tableOffset,
            incompleteAllowed = false,
        ),
        // "If only one distance code is used, it is encoded using one bit, not
        // zero bits; in this case there is a single code length of one, with
        // one unused code. One distance code of zero bits means that there are
        // no distance codes used at all (the data is all literals)." Both are
        // incomplete codes the clause requires a decoder to take.
        distance = HuffmanTable.of(
            lengths.copyOfRange(literalCount, lengths.size),
            "distance",
            tableOffset,
            incompleteAllowed = true,
        ),
    )
}

/**
 * The literal/length alphabet of §3.2.6, in decoding form.
 *
 * Built from the same lengths the encoder writes with, by the same
 * construction — the fixed alphabet is not a special case of anything, it is
 * one particular table of code lengths that both sides know without being told.
 *
 * Lazily, because a top-level value that reads a top-level value of another
 * file depends on which file is initialised first, and that is not something
 * five different backends agree on.
 */
internal val FIXED_LITERAL_TABLE: HuffmanTable by lazy {
    HuffmanTable.of(FIXED_LITERAL_LENGTHS, "literal/length", 0, incompleteAllowed = false)
}

/** The distance alphabet of §3.2.6: "(fixed-length) 5-bit codes". */
internal val FIXED_DISTANCE_TABLE: HuffmanTable by lazy {
    HuffmanTable.of(FIXED_DISTANCE_LENGTHS, "distance", 0, incompleteAllowed = false)
}

/** "bit 5 FDICT (preset dictionary)" of FLG (RFC 1950 §2.2). */
private const val FDICT = 0x20

/** "5 Bits: HLIT", "5 Bits: HDIST", "4 Bits: HCLEN" (§3.2.7). */
private const val HLIT_BITS = 5
private const val HDIST_BITS = 5
private const val HCLEN_BITS = 4

/** "# of Literal/Length codes - 257 (257 - 286)" (§3.2.7). */
private const val MIN_LITERAL_CODES = 257
private const val MAX_LITERAL_CODES = 286

/** "# of Code Length codes - 4 (4 - 19)" (§3.2.7). */
private const val MIN_CODE_LENGTH_CODES = 4

/** "These code lengths are interpreted as 3-bit integers (0-7)" (§3.2.7). */
private const val CODE_LENGTH_BITS = 3

/**
 * The code length alphabet of §3.2.7 — 0 to 15 for the lengths themselves and
 * three codes that repeat.
 */
private const val CODE_LENGTH_ALPHABET_SIZE = 19

/** "16: Copy the previous code length 3 - 6 times. The next 2 bits indicate repeat length". */
private const val CODE_LENGTH_REPEAT_PREVIOUS = 16
private const val REPEAT_PREVIOUS_EXTRA_BITS = 2
private const val MIN_REPEAT_PREVIOUS = 3

/** "17: Repeat a code length of 0 for 3 - 10 times. (3 bits of length)". */
private const val CODE_LENGTH_REPEAT_ZERO_SHORT = 17
private const val REPEAT_ZERO_SHORT_EXTRA_BITS = 3
private const val MIN_REPEAT_ZERO_SHORT = 3

/** "18: Repeat a code length of 0 for 11 - 138 times (7 bits of length)". */
private const val REPEAT_ZERO_LONG_EXTRA_BITS = 7
private const val MIN_REPEAT_ZERO_LONG = 11

/**
 * "code lengths for the code length alphabet … in the order: 16, 17, 18, 0, 8,
 * 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15" (§3.2.7).
 *
 * The order is what makes HCLEN worth having: the lengths a real block is least
 * likely to use — a code length of 15, of 1 — sit at the end, so the sequence
 * can be cut short there and the ones left out are zero.
 */
private val CODE_LENGTH_ORDER: IntArray =
    intArrayOf(16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15)

/**
 * [value] as eight hexadecimal digits, for a checksum — which is a bit pattern
 * and not a number, and which [adler32] returns as an [Int] that is negative
 * whenever `s2` is 32 768 or more.
 */
private fun hex(value: Int): String = "0x" + (value.toLong() and 0xFFFFFFFFL).toString(radix = 16).padStart(8, '0')
