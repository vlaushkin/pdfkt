/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.image

import io.pdfkt.core.filter.flateDecode
import io.pdfkt.core.obj.PdfInt
import io.pdfkt.core.obj.PdfName
import io.pdfkt.core.obj.PdfRef
import io.pdfkt.core.obj.PdfStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// ISO 32000-2:2020 §8.9.3 "Sample representation",
// §8.9.5.1 "Image dictionaries — General" (Table 87),
// §8.6.6.3 "Indexed colour spaces", §7.4.4 "FlateDecode filter",
// §11.6.5.2 "Soft-mask images" (Table 143)
// PNG Specification (Third Edition) §6.1, §6.2, §7.2, §11.2.2, §11.3.1.1

/**
 * A PNG turned into an image XObject: which `/ColorSpace` each colour type of
 * Table 9 becomes, at which `/BitsPerComponent`, and where its transparency
 * goes.
 *
 * The assertions are on the samples a reader gets back — every test decodes the
 * stream with this library's own `/FlateDecode` reader and compares byte for
 * byte against the scanlines the datastream was written from. That is the whole
 * claim: §8.9.3 and PNG §7.2 describe one layout, so re-encoding a PNG changes
 * the compression and nothing else.
 */
class PngImageXObjectTest {
    /**
     * Colour type 0 — "each pixel is a greyscale sample" — is `/DeviceGray`
     * (§8.6.4.2), and the scanlines are the samples: nothing is widened,
     * reordered or resampled on the way into the stream.
     */
    @Test
    fun `a greyscale PNG becomes a DeviceGray FlateDecode XObject`() {
        val rows = listOf(byteArrayOf(0, 64, -128, -1), byteArrayOf(-1, -128, 64, 0))
        val image = pngImage("grey", pngOf(width = 4, height = 2, bitDepth = 8, colourType = 0, rows = rows))

        assertEquals(4, image.width)
        assertEquals(2, image.height)
        assertEquals(ImageColorSpace.DEVICE_GRAY, image.colorSpace)
        assertEquals(8, image.bitsPerComponent)
        assertEquals(ImageFilter.FLATE_DECODE, image.filter)
        assertNull(image.softMask, "an image with neither an alpha channel nor a tRNS chunk is opaque")

        val stream = xObjectOf(image)
        assertEquals(PdfName("FlateDecode"), stream.dict.entries[PdfName("Filter")])
        assertEquals(PdfName("DeviceGray"), stream.dict.entries[PdfName("ColorSpace")])
        assertEquals(PdfInt(8L), stream.dict.entries[PdfName("BitsPerComponent")])
        assertContentEquals(rows[0] + rows[1], flateDecode(stream.data))
    }

    /**
     * The sub-byte depths of Table 12 stay sub-byte. §7.2 packs "the leftmost
     * sample in the high-order bits of a byte" and pads the row out; §8.9.3 says
     * the identical thing of image sample data, so a four-bit image is written
     * as `/BitsPerComponent 4` over the same bytes rather than expanded to
     * eight.
     */
    @Test
    fun `a four bit greyscale PNG keeps its bit depth and its packing`() {
        // Three pixels a row at four bits each: one and a half bytes, padded to
        // two, the last four bits unused.
        val rows = listOf(byteArrayOf(0x0F, 0x30), byteArrayOf(0xA5.toByte(), 0x00))
        val image = pngImage(
            "packed",
            pngOf(width = 3, height = 2, bitDepth = 4, colourType = 0, rows = rows),
        )

        assertEquals(ImageColorSpace.DEVICE_GRAY, image.colorSpace)
        assertEquals(4, image.bitsPerComponent)
        assertContentEquals(rows[0] + rows[1], flateDecode(xObjectOf(image).data))
    }

    /**
     * Sixteen bits per sample survive too: Table 87 admits the value "from PDF
     * 1.5", and PNG writes such samples "in network byte order (MSB first, LSB
     * second)" (§7.2), which is the order a PDF bit stream reads them in.
     */
    @Test
    fun `a sixteen bit greyscale PNG is written at sixteen bits per component`() {
        val rows = listOf(byteArrayOf(0x12, 0x34, 0xAB.toByte(), 0xCD.toByte()))
        val image = pngImage("deep", pngOf(width = 2, height = 1, bitDepth = 16, colourType = 0, rows = rows))

        assertEquals(16, image.bitsPerComponent)
        assertContentEquals(rows[0], flateDecode(xObjectOf(image).data))
    }

    /** Colour type 2 — "an R,G,B triple" — is `/DeviceRGB` (§8.6.4.3), in that order. */
    @Test
    fun `a truecolour PNG becomes a DeviceRGB XObject`() {
        val rows = listOf(byteArrayOf(-1, 0, 0, 0, -1, 0), byteArrayOf(0, 0, -1, 32, 64, 96))
        val image = pngImage("colour", pngOf(width = 2, height = 2, bitDepth = 8, colourType = 2, rows = rows))

        assertEquals(ImageColorSpace.DEVICE_RGB, image.colorSpace)
        assertEquals(8, image.bitsPerComponent)
        assertContentEquals(rows[0] + rows[1], flateDecode(xObjectOf(image).data))
    }

    /**
     * Colour type 3 stays indexed. §11.2.2 makes a PLTE entry "a three-byte
     * series … red, green, blue" and §8.6.6.3 makes the lookup of a DeviceRGB
     * Indexed space "R0 G0 B0 R1 G1 B1" — the same bytes, so the palette is the
     * colour table and the pixels remain indices at the PNG's own bit depth.
     *
     * `hival` "specifies the maximum valid index value", one less than the
     * number of entries, and the whole array is asserted as it is serialized:
     * `[/Indexed /DeviceRGB 2 <…>]`.
     */
    @Test
    fun `an indexed PNG becomes an Indexed colour space over its palette`() {
        val palette = byteArrayOf(-1, 0, 0, 0, -1, 0, 0, 0, -1)
        // Two bits a pixel, four pixels a row: one byte, indices 0 1 2 0.
        val rows = listOf(byteArrayOf(0b00_01_10_00), byteArrayOf(0b10_10_01_00.toByte()))
        val image = pngImage(
            "logo",
            pngOf(
                width = 4,
                height = 2,
                bitDepth = 2,
                colourType = 3,
                rows = rows,
                extraChunks = listOf(pngChunk("PLTE", palette)),
            ),
        )

        val space = image.colorSpace
        assertTrue(space is ImageColorSpace.Indexed, "an indexed PNG is an Indexed colour space, not $space")
        assertEquals(3, space.entryCount)
        assertContentEquals(palette, space.lookup)
        assertEquals(2, image.bitsPerComponent)
        assertEquals("[/Indexed /DeviceRGB 2 <FF000000FF000000FF>]", space.pdfObject.serialized())
        assertContentEquals(rows[0] + rows[1], flateDecode(xObjectOf(image).data))
    }

    /**
     * Colour type 6 is split. "Each pixel is an R,G,B triple followed by an
     * alpha sample" (Table 12): the triples become the image and the alpha
     * samples the `/SMask`, "a subsidiary image XObject defining a soft-mask
     * image" (Table 87), which Table 143 requires to be DeviceGray.
     */
    @Test
    fun `an RGBA PNG splits into DeviceRGB samples and a DeviceGray SMask`() {
        val rows = listOf(
            byteArrayOf(-1, 0, 0, 0, 0, -1, 0, 64),
            byteArrayOf(0, 0, -1, -128, 1, 2, 3, -1),
        )
        val image = pngImage("badge", pngOf(width = 2, height = 2, bitDepth = 8, colourType = 6, rows = rows))

        assertEquals(ImageColorSpace.DEVICE_RGB, image.colorSpace)
        assertContentEquals(
            byteArrayOf(-1, 0, 0, 0, -1, 0, 0, 0, -1, 1, 2, 3),
            flateDecode(xObjectOf(image).data),
            "the alpha channel was left interleaved with the colour samples",
        )

        val mask = assertNotNull(image.softMask, "an alpha channel is transparency and needs an /SMask")
        assertEquals(ImageColorSpace.DEVICE_GRAY, mask.colorSpace)
        assertEquals(8, mask.bitsPerComponent)
        assertEquals(2, mask.width)
        assertEquals(2, mask.height)
        assertNull(mask.softMask, "in a soft-mask image dictionary SMask shall be absent (Table 143)")
        assertContentEquals(byteArrayOf(0, 64, -128, -1), flateDecode(maskStreamOf(image).data))
    }

    /** Colour type 4 splits the same way, and at sixteen bits a sample is two bytes. */
    @Test
    fun `a sixteen bit greyscale alpha PNG splits sample by sample`() {
        val rows = listOf(byteArrayOf(0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x78))
        val image = pngImage("fade", pngOf(width = 2, height = 1, bitDepth = 16, colourType = 4, rows = rows))

        assertEquals(ImageColorSpace.DEVICE_GRAY, image.colorSpace)
        assertEquals(16, image.bitsPerComponent)
        assertContentEquals(byteArrayOf(0x11, 0x22, 0x55, 0x66), flateDecode(xObjectOf(image).data))

        val mask = assertNotNull(image.softMask)
        assertEquals(16, mask.bitsPerComponent)
        assertContentEquals(byteArrayOf(0x33, 0x44, 0x77, 0x78), flateDecode(maskStreamOf(image).data))
    }

    /**
     * A tRNS chunk on a greyscale image names one value: "pixels of the
     * specified grey level are to be treated as transparent", everything else
     * opaque. The chunk carries "two bytes per sample … regardless of the image
     * bit depth", so at four bits the grey 5 is the bytes `00 05`.
     */
    @Test
    fun `a tRNS grey level becomes a transparent sample in the SMask`() {
        val rows = listOf(byteArrayOf(0x05, 0x53))
        val image = pngImage(
            "keyed",
            pngOf(
                width = 4,
                height = 1,
                bitDepth = 4,
                colourType = 0,
                rows = rows,
                extraChunks = listOf(pngChunk("tRNS", byteArrayOf(0x00, 0x05))),
            ),
        )

        assertEquals(4, image.bitsPerComponent, "the colour samples keep the PNG's depth")
        val mask = assertNotNull(image.softMask, "a tRNS chunk is transparency and needs an /SMask")
        assertEquals(8, mask.bitsPerComponent, "a colour-key mask is one byte of opacity per pixel")
        // Greys 0, 5, 5, 3: the two fives are the transparent level.
        assertContentEquals(byteArrayOf(-1, 0, 0, -1), flateDecode(maskStreamOf(image).data))
    }

    /** The same on colour type 2, where the key is a whole "specified colour value". */
    @Test
    fun `a tRNS colour value becomes a transparent pixel in the SMask`() {
        val rows = listOf(byteArrayOf(-1, 0, 0, 0, -1, 0, -1, 0, 0))
        val image = pngImage(
            "keyed",
            pngOf(
                width = 3,
                height = 1,
                bitDepth = 8,
                colourType = 2,
                rows = rows,
                extraChunks = listOf(pngChunk("tRNS", byteArrayOf(0, -1, 0, 0, 0, 0))),
            ),
        )

        // The key is the two-byte-per-sample triple 00FF 0000 0000, which is the
        // red of the first and third pixels; the green one is opaque.
        val mask = assertNotNull(image.softMask)
        assertContentEquals(byteArrayOf(0, -1, 0), flateDecode(maskStreamOf(image).data))
        assertContentEquals(rows[0], flateDecode(xObjectOf(image).data), "the colour samples were altered")
    }

    /**
     * On an indexed image tRNS is "a series of one-byte alpha values,
     * corresponding to entries in the PLTE chunk" and "may contain fewer values
     * than there are palette entries. In this case, the alpha value for all
     * remaining palette entries is assumed to be 255."
     */
    @Test
    fun `an indexed tRNS becomes one alpha byte per pixel with the rest opaque`() {
        val palette = byteArrayOf(-1, 0, 0, 0, -1, 0, 0, 0, -1, 7, 7, 7)
        val rows = listOf(byteArrayOf(0, 1, 2, 3))
        val image = pngImage(
            "sprite",
            pngOf(
                width = 4,
                height = 1,
                bitDepth = 8,
                colourType = 3,
                rows = rows,
                extraChunks = listOf(pngChunk("PLTE", palette), pngChunk("tRNS", byteArrayOf(0, 0x40))),
            ),
        )

        val mask = assertNotNull(image.softMask)
        assertEquals(8, mask.bitsPerComponent)
        assertContentEquals(byteArrayOf(0, 0x40, -1, -1), flateDecode(maskStreamOf(image).data))
    }

    /**
     * An alpha channel that is opaque everywhere states no transparency, and
     * Table 87 spells that as no entry at all: "If SMask is absent and
     * SMaskInData has value 0, the image shall have no associated soft mask."
     */
    @Test
    fun `an alpha channel with nothing transparent in it is not written`() {
        val rows = listOf(byteArrayOf(-1, 0, 0, -1, 0, -1, 0, -1))
        val image = pngImage("opaque", pngOf(width = 2, height = 1, bitDepth = 8, colourType = 6, rows = rows))

        assertNull(image.softMask, "a fully opaque alpha channel is not a soft mask")
        assertEquals(null, xObjectOf(image).dict.entries[PdfName("SMask")])
    }

    /** A tRNS chunk that matches no pixel of the image is transparency nowhere. */
    @Test
    fun `a tRNS value no pixel carries leaves the image opaque`() {
        val image = pngImage(
            "unkeyed",
            pngOf(
                width = 2,
                height = 1,
                bitDepth = 8,
                colourType = 0,
                rows = listOf(byteArrayOf(10, 20)),
                extraChunks = listOf(pngChunk("tRNS", byteArrayOf(0, 30))),
            ),
        )

        assertNull(image.softMask)
    }

    /**
     * The `/SMask` reaches the file as an indirect reference, because its type
     * in Table 87 is "stream" and "all streams shall be indirect objects"
     * (§7.3.8) — and it is the only entry of an image dictionary that is one.
     */
    @Test
    fun `the SMask entry is an indirect reference to the mask stream`() {
        val rows = listOf(byteArrayOf(-1, 0, 0, 0))
        val image = pngImage("badge", pngOf(width = 1, height = 1, bitDepth = 8, colourType = 6, rows = rows))

        var next = 7
        val defined = mutableListOf<PdfStream>()
        val stream = ImageCatalog().resolve(image).imageXObject { value ->
            defined += value as PdfStream
            PdfRef(next++)
        }

        assertEquals(PdfRef(7), stream.dict.entries[PdfName("SMask")])
        assertEquals(1, defined.size, "one soft mask, one object")
        assertEquals(PdfName("Image"), defined.single().dict.entries[PdfName("Subtype")])
        assertEquals(PdfName("DeviceGray"), defined.single().dict.entries[PdfName("ColorSpace")])
    }

    /**
     * Table 143 on a mask a caller builds by hand: "ColorSpace Required; shall
     * be DeviceGray", and "SMask shall be absent".
     */
    @Test
    fun `a soft mask outside Table 143 is refused`() {
        val grey = ImageRef("mask", 2, 1, ImageColorSpace.DEVICE_GRAY, data = ByteArray(2))
        val colour = ImageRef("mask", 2, 1, ImageColorSpace.DEVICE_RGB, data = ByteArray(6))

        assertFailsWith<IllegalArgumentException> {
            ImageRef("image", 2, 1, ImageColorSpace.DEVICE_RGB, data = ByteArray(6), softMask = colour)
        }
        assertFailsWith<IllegalArgumentException> {
            ImageRef(
                "image",
                2,
                1,
                ImageColorSpace.DEVICE_RGB,
                data = ByteArray(6),
                softMask = ImageRef("nested", 2, 1, ImageColorSpace.DEVICE_GRAY, ByteArray(2), softMask = grey),
            )
        }
    }

    /**
     * §8.6.6.3: an Indexed space "shall be indexed by integers in the range 0 to
     * hival", and "hival shall be no greater than 255, which is the integer
     * required to index a table with 8-bit index values" — so a sixteen-bit
     * sample cannot be an index.
     */
    @Test
    fun `an indexed image deeper than eight bits is refused`() {
        val space = ImageColorSpace.Indexed(ImageColorSpace.DEVICE_RGB, ByteArray(6))
        assertFailsWith<IllegalArgumentException> {
            ImageRef("deep", 2, 1, space, data = ByteArray(4), bitsPerComponent = 16)
        }
    }

    /** A colour table is "m x (hival + 1) bytes long", and hival is at most 255. */
    @Test
    fun `a colour table that is not whole colours is refused`() {
        assertFailsWith<IllegalArgumentException> {
            ImageColorSpace.Indexed(ImageColorSpace.DEVICE_RGB, ByteArray(7))
        }
        assertFailsWith<IllegalArgumentException> {
            ImageColorSpace.Indexed(ImageColorSpace.DEVICE_RGB, ByteArray(257 * 3))
        }
    }

    /**
     * Two PNGs alike in every colour sample and different in their alpha are two
     * images, so one id cannot name both: what a page shows is the colour
     * through the mask, and a catalog that ignored the mask would draw one of
     * them where the other was asked for.
     */
    @Test
    fun `images differing only in their alpha are different images`() {
        val opaque = pngImage("badge", pngOf(1, 1, 8, 6, listOf(byteArrayOf(-1, 0, 0, -1))))
        val faded = pngImage("badge", pngOf(1, 1, 8, 6, listOf(byteArrayOf(-1, 0, 0, 64))))

        val catalog = ImageCatalog()
        catalog.resolve(opaque)
        assertFailsWith<IllegalArgumentException> { catalog.resolve(faded) }
    }

    /** The image XObject of [image], for reading its dictionary and its stream. */
    private fun xObjectOf(image: ImageRef): PdfStream = ImageCatalog().resolve(image).imageXObject { PdfRef(1) }

    /** The `/SMask` stream of [image] — the object the parent's entry refers to. */
    private fun maskStreamOf(image: ImageRef): PdfStream {
        val masks = mutableListOf<PdfStream>()
        ImageCatalog().resolve(image).imageXObject { value ->
            masks += value as PdfStream
            PdfRef(masks.size)
        }
        return masks.single()
    }
}
