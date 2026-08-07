/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.image

// ISO 32000-2:2020 §7.4.8 "DCTDecode filter"
// ITU-T T.81 (09/92) | ISO/IEC 10918-1 §B.2.2 "Frame header syntax" (Table B.1, SOFn)

/**
 * The encoding process of a JPEG frame — which SOFn marker started it.
 *
 * §7.4.8 defines DCTDecode over "the JPEG baseline format in accordance with
 * ISO/IEC 10918 (all parts)" and then adds, "beginning with PDF 1.3, the
 * DCTDecode filter shall support the progressive JPEG extension … the
 * distinction between baseline and progressive JPEG shall be represented in the
 * encoded data". This enum is that distinction, read out of the encoded data:
 * the three frames a PDF processor is required to decode, and nothing else.
 *
 * The frames left out are left out deliberately. Lossless (SOF3), differential
 * (SOF5–SOF7, SOF13–SOF15) and arithmetic-coded (SOF9–SOF11) frames are legal
 * JPEG that no PDF processor has to decode, so embedding one produces a file
 * that opens somewhere and is blank somewhere else. Refusing it names the
 * marker instead.
 *
 * @property marker the SOFn code that starts a frame with this encoding.
 */
public enum class JpegEncoding(internal val marker: Int) {
    /** SOF0 — baseline sequential DCT, Huffman coding, 8 bits per sample. */
    BASELINE(JpegMarker.BASELINE_DCT),

    /** SOF1 — extended sequential DCT, Huffman coding, 8 or 12 bits per sample. */
    EXTENDED_SEQUENTIAL(JpegMarker.EXTENDED_SEQUENTIAL_DCT),

    /** SOF2 — progressive DCT, Huffman coding; the extension PDF 1.3 added. */
    PROGRESSIVE(JpegMarker.PROGRESSIVE_DCT),

    ;

    internal companion object {
        /** The encoding [marker] starts, or null if it is a frame DCTDecode does not decode. */
        fun forMarker(marker: Int): JpegEncoding? = entries.firstOrNull { it.marker == marker }
    }
}
