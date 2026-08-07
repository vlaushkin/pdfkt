/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.font.truetype

// OpenType 1.9.1 §"post — PostScript Table"
// ISO 32000-2:2020 §9.8.1 "Font descriptors — General" (Table 120)

/**
 * The header of the `post` table: the two fields a PDF font descriptor has no
 * other source for.
 *
 * Table 120 marks `ItalicAngle` Required — "The angle, expressed in degrees
 * counterclockwise from the vertical, of the dominant vertical strokes of the
 * font" — and there is exactly one field in a font program that states it. The
 * alternative to reading it is writing 0 into every descriptor, which for an
 * italic font is a number the file asserts and the font contradicts.
 *
 * `isFixedPitch` answers bit 1 of Table 121, `FixedPitch`: "All glyphs have the
 * same width". The `hmtx` table would answer it too, by comparing every advance
 * in the font; the flag is the font's own statement of the same fact, and it is
 * four bytes away from the one this table is opened for.
 *
 * Only the header is read. Everything after it is a glyph-name array, and glyph
 * names are of no interest to a font addressed by glyph index (§9.7.4.2). The
 * header is the same in every version the specification defines — 1.0, 2.0, 2.5,
 * 3.0 and 4.0 differ only in what follows it — so the version is read past
 * rather than checked: a version this parser has not heard of still begins with
 * the fields below.
 *
 * The table is optional in a font this library is handed, and absent from every
 * font it writes: Table 124 does not list `post` among the tables an embedded
 * TrueType program has to include.
 *
 * @property italicAngle degrees counterclockwise from the vertical; negative for
 *   fonts that slope to the right, and 0 for upright ones.
 * @property isFixedPitch whether "the font is monospaced".
 */
internal class PostTable(val italicAngle: Double, val isFixedPitch: Boolean) {
    override fun toString(): String = "post(italicAngle=$italicAngle, isFixedPitch=$isFixedPitch)"

    companion object {
        /**
         * A `Fixed` is "32-bit signed fixed-point number (16.16)": the integer it
         * is stored as, divided by 2^16.
         */
        private const val FIXED_POINT_ONE: Double = 65536.0

        /** Reads the header of the table [reader] covers. */
        fun parse(reader: BigEndianReader): PostTable {
            reader.seek(0)
            reader.skip(4) // version — the fields below are common to all of them
            val italicAngle = reader.uint32().toInt() / FIXED_POINT_ONE
            reader.skip(2) // underlinePosition — FWORD
            reader.skip(2) // underlineThickness — FWORD
            val isFixedPitch = reader.uint32() != 0L
            return PostTable(italicAngle, isFixedPitch)
        }
    }
}
