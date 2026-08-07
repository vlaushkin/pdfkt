/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.font.truetype

// OpenType 1.9.1 §"maxp — Maximum Profile table", §"OpenType font file" —
// "Table Version Numbers" (Version16Dot16)

/**
 * `maxp` — "This table establishes the memory requirements for this font."
 *
 * One field of it is load-bearing here. [numGlyphs] is the size of every array
 * indexed by glyph id: the left side bearings of `hmtx`, the offsets of `loca`,
 * the glyph ids a `cmap` may legitimately produce. It is also the bound a
 * subset is built against.
 *
 * The rest of version 1.0 — maximum points, contours, stack depth — describes
 * what a TrueType interpreter has to allocate, and this library interprets
 * nothing.
 *
 * @property version the `Version16Dot16` the table opens with: "a packed value:
 *   the upper 16 bits comprise a major version number, and the lower 16 bits, a
 *   minor version". [VERSION_0_5] for a font with CFF outlines, [VERSION_1_0]
 *   for one with TrueType outlines.
 */
internal class MaxpTable(val version: Long, val numGlyphs: Int) {
    /**
     * Whether the table is the version a font with TrueType outlines is required
     * to use — "Fonts with TrueType outlines must use Version 1.0 of this table,
     * where all data is required."
     */
    val describesTrueTypeOutlines: Boolean get() = version == VERSION_1_0

    override fun toString(): String = "maxp(version=${hex(version)}, numGlyphs=$numGlyphs)"

    companion object {
        /** "0x00005000 for version 0.5" — "Fonts with CFF or CFF2 outlines must use Version 0.5". */
        const val VERSION_0_5: Long = 0x00005000L

        /** "0x00010000 for version 1.0." — "Fonts with TrueType outlines must use Version 1.0". */
        const val VERSION_1_0: Long = 0x00010000L

        /** Reads the table [reader] covers. */
        fun parse(reader: BigEndianReader): MaxpTable {
            reader.seek(0)
            val version = reader.uint32()
            require(version == VERSION_0_5 || version == VERSION_1_0) {
                "the 'maxp' table is version ${hex(version)}; the specification defines " +
                    "${hex(VERSION_0_5)} (0.5) and ${hex(VERSION_1_0)} (1.0), and the number of glyphs " +
                    "is not a field to read out of a layout this library does not know"
            }
            val numGlyphs = reader.uint16()
            require(numGlyphs >= 1) {
                "the 'maxp' table gives numGlyphs as 0; every font has at least the glyph at index 0, " +
                    "\"a special glyph representing a missing character, commonly known as .notdef\""
            }
            return MaxpTable(version, numGlyphs)
        }
    }
}
