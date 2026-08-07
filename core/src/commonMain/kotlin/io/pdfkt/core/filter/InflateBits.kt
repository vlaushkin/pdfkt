/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.filter

// RFC 1950 "ZLIB Compressed Data Format Specification version 3.3" §2.2 "Data format"
// RFC 1951 "DEFLATE Compressed Data Format Specification version 1.3"
//   §3.1.1 "Packing into bytes",
//   §3.2.2 "Use of Huffman coding in the 'deflate' format",
//   §3.2.3 "Details of block format",
//   §3.2.4 "Non-compressed blocks (BTYPE=00)"

// The machinery [flateDecode] reads a stream with, and the mirror of what
// Deflate.kt holds for the writing side: the bit stream of §3.1.1, the codes of
// §3.2.2 in the form a decoder needs them, and the output the bytes accumulate
// in. Inflate.kt holds what the format *says*; this file holds what saying it
// costs.

/**
 * A bit stream read the way §3.1.1 packs one.
 *
 * > * Data elements are packed into bytes in order of increasing bit number
 * >   within the byte, i.e., starting with the least-significant bit of the
 * >   byte.
 * > * Data elements other than Huffman codes are packed starting with the
 * >   least-significant bit of the data element.
 * > * Huffman codes are packed starting with the most-significant bit of the
 * >   code.
 *
 * The mirror of [BitSink], and split the same way: [readBits] for a data
 * element, which arrives least significant bit first and is assembled by
 * shifting each byte up into the buffer; [readBit] for one bit of a Huffman
 * code, which the caller shifts into a code from the top because that is the
 * end the code was written from.
 *
 * Bytes are pulled into [buffer] a whole byte at a time, so more bits may be
 * held than have been consumed; [offset] accounts for that, and is the position
 * of the byte the next unread bit lies in — what an error is reported at.
 */
internal class BitSource(private val data: ByteArray, from: Int) {
    private var at = from
    private var buffer = 0
    private var held = 0

    /** The byte the next unread bit lies in. */
    val offset: Int get() = at - (held + 7) / 8

    /** [count] bits as an integer, least significant bit first — a data element. */
    fun readBits(count: Int): Int {
        while (held < count) {
            if (at >= data.size) {
                throw FlateFormatException(
                    data.size,
                    "the stream ends with $held bit" + (if (held == 1) "" else "s") +
                        " left where $count more are needed",
                    RFC1951_PACKING,
                )
            }
            buffer = buffer or ((data[at++].toInt() and 0xFF) shl held)
            held += 8
        }
        val value = buffer and ((1 shl count) - 1)
        buffer = buffer ushr count
        held -= count
        return value
    }

    /** One bit, which is what a Huffman code is read a step at a time. */
    fun readBit(): Int = readBits(1)

    /** One whole byte; at a byte boundary, the byte itself. */
    fun readByte(): Int = readBits(8)

    /**
     * Discards the rest of the current byte — "any bits of input up to the next
     * byte boundary are ignored" (§3.2.4).
     *
     * Only the bits of the partially read byte go: whole bytes already pulled
     * into the buffer are unread input, not padding.
     */
    fun alignToByte() {
        val partial = held % 8
        buffer = buffer ushr partial
        held -= partial
    }
}

/**
 * The output of a decode: bytes appended one at a time, and stretches copied
 * back from what is already there.
 *
 * It cannot be a fixed array, because nothing in a deflate stream declares how
 * much data it decodes to; and it cannot be a [MutableList] of bytes, because
 * [copyBack] is most of the work of a decode and wants indexed bytes rather
 * than boxed ones.
 */
internal class ByteSink(initialCapacity: Int) {
    private var bytes = ByteArray(if (initialCapacity < 64) 64 else initialCapacity)

    /** How many bytes have been decoded — also the furthest back a distance may reach. */
    var size: Int = 0
        private set

    /** One byte, the low eight bits of [value]. */
    fun append(value: Int) {
        reserve(1)
        bytes[size++] = value.toByte()
    }

    /**
     * [length] bytes copied from [distance] bytes back (§3.2.3).
     *
     * One byte at a time, and that is not an oversight:
     *
     * > Note also that the referenced string may overlap the current position;
     * > for example, if the last 2 bytes decoded have values X and Y, a string
     * > reference with <length = 5, distance = 2> adds X,Y,X,Y,X to the output
     * > stream.
     *
     * So the source of the copy may run into bytes the copy itself is writing —
     * which is how a run of one byte is coded as <length, distance = 1> — and a
     * block move that read the source before writing the destination would
     * produce something else.
     */
    fun copyBack(distance: Int, length: Int) {
        reserve(length)
        var from = size - distance
        repeat(length) { bytes[size++] = bytes[from++] }
    }

    /** Everything decoded so far. */
    fun toByteArray(): ByteArray = bytes.copyOf(size)

    private fun reserve(count: Int) {
        if (size + count > bytes.size) {
            var capacity = bytes.size * 2
            while (capacity < size + count) capacity *= 2
            bytes = bytes.copyOf(capacity)
        }
    }
}

/**
 * One of the Huffman codes of §3.2.2, in the form a decoder needs: not the
 * codes themselves but the counts and the symbol order they are built from.
 *
 * The construction of §3.2.2 assigns to each symbol a code that can be read
 * back without ever building it. Its step 2,
 *
 * >     code = 0;
 * >     bl_count[0] = 0;
 * >     for (bits = 1; bits <= MAX_BITS; bits++) {
 * >         code = (code + bl_count[bits-1]) << 1;
 * >         next_code[bits] = code;
 * >     }
 *
 * makes `next_code[n]` the smallest code of length `n`; its step 3 hands out
 * consecutive values from there "in the same order as the symbols they
 * represent". So for a code of length `n` and value `c`, the symbol is the
 * `c - next_code[n]`-th of the symbols of that length, and both quantities can
 * be computed while the bits arrive: [decode] carries `first` (the clause's
 * `next_code`) alongside the code read so far and compares them at each length.
 * That is the construction run backwards, and it needs no table of codes and no
 * tree.
 *
 * @property alphabet what this codes, for the message when a code is not one of
 *   its own.
 */
internal class HuffmanTable private constructor(
    private val alphabet: String,
    private val countOfLength: IntArray,
    private val symbols: IntArray,
) {
    /** Whether the alphabet has no codes at all — "no distance codes used at all" (§3.2.7). */
    val isEmpty: Boolean get() = symbols.isEmpty()

    /** The symbol the next code in [source] stands for. */
    fun decode(source: BitSource): Int {
        val offset = source.offset
        var code = 0
        // "Shorter codes lexicographically precede longer codes", so the code
        // read so far can only be a code of the length read so far, and one
        // comparison per bit decides it.
        var first = 0
        var index = 0
        for (length in 1..MAX_CODE_BITS) {
            code = code or source.readBit()
            val count = countOfLength[length]
            if (code - first < count) return symbols[index + code - first]
            index += count
            first = (first + count) shl 1
            code = code shl 1
        }
        throw FlateFormatException(
            offset,
            "$MAX_CODE_BITS bits that are no code of the $alphabet alphabet",
            RFC1951_HUFFMAN_CODING,
        )
    }

    internal companion object {
        /**
         * The code the given [lengths] define, "zero meaning no code" — or a
         * [FlateFormatException] at [offset] if they define none.
         *
         * Two ways for that to happen, and the Kraft sum is what tells them
         * apart. Reading §3.2.2's rules as arithmetic, a code of length `n`
         * claims a `2^-n` share of the space of codes; `left` here is that space
         * scaled to `2^bits`, so it falls below zero exactly when the lengths
         * have claimed more codes than exist — two symbols would share one code,
         * and a decoder handed that code could not say which was meant.
         *
         * Ending above zero is the other way: some sequence of bits is a prefix
         * of no code at all, so the alphabet cannot decode everything that can
         * be written. §3.2.7 requires exactly two such codes to be accepted, and
         * only for distances — a single code, of one bit or of none — so
         * [incompleteAllowed] admits an alphabet of at most one symbol and
         * nothing else. Where the surplus is real, refusing it here rather than
         * at the bit that falls into the gap is what makes the offset in the
         * message the offset of the table, which is where the fault is.
         */
        fun of(lengths: IntArray, alphabet: String, offset: Int, incompleteAllowed: Boolean): HuffmanTable {
            val countOfLength = IntArray(MAX_CODE_BITS + 1)
            for (length in lengths) {
                require(length in 0..MAX_CODE_BITS) { "a code length is 0 to $MAX_CODE_BITS (RFC 1951 §3.2.7)" }
                if (length != 0) countOfLength[length]++
            }

            var left = 1
            for (bits in 1..MAX_CODE_BITS) {
                left = (left shl 1) - countOfLength[bits]
                if (left < 0) {
                    throw FlateFormatException(
                        offset,
                        "the $alphabet code lengths claim more codes of $bits bits than a prefix code has: " +
                            "the alphabet is over-subscribed",
                        RFC1951_HUFFMAN_CODING,
                    )
                }
            }

            var total = 0
            val startOfLength = IntArray(MAX_CODE_BITS + 1)
            for (bits in 1..MAX_CODE_BITS) {
                startOfLength[bits] = total
                total += countOfLength[bits]
            }
            if (left > 0 && !(incompleteAllowed && total <= 1)) {
                throw FlateFormatException(
                    offset,
                    "the $alphabet code lengths do not fill the code space, so some sequence of bits " +
                        "decodes to no symbol at all: the alphabet is incomplete",
                    RFC1951_HUFFMAN_CODING,
                )
            }

            // "consecutive values for all codes of the same length … in the same
            // order as the symbols they represent": sorted by length, then by
            // symbol, which is the order this loop visits them in.
            val symbols = IntArray(total)
            val cursor = startOfLength.copyOf()
            for (symbol in lengths.indices) {
                val length = lengths[symbol]
                if (length != 0) symbols[cursor[length]++] = symbol
            }
            return HuffmanTable(alphabet, countOfLength, symbols)
        }
    }
}

/**
 * The longest code any of the three alphabets can have.
 *
 * Not a limit chosen here: the code length alphabet of §3.2.7 "represent[s]
 * code lengths of 0 - 15", so no dynamic code is longer, and the fixed
 * alphabet's longest code is nine bits.
 */
internal const val MAX_CODE_BITS = 15

/**
 * The clauses the messages of [FlateFormatException] cite.
 *
 * Kept together, and beside the code that raises: a message naming a clause
 * that does not say what the message says is worse than one naming none.
 */
internal const val RFC1950_DATA_FORMAT = "RFC 1950 §2.2"
internal const val RFC1951_PACKING = "RFC 1951 §3.1.1"
internal const val RFC1951_HUFFMAN_CODING = "RFC 1951 §3.2.2"
internal const val RFC1951_BLOCK_FORMAT = "RFC 1951 §3.2.3"
internal const val RFC1951_STORED_BLOCK = "RFC 1951 §3.2.4"
internal const val RFC1951_LENGTH_AND_DISTANCE = "RFC 1951 §3.2.5"
internal const val RFC1951_DYNAMIC_BLOCK = "RFC 1951 §3.2.7"
