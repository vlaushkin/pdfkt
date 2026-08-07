/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.image

// ISO 32000-2:2020 §7.4.8 "DCTDecode filter" (Table 13, ColorTransform)
// ITU-T T.81 (09/92) | ISO/IEC 10918-1 §B.1.1.2 "Markers",
// §B.1.1.4 "Marker segments", §B.2.2 "Frame header syntax" (Table B.2)
// ITU-T T.871 (05/2011) | ISO/IEC 10918-5, the JFIF APP0 marker segment
// Adobe Technical Note #5116, the APP14 marker segment §7.4.8 refers to

/**
 * What a JPEG data stream says about itself: its dimensions, how many colour
 * components a sample has, how the samples were encoded, and — if the stream
 * carries a JFIF segment — how large one pixel is meant to be.
 *
 * ## Why a PDF library reads a JPEG at all
 *
 * It does not decode one. §7.4.8 defines the DCTDecode filter over data
 * "encoded in the JPEG baseline format", and the encoded data go into the file
 * exactly as they arrived — that is the whole point of the filter, and the
 * reason JPEG needs no decoding at all here.
 *
 * What the image XObject around those bytes still has to state is `/Width`,
 * `/Height`, `/ColorSpace` and `/BitsPerComponent`, and none of them may
 * contradict the stream: "The values of these parameters … are entirely under
 * the control of the encoder and shall be stored in the encoded data." So the
 * dictionary is written from the frame header, never from what a caller
 * believes about the file.
 *
 * ## How it is read
 *
 * The stream is a sequence of markers (§B.1.1.2). Each is X'FF' followed by a
 * code, optionally preceded by fill bytes, and all but a few introduce a
 * segment whose first parameter is a two-byte length counting itself
 * (§B.1.1.4). Reading a header is therefore a walk: take a marker, take or skip
 * its segment, stop at SOS — after which entropy-coded data begin and nothing
 * further is a header. Everything this class holds comes from a frame header
 * (SOF0, SOF1 or SOF2), from a JFIF APP0 segment or from an Adobe APP14
 * segment; every other segment is walked over by its length and ignored.
 *
 * ## Refusals
 *
 * A JPEG is input from outside this library, so every length and count in it is
 * a claim. Each one is checked, and a claim that does not hold raises
 * [IllegalArgumentException] **naming the marker it was made in** — "SOF3
 * (0xFFC3)", "APP0 (0xFFE0)" — because that is what a caller can find in a hex
 * dump of their own file. The alternative, embedding the stream anyway and
 * writing a dictionary guessed around it, produces a document that opens blank
 * in one reader and wrong in another; the no-silent-loss policy forbids exactly
 * that.
 *
 * @property width the frame header's X, "the maximum number of samples per line".
 * @property height the frame header's Y, the number of lines.
 * @property bitsPerComponent the frame header's P, the sample precision: 8, or
 *   12 for an extended sequential or progressive frame.
 * @property componentCount the frame header's Nf, the number of components in
 *   the frame — 1, 3 or 4, the counts §7.4.8 admits.
 * @property colourSpace the device colour space [componentCount] selects.
 * @property encoding which of the three frames DCTDecode decodes this is.
 * @property density what one pixel measures, or null if the stream carries no
 *   JFIF APP0 segment — most JPEGs written by cameras and by libraries do, but
 *   a JPEG is perfectly valid without one.
 * @property adobeColourTransform the transform code of the Adobe APP14 segment,
 *   or null if the stream carries none. See [colourTransform], which is the
 *   value a filter dictionary is written from.
 */
public class JpegHeader internal constructor(
    public val width: Int,
    public val height: Int,
    public val bitsPerComponent: Int,
    public val componentCount: Int,
    public val colourSpace: JpegColourSpace,
    public val encoding: JpegEncoding,
    public val density: JpegDensity?,
    public val adobeColourTransform: Int?,
) {
    /**
     * The transformation the samples of this stream have had applied to them,
     * as Table 13 decides it.
     *
     * The clause hands the decision to the encoded data first and to the filter
     * dictionary second: "If the encoding algorithm has inserted the
     * Adobe-defined marker code in the encoded data indicating the
     * ColorTransform value, then the colours shall be transformed, or not …
     * according to the value provided in the encoded data and the value of this
     * dictionary entry shall be ignored." Only when APP14 is absent does the
     * dictionary get a say, and when both are absent the default is "1 if the
     * image has three components and 0 otherwise".
     *
     * This property is the first and the third of those: what APP14 said, or
     * the default for the component count. It is what a `/DecodeParms` entry
     * would be written from — and, when APP14 was present, the reason it need
     * not be written at all.
     */
    public val colourTransform: Int
        get() = adobeColourTransform ?: if (componentCount == 3) 1 else 0

    override fun toString(): String = "JpegHeader($width×$height, $componentCount components as " +
        "${colourSpace.pdfName.name}, $bitsPerComponent bits, $encoding" +
        (density?.let { ", $it" } ?: "") + ")"

    public companion object {
        /**
         * Reads the header of the JPEG data stream [bytes].
         *
         * The walk stops at SOS: everything this returns is stated before the
         * first scan, and the entropy-coded data after it can only be walked by
         * decoding them, which this library never does.
         *
         * @throws IllegalArgumentException if [bytes] are not a JPEG data stream
         *   whose image the DCTDecode filter can be asked to decode, naming the
         *   marker that made it say so.
         */
        public fun parse(bytes: ByteArray): JpegHeader {
            val markers = JpegMarkerWalk(bytes)
            val start = markers.nextMarker("at the start of the JPEG data")
            require(start == JpegMarker.START_OF_IMAGE) {
                "the JPEG data start with ${JpegMarker.nameOf(start)} rather than " +
                    "${JpegMarker.nameOf(JpegMarker.START_OF_IMAGE)}, which every JPEG data stream begins with"
            }

            var frame: JpegFrameHeader? = null
            var density: JpegDensity? = null
            var adobe: Int? = null
            var after = JpegMarker.nameOf(JpegMarker.START_OF_IMAGE)

            while (true) {
                val marker = markers.nextMarker("after $after")
                after = JpegMarker.nameOf(marker)
                if (marker == JpegMarker.START_OF_SCAN || marker == JpegMarker.END_OF_IMAGE) break
                if (JpegMarker.isStandalone(marker)) continue
                val segment = markers.segmentOf(marker)
                when {
                    JpegMarker.isStartOfFrame(marker) -> {
                        require(frame == null) {
                            "${JpegMarker.nameOf(marker)}: the JPEG data carry a second frame header, after the " +
                                "one ${JpegMarker.nameOf(frame!!.marker)} started; an image is one frame"
                        }
                        frame = JpegFrameHeader.parse(marker, segment)
                    }
                    // The first JFIF APP0 is the one JFIF defines; a second, and
                    // an APP0 of any other identifier (JFXX and the like), says
                    // nothing about the density.
                    marker == JpegMarker.APP0 && density == null -> density = jfifDensityOrNull(segment)
                    marker == JpegMarker.APP14 && adobe == null -> adobe = adobeColourTransformOrNull(segment)
                }
            }

            val header = requireNotNull(frame) {
                "the JPEG data carry no frame header: none of ${JpegMarker.nameOf(JpegMarker.BASELINE_DCT)}, " +
                    "${JpegMarker.nameOf(JpegMarker.EXTENDED_SEQUENTIAL_DCT)} or " +
                    "${JpegMarker.nameOf(JpegMarker.PROGRESSIVE_DCT)} appears before $after, and the dimensions " +
                    "of an image are stated nowhere else"
            }
            return JpegHeader(
                width = header.width,
                height = header.height,
                bitsPerComponent = header.bitsPerComponent,
                componentCount = header.componentCount,
                colourSpace = header.colourSpace,
                encoding = header.encoding,
                density = density,
                adobeColourTransform = adobe,
            )
        }

        /**
         * The density stated by the JFIF APP0 segment whose payload is
         * [segment], or null if [segment] is an APP0 of some other identifier.
         *
         * JFIF fixes the segment: the five bytes `JFIF\0`, a two-byte version, a
         * one-byte units field, Xdensity and Ydensity as two bytes each, then
         * the size of an optional thumbnail. The densities are required to be
         * non-zero, which matters — a zero would make the pixel aspect ratio
         * this reports a ratio to nothing.
         */
        private fun jfifDensityOrNull(segment: ByteArray): JpegDensity? {
            val name = JpegMarker.nameOf(JpegMarker.APP0)
            if (!segment.startsWith(JFIF_IDENTIFIER)) return null
            require(segment.size >= JFIF_MINIMUM_SIZE) {
                "$name: a JFIF segment is at least $JFIF_MINIMUM_SIZE bytes after its length parameter — the " +
                    "identifier, the version, the units and the two densities, then the size of the thumbnail — " +
                    "and this one is ${segment.size}"
            }
            val unitsCode = segment.uint8At(JFIF_UNITS_OFFSET)
            val unit = requireNotNull(JpegDensityUnit.forCode(unitsCode)) {
                "$name: the JFIF units field is $unitsCode, which denotes no unit; the defined values are " +
                    "0 (no units, the densities being a pixel aspect ratio), 1 (dots per inch) and " +
                    "2 (dots per centimetre)"
            }
            val horizontal = segment.uint16At(JFIF_UNITS_OFFSET + 1)
            val vertical = segment.uint16At(JFIF_UNITS_OFFSET + 3)
            require(horizontal > 0 && vertical > 0) {
                "$name: the JFIF densities are $horizontal and $vertical; neither Xdensity nor Ydensity may be " +
                    "zero, since together they state either a physical size or the ratio between the two sides " +
                    "of a pixel"
            }
            return JpegDensity(unit, horizontal, vertical)
        }

        /**
         * The transform code of the Adobe APP14 segment whose payload is
         * [segment], or null if [segment] is an APP14 of some other identifier.
         *
         * The segment is the one §7.4.8 calls "the Adobe-defined marker code …
         * indicating the ColorTransform value": the five bytes `Adobe`, a
         * version and two flag fields of two bytes each, and the transform code
         * last. Its three values are 0 (none), 1 (YCbCr, three components) and
         * 2 (YCCK, four components) — 1 and 2 being what Table 13 describes as
         * transforming RGB to YCbCr and CMYK to YCbCrK before encoding.
         */
        private fun adobeColourTransformOrNull(segment: ByteArray): Int? {
            val name = JpegMarker.nameOf(JpegMarker.APP14)
            if (!segment.startsWith(ADOBE_IDENTIFIER)) return null
            require(segment.size >= ADOBE_MINIMUM_SIZE) {
                "$name: an Adobe segment is at least $ADOBE_MINIMUM_SIZE bytes after its length parameter — the " +
                    "identifier, the version, two flag fields and the transform code — and this one is " +
                    "${segment.size}"
            }
            val transform = segment.uint8At(ADOBE_TRANSFORM_OFFSET)
            require(transform in 0..ADOBE_TRANSFORM_MAX) {
                "$name: the Adobe transform code is $transform; the defined values are 0 (no transformation), " +
                    "1 (three components transformed between RGB and YCbCr) and 2 (four components transformed " +
                    "between CMYK and YCCK)"
            }
            return transform
        }

        /** `JFIF` and the null that terminates it — the identifier of a JFIF APP0 segment. */
        private val JFIF_IDENTIFIER: ByteArray = byteArrayOf(0x4A, 0x46, 0x49, 0x46, 0x00)

        /** `Adobe` — the identifier of the APP14 segment, five bytes and no terminator. */
        private val ADOBE_IDENTIFIER: ByteArray = byteArrayOf(0x41, 0x64, 0x6F, 0x62, 0x65)

        /** Where the units field sits: after the identifier and the two-byte version. */
        private const val JFIF_UNITS_OFFSET: Int = 7

        /** Identifier, version, units, two densities, and the thumbnail's two dimensions. */
        private const val JFIF_MINIMUM_SIZE: Int = 14

        /** Where the transform code sits: after the identifier, the version and two flag fields. */
        private const val ADOBE_TRANSFORM_OFFSET: Int = 11

        /** Identifier, version, two flag fields, transform code. */
        private const val ADOBE_MINIMUM_SIZE: Int = 12

        /** The largest defined Adobe transform code: 2, YCCK. */
        private const val ADOBE_TRANSFORM_MAX: Int = 2
    }
}

/**
 * The parameters of a frame header, §B.2.2 — the fields SOFn is followed by,
 * before they become the entries of an image dictionary.
 *
 * @property marker which SOFn started the frame, kept so that a refusal raised
 *   later can still name it.
 */
private class JpegFrameHeader(
    val marker: Int,
    val encoding: JpegEncoding,
    val bitsPerComponent: Int,
    val width: Int,
    val height: Int,
    val componentCount: Int,
    val colourSpace: JpegColourSpace,
) {
    companion object {
        /**
         * Reads the frame header [segment] that the marker [marker] introduced.
         *
         * The syntax is the same for every SOFn: the sample precision P, the
         * number of lines Y, the number of samples per line X, the number of
         * components Nf, and then three bytes per component — its identifier,
         * its two sampling factors packed into one byte, and the quantization
         * table it uses. Lf, the length, is therefore fixed by Nf at 8 + 3 × Nf,
         * and a header where the two disagree is refused: one of the two numbers
         * is wrong and nothing here can tell which.
         */
        fun parse(marker: Int, segment: ByteArray): JpegFrameHeader {
            val name = JpegMarker.nameOf(marker)
            val encoding = requireNotNull(JpegEncoding.forMarker(marker)) {
                "$name: the DCTDecode filter decodes data \"encoded in the JPEG baseline format\" and, since " +
                    "PDF 1.3, the progressive extension (ISO 32000-2:2020 §7.4.8); those are the frames " +
                    "${JpegMarker.nameOf(JpegMarker.BASELINE_DCT)}, " +
                    "${JpegMarker.nameOf(JpegMarker.EXTENDED_SEQUENTIAL_DCT)} and " +
                    "${JpegMarker.nameOf(JpegMarker.PROGRESSIVE_DCT)} start, and this is not one of them"
            }
            require(segment.size >= FIXED_FIELDS) {
                "$name: a frame header is at least $FIXED_FIELDS bytes after its length parameter — the sample " +
                    "precision, the two dimensions and the number of components — and this one is ${segment.size}"
            }

            val bitsPerComponent = segment.uint8At(0)
            val allowedPrecisions = if (encoding == JpegEncoding.BASELINE) BASELINE_PRECISIONS else DCT_PRECISIONS
            require(bitsPerComponent in allowedPrecisions) {
                "$name: the sample precision P is $bitsPerComponent bits; a frame this marker starts has " +
                    allowedPrecisions.joinToString(" or ") { "$it" } + " bits per sample"
            }

            val height = segment.uint16At(1)
            require(height > 0) {
                "$name: the number of lines Y is zero, which means the height is stated by a " +
                    "${JpegMarker.nameOf(0xDC)} marker at the end of the first scan; an image XObject has to " +
                    "carry its /Height, and these data do not state one before the scan begins"
            }
            val width = segment.uint16At(3)
            require(width > 0) {
                "$name: the number of samples per line X is zero, and an image has at least one column"
            }

            val componentCount = segment.uint8At(5)
            require(componentCount > 0) {
                "$name: the number of components Nf is zero, and a frame has at least one component"
            }
            require(segment.size == FIXED_FIELDS + BYTES_PER_COMPONENT * componentCount) {
                "$name: the frame header declares $componentCount components, so its length Lf is " +
                    "${2 + FIXED_FIELDS + BYTES_PER_COMPONENT * componentCount} bytes — three bytes per " +
                    "component after the fixed fields — and it declared ${2 + segment.size}"
            }
            val colourSpace = requireNotNull(JpegColourSpace.forComponentCount(componentCount)) {
                "$name: the frame has $componentCount colour components; the DCTDecode filter decodes samples " +
                    "\"each consisting of one, three, or four colour components\" (ISO 32000-2:2020 §7.4.8), " +
                    "which are the components of /DeviceGray, /DeviceRGB and /DeviceCMYK"
            }

            for (component in 0 until componentCount) {
                val at = FIXED_FIELDS + BYTES_PER_COMPONENT * component
                val sampling = segment.uint8At(at + 1)
                val horizontal = sampling shr 4
                val vertical = sampling and 0x0F
                require(horizontal in 1..MAX_SAMPLING_FACTOR && vertical in 1..MAX_SAMPLING_FACTOR) {
                    "$name: component $component has the sampling factors $horizontal and $vertical; each is a " +
                        "four-bit field holding 1 to $MAX_SAMPLING_FACTOR"
                }
                val quantizationTable = segment.uint8At(at + 2)
                require(quantizationTable in 0..MAX_QUANTIZATION_TABLE) {
                    "$name: component $component selects the quantization table $quantizationTable; a DCT-based " +
                        "frame has the tables 0 to $MAX_QUANTIZATION_TABLE"
                }
            }

            return JpegFrameHeader(
                marker = marker,
                encoding = encoding,
                bitsPerComponent = bitsPerComponent,
                width = width,
                height = height,
                componentCount = componentCount,
                colourSpace = colourSpace,
            )
        }

        /** P, Y, X and Nf: one byte, two, two, one. */
        private const val FIXED_FIELDS: Int = 6

        /** Ci, Hi packed with Vi, Tqi. */
        private const val BYTES_PER_COMPONENT: Int = 3

        private val BASELINE_PRECISIONS: List<Int> = listOf(8)
        private val DCT_PRECISIONS: List<Int> = listOf(8, 12)
        private const val MAX_SAMPLING_FACTOR: Int = 4
        private const val MAX_QUANTIZATION_TABLE: Int = 3
    }
}

/**
 * A cursor over a JPEG data stream that reads markers and their segments,
 * §B.1.1.2 and §B.1.1.4.
 *
 * It knows nothing about what any segment means. Its whole job is that a marker
 * is found where one must be, and that a segment which claims a length has one
 * — so that the parser above walks a structure that has already been checked
 * rather than indexing into bytes and hoping.
 */
private class JpegMarkerWalk(private val data: ByteArray) {
    private var at: Int = 0

    /**
     * The code of the next marker, leaving the cursor on the byte after it.
     *
     * A marker is X'FF' followed by its code, and "any marker may optionally be
     * preceded by any number of fill bytes, which are bytes assigned code
     * X'FF'" — so a run of X'FF' is skipped and the first byte that is not one
     * is the code. X'FF00' is not a marker: that pair is how a data byte of
     * X'FF' is carried inside entropy-coded data, and meeting it where a marker
     * belongs means the walk has left the header.
     *
     * @param where what the cursor is at, for the refusal — "after SOI
     *   (0xFFD8)".
     */
    fun nextMarker(where: String): Int {
        require(at + 2 <= data.size) {
            "the JPEG data end $where, before the next marker; a marker is two bytes, X'FF' and a code"
        }
        val first = data.uint8At(at)
        require(first == JpegMarker.FILL) {
            "expected a marker $where, but the byte at offset $at is " +
                "0x${JpegMarker.hexByte(first)}; every marker starts with X'FF'"
        }
        var index = at
        while (index < data.size && data.uint8At(index) == JpegMarker.FILL) index++
        require(index < data.size) {
            "the JPEG data end $where in the fill bytes that may precede a marker, with no marker code after them"
        }
        val code = data.uint8At(index)
        require(code != 0x00) {
            "expected a marker $where, but X'FF' is followed by X'00', which is not a marker: it is the stuffed " +
                "byte pair that carries a data value of X'FF' through entropy-coded data"
        }
        at = index + 1
        return code
    }

    /**
     * The payload of the segment introduced by [marker] — everything the length
     * parameter covers except the two bytes of the parameter itself — leaving
     * the cursor on the byte after the segment.
     *
     * The length "includes the two bytes of the length parameter", so a value
     * below 2 describes a segment shorter than its own length field and is
     * refused here rather than turning into a negative payload.
     */
    fun segmentOf(marker: Int): ByteArray {
        val name = JpegMarker.nameOf(marker)
        require(at + 2 <= data.size) {
            "$name: the JPEG data end before its two-byte segment length parameter, of which " +
                "${data.size - at} bytes are present"
        }
        val length = data.uint16At(at)
        require(length >= 2) {
            "$name: declares a segment length of $length bytes; the parameter counts its own two bytes, so the " +
                "smallest a segment can declare is 2"
        }
        require(at + length <= data.size) {
            "$name: declares a segment of $length bytes at offset $at, and only ${data.size - at} bytes of the " +
                "JPEG data remain"
        }
        val payload = data.copyOfRange(at + 2, at + length)
        at += length
        return payload
    }
}

/** The unsigned byte at [offset]. Bounds are the caller's, having checked the length. */
private fun ByteArray.uint8At(offset: Int): Int = this[offset].toInt() and 0xFF

/** The two-byte big-endian value at [offset]; JPEG writes every multi-byte parameter this way. */
private fun ByteArray.uint16At(offset: Int): Int = (uint8At(offset) shl 8) or uint8At(offset + 1)

/** Whether this array begins with [prefix] — how a segment's identifier is recognised. */
private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
    if (size < prefix.size) return false
    for (index in prefix.indices) if (this[index] != prefix[index]) return false
    return true
}
