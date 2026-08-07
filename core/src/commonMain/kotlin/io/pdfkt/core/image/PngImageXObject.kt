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

// ISO 32000-2:2020 §8.9.3 "Sample representation",
// §8.9.5.1 "Image dictionaries — General" (Table 87),
// §8.6.4 "Device colour spaces", §8.6.6.3 "Indexed colour spaces",
// §7.4.4 "LZWDecode and FlateDecode filters",
// §11.6.5.2 "Soft-mask images" (Table 143)
// Portable Network Graphics (PNG) Specification (Third Edition),
// W3C Recommendation 24 June 2025
//   §6.1 "Color types and values", §6.2 "Alpha representation",
//   §7.2 "Scanlines", §11.2.2 "PLTE Palette", §11.3.1.1 "tRNS Transparency"

/**
 * A PNG file as an image of a document: taken apart into samples and written
 * back out as an image XObject under `/Filter /FlateDecode`.
 *
 * A JPEG is embedded — §7.4.8 makes its encoding a PDF filter, so the file *is*
 * the stream. PNG has no such filter: nothing in `/FlateDecode` understands
 * chunks, a per-scanline filter type byte or a palette, so a PNG on a page is a
 * PNG decoded ([PngImage]) and re-encoded. What makes the re-encoding cheap is
 * that the two formats agree about what a row of samples is. PNG §7.2 packs a
 * scanline "into a sequence of bytes with no wasted bits between pixels", starts
 * each one on a byte boundary and puts "the leftmost sample in the high-order
 * bits of a byte"; §8.9.3 says the same of image sample data — "each row of
 * sample data shall begin on a byte boundary. If the number of data bits per row
 * is not a multiple of 8, the end of the row is padded with extra bits". So for
 * every colour type without an alpha channel the unfiltered scanlines are handed
 * to [flateEncode] exactly as they came out, at the PNG's own bit depth.
 *
 * ## What each colour type becomes
 *
 * | PNG colour type | `/ColorSpace` | `/BitsPerComponent` |
 * |---|---|---|
 * | 0, greyscale | `/DeviceGray` (§8.6.4.2) | the IHDR bit depth: 1, 2, 4, 8 or 16 |
 * | 2, truecolour | `/DeviceRGB` (§8.6.4.3) | 8 or 16 |
 * | 3, indexed | `[/Indexed /DeviceRGB hival lookup]` (§8.6.6.3) | 1, 2, 4 or 8 |
 * | 4, greyscale with alpha | `/DeviceGray`, alpha split into `/SMask` | 8 or 16 |
 * | 6, truecolour with alpha | `/DeviceRGB`, alpha split into `/SMask` | 8 or 16 |
 *
 * The palette of an indexed PNG is written as a colour table rather than
 * resolved into RGB samples: a PLTE chunk is "a three-byte series R G B"
 * repeated (§11.2.2) and §8.6.6.3's lookup is "R0 G0 B0 R1 G1 B1" for a
 * DeviceRGB base — the same bytes in the same order, so the indices stay indices
 * and a 4-bit logo does not become a 24-bit one on the way onto the page.
 *
 * ## Transparency
 *
 * §6.2 of the PNG specification lists the ways an image carries alpha, and each
 * of them becomes the `/SMask` of Table 87 — "a subsidiary image XObject
 * defining a soft-mask image … used as a source of mask shape or mask opacity
 * values", which §11.6.5.2 restricts to DeviceGray by Table 143. A soft mask is
 * therefore one greyscale sample per pixel, and that is what an alpha channel
 * already is:
 *
 * - colour types 4 and 6 carry alpha "as the fourth sample" (or second): the
 *   plane is lifted out of the interleaved samples at the image's own bit depth,
 *   and the colour samples are what is left;
 * - a tRNS chunk on colour type 0 or 2 names one sample value that "shall be
 *   treated as fully transparent", every other pixel being opaque, and on colour
 *   type 3 gives "a series of one-byte alpha values, corresponding to entries in
 *   the PLTE chunk". Both become an 8-bit mask — the byte an indexed alpha
 *   already is, and 0 or 255 for the other two.
 *
 * A mask that is opaque everywhere is not written. An RGBA screenshot with
 * nothing transparent in it describes no transparency, and Table 87 has a
 * spelling for exactly that: "If SMask is absent and SMaskInData has value 0,
 * the image shall have no associated soft mask."
 *
 * No `/Decode` array is written for either image. The default of Table 88 is the
 * identity for the device spaces and `[0 2^n − 1]` for an Indexed one, which is
 * what samples straight out of a PNG mean.
 *
 * @param id what this image is called within one document, as [ImageRef.id].
 * @param bytes the PNG datastream, from its signature on.
 * @throws PngFormatException if [bytes] are not a PNG datastream this library
 *   decodes, carrying the offset of the fault and the clause it breaks.
 */
public fun pngImage(id: String, bytes: ByteArray): ImageRef {
    val png = PngImage.decode(bytes)
    val alpha = png.alphaPlane()
    return ImageRef(
        id = id,
        width = png.width,
        height = png.height,
        colorSpace = png.imageColorSpace(),
        data = flateEncode(png.colourPlane()),
        bitsPerComponent = png.bitDepth,
        filter = ImageFilter.FLATE_DECODE,
        softMask = alpha?.let { plane ->
            ImageRef(
                id = "$id soft mask",
                width = png.width,
                height = png.height,
                colorSpace = ImageColorSpace.DEVICE_GRAY,
                data = flateEncode(plane.samples),
                bitsPerComponent = plane.bitsPerComponent,
                filter = ImageFilter.FLATE_DECODE,
            )
        },
    )
}

/**
 * The `/ColorSpace` of Table 87 for this image: what the colour samples left
 * after the alpha is taken out are to be interpreted in.
 *
 * The alpha-bearing colour types name the space of the samples that remain, "the
 * greyscale sample" of type 4 and "the R,G,B triple" of type 6 (Table 12) — the
 * alpha sample is not a colour component and is not counted by
 * [ImageColorSpace.components].
 */
private fun PngImage.imageColorSpace(): ImageColorSpace = when (colourType) {
    PngColourType.GREYSCALE, PngColourType.GREYSCALE_ALPHA -> ImageColorSpace.DEVICE_GRAY
    PngColourType.TRUECOLOUR, PngColourType.TRUECOLOUR_ALPHA -> ImageColorSpace.DEVICE_RGB
    PngColourType.INDEXED -> ImageColorSpace.Indexed(
        base = ImageColorSpace.DEVICE_RGB,
        lookup = requireNotNull(palette) { "an indexed PNG without a PLTE chunk does not decode" },
    )
}

/**
 * The samples the image XObject is written from: the scanlines as PNG packed
 * them, with the alpha channel dropped where there is one.
 *
 * Where there is none this is the decoded array itself and not a copy of it,
 * because the two clauses describe one layout — see the note on [pngImage].
 */
private fun PngImage.colourPlane(): ByteArray =
    if (!colourType.hasAlpha) samples else interleavedPlane(from = 0, channels = colourType.channels - 1)

/**
 * The image's alpha as a greyscale plane, or null where the image is opaque —
 * either because nothing in it states transparency (§6.2: "the image is fully
 * opaque"), or because everything it states is opaque.
 */
private fun PngImage.alphaPlane(): AlphaPlane? {
    val plane = when {
        colourType.hasAlpha ->
            AlphaPlane(interleavedPlane(from = colourType.channels - 1, channels = 1), bitDepth)
        transparency == null -> null
        colourType == PngColourType.INDEXED -> AlphaPlane(paletteAlpha(transparency), BITS_PER_BYTE)
        else -> AlphaPlane(colourKeyAlpha(transparency), BITS_PER_BYTE)
    }
    // "If SMask is absent … the image shall have no associated soft mask"
    // (Table 87). Every sample at its maximum is an alpha of 1.0 everywhere, and
    // the maximum is all bits set whatever the depth, so the whole plane is a
    // mask worth writing exactly when some byte of it is not 0xFF.
    return plane?.takeIf { candidate -> candidate.samples.any { byte -> byte != OPAQUE } }
}

/**
 * [channels] consecutive samples of every pixel, starting at channel [from],
 * lifted out into a plane of their own.
 *
 * Only the alpha-bearing colour types are ever split, and Table 12 admits them
 * at bit depths 8 and 16 only, so a sample is a whole number of bytes and the
 * split is a copy rather than a shift. That is why this is not written for the
 * sub-byte depths: they are the depths no colour type with an alpha channel has.
 */
private fun PngImage.interleavedPlane(from: Int, channels: Int): ByteArray {
    val sampleBytes = bitDepth / BITS_PER_BYTE
    val pixelBytes = colourType.channels * sampleBytes
    val wanted = channels * sampleBytes
    val plane = ByteArray(width * wanted * height)
    var to = 0
    for (y in 0 until height) {
        var at = y * bytesPerRow + from * sampleBytes
        for (x in 0 until width) {
            samples.copyInto(plane, to, at, at + wanted)
            to += wanted
            at += pixelBytes
        }
    }
    return plane
}

/**
 * The alpha of an indexed image, one byte per pixel, from the tRNS chunk:
 * "a series of one-byte alpha values, corresponding to entries in the PLTE
 * chunk", which "may contain fewer values than there are palette entries. In
 * this case, the alpha value for all remaining palette entries is assumed to be
 * 255."
 */
private fun PngImage.paletteAlpha(alphaOfEntry: ByteArray): ByteArray {
    val plane = ByteArray(width * height)
    var to = 0
    for (y in 0 until height) {
        for (x in 0 until width) {
            val index = sample(x, y, 0)
            plane[to++] = if (index < alphaOfEntry.size) alphaOfEntry[index] else OPAQUE
        }
    }
    return plane
}

/**
 * The alpha of a greyscale or truecolour image with a tRNS chunk, one byte per
 * pixel: "pixels of the specified grey level are to be treated as transparent",
 * and for a truecolour image the same of "pixels of the specified colour value".
 *
 * The chunk states the value with "two bytes per sample … regardless of the
 * image bit depth" (§11.3.1.1), so the comparison is against the sample value
 * itself and not against anything scaled: at bit depth 4 a grey of 5 is the
 * two bytes `00 05`.
 */
private fun PngImage.colourKeyAlpha(transparentValue: ByteArray): ByteArray {
    val channels = colourType.channels
    val transparent = IntArray(channels) { channel ->
        (transparentValue[channel * 2].toInt() and BYTE_MASK shl BITS_PER_BYTE) or
            (transparentValue[channel * 2 + 1].toInt() and BYTE_MASK)
    }
    val plane = ByteArray(width * height)
    var to = 0
    for (y in 0 until height) {
        for (x in 0 until width) {
            var matches = true
            for (channel in 0 until channels) {
                if (sample(x, y, channel) != transparent[channel]) {
                    matches = false
                    break
                }
            }
            plane[to++] = if (matches) TRANSPARENT else OPAQUE
        }
    }
    return plane
}

/**
 * One greyscale plane of opacity values, ready to become the `/SMask` image.
 *
 * @property samples the plane, in the layout of §8.9.3 — one sample per pixel,
 *   rows in order, each beginning on a byte boundary, which it does by holding
 *   whole bytes.
 * @property bitsPerComponent what those samples are: the image's own bit depth
 *   where the alpha came out of an alpha channel, and 8 where a tRNS chunk built
 *   it.
 */
private class AlphaPlane(val samples: ByteArray, val bitsPerComponent: Int)

/** Alpha at its maximum: "a value of 255 indicates a fully opaque pixel" (§6.2). */
private const val OPAQUE: Byte = 0xFF.toByte()

/** "A value of 0 in the alpha channel indicates a fully transparent pixel" (§6.2). */
private const val TRANSPARENT: Byte = 0

/** The byte §8.9.3's padding rule and PNG's scanline packing are both stated in. */
private const val BITS_PER_BYTE: Int = 8

private const val BYTE_MASK: Int = 0xFF
