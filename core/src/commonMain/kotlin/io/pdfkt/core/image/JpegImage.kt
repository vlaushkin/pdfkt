/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.image

import io.pdfkt.core.obj.StreamBody

// ISO 32000-2:2020 §7.4.8 "DCTDecode filter" (Table 13),
// §8.9.5.1 "Image dictionaries — General" (Table 87)
// ITU-T T.81 (09/92) | ISO/IEC 10918-1 §B.2.2 "Frame header syntax"

/**
 * A JPEG file as an image of a document: its own bytes, embedded as they arrived
 * under `/Filter /DCTDecode`, described by what its frame header says it is.
 *
 * This is the whole of what "supporting JPEG" means here, and the reason the
 * library needs no JPEG decoder: §7.4.8 makes DCTDecode a filter, so the encoded
 * data *are* the stream and a PDF processor decodes them ("The DCTDecode filter
 * decodes grayscale or colour image data that has been encoded in the JPEG
 * baseline format"). Nothing recompresses, re-samples or re-encodes anything:
 * JPEG needs no decoding at all, because PDF embeds it as-is under DCTDecode.
 *
 * Everything the image dictionary of Table 87 requires beyond the samples is
 * read out of the frame header by [JpegHeader], because §7.4.8 says that is
 * where it is: "The values of these parameters, which include the dimensions of
 * the image and the number of components per sample, are entirely under the
 * control of the encoder and shall be stored in the encoded data." So the
 * dimensions written into `/Width` and `/Height` are the frame's own, and a
 * caller cannot state them differently from what the reader will decode.
 *
 * `/DecodeParms` is deliberately never written. The one parameter Table 13
 * admits is `/ColorTransform`, and it is needed in no case this function can
 * produce: when the encoder wrote an Adobe APP14 segment "the value of this
 * dictionary entry shall be ignored", and when it did not, the value
 * [JpegHeader.colourTransform] reports is exactly the default the same table
 * states — "1 if the image has three components and 0 otherwise".
 *
 * @param id what this image is called within one document, as [ImageRef.id].
 * @param bytes the JPEG file, from its SOI marker on. They become the stream
 *   unchanged, which is what makes the embedding lossless: what comes out of the
 *   file is the file that went in.
 * @throws IllegalArgumentException if [bytes] are not a JPEG this library will
 *   embed — a frame DCTDecode is not required to decode, a component count no
 *   device colour space has, or samples of a precision other than 8 bits.
 */
public fun jpegImage(id: String, bytes: ByteArray): ImageRef =
    jpegImage(id, JpegHeader.parse(bytes), StreamBody.Bytes(bytes))

/** The JPEG whose frame header is [header] and whose file is [data], as [jpegImage] describes it. */
internal fun jpegImage(id: String, header: JpegHeader, data: StreamBody): ImageRef {
    require(header.bitsPerComponent == DCT_DECODE_SAMPLE_BITS) {
        "the JPEG \"$id\" encodes ${header.bitsPerComponent}-bit samples, and an image whose stream " +
            "uses DCTDecode is written with BitsPerComponent 8: \"the value of BitsPerComponent shall " +
            "be consistent with the size of the data samples that the filter delivers … a " +
            "RunLengthDecode or DCTDecode filter shall always deliver 8-bit samples\" " +
            "(ISO 32000-2:2020 §8.9.5.1, Table 87). Re-encode it at 8 bits per sample"
    }
    return ImageRef(
        id = id,
        width = header.width,
        height = header.height,
        colorSpace = header.colourSpace.deviceColorSpace(),
        samples = data,
        bitsPerComponent = header.bitsPerComponent,
        filter = ImageFilter.DCT_DECODE,
        softMask = null,
    )
}

/**
 * The device colour space (§8.6.4) an image dictionary names for a frame of this
 * many components — the same space [JpegColourSpace] already chose by counting
 * them, said in the vocabulary [ImageRef] is built from.
 *
 * A `when` over the enum rather than a lookup by name, so that a colour space
 * added on either side is a compilation error here instead of a JPEG that
 * silently stops being embeddable.
 */
private fun JpegColourSpace.deviceColorSpace(): ImageColorSpace.Device = when (this) {
    JpegColourSpace.DEVICE_GRAY -> ImageColorSpace.DEVICE_GRAY
    JpegColourSpace.DEVICE_RGB -> ImageColorSpace.DEVICE_RGB
    JpegColourSpace.DEVICE_CMYK -> ImageColorSpace.DEVICE_CMYK
}

/**
 * "A RunLengthDecode or DCTDecode filter shall always deliver 8-bit samples"
 * (Table 87) — so a 12-bit extended-sequential frame, legal as JPEG, is not an
 * image this library writes.
 */
private const val DCT_DECODE_SAMPLE_BITS: Int = 8
