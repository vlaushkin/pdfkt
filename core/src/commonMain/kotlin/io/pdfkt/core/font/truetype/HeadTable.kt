/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.font.truetype

// OpenType 1.9.1 §"head — Font header table"
// ISO 32000-2:2020 §9.2.4 "Glyph positioning and metrics" (NOTE 3),
// §9.8.1 "Font descriptors — General" (Table 120, FontBBox)

/**
 * `head` — "This table gives global information about the font."
 *
 * Two of its fields are why this table is parsed at all:
 *
 * - [unitsPerEm] is the size of the em in the units every other measurement in
 *   the font program is expressed in, and therefore the divisor that turns them
 *   into what a PDF measures in. §9.2.4 NOTE 3 is about exactly this step:
 *   "TrueType stores widths in units of 1024 or 2048 to an Em … however the
 *   Widths array stores widths in units of 1000 to an Em."
 * - [indexToLocFormat] says whether the `loca` table holds `Offset16` or
 *   `Offset32`, without which the glyph outlines cannot be found at all. Nothing
 *   in `loca` itself records this.
 *
 * The bounding box ([xMin], [yMin], [xMax], [yMax]) is the other reason: it is
 * what a PDF font descriptor's `FontBBox` entry (§9.8.1, Table 120) is, in the
 * font's own units.
 *
 * @property unitsPerEm "Set to a value from 16 to 16384. Any value in this range
 *   is valid." Checked, because everything this library computes about a
 *   supplied font is divided by it.
 * @property flags the flags field; bit 1 is "Left sidebearing point at x=0",
 *   which is what decides whether `hmtx` left side bearings and `glyf` xMin
 *   values must agree.
 * @property macStyle bit 0 bold, bit 1 italic — what a font descriptor's
 *   `Flags` entry restates.
 * @property indexToLocFormat "0 for short offsets (Offset16), 1 for long
 *   (Offset32)."
 */
internal class HeadTable(
    val majorVersion: Int,
    val minorVersion: Int,
    val flags: Int,
    val unitsPerEm: Int,
    val xMin: Int,
    val yMin: Int,
    val xMax: Int,
    val yMax: Int,
    val macStyle: Int,
    val indexToLocFormat: Int,
) {
    /** Whether `loca` holds `Offset32` entries rather than `Offset16` ones. */
    val hasLongLocaOffsets: Boolean get() = indexToLocFormat == LONG_LOCA_OFFSETS

    override fun toString(): String = "head(unitsPerEm=$unitsPerEm, bbox=[$xMin $yMin $xMax $yMax])"

    companion object {
        /** "magicNumber | Set to 0x5F0F3CF5." */
        const val MAGIC_NUMBER: Long = 0x5F0F3CF5L

        /** "Set to a value from 16 to 16384." */
        const val MIN_UNITS_PER_EM: Int = 16

        /** "Set to a value from 16 to 16384." */
        const val MAX_UNITS_PER_EM: Int = 16384

        private const val SHORT_LOCA_OFFSETS: Int = 0
        private const val LONG_LOCA_OFFSETS: Int = 1

        /**
         * Reads the table [reader] covers.
         *
         * The fields nothing here keeps are skipped by their width rather than
         * jumped over by a computed offset, so this function reads as the
         * specification's field table does, top to bottom.
         */
        fun parse(reader: BigEndianReader): HeadTable {
            reader.seek(0)
            val majorVersion = reader.uint16()
            val minorVersion = reader.uint16()
            require(majorVersion == 1) {
                "the 'head' table is version $majorVersion.$minorVersion; the specification sets its " +
                    "major version to 1, and a major version this library does not know is a table it " +
                    "cannot make assumptions about the layout of"
            }
            reader.skip(4) // fontRevision — "Set by font manufacturer"
            reader.skip(4) // checksumAdjustment — see TableDirectory on checksums
            val magicNumber = reader.uint32()
            require(magicNumber == MAGIC_NUMBER) {
                "the 'head' table has the magicNumber ${hex(magicNumber)} rather than " +
                    "${hex(MAGIC_NUMBER)}, so these bytes are not a font header"
            }
            val flags = reader.uint16()
            val unitsPerEm = reader.uint16()
            require(unitsPerEm in MIN_UNITS_PER_EM..MAX_UNITS_PER_EM) {
                "the 'head' table gives unitsPerEm as $unitsPerEm, outside the " +
                    "$MIN_UNITS_PER_EM..$MAX_UNITS_PER_EM the specification allows; it is the divisor " +
                    "every measurement of this font is scaled by, so there is no reading of it to guess"
            }
            reader.skip(8) // created — LONGDATETIME
            reader.skip(8) // modified — LONGDATETIME
            val xMin = reader.int16()
            val yMin = reader.int16()
            val xMax = reader.int16()
            val yMax = reader.int16()
            val macStyle = reader.uint16()
            reader.skip(2) // lowestRecPPEM — "Smallest readable size in pixels"
            reader.skip(2) // fontDirectionHint — "Deprecated (Set to 2)"
            val indexToLocFormat = reader.int16()
            require(indexToLocFormat == SHORT_LOCA_OFFSETS || indexToLocFormat == LONG_LOCA_OFFSETS) {
                "the 'head' table gives indexToLocFormat as $indexToLocFormat; it is " +
                    "$SHORT_LOCA_OFFSETS for short 'loca' offsets or $LONG_LOCA_OFFSETS for long ones, " +
                    "and nothing else says which the font used"
            }
            return HeadTable(
                majorVersion = majorVersion,
                minorVersion = minorVersion,
                flags = flags,
                unitsPerEm = unitsPerEm,
                xMin = xMin,
                yMin = yMin,
                xMax = xMax,
                yMax = yMax,
                macStyle = macStyle,
                indexToLocFormat = indexToLocFormat,
            )
        }
    }
}
