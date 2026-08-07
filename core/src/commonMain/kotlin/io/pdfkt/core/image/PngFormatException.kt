/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.image

// Portable Network Graphics (PNG) Specification (Third Edition),
// W3C Recommendation 24 June 2025
//   §4.10 "Error handling", §13.1 "Error handling", §13.2 "Error checking"

/**
 * A PNG datastream this library will not turn into samples, refused at the byte
 * where it stops being decodable.
 *
 * Two kinds of thing end up here, and the message says which:
 *
 * - a datastream that is not what the specification describes — a signature
 *   that is not the eight bytes of §5.2, a CRC that does not match the chunk it
 *   covers, an IHDR declaring a bit depth its colour type does not admit, image
 *   data that decompress to fewer bytes than the declared height needs. §13.1
 *   calls these transmission and syntax errors, and says a decoder should
 *   "detect errors as early as possible" and "provide helpful messages
 *   describing errors";
 * - a datastream that is valid and that this decoder does not read — today that
 *   is interlace method 1, the Adam7 method of §8.1. Refusing it is the point:
 *   a decoder that took the seven reduced images for one image would produce a
 *   scrambled picture with nothing to say about it, which is the silent loss
 *   this library's design rules out.
 *
 * [offset] is the byte the fault was detected at, counted from the start of the
 * datastream, so a caller holding a file can look at the bytes concerned. Where
 * a fault is found in the *decompressed* image data — an unknown filter type,
 * a short final scanline — the position inside that decompressed stream cannot
 * be a position in the file, because "IDAT chunk boundaries have no semantic
 * significance and can occur at any point in the compressed datastream" (§13.8);
 * such faults report the offset of the image data in the file and state the
 * decompressed position in [problem].
 *
 * [clause] is the sentence of the specification that was broken, because
 * "invalid bit depth" is a verdict and "colour type 2 admits the bit depths 8
 * and 16 (Table 12)" is a verdict with its reason attached.
 *
 * A subtype of [IllegalArgumentException] because that is what such a
 * datastream is: an argument the function cannot accept.
 *
 * @property offset the byte the fault was detected at, from the start of the
 *   data passed to [PngImage.decode].
 * @property problem what is wrong, in the terms the specification uses for it.
 * @property clause the clause that says so.
 */
public class PngFormatException internal constructor(
    public val offset: Int,
    public val problem: String,
    public val clause: String,
    cause: Throwable? = null,
) : IllegalArgumentException("malformed PNG datastream at byte $offset: $problem ($clause)", cause)
