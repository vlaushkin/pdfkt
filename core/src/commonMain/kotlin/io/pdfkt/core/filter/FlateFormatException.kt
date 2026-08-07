/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.filter

// ISO 32000-2:2020 §7.4.4 "LZWDecode and FlateDecode filters"
// RFC 1950 "ZLIB Compressed Data Format Specification version 3.3" §2.2 "Data format"
// RFC 1951 "DEFLATE Compressed Data Format Specification version 1.3" §3.2 "Compressed
//   representation overview"

/**
 * A stream that is not what RFC 1950 and RFC 1951 describe, refused at the byte
 * where it stops being that.
 *
 * A decoder meets bytes it did not write, and every one of the two RFCs' rules
 * is a rule some stream out there breaks: a header whose check bits do not
 * multiply out, a stored block whose NLEN is not the complement of its LEN, a
 * code table that hands out more codes than the bit lengths admit, a distance
 * pointing before the beginning of the output, a checksum that does not match
 * what came out. None of those can be decoded into anything; what a decoder
 * chooses is what it says about them.
 *
 * It says three things, and [offset] is the first: the byte the fault was
 * detected at, counted from the start of the data handed in, so a caller
 * holding a 4 MB stream can look at the bytes concerned instead of at the whole.
 * For a stream that simply stops early the offset is its length — the fault is
 * at the end, which is where the data the format requires is missing from. Then
 * [problem], in words, and [clause], the sentence of the RFC that was broken,
 * because "invalid distance" is a verdict and "a distance cannot refer past the
 * beginning of the output stream (RFC 1951 §3.2.3)" is a verdict with its
 * reason attached.
 *
 * What it never does is return the bytes decoded so far. Truncated compressed
 * data decodes to a prefix of the original and looks exactly like data — a
 * content stream missing its last operators, an image missing its last rows —
 * which is the silent loss this library's design rules out.
 *
 * A subtype of [IllegalArgumentException] because that is what a malformed
 * stream is: an argument the function cannot accept.
 *
 * @property offset the byte the fault was detected at, from the start of the
 *   data passed to [flateDecode]. Bits are read least significant first within
 *   a byte (RFC 1951 §3.1.1), so a fault inside a byte is reported at that byte.
 * @property problem what is wrong, in the terms the RFC uses for it.
 * @property clause the clause that says so.
 */
public class FlateFormatException internal constructor(
    public val offset: Int,
    public val problem: String,
    public val clause: String,
) : IllegalArgumentException("malformed flate stream at byte $offset: $problem ($clause)")
