/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.image

import io.pdfkt.core.obj.PdfName
import io.pdfkt.core.obj.PdfObject
import io.pdfkt.core.obj.PdfRef
import io.pdfkt.core.obj.writePdfObject
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertSame

// ISO 32000-2:2020 §8.9.3 "Sample representation",
// §8.9.5.1 "Image dictionaries — General" (Table 87),
// §7.8.3 "Resource dictionaries" (Table 34)

/**
 * What Table 87 requires of an image XObject, and what a document-scoped catalog
 * adds to it: one name per image, one object per image, per document.
 */
class ImageCatalogTest {
    /**
     * Table 87's required entries, written and nothing else: `/Width`,
     * `/Height`, `/ColorSpace`, `/BitsPerComponent`, plus the `/Subtype /Image`
     * the `Do` operator dispatches on (Table 86) and the optional `/Type
     * /XObject`. `/Length` is the stream's own (Table 5).
     */
    @Test
    fun `an image XObject carries the entries Table 87 requires`() {
        val catalog = ImageCatalog()
        val image = catalog.resolve(redAndBlue())
        assertEquals(
            "<</Length 12 /Type /XObject /Subtype /Image /Width 2 /Height 2 " +
                "/ColorSpace /DeviceRGB /BitsPerComponent 8>>",
            image.imageXObject(NUMBERING).dict.serialized(),
        )
        assertEquals(12, image.imageXObject(NUMBERING).data.size)
    }

    /** A filtered image declares how its bytes are encoded — §7.4, Table 5. */
    @Test
    fun `a filter is declared when the samples are encoded`() {
        val jpeg =
            ImageRef(
                id = "photo",
                width = 64,
                height = 48,
                colorSpace = ImageColorSpace.DEVICE_RGB,
                data = byteArrayOf(1, 2, 3, 4),
                filter = ImageFilter.DCT_DECODE,
            )
        assertEquals(
            "<</Length 4 /Type /XObject /Subtype /Image /Width 64 /Height 48 " +
                "/ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode>>",
            ImageCatalog().resolve(jpeg).imageXObject(NUMBERING).dict.serialized(),
        )
    }

    /**
     * §8.9.3: a row is a continuous bit stream except that "each row of sample
     * data shall begin on a byte boundary", so 2 × 2 samples of three 8-bit
     * components are twelve bytes and eleven is an image with a sample missing.
     */
    @Test
    fun `unfiltered samples shorter than the image are refused`() {
        val short =
            assertFailsWith<IllegalArgumentException> {
                ImageRef("short", 2, 2, ImageColorSpace.DEVICE_RGB, data = ByteArray(11))
            }
        assertEquals(true, short.message?.contains("needs 12"), short.message)
    }

    /**
     * The padding rule of the same clause: "If the number of data bits per row is
     * not a multiple of 8, the end of the row is padded with extra bits to fill
     * out the last byte." Three 1-bit samples per row are one byte per row.
     */
    @Test
    fun `a row of a one bit image is padded to a byte`() {
        ImageRef("mask", 3, 2, ImageColorSpace.DEVICE_GRAY, data = ByteArray(2), bitsPerComponent = 1)
        assertFailsWith<IllegalArgumentException> {
            ImageRef("mask", 3, 2, ImageColorSpace.DEVICE_GRAY, data = ByteArray(1), bitsPerComponent = 1)
        }
    }

    /** "The value shall be 1, 2, 4, 8, or (from PDF 1.5) 16" (Table 87). */
    @Test
    fun `a bit depth outside the table is refused`() {
        assertFailsWith<IllegalArgumentException> {
            ImageRef("odd", 1, 1, ImageColorSpace.DEVICE_GRAY, data = ByteArray(4), bitsPerComponent = 12)
        }
    }

    /**
     * Resolution is per document and by id: the same image asked for twice is
     * one entry, one name and — through [ImageCatalog.imageResources] — one
     * object in the file.
     */
    @Test
    fun `an image resolved twice is one image of the document`() {
        val catalog = ImageCatalog()
        val image = redAndBlue()
        val first = catalog.resolve(image)
        assertSame(first, catalog.resolve(image))
        assertEquals(PdfName("Im1"), first.resourceName)
        assertEquals(1, catalog.images().size)
    }

    /** Two documents are two catalogs, and share nothing. */
    @Test
    fun `two catalogs resolve the same reference separately`() {
        val image = redAndBlue()
        assertNotEquals(ImageCatalog().resolve(image), ImageCatalog().resolve(image))
    }

    /** Names are handed out in first-use order and mean one image per document. */
    @Test
    fun `each image of a document gets its own resource name`() {
        val catalog = ImageCatalog()
        catalog.resolve(redAndBlue())
        val second = catalog.resolve(ImageRef("grey", 2, 1, ImageColorSpace.DEVICE_GRAY, data = ByteArray(2)))
        assertEquals(PdfName("Im2"), second.resourceName)
    }

    /**
     * Table 34: "A dictionary that maps resource names to external objects." The
     * values are indirect references, which is what §7.3.8 requires of a stream
     * and what lets two pages share one image.
     */
    @Test
    fun `the XObject subdictionary maps every name to an indirect object`() {
        val catalog = ImageCatalog()
        catalog.resolve(redAndBlue())
        catalog.resolve(ImageRef("grey", 2, 1, ImageColorSpace.DEVICE_GRAY, data = ByteArray(2)))
        var next = 1
        val resources = catalog.imageResources { PdfRef(next++) }
        assertEquals("<</Im1 1 0 R /Im2 2 0 R>>", resources.serialized())
    }

    /**
     * An id names one image within one document. Two different images under one
     * id would mean a page drawing the first where the second was asked for,
     * reported as a success — refused instead, per the no-silent-loss policy.
     */
    @Test
    fun `two different images under one id are refused`() {
        val catalog = ImageCatalog()
        catalog.resolve(redAndBlue())
        assertFailsWith<IllegalArgumentException> {
            catalog.resolve(ImageRef("swatch", 2, 2, ImageColorSpace.DEVICE_RGB, data = ByteArray(12)))
        }
    }

    /** An equal copy of the same samples under the same id is the same image. */
    @Test
    fun `an equal copy of an image resolves to the entry already there`() {
        val catalog = ImageCatalog()
        val first = catalog.resolve(redAndBlue())
        assertSame(first, catalog.resolve(redAndBlue()))
    }

    /** A 2 × 2 swatch: red, blue over blue, red — twelve bytes of DeviceRGB. */
    private fun redAndBlue(): ImageRef = ImageRef(
        id = "swatch",
        width = 2,
        height = 2,
        colorSpace = ImageColorSpace.DEVICE_RGB,
        data =
        byteArrayOf(
            RED, 0, 0, 0, 0, RED,
            0, 0, RED, RED, 0, 0,
        ),
    )

    private companion object {
        const val RED: Byte = 0x7F
    }
}

/**
 * Object numbers for an image XObject that needs none: `/SMask` is the only
 * entry written as an indirect reference (§7.3.8), so an image with no soft mask
 * never calls this.
 */
internal val NUMBERING: (PdfObject) -> PdfRef = { PdfRef(0) }

/** The object as the serializer of §7.3 writes it, for comparing whole dictionaries. */
internal fun PdfObject.serialized(): String {
    val buffer = Buffer()
    buffer.writePdfObject(this)
    return buffer.readByteArray().decodeToString()
}
