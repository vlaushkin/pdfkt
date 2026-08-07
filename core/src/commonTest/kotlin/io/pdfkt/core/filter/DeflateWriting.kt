/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.filter

// RFC 1950 §2.2 "Data format"
// RFC 1951 §3.1.1 "Packing into bytes", §3.2.2 "Use of Huffman coding in the
//   'deflate' format", §3.2.3 "Details of block format",
//   §3.2.5 "Compressed blocks (length and distance codes)",
//   §3.2.7 "Compression with dynamic Huffman codes (BTYPE=10)"

/**
 * Streams written by hand, for the decoder to be tested against.
 *
 * [flateEncode] writes stored and fixed-Huffman blocks and nothing else, so a
 * decoder tested only against its output would have its whole §3.2.7 path
 * untested — and §3.3 is explicit that a decoder does not get to implement a
 * subset: "a compliant decompressor must accept the full range of possible
 * values defined in the previous section". These functions write the values the
 * encoder never does: dynamic alphabets, the code length repeat codes, a single
 * distance code, and the malformed variants of each.
 *
 * They write bits through [BitSink], which is the encoder's, so the packing
 * rules of §3.1.1 are not restated here. What is restated is §3.2.7, spelled
 * out as the block layout it prints; the JVM tests hand these same streams to
 * `java.util.zip.Inflater`, so a mistake in this file shows up as an
 * independent decompressor rejecting the block rather than as two of our own
 * mistakes cancelling out.
 */

/** The bytes [build] writes, padded to a whole number of bytes. */
internal fun deflate(build: (BitSink) -> Unit): ByteArray {
    val sink = BitSink(64)
    build(sink)
    return sink.toByteArray()
}

/**
 * [deflated] inside the zlib wrapper of RFC 1950 §2.2, with the Adler-32 of
 * [data] — the bytes the deflate data is supposed to decode to.
 *
 * The header is the one pair of bytes CM = 8, CINFO = 7, FDICT = 0 and
 * FLEVEL = 0 admit under the FCHECK rule; the same one [flateEncode] writes,
 * and `FlateTest` derives it there from the clause.
 */
internal fun zlib(deflated: ByteArray, data: ByteArray): ByteArray {
    val checksum = adler32(data)
    return byteArrayOf(0x78, 0x01) + deflated +
        byteArrayOf(
            (checksum ushr 24).toByte(),
            (checksum ushr 16).toByte(),
            (checksum ushr 8).toByte(),
            checksum.toByte(),
        )
}

/** A literal or a `<length, distance>` pair, packed the way [Lz77] packs one. */
internal fun literal(byte: Int): Int = byte

internal fun match(length: Int, distance: Int): Int = MATCH_MARK or (distance shl MATCH_LENGTH_BITS) or length

/**
 * One symbol of the code length alphabet of §3.2.7, with the extra bits that
 * follow it and how many code lengths it stands for.
 *
 * @property count the lengths this token emits — 1 for a length given
 *   literally, 3 to 138 for one of the repeat codes. It is what lets a test
 *   assert *where* a repeat falls, in particular that one of them crosses from
 *   the literal/length lengths into the distance lengths.
 */
internal class CodeLengthToken(val symbol: Int, val extra: Int, val extraBits: Int, val count: Int)

/**
 * [lengths] — the concatenated literal/length and distance code lengths — as
 * the symbols that spell them (§3.2.7).
 *
 * > 0 - 15: Represent code lengths of 0 - 15
 * >     16: Copy the previous code length 3 - 6 times.
 * >         The next 2 bits indicate repeat length (0 = 3, ... , 3 = 6)
 * >     17: Repeat a code length of 0 for 3 - 10 times. (3 bits of length)
 * >     18: Repeat a code length of 0 for 11 - 138 times (7 bits of length)
 *
 * With [repeats] false every length is written out as itself, which is the
 * simplest sequence a conforming block can carry. With it true the runs are
 * folded into 16, 17 and 18 — longest code first, since 18 reaches further than
 * 17 and 17 further than a bare zero.
 */
internal fun codeLengthTokens(lengths: IntArray, repeats: Boolean): List<CodeLengthToken> {
    val tokens = mutableListOf<CodeLengthToken>()
    var at = 0
    while (at < lengths.size) {
        val value = lengths[at]
        var run = 1
        while (at + run < lengths.size && lengths[at + run] == value) run++
        if (!repeats) {
            tokens += CodeLengthToken(value, 0, 0, 1)
            at++
            continue
        }
        if (value != 0) {
            // 16 copies the *previous* length, so the first of a run is always
            // written out as itself.
            tokens += CodeLengthToken(value, 0, 0, 1)
            at++
            run--
            while (run >= 3) {
                val count = if (run < 6) run else 6
                tokens += CodeLengthToken(16, count - 3, 2, count)
                at += count
                run -= count
            }
        } else {
            while (run >= 11) {
                val count = if (run < 138) run else 138
                tokens += CodeLengthToken(18, count - 11, 7, count)
                at += count
                run -= count
            }
            while (run >= 3) {
                val count = if (run < 10) run else 10
                tokens += CodeLengthToken(17, count - 3, 3, count)
                at += count
                run -= count
            }
        }
        while (run > 0) {
            tokens += CodeLengthToken(value, 0, 0, 1)
            at++
            run--
        }
    }
    return tokens
}

/**
 * A block with BTYPE=10 (§3.2.7), final if [last]:
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
 * >
 * > The actual compressed data of the block, encoded using the literal/length
 * >    and distance Huffman codes
 * >
 * > The literal/length symbol 256 (end of data), encoded using the
 * >    literal/length Huffman code
 *
 * [codeLengths] spells the concatenated [literalLengths] and [distanceLengths]
 * and is passed in rather than derived, so that a test can decide which repeat
 * codes appear — and so that a test of a *malformed* block can pass a sequence
 * that runs past the end or begins with a copy of a length that is not there.
 * Nothing else is malformable from here: the alphabets are whatever
 * [literalLengths] and [distanceLengths] say, valid or not.
 */
internal fun writeDynamicBlock(
    sink: BitSink,
    literalLengths: IntArray,
    distanceLengths: IntArray,
    codeLengths: List<CodeLengthToken>,
    symbols: List<Int>,
    last: Boolean = true,
) {
    sink.writeBits(if (last) 1 else 0, 1)
    sink.writeBits(2, 2)
    sink.writeBits(literalLengths.size - 257, 5)
    sink.writeBits(distanceLengths.size - 1, 5)
    sink.writeBits(CODE_LENGTH_ALPHABET.size - 4, 4)

    // A code for the code length alphabet that is complete whatever the block
    // carries: thirteen codes of four bits and six of five, which is
    // 13/16 + 6/32 = 1 exactly, so every one of the nineteen symbols has a code
    // and no test needs to build a Huffman tree to say something about §3.2.7.
    val codeLengthLengths = IntArray(CODE_LENGTH_ALPHABET.size) { if (it < 13) 4 else 5 }
    val codeLengthCodes = canonicalCodes(codeLengthLengths)
    for (symbol in CODE_LENGTH_ALPHABET) sink.writeBits(codeLengthLengths[symbol], 3)
    for (token in codeLengths) {
        sink.writeCode(codeLengthCodes[token.symbol], codeLengthLengths[token.symbol])
        sink.writeBits(token.extra, token.extraBits)
    }

    val literalCodes = canonicalCodes(literalLengths)
    val distanceCodes = canonicalCodes(distanceLengths)
    for (token in symbols) {
        if (token < MATCH_MARK) {
            sink.writeCode(literalCodes[token], literalLengths[token])
            continue
        }
        val length = token and MATCH_LENGTH_MASK
        val distance = (token ushr MATCH_LENGTH_BITS) and MATCH_DISTANCE_MASK
        val lengthSymbol = lengthCode(length)
        val lengthIndex = lengthSymbol - LITERAL_LENGTH_CODE_BASE
        sink.writeCode(literalCodes[lengthSymbol], literalLengths[lengthSymbol])
        sink.writeBits(length - LENGTH_BASE[lengthIndex], LENGTH_EXTRA_BITS[lengthIndex])
        val distanceSymbol = distanceCode(distance)
        sink.writeCode(distanceCodes[distanceSymbol], distanceLengths[distanceSymbol])
        sink.writeBits(distance - DISTANCE_BASE[distanceSymbol], DISTANCE_EXTRA_BITS[distanceSymbol])
    }
    sink.writeCode(literalCodes[END_OF_BLOCK], literalLengths[END_OF_BLOCK])
}

/**
 * "the order: 16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15"
 * (§3.2.7), written out here independently of the decoder's copy — a test that
 * read the order from the code it tests would agree with any order at all.
 */
internal val CODE_LENGTH_ALPHABET: IntArray =
    intArrayOf(16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15)

/** The literal/length alphabet, "0..285", padded to the 286 lengths HLIT may declare. */
internal const val LITERAL_ALPHABET_SIZE = 286

/**
 * "the distance is drawn from (1..32,768)" (§3.2.5) — thirty codes.
 *
 * HDIST may declare up to 32 of them, since §3.2.7 gives its range as "1 - 32",
 * and the two beyond the thirtieth are the ones that "will never actually occur
 * in the compressed data" (§3.2.6). Blocks written here declare thirty: a block
 * declaring the two dead codes as well is legal and pointless, and decoders
 * exist that refuse it.
 */
internal const val DISTANCE_ALPHABET_SIZE = 30
