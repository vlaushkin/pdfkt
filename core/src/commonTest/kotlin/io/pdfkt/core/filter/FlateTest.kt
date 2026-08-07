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
import io.pdfkt.core.obj.PdfInt
import io.pdfkt.core.obj.PdfName
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

// ISO 32000-2:2020 §7.4.4 "LZWDecode and FlateDecode filters"
// RFC 1950 §2.2 "Data format"
// RFC 1951 §3.1.1 "Packing into bytes", §3.2.2 "Use of Huffman coding in the
//   'deflate' format", §3.2.3 "Details of block format", §3.2.4
//   "Non-compressed blocks (BTYPE=00)", §3.2.5 "Compressed blocks (length and
//   distance codes)", §3.2.6 "Compression with fixed Huffman codes (BTYPE=01)"

/**
 * What the two RFCs require of the bytes [flateEncode] produces.
 *
 * RFC 1950 §2.2 gives the wrapper:
 *
 * > A zlib stream has the following structure:
 * >
 * >       0   1
 * >     +---+---+
 * >     |CMF|FLG|   (more-->)
 * >     +---+---+
 * >
 * >     +=====================+---+---+---+---+
 * >     |...compressed data...|    ADLER32    |
 * >     +=====================+---+---+---+---+
 *
 * > bits 0 to 3  CM     Compression method
 * > bits 4 to 7  CINFO  Compression info
 *
 * > bits 0 to 4  FCHECK  (check bits for CMF and FLG)
 * > bit  5       FDICT   (preset dictionary)
 * > bits 6 to 7  FLEVEL  (compression level)
 *
 * > The FCHECK value must be such that CMF and FLG, when viewed as a 16-bit
 * > unsigned integer stored in MSB order (CMF*256 + FLG), is a multiple of 31.
 *
 * > Adler-32 is composed of two sums accumulated per byte: s1 is the sum of all
 * > bytes, s2 is the sum of all s1 values. Both sums are done modulo 65521. s1
 * > is initialized to 1, s2 to zero. The Adler-32 checksum is stored as
 * > s2*65536 + s1 in most-significant-byte first (network) order.
 *
 * RFC 1951 gives the blocks. §3.2.3:
 *
 * > Each block of compressed data begins with 3 header bits containing the
 * > following data:
 * >
 * >     first bit       BFINAL
 * >     next 2 bits     BTYPE
 *
 * > BFINAL is set if and only if this is the last block of the data set.
 * > BTYPE specifies how the data are compressed, as follows:
 * >     00 - no compression
 * >     01 - compressed with fixed Huffman codes
 *
 * §3.2.4:
 *
 * > Any bits of input up to the next byte boundary are ignored. The rest of the
 * > block consists of the following information:
 * >
 * >       0   1   2   3   4...
 * >     +---+---+---+---+================================+
 * >     |  LEN  | NLEN  |... LEN bytes of literal data...|
 * >     +---+---+---+---+================================+
 * >
 * > LEN is the number of data bytes in the block. NLEN is the one's complement
 * > of LEN.
 *
 * And §3.1.1 fixes how the bits are laid out: "Data elements are packed into
 * bytes in order of increasing bit number within the byte", data elements
 * "starting with the least-significant bit of the data element" and Huffman
 * codes "starting with the most-significant bit of the code" — so a two-byte
 * LEN goes least significant byte first, the reverse of RFC 1950 §2.1, where
 * "All multi-byte numbers in the format described here are stored with the
 * MOST-significant byte first".
 *
 * The assertions are on bytes because that is what the RFCs specify; the file
 * tests in `jvmTest` add what bytes cannot show, that decompressors outside this
 * project read the result back.
 */
class FlateTest {
    /**
     * The header, byte for byte, and the arithmetic that makes it the header:
     * deflate with a 32K window, no preset dictionary, and a check value that
     * takes the pair to a multiple of 31.
     */
    @Test
    fun `the zlib header names deflate with a 32K window and checks to a multiple of 31`() {
        val header = flateEncode("anything at all".encodeToByteArray())
        val cmf = header[0].toInt() and 0xFF
        val flg = header[1].toInt() and 0xFF

        assertEquals(8, cmf and 0x0F, "CM: deflate")
        assertEquals(7, cmf shr 4, "CINFO: log2 of the window size minus eight")
        assertEquals(0, flg and 0x20, "FDICT: no preset dictionary")
        assertEquals(0, flg shr 6, "FLEVEL: fastest algorithm")
        assertEquals(0, (cmf * 256 + flg) % 31, "FCHECK: CMF*256 + FLG is a multiple of 31")
        // The one pair of bytes those rules admit for this method and level.
        assertEquals(0x78, cmf)
        assertEquals(0x01, flg)
    }

    /**
     * The codes of §3.2.6, generated from its code lengths by the algorithm of
     * §3.2.2 and compared against the codes the clause itself prints:
     *
     * > Lit Value    Bits        Codes
     * > ---------    ----        -----
     * >   0 - 143     8          00110000 through 10111111
     * > 144 - 255     9          110010000 through 111111111
     * > 256 - 279     7          0000000 through 0010111
     * > 280 - 287     8          11000000 through 11000111
     *
     * The clause says the lengths alone are "sufficient to determine the actual
     * codes" and shows the codes "for added clarity"; that redundancy is what
     * this test spends — the endpoints of all four ranges have to come out of
     * the generator, and every code in between has to be its predecessor plus
     * one, since "all codes of a given bit length have lexicographically
     * consecutive values, in the same order as the symbols they represent".
     */
    @Test
    fun `the fixed literal alphabet is the one the clause prints`() {
        assertEquals(0b00110000, FIXED_LITERAL_CODES[0], "literal 0 is 00110000")
        assertEquals(0b10111111, FIXED_LITERAL_CODES[143], "literal 143 is 10111111")
        assertEquals(0b110010000, FIXED_LITERAL_CODES[144], "literal 144 is 110010000")
        assertEquals(0b111111111, FIXED_LITERAL_CODES[255], "literal 255 is 111111111")
        assertEquals(0b0000000, FIXED_LITERAL_CODES[256], "end-of-block is 0000000")
        assertEquals(0b0010111, FIXED_LITERAL_CODES[279], "279 is 0010111")
        assertEquals(0b11000000, FIXED_LITERAL_CODES[280], "280 is 11000000")
        assertEquals(0b11000111, FIXED_LITERAL_CODES[287], "287 is 11000111")

        assertEquals(288, FIXED_LITERAL_CODES.size, "286 and 287 participate in the code construction")
        for (symbol in 1 until 288) {
            if (FIXED_LITERAL_LENGTHS[symbol] == FIXED_LITERAL_LENGTHS[symbol - 1]) {
                assertEquals(
                    FIXED_LITERAL_CODES[symbol - 1] + 1,
                    FIXED_LITERAL_CODES[symbol],
                    "codes of one length are consecutive, in the order of their symbols",
                )
            }
        }
    }

    /**
     * The other alphabet of §3.2.6: "distance codes 0-31 are represented by
     * (fixed-length) 5-bit codes". A code of a fixed length is the symbol
     * number itself, which is what the canonical construction yields when every
     * length is equal.
     */
    @Test
    fun `the fixed distance alphabet is the symbol numbers in five bits`() {
        assertEquals(32, FIXED_DISTANCE_CODES.size)
        for (symbol in 0 until 32) {
            assertEquals(5, FIXED_DISTANCE_LENGTHS[symbol])
            assertEquals(symbol, FIXED_DISTANCE_CODES[symbol])
        }
    }

    /**
     * The worked example of §3.2.2, which exists precisely so that a generator
     * can be checked against it:
     *
     * > Consider the alphabet ABCDEFGH, with bit lengths (3, 3, 3, 3, 3, 2, 4,
     * > 4).
     * >
     * > Symbol Length   Code
     * > ------ ------   ----
     * > A       3        010
     * > B       3        011
     * > C       3        100
     * > D       3        101
     * > E       3        110
     * > F       2         00
     * > G       4       1110
     * > H       4       1111
     */
    @Test
    fun `the canonical construction reproduces the worked example of the clause`() {
        val codes = canonicalCodes(intArrayOf(3, 3, 3, 3, 3, 2, 4, 4))

        assertContentEquals(
            intArrayOf(0b010, 0b011, 0b100, 0b101, 0b110, 0b00, 0b1110, 0b1111),
            codes,
        )
    }

    /**
     * The length and distance tables of §3.2.5, at the rows where they are
     * easiest to get wrong: the extra bits have to place every value of the
     * range the clause prints, and the two codes that overlap at 258 have to be
     * told apart.
     *
     * > 264   0    10       284   5   227-257      285   0    258
     */
    @Test
    fun `the length and distance codes cover the ranges the clause prints`() {
        assertEquals(257, lengthCode(3), "the shortest length the alphabet has")
        assertEquals(264, lengthCode(10), "the last length with no extra bits")
        assertEquals(265, lengthCode(11), "11,12 share a code and one extra bit")
        assertEquals(265, lengthCode(12))
        assertEquals(266, lengthCode(13))
        assertEquals(284, lengthCode(257), "284 reaches 257 and no further")
        assertEquals(285, lengthCode(258), "258 has a code of its own, with no extra bits")

        // Every length in 3..258 lands inside the range of the code chosen, and
        // the offset from the base fits the extra bits declared for it.
        for (length in 3..258) {
            val code = lengthCode(length)
            val extra = length - LENGTH_BASE[code - 257]
            assertTrue(extra >= 0 && extra < (1 shl LENGTH_EXTRA_BITS[code - 257]), "length $length under code $code")
        }

        assertEquals(0, distanceCode(1))
        assertEquals(3, distanceCode(4), "the last distance with no extra bits")
        assertEquals(4, distanceCode(5), "5,6 share a code and one extra bit")
        assertEquals(4, distanceCode(6))
        assertEquals(29, distanceCode(32768), "the largest distance the format allows")
        for (distance in 1..32768) {
            val code = distanceCode(distance)
            val extra = distance - DISTANCE_BASE[code]
            assertTrue(extra >= 0 && extra < (1 shl DISTANCE_EXTRA_BITS[code]), "distance $distance under code $code")
        }
    }

    /**
     * The empty input: a wrapper around one final, empty, fixed-Huffman block.
     * The decoding loop of §3.2.3 reads a block header before it can learn there
     * is nothing to read, so the empty deflate stream is two bytes rather than
     * none — three header bits and the end-of-block code.
     *
     * BFINAL = 1 is bit 0, BTYPE = 01 is a two-bit data element so its least
     * significant bit comes first, in bits 1 and 2, and the end-of-block code is
     * seven zero bits. The byte is therefore `0000 0011`, and the two bits that
     * spill into the next one are padded out.
     *
     * The checksum is that of no bytes at all — s1 as initialized, s2 zero.
     */
    @Test
    fun `an empty input is one empty final block between header and checksum`() {
        assertContentEquals(
            byteArrayOf(
                0x78, // CMF
                0x01, // FLG
                0x03, // BFINAL = 1, BTYPE = 01, then the first bits of code 256
                0x00, // the rest of code 256, and padding
                0x00, // ADLER32 = 1: s1 = 1, s2 = 0
                0x00,
                0x00,
                0x01,
            ),
            flateEncode(ByteArray(0)),
        )
    }

    /**
     * A three-byte input, in full, spelled out bit by bit from the clauses.
     *
     * "abc" has nothing to match, so the block is three literals and the
     * end-of-block code. Under §3.2.6 a literal below 144 is eight bits, code
     * `00110000` plus the literal's value, so 'a' = 97 is `10010001`, 'b' is
     * `10010010`, 'c' is `10010011`; §3.1.1 puts a Huffman code in most
     * significant bit first, into bytes filled from bit 0 up. Writing the bits
     * in the order they are produced,
     *
     *     1 10 10010001 10010010 10010011 0000000
     *     ^ ^  a        b        c        end of block
     *     | BTYPE = 01, least significant bit first
     *     BFINAL
     *
     * and cutting that stream into bytes from bit 0 gives `0100 1011`,
     * `0100 1100`, `0100 1010`, `0000 0110` — 34 bits padded to five bytes.
     *
     * A stored block would have been 64 bits: three header bits, five of
     * padding, four bytes of LEN and NLEN and the three bytes themselves. The
     * shorter one is what gets written.
     *
     * ADLER32 has s1 = 1 + 97 + 98 + 99 = 295 = 0x0127 and
     * s2 = 98 + 196 + 295 = 589 = 0x024D, stored as s2 then s1, most
     * significant byte first.
     */
    @Test
    fun `a short input is one final fixed-Huffman block of literals`() {
        assertContentEquals(
            byteArrayOf(
                0x78, 0x01,
                0x4B, 0x4C, 0x4A, 0x06, 0x00,
                0x02, 0x4D, 0x01, 0x27, // ADLER32 = s2 * 65536 + s1
            ),
            flateEncode("abc".encodeToByteArray()),
        )
    }

    /**
     * A match, in full. "aaaaaa" is one literal and then a pair, because
     * §3.2.3 lets the referenced string overlap the current position:
     *
     * > if the last 2 bytes decoded have values X and Y, a string reference with
     * > <length = 5, distance = 2> adds X,Y,X,Y,X to the output stream.
     *
     * So <length = 5, distance = 1> after one 'a' produces the other five.
     * Length 5 is code 259 with no extra bits (§3.2.5), which under §3.2.6 is
     * seven bits, `0000011`; distance 1 is code 0 with no extra bits, five bits,
     * `00000`. The bits in the order they are produced:
     *
     *     1 10 10010001 0000011 00000 0000000
     *     ^ ^  a        len 5   dist 1 end of block
     *     | BTYPE = 01
     *     BFINAL
     *
     * which is 30 bits, cut from bit 0 into `0100 1011`, `0000 0100`,
     * `0000 0011`, `0000 0000`. Six bytes have become four, and the pair is what
     * did it: six literals would have been 48 bits.
     *
     * ADLER32: s1 = 1 + 6 × 97 = 583 = 0x0247, and s2 accumulates
     * 98 + 195 + 292 + 389 + 486 + 583 = 2043 = 0x07FB.
     */
    @Test
    fun `a repeated byte becomes a literal and a length-distance pair`() {
        assertContentEquals(
            byteArrayOf(
                0x78, 0x01,
                0x4B, 0x04, 0x03, 0x00,
                0x07, 0xFB.toByte(), 0x02, 0x47,
            ),
            flateEncode("aaaaaa".encodeToByteArray()),
        )
    }

    /**
     * The acceptance in one line: repetitive data comes out smaller than it went
     * in, and by a wide margin. The corpus is a page content stream of the kind
     * this library writes, repeated — the redundancy an LZ77 stage exists to
     * find, at distances of hundreds of bytes.
     */
    @Test
    fun `repetitive data compresses to a fraction of its size`() {
        val corpus = contentStreamCorpus()

        val encoded = flateEncode(corpus)

        assertTrue(corpus.size > 20_000, "the corpus is large enough for the ratio to mean something")
        assertTrue(
            encoded.size < corpus.size / 10,
            "expected under a tenth of ${corpus.size} bytes, got ${encoded.size}",
        )
    }

    /**
     * Text, where the redundancy is real but not overwhelming: repeated words
     * and repeated substrings rather than repeated paragraphs. Compression that
     * only worked on exact repetition would pass the test above and fail this
     * one.
     */
    @Test
    fun `text compresses well below its uncompressed size`() {
        val sentences =
            "Every file implementing part of a format cites its clause. " +
                "The reference tool is the specification itself, and the source of truth " +
                "is the specification text. A format is implemented from its clause, not " +
                "from another implementation of the same clause.\n"
        val text = sentences.encodeToByteArray()
        val corpus = ByteArray(text.size * 4) { text[it % text.size] }

        val encoded = flateEncode(corpus)

        assertTrue(encoded.size < corpus.size / 2, "expected under half of ${corpus.size} bytes, got ${encoded.size}")
    }

    /**
     * Data with no redundancy, where the fixed alphabet would *cost* rather than
     * save: it spends nine bits on every byte of 144 or above, so a uniform
     * spread of byte values comes out about 5 % longer coded than stored. The
     * block falls back to BTYPE = 00 and the output stays at the bound §3.2.4
     * fixes — five bytes of block header per 65 535, plus the wrapper.
     */
    @Test
    fun `incompressible data falls back to stored blocks`() {
        val data = incompressible(MAX_BLOCK)

        val encoded = flateEncode(data)

        assertEquals(2 + 5 + data.size + 4, encoded.size, "header, one stored block header, the data, checksum")
        assertEquals(0x01, encoded[2].toInt() and 0xFF, "BFINAL = 1, BTYPE = 00, padding to the byte boundary")
        assertContentEquals(data, storedPayload(encoded), "the payload is the input, verbatim")
    }

    /**
     * LEN is two bytes wide, so incompressible input of more than 65 535 bytes
     * cannot be one stored block. It becomes as few as will hold it, each
     * declaring its own length, and only the last one sets BFINAL: a
     * decompressor that stopped at the first block would produce a prefix of the
     * data, which is exactly the silent loss the no-silent-loss policy rules out.
     *
     * The input is two full blocks and a partial one, all three of them
     * incompressible, and each block is checked where it lies — the size of the
     * whole is then fixed by the format, so the offsets are not searched for.
     * The tail is 40 000 bytes rather than a handful: a block of a few bytes is
     * cheaper coded than stored whatever it contains, since the padding and the
     * four bytes of LEN and NLEN cost more than the codes do.
     */
    @Test
    fun `incompressible input longer than one block is split and only the last block is final`() {
        val data = incompressible(2 * MAX_BLOCK + 40_000)

        val encoded = flateEncode(data)

        assertEquals(2 + 3 * 5 + data.size + 4, encoded.size, "header, three block headers, data, checksum")
        val lengths = mutableListOf<Int>()
        var at = 2
        while (at < encoded.size - 4) {
            val header = encoded[at].toInt() and 0xFF
            val len = (encoded[at + 1].toInt() and 0xFF) or ((encoded[at + 2].toInt() and 0xFF) shl 8)
            val nlen = (encoded[at + 3].toInt() and 0xFF) or ((encoded[at + 4].toInt() and 0xFF) shl 8)
            assertEquals(len.inv() and 0xFFFF, nlen, "NLEN is the one's complement of LEN")
            assertEquals(0x00, header and 0x06, "BTYPE = 00, no compression")
            assertEquals(0x00, header and 0xF8, "the bits up to the byte boundary are ignored, so zero")
            val last = at + 5 + len == encoded.size - 4
            assertEquals(if (last) 1 else 0, header and 0x01, "BFINAL on the last block only")
            lengths += len
            at += 5 + len
        }
        assertEquals(listOf(MAX_BLOCK, MAX_BLOCK, 40_000), lengths, "as few blocks as hold the data")
        assertContentEquals(data, storedPayload(encoded), "the payload is the input")
    }

    /**
     * The choice is taken per block, not once for the stream: a file that is
     * half incompressible samples and half repeated text gets a stored block for
     * the one and a coded block for the other, and comes out smaller than either
     * decision applied to the whole would have made it.
     */
    @Test
    fun `each block is stored or coded on its own`() {
        val noise = incompressible(MAX_BLOCK)
        val phrase = "compressible ".encodeToByteArray()
        val repetitive = ByteArray(MAX_BLOCK) { phrase[it % phrase.size] }
        val mixed = noise + repetitive

        val encoded = flateEncode(mixed)

        // The stored half is bounded below by its own size; the whole is barely
        // larger than that, so the second half cost almost nothing.
        assertTrue(encoded.size > MAX_BLOCK, "the incompressible half cannot shrink")
        assertTrue(
            encoded.size < MAX_BLOCK + MAX_BLOCK / 20,
            "the repetitive half should nearly vanish, got ${encoded.size} for ${mixed.size} bytes",
        )
    }

    /**
     * The checksum against its definition, on an input long enough that both
     * sums pass 65521 several times.
     *
     * The expectation is computed from the RFC's words rather than the way
     * [flateEncode] computes it: the sums are accumulated without reduction and
     * reduced once at the end, which is the same value — addition survives the
     * modulus — arrived at differently. `s1` after 70 000 bytes is at most 17.9
     * million and `s2` at most 625 billion, both far inside a [Long].
     */
    @Test
    fun `the trailing checksum is Adler-32 of the uncompressed data`() {
        val data = ByteArray(70_000) { (it * 37 % 253).toByte() }
        var s1 = 1L
        var s2 = 0L
        for (byte in data) {
            s1 += byte.toInt() and 0xFF
            s2 += s1
        }
        val expected = ((s2 % 65521L) shl 16) or (s1 % 65521L)

        val encoded = flateEncode(data)

        assertTrue(s1 > 65521L && s2 > 65521L, "the input is long enough to wrap both sums")
        assertEquals(expected, checksumOf(encoded), "s2 * 65536 + s1, modulo 65521 each")
    }

    /** The checksum covers the data, not the blocks it was cut into. */
    @Test
    fun `the checksum spans the whole input across block boundaries`() {
        val data = incompressible(MAX_BLOCK + 1000)

        assertEquals(checksumOf(flateEncode(data)), checksumOf(flateEncode(storedPayload(flateEncode(data)))))
    }

    /**
     * The stream factory: the `/Filter` entry §7.4.1 requires, the caller's own
     * entries beside it, and the encoded bytes as the stream's data. `/Length`
     * is the length of *those* bytes — "If the stream has a filter, Length shall
     * be the number of bytes of encoded data" (§7.3.8.2).
     */
    @Test
    fun `a flate stream declares the filter and holds the encoded bytes`() {
        val data = "0 0 1 rg\n100 100 200 300 re\nf\n".encodeToByteArray()

        val stream = flateStream(PdfDict(mapOf(PdfName("Type") to PdfName("XObject"))), data)

        assertContentEquals(flateEncode(data), stream.data)
        assertEquals(PdfName("FlateDecode"), stream.dict.entries[PdfName("Filter")])
        assertEquals(PdfName("XObject"), stream.dict.entries[PdfName("Type")])
        assertEquals(PdfInt(stream.data.size.toLong()), stream.dict.entries[PdfName("Length")])
    }

    /**
     * A caller who already declared a filter is building a pipeline, and where
     * in the order this one belongs is not a decision this function can take for
     * them.
     */
    @Test
    fun `a dictionary that already declares a filter is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            flateStream(PdfDict(mapOf(PdfName("Filter") to PdfName("ASCIIHexDecode"))), ByteArray(1))
        }
    }

    /** The concatenated payload of the stored blocks of a zlib stream. */
    private fun storedPayload(encoded: ByteArray): ByteArray {
        val payload = mutableListOf<Byte>()
        var at = 2
        while (at < encoded.size - 4) {
            val length = (encoded[at + 1].toInt() and 0xFF) or ((encoded[at + 2].toInt() and 0xFF) shl 8)
            for (i in 0 until length) payload += encoded[at + 5 + i]
            at += 5 + length
        }
        return payload.toByteArray()
    }

    /** The four trailing bytes as the value they spell, most significant first. */
    private fun checksumOf(encoded: ByteArray): Long {
        var value = 0L
        for (i in encoded.size - 4 until encoded.size) value = (value shl 8) or (encoded[i].toLong() and 0xFF)
        return value
    }
}

/** "LEN is the number of data bytes in the block", two bytes wide (§3.2.4). */
private const val MAX_BLOCK = 65535

/**
 * [size] bytes with no redundancy for either stage to find: byte values spread
 * over the whole range, in an order that repeats no three-byte sequence often
 * enough to pay for a pair.
 *
 * The generator is a linear congruential sequence with the multiplier and
 * increment of a full-period generator modulo 2^32; the high bits are the ones
 * taken, since the low bits of such a sequence have short periods. It is written
 * out here rather than taken from a library so that the corpus is the same on
 * every target and in every run — a compression ratio asserted against a corpus
 * that changed between runs would assert nothing.
 */
private fun incompressible(size: Int): ByteArray {
    var state = 0x2545F491
    return ByteArray(size) {
        state = state * 1664525 + 1013904223
        (state ushr 24).toByte()
    }
}

/**
 * A corpus of the kind this library actually writes: a page content stream that
 * draws a grid of rectangles and labels them, where operators, operands and
 * whole lines recur.
 */
private fun contentStreamCorpus(): ByteArray {
    val text = StringBuilder()
    for (row in 0 until 40) {
        for (column in 0 until 20) {
            text.append("q 1 0 0 1 ").append(column * 28).append(' ').append(row * 19).append(" cm\n")
            text.append("0.8 0.8 0.9 rg\n0 0 26 17 re\nf\n")
            text.append("BT /F1 9 Tf 2 5 Td (cell) Tj ET\n")
            text.append("Q\n")
        }
    }
    return text.toString().encodeToByteArray()
}
