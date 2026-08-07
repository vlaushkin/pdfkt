/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.image

import java.awt.color.ColorSpace
import java.awt.image.BufferedImage
import java.awt.image.ComponentColorModel
import java.awt.image.DataBuffer
import java.awt.image.IndexColorModel
import java.awt.image.Raster
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

// Portable Network Graphics (PNG) Specification (Third Edition),
// W3C Recommendation 24 June 2025
//   §7.2 "Scanlines", §8.1 "Interlace methods",
//   §9.2 "Filter types for filter method 0", §11.2.1 "IHDR Image header"
//   (Table 12), §11.2.2 "PLTE Palette"

/**
 * PNG files this project did not write, decoded by this project.
 *
 * `PngImageTest` builds its datastreams from the clauses, which covers the
 * format and cannot cover the choices a real encoder makes: which filter type
 * it picks for each scanline, where it ends one IDAT chunk and starts the next,
 * what it packs into a palette. Those are what a decoder actually meets, and a
 * decoder is measured on input it had no hand in.
 *
 * The JDK's image writer is used as a *tool* here, exactly as qpdf and
 * pdftotext are: it is asked for bytes, and the bytes are handed to this
 * decoder. Its sources inform none of ours. What is compared is the samples —
 * every pixel, every channel, against the same file decoded by the writer's own
 * decoder — never the internals of either.
 *
 * The comparison is against the [Raster], not against `getRGB`: a raster
 * returns the samples as they are in the file, where `getRGB` would rescale a
 * 16-bit image to eight bits and turn a palette index into a colour, hiding
 * exactly the differences this test exists to catch.
 *
 * It also runs the other way once. A datastream this project assembles is
 * handed to the JDK's decoder, which checks the CRCs it was given: our chunk
 * framing and our CRC are then confirmed by something that never saw the code
 * that wrote them.
 */
class PngForeignTest {
    /**
     * Every colour type and bit depth the JDK's writer can be made to produce,
     * decoded here and compared sample for sample with its own decoding.
     *
     * The images are noise rather than a gradient on purpose: an encoder chooses
     * filter types per scanline (§9.2 leaves the choice to it), and noise makes
     * it choose differently from row to row, so a decoder that mishandled one of
     * the five would be caught rather than never asked. The dimensions are
     * deliberately not multiples of eight, so a sub-byte scanline ends with the
     * padding bits §7.2 leaves unspecified.
     */
    @Test
    fun `every colour type the JDK writes decodes to the samples the JDK reads back`() {
        val exercised = mutableSetOf<Pair<PngColourType, Int>>()

        for (kind in foreignImages()) {
            val file = writtenPng(kind.image)
            val ours = PngImage.decode(file)

            assertEquals(kind.colourType, ours.colourType, "${kind.name}: the colour type of the file")
            assertEquals(kind.bitDepth, ours.bitDepth, "${kind.name}: the bit depth of the file")
            exercised += ours.colourType to ours.bitDepth

            val theirs = ImageIO.read(ByteArrayInputStream(file))
                ?: error("${kind.name}: the JDK did not read back the PNG it wrote")
            val raster = theirs.raster
            assertEquals(kind.image.width, ours.width, "${kind.name}: width")
            assertEquals(kind.image.height, ours.height, "${kind.name}: height")
            assertEquals(raster.numBands, ours.colourType.channels, "${kind.name}: samples per pixel")

            for (y in 0 until ours.height) {
                for (x in 0 until ours.width) {
                    for (channel in 0 until raster.numBands) {
                        assertEquals(
                            raster.getSample(x, y, channel),
                            ours.sample(x, y, channel),
                            "${kind.name}: pixel ($x, $y) channel $channel",
                        )
                    }
                }
            }

            if (ours.colourType == PngColourType.INDEXED) {
                assertPaletteMatches(kind.name, theirs.colorModel as IndexColorModel, ours)
            }
        }

        assertEquals(
            PngColourType.entries.toSet(),
            exercised.map { it.first }.toSet(),
            "all five colour types of Table 9 are meant to be exercised by real files",
        )
        assertTrue(
            exercised.any { it.second == 16 } && exercised.any { it.second < 8 },
            "the sweep is meant to cover a 16-bit image and a sub-byte one: $exercised",
        )
    }

    /**
     * A photograph-sized image, whose compressed data the JDK splits across
     * several IDAT chunks — "there is not necessarily any correlation between
     * IDAT chunk boundaries and deflate block boundaries or any other feature of
     * the zlib data" (§10.2), so the concatenation has to happen before anything
     * is decompressed.
     */
    @Test
    fun `an image whose data the JDK splits across IDAT chunks decodes whole`() {
        val image = BufferedImage(320, 240, BufferedImage.TYPE_INT_RGB)
        val random = Random(20260805)
        for (y in 0 until image.height) {
            for (x in 0 until image.width) image.setRGB(x, y, random.nextInt())
        }

        val file = writtenPng(image)
        assertTrue(countChunks(file, "IDAT") > 1, "the JDK is expected to split noise this size across IDAT chunks")

        val ours = PngImage.decode(file)
        val theirs = ImageIO.read(ByteArrayInputStream(file)).raster
        for (y in 0 until ours.height) {
            for (x in 0 until ours.width) {
                for (channel in 0 until 3) {
                    assertEquals(theirs.getSample(x, y, channel), ours.sample(x, y, channel), "pixel ($x, $y)")
                }
            }
        }
    }

    /**
     * The deferral, against a real interlaced file rather than a hand-set byte.
     *
     * The JDK writes Adam7 when asked for a progressive image, and what comes
     * back is a valid PNG this library does not decode. It has to say so: the
     * seven reduced images of §8.1 read as one would be a picture, and a wrong
     * one.
     */
    @Test
    fun `an Adam7 image written by the JDK is refused rather than read as one pass`() {
        val file = writtenPng(colourImage(24, 18), progressive = true)

        // The interlace method is the thirteenth byte of IHDR: the signature,
        // the chunk's length and type, then twelve bytes of fields before it.
        assertEquals(1, file[8 + 4 + 4 + 12].toInt(), "the JDK is expected to write an interlaced file here")

        val fault = assertFailsWith<PngFormatException> { PngImage.decode(file) }
        assertTrue("Adam7" in fault.problem, fault.message)
        assertEquals(8 + 8 + 12, fault.offset)
    }

    /**
     * The other direction, once: a datastream assembled by this project's own
     * test fixtures, decoded by the JDK.
     *
     * It is the CRCs that matter here. This project computes them through a
     * table built from the polynomial of §5.5 and the tests compute them bit by
     * bit from the same clause; both would agree with each other and with
     * nothing else if the clause had been misread. The JDK checks the CRC of
     * every chunk it reads, so a file it accepts is a file whose CRCs are the
     * ones the rest of the world computes.
     *
     * The image is greyscale with alpha — colour type 4 — which is also the one
     * combination the sweep above cannot reach through a plain
     * [BufferedImage] type constant.
     */
    @Test
    fun `a datastream this project assembles is accepted and read back by the JDK`() {
        val width = 5
        val height = 4
        val rows = List(height) { y -> ByteArray(width * 2) { ((y * 40 + it * 7) % 256).toByte() } }
        val file = pngOf(
            width = width,
            height = height,
            bitDepth = 8,
            colourType = 4,
            rows = rows,
            filterTypes = List(height) { it % 5 },
        )

        val theirs = ImageIO.read(ByteArrayInputStream(file))
            ?: error("the JDK did not read the datastream this project assembled")
        assertEquals(width, theirs.width)
        assertEquals(height, theirs.height)
        assertEquals(2, theirs.raster.numBands)

        for (y in 0 until height) {
            for (x in 0 until width) {
                for (channel in 0 until 2) {
                    assertEquals(
                        rows[y][x * 2 + channel].toInt() and 0xFF,
                        theirs.raster.getSample(x, y, channel),
                        "pixel ($x, $y) channel $channel",
                    )
                }
            }
        }
    }

    /** An image of the JDK's, and what Table 12 says the file of it should be. */
    private class ForeignImage(
        val name: String,
        val image: BufferedImage,
        val colourType: PngColourType,
        val bitDepth: Int,
    )

    /**
     * One image of every kind the JDK's PNG writer maps onto a distinct colour
     * type: greyscale at 8 and at 16 bits, truecolour, truecolour with alpha,
     * indexed at 8 bits and at 1, and greyscale with alpha — the last built from
     * a colour model rather than a `BufferedImage` type constant, since none of
     * those is a grey image with an alpha channel.
     */
    private fun foreignImages(): List<ForeignImage> = listOf(
        ForeignImage("truecolour", noise(13, 7, BufferedImage.TYPE_INT_RGB), PngColourType.TRUECOLOUR, 8),
        ForeignImage(
            "truecolour with alpha",
            noise(13, 7, BufferedImage.TYPE_INT_ARGB),
            PngColourType.TRUECOLOUR_ALPHA,
            8,
        ),
        ForeignImage("greyscale", noise(13, 7, BufferedImage.TYPE_BYTE_GRAY), PngColourType.GREYSCALE, 8),
        ForeignImage("greyscale at 16 bits", noise(13, 7, BufferedImage.TYPE_USHORT_GRAY), PngColourType.GREYSCALE, 16),
        ForeignImage("indexed", noise(13, 7, BufferedImage.TYPE_BYTE_INDEXED), PngColourType.INDEXED, 8),
        ForeignImage("indexed at four bits", indexedAtFourBits(13, 7), PngColourType.INDEXED, 4),
        // A two-entry black-and-white palette is written as a greyscale image
        // rather than an indexed one, which is the encoder's choice to make —
        // and it is the sub-byte greyscale case.
        ForeignImage("greyscale at one bit", noise(13, 7, BufferedImage.TYPE_BYTE_BINARY), PngColourType.GREYSCALE, 1),
        ForeignImage("greyscale with alpha", greyWithAlpha(13, 7), PngColourType.GREYSCALE_ALPHA, 8),
    )

    /**
     * An image of sixteen palette entries, which an [IndexColorModel] of four
     * bits per pixel makes a colour type 3 file at bit depth 4 — two pixels to
     * a byte, the leftmost in the high-order bits (§7.2).
     *
     * The entries are deliberately colourful: a palette of greys would let the
     * encoder write a greyscale image instead, as it does for the black and
     * white one above.
     */
    private fun indexedAtFourBits(width: Int, height: Int): BufferedImage {
        val entries = 16
        val reds = ByteArray(entries) { (it * 17).toByte() }
        val greens = ByteArray(entries) { (255 - it * 13).toByte() }
        val blues = ByteArray(entries) { (it * 7 + 40).toByte() }
        val colourModel = IndexColorModel(4, entries, reds, greens, blues)
        val image = BufferedImage(width, height, BufferedImage.TYPE_BYTE_BINARY, colourModel)
        val random = Random(1604)
        for (y in 0 until height) {
            for (x in 0 until width) image.raster.setSample(x, y, 0, random.nextInt(entries))
        }
        return image
    }

    /**
     * A [width] × [height] image of pseudo-random pixels of the given
     * [BufferedImage] type.
     *
     * Noise is what makes the encoder vary its filter type from scanline to
     * scanline, and — for the indexed types — what makes it fill a palette.
     */
    private fun noise(width: Int, height: Int, type: Int): BufferedImage {
        val image = BufferedImage(width, height, type)
        val random = Random(width * 1000 + type)
        for (y in 0 until height) {
            for (x in 0 until width) image.setRGB(x, y, random.nextInt() or (0xFF shl 24))
        }
        // Alpha, where the type has it, is set separately: setRGB above forces
        // it opaque so that the colour values survive premultiplication-free.
        if (image.colorModel.hasAlpha()) {
            for (y in 0 until height) {
                for (x in 0 until width) {
                    image.raster.setSample(x, y, image.raster.numBands - 1, random.nextInt(256))
                }
            }
        }
        return image
    }

    /**
     * A greyscale image with an alpha channel: two 8-bit bands over the grey
     * colour space, which is colour type 4 of Table 12.
     */
    private fun greyWithAlpha(width: Int, height: Int): BufferedImage {
        val colourModel = ComponentColorModel(
            ColorSpace.getInstance(ColorSpace.CS_GRAY),
            true,
            false,
            java.awt.Transparency.TRANSLUCENT,
            DataBuffer.TYPE_BYTE,
        )
        val raster = colourModel.createCompatibleWritableRaster(width, height)
        val random = Random(4004)
        for (y in 0 until height) {
            for (x in 0 until width) {
                raster.setSample(x, y, 0, random.nextInt(256))
                raster.setSample(x, y, 1, random.nextInt(256))
            }
        }
        return BufferedImage(colourModel, raster, false, null)
    }

    /** [image] as a PNG file, Adam7-interlaced when [progressive] asks for it. */
    private fun writtenPng(image: BufferedImage, progressive: Boolean = false): ByteArray {
        val bytes = ByteArrayOutputStream()
        val writer = ImageIO.getImageWritersByFormatName("png").next()
        ImageIO.createImageOutputStream(bytes).use { output ->
            writer.output = output
            val parameters = writer.defaultWriteParam
            if (progressive) parameters.progressiveMode = ImageWriteParam.MODE_DEFAULT
            writer.write(null, IIOImage(image, null, null), parameters)
        }
        writer.dispose()
        return bytes.toByteArray()
    }

    /**
     * The palette the JDK read, entry for entry against the PLTE chunk this
     * decoder kept — "the first entry in PLTE is referenced by pixel value 0"
     * (§11.2.2), which is the order an [IndexColorModel] holds too.
     */
    private fun assertPaletteMatches(name: String, theirs: IndexColorModel, ours: PngImage) {
        val palette = ours.palette ?: error("$name: an indexed image with no palette")
        assertEquals(theirs.mapSize, ours.paletteEntryCount, "$name: palette entries")
        for (entry in 0 until ours.paletteEntryCount) {
            assertEquals(theirs.getRed(entry), palette[entry * 3].toInt() and 0xFF, "$name: red of entry $entry")
            assertEquals(theirs.getGreen(entry), palette[entry * 3 + 1].toInt() and 0xFF, "$name: green of $entry")
            assertEquals(theirs.getBlue(entry), palette[entry * 3 + 2].toInt() and 0xFF, "$name: blue of $entry")
        }
    }

    /** How many chunks of type [type] the datastream [file] carries. */
    private fun countChunks(file: ByteArray, type: String): Int {
        var at = 8
        var found = 0
        while (at + 8 <= file.size) {
            val length = ((file[at].toInt() and 0xFF) shl 24) or ((file[at + 1].toInt() and 0xFF) shl 16) or
                ((file[at + 2].toInt() and 0xFF) shl 8) or (file[at + 3].toInt() and 0xFF)
            val name = String(file, at + 4, 4, Charsets.US_ASCII)
            if (name == type) found++
            at += 12 + length
        }
        return found
    }
}
