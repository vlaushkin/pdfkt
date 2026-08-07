/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.font.truetype

// OpenType 1.9.1 §"cmap — Character To Glyph Index Mapping Table":
// "Table overview", "Encoding records and encodings"

/**
 * One subtable of `cmap`: which glyph a character code selects.
 *
 * The subtable formats differ in the code space they cover and in how sparsely
 * they can describe it; they do not differ in what they answer. The one rule
 * they share is the one the table overview states for all of them:
 *
 * > Regardless of the encoding scheme, character codes that do not correspond to
 * > any glyph in the font should be mapped to glyph index 0. The glyph at this
 * > location must be a special glyph representing a missing character, commonly
 * > known as .notdef.
 *
 * So [glyphIdOf] returns 0 for a character the font does not have, and this is
 * the one place in this library where a missing glyph is not an error — the
 * caller that turns 0 into a refusal is the one that knows which character was
 * asked for and where it came from.
 */
internal interface CmapSubtable {
    /** The subtable format — 4 or 12; see [CmapFormat4Subtable], [CmapFormat12Subtable]. */
    val format: Int

    /** The glyph [codePoint] selects, or 0 if the font has no glyph for it. */
    fun glyphIdOf(codePoint: Int): Int
}

/**
 * One `EncodingRecord` of the `cmap` header and the subtable it points at.
 *
 * @property platformId "Platform ID."
 * @property encodingId "Platform-specific encoding ID."
 * @property format the format of the subtable, which is read whether or not the
 *   subtable itself is.
 * @property subtable the parsed subtable, or null for a format this library does
 *   not read. Keeping the record either way is what lets a font that cannot be
 *   used say what it does contain.
 */
internal class CmapEncoding(val platformId: Int, val encodingId: Int, val format: Int, val subtable: CmapSubtable?) {
    /**
     * Whether this record names a Unicode encoding — the only kind this library
     * maps through, since everything it draws it draws from Unicode text.
     *
     * The Unicode platform is Unicode by definition, except for encoding 5,
     * which "must only be used" for the variation-sequence format 14 and maps
     * nothing on its own. On the Windows platform the specification names
     * exactly two: "When creating fonts for Windows, Unicode 'cmap' subtables
     * should always be used—platform ID 3 with encoding ID 1 or encoding ID 10."
     */
    val isUnicode: Boolean
        get() = when (platformId) {
            PLATFORM_UNICODE -> encodingId != UNICODE_VARIATION_SEQUENCES
            PLATFORM_WINDOWS -> encodingId == WINDOWS_UNICODE_BMP || encodingId == WINDOWS_UNICODE_FULL
            else -> false
        }

    override fun toString(): String = "platform $platformId, encoding $encodingId, format $format"

    companion object {
        /** "0 | Unicode | Various" */
        const val PLATFORM_UNICODE: Int = 0

        /** "3 | Windows | Windows encoding" */
        const val PLATFORM_WINDOWS: Int = 3

        /** "5 | Unicode variation sequences—for use with subtable format 14" */
        const val UNICODE_VARIATION_SEQUENCES: Int = 5

        /** "3 | 1 | Unicode BMP" */
        const val WINDOWS_UNICODE_BMP: Int = 1

        /** "3 | 10 | Unicode full repertoire" */
        const val WINDOWS_UNICODE_FULL: Int = 10
    }
}
