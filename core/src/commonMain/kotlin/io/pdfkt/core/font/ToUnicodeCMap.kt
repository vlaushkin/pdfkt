/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.font

import io.pdfkt.core.obj.PdfDict
import io.pdfkt.core.obj.PdfInt
import io.pdfkt.core.obj.PdfName
import io.pdfkt.core.obj.PdfString

// ISO 32000-2:2020 §9.10.3 "ToUnicode CMaps", §9.10.2 "Mapping character codes
// to Unicode values", §9.7.5.4 "CMap example and operator summary",
// §9.6.1 "Simple fonts — General"

/**
 * The CMap program of a `/ToUnicode` stream: what each character code a page
 * shows stands for, as Unicode.
 *
 * Without it a `Identity-H` document says nothing about its own text. §9.10.3
 * puts it plainly, of the very arrangement this library writes:
 *
 * > This example illustrates a Type 0 font that uses the `Identity-H` CMap to
 * > map from character codes to CIDs and whose descendant CIDFont uses the
 * > Identity mapping from CIDs to TrueType glyph indices. Text strings shown
 * > using this font simply use a 2-byte glyph index for each glyph. In the
 * > absence of a `ToUnicode` entry, no information would be available about what
 * > the glyphs mean.
 *
 * "No information available" is the silent loss this library forbids
 * arriving one layer later: a document that renders perfectly and from which
 * nothing can be copied, searched or read aloud. So the stream is written
 * always, for every code the document showed.
 *
 * ## The shape
 *
 * The program below is the one Example 2 of the clause writes out, with the
 * mappings changed: a `begincmap`…`endcmap` body carrying a `CIDSystemInfo`, a
 * `CMapName`, `CMapType 2`, one codespace range and the `beginbfchar` blocks.
 * The clause requires the middle two of those — "It shall use the `beginbfchar`,
 * `endbfchar`, `beginbfrange`, and `endbfrange` operators to define the mapping
 * from character codes to Unicode character sequences expressed in UTF-16BE
 * encoding", and "The CMap file shall contain `begincodespacerange` and
 * `endcodespacerange` operators that are consistent with the encoding that the
 * font uses".
 *
 * The codespace is the one the font's own encoding uses, which is what the
 * clause requires of it — "The CMap file shall contain begincodespacerange and
 * endcodespacerange operators that are consistent with the encoding that the
 * font uses" — and §9.10.3 admits both widths this library writes: "For CID
 * keyed fonts character codes may have 1 byte, 2 bytes, or more than 2 bytes".
 * `<0000>` to `<FFFF>` for the composite path, since `Identity-H` "maps 2-byte
 * character codes ranging from 0 to 65,535" (§9.7.5.2); `<00>` to `<FF>` for a
 * simple font, whose codes are single bytes ("the glyphs … shall be selected by
 * single-byte character codes", §9.6.1).
 *
 * Only `beginbfchar` is used, never `beginbfrange`. A range is the compact form
 * for consecutive codes mapping to consecutive characters, and the codes here
 * are glyph indices of a font program: whether two of them are adjacent, and
 * whether the characters they draw are, is a property of the font rather than of
 * the text, so a writer that emitted ranges would emit one range per code in
 * most fonts and a subtle mismapping in the rest.
 *
 * @param mappings each character code, as it appears in a shown string, against
 *   the text it stands for. In code order or not; written in the order given.
 * @param bytesPerCode how wide a character code of this font is, which is what
 *   the declared codespace and the `srcCode` of every `bfchar` are written to:
 *   two bytes for the composite path, one for a simple font.
 * @throws IllegalArgumentException if a code is outside the codespace this CMap
 *   declares, or a destination is empty or longer than the 512 bytes the clause
 *   allows a `dstString`.
 */
internal fun toUnicodeCMap(mappings: List<Pair<Int, String>>, bytesPerCode: Int = BYTES_PER_COMPOSITE_CODE): ByteArray {
    require(bytesPerCode == 1 || bytesPerCode == BYTES_PER_COMPOSITE_CODE) {
        "a character code of a font this library writes is one byte (a simple font, ISO 32000-2:2020 " +
            "§9.6.1) or two (Identity-H, §9.7.5.2); $bytesPerCode is neither"
    }
    val digits = bytesPerCode * HEX_DIGITS_PER_BYTE
    val codeMax = (1 shl (bytesPerCode * BITS_PER_BYTE)) - 1
    val program = StringBuilder(prologue(digits, codeMax))
    for (block in mappings.chunked(ENTRIES_PER_BLOCK)) {
        program.append(block.size).append(" beginbfchar\n")
        for ((code, text) in block) {
            require(code in 0..codeMax) {
                "the character code $code is outside the <${hex(0, digits)}>..<${hex(codeMax, digits)}> " +
                    "codespace this CMap declares, which is the $bytesPerCode-byte encoding of the font " +
                    "it is written for (ISO 32000-2:2020 §9.10.3)"
            }
            require(text.isNotEmpty()) {
                "the character code $code maps to no text; a ToUnicode entry that maps a code to nothing " +
                    "is the code left unexplained, which is what writing the CMap was for"
            }
            require(text.length * BYTES_PER_UTF16_UNIT <= MAX_DESTINATION_BYTES) {
                "the character code $code maps to ${text.length * BYTES_PER_UTF16_UNIT} bytes of UTF-16BE, " +
                    "past the $MAX_DESTINATION_BYTES a dstString may be (ISO 32000-2:2020 §9.10.3)"
            }
            program.append('<').append(hex(code, digits)).append("> <").append(utf16BeHex(text)).append(">\n")
        }
        program.append("endbfchar\n")
    }
    program.append(EPILOGUE)
    return program.toString().encodeToByteArray()
}

/**
 * The stream dictionary a `ToUnicode` CMap is written under, as Example 2 of
 * §9.10.3 writes it, minus the entries a stream computes for itself. Table 118
 * says only `UseCMap` is pertinent to such a CMap, and one written here is based
 * on no other.
 *
 * The same for every font this library writes, whatever the width of its codes:
 * the entries describe the destinations, which are Unicode either way.
 */
internal val TO_UNICODE_STREAM_DICT: PdfDict = PdfDict(
    mapOf(
        PdfName("Type") to PdfName("CMap"),
        PdfName("CMapName") to PdfName("Adobe-Identity-UCS2"),
        PdfName("CIDSystemInfo") to PdfDict(
            mapOf(
                PdfName("Registry") to PdfString("Adobe".encodeToByteArray()),
                PdfName("Ordering") to PdfString("UCS2".encodeToByteArray()),
                PdfName("Supplement") to PdfInt(0),
            ),
        ),
    ),
)

/** [text] in "UTF-16BE encoding", as hexadecimal — the destination of a `bfchar`. */
private fun utf16BeHex(text: String): String {
    val hex = StringBuilder(text.length * HEX_DIGITS_PER_UTF16_UNIT)
    // A Kotlin string is a sequence of UTF-16 code units already, so a character
    // beyond the basic multilingual plane is written as the surrogate pair it is
    // held as — which is what UTF-16BE is: the clause's own example maps one code
    // to <D840DC3E>, the pair for U+2003E.
    for (unit in text) hex.append(hex(unit.code, HEX_DIGITS_PER_UTF16_UNIT))
    return hex.toString()
}

/** [value] as [digits] uppercase hexadecimal digits — how a code and a UTF-16 unit are written. */
private fun hex(value: Int, digits: Int): String = value.toString(radix = 16).uppercase().padStart(digits, '0')

/**
 * Everything before the first `beginbfchar`, from Example 2 of §9.10.3, over a
 * codespace of `<0…0>` to `<F…F>` [digits] hexadecimal digits wide.
 *
 * `Adobe-Identity-UCS2` names what this CMap is: destinations in UCS-2/UTF-16BE
 * against codes that carry no character collection of their own. `CMapType 2` is
 * the type the clause's example gives a ToUnicode CMap.
 */
private fun prologue(digits: Int, codeMax: Int): String =
    """
    /CIDInit /ProcSet findresource begin
    12 dict begin
    begincmap
    /CIDSystemInfo
    <</Registry (Adobe)
    /Ordering (UCS2)
    /Supplement 0
    >> def
    /CMapName /Adobe-Identity-UCS2 def
    /CMapType 2 def
    1 begincodespacerange
    <${hex(0, digits)}> <${hex(codeMax, digits)}>
    endcodespacerange

    """.trimIndent()

/** Everything after the last `endbfchar`, from the same example. */
private val EPILOGUE: String =
    """
    endcmap
    CMapName currentdict /CMap defineresource pop
    end
    end

    """.trimIndent()

/**
 * How many mappings go in one `beginbfchar`…`endbfchar` block.
 *
 * The count before the operator is part of the syntax, so the blocks have to be
 * cut somewhere. A hundred is where the specification's own CMap cuts them: the
 * example of §9.7.5.4 writes `100 begincidrange`.
 */
private const val ENTRIES_PER_BLOCK: Int = 100

/** "dstString may be a string of up to 512 bytes". */
private const val MAX_DESTINATION_BYTES: Int = 512

/** The width of an `Identity-H` code: "2-byte character codes" (§9.7.5.2). */
private const val BYTES_PER_COMPOSITE_CODE: Int = 2

private const val BYTES_PER_UTF16_UNIT: Int = 2
private const val HEX_DIGITS_PER_UTF16_UNIT: Int = 4
private const val HEX_DIGITS_PER_BYTE: Int = 2
private const val BITS_PER_BYTE: Int = 8
