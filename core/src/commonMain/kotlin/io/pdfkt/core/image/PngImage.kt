/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.image

import io.pdfkt.core.filter.FlateFormatException
import io.pdfkt.core.filter.flateDecode

// Portable Network Graphics (PNG) Specification (Third Edition),
// W3C Recommendation 24 June 2025
//   §5.1 "PNG datastream", §5.6 "Chunk ordering" (Table 7), §7.1 "Integers and
//   byte order", §7.2 "Scanlines", §8.1 "Interlace methods",
//   §10.1 "Compression method 0", §10.2 "Compression of the sequence of
//   filtered scanlines", §11.2.1 "IHDR Image header" (Table 12),
//   §11.2.2 "PLTE Palette", §11.2.3 "IDAT Image data",
//   §11.2.4 "IEND Image trailer", §11.3.1.1 "tRNS Transparency",
//   §13.1 "Error handling"
// RFC 1950 "ZLIB Compressed Data Format Specification version 3.3", the
//   wrapper §10.1 stores the compressed image data in
// ISO 32000-2:2020 §8.9.3 "Sample representation", where these samples go next

/**
 * A PNG image decoded into its samples: what the IHDR chunk declares, and the
 * scanlines the IDAT chunks decompress and unfilter to.
 *
 * ## Why a PDF library decodes a PNG at all
 *
 * Unlike a JPEG, which PDF embeds untouched under `/DCTDecode` (§7.4.8, and
 * [JpegHeader] for the reading of its header), a PNG datastream is not a filter
 * PDF defines. Nothing in ISO 32000-2 understands chunks, a per-scanline filter
 * type byte or a PLTE chunk, so a PNG on a page is a PNG taken apart: the
 * samples come out here and go back in as an image XObject.
 *
 * What comes out is convenient for that, and not by accident. §7.2 packs a
 * scanline "into a sequence of bytes with no wasted bits between pixels",
 * begins each on a byte boundary, and puts the leftmost sample in the
 * high-order bits — the same layout ISO 32000-2:2020 §8.9.3 describes for image
 * sample data, where "each row of sample data shall begin on a byte boundary"
 * and the row is padded to it. So the unfiltered scanlines of a PNG are already
 * the samples of an image XObject, for every colour type without an alpha
 * channel to split off.
 *
 * ## What is decoded, and what is refused
 *
 * All five colour types of Table 9 at every bit depth Table 12 admits for them,
 * with interlace method 0 — "the null method, pixels are extracted sequentially
 * from left to right, and scanlines sequentially from top to bottom" (§8.1).
 * The Adam7 method 1 is refused by name rather than misread: see
 * [PngFormatException].
 *
 * Ancillary chunks are skipped, with one exception. tRNS is kept, because it is
 * where transparency lives for the three colour types that have no alpha
 * channel (§6.2), and dropping it would turn transparent pixels into opaque
 * ones with no error anywhere — the silent loss this library's design rules
 * out. It is kept as its bytes, checked for the length its colour type gives
 * it; what a page does with them is the business of whoever builds the
 * `/SMask`. Everything else an ancillary chunk carries — gamma, chromaticity,
 * text, timestamps — is ignored, which §13.1 permits explicitly: "encountering
 * an unknown ancillary chunk is never an error".
 *
 * @property width the IHDR width, "the image dimensions in pixels".
 * @property height the IHDR height.
 * @property bitDepth "the number of bits per sample or per palette index (not
 *   per pixel)" (§11.2.1).
 * @property colourType what a pixel is made of, and how many samples it takes.
 */
public class PngImage internal constructor(
    public val width: Int,
    public val height: Int,
    public val bitDepth: Int,
    public val colourType: PngColourType,
    internal val samples: ByteArray,
    internal val palette: ByteArray?,
    internal val transparency: ByteArray?,
) {
    /**
     * How many bytes one scanline of samples takes: `ceil(width × channels ×
     * bitDepth / 8)`, since "scanlines always begin on byte boundaries" and
     * "when there are multiple pixels per byte, some low-order bits of the last
     * byte of a scanline may go unused" (§7.2).
     */
    public val bytesPerRow: Int = scanlineBytes(width, colourType.channels, bitDepth).toInt()

    /** How many entries the PLTE chunk carried, or 0 where there was none (§11.2.2). */
    public val paletteEntryCount: Int = (palette?.size ?: 0) / PALETTE_ENTRY_SIZE

    /**
     * The value of one sample: the [channel]-th sample of the pixel at [x], [y],
     * in the range 0 to `2^bitDepth - 1`.
     *
     * The channels are in the order Table 12 lists them — greyscale, or red,
     * green and blue, followed by alpha where the colour type has it — and for
     * an indexed image the single channel is the palette index, not a colour.
     *
     * Sub-byte samples are read as §7.2 packs them: "these samples are packed
     * into bytes with the leftmost sample in the high-order bits of a byte
     * followed by the other samples for the scanline". Sixteen-bit samples "are
     * in network byte order (MSB first, LSB second)".
     */
    public fun sample(x: Int, y: Int, channel: Int): Int {
        require(x in 0 until width && y in 0 until height) {
            "the pixel ($x, $y) is outside an image of $width x $height samples"
        }
        require(channel in 0 until colourType.channels) {
            "channel $channel of a $colourType pixel: it carries ${colourType.channels} sample" +
                (if (colourType.channels == 1) "" else "s")
        }
        val rowAt = y * bytesPerRow
        return when {
            bitDepth == BITS_PER_BYTE * 2 -> {
                val at = rowAt + (x * colourType.channels + channel) * 2
                (samples[at].toInt() and BYTE_MASK shl BITS_PER_BYTE) or (samples[at + 1].toInt() and BYTE_MASK)
            }
            bitDepth == BITS_PER_BYTE -> samples[rowAt + x * colourType.channels + channel].toInt() and BYTE_MASK
            else -> {
                val bitAt = x * bitDepth
                val byte = samples[rowAt + bitAt / BITS_PER_BYTE].toInt() and BYTE_MASK
                val shift = BITS_PER_BYTE - bitDepth - bitAt % BITS_PER_BYTE
                (byte ushr shift) and ((1 shl bitDepth) - 1)
            }
        }
    }

    /** The shape; the samples are not something to print. */
    override fun toString(): String = "PngImage(${width}x$height, $colourType at $bitDepth bits" +
        (if (paletteEntryCount > 0) ", $paletteEntryCount palette entries" else "") +
        (if (transparency != null) ", tRNS" else "") + ")"

    public companion object {
        /**
         * Decodes the PNG datastream [bytes] into its samples.
         *
         * The walk is the one §5.1 describes — the signature, then chunks —
         * with the ordering rules of Table 7 checked as it goes: IHDR first,
         * PLTE before the first IDAT, the IDAT chunks consecutive, IEND last.
         * Their concatenated data are one zlib datastream (§10.2), decompressed
         * by this library's own inflate, and what that yields is the filtered
         * scanlines that [pngReconstruct] turns back into samples.
         *
         * @throws PngFormatException if [bytes] are not a PNG datastream this
         *   library decodes, carrying the offset of the fault and the clause it
         *   breaks.
         */
        public fun decode(bytes: ByteArray): PngImage {
            val walk = PngChunkWalk(bytes)
            walk.readSignature()

            var header: PngIhdr? = null
            var palette: PngChunk? = null
            var transparency: PngChunk? = null
            val imageData = mutableListOf<PngChunk>()
            var imageDataEnded = false
            var trailer: PngChunk? = null
            var after = "after the signature"

            while (trailer == null) {
                if (!walk.hasNext()) {
                    throw PngFormatException(
                        bytes.size,
                        "the datastream ends $after, with no IEND chunk; a PNG datastream \"shall end with an " +
                            "IEND chunk\"",
                        TRAILER_CLAUSE,
                    )
                }
                val chunk = walk.next(after)
                if (header == null && chunk.type != IHDR) {
                    throw PngFormatException(
                        chunk.at,
                        "the first chunk of the datastream is ${chunk.name}; a PNG datastream begins with the " +
                            "signature \"immediately followed by an IHDR chunk\", which \"shall be the first chunk\"",
                        HEADER_CLAUSE,
                    )
                }
                // "Multiple IDAT chunks shall be consecutive" (Table 7): once
                // something else has been read, no further IDAT may appear.
                if (chunk.type != IDAT && imageData.isNotEmpty()) imageDataEnded = true

                when (chunk.type) {
                    IHDR -> {
                        if (header != null) {
                            throw PngFormatException(
                                chunk.at,
                                "the datastream carries a second IHDR chunk, after the one at byte " +
                                    "${PNG_SIGNATURE.size}; \"only one IHDR chunk and one IEND chunk are allowed " +
                                    "in a PNG datastream\"",
                                HEADER_CLAUSE,
                            )
                        }
                        header = PngIhdr.parse(chunk, bytes)
                    }

                    PLTE -> {
                        if (palette != null) {
                            throw PngFormatException(
                                chunk.at,
                                "the datastream carries a second PLTE chunk, after the one at byte ${palette.at}; " +
                                    "\"there shall not be more than one PLTE chunk\"",
                                PALETTE_CLAUSE,
                            )
                        }
                        if (imageData.isNotEmpty()) {
                            throw PngFormatException(
                                chunk.at,
                                "the PLTE chunk follows the image data; Table 7 places it \"before first IDAT\", " +
                                    "and a palette read after the pixels that index it is a palette no decoder " +
                                    "streaming the image could have used",
                                ORDERING_CLAUSE,
                            )
                        }
                        palette = chunk
                    }

                    IDAT -> {
                        if (imageDataEnded) {
                            throw PngFormatException(
                                chunk.at,
                                "an IDAT chunk follows a chunk of another type; \"there may be multiple IDAT " +
                                    "chunks; if so, they shall appear consecutively with no other intervening " +
                                    "chunks\"",
                                IMAGE_DATA_CLAUSE,
                            )
                        }
                        imageData += chunk
                    }

                    TRNS -> {
                        if (transparency != null) {
                            throw PngFormatException(
                                chunk.at,
                                "the datastream carries a second tRNS chunk, after the one at byte " +
                                    "${transparency.at}; the transparency of an image is stated once",
                                TRANSPARENCY_CLAUSE,
                            )
                        }
                        transparency = chunk
                    }

                    IEND -> {
                        if (chunk.size != 0) {
                            throw PngFormatException(
                                chunk.at,
                                "the IEND chunk declares ${chunk.size} bytes of data; \"the chunk's data field is " +
                                    "empty\"",
                                TRAILER_CLAUSE,
                            )
                        }
                        trailer = chunk
                    }

                    // "Encountering an unknown critical chunk is a fatal
                    // condition for any decoder trying to extract the image
                    // from the datastream. A decoder that ignored a critical
                    // chunk could not know whether the image it extracted was
                    // the one intended by the encoder." (§13.1)
                    else -> if (chunk.isCritical) {
                        throw PngFormatException(
                            chunk.at,
                            "the datastream carries the critical chunk ${chunk.name}, which this decoder does not " +
                                "know; a decoder that ignored it \"could not know whether the image it extracted " +
                                "was the one intended by the encoder\"",
                            ERROR_CLAUSE,
                        )
                    }
                }
                after = "after the ${chunk.name} chunk at byte ${chunk.at}"
            }

            if (walk.hasNext()) {
                throw PngFormatException(
                    trailer.end,
                    "${bytes.size - trailer.end} byte" + (if (bytes.size - trailer.end == 1) "" else "s") +
                        " follow the IEND chunk; Table 7 says of IEND that it \"shall be last\"",
                    ORDERING_CLAUSE,
                )
            }

            val ihdr = requireNotNull(header) { "the chunk loop ends only at IEND, which cannot precede IHDR" }
            val paletteBytes = readPalette(palette, ihdr, bytes)
            val transparencyBytes = readTransparency(transparency, ihdr, paletteBytes, bytes)

            if (imageData.isEmpty()) {
                throw PngFormatException(
                    trailer.at,
                    "the datastream carries no IDAT chunk; a PNG datastream is a signature, \"an IHDR chunk, then " +
                        "one or more IDAT chunks\", and an IEND chunk",
                    IMAGE_DATA_CLAUSE,
                )
            }

            return PngImage(
                width = ihdr.width,
                height = ihdr.height,
                bitDepth = ihdr.bitDepth,
                colourType = ihdr.colourType,
                samples = readSamples(imageData, ihdr, bytes),
                palette = paletteBytes,
                transparency = transparencyBytes,
            )
        }

        /**
         * The PLTE chunk's entries, or null where the image has no palette.
         *
         * "The PLTE chunk contains from 1 to 256 palette entries, each a
         * three-byte series … The number of entries is determined from the
         * chunk length. A chunk length not divisible by 3 is an error." Its
         * presence is decided by the colour type: required for colour type 3,
         * optional for 2 and 6 — where it is only "a suggested set of colors …
         * to which the truecolor image can be quantized" and nothing this
         * library needs — and forbidden for 0 and 4.
         */
        private fun readPalette(chunk: PngChunk?, ihdr: PngIhdr, bytes: ByteArray): ByteArray? {
            if (chunk == null) {
                if (ihdr.colourType.isIndexed) {
                    throw PngFormatException(
                        ihdr.colourTypeAt,
                        "the image is indexed-colour and the datastream carries no PLTE chunk; for colour type 3 " +
                            "\"a PLTE chunk shall appear\", since every pixel is an index into it",
                        PALETTE_CLAUSE,
                    )
                }
                return null
            }
            if (ihdr.colourType == PngColourType.GREYSCALE || ihdr.colourType == PngColourType.GREYSCALE_ALPHA) {
                throw PngFormatException(
                    chunk.at,
                    "the datastream carries a PLTE chunk and its colour type is ${ihdr.colourType.code}; a palette " +
                        "\"shall not appear for color types 0 and 4\", whose pixels are greyscale samples and " +
                        "index nothing",
                    PALETTE_CLAUSE,
                )
            }
            if (chunk.size % PALETTE_ENTRY_SIZE != 0) {
                throw PngFormatException(
                    chunk.at,
                    "the PLTE chunk carries ${chunk.size} bytes; an entry is three bytes — red, green, blue — and " +
                        "\"a chunk length not divisible by 3 is an error\"",
                    PALETTE_CLAUSE,
                )
            }
            val entries = chunk.size / PALETTE_ENTRY_SIZE
            if (entries !in 1..MAX_PALETTE_ENTRIES) {
                throw PngFormatException(
                    chunk.at,
                    "the PLTE chunk carries $entries entries; it \"contains from 1 to $MAX_PALETTE_ENTRIES " +
                        "palette entries\"",
                    PALETTE_CLAUSE,
                )
            }
            if (ihdr.colourType == PngColourType.INDEXED && entries > 1 shl ihdr.bitDepth) {
                throw PngFormatException(
                    chunk.at,
                    "the PLTE chunk carries $entries entries and the image is ${ihdr.bitDepth} bits deep; \"the " +
                        "number of palette entries shall not exceed the range that can be represented in the " +
                        "image bit depth\", which is ${1 shl ihdr.bitDepth}",
                    PALETTE_CLAUSE,
                )
            }
            return chunk.data(bytes)
        }

        /**
         * The tRNS chunk's bytes, or null where the datastream carries none.
         *
         * The chunk's length is fixed by the colour type: two bytes for a grey
         * sample value (colour type 0), six for an RGB one (colour type 2), and
         * for colour type 3 "a series of one-byte alpha values, corresponding
         * to entries in the PLTE chunk … shall not contain more alpha values
         * than there are palette entries, but … may contain fewer". For colour
         * types 4 and 6 it "shall not appear … since a full alpha channel is
         * already present in those cases", so a datastream carrying both is
         * stating transparency twice and is refused rather than guessed at.
         */
        private fun readTransparency(
            chunk: PngChunk?,
            ihdr: PngIhdr,
            palette: ByteArray?,
            bytes: ByteArray,
        ): ByteArray? {
            if (chunk == null) return null
            if (ihdr.colourType.hasAlpha) {
                throw PngFormatException(
                    chunk.at,
                    "the datastream carries a tRNS chunk and its colour type is ${ihdr.colourType.code}; a tRNS " +
                        "chunk \"shall not appear for color types 4 and 6, since a full alpha channel is already " +
                        "present in those cases\"",
                    TRANSPARENCY_CLAUSE,
                )
            }
            // Null for an indexed image, whose tRNS chunk is as long as it
            // chooses to be up to one alpha value per palette entry.
            val fixedSize: Int? = when (ihdr.colourType) {
                PngColourType.GREYSCALE -> GREY_TRANSPARENCY_SIZE
                PngColourType.TRUECOLOUR -> RGB_TRANSPARENCY_SIZE
                else -> null
            }
            if (fixedSize != null && chunk.size != fixedSize) {
                throw PngFormatException(
                    chunk.at,
                    "the tRNS chunk carries ${chunk.size} bytes and colour type ${ihdr.colourType.code} gives it " +
                        "$fixedSize: \"for color types 0 or 2, two bytes per sample are used regardless of the " +
                        "image bit depth\"",
                    TRANSPARENCY_CLAUSE,
                )
            }
            val entries = (palette?.size ?: 0) / PALETTE_ENTRY_SIZE
            if (fixedSize == null && chunk.size > entries) {
                throw PngFormatException(
                    chunk.at,
                    "the tRNS chunk carries ${chunk.size} alpha values and the palette has $entries entries; it " +
                        "\"shall not contain more alpha values than there are palette entries\"",
                    TRANSPARENCY_CLAUSE,
                )
            }
            return chunk.data(bytes)
        }

        /**
         * The samples: the IDAT chunks concatenated, decompressed and
         * unfiltered.
         *
         * "The concatenation of the contents of all the IDAT chunks makes up a
         * zlib datastream. This datastream decompresses to filtered image
         * data" (§10.2) — and the boundaries between the chunks say nothing:
         * "it is entirely possible for the terminating zlib check value to be
         * split across IDAT chunks", so the join happens before anything is
         * decompressed.
         *
         * The decompressed data are `height × (1 + bytesPerRow)` bytes: one
         * filter type byte per scanline (§9.1) and the scanline itself. A
         * datastream decompressing to fewer than that has lost rows, and one
         * decompressing to more contradicts its own IHDR; neither is turned
         * into an image, since a partial image looks exactly like an image.
         */
        private fun readSamples(imageData: List<PngChunk>, ihdr: PngIhdr, bytes: ByteArray): ByteArray {
            val bytesPerRow = scanlineBytes(ihdr.width, ihdr.colourType.channels, ihdr.bitDepth)
            val expected = ihdr.height.toLong() * (1 + bytesPerRow)
            if (expected > Int.MAX_VALUE) {
                throw PngFormatException(
                    ihdr.at,
                    "the image is ${ihdr.width} x ${ihdr.height} pixels of colour type ${ihdr.colourType.code} at " +
                        "${ihdr.bitDepth} bits, which needs $expected bytes of filtered image data — more than " +
                        "the ${Int.MAX_VALUE} a single array holds",
                    HEADER_CLAUSE,
                )
            }

            val compressed = ByteArray(imageData.sumOf { it.size })
            var at = 0
            for (chunk in imageData) {
                bytes.copyInto(compressed, at, chunk.dataAt, chunk.dataAt + chunk.size)
                at += chunk.size
            }

            val imageDataAt = imageData.first().dataAt
            val filtered = try {
                flateDecode(compressed)
            } catch (fault: FlateFormatException) {
                throw PngFormatException(
                    imageDataAt,
                    "the concatenated IDAT data are not a zlib datastream of deflate-compressed image data: " +
                        "${fault.problem}, at byte ${fault.offset} of the concatenation (${fault.clause}); PNG " +
                        "\"compression method 0 is deflate compression with a sliding window … of at most 32768 " +
                        "bytes\", stored in the zlib format",
                    COMPRESSION_CLAUSE,
                    fault,
                )
            }

            if (filtered.size.toLong() != expected) {
                throw PngFormatException(
                    imageDataAt,
                    "the image data decompress to ${filtered.size} bytes and an image of ${ihdr.width} x " +
                        "${ihdr.height} at ${ihdr.bitDepth} bits needs $expected — ${ihdr.height} scanlines of " +
                        "$bytesPerRow bytes, each preceded by its filter type byte",
                    IMAGE_DATA_CLAUSE,
                )
            }

            // "a, the byte corresponding to x in the pixel immediately before
            // the pixel containing x (or the byte immediately before x, when
            // the bit depth is less than 8)" (Table 10) — so the distance is a
            // pixel where a pixel is at least a byte, and one byte below that.
            val bitsPerPixel = ihdr.colourType.channels * ihdr.bitDepth
            val bytesPerPixel = maxOf(1, bitsPerPixel / BITS_PER_BYTE)

            return pngReconstruct(
                filtered = filtered,
                height = ihdr.height,
                bytesPerRow = bytesPerRow.toInt(),
                bytesPerPixel = bytesPerPixel,
                imageDataAt = imageDataAt,
            )
        }
    }
}

/**
 * The IHDR chunk's thirteen bytes, §11.2.1: "Width 4 bytes, Height 4 bytes, Bit
 * depth 1 byte, Color type 1 byte, Compression method 1 byte, Filter method 1
 * byte, Interlace method 1 byte."
 *
 * Every one of them is checked here, before a single byte of image data is
 * decompressed — which is the point of the chunk being first and of §13.1's
 * "detect errors as early as possible". An image whose bit depth its colour
 * type does not admit has no scanline length, so there is nothing later that
 * could be checked instead.
 *
 * @property at the offset of the chunk, for a refusal raised later.
 * @property colourTypeAt the offset of the colour type byte, likewise.
 */
private class PngIhdr(
    val at: Int,
    val colourTypeAt: Int,
    val width: Int,
    val height: Int,
    val bitDepth: Int,
    val colourType: PngColourType,
) {
    companion object {
        fun parse(chunk: PngChunk, bytes: ByteArray): PngIhdr {
            if (chunk.size != IHDR_SIZE) {
                throw PngFormatException(
                    chunk.at,
                    "the IHDR chunk carries ${chunk.size} bytes; it is $IHDR_SIZE — two four-byte dimensions and " +
                        "the five single bytes of bit depth, colour type, compression method, filter method and " +
                        "interlace method",
                    HEADER_CLAUSE,
                )
            }
            val at = chunk.dataAt
            val width = bytes.int32At(at)
            val height = bytes.int32At(at + 4)
            // "Width and height give the image dimensions in pixels. They are
            // PNG four-byte unsigned integers. Zero is an invalid value", and
            // §7.1 limits such an integer to 0 … 2^31-1, which is where a
            // negative value read as signed comes from.
            if (width <= 0 || height <= 0) {
                throw PngFormatException(
                    if (width <= 0) at else at + 4,
                    "the image is ${dimension(width)} x ${dimension(height)} pixels; the dimensions are PNG " +
                        "four-byte unsigned integers limited to \"the range 0 to 2^31-1\", of which \"zero is an " +
                        "invalid value\"",
                    HEADER_CLAUSE,
                )
            }

            val bitDepth = bytes.uint8At(at + 8)
            val colourTypeCode = bytes.uint8At(at + 9)
            val colourType = PngColourType.forCode(colourTypeCode)
                ?: throw PngFormatException(
                    at + 9,
                    "the colour type is $colourTypeCode; a colour type \"is the sum of the following values: 1 " +
                        "(palette used), 2 (truecolor used) and 4 (alpha used)\", and Table 9 defines the five " +
                        "combinations " + PngColourType.entries.joinToString(", ") { "${it.code}" },
                    HEADER_CLAUSE,
                )
            if (bitDepth !in colourType.allowedBitDepths) {
                throw PngFormatException(
                    at + 8,
                    "the bit depth is $bitDepth and the colour type is $colourTypeCode; Table 12 admits the bit " +
                        "depth" + (if (colourType.allowedBitDepths.size == 1) " " else "s ") +
                        colourType.allowedBitDepths.joinToString(", ") + " for that colour type",
                    HEADER_CLAUSE,
                )
            }

            val compressionMethod = bytes.uint8At(at + 10)
            if (compressionMethod != 0) {
                throw PngFormatException(
                    at + 10,
                    "the compression method is $compressionMethod; \"only compression method 0 (deflate " +
                        "compression with a sliding window of at most 32768 bytes) is defined in this " +
                        "specification\"",
                    HEADER_CLAUSE,
                )
            }
            val filterMethod = bytes.uint8At(at + 11)
            if (filterMethod != 0) {
                throw PngFormatException(
                    at + 11,
                    "the filter method is $filterMethod; \"only filter method 0 (adaptive filtering with five " +
                        "basic filter types) is defined in this specification\"",
                    HEADER_CLAUSE,
                )
            }
            val interlaceMethod = bytes.uint8At(at + 12)
            if (interlaceMethod == ADAM7) {
                throw PngFormatException(
                    at + 12,
                    "the interlace method is 1, the Adam7 method, which this library does not decode: the image " +
                        "arrives as \"a sequence of seven reduced images\" that have to be woven back together, " +
                        "and reading them as one image would produce a scrambled picture rather than an error",
                    INTERLACE_CLAUSE,
                )
            }
            if (interlaceMethod != 0) {
                throw PngFormatException(
                    at + 12,
                    "the interlace method is $interlaceMethod; \"two interlace methods are defined in this " +
                        "International Standard, methods 0 and 1\", and \"other values of interlace method are " +
                        "reserved for future standardization\"",
                    INTERLACE_CLAUSE,
                )
            }

            return PngIhdr(
                at = chunk.at,
                colourTypeAt = at + 9,
                width = width,
                height = height,
                bitDepth = bitDepth,
                colourType = colourType,
            )
        }

        /** A dimension as the datastream states it, unsigned, so a message about it reads. */
        private fun dimension(value: Int): String = if (value < 0) "${value.toLong() and 0xFFFFFFFFL}" else "$value"

        /** The thirteen bytes §11.2.1 lists. */
        private const val IHDR_SIZE: Int = 13

        /** "Interlace method 1, known as Adam7, defines seven distinct passes over the image" (§8.1). */
        private const val ADAM7: Int = 1
    }
}

/**
 * How many bytes a scanline of [width] pixels of [channels] samples at
 * [bitDepth] bits takes, §7.2: pixels are packed "with no wasted bits between
 * pixels", scanlines "always begin on byte boundaries", and the last byte of a
 * scanline may have unused low-order bits.
 *
 * In [Long] because the product of a dimension that may reach 2^31-1, four
 * channels and sixteen bits does not fit an [Int] — the caller refuses such an
 * image rather than wrapping into a plausible small number.
 */
private fun scanlineBytes(width: Int, channels: Int, bitDepth: Int): Long =
    (width.toLong() * channels * bitDepth + BITS_PER_BYTE - 1) / BITS_PER_BYTE

/** The chunk types this decoder knows, as the hexadecimal values their clauses print. */
private const val IHDR: Int = 0x49484452
private const val PLTE: Int = 0x504C5445
private const val IDAT: Int = 0x49444154
private const val IEND: Int = 0x49454E44
private const val TRNS: Int = 0x74524E53

/** "each a three-byte series of the form: Red 1 byte, Green 1 byte, Blue 1 byte" (§11.2.2). */
private const val PALETTE_ENTRY_SIZE: Int = 3

/** "The PLTE chunk contains from 1 to 256 palette entries" (§11.2.2). */
private const val MAX_PALETTE_ENTRIES: Int = 256

/** Colour type 0: "Grey sample value, 2 bytes" (§11.3.1.1). */
private const val GREY_TRANSPARENCY_SIZE: Int = 2

/** Colour type 2: red, green and blue sample values, two bytes each. */
private const val RGB_TRANSPARENCY_SIZE: Int = 6

private const val BITS_PER_BYTE: Int = 8
private const val BYTE_MASK: Int = 0xFF

private const val HEADER_CLAUSE: String = "PNG Specification (Third Edition) §11.2.1 \"IHDR Image header\""
private const val PALETTE_CLAUSE: String = "PNG Specification (Third Edition) §11.2.2 \"PLTE Palette\""
private const val IMAGE_DATA_CLAUSE: String = "PNG Specification (Third Edition) §11.2.3 \"IDAT Image data\""
private const val TRAILER_CLAUSE: String = "PNG Specification (Third Edition) §11.2.4 \"IEND Image trailer\""
private const val TRANSPARENCY_CLAUSE: String = "PNG Specification (Third Edition) §11.3.1.1 \"tRNS Transparency\""
private const val ORDERING_CLAUSE: String = "PNG Specification (Third Edition) §5.6 \"Chunk ordering\""
private const val COMPRESSION_CLAUSE: String = "PNG Specification (Third Edition) §10.1 \"Compression method 0\""
private const val INTERLACE_CLAUSE: String = "PNG Specification (Third Edition) §8.1 \"Interlace methods\""
private const val ERROR_CLAUSE: String = "PNG Specification (Third Edition) §13.1 \"Error handling\""

/** The unsigned byte at [offset]. Bounds are the caller's, having checked the chunk's length. */
private fun ByteArray.uint8At(offset: Int): Int = this[offset].toInt() and BYTE_MASK

/** The four-byte big-endian value at [offset] (§7.1, "network byte order"). */
private fun ByteArray.int32At(offset: Int): Int = (uint8At(offset) shl 24) or (uint8At(offset + 1) shl 16) or
    (uint8At(offset + 2) shl 8) or uint8At(offset + 3)
