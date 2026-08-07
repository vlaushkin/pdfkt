/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.image

import io.pdfkt.core.filter.flateEncode
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

// Portable Network Graphics (PNG) Specification (Third Edition),
// W3C Recommendation 24 June 2025
//   §5.2 "PNG signature", §5.3 "Chunk layout", §5.5 "CRC algorithm",
//   §5.6 "Chunk ordering" (Table 7), §7.2 "Scanlines", §8.1 "Interlace methods",
//   §9.2 "Filter types for filter method 0" (Table 10, Table 11),
//   §9.3 "Filter type 3: Average", §9.4 "Filter type 4: Paeth",
//   §10.1 "Compression method 0", §10.2 "Compression of the sequence of
//   filtered scanlines", §11.2.1 "IHDR Image header" (Table 12),
//   §11.2.2 "PLTE Palette", §11.2.3 "IDAT Image data",
//   §11.2.4 "IEND Image trailer", §11.3.1.1 "tRNS Transparency",
//   §13.1 "Error handling"

/**
 * What the PNG specification requires of a decoder, in three groups.
 *
 * The first is the filters of §9.2, and it is written twice over on purpose.
 * The scanlines of the worked examples carry filtered bytes computed by hand
 * from the Filter Function column of Table 11 — the arithmetic is in the
 * comments — because a round trip through this file's own encoder would pass
 * just as happily if both halves of the table were misread the same way. The
 * round trips then cover what a handful of worked examples cannot: every colour
 * type at every bit depth Table 12 admits, through every filter type.
 *
 * The second is the structure: the signature, chunk layout and CRC of clause 5,
 * the ordering rules of Table 7, and the fields of IHDR, PLTE and tRNS.
 *
 * The third is refusal. §13.1 asks a decoder to "detect errors as early as
 * possible" and to "provide helpful messages describing errors", so the
 * assertions are on the offset and the words of the message rather than on "it
 * threw": a decoder that reports byte zero for every kind of damage tells a
 * caller nothing about which byte of a file to look at. The one datastream here
 * that is *valid* and still refused is the interlaced one — the deferral this
 * task states, tested so that it stays a refusal and never becomes a scramble.
 *
 * Real files, from an encoder that never saw this library, are `PngForeignTest`.
 */
class PngImageTest {
    /**
     * A worked example of the Sub, Up and Paeth filters, with every byte of the
     * filtered scanlines computed from Table 11 by hand.
     *
     * The image is four pixels by three, greyscale at 8 bits, so a pixel is a
     * byte and `a` is the byte before `x` in the scanline:
     *
     * ```
     * 10  20  30  40
     * 50  60  70  80
     * 90 100 110 120
     * ```
     *
     * Scanline 0, filter type 1 Sub, `Filt(x) = Orig(x) - Orig(a)` with `a`
     * zero before the first pixel: 10-0, 20-10, 30-20, 40-30 = 10 10 10 10.
     *
     * Scanline 1, filter type 2 Up, `Filt(x) = Orig(x) - Orig(b)`: 50-10,
     * 60-20, 70-30, 80-40 = 40 40 40 40.
     *
     * Scanline 2, filter type 4 Paeth, `Filt(x) = Orig(x) -
     * PaethPredictor(Orig(a), Orig(b), Orig(c))`:
     *
     * - x=90: a=0, b=50, c=0. p = 0+50-0 = 50, pa = 50, pb = 0, pc = 50; pa <=
     *   pb is false, pb <= pc is true, so Pr = b = 50 and Filt = 40.
     * - x=100: a=90, b=60, c=50. p = 100, pa = 10, pb = 40, pc = 50, so Pr = a
     *   = 90 and Filt = 10.
     * - x=110: a=100, b=70, c=60. p = 110, pa = 10, pb = 40, pc = 50, Pr = 100,
     *   Filt = 10.
     * - x=120: a=110, b=80, c=70. p = 120, pa = 10, pb = 40, pc = 50, Pr = 110,
     *   Filt = 10.
     */
    @Test
    fun `the filtered scanlines of the worked example reconstruct to its samples`() {
        val filtered = byteArrayOf(
            1, 10, 10, 10, 10,
            2, 40, 40, 40, 40,
            4, 40, 10, 10, 10,
        )
        val image = PngImage.decode(
            pngDatastream(
                ihdr(width = 4, height = 3, bitDepth = 8, colourType = 0),
                idat(flateEncode(filtered)),
                iend(),
            ),
        )

        assertEquals(4, image.width)
        assertEquals(3, image.height)
        assertEquals(PngColourType.GREYSCALE, image.colourType)
        assertContentEquals(
            byteArrayOf(10, 20, 30, 40, 50, 60, 70, 80, 90, 100, 110, 120),
            image.samples,
        )
    }

    /**
     * Filter type 3, worked by hand, on the two counts §9.3 singles out.
     *
     * The image is two pixels by two, greyscale at 8 bits, holding
     *
     * ```
     *  10 200
     *  30   5
     * ```
     *
     * Scanline 0 is filter type 0 None, so its bytes are its samples. Scanline
     * 1 is filter type 3 Average, `Filt(x) = Orig(x) - floor((Orig(a) +
     * Orig(b)) / 2)`:
     *
     * - x=30: a=0, b=10, floor(10/2) = 5, Filt = 25.
     * - x=5: a=30, b=200. The sum is 230, which is the "at least nine-bit
     *   arithmetic" the clause demands — a byte would have wrapped it to 0 and
     *   predicted 0 instead of 115. Filt = 5 - 115 = -110, which is the byte
     *   146, since "unsigned arithmetic modulo 256 is used".
     *
     * Reconstructing the second byte reads `Recon(a)` = 30, the value already
     * reconstructed on this scanline, not the 25 the datastream carries there.
     */
    @Test
    fun `the average filter takes the floor of the mean of the reconstructed neighbours`() {
        val filtered = concat(
            byteArrayOf(0, 10, 200.toByte()),
            byteArrayOf(3, 25, 146.toByte()),
        )
        val image = PngImage.decode(
            pngDatastream(
                ihdr(width = 2, height = 2, bitDepth = 8, colourType = 0),
                idat(flateEncode(filtered)),
                iend(),
            ),
        )

        assertContentEquals(byteArrayOf(10, 200.toByte(), 30, 5), image.samples)
    }

    /**
     * "Unsigned arithmetic modulo 256 is used, so that both the inputs and
     * outputs fit into bytes" (§9.2).
     *
     * One scanline of two pixels holding 200 then 100. Sub gives 200 - 0 = 200
     * and 100 - 200 = -100, the byte 156; reconstructing the second is 156 +
     * 200 = 356, which is 100 modulo 256. A decoder that saturated instead of
     * wrapping would return 255 here.
     */
    @Test
    fun `a reconstructed byte wraps modulo 256`() {
        val image = PngImage.decode(
            pngDatastream(
                ihdr(width = 2, height = 1, bitDepth = 8, colourType = 0),
                idat(flateEncode(byteArrayOf(1, 200.toByte(), 156.toByte()))),
                iend(),
            ),
        )

        assertContentEquals(byteArrayOf(200.toByte(), 100), image.samples)
    }

    /**
     * The PaethPredictor of §9.4, including the two ties whose resolution "is
     * critical and shall not be altered".
     *
     * - a=1, b=2, c=3: p = 0, pa = 1, pb = 2, pc = 3 — the plain first branch.
     * - a=1, b=1, c=1: every distance is zero, and `pa <= pb and pa <= pc`
     *   takes `a` before either of the others.
     * - a=0, b=0, c=255: p = -255, pa = pb = 255, pc = 510. The tie between pa
     *   and pb goes to `a`, and computing `p` in bytes rather than "exactly,
     *   without overflow" would have made it 1 and chosen differently.
     * - a=2, b=0, c=1: p = 1, pa = 1, pb = 1, pc = 0. The first branch fails on
     *   `pa <= pc`, the second on `pb <= pc`, so the answer is `c` — a function
     *   that stopped at `pa <= pb` would have answered `a`.
     * - a=0, b=10, c=0: p = 10, pa = 10, pb = 0, pc = 10, the plain second
     *   branch.
     * - a=30, b=0, c=20: p = 10, pa = 20, pb = pc = 10. The first branch fails
     *   and the tie between `b` and `c` goes to `b`, since the comparison the
     *   clause prints is `pb <= pc` and not `pb < pc`.
     */
    @Test
    fun `the Paeth predictor chooses the closest neighbour and breaks ties in the printed order`() {
        assertEquals(1, paethPredictor(a = 1, b = 2, c = 3))
        assertEquals(1, paethPredictor(a = 1, b = 1, c = 1))
        assertEquals(0, paethPredictor(a = 0, b = 0, c = 255))
        assertEquals(1, paethPredictor(a = 2, b = 0, c = 1))
        assertEquals(10, paethPredictor(a = 0, b = 10, c = 0))
        assertEquals(0, paethPredictor(a = 30, b = 0, c = 20))
        assertEquals(255, paethPredictor(a = 255, b = 0, c = 0))
    }

    /**
     * Every combination Table 12 admits, through every filter type of Table 11.
     *
     * The scanlines are pseudo-random bytes, which is the point: a filter that
     * dropped the high bit or the last byte of a row would survive a picture of
     * flat colour. Each image is filtered scanline by scanline with a different
     * filter type — so the Up, Average and Paeth rows read a prior scanline
     * that was itself filtered differently — and must come back byte for byte.
     */
    @Test
    fun `every colour type at every admitted bit depth round-trips through every filter type`() {
        for (colourType in PngColourType.entries) {
            for (bitDepth in colourType.allowedBitDepths) {
                val width = 7
                val height = 6
                val random = Random(colourType.code * 100 + bitDepth)
                val bytesPerRow = (width * colourType.channels * bitDepth + 7) / 8
                val rows = List(height) { ByteArray(bytesPerRow) { random.nextInt(256).toByte() } }
                val filterTypes = List(height) { it % 5 }

                val datastream = pngOf(
                    width = width,
                    height = height,
                    bitDepth = bitDepth,
                    colourType = colourType.code,
                    rows = rows,
                    filterTypes = filterTypes,
                    extraChunks = if (colourType == PngColourType.INDEXED) {
                        listOf(paletteOf(1 shl bitDepth))
                    } else {
                        emptyList()
                    },
                )

                val image = PngImage.decode(datastream)
                assertEquals(bytesPerRow, image.bytesPerRow, "$colourType at $bitDepth bits")
                assertContentEquals(
                    concat(*rows.toTypedArray()),
                    image.samples,
                    "$colourType at $bitDepth bits through filter types $filterTypes",
                )
            }
        }
    }

    /**
     * "These samples are packed into bytes with the leftmost sample in the
     * high-order bits of a byte followed by the other samples for the
     * scanline" (§7.2), and "when there are multiple pixels per byte, some
     * low-order bits of the last byte of a scanline may go unused".
     *
     * Three pixels at 4 bits fill one byte and a half, so a scanline is two
     * bytes and the low four bits of the second are padding. The samples read
     * back are the high nibble first.
     */
    @Test
    fun `sub-byte samples are read from the high-order bits first and the row is padded`() {
        val row = byteArrayOf(0x9C.toByte(), 0x30)
        val image = PngImage.decode(
            pngOf(width = 3, height = 1, bitDepth = 4, colourType = 0, rows = listOf(row)),
        )

        assertEquals(2, image.bytesPerRow)
        assertEquals(0x9, image.sample(x = 0, y = 0, channel = 0))
        assertEquals(0xC, image.sample(x = 1, y = 0, channel = 0))
        assertEquals(0x3, image.sample(x = 2, y = 0, channel = 0))
    }

    /** The same for one bit and for two, where a row of five pixels leaves a padded tail. */
    @Test
    fun `one-bit and two-bit samples are read from the high-order bits first`() {
        val oneBit = PngImage.decode(
            pngOf(width = 5, height = 1, bitDepth = 1, colourType = 0, rows = listOf(byteArrayOf(0xA8.toByte()))),
        )
        assertEquals(1, oneBit.bytesPerRow)
        assertContentEquals(
            intArrayOf(1, 0, 1, 0, 1).toList(),
            (0 until 5).map { oneBit.sample(x = it, y = 0, channel = 0) },
        )

        val twoBit = PngImage.decode(
            pngOf(width = 5, height = 1, bitDepth = 2, colourType = 0, rows = listOf(byteArrayOf(0x1B, 0xC0.toByte()))),
        )
        assertEquals(2, twoBit.bytesPerRow)
        assertContentEquals(
            intArrayOf(0, 1, 2, 3, 3).toList(),
            (0 until 5).map { twoBit.sample(x = it, y = 0, channel = 0) },
        )
    }

    /**
     * "PNG images that are not indexed-color images may have sample values with
     * a bit depth of 16. Such sample values are in network byte order (MSB
     * first, LSB second)" (§7.2), and the channels of a pixel follow one
     * another in the order Table 12 gives — R, G, B, then alpha.
     */
    @Test
    fun `sixteen-bit samples are network byte order and the channels follow the table`() {
        val row = byteArrayOf(0x12, 0x34, 0x00, 0xFF.toByte(), 0xFF.toByte(), 0x00, 0x80.toByte(), 0x01)
        val image = PngImage.decode(
            pngOf(width = 1, height = 1, bitDepth = 16, colourType = 6, rows = listOf(row)),
        )

        assertEquals(0x1234, image.sample(x = 0, y = 0, channel = 0))
        assertEquals(0x00FF, image.sample(x = 0, y = 0, channel = 1))
        assertEquals(0xFF00, image.sample(x = 0, y = 0, channel = 2))
        assertEquals(0x8001, image.sample(x = 0, y = 0, channel = 3))
    }

    /**
     * "The compressed datastream is then the concatenation of the contents of
     * the data fields of all the IDAT chunks (noting that data fields may be of
     * zero length)" (§11.2.3), and the boundaries "can fall anywhere in the
     * zlib datastream" (§10.2) — including inside the trailing check value,
     * which the split below puts three bytes from the end.
     */
    @Test
    fun `the image data are the concatenation of the IDAT chunks and a chunk may be empty`() {
        val rows = List(4) { y -> ByteArray(9) { (y * 9 + it).toByte() } }
        val compressed = flateEncode(filterScanlines(rows, List(4) { 0 }, bytesPerPixel = 3))
        val cut = compressed.size - 3

        val image = PngImage.decode(
            pngDatastream(
                ihdr(width = 3, height = 4, bitDepth = 8, colourType = 2),
                idat(ByteArray(0)),
                idat(compressed.copyOfRange(0, 1)),
                idat(compressed.copyOfRange(1, cut)),
                idat(ByteArray(0)),
                idat(compressed.copyOfRange(cut, compressed.size)),
                iend(),
            ),
        )

        assertContentEquals(concat(*rows.toTypedArray()), image.samples)
    }

    /**
     * "Encountering an unknown ancillary chunk is never an error. The chunk can
     * simply be ignored" (§13.1) — the lowercase first letter of §5.4 being
     * what says so, whatever the chunk holds.
     */
    @Test
    fun `an unknown ancillary chunk is ignored wherever it sits`() {
        val rows = listOf(byteArrayOf(1, 2, 3, 4))
        val compressed = flateEncode(filterScanlines(rows, listOf(0), bytesPerPixel = 1))

        val image = PngImage.decode(
            pngDatastream(
                ihdr(width = 4, height = 1, bitDepth = 8, colourType = 0),
                pngChunk("gAMA", uint32(45455)),
                pngChunk("tEXt", "Comment not a chunk this decoder knows".encodeToByteArray()),
                idat(compressed),
                pngChunk("tIME", byteArrayOf(0x07, 0xE6.toByte(), 8, 5, 12, 0, 0)),
                iend(),
            ),
        )

        assertContentEquals(byteArrayOf(1, 2, 3, 4), image.samples)
    }

    /**
     * "Encountering an unknown critical chunk is a fatal condition for any
     * decoder trying to extract the image from the datastream. A decoder that
     * ignored a critical chunk could not know whether the image it extracted
     * was the one intended by the encoder" (§13.1).
     */
    @Test
    fun `an unknown critical chunk is fatal and is named in the refusal`() {
        val fault = assertFailsWith<PngFormatException> {
            PngImage.decode(
                pngDatastream(
                    ihdr(width = 1, height = 1, bitDepth = 8, colourType = 0),
                    pngChunk("CRIT", byteArrayOf(1, 2, 3)),
                    idat(flateEncode(byteArrayOf(0, 42))),
                    iend(),
                ),
            )
        }

        assertTrue("CRIT" in fault.problem, fault.message)
        assertTrue("critical" in fault.problem, fault.message)
    }

    /** "The first eight bytes of a PNG datastream always contain … 89 50 4E 47 0D 0A 1A 0A" (§5.2). */
    @Test
    fun `a datastream not beginning with the signature is refused at the byte that differs`() {
        val valid = pngOf(width = 1, height = 1, bitDepth = 8, colourType = 0, rows = listOf(byteArrayOf(7)))

        val damaged = valid.copyOf()
        damaged[3] = 0x48
        val fault = assertFailsWith<PngFormatException> { PngImage.decode(damaged) }
        assertEquals(3, fault.offset)
        assertTrue("signature" in fault.clause, fault.message)

        val short = assertFailsWith<PngFormatException> { PngImage.decode(valid.copyOfRange(0, 6)) }
        assertEquals(6, short.offset)
        assertTrue("8 bytes of the signature" in short.problem, short.message)
    }

    /**
     * "A CRC should be checked before processing the chunk data" (§13.1), and a
     * mismatch "indicates a corrupted datastream".
     *
     * The IHDR here is the one every other test uses, with one bit of its CRC
     * flipped, so what is being checked is the check itself and not some other
     * consequence of damage.
     */
    @Test
    fun `a chunk whose CRC does not match its data is refused at the CRC field`() {
        val header = ihdr(width = 1, height = 1, bitDepth = 8, colourType = 0)
        val correct = crcBitByBit(header.copyOfRange(4, header.size - 4))

        val fault = assertFailsWith<PngFormatException> {
            PngImage.decode(
                pngDatastream(
                    pngChunkWithCrc(
                        "IHDR",
                        header.copyOfRange(8, header.size - 4),
                        correct xor 1,
                    ),
                    idat(flateEncode(byteArrayOf(0, 7))),
                    iend(),
                ),
            )
        }

        // Signature, four length bytes, four type bytes and thirteen of data.
        assertEquals(8 + 4 + 4 + 13, fault.offset)
        assertTrue("CRC" in fault.problem, fault.message)
    }

    /**
     * A chunk is "Length, Chunk Type, Chunk Data, CRC" (§5.3), so a datastream
     * that stops inside any of the four is refused where it stops — and a
     * length whose top bit is set claims more than the "shall not exceed 2^31-1
     * bytes" the clause allows.
     */
    @Test
    fun `a chunk that runs past the end of the datastream is refused`() {
        val valid = pngOf(width = 2, height = 1, bitDepth = 8, colourType = 0, rows = listOf(byteArrayOf(1, 2)))

        val truncated = assertFailsWith<PngFormatException> { PngImage.decode(valid.copyOfRange(0, valid.size - 5)) }
        assertEquals(valid.size - 5, truncated.offset)

        val headless = assertFailsWith<PngFormatException> { PngImage.decode(valid.copyOfRange(0, 12)) }
        assertEquals(12, headless.offset)
        assertTrue("four-byte length" in headless.problem, headless.message)

        val overlong = assertFailsWith<PngFormatException> {
            PngImage.decode(pngDatastream(pngChunkDeclaring("IHDR", -1, ByteArray(13))))
        }
        assertEquals(8, overlong.offset)
        assertTrue("2^31-1" in overlong.problem, overlong.message)
    }

    /**
     * "Each byte of a chunk type is restricted to the hexadecimal values 41 to
     * 5A and 61 to 7A" (§5.3) — the plausibility check §13.2 recommends, which
     * is what tells a decoder that has lost its place that it has.
     */
    @Test
    fun `a chunk type that is not four ISO 646 letters is refused at the offending byte`() {
        val fault = assertFailsWith<PngFormatException> {
            PngImage.decode(
                pngDatastream(
                    ihdr(width = 1, height = 1, bitDepth = 8, colourType = 0),
                    pngChunk("ID T", ByteArray(0)),
                ),
            )
        }

        assertEquals(8 + 25 + 4 + 2, fault.offset)
        assertTrue("41 to 5A" in fault.problem, fault.message)
    }

    /** Table 12, both ways: every admitted pair is decoded and every other pair is refused. */
    @Test
    fun `a bit depth its colour type does not admit is refused naming both`() {
        val refused = listOf(
            0 to 3, 0 to 32, 2 to 1, 2 to 2, 2 to 4, 3 to 16, 4 to 1, 4 to 4, 6 to 2, 6 to 0,
        )
        for ((colourType, bitDepth) in refused) {
            val fault = assertFailsWith<PngFormatException> {
                PngImage.decode(
                    pngDatastream(
                        ihdr(width = 1, height = 1, bitDepth = bitDepth, colourType = colourType),
                        idat(flateEncode(byteArrayOf(0, 0, 0, 0, 0))),
                        iend(),
                    ),
                )
            }
            assertEquals(8 + 8 + 8, fault.offset, "colour type $colourType at $bitDepth bits")
            assertTrue("Table 12" in fault.problem, fault.message)
        }
    }

    /**
     * "A color type … is the sum of the following values: 1 (palette used), 2
     * (truecolor used) and 4 (alpha used)" (§6.1), and Table 9 names the five
     * combinations. 1, 5 and 7 are not among them.
     */
    @Test
    fun `a colour type outside Table 9 is refused`() {
        for (colourType in listOf(1, 5, 7, 8, 255)) {
            val fault = assertFailsWith<PngFormatException> {
                PngImage.decode(
                    pngDatastream(
                        ihdr(width = 1, height = 1, bitDepth = 8, colourType = colourType),
                        idat(flateEncode(byteArrayOf(0, 0, 0, 0, 0))),
                        iend(),
                    ),
                )
            }
            assertEquals(8 + 8 + 9, fault.offset, "colour type $colourType")
            assertTrue("Table 9" in fault.problem, fault.message)
        }
    }

    /** "Zero is an invalid value" for either dimension (§11.2.1). */
    @Test
    fun `an image of no width or no height is refused`() {
        for ((width, height) in listOf(0 to 4, 4 to 0)) {
            val fault = assertFailsWith<PngFormatException> {
                PngImage.decode(
                    pngDatastream(
                        ihdr(width = width, height = height, bitDepth = 8, colourType = 0),
                        idat(flateEncode(byteArrayOf(0))),
                        iend(),
                    ),
                )
            }
            assertEquals(if (width == 0) 16 else 20, fault.offset)
            assertTrue("zero is an invalid value" in fault.problem, fault.message)
        }
    }

    /**
     * The deferral this task states, and the reason it is a refusal rather than
     * a best effort: interlace method 1 delivers "a sequence of seven reduced
     * images" (§8.1), and reading them as one image would put pass 1's pixels
     * along the top of the picture.
     */
    @Test
    fun `an interlaced image is refused by name rather than decoded as one pass`() {
        val fault = assertFailsWith<PngFormatException> {
            PngImage.decode(
                pngDatastream(
                    ihdr(width = 8, height = 8, bitDepth = 8, colourType = 0, interlaceMethod = 1),
                    idat(flateEncode(ByteArray(72))),
                    iend(),
                ),
            )
        }

        assertEquals(8 + 8 + 12, fault.offset)
        assertTrue("Adam7" in fault.problem, fault.message)
        assertTrue("does not decode" in fault.problem, fault.message)
        assertTrue("Interlace methods" in fault.clause, fault.message)
    }

    /**
     * The other three single bytes of IHDR: "only compression method 0 …",
     * "only filter method 0 …", and "other values of interlace method are
     * reserved for future standardization" (§11.2.1, §8.1).
     */
    @Test
    fun `an undefined compression filter or interlace method is refused`() {
        val compression = assertFailsWith<PngFormatException> {
            PngImage.decode(headerOnly(compressionMethod = 1))
        }
        assertEquals(8 + 8 + 10, compression.offset)
        assertTrue("compression method 0" in compression.problem, compression.message)

        val filter = assertFailsWith<PngFormatException> { PngImage.decode(headerOnly(filterMethod = 1)) }
        assertEquals(8 + 8 + 11, filter.offset)
        assertTrue("filter method 0" in filter.problem, filter.message)

        val interlace = assertFailsWith<PngFormatException> { PngImage.decode(headerOnly(interlaceMethod = 2)) }
        assertEquals(8 + 8 + 12, interlace.offset)
        assertTrue("reserved for future standardization" in interlace.problem, interlace.message)
    }

    /** "The IHDR chunk shall be the first chunk in the PNG datastream" (§11.2.1). */
    @Test
    fun `a datastream whose first chunk is not IHDR is refused`() {
        val fault = assertFailsWith<PngFormatException> {
            PngImage.decode(
                pngDatastream(
                    pngChunk("gAMA", uint32(45455)),
                    ihdr(width = 1, height = 1, bitDepth = 8, colourType = 0),
                    idat(flateEncode(byteArrayOf(0, 1))),
                    iend(),
                ),
            )
        }

        assertEquals(8, fault.offset)
        assertTrue("shall be the first chunk" in fault.problem, fault.message)
    }

    /** "Only one IHDR chunk and one IEND chunk are allowed in a PNG datastream" (§11.2). */
    @Test
    fun `a second IHDR chunk is refused`() {
        val fault = assertFailsWith<PngFormatException> {
            PngImage.decode(
                pngDatastream(
                    ihdr(width = 1, height = 1, bitDepth = 8, colourType = 0),
                    ihdr(width = 1, height = 1, bitDepth = 8, colourType = 0),
                    idat(flateEncode(byteArrayOf(0, 1))),
                    iend(),
                ),
            )
        }

        assertTrue("second IHDR" in fault.problem, fault.message)
    }

    /**
     * Table 7 on IDAT: "multiple IDAT chunks shall be consecutive", and on
     * IEND: "shall be last". A datastream missing its IDAT chunks entirely is
     * not an image either — §11.2 requires "one or more".
     */
    @Test
    fun `the IDAT chunks are consecutive and present and the IEND chunk is last`() {
        val rows = listOf(byteArrayOf(1, 2))
        val compressed = flateEncode(filterScanlines(rows, listOf(0), bytesPerPixel = 1))
        val header = ihdr(width = 2, height = 1, bitDepth = 8, colourType = 0)

        val interrupted = assertFailsWith<PngFormatException> {
            PngImage.decode(
                pngDatastream(
                    header,
                    idat(compressed.copyOfRange(0, 4)),
                    pngChunk("tEXt", "in the middle".encodeToByteArray()),
                    idat(compressed.copyOfRange(4, compressed.size)),
                    iend(),
                ),
            )
        }
        assertTrue("intervening chunks" in interrupted.problem, interrupted.message)

        val absent = assertFailsWith<PngFormatException> { PngImage.decode(pngDatastream(header, iend())) }
        assertTrue("no IDAT chunk" in absent.problem, absent.message)

        val trailing = assertFailsWith<PngFormatException> {
            PngImage.decode(
                concat(
                    pngDatastream(header, idat(compressed), iend()),
                    pngChunk("tEXt", "after the end".encodeToByteArray()),
                ),
            )
        }
        assertTrue("shall be last" in trailing.problem, trailing.message)

        val unterminated = assertFailsWith<PngFormatException> {
            PngImage.decode(pngDatastream(header, idat(compressed)))
        }
        assertTrue("no IEND chunk" in unterminated.problem, unterminated.message)
    }

    /** "The chunk's data field is empty" (§11.2.4). */
    @Test
    fun `an IEND chunk carrying data is refused`() {
        val fault = assertFailsWith<PngFormatException> {
            PngImage.decode(
                pngDatastream(
                    ihdr(width = 1, height = 1, bitDepth = 8, colourType = 0),
                    idat(flateEncode(byteArrayOf(0, 1))),
                    pngChunk("IEND", byteArrayOf(0)),
                ),
            )
        }

        assertTrue("data field is empty" in fault.problem, fault.message)
    }

    /**
     * PLTE, §11.2.2: required for colour type 3, forbidden for 0 and 4,
     * three bytes an entry, at most 256 of them, at most as many as the bit
     * depth can index, and never twice. Where it is merely "a suggested set of
     * colors" — colour types 2 and 6 — it is accepted and unused.
     */
    @Test
    fun `the PLTE chunk is required for an indexed image and refused where it cannot be indexed`() {
        val missing = assertFailsWith<PngFormatException> {
            PngImage.decode(
                pngOf(width = 2, height = 1, bitDepth = 8, colourType = 3, rows = listOf(byteArrayOf(0, 1))),
            )
        }
        assertTrue("no PLTE chunk" in missing.problem, missing.message)

        val greyscale = assertFailsWith<PngFormatException> {
            PngImage.decode(
                pngOf(
                    width = 2,
                    height = 1,
                    bitDepth = 8,
                    colourType = 0,
                    rows = listOf(byteArrayOf(0, 1)),
                    extraChunks = listOf(paletteOf(2)),
                ),
            )
        }
        assertTrue("shall not appear for color types 0 and 4" in greyscale.problem, greyscale.message)

        val ragged = assertFailsWith<PngFormatException> {
            PngImage.decode(
                pngOf(
                    width = 2,
                    height = 1,
                    bitDepth = 8,
                    colourType = 3,
                    rows = listOf(byteArrayOf(0, 1)),
                    extraChunks = listOf(pngChunk("PLTE", ByteArray(7))),
                ),
            )
        }
        assertTrue("not divisible by 3" in ragged.problem, ragged.message)

        val tooDeep = assertFailsWith<PngFormatException> {
            PngImage.decode(
                pngOf(
                    width = 2,
                    height = 1,
                    bitDepth = 2,
                    colourType = 3,
                    rows = listOf(byteArrayOf(0x40)),
                    extraChunks = listOf(paletteOf(5)),
                ),
            )
        }
        assertTrue("shall not exceed the range" in tooDeep.problem, tooDeep.message)

        val twice = assertFailsWith<PngFormatException> {
            PngImage.decode(
                pngOf(
                    width = 2,
                    height = 1,
                    bitDepth = 8,
                    colourType = 3,
                    rows = listOf(byteArrayOf(0, 1)),
                    extraChunks = listOf(paletteOf(4), paletteOf(4)),
                ),
            )
        }
        assertTrue("second PLTE" in twice.problem, twice.message)

        // "It is permissible to have fewer entries than the bit depth would
        // allow", and a truecolour image may carry a suggested palette.
        val suggested = PngImage.decode(
            pngOf(
                width = 1,
                height = 1,
                bitDepth = 8,
                colourType = 2,
                rows = listOf(byteArrayOf(9, 8, 7)),
                extraChunks = listOf(paletteOf(3)),
            ),
        )
        assertEquals(3, suggested.paletteEntryCount)
        assertContentEquals(byteArrayOf(9, 8, 7), suggested.samples)
    }

    /** A palette is kept as its entries, "the first entry … referenced by pixel value 0" (§11.2.2). */
    @Test
    fun `an indexed image keeps its palette and its pixels are indices into it`() {
        val image = PngImage.decode(
            pngOf(
                width = 4,
                height = 1,
                bitDepth = 2,
                colourType = 3,
                rows = listOf(byteArrayOf(0x1B)),
                extraChunks = listOf(paletteOf(4)),
            ),
        )

        val palette = image.palette ?: error("an indexed image keeps the palette its pixels index")
        assertEquals(4, image.paletteEntryCount)
        assertContentEquals(listOf(0, 1, 2, 3), (0 until 4).map { image.sample(it, 0, 0) })
        assertContentEquals(byteArrayOf(0, 0, 0), palette.copyOfRange(0, 3))
        assertContentEquals(byteArrayOf(30, 60, 90), palette.copyOfRange(3, 6))
    }

    /**
     * tRNS, §11.3.1.1: two bytes for a grey sample value, six for an RGB one,
     * at most one alpha value per palette entry — and never at all for the
     * colour types that already carry an alpha channel.
     *
     * It is kept rather than skipped because it is where the transparency of
     * those three colour types lives (§6.2); a decoder that ignored it would
     * hand back an opaque image and say nothing.
     */
    @Test
    fun `the tRNS chunk is kept and checked against the colour type it belongs to`() {
        val grey = PngImage.decode(
            pngOf(
                width = 2,
                height = 1,
                bitDepth = 8,
                colourType = 0,
                rows = listOf(byteArrayOf(3, 4)),
                extraChunks = listOf(pngChunk("tRNS", byteArrayOf(0, 3))),
            ),
        )
        assertContentEquals(byteArrayOf(0, 3), grey.transparency)

        val short = assertFailsWith<PngFormatException> {
            PngImage.decode(
                pngOf(
                    width = 1,
                    height = 1,
                    bitDepth = 8,
                    colourType = 2,
                    rows = listOf(byteArrayOf(1, 2, 3)),
                    extraChunks = listOf(pngChunk("tRNS", byteArrayOf(0, 1, 0, 2))),
                ),
            )
        }
        assertTrue("two bytes per sample" in short.problem, short.message)

        val withAlpha = assertFailsWith<PngFormatException> {
            PngImage.decode(
                pngOf(
                    width = 1,
                    height = 1,
                    bitDepth = 8,
                    colourType = 4,
                    rows = listOf(byteArrayOf(1, 2)),
                    extraChunks = listOf(pngChunk("tRNS", byteArrayOf(0, 1))),
                ),
            )
        }
        assertTrue("shall not appear for color types 4 and 6" in withAlpha.problem, withAlpha.message)

        val overrun = assertFailsWith<PngFormatException> {
            PngImage.decode(
                pngOf(
                    width = 1,
                    height = 1,
                    bitDepth = 8,
                    colourType = 3,
                    rows = listOf(byteArrayOf(0)),
                    extraChunks = listOf(paletteOf(2), pngChunk("tRNS", byteArrayOf(0, 1, 2))),
                ),
            )
        }
        assertTrue("more alpha values than there are palette entries" in overrun.problem, overrun.message)
    }

    /**
     * The image data are one zlib datastream (§10.1). Bytes that are not one
     * are refused where the image data begin — a position inside the
     * concatenation is not a position in the file (§13.8) — and the refusal
     * carries what the inflate said, which is where the useful detail is.
     */
    @Test
    fun `image data that are not a zlib datastream are refused citing the compression method`() {
        val fault = assertFailsWith<PngFormatException> {
            PngImage.decode(
                pngDatastream(
                    ihdr(width = 1, height = 1, bitDepth = 8, colourType = 0),
                    idat(byteArrayOf(0x11, 0x22, 0x33, 0x44)),
                    iend(),
                ),
            )
        }

        assertEquals(8 + 25 + 8, fault.offset)
        assertTrue("zlib" in fault.problem, fault.message)
        assertTrue("of the concatenation" in fault.problem, fault.message)
        assertTrue("Compression method 0" in fault.clause, fault.message)
    }

    /**
     * The decompressed data are exactly `height × (1 + bytesPerRow)` bytes: a
     * filter type byte per scanline and the scanline itself (§9.1). Fewer means
     * rows are missing, and a partial image looks exactly like an image, which
     * is why nothing is returned.
     */
    @Test
    fun `image data that decompress to the wrong number of bytes are refused`() {
        val header = ihdr(width = 3, height = 4, bitDepth = 8, colourType = 0)

        val short = assertFailsWith<PngFormatException> {
            PngImage.decode(pngDatastream(header, idat(flateEncode(ByteArray(3 * 4))), iend()))
        }
        assertTrue("decompress to 12 bytes" in short.problem, short.message)
        assertTrue("needs 16" in short.problem, short.message)

        val long = assertFailsWith<PngFormatException> {
            PngImage.decode(pngDatastream(header, idat(flateEncode(ByteArray(17))), iend()))
        }
        assertTrue("decompress to 17 bytes" in long.problem, long.message)
    }

    /**
     * "Filter method 0 specifies exactly this set of five filter types and this
     * shall not be extended" (§9.2), and the refusal says which scanline
     * declared what — the decompressed data are what a caller has to look at,
     * and the file offset alone would not lead them there.
     */
    @Test
    fun `a scanline declaring a filter type outside the five is refused naming the scanline`() {
        val fault = assertFailsWith<PngFormatException> {
            PngImage.decode(
                pngDatastream(
                    ihdr(width = 2, height = 3, bitDepth = 8, colourType = 0),
                    idat(flateEncode(byteArrayOf(0, 1, 2, 0, 3, 4, 5, 5, 6))),
                    iend(),
                ),
            )
        }

        assertTrue("scanline 2" in fault.problem, fault.message)
        assertTrue("filter type 5" in fault.problem, fault.message)
        assertTrue("byte 6 of those data" in fault.problem, fault.message)
    }

    /** A greyscale IHDR with the three method bytes as given — for the refusals above. */
    private fun headerOnly(compressionMethod: Int = 0, filterMethod: Int = 0, interlaceMethod: Int = 0): ByteArray =
        pngDatastream(
            ihdr(
                width = 1,
                height = 1,
                bitDepth = 8,
                colourType = 0,
                compressionMethod = compressionMethod,
                filterMethod = filterMethod,
                interlaceMethod = interlaceMethod,
            ),
            idat(flateEncode(byteArrayOf(0, 1))),
            iend(),
        )

    /** A PLTE chunk of [entries] distinct entries — "Red 1 byte, Green 1 byte, Blue 1 byte". */
    private fun paletteOf(entries: Int): ByteArray =
        pngChunk("PLTE", ByteArray(entries * 3) { ((it / 3) * 30 * (it % 3 + 1) % 256).toByte() })
}
