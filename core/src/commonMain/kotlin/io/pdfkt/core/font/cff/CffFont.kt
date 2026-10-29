/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.font.cff

import io.pdfkt.core.font.truetype.BigEndianReader

// Adobe Technical Note #5176 "The Compact Font Format Specification" §6
// "Header", §8 "Top DICT INDEX", §15 "Private DICT Data", §16 "Local/Global
// Subrs INDEXes", §19 "FDSelect", §18 "CID-keyed Fonts"; OpenType 1.9.1
// §"CFF2", "Header", "Top DICT", "Font DICT INDEX", "FDSelect", "VariationStore";
// §"OpenType Font Variations Common Table Formats", "Item variation store".

/**
 * One Font DICT's worth of what a charstring is interpreted against: its
 * Private DICT and the local subroutines that DICT names.
 *
 * A name-keyed CFF font has one of these, made from its Top DICT's Private; a
 * CID-keyed font and a CFF2 font have one per entry of their FDArray — "each
 * … contains a Private DICT … and local subroutines".
 */
internal class CffFontDict(
    /** The Private DICT, blends resolved to the default instance. */
    val private: CffDict,
    val localSubrs: CffIndex,
    /** The Font DICT's own FontMatrix, if it states one. */
    val fontMatrix: CffDictEntry?,
)

/**
 * The outlines of an OpenType font with CFF or CFF2 data: a `CFF ` or `CFF2`
 * table read as far as cutting a subset out of it requires.
 *
 * Both formats are read into one shape — charstrings by glyph index, global
 * subroutines, and per glyph the Font DICT its charstring is interpreted under
 * — because both are written out as one: a CID-keyed CFF font (see [CffSubset]).
 *
 * @property isCff2 whether the table is `CFF2`, whose charstrings carry
 *   `blend` and `vsindex` and need no `endchar`.
 */
internal class CffFont private constructor(
    val isCff2: Boolean,
    val charStrings: CffIndex,
    val globalSubrs: CffIndex,
    val fontDicts: List<CffFontDict>,
    private val fdSelect: (Int) -> Int,
    val fontMatrix: CffDictEntry?,
    /** For CFF2, the regions each item variation data of the VariationStore blends over. */
    val regionCounts: List<Int>,
) {
    val numGlyphs: Int get() = charStrings.count

    /** The Font DICT the charstring of [glyphId] is interpreted under. */
    fun fontDictOf(glyphId: Int): CffFontDict = fontDicts[fdSelect(glyphId)]

    fun fdIndexOf(glyphId: Int): Int = fdSelect(glyphId)

    /** The regions [vsindex] blends over. */
    fun regionCount(vsindex: Int): Int {
        require(vsindex in regionCounts.indices) {
            "a vsindex of $vsindex; the VariationStore holds ${regionCounts.size} item variation data"
        }
        return regionCounts[vsindex]
    }

    companion object {
        /** Reads the `CFF ` table [data] covers. */
        fun parseCff(data: BigEndianReader): CffFont {
            data.seek(0)
            val major = data.uint8()
            require(major == 1) { "the 'CFF ' table is version $major; version 1 is the one a 'CFF ' table holds" }
            data.uint8() // minor
            val headerSize = data.uint8()
            val names = CffIndex.read(data, headerSize, false, "the Name INDEX")
            require(names.count == 1) {
                "the 'CFF ' table holds ${names.count} fonts; \"the Name INDEX in the CFF data must contain " +
                    "only one entry\" in an OpenType font"
            }
            val topDicts = CffIndex.read(data, names.end, false, "the Top DICT INDEX")
            val strings = CffIndex.read(data, topDicts.end, false, "the String INDEX")
            val globalSubrs = CffIndex.read(data, strings.end, false, "the Global Subr INDEX")
            val top = CffDict.read(topDicts[0])
            require((top.int(CffDict.CHARSTRING_TYPE) ?: 2) == 2) {
                "the font's charstrings are Type ${top.int(CffDict.CHARSTRING_TYPE)}; an OpenType font's are Type 2"
            }
            val charStrings = CffIndex.read(
                data,
                requireNotNull(top.int(CffDict.CHAR_STRINGS)) { "the Top DICT has no CharStrings" },
                false,
                "the CharStrings INDEX",
            )
            val fontDicts: List<CffFontDict>
            val fdSelect: (Int) -> Int
            if (top.entry(CffDict.ROS) != null) {
                val fdArray = CffIndex.read(
                    data,
                    requireNotNull(top.int(CffDict.FD_ARRAY)) { "a CID-keyed font with no FDArray" },
                    false,
                    "the Font DICT INDEX",
                )
                fontDicts = fdArray.items.map { fontDictOf(data, CffDict.read(it), wideCount = false) { 0 } }
                fdSelect = fdSelectOf(
                    data,
                    requireNotNull(top.int(CffDict.FD_SELECT)) { "a CID-keyed font with no FDSelect" },
                    charStrings.count,
                    fontDicts.size,
                )
            } else {
                fontDicts =
                    listOf(
                        fontDictOf(data, top, wideCount = false) {
                            0
                        }.let { CffFontDict(it.private, it.localSubrs, null) },
                    )
                fdSelect = { 0 }
            }
            return CffFont(
                isCff2 = false,
                charStrings = charStrings,
                globalSubrs = globalSubrs,
                fontDicts = fontDicts,
                fdSelect = fdSelect,
                fontMatrix = top.entry(CffDict.FONT_MATRIX),
                regionCounts = emptyList(),
            )
        }

        /**
         * Reads the `CFF2` table [data] covers. "The CFF2 header … is followed
         * by the Top DICT data … followed by the Global Subr INDEX", and there
         * is no Name INDEX or String INDEX: "CFF2 does not use … strings".
         */
        fun parseCff2(data: BigEndianReader): CffFont {
            data.seek(0)
            val major = data.uint8()
            require(major == 2) { "the 'CFF2' table is version $major" }
            data.uint8() // minor
            val headerSize = data.uint8()
            val topDictLength = data.uint16()
            val top = CffDict.read(data.sliceAt(headerSize, topDictLength, "the CFF2 Top DICT"))
            val globalSubrs = CffIndex.read(data, headerSize + topDictLength, true, "the Global Subr INDEX")
            val charStrings = CffIndex.read(
                data,
                requireNotNull(top.int(CffDict.CHAR_STRINGS)) { "the CFF2 Top DICT has no CharStrings" },
                true,
                "the CharStrings INDEX",
            )
            val regionCounts = top.int(CffDict.VSTORE)?.let { regionCountsOf(data, it) } ?: emptyList()
            val regionCount = { vsindex: Int ->
                require(vsindex in regionCounts.indices) {
                    "a vsindex of $vsindex; the VariationStore holds ${regionCounts.size} item variation data"
                }
                regionCounts[vsindex]
            }
            val fdArray = CffIndex.read(
                data,
                requireNotNull(top.int(CffDict.FD_ARRAY)) { "the CFF2 Top DICT has no FDArray, which it requires" },
                true,
                "the Font DICT INDEX",
            )
            val fontDicts = fdArray.items.map { fontDictOf(data, CffDict.read(it), wideCount = true, regionCount) }
            // "FDSelect … is optional when the FDArray has only one Font DICT."
            val fdSelect = top.int(CffDict.FD_SELECT)?.let { fdSelectOf(data, it, charStrings.count, fontDicts.size) }
                ?: { 0 }
            return CffFont(
                isCff2 = true,
                charStrings = charStrings,
                globalSubrs = globalSubrs,
                fontDicts = fontDicts,
                fdSelect = fdSelect,
                fontMatrix = top.entry(CffDict.FONT_MATRIX),
                regionCounts = regionCounts,
            )
        }

        /**
         * The Private DICT a Font DICT (or a name-keyed Top DICT) points at —
         * "Private: size, offset" — and the local subroutines it points at in
         * turn, whose offset "is relative to the beginning of the Private DICT
         * data".
         */
        private fun fontDictOf(
            data: BigEndianReader,
            dict: CffDict,
            wideCount: Boolean,
            regionCount: (Int) -> Int,
        ): CffFontDict {
            val (size, offset) = requireNotNull(dict.ints(CffDict.PRIVATE)) { "a Font DICT with no Private" }
            val private = CffDict.read(data.sliceAt(offset, size, "a Private DICT"), regionCount)
            val subrs = private.int(CffDict.SUBRS)?.let {
                CffIndex.read(data, offset + it, wideCount, "the Local Subr INDEX")
            } ?: CffIndex(emptyList(), 0)
            return CffFontDict(private, subrs, dict.entry(CffDict.FONT_MATRIX))
        }

        /** FDSelect formats 0 and 3, and CFF2's format 4. */
        private fun fdSelectOf(data: BigEndianReader, offset: Int, numGlyphs: Int, fdCount: Int): (Int) -> Int {
            data.seek(offset)
            val fds = IntArray(numGlyphs)
            when (val format = data.uint8()) {
                0 -> for (glyph in 0 until numGlyphs) fds[glyph] = data.uint8()
                3, 4 -> {
                    val wide = format == 4
                    val ranges = if (wide) data.uint32().toInt() else data.uint16()
                    var first = if (wide) data.uint32().toInt() else data.uint16()
                    repeat(ranges) {
                        val fd = if (wide) data.uint16() else data.uint8()
                        val next = if (wide) data.uint32().toInt() else data.uint16()
                        for (glyph in first until minOf(next, numGlyphs)) fds[glyph] = fd
                        first = next
                    }
                }
                else -> throw IllegalArgumentException("an FDSelect of format $format; the formats are 0, 3 and 4")
            }
            for (glyph in 0 until numGlyphs) {
                require(fds[glyph] < fdCount) {
                    "the FDSelect gives glyph $glyph Font DICT ${fds[glyph]}; the FDArray holds $fdCount"
                }
            }
            return { glyph -> fds[glyph] }
        }

        /**
         * The region count of each item variation data in the VariationStore:
         * "the VariationStore data … is comprised of a uint16 length field
         * followed by an ItemVariationStore". All this library needs of it is
         * k, the number of deltas each blended value is followed by.
         */
        private fun regionCountsOf(data: BigEndianReader, offset: Int): List<Int> {
            val store = offset + 2
            data.seek(store)
            data.uint16() // format
            data.uint32() // variationRegionListOffset
            val count = data.uint16()
            val offsets = (0 until count).map { data.uint32().toInt() }
            return offsets.map { itemData ->
                data.seek(store + itemData)
                data.uint16() // itemCount
                data.uint16() // wordDeltaCount
                data.uint16() // regionIndexCount
            }
        }
    }
}
