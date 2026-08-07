/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.filter

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
//   distance codes)", §3.2.6 "Compression with fixed Huffman codes (BTYPE=01)",
//   §3.2.7 "Compression with dynamic Huffman codes (BTYPE=10)", §3.3 "Compliance"

/**
 * What RFC 1950 and RFC 1951 require of a decoder.
 *
 * The tests fall into three groups, and the middle one is the reason this file
 * is long. Round trips against [flateEncode] cover the two block types this
 * library writes and nothing else; §3.3 asks for more:
 *
 * > A compliant decompressor must accept the full range of possible values
 * > defined in the previous section, and must accept blocks of arbitrary size.
 *
 * So the dynamic Huffman blocks of §3.2.7 — which nothing here writes — are
 * built by hand, bit by bit, from the layout the clause prints, together with
 * the two incomplete distance codes it requires a decoder to take and the code
 * length repeat codes that can "cross from HLIT + 257 to the HDIST + 1 code
 * lengths". `InflateForeignTest` hands those same hand-built blocks to a
 * decompressor outside this project, so a shared misreading of §3.2.7 between
 * this file's writer and the decoder cannot pass unnoticed.
 *
 * The third group is malformed input, asserted on the offset and the words of
 * the message rather than only on "it threw": a decoder that raises the same
 * exception at position zero for every kind of damage tells a caller nothing
 * about which byte of a 4 MB stream to look at.
 */
class InflateTest {
    /**
     * The acceptance, in one direction: everything [flateEncode] writes comes
     * back byte for byte.
     *
     * The sizes are the boundaries of §3.2.4 — a block holds at most 65 535
     * bytes, so a stream around and past that is several blocks, and only the
     * last carries BFINAL. The shapes are the ones the encoder decides between:
     * repetitive data becomes fixed-Huffman blocks full of matches,
     * incompressible data becomes stored blocks, and mixed data becomes one of
     * each, with matches reaching across the boundary between them.
     */
    @Test
    fun `every stream this library encodes decodes back to what went in`() {
        for (size in listOf(0, 1, 2, 3, 17, 255, 256, 1000, 65_534, 65_535, 65_536, 131_072, 200_000)) {
            for ((shape, data) in corpora(size)) {
                assertContentEquals(data, flateDecode(flateEncode(data)), "$shape, $size bytes")
            }
        }
    }

    /**
     * The three streams `FlateTest` computes bit by bit from §3.2.6 and RFC 1950
     * §2.2, read back. Those bytes are asserted there against the clauses; here
     * they are the input, so a decoder that agreed with this one but not with
     * the clause would have to disagree with the arithmetic written out there.
     */
    @Test
    fun `the streams the clauses spell out byte by byte decode to their data`() {
        assertContentEquals(
            ByteArray(0),
            flateDecode(byteArrayOf(0x78, 0x01, 0x03, 0x00, 0x00, 0x00, 0x00, 0x01)),
            "one empty final block",
        )
        assertContentEquals(
            "abc".encodeToByteArray(),
            flateDecode(byteArrayOf(0x78, 0x01, 0x4B, 0x4C, 0x4A, 0x06, 0x00, 0x02, 0x4D, 0x01, 0x27)),
            "three literals under the fixed alphabet",
        )
        assertContentEquals(
            "aaaaaa".encodeToByteArray(),
            flateDecode(byteArrayOf(0x78, 0x01, 0x4B, 0x04, 0x03, 0x00, 0x07, 0xFB.toByte(), 0x02, 0x47)),
            "a literal and a length-distance pair",
        )
    }

    /**
     * The example §3.2.3 gives for a match that overlaps the position it is
     * copied to:
     *
     * > Note also that the referenced string may overlap the current position;
     * > for example, if the last 2 bytes decoded have values X and Y, a string
     * > reference with <length = 5, distance = 2> adds X,Y,X,Y,X to the output
     * > stream.
     *
     * Copying the five bytes as a block — reading the source before writing the
     * destination — would give X,Y,X,Y and then two bytes of whatever was there,
     * which is why the clause spells the result out.
     */
    @Test
    fun `a match that overlaps the current position repeats what it has just written`() {
        val expected = "XYXYXYX".encodeToByteArray()
        val deflated =
            deflate { sink ->
                writeFixedBlock(sink, intArrayOf(literal('X'.code), literal('Y'.code), match(5, 2)), 3, last = true)
            }

        assertContentEquals(expected, flateDecode(zlib(deflated, expected)))
    }

    /**
     * A match that reaches into an earlier block, and a block boundary between
     * two different block types:
     *
     * > Note that a duplicated string reference may refer to a string in a
     * > previous block; i.e., the backward distance may cross one or more block
     * > boundaries.
     *
     * The first block is stored, so its bytes never went through an alphabet at
     * all; the second is a fixed-Huffman block whose only symbol is a pair
     * pointing back into it. A decoder that reset its window per block, or that
     * treated BFINAL as "the stream ends at the first block", returns half of
     * this.
     */
    @Test
    fun `a match reaches back into a previous block of another type`() {
        val expected = "hello hello ".encodeToByteArray()
        val deflated =
            deflate { sink ->
                writeStoredBlock(sink, expected, 0, 6, last = false)
                writeFixedBlock(sink, intArrayOf(match(6, 6)), 1, last = true)
            }

        assertContentEquals(expected, flateDecode(zlib(deflated, expected)))
    }

    /**
     * A block with BTYPE=10, the alphabets carried in its own head (§3.2.7).
     *
     * The code lengths are chosen so that the Kraft sum is exactly one —
     * `1/2 + 3 × 1/8 + 2 × 1/16` — because §3.2.2's construction defines a code
     * only for lengths that fill the code space, and they are written out one
     * per length, with none of the repeat codes, so this test isolates the
     * plainest sequence a conforming block can carry.
     *
     * The distance alphabet is the other case §3.2.7 names: "one distance code
     * of zero bits means that there are no distance codes used at all (the data
     * is all literals)", which is a code with no codes in it — something the
     * completeness rule would otherwise reject.
     */
    @Test
    fun `a dynamic block decodes with the alphabets it carries`() {
        val text = "abracadabra"
        val literalLengths = IntArray(257)
        literalLengths['a'.code] = 1
        literalLengths['b'.code] = 3
        literalLengths['r'.code] = 3
        literalLengths[END_OF_BLOCK] = 3
        literalLengths['c'.code] = 4
        literalLengths['d'.code] = 4
        val distanceLengths = IntArray(1)
        val lengths = literalLengths + distanceLengths

        val deflated =
            deflate { sink ->
                writeDynamicBlock(
                    sink,
                    literalLengths,
                    distanceLengths,
                    codeLengthTokens(lengths, repeats = false),
                    text.map { literal(it.code) },
                )
            }

        assertEquals(0b101, deflated[0].toInt() and 0b111, "BFINAL = 1 and BTYPE = 10, least significant bit first")
        assertContentEquals(text.encodeToByteArray(), flateDecode(zlib(deflated, text.encodeToByteArray())))
    }

    /**
     * The same block format with everything §3.2.7 adds to it: the three repeat
     * codes, a repeat that crosses from the literal/length lengths into the
     * distance lengths, and a distance alphabet of exactly one code.
     *
     * > The code length repeat codes can cross from HLIT + 257 to the HDIST + 1
     * > code lengths. In other words, all code lengths form a single sequence of
     * > HLIT + HDIST + 258 values.
     *
     * The alphabets are laid out so that the crossing is unavoidable: the
     * literal lengths end in 23 zeroes (nothing between 263 and 285 is used) and
     * the distance lengths begin with five, so the run of 28 that a code 18
     * folds them into begins in one alphabet and ends in the other. A decoder
     * that read the two alphabets' lengths separately would stop the run at 286
     * and place every distance length five symbols early — and would then find
     * an alphabet that still decodes, just to different bytes.
     *
     * > If only one distance code is used, it is encoded using one bit, not zero
     * > bits; in this case there is a single code length of one, with one unused
     * > code.
     *
     * That is the distance alphabet here: one code, of one bit, for the only
     * distance the block uses. It leaves half the code space unassigned, so a
     * decoder that insisted on completeness for every alphabet would reject a
     * block the clause requires it to take.
     */
    @Test
    fun `a dynamic block uses the repeat codes and a repeat crosses into the distance lengths`() {
        val expected = "abcdefghabcdefgh".encodeToByteArray()
        val literalLengths = IntArray(LITERAL_ALPHABET_SIZE)
        for (letter in 'a'..'h') literalLengths[letter.code] = 4
        literalLengths[END_OF_BLOCK] = 2
        literalLengths[lengthCode(8)] = 2
        val distanceLengths = IntArray(DISTANCE_ALPHABET_SIZE)
        distanceLengths[distanceCode(8)] = 1
        val tokens = codeLengthTokens(literalLengths + distanceLengths, repeats = true)

        // What the sequence is made of, before it is written: all three repeat
        // codes, and one repeat spanning the boundary between the alphabets.
        val used = tokens.map { it.symbol }.toSet()
        assertTrue(16 in used, "code 16 copies the previous code length: the eight letters share a length")
        assertTrue(17 in used, "code 17 repeats a zero 3 to 10 times: the five unused length codes")
        assertTrue(18 in used, "code 18 repeats a zero 11 to 138 times: the unused halves of the alphabet")
        var at = 0
        var crossings = 0
        for (token in tokens) {
            if (at < LITERAL_ALPHABET_SIZE && at + token.count > LITERAL_ALPHABET_SIZE) crossings++
            at += token.count
        }
        assertEquals(LITERAL_ALPHABET_SIZE + DISTANCE_ALPHABET_SIZE, at, "the tokens spell every code length")
        assertEquals(1, crossings, "one repeat runs from the literal/length lengths into the distance lengths")

        val deflated =
            deflate { sink ->
                writeDynamicBlock(
                    sink,
                    literalLengths,
                    distanceLengths,
                    tokens,
                    ('a'..'h').map { literal(it.code) } + match(8, 8),
                )
            }

        assertContentEquals(expected, flateDecode(zlib(deflated, expected)))
    }

    /**
     * The far end of HDIST: "5 Bits: HDIST, # of Distance codes - 1 (1 - 32)"
     * (§3.2.7), where the last two of the thirty-two are the codes 30 and 31
     * that "will never actually occur in the compressed data" (§3.2.6).
     *
     * A block may still declare them — the range the clause prints goes to 32 —
     * and a decoder that took 30 as the limit would refuse a stream the format
     * allows. Nothing is lost by taking it: an unused code length is a zero, and
     * a block that actually coded one of those symbols is refused where the
     * symbol is decoded rather than where it is declared.
     */
    @Test
    fun `a block declaring the two distance codes that never occur is still a block`() {
        val expected = "abcdefghabcdefgh".encodeToByteArray()
        val literalLengths = IntArray(LITERAL_ALPHABET_SIZE)
        for (letter in 'a'..'h') literalLengths[letter.code] = 4
        literalLengths[END_OF_BLOCK] = 2
        literalLengths[lengthCode(8)] = 2
        val distanceLengths = IntArray(32)
        distanceLengths[distanceCode(8)] = 1

        val deflated =
            deflate { sink ->
                writeDynamicBlock(
                    sink,
                    literalLengths,
                    distanceLengths,
                    codeLengthTokens(literalLengths + distanceLengths, repeats = true),
                    ('a'..'h').map { literal(it.code) } + match(8, 8),
                )
            }

        assertContentEquals(expected, flateDecode(zlib(deflated, expected)))
    }

    /**
     * Bytes after the checksum are not the decoder's business: "any data which
     * may appear after ADLER32 are not part of the zlib stream" (RFC 1950 §2.2).
     *
     * This is not a hypothetical. A PDF stream's `/Length` counts the bytes
     * between `stream` and `endstream`, and §7.3.8.1 allows an end-of-line
     * marker there that is not part of the data — so a reader handing this
     * function what the `/Length` delimits may well be handing it two bytes more
     * than the filter wrote.
     */
    @Test
    fun `bytes after the checksum are not part of the stream`() {
        val data = "0 0 1 rg\n".encodeToByteArray()

        assertContentEquals(data, flateDecode(flateEncode(data) + "\r\n".encodeToByteArray()))
    }

    /**
     * The wrapper's own rules, each broken on its own, each reported at the byte
     * that breaks it (RFC 1950 §2.2). CMF is byte 0 and FLG is byte 1, so an
     * offset that pointed at the wrong one of the two would be visible here.
     */
    @Test
    fun `the zlib header is checked field by field`() {
        val tooShort = assertFailsWith<FlateFormatException> { flateDecode(byteArrayOf(0x78)) }
        assertEquals(1, tooShort.offset, "the stream ends where CMF and FLG should be")
        assertEquals(0, assertFailsWith<FlateFormatException> { flateDecode(ByteArray(0)) }.offset)

        // CM = 9: "CM = 8 denotes the 'deflate' compression method"; nothing else
        // is defined, and CM is the low four bits of byte 0.
        val method = assertFailsWith<FlateFormatException> { flateDecode(byteArrayOf(0x79, 0x00, 0x03, 0x00)) }
        assertEquals(0, method.offset)
        assertTrue("CM is 9" in method.message!!, method.message!!)

        // CINFO = 8: "values of CINFO above 7 are not allowed".
        val window = assertFailsWith<FlateFormatException> { flateDecode(byteArrayOf(0x88.toByte(), 0x00, 0x03)) }
        assertEquals(0, window.offset)
        assertTrue("CINFO is 8" in window.message!!, window.message!!)

        // CMF*256 + FLG must be a multiple of 31, and 0x7802 is not.
        val check = assertFailsWith<FlateFormatException> { flateDecode(byteArrayOf(0x78, 0x02, 0x03, 0x00)) }
        assertEquals(1, check.offset)
        assertTrue("FCHECK" in check.message!!, check.message!!)

        // FDICT set, with the check bits still correct — the dictionary the data
        // was compressed against is not in the stream and cannot be recovered.
        val dictionary = assertFailsWith<FlateFormatException> { flateDecode(byteArrayOf(0x78, 0x20, 0x03, 0x00)) }
        assertEquals(1, dictionary.offset)
        assertTrue("FDICT" in dictionary.message!!, dictionary.message!!)
    }

    /**
     * "11 - reserved (error)" (§3.2.3) — the one BTYPE with no meaning, reported
     * at the byte the block header begins in.
     *
     * The header is three bits at the start of byte 2: BFINAL is bit 0 and BTYPE
     * is bits 1 and 2, least significant first, so `0000 0111` is a final block
     * of the reserved type.
     */
    @Test
    fun `the reserved block type is refused`() {
        val reserved = assertFailsWith<FlateFormatException> { flateDecode(byteArrayOf(0x78, 0x01, 0x07, 0x00)) }

        assertEquals(2, reserved.offset)
        assertTrue("BTYPE is 11" in reserved.message!!, reserved.message!!)
        assertEquals("RFC 1951 §3.2.3", reserved.clause)
    }

    /**
     * "NLEN is the one's complement of LEN" (§3.2.4), and it is checked. It
     * carries nothing a decoder needs — LEN alone says how many bytes follow —
     * so the only use two bytes of every stored block can be put to is this one.
     *
     * The block header occupies three bits of byte 2 and the rest of that byte
     * is padding, so LEN begins at byte 3, which is where the fault is reported.
     */
    @Test
    fun `a stored block whose NLEN is not the complement of its LEN is refused`() {
        val data = "abc".encodeToByteArray()
        val stored = byteArrayOf(0x78, 0x01, 0x01, 0x03, 0x00, 0xFF.toByte(), 0xFF.toByte()) + data
        val checksum = adler32(data)
        val trailer =
            byteArrayOf(
                (checksum ushr 24).toByte(),
                (checksum ushr 16).toByte(),
                (checksum ushr 8).toByte(),
                checksum.toByte(),
            )

        val broken = assertFailsWith<FlateFormatException> { flateDecode(stored + trailer) }

        assertEquals(3, broken.offset, "LEN begins at byte 3, after the padded block header")
        assertTrue("NLEN" in broken.message!!, broken.message!!)
        assertEquals("RFC 1951 §3.2.4", broken.clause)
        // The same block with the complement right is a stream, which is what
        // makes the assertion above about NLEN and not about something else.
        val whole = stored.copyOf()
        whole[5] = 0xFC.toByte()
        assertContentEquals(data, flateDecode(whole + trailer))
    }

    /**
     * A stream that stops early stops decoding: no prefix of the data comes
     * back, at any cut.
     *
     * Every truncation of a valid stream is tried, and each has to raise. The
     * cut may fall in the header, inside a stored block's payload, in the middle
     * of a Huffman code, or between the last block and its checksum, and the
     * three parts of the format that could paper over it — a block that ends at
     * the end of the input, a Huffman code that runs out of bits, a checksum
     * that is not there — each have to refuse.
     */
    @Test
    fun `every truncation of a valid stream is refused`() {
        for ((shape, data) in corpora(3000)) {
            val encoded = flateEncode(data)
            for (cut in 0 until encoded.size) {
                val failure = assertFailsWith<FlateFormatException>("$shape cut to $cut bytes") {
                    flateDecode(encoded.copyOf(cut))
                }
                assertTrue(failure.offset <= cut, "the offset is inside the stream of $cut bytes, not $failure")
            }
        }
    }

    /**
     * "However a distance cannot refer past the beginning of the output stream"
     * (§3.2.3). The block here decodes one literal and then asks for five bytes
     * from five back, which are not there.
     *
     * Where the fault is reported is worth the arithmetic: the block header is
     * three bits, the literal 'a' is eight under the fixed alphabet (§3.2.6),
     * and the length code for 3 is seven with no extra bits, so the distance
     * code begins at bit 18 of the deflate data — byte 2 of it, byte 4 of the
     * stream.
     */
    @Test
    fun `a distance reaching past the beginning of the output is refused`() {
        val deflated = deflate { sink -> writeFixedBlock(sink, intArrayOf(literal('a'.code), match(3, 5)), 2, true) }

        val failure = assertFailsWith<FlateFormatException> { flateDecode(zlib(deflated, ByteArray(0))) }

        assertEquals(4, failure.offset, "the distance code begins at bit 18 of the deflate data")
        assertTrue("reaches past the beginning" in failure.message!!, failure.message!!)
    }

    /**
     * The symbols that exist in an alphabet and stand for nothing: 286 and 287,
     * which "will never actually occur in the compressed data, but participate
     * in the code construction" (§3.2.6), and the distance codes 30 and 31, of
     * which the clause says the same.
     *
     * They are reachable — the fixed alphabet gives all four a code — so a
     * decoder that indexed the length or distance tables with them would read
     * past the end of an array rather than say what is wrong.
     */
    @Test
    fun `the symbols that stand for nothing are refused`() {
        val literalStream =
            deflate { sink ->
                sink.writeBits(1, 1)
                sink.writeBits(FIXED_HUFFMAN_BLOCK, 2)
                sink.writeCode(FIXED_LITERAL_CODES[286], FIXED_LITERAL_LENGTHS[286])
            }
        val beyondTheLengths = assertFailsWith<FlateFormatException> { flateDecode(zlib(literalStream, ByteArray(0))) }
        assertEquals(2, beyondTheLengths.offset, "the symbol begins at bit 3 of the deflate data")
        assertTrue("286" in beyondTheLengths.message!!, beyondTheLengths.message!!)

        val distanceStream =
            deflate { sink ->
                sink.writeBits(1, 1)
                sink.writeBits(FIXED_HUFFMAN_BLOCK, 2)
                sink.writeCode(FIXED_LITERAL_CODES['a'.code], FIXED_LITERAL_LENGTHS['a'.code])
                sink.writeCode(FIXED_LITERAL_CODES[257], FIXED_LITERAL_LENGTHS[257])
                sink.writeCode(FIXED_DISTANCE_CODES[30], FIXED_DISTANCE_LENGTHS[30])
            }
        val beyondTheDistances =
            assertFailsWith<FlateFormatException> { flateDecode(zlib(distanceStream, ByteArray(0))) }
        assertEquals(4, beyondTheDistances.offset, "the distance code begins at bit 18 of the deflate data")
        assertTrue("30" in beyondTheDistances.message!!, beyondTheDistances.message!!)
    }

    /**
     * A dynamic block whose code lengths do not define a code (§3.2.2).
     *
     * Two ways to fail, and they are opposite. Three codes of one bit ask for
     * more codes than one bit has, so two symbols would share a code and no
     * decoder could tell them apart. A single code of one bit for the
     * literal/length alphabet asks for fewer, so half of every sequence of bits
     * decodes to nothing — which the clause allows only for a distance
     * alphabet, and this is not one.
     *
     * Both are refused where the alphabet is, not at the bit that later falls
     * into the gap: an over-subscribed table has no first wrong bit at all.
     */
    @Test
    fun `code lengths that define no code are refused`() {
        val overSubscribed = IntArray(257)
        overSubscribed[0] = 1
        overSubscribed[1] = 1
        overSubscribed[2] = 1
        val over = assertFailsWith<FlateFormatException> { flateDecode(dynamicStream(overSubscribed)) }
        assertTrue("over-subscribed" in over.message!!, over.message!!)
        assertEquals("RFC 1951 §3.2.2", over.clause)
        assertTrue(over.offset >= ZLIB_HEADER_SIZE, "the offset points into the deflate data")

        val incomplete = IntArray(257)
        incomplete[END_OF_BLOCK] = 1
        val gap = assertFailsWith<FlateFormatException> { flateDecode(dynamicStream(incomplete)) }
        assertTrue("incomplete" in gap.message!!, gap.message!!)
        assertTrue("literal/length" in gap.message!!, gap.message!!)
    }

    /**
     * The repeat codes of §3.2.7 asked to do what they cannot: copy a length
     * that does not exist yet, and write more lengths than the block declares.
     *
     * > 16: Copy the previous code length 3 - 6 times.
     * > 18: Repeat a code length of 0 for 11 - 138 times
     *
     * The first token of the sequence has no previous length to copy — the
     * sequence is "HLIT + 257 code lengths" and a copy of the one before the
     * first is a copy of nothing. And a block declaring 258 lengths cannot
     * receive 276: the surplus has nowhere to go, and a decoder that let it run
     * would be writing past the alphabets it is filling in.
     */
    @Test
    fun `a repeat with nothing to repeat or nowhere to put it is refused`() {
        val lengths = IntArray(257)
        lengths[END_OF_BLOCK] = 1
        lengths[0] = 1
        val distances = IntArray(1)

        val orphan =
            assertFailsWith<FlateFormatException> {
                flateDecode(
                    zlib(
                        deflate { sink ->
                            writeDynamicBlock(
                                sink,
                                lengths,
                                distances,
                                listOf(CodeLengthToken(16, 0, 2, 3)),
                                emptyList(),
                            )
                        },
                        ByteArray(0),
                    ),
                )
            }
        assertTrue("previous code length" in orphan.message!!, orphan.message!!)
        assertEquals("RFC 1951 §3.2.7", orphan.clause)

        val overrun =
            assertFailsWith<FlateFormatException> {
                flateDecode(
                    zlib(
                        deflate { sink ->
                            writeDynamicBlock(
                                sink,
                                lengths,
                                distances,
                                listOf(CodeLengthToken(18, 127, 7, 138), CodeLengthToken(18, 127, 7, 138)),
                                emptyList(),
                            )
                        },
                        ByteArray(0),
                    ),
                )
            }
        assertTrue("runs past" in overrun.message!!, overrun.message!!)
    }

    /**
     * HLIT declares "# of Literal/Length codes - 257 (257 - 286)" in five bits,
     * which reach 288. The alphabet has 286 symbols; a block claiming more is
     * claiming code lengths for symbols that do not exist.
     */
    @Test
    fun `a block declaring more literal codes than the alphabet has is refused`() {
        val lengths = IntArray(288)
        lengths[END_OF_BLOCK] = 1
        lengths[0] = 1

        val failure =
            assertFailsWith<FlateFormatException> {
                flateDecode(
                    zlib(
                        deflate { sink ->
                            writeDynamicBlock(
                                sink,
                                lengths,
                                IntArray(1),
                                codeLengthTokens(lengths + IntArray(1), repeats = true),
                                emptyList(),
                            )
                        },
                        ByteArray(0),
                    ),
                )
            }

        assertTrue("HLIT declares 288" in failure.message!!, failure.message!!)
        assertEquals(ZLIB_HEADER_SIZE, failure.offset, "HLIT is the first field after the block header")
    }

    /**
     * A length code in a block whose distance alphabet is empty: "one distance
     * code of zero bits means that there are no distance codes used at all (the
     * data is all literals)" (§3.2.7), and this block's data is not.
     */
    @Test
    fun `a length in a block with no distance codes is refused`() {
        val lengths = IntArray(lengthCode(3) + 1)
        lengths[END_OF_BLOCK] = 1
        lengths[lengthCode(3)] = 1

        val failure =
            assertFailsWith<FlateFormatException> {
                flateDecode(
                    zlib(
                        deflate { sink ->
                            writeDynamicBlock(
                                sink,
                                lengths,
                                IntArray(1),
                                codeLengthTokens(lengths + IntArray(1), repeats = true),
                                listOf(match(3, 1)),
                            )
                        },
                        ByteArray(0),
                    ),
                )
            }

        assertTrue("no distance codes at all" in failure.message!!, failure.message!!)
    }

    /**
     * The checksum, which is the only part of the format that says anything
     * about the *output*: "ADLER32 (Adler-32 checksum) — this contains a
     * checksum value of the uncompressed data" (RFC 1950 §2.2).
     *
     * A decoder that skipped it would pass every test in this file that feeds it
     * a well-formed stream, and would hand back plausible wrong bytes for a
     * stream whose compressed data was damaged in a way that still decodes.
     * Here the damage is to the checksum itself, which is the same test the
     * other way round: the four bytes are read, compared, and disagreed with.
     */
    @Test
    fun `a checksum that does not match the decoded data is refused`() {
        val encoded = flateEncode("the quick brown fox".encodeToByteArray())
        val corrupted = encoded.copyOf()
        corrupted[corrupted.size - 1] = (corrupted[corrupted.size - 1] + 1).toByte()

        val failure = assertFailsWith<FlateFormatException> { flateDecode(corrupted) }

        assertEquals(encoded.size - 4, failure.offset, "the checksum is the last four bytes")
        assertTrue("ADLER32" in failure.message!!, failure.message!!)
        assertEquals("RFC 1950 §2.2", failure.clause)

        val truncated = assertFailsWith<FlateFormatException> { flateDecode(encoded.copyOf(encoded.size - 4)) }
        assertEquals(encoded.size - 4, truncated.offset)
        assertTrue("ADLER32" in truncated.message!!, truncated.message!!)
    }

    /**
     * The property the acceptance calls "never loop", asserted the only way a
     * test can assert it: damage a valid stream in every single-byte way there
     * is and decode all of them.
     *
     * Two outcomes are allowed and no others. A [FlateFormatException] is the
     * usual one. Success is the other, and it is not a defect: bits after a
     * stored block's header are ignored by §3.2.4, bytes past the checksum are
     * not part of the stream by RFC 1950 §2.2, and damage there changes nothing
     * — while damage anywhere that matters has to get past the Adler-32 of the
     * output to be called success, which is what makes "it decoded" evidence
     * that it decoded to the right thing.
     *
     * What is not allowed is any other throwable — an index out of bounds, an
     * arithmetic failure — and neither is not returning, which a test can only
     * assert by finishing.
     */
    @Test
    fun `no single-byte corruption loops or fails in any way but a refusal`() {
        var refused = 0
        var accepted = 0
        var trials = 0

        for ((shape, data) in corpora(900)) {
            val encoded = flateEncode(data)
            for (position in encoded.indices) {
                for (damage in listOf(0x01, 0x40, 0xFF)) {
                    val corrupted = encoded.copyOf()
                    corrupted[position] = (corrupted[position].toInt() xor damage).toByte()
                    trials++
                    try {
                        assertContentEquals(data, flateDecode(corrupted), "$shape, byte $position xor $damage")
                        accepted++
                    } catch (refusal: FlateFormatException) {
                        assertTrue(refusal.offset in 0..encoded.size, "the offset is inside the stream: $refusal")
                        refused++
                    }
                }
            }
        }

        assertTrue(refused > trials / 2, "most single-byte damage is refused, not $refused of $trials")
        // A stored block's header is followed by padding to the byte boundary
        // (§3.2.4), and those bits are ignored: the incompressible corpus is one
        // stored block, so damage to them is damage the format defines away.
        assertTrue(accepted > 0, "damage to bits the format ignores changes nothing, and none was seen")
    }

    /**
     * Garbage: bytes that were never a stream, of every length up to a few
     * hundred, each of which has to be refused rather than decoded, and each of
     * which has to be refused now rather than eventually.
     *
     * The header rules out almost all of it in two bytes, so the sweep starts
     * from a valid header and fills the rest with a spread of byte values —
     * which is what a stream whose data was replaced by other data looks like.
     */
    @Test
    fun `arbitrary bytes behind a valid header never loop and never fail in another way`() {
        var state = 0x2545F491
        for (size in 0 until 300) {
            val stream = ByteArray(size + 2)
            stream[0] = 0x78
            stream[1] = 0x01
            for (index in 2 until stream.size) {
                state = state * 1664525 + 1013904223
                stream[index] = (state ushr 24).toByte()
            }
            try {
                // A decode that succeeds has matched its own checksum, which
                // these bytes may do by chance; what it must not do is loop or
                // fail in any other way.
                flateDecode(stream)
            } catch (refusal: FlateFormatException) {
                assertTrue(refusal.offset in 0..stream.size, "$refusal")
            }
        }
    }

    /**
     * A dynamic block carrying [literalLengths], nothing else — enough of a
     * stream to reach the point where those lengths are turned into a code.
     */
    private fun dynamicStream(literalLengths: IntArray): ByteArray {
        val distanceLengths = IntArray(1)
        return zlib(
            deflate { sink ->
                writeDynamicBlock(
                    sink,
                    literalLengths,
                    distanceLengths,
                    codeLengthTokens(literalLengths + distanceLengths, repeats = true),
                    emptyList(),
                )
            },
            ByteArray(0),
        )
    }
}

/**
 * Data of [size] bytes in each of the shapes the encoder decides differently
 * about, named so that a failure says which one.
 *
 * - repetitive data is all matches, so its blocks are coded and its distances
 *   reach far back;
 * - incompressible data costs more coded than stored, so its blocks are stored
 *   and the decoder's business is LEN, NLEN and the byte boundary;
 * - the mixture is both, in one stream, with matches crossing from a coded
 *   block into a stored one;
 * - a single repeated byte is the corner of §3.2.5: matches of length 258 at
 *   distance 1, which is code 285 and the overlapping copy of §3.2.3.
 */
private fun corpora(size: Int): List<Pair<String, ByteArray>> {
    val phrase = "the specification is the source of truth. ".encodeToByteArray()
    var state = 0x2545F491
    val noise =
        ByteArray(size) {
            state = state * 1664525 + 1013904223
            (state ushr 24).toByte()
        }
    return listOf(
        "repetitive" to ByteArray(size) { phrase[it % phrase.size] },
        "incompressible" to noise,
        "mixed" to ByteArray(size) { if ((it / 4096) % 2 == 0) noise[it] else phrase[it % phrase.size] },
        "one repeated byte" to ByteArray(size) { 0x5A },
    )
}
