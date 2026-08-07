/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.filter

import java.util.zip.Deflater
import java.util.zip.Inflater
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

// ISO 32000-2:2020 §7.4.4 "LZWDecode and FlateDecode filters"
// RFC 1950 §2.2 "Data format"
// RFC 1951 §3.2.3 "Details of block format", §3.2.7 "Compression with dynamic
//   Huffman codes (BTYPE=10)", §3.3 "Compliance"

/**
 * Streams this project did not write, decoded by this project.
 *
 * A PDF reader's decoder meets whatever wrote the file, which is where
 * [flateDecode] differs in kind from [flateEncode]: an encoder is measured
 * against its own output being readable, a decoder against input it had no hand
 * in. `InflateTest` covers the format from the clauses; what it cannot cover is
 * the shapes a different compressor happens to produce — the block boundaries
 * it chooses, the dynamic alphabets it builds, the level-0 stored blocks it
 * emits when told not to compress.
 *
 * [Deflater] is used as a *tool* here and its sources are never read:
 * it is
 * asked for bytes, and the bytes are handed to this decoder, exactly as a file
 * from a foreign producer would be. The reverse direction — our encoder read by
 * `java.util.zip` — is `FlateFileTest`, and the two together close the loop.
 *
 * [Inflater] appears once more, on the hand-built dynamic blocks of
 * `DeflateWriting`: those blocks are the only evidence for the §3.2.7 path in
 * `InflateTest`, and evidence written by the same hand it tests is worth
 * checking against a decompressor that shares nothing with either.
 */
class InflateForeignTest {
    /**
     * The acceptance: a foreign zlib compresses, this decoder decompresses, at
     * every level the compressor offers.
     *
     * The levels matter because they are different formats, not different
     * ratios. Level 0 is "no compression" and comes out as the stored blocks of
     * §3.2.4; the middle levels build dynamic alphabets per block (§3.2.7); and
     * across the corpora the compressor also falls back to fixed alphabets
     * (§3.2.6) where a table would cost more than it saves. The block types that
     * actually occurred are counted below, so this test states what it covered
     * rather than hoping.
     */
    @Test
    fun `a foreign zlib compresses at every level and this decoder reads it back`() {
        val types = mutableSetOf<Int>()

        for ((shape, data) in foreignCorpora()) {
            for (level in Deflater.NO_COMPRESSION..Deflater.BEST_COMPRESSION) {
                val encoded = deflateWith(data, level)
                assertContentEquals(data, flateDecode(encoded), "$shape at level $level")
                types += firstBlockType(encoded)
            }
        }

        assertTrue(STORED_BLOCK in types, "level 0 produces stored blocks (§3.2.4)")
        assertTrue(DYNAMIC_HUFFMAN_BLOCK in types, "the compressing levels produce dynamic blocks (§3.2.7)")
    }

    /**
     * The same, with the compressor's strategies, which change what the blocks
     * are made of rather than how hard it looks.
     *
     * `HUFFMAN_ONLY` suppresses the LZ77 stage, so its blocks are literals and
     * end-of-block and nothing else — the case where a dynamic literal/length
     * alphabet is carried and the distance alphabet is empty, which §3.2.7
     * describes as "one distance code of zero bits means that there are no
     * distance codes used at all (the data is all literals)". `FILTERED`
     * produces mostly short matches and long stretches of literals. Both are
     * legitimate deflate streams that this library's own encoder would never
     * write.
     */
    @Test
    fun `a foreign zlib at every strategy is read back`() {
        for ((shape, data) in foreignCorpora()) {
            for (strategy in listOf(Deflater.DEFAULT_STRATEGY, Deflater.FILTERED, Deflater.HUFFMAN_ONLY)) {
                val encoded = deflateWith(data, Deflater.BEST_COMPRESSION, strategy)
                assertContentEquals(data, flateDecode(encoded), "$shape under strategy $strategy")
            }
        }
    }

    /**
     * Data far larger than one block and than one window, so that the foreign
     * compressor has to split it and to point matches back across the splits.
     *
     * Two megabytes of text-shaped data is many blocks at any level, and the
     * repetition is placed at distances of tens of thousands of bytes — up to
     * the 32 768 the format allows — so the matches near the top of the distance
     * alphabet (§3.2.5) are reached by a producer that chose them, not by one
     * this project wrote.
     */
    @Test
    fun `a stream of many blocks from a foreign compressor is read back`() {
        val sentences =
            listOf(
                "The specification is the source of truth. ",
                "Every file cites the clause it implements. ",
                "A decoder must accept the full range of possible values. ",
                "Blocks of arbitrary size, and distances of up to thirty-two kilobytes. ",
            )
        val builder = StringBuilder()
        var index = 0
        while (builder.length < 2_000_000) {
            index = (index * 7 + 3) % sentences.size
            builder.append(sentences[index])
            if (builder.length > 40_000 && index == 0) {
                builder.append(builder.substring(builder.length - 30_000, builder.length - 20_000))
            }
        }
        val data = builder.toString().encodeToByteArray()

        for (level in listOf(1, 6, 9)) {
            assertContentEquals(data, flateDecode(deflateWith(data, level)), "${data.size} bytes at level $level")
        }
    }

    /**
     * A preset dictionary, which a foreign compressor can be asked for and which
     * a `/FlateDecode` stream has no way to carry: "if FDICT is set, a DICT
     * dictionary identifier is present immediately after the FLG byte", and the
     * dictionary itself is not — it is bytes the compressor was fed and the
     * decompressor has to be fed too.
     *
     * The point of the test is that the refusal names FDICT. Such a stream
     * decodes to nothing recoverable, and a reader looking at "invalid data"
     * would have no way to learn that the data is fine and the dictionary is
     * missing.
     */
    @Test
    fun `a stream compressed against a preset dictionary is refused by name`() {
        val dictionary = "the specification is the source of truth".encodeToByteArray()
        val data = "the specification is the source of truth, and of nothing else".encodeToByteArray()
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        val encoded: ByteArray
        try {
            deflater.setDictionary(dictionary)
            deflater.setInput(data)
            deflater.finish()
            encoded = drain(deflater)
        } finally {
            deflater.end()
        }

        assertEquals(0x20, encoded[1].toInt() and 0x20, "the compressor set FDICT")
        val failure = assertFailsWith<FlateFormatException> { flateDecode(encoded) }
        assertEquals(1, failure.offset, "FDICT is a bit of FLG, which is byte 1")
        assertTrue("FDICT" in failure.message!!, failure.message!!)
    }

    /**
     * The hand-built dynamic blocks of `DeflateWriting`, under a decompressor
     * that is not this one.
     *
     * `InflateTest` builds those blocks from §3.2.7 and asserts that this
     * decoder reads them; that pair of readings could agree with each other and
     * both be wrong. Here the same bytes go to [Inflater]. If the block layout
     * written there misreads the clause — the order of HLIT and HDIST, the
     * meaning of the extra bits of code 18, which alphabet the repeat runs
     * into — this fails, and the tests that rest on those blocks stop being
     * evidence.
     */
    @Test
    fun `the hand-built dynamic blocks are accepted by an independent decompressor`() {
        val text = "abracadabra"
        val plainLengths = IntArray(257)
        plainLengths['a'.code] = 1
        plainLengths['b'.code] = 3
        plainLengths['r'.code] = 3
        plainLengths[END_OF_BLOCK] = 3
        plainLengths['c'.code] = 4
        plainLengths['d'.code] = 4
        val plain =
            deflate { sink ->
                writeDynamicBlock(
                    sink,
                    plainLengths,
                    IntArray(1),
                    codeLengthTokens(plainLengths + IntArray(1), repeats = false),
                    text.map { literal(it.code) },
                )
            }
        assertContentEquals(
            text.encodeToByteArray(),
            inflateWith(zlib(plain, text.encodeToByteArray()), text.length),
            "a dynamic block whose code lengths are written out one by one",
        )

        val repeated = "abcdefghabcdefgh".encodeToByteArray()
        val repeatedLengths = IntArray(LITERAL_ALPHABET_SIZE)
        for (letter in 'a'..'h') repeatedLengths[letter.code] = 4
        repeatedLengths[END_OF_BLOCK] = 2
        repeatedLengths[lengthCode(8)] = 2
        val distanceLengths = IntArray(DISTANCE_ALPHABET_SIZE)
        distanceLengths[distanceCode(8)] = 1
        val folded =
            deflate { sink ->
                writeDynamicBlock(
                    sink,
                    repeatedLengths,
                    distanceLengths,
                    codeLengthTokens(repeatedLengths + distanceLengths, repeats = true),
                    ('a'..'h').map { literal(it.code) } + match(8, 8),
                )
            }
        assertContentEquals(
            repeated,
            inflateWith(zlib(folded, repeated), repeated.size),
            "the repeat codes, a repeat crossing into the distance lengths, and a single distance code",
        )
    }

    /**
     * Round trips through a foreign compressor and back through it, so that the
     * one thing neither direction alone shows — that this decoder agrees with a
     * foreign one about the *same* bytes — is stated: our encoding of a corpus
     * and the foreign encoding of it decode, through both decoders, to the same
     * data.
     */
    @Test
    fun `both decoders agree on both encodings of the same data`() {
        for ((shape, data) in foreignCorpora()) {
            val ours = flateEncode(data)
            val theirs = deflateWith(data, Deflater.BEST_COMPRESSION)

            assertContentEquals(data, flateDecode(ours), "$shape: ours through ours")
            assertContentEquals(data, flateDecode(theirs), "$shape: theirs through ours")
            assertContentEquals(data, inflateWith(ours, data.size), "$shape: ours through theirs")
            assertContentEquals(data, inflateWith(theirs, data.size), "$shape: theirs through theirs")
        }
    }

    /** [data] compressed by `java.util.zip`, zlib wrapper included. */
    private fun deflateWith(data: ByteArray, level: Int, strategy: Int = Deflater.DEFAULT_STRATEGY): ByteArray {
        val deflater = Deflater(level)
        try {
            deflater.setStrategy(strategy)
            deflater.setInput(data)
            deflater.finish()
            return drain(deflater)
        } finally {
            deflater.end()
        }
    }

    /**
     * Everything a finishing [Deflater] has to say.
     *
     * The loop ends on `finished()` and on nothing else: a compressor whose
     * strategy was changed answers the first call with no bytes at all, and a
     * loop that read that as "there is nothing left" would hand this decoder an
     * empty stream and call it a foreign encoding.
     */
    private fun drain(deflater: Deflater): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(4096)
        while (!deflater.finished()) {
            out.write(chunk, 0, deflater.deflate(chunk))
        }
        return out.toByteArray()
    }

    /** [encoded] decompressed by `java.util.zip`, checksum verification included. */
    private fun inflateWith(encoded: ByteArray, size: Int): ByteArray {
        val inflater = Inflater()
        try {
            inflater.setInput(encoded)
            val out = ByteArray(size)
            var at = 0
            while (at < size) {
                val read = inflater.inflate(out, at, size - at)
                if (read == 0 && (inflater.finished() || inflater.needsInput())) break
                at += read
            }
            inflater.inflate(ByteArray(1))
            assertTrue(inflater.finished(), "the stream did not end after $size bytes")
            return out.copyOf(at)
        } finally {
            inflater.end()
        }
    }

    /**
     * BTYPE of the first block of a zlib stream: "first bit BFINAL, next 2 bits
     * BTYPE" (§3.2.3), at the start of the byte after CMF and FLG, least
     * significant bit first.
     */
    private fun firstBlockType(encoded: ByteArray): Int = (encoded[2].toInt() shr 1) and 0x03

    /**
     * Corpora with different amounts of redundancy in them, which is what a
     * compressor's choice of block type and alphabet turns on.
     */
    private fun foreignCorpora(): List<Pair<String, ByteArray>> {
        var state = 0x2545F491
        fun noise(size: Int) = ByteArray(size) {
            state = state * 1664525 + 1013904223
            (state ushr 24).toByte()
        }
        val sentences =
            "Every file implementing part of a format cites its clause. The source of truth is the " +
                "specification text, and the reference tool is the specification itself.\n"
        val text = sentences.encodeToByteArray()
        return listOf(
            "empty" to ByteArray(0),
            "one byte" to byteArrayOf(0x2A),
            "text" to ByteArray(text.size * 60) { text[it % text.size] },
            "one repeated byte" to ByteArray(70_000) { 0x41 },
            "incompressible" to noise(70_000),
            "mixed" to (noise(30_000) + ByteArray(text.size * 40) { text[it % text.size] } + noise(10_000)),
        )
    }
}
