/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.image

import io.pdfkt.core.obj.PdfArray
import io.pdfkt.core.obj.PdfInt
import io.pdfkt.core.obj.PdfName
import io.pdfkt.core.obj.PdfObject
import io.pdfkt.core.obj.PdfString

// ISO 32000-2:2020 §8.9.3 "Sample representation",
// §8.9.5.1 "Image dictionaries — General" (Table 87),
// §8.6.4 "Device colour spaces", §8.6.6.3 "Indexed colour spaces",
// §11.6.5.2 "Soft-mask images" (Table 143), §7.4 "Filters"

/**
 * Which image a plan draws, as the caller names it — the counterpart of
 * `FontRef` for the other kind of resource a page carries.
 *
 * It carries the four parameters §8.9.3 says an image is described by — "The
 * width of the image in samples, The height of the image in samples, The number
 * of colour components per sample, The number of bits per colour component" —
 * the samples themselves, and how those samples are encoded. That is exactly
 * what Table 87 requires of an image dictionary and nothing more: an
 * [ImageCatalog] turns one of these into the image XObject a page's `/XObject`
 * resources name, and no other part of the library reads it.
 *
 * The layout above it never looks inside. It needs [width] and [height] to fit
 * an image to a column, and it needs [id] to talk about the same image twice;
 * the bytes are the emitter's business.
 *
 * @property id names this image within one document, the way `FontRef.Supplied`
 *   names a font program: two references carrying the same id are the same
 *   image however many instances a caller builds, and two *different* images
 *   under one id are an error rather than a document that draws one of them
 *   twice.
 * @property width the width in samples, an integer per Table 87 ("Required").
 * @property height the height in samples (Table 87, "Required").
 * @property colorSpace "The colour space in which image samples shall be
 *   specified" (Table 87, Required for images) — which also fixes how many
 *   components a sample has, since "The number of colour components shall be
 *   inferred from the colour space specified in the dictionary" (§8.9.3).
 * @property bitsPerComponent "The number of bits used to represent each colour
 *   component … The value shall be 1, 2, 4, 8, or (from PDF 1.5) 16" (Table 87).
 * @property filter how [data] is encoded, or null when the bytes are the samples
 *   themselves. A JPEG is embedded as it arrived, under [ImageFilter.DCT_DECODE]
 *   — nothing decodes it.
 * @property softMask the image's alpha, as the `/SMask` of Table 87: "A
 *   subsidiary image XObject defining a soft-mask image (see 11.6.5.2,
 *   "Soft-mask images") that shall be used as a source of mask shape or mask
 *   opacity values in the transparent imaging model." Null where the image is
 *   opaque — "If SMask is absent and SMaskInData has value 0, the image shall
 *   have no associated soft mask". A mask is an image like any other, which is
 *   why it is one of these, restricted by Table 143.
 */
public class ImageRef(
    public val id: String,
    public val width: Int,
    public val height: Int,
    public val colorSpace: ImageColorSpace,
    internal val data: ByteArray,
    public val bitsPerComponent: Int = BITS_PER_BYTE,
    public val filter: ImageFilter? = null,
    public val softMask: ImageRef? = null,
) {
    init {
        require(id.isNotEmpty()) {
            "an image needs an id: it is what names it within one document and what its resolution " +
                "is keyed by"
        }
        require(width > 0 && height > 0) {
            "an image is $width x $height samples: Width and Height are the size of the sample data " +
                "and a dimension of zero describes no image (ISO 32000-2:2020 §8.9.5.1, Table 87)"
        }
        require(bitsPerComponent in ALLOWED_BITS_PER_COMPONENT) {
            "BitsPerComponent shall be 1, 2, 4, 8 or 16; \"$id\" declares $bitsPerComponent " +
                "(ISO 32000-2:2020 §8.9.5.1, Table 87)"
        }
        // §8.6.6.3: "hival shall be no greater than 255, which is the integer
        // required to index a table with 8-bit index values." A sample wider
        // than the table can be indexed by is a sample that cannot mean an
        // index, whatever the colour table holds.
        require(colorSpace !is ImageColorSpace.Indexed || bitsPerComponent <= BITS_PER_BYTE) {
            "the image \"$id\" indexes a colour table with $bitsPerComponent-bit samples; an Indexed " +
                "colour space is indexed \"by integers in the range 0 to hival\" and \"hival shall be no " +
                "greater than 255, which is the integer required to index a table with 8-bit index " +
                "values\" (ISO 32000-2:2020 §8.6.6.3)"
        }
        require(data.isNotEmpty()) {
            "the image \"$id\" arrived with no sample data (ISO 32000-2:2020 §8.9.3)"
        }
        if (filter == null) require(data.size >= unfilteredSize()) { tooFewSamples() }
        if (softMask != null) requireSoftMaskOfTable143(softMask)
    }

    /**
     * Table 143, "Restrictions on the entries in a soft-mask image dictionary",
     * on the two entries a caller could get wrong here.
     *
     * `/ColorSpace` is "Required; shall be DeviceGray" — a mask is an opacity
     * per sample and nothing else — and `/SMask` "shall be absent", so the
     * recursion stops at one level. `/Width` and `/Height` are deliberately not
     * checked against the parent's: they are "independent of it" as long as no
     * `/Matte` entry is written, since "both images shall be mapped to the unit
     * square in user space (as are all images), regardless of whether the
     * samples coincide individually", and this library writes no `/Matte`.
     */
    private fun requireSoftMaskOfTable143(mask: ImageRef) {
        require(mask.colorSpace == ImageColorSpace.DEVICE_GRAY) {
            "the soft mask of \"$id\" is in ${mask.colorSpace}; the ColorSpace of a soft-mask image is " +
                "\"Required; shall be DeviceGray\" (ISO 32000-2:2020 §11.6.5.2, Table 143)"
        }
        require(mask.softMask == null) {
            "the soft mask of \"$id\" carries a soft mask of its own; in a soft-mask image dictionary " +
                "SMask \"shall be absent\" (ISO 32000-2:2020 §11.6.5.2, Table 143)"
        }
    }

    /**
     * How many bytes of unfiltered samples an image of this size takes, per
     * §8.9.3: the components of a row are a continuous bit stream, "Byte
     * boundaries shall be ignored, except that each row of sample data shall
     * begin on a byte boundary. If the number of data bits per row is not a
     * multiple of 8, the end of the row is padded with extra bits to fill out
     * the last byte."
     *
     * So a row is `ceil(width × components × bitsPerComponent / 8)` bytes and the
     * image is [height] of them.
     */
    private fun unfilteredSize(): Int {
        val bitsPerRow = width * colorSpace.components * bitsPerComponent
        return (bitsPerRow + BITS_PER_BYTE - 1) / BITS_PER_BYTE * height
    }

    private fun tooFewSamples(): String =
        "the image \"$id\" carries ${data.size} bytes of samples where $width x $height in " +
            "$colorSpace at $bitsPerComponent bits per component needs " +
            "${unfilteredSize()} — each row begins on a byte boundary and is padded to it " +
            "(ISO 32000-2:2020 §8.9.3)"

    /** The id and the shape; the samples are not something to print. */
    override fun toString(): String = "ImageRef(id=$id, ${width}x$height, $colorSpace, $bitsPerComponent bpc" +
        (if (softMask != null) ", SMask" else "") + ")"

    private companion object {
        /** "The value shall be 1, 2, 4, 8, or (from PDF 1.5) 16" (Table 87). */
        val ALLOWED_BITS_PER_COMPONENT: Set<Int> = setOf(1, 2, 4, 8, 16)
    }
}

/**
 * "Sample data shall be represented as a stream of bytes, interpreted as 8-bit
 * unsigned integers in the range 0 to 255" (§8.9.3) — the byte the padding rule
 * of that clause is stated in terms of.
 */
private const val BITS_PER_BYTE: Int = 8

/**
 * "The colour space in which image samples shall be specified" (Table 87), which
 * that table types as "name or array" — so this is a hierarchy and not a name,
 * and the `when` that writes it cannot forget a case.
 *
 * Two families are here, and they are the two a decoded image of this library
 * can be in: the device spaces of §8.6.4, and the Indexed space of §8.6.6.3 that
 * a palette is.
 *
 * @property pdfObject what is written as the `/ColorSpace` of the image
 *   dictionary.
 * @property components how many numbers one *sample* carries, which §8.9.3 says
 *   is not written anywhere: "The number of colour components shall be inferred
 *   from the colour space specified in the dictionary."
 */
public sealed class ImageColorSpace {
    public abstract val pdfObject: PdfObject
    public abstract val components: Int

    /**
     * A device colour space (§8.6.4) — one of the ones "that can be specified by
     * a name and no additional parameters" (§8.6.8), so `/ColorSpace` is that
     * name and no entry of the resource dictionary is needed.
     *
     * The three of them are the values on [ImageColorSpace]'s companion; the
     * constructor is internal because a fourth device space is not something a
     * caller can invent — §8.6.4 lists exactly these.
     */
    public class Device internal constructor(public val pdfName: PdfName, override val components: Int) :
        ImageColorSpace() {
        override val pdfObject: PdfObject get() = pdfName

        override fun toString(): String = "/${pdfName.name}"
    }

    /**
     * An Indexed colour space, §8.6.6.3: "A PDF reader shall treat each sample
     * value as an index into the colour table and shall use the colour value it
     * finds there."
     *
     * Written as the four-element array the clause prescribes,
     * `[/Indexed base hival lookup]`, where hival "specifies the maximum valid
     * index value" — one less than the number of entries, because "the colour
     * table shall be indexed by integers in the range 0 to hival" — and the
     * lookup is the colour table as a byte string, which is the form PDF 1.2
     * added and the smaller one for a table of at most 768 bytes.
     *
     * @property base "an array or name that identifies the base colour space in
     *   which the values in the colour table are to be interpreted"; §8.6.6.3
     *   forbids "a Pattern space or another Indexed space", which typing it as a
     *   [Device] rules out by construction.
     * @property lookup the colour table, "m × (hival + 1) bytes long, where m is
     *   the number of colour components in the base colour space", its
     *   components "consecutively in the string": R0 G0 B0 R1 G1 B1 … for a
     *   DeviceRGB base (EXAMPLE 1).
     */
    public class Indexed(public val base: Device, public val lookup: ByteArray) : ImageColorSpace() {
        /** "hival + 1" — how many colours the table holds. */
        public val entryCount: Int = lookup.size / base.components

        init {
            require(lookup.size % base.components == 0) {
                "the colour table is ${lookup.size} bytes and $base has ${base.components} components " +
                    "per colour; a colour table is \"m x (hival + 1) bytes long, where m is the number of " +
                    "colour components in the base colour space\" (ISO 32000-2:2020 §8.6.6.3)"
            }
            require(entryCount in 1..MAX_INDEXED_ENTRIES) {
                "the colour table holds $entryCount colours; it is indexed \"by integers in the range 0 " +
                    "to hival\" and \"hival shall be no greater than 255\" (ISO 32000-2:2020 §8.6.6.3)"
            }
        }

        override val components: Int get() = 1

        override val pdfObject: PdfObject
            get() = PdfArray(
                listOf(
                    PdfName("Indexed"),
                    base.pdfName,
                    PdfInt((entryCount - 1).toLong()),
                    PdfString(lookup, hex = true),
                ),
            )

        /** Two colour tables are one space when they are the same table over the same base. */
        override fun equals(other: Any?): Boolean =
            other is Indexed && other.base == base && other.lookup.contentEquals(lookup)

        override fun hashCode(): Int = 31 * base.hashCode() + lookup.contentHashCode()

        override fun toString(): String = "[/Indexed $base ${entryCount - 1} <${lookup.size} bytes>]"
    }

    public companion object {
        /** §8.6.4.2 — one component, "0.0 … 1.0", black to white. */
        public val DEVICE_GRAY: Device = Device(PdfName("DeviceGray"), 1)

        /** §8.6.4.3 — red, green and blue, in that order within a sample. */
        public val DEVICE_RGB: Device = Device(PdfName("DeviceRGB"), 3)

        /** §8.6.4.4 — cyan, magenta, yellow and black. */
        public val DEVICE_CMYK: Device = Device(PdfName("DeviceCMYK"), 4)

        /** "hival shall be no greater than 255", so 256 colours is the largest table. */
        private const val MAX_INDEXED_ENTRIES: Int = 256
    }
}

/**
 * How the bytes of an image arrived encoded — the `/Filter` of the stream
 * (§7.4), "the name of a filter that shall be applied in processing the stream
 * data" (Table 5).
 *
 * Only the two an image of this library can be in: a JPEG embedded as it stands,
 * and samples compressed by our own deflate. Both are named here rather than
 * taken as a `PdfName` so that a caller cannot declare a filter the writer does
 * not produce the data for.
 */
public enum class ImageFilter(public val pdfName: PdfName) {
    /**
     * §7.4.8 — "Decompresses data encoded using a DCT (discrete cosine
     * transform) technique based on the JPEG standard". The bytes are the JPEG
     * file's own; nothing in this library decodes them.
     */
    DCT_DECODE(PdfName("DCTDecode")),

    /** §7.4.4 — samples deflated per RFC 1950/1951, by our own encoder. */
    FLATE_DECODE(PdfName("FlateDecode")),
}
