/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.filter

// RFC 1951 "DEFLATE Compressed Data Format Specification version 1.3"
//   §3.1.1 "Packing into bytes",
//   §3.2.2 "Use of Huffman coding in the 'deflate' format",
//   §3.2.3 "Details of block format",
//   §3.2.4 "Non-compressed blocks (BTYPE=00)",
//   §3.2.5 "Compressed blocks (length and distance codes)",
//   §3.2.6 "Compression with fixed Huffman codes (BTYPE=01)"

/**
 * The bit stream a deflate block is written into, with the packing rules of
 * §3.1.1 built in.
 *
 * > * Data elements are packed into bytes in order of increasing bit number
 * >   within the byte, i.e., starting with the least-significant bit of the
 * >   byte.
 * > * Data elements other than Huffman codes are packed starting with the
 * >   least-significant bit of the data element.
 * > * Huffman codes are packed starting with the most-significant bit of the
 * >   code.
 *
 * So there are two ways to write a run of bits and they are opposites:
 * [writeBits] for everything the format calls a data element — BFINAL, BTYPE,
 * the extra bits of a length or a distance — and [writeCode] for a Huffman
 * code, which goes in reversed. The distinction is not cosmetic: a decoder
 * walks the code tree bit by bit as the bits arrive, so a code written the
 * other way round decodes to a different symbol.
 */
internal class BitSink(initialCapacity: Int) {
    private var bytes = ByteArray(if (initialCapacity < 16) 16 else initialCapacity)
    private var size = 0

    /** The bits written since the last byte boundary, in bits 0 to [bitCount] - 1. */
    private var partial = 0
    private var bitCount = 0

    /** How many bits have been written in all, the incomplete byte included. */
    val bits: Long get() = size.toLong() * 8 + bitCount

    /** Whether the next byte written would start at a byte boundary. */
    val aligned: Boolean get() = bitCount == 0

    /** [count] bits of [value], least significant first — a data element. */
    fun writeBits(value: Int, count: Int) {
        partial = partial or ((value and ((1 shl count) - 1)) shl bitCount)
        bitCount += count
        while (bitCount >= 8) {
            append(partial.toByte())
            partial = partial ushr 8
            bitCount -= 8
        }
    }

    /**
     * A Huffman [code] of [length] bits, most significant first.
     *
     * The reversal is done here rather than by the caller because a code is
     * defined by §3.2.2 as an integer "intended to be read from most- to
     * least-significant bit", and that is the form the tables in this file
     * hold; reversing at the point of writing keeps the tables comparable with
     * the ones the clause prints.
     */
    fun writeCode(code: Int, length: Int) {
        var remaining = length
        while (remaining > 0) {
            remaining--
            writeBits((code ushr remaining) and 1, 1)
        }
    }

    /**
     * Discards the rest of the current byte — "any bits of input up to the next
     * byte boundary are ignored" (§3.2.4), which on the writing side means
     * padding them with zeroes.
     */
    fun alignToByte() {
        if (bitCount != 0) {
            append(partial.toByte())
            partial = 0
            bitCount = 0
        }
    }

    /** One byte, which at a byte boundary is the byte itself. */
    fun writeByte(value: Int): Unit = writeBits(value and 0xFF, 8)

    /** `src[from until to]` copied in whole bytes; only valid at a byte boundary. */
    fun writeBytes(src: ByteArray, from: Int, to: Int) {
        require(aligned) { "a byte run has to start at a byte boundary (RFC 1951 §3.1.1)" }
        val length = to - from
        reserve(length)
        src.copyInto(bytes, size, from, to)
        size += length
    }

    /** Everything written, padded to a whole number of bytes. */
    fun toByteArray(): ByteArray {
        alignToByte()
        return bytes.copyOf(size)
    }

    private fun append(byte: Byte) {
        reserve(1)
        bytes[size++] = byte
    }

    private fun reserve(count: Int) {
        if (size + count > bytes.size) {
            var capacity = bytes.size * 2
            while (capacity < size + count) capacity *= 2
            bytes = bytes.copyOf(capacity)
        }
    }
}

/**
 * The Huffman codes of an alphabet whose symbols have the given code
 * [lengths], zero meaning "no code" (§3.2.2).
 *
 * > Given this rule, we can define the Huffman code for an alphabet just by
 * > giving the bit lengths of the codes for each symbol of the alphabet in
 * > order; this is sufficient to determine the actual codes.
 *
 * The three steps of the clause, in its own terms:
 *
 * > 1) Count the number of codes for each code length. Let bl_count[N] be the
 * >    number of codes of length N, N >= 1.
 * > 2) Find the numerical value of the smallest code for each code length:
 * >        code = 0;
 * >        bl_count[0] = 0;
 * >        for (bits = 1; bits <= MAX_BITS; bits++) {
 * >            code = (code + bl_count[bits-1]) << 1;
 * >            next_code[bits] = code;
 * >        }
 * > 3) Assign numerical values to all codes, using consecutive values for all
 * >    codes of the same length with the base values determined at step 2.
 * >    Codes that are never used (which have a bit length of zero) must not be
 * >    assigned a value.
 *
 * The result is the code as an integer read from most- to least-significant
 * bit, so a code of length 3 and value 2 is `010` and not `10`.
 */
internal fun canonicalCodes(lengths: IntArray): IntArray {
    var maxBits = 0
    for (length in lengths) if (length > maxBits) maxBits = length
    val countOfLength = IntArray(maxBits + 1)
    for (length in lengths) if (length != 0) countOfLength[length]++

    val smallestOfLength = IntArray(maxBits + 1)
    var code = 0
    for (bits in 1..maxBits) {
        code = (code + countOfLength[bits - 1]) shl 1
        smallestOfLength[bits] = code
    }

    val codes = IntArray(lengths.size)
    for (symbol in lengths.indices) {
        val length = lengths[symbol]
        if (length != 0) codes[symbol] = smallestOfLength[length]++
    }
    return codes
}

/**
 * The code lengths of the literal/length alphabet under BTYPE=01 (§3.2.6):
 *
 * > Lit Value    Bits        Codes
 * > ---------    ----        -----
 * >   0 - 143     8          00110000 through 10111111
 * > 144 - 255     9          110010000 through 111111111
 * > 256 - 279     7          0000000 through 0010111
 * > 280 - 287     8          11000000 through 11000111
 *
 * > Literal/length values 286-287 will never actually occur in the compressed
 * > data, but participate in the code construction.
 *
 * Which is why the array runs to 287 and not to 285: leaving the two unused
 * symbols out would shift every code of length 8 and produce a different,
 * wrong, alphabet.
 */
internal val FIXED_LITERAL_LENGTHS: IntArray =
    IntArray(288) {
        when {
            it <= 143 -> 8
            it <= 255 -> 9
            it <= 279 -> 7
            else -> 8
        }
    }

/** The literal/length codes those lengths determine (§3.2.2, §3.2.6). */
internal val FIXED_LITERAL_CODES: IntArray = canonicalCodes(FIXED_LITERAL_LENGTHS)

/**
 * > Distance codes 0-31 are represented by (fixed-length) 5-bit codes, with
 * > possible additional bits as shown in the table shown in Paragraph 3.2.5,
 * > above. Note that distance codes 30-31 will never actually occur in the
 * > compressed data.
 *
 * A fixed-length code is the canonical code of an alphabet whose lengths are
 * all equal, so this needs no separate treatment: the codes come out as the
 * symbol numbers themselves, five bits wide.
 */
internal val FIXED_DISTANCE_LENGTHS: IntArray = IntArray(32) { 5 }

/** The distance codes those lengths determine. */
internal val FIXED_DISTANCE_CODES: IntArray = canonicalCodes(FIXED_DISTANCE_LENGTHS)

/**
 * The smallest length each of the codes 257 to 285 stands for, and how many
 * extra bits follow it (§3.2.5).
 *
 * >      Extra               Extra               Extra
 * > Code Bits Length(s) Code Bits Lengths   Code Bits Length(s)
 * >  257   0     3       267   1   15,16     277   4   67-82
 * >  258   0     4       268   1   17,18     278   4   83-98
 * >  259   0     5       269   2   19-22     279   4   99-114
 * >  260   0     6       270   2   23-26     280   4  115-130
 * >  261   0     7       271   2   27-30     281   5  131-162
 * >  262   0     8       272   2   31-34     282   5  163-194
 * >  263   0     9       273   3   35-42     283   5  195-226
 * >  264   0    10       274   3   43-50     284   5  227-257
 * >  265   1  11,12      275   3   51-58     285   0    258
 * >  266   1  13,14      276   3   59-66
 */
internal val LENGTH_BASE: IntArray =
    intArrayOf(
        3, 4, 5, 6, 7, 8, 9, 10, 11, 13, 15, 17, 19, 23, 27, 31, 35, 43, 51, 59,
        67, 83, 99, 115, 131, 163, 195, 227, 258,
    )

/** The "Extra Bits" column of the length table above, for codes 257 to 285. */
internal val LENGTH_EXTRA_BITS: IntArray =
    intArrayOf(
        0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3,
        4, 4, 4, 4, 5, 5, 5, 5, 0,
    )

/**
 * The smallest distance each of the codes 0 to 29 stands for (§3.2.5).
 *
 * >  Extra           Extra               Extra
 * > Code Bits Dist  Code Bits   Dist     Code Bits Distance
 * >   0   0    1     10   4     33-48    20    9   1025-1536
 * >   1   0    2     11   4     49-64    21    9   1537-2048
 * >   2   0    3     12   5     65-96    22   10   2049-3072
 * >   3   0    4     13   5     97-128   23   10   3073-4096
 * >   4   1   5,6    14   6    129-192   24   11   4097-6144
 * >   5   1   7,8    15   6    193-256   25   11   6145-8192
 * >   6   2   9-12   16   7    257-384   26   12  8193-12288
 * >   7   2  13-16   17   7    385-512   27   12 12289-16384
 * >   8   3  17-24   18   8    513-768   28   13 16385-24576
 * >   9   3  25-32   19   8   769-1024   29   13 24577-32768
 */
internal val DISTANCE_BASE: IntArray =
    intArrayOf(
        1, 2, 3, 4, 5, 7, 9, 13, 17, 25, 33, 49, 65, 97, 129, 193, 257, 385, 513, 769,
        1025, 1537, 2049, 3073, 4097, 6145, 8193, 12289, 16385, 24577,
    )

/** The "Extra Bits" column of the distance table above, for codes 0 to 29. */
internal val DISTANCE_EXTRA_BITS: IntArray =
    intArrayOf(
        0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 8, 8,
        9, 9, 10, 10, 11, 11, 12, 12, 13, 13,
    )

/**
 * The literal/length code that carries [length], which is in 3..258.
 *
 * The tables of §3.2.5 overlap at one value: code 284 has five extra bits over
 * a base of 227, which would reach 258, but 258 is what code 285 stands for on
 * its own. The clause resolves it by printing 284 as "227-257" and 285 as
 * "258", so the search runs from the top and the higher code wins the overlap.
 */
internal fun lengthCode(length: Int): Int {
    var index = LENGTH_BASE.size - 1
    while (LENGTH_BASE[index] > length) index--
    return LITERAL_LENGTH_CODE_BASE + index
}

/** The distance code that carries [distance], which is in 1..32768 (§3.2.5). */
internal fun distanceCode(distance: Int): Int {
    var index = DISTANCE_BASE.size - 1
    while (DISTANCE_BASE[index] > distance) index--
    return index
}

/**
 * The LZ77 stage: input bytes turned into the symbols of §3.2.5, which are
 *
 * > either literal bytes, from the alphabet of byte values (0..255), or
 * > <length, backward distance> pairs, where the length is drawn from (3..258)
 * > and the distance is drawn from (1..32,768).
 *
 * A match is looked for by keeping, for every three-byte sequence seen so far,
 * the chain of positions where it started: [head] holds the most recent
 * position for a hash of those three bytes and [previous] links each position
 * to the one before it with the same hash. Three is the shortest length the
 * alphabet can express, so a shorter agreement is of no use and need not be
 * indexed.
 *
 * The chain is walked newest-first and cut off at [MAX_CHAIN_LENGTH] entries.
 * Both the cut-off and the choice of the longest match at the current position
 * rather than a search over the whole input are free of consequences for
 * correctness: any sequence of literals and pairs that reproduces the input
 * decodes to the input, and the encoder settles for a good one instead of the
 * best one. The one thing that would be a defect is emitting a pair the
 * alphabet cannot express, so the length is capped at [MAX_MATCH_LENGTH] and
 * the distance at [MAX_DISTANCE].
 *
 * Matches may point into data that an earlier block already emitted — "a
 * duplicated string reference may refer to a string in a previous block"
 * (§3.2.3) — so the index is not reset between blocks, and the state of this
 * object is what carries that across.
 */
internal class Lz77(private val data: ByteArray) {
    private val head = IntArray(HASH_SIZE) { NO_POSITION }
    private val previous = IntArray(WINDOW_SIZE)

    /**
     * Encodes `data[from until to]` into [out], returning how many symbols were
     * written. [out] has to hold `to - from` entries: an input of only literals
     * produces one symbol per byte.
     *
     * Matches never reach past [to] even though the format would allow it,
     * which keeps a block's symbols and its share of the input the same thing —
     * what the caller needs to weigh this block against a stored one.
     */
    fun tokenize(from: Int, to: Int, out: IntArray): Int {
        var count = 0
        var at = from
        while (at < to) {
            var bestLength = 0
            var bestDistance = 0
            if (to - at >= MIN_MATCH_LENGTH) {
                val longest = if (to - at < MAX_MATCH_LENGTH) to - at else MAX_MATCH_LENGTH
                var candidate = head[hashAt(at)]
                var chain = MAX_CHAIN_LENGTH
                while (candidate != NO_POSITION && chain-- > 0) {
                    val distance = at - candidate
                    if (distance > MAX_DISTANCE) break
                    var length = 0
                    while (length < longest && data[candidate + length] == data[at + length]) length++
                    if (length > bestLength) {
                        bestLength = length
                        bestDistance = distance
                        if (length == longest) break
                    }
                    candidate = previous[candidate and WINDOW_MASK]
                }
            }
            if (bestLength >= MIN_MATCH_LENGTH) {
                out[count++] = MATCH_MARK or (bestDistance shl MATCH_LENGTH_BITS) or bestLength
                repeat(bestLength) { index(at++) }
            } else {
                out[count++] = data[at].toInt() and 0xFF
                index(at++)
            }
        }
        return count
    }

    /** Records [at] as the newest start of the three bytes that begin there. */
    private fun index(at: Int) {
        if (at + MIN_MATCH_LENGTH <= data.size) {
            val hash = hashAt(at)
            previous[at and WINDOW_MASK] = head[hash]
            head[hash] = at
        }
    }

    /**
     * A hash of the three bytes at [at], spreading them over the table so that
     * chains stay short. Nothing in the format depends on the function: a
     * collision costs a wasted comparison and a miss costs a missed match, and
     * both are matters of ratio, not of validity.
     */
    private fun hashAt(at: Int): Int {
        val first = data[at].toInt() and 0xFF
        val second = data[at + 1].toInt() and 0xFF
        val third = data[at + 2].toInt() and 0xFF
        return ((first shl 10) xor (second shl 5) xor third) and (HASH_SIZE - 1)
    }
}

/**
 * How many bits the symbols `tokens[0 until count]` occupy as a fixed-Huffman
 * block, the three header bits and the end-of-block code included.
 *
 * The point of counting before writing is the choice §3.2.3 leaves open: a
 * block may be stored or compressed, and for data with no redundancy the fixed
 * alphabet costs more than the bytes themselves — literals 144 to 255 take
 * nine bits each. Measuring both and taking the shorter makes that the
 * encoder's decision instead of a guess.
 */
internal fun fixedBlockBits(tokens: IntArray, count: Int): Long {
    var bits = BLOCK_HEADER_BITS.toLong() + FIXED_LITERAL_LENGTHS[END_OF_BLOCK]
    for (position in 0 until count) {
        val token = tokens[position]
        bits +=
            if (token < MATCH_MARK) {
                FIXED_LITERAL_LENGTHS[token]
            } else {
                val length = lengthCode(token and MATCH_LENGTH_MASK)
                val distance = distanceCode((token ushr MATCH_LENGTH_BITS) and MATCH_DISTANCE_MASK)
                FIXED_LITERAL_LENGTHS[length] + LENGTH_EXTRA_BITS[length - LITERAL_LENGTH_CODE_BASE] +
                    FIXED_DISTANCE_LENGTHS[distance] + DISTANCE_EXTRA_BITS[distance]
            }
    }
    return bits
}

/**
 * How many bits a stored block of [length] bytes would occupy when the stream
 * stands at [bitsWritten].
 *
 * A stored block is the only one whose cost depends on where it begins: its
 * three header bits are followed by padding to the byte boundary (§3.2.4), so
 * between one and eight bits are spent on nothing.
 */
internal fun storedBlockBits(bitsWritten: Long, length: Int): Long {
    val afterHeader = bitsWritten + BLOCK_HEADER_BITS
    val padded = (afterHeader + 7) / 8 * 8
    return padded - bitsWritten + STORED_BLOCK_LENGTH_BITS + length.toLong() * 8
}

/**
 * Writes `tokens[0 until count]` as a block with BTYPE=01 (§3.2.6), final if
 * [last].
 *
 * Each symbol is the Huffman code of §3.2.6 followed, for a length or a
 * distance, by the extra bits of §3.2.5 holding the offset from the code's
 * base value. Extra bits are not Huffman codes, so §3.1.1 packs them least
 * significant bit first like any other data element.
 */
internal fun writeFixedBlock(sink: BitSink, tokens: IntArray, count: Int, last: Boolean) {
    sink.writeBits(if (last) 1 else 0, 1)
    sink.writeBits(FIXED_HUFFMAN_BLOCK, BLOCK_TYPE_BITS)
    for (position in 0 until count) {
        val token = tokens[position]
        if (token < MATCH_MARK) {
            sink.writeCode(FIXED_LITERAL_CODES[token], FIXED_LITERAL_LENGTHS[token])
        } else {
            val length = token and MATCH_LENGTH_MASK
            val distance = (token ushr MATCH_LENGTH_BITS) and MATCH_DISTANCE_MASK
            val lengthSymbol = lengthCode(length)
            val lengthIndex = lengthSymbol - LITERAL_LENGTH_CODE_BASE
            sink.writeCode(FIXED_LITERAL_CODES[lengthSymbol], FIXED_LITERAL_LENGTHS[lengthSymbol])
            sink.writeBits(length - LENGTH_BASE[lengthIndex], LENGTH_EXTRA_BITS[lengthIndex])
            val distanceSymbol = distanceCode(distance)
            sink.writeCode(FIXED_DISTANCE_CODES[distanceSymbol], FIXED_DISTANCE_LENGTHS[distanceSymbol])
            sink.writeBits(distance - DISTANCE_BASE[distanceSymbol], DISTANCE_EXTRA_BITS[distanceSymbol])
        }
    }
    // "if value = end of block (256) break from loop" (§3.2.3) — a compressed
    // block is delimited by this symbol and by nothing else.
    sink.writeCode(FIXED_LITERAL_CODES[END_OF_BLOCK], FIXED_LITERAL_LENGTHS[END_OF_BLOCK])
}

/**
 * Writes `data[from until to]` as a block with BTYPE=00 (§3.2.4), final if
 * [last].
 *
 * > Any bits of input up to the next byte boundary are ignored. The rest of
 * > the block consists of the following information:
 * >
 * >      0   1   2   3   4...
 * >    +---+---+---+---+================================+
 * >    |  LEN  | NLEN  |... LEN bytes of literal data...|
 * >    +---+---+---+---+================================+
 * >
 * > LEN is the number of data bytes in the block. NLEN is the one's complement
 * > of LEN.
 *
 * LEN and NLEN are data elements, so §3.1.1 puts their least significant byte
 * first — the opposite of the order RFC 1950 §2.1 fixes for the numbers of the
 * zlib wrapper around them.
 */
internal fun writeStoredBlock(sink: BitSink, data: ByteArray, from: Int, to: Int, last: Boolean) {
    val length = to - from
    require(length <= MAX_STORED_LENGTH) { "LEN is two bytes wide, so a stored block holds at most 65535 bytes" }
    sink.writeBits(if (last) 1 else 0, 1)
    sink.writeBits(STORED_BLOCK, BLOCK_TYPE_BITS)
    sink.alignToByte()
    sink.writeByte(length)
    sink.writeByte(length ushr 8)
    sink.writeByte(length.inv())
    sink.writeByte(length.inv() ushr 8)
    sink.writeBytes(data, from, to)
}

/** "first bit BFINAL, next 2 bits BTYPE" (§3.2.3). */
internal const val BLOCK_TYPE_BITS = 2
internal const val BLOCK_HEADER_BITS = 1 + BLOCK_TYPE_BITS

/** "00 - no compression" (§3.2.3). */
internal const val STORED_BLOCK = 0

/** "01 - compressed with fixed Huffman codes" (§3.2.3). */
internal const val FIXED_HUFFMAN_BLOCK = 1

/**
 * "10 - compressed with dynamic Huffman codes" (§3.2.3).
 *
 * No encoder here writes one: the alphabets of such a block have to be built
 * from the symbol frequencies and then transmitted (§3.2.7), which pays for
 * itself only over a block long enough to amortise the table. The decoder has
 * no such choice — "a compliant decompressor must accept the full range of
 * possible values defined in the previous section" (§3.3) — so the constant
 * lives on this side and is read, never written.
 */
internal const val DYNAMIC_HUFFMAN_BLOCK = 2

/** LEN and NLEN, two bytes each (§3.2.4). */
private const val STORED_BLOCK_LENGTH_BITS = 32

/**
 * "LEN is the number of data bytes in the block", two bytes wide, so a stored
 * block holds at most this many bytes and longer input becomes several blocks.
 */
internal const val MAX_STORED_LENGTH = 65535

/** "the value 256 indicates end-of-block" (§3.2.5). */
internal const val END_OF_BLOCK = 256

/** "values 257..285 represent length codes" (§3.2.5). */
internal const val LITERAL_LENGTH_CODE_BASE = 257

/**
 * How a symbol is packed into the [Int] that carries it between the LZ77 stage
 * and the writer: a literal is its own byte value, 0 to 255, and a pair is
 * [MATCH_MARK] above the distance and the length. Nine bits hold a length of
 * at most 258 and sixteen hold a distance of at most 32 768, which is why the
 * mark sits at bit 25.
 */
internal const val MATCH_LENGTH_BITS = 9
internal const val MATCH_LENGTH_MASK = (1 shl MATCH_LENGTH_BITS) - 1
internal const val MATCH_DISTANCE_MASK = 0xFFFF
internal const val MATCH_MARK = 1 shl 25

/** "the length is drawn from (3..258)" (§3.2.5). */
internal const val MIN_MATCH_LENGTH = 3
internal const val MAX_MATCH_LENGTH = 258

/** "the distance is drawn from (1..32,768)" (§3.2.5). */
internal const val MAX_DISTANCE = 32768

/**
 * The sliding window the match index covers. It equals [MAX_DISTANCE], so a
 * position's slot is reused exactly when that position has fallen out of
 * reach.
 */
private const val WINDOW_SIZE = MAX_DISTANCE
private const val WINDOW_MASK = WINDOW_SIZE - 1

/** The match index: a power of two, so the hash is masked rather than divided. */
private const val HASH_SIZE = 1 shl 15

/** No position — the chains start empty, and position 0 is a real position. */
private const val NO_POSITION = -1

/**
 * How many positions of one chain are examined before the search gives up.
 * Deeper searches find longer matches and take longer; the bound is what keeps
 * the time linear in the input for data whose three-byte sequences repeat
 * often, such as a long run of one byte.
 */
private const val MAX_CHAIN_LENGTH = 128
