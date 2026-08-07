/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.font.truetype

// OpenType 1.9.1 §"hhea — Horizontal header table"
// ISO 32000-2:2020 §9.8.1 "Font descriptors — General" (Table 120)

/**
 * `hhea` — "This table contains information for horizontal layout."
 *
 * It answers two of the three questions L4 asks a
 * font — how far a line of it reaches above the baseline and how far below —
 * and it says how many records the `hmtx` table holds, without which that table
 * cannot be parsed at all.
 *
 * @property ascender "Typographic ascent", in font design units. Table 120's
 *   `Ascent`.
 * @property descender "Typographic descent". Negative in every font that
 *   follows the convention, which is also what Table 120 requires of `Descent`:
 *   "The value shall be a negative number."
 * @property lineGap "Typographic line gap" — Table 120's `Leading`, the space
 *   left between one line's descent and the next line's ascent.
 * @property advanceWidthMax "Maximum advance width value in 'hmtx' table" —
 *   what Table 120 calls `MaxWidth`, and a cross-check on a parse of `hmtx`.
 * @property numberOfHMetrics "Number of hMetric entries in 'hmtx' table".
 */
internal class HheaTable(
    val majorVersion: Int,
    val minorVersion: Int,
    val ascender: Int,
    val descender: Int,
    val lineGap: Int,
    val advanceWidthMax: Int,
    val numberOfHMetrics: Int,
) {
    override fun toString(): String = "hhea(ascender=$ascender, descender=$descender, lineGap=$lineGap, " +
        "numberOfHMetrics=$numberOfHMetrics)"

    companion object {
        /** "metricDataFormat | 0 for current format." */
        private const val CURRENT_METRIC_DATA_FORMAT: Int = 0

        /** Reads the table [reader] covers. */
        fun parse(reader: BigEndianReader): HheaTable {
            reader.seek(0)
            val majorVersion = reader.uint16()
            val minorVersion = reader.uint16()
            require(majorVersion == 1) {
                "the 'hhea' table is version $majorVersion.$minorVersion; the specification sets its " +
                    "major version to 1, and a major version this library does not know is a table it " +
                    "cannot make assumptions about the layout of"
            }
            val ascender = reader.int16() // FWORD
            val descender = reader.int16() // FWORD
            val lineGap = reader.int16() // FWORD
            val advanceWidthMax = reader.uint16() // UFWORD
            reader.skip(2) // minLeftSideBearing
            reader.skip(2) // minRightSideBearing
            reader.skip(2) // xMaxExtent
            reader.skip(2) // caretSlopeRise
            reader.skip(2) // caretSlopeRun
            reader.skip(2) // caretOffset
            reader.skip(8) // four reserved int16 fields — "All reserved areas must be set to 0"
            val metricDataFormat = reader.int16()
            require(metricDataFormat == CURRENT_METRIC_DATA_FORMAT) {
                "the 'hhea' table gives metricDataFormat as $metricDataFormat; the specification defines " +
                    "only $CURRENT_METRIC_DATA_FORMAT, \"the current format\", so the layout of 'hmtx' " +
                    "in this font is not one this library knows"
            }
            val numberOfHMetrics = reader.uint16()
            require(numberOfHMetrics >= 1) {
                "the 'hhea' table gives numberOfHMetrics as $numberOfHMetrics; the advance width of every " +
                    "glyph past the last record is that record's, so a font without one record has no " +
                    "advance width for any glyph"
            }
            return HheaTable(
                majorVersion = majorVersion,
                minorVersion = minorVersion,
                ascender = ascender,
                descender = descender,
                lineGap = lineGap,
                advanceWidthMax = advanceWidthMax,
                numberOfHMetrics = numberOfHMetrics,
            )
        }
    }
}
