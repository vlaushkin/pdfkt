/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.font.truetype

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// OpenType 1.9.1 §"OpenType font file" — "Table Directory",
// "Calculating Checksums"; §"glyf", §"loca", §"hmtx", §"head", §"maxp"
// ISO 32000-2:2020 §9.9 "Embedded font programs" (Table 124), §9.9.2 "Font subsets"

/**
 * The subsetter, read back through the parser it writes for.
 *
 * Every assertion here is about the bytes that come out: which glyphs the subset
 * has, what the composite among them is drawn from once everything has been
 * renumbered, what the table directory says, and whether the checksums add up to
 * what the clause says they must. The checksum arithmetic is written out a
 * second time at the bottom of this file, from the clause, so that a sum is
 * never checked against itself.
 *
 * The font it all runs against has six glyphs, which is the smallest number that
 * holds every case the subsetter has to deal with:
 *
 * | Glyph | What it is | Why it is there |
 * |---|---|---|
 * | 0 | `.notdef`, no outline | "The glyph name .notdef shall be defined in the font subset" |
 * | 1 | simple | a component of glyph 3, pulled in by the closure |
 * | 2 | simple | asked for directly |
 * | 3 | composite of 1 and 5 | the renumbering case |
 * | 4 | simple | never asked for, never a component: the glyph that must be dropped |
 * | 5 | simple | a component of glyph 3, and the one that proves the closure is not one-sided |
 */
class TrueTypeSubsetTest {
    @Test
    fun `a subset keeps the glyphs asked for and the ones they are drawn from`() {
        val font = TrueTypeFont.parse(sourceFontBytes())

        val subset = TrueTypeSubset.of(font, listOf(3))

        // 0 for .notdef, 3 because it was asked for, 1 and 5 because 3 is drawn
        // out of them. 2 and 4 were not asked for and are not components.
        assertEquals(listOf(0, 1, 3, 5), subset.glyphIds)
        assertEquals(4, subset.numGlyphs)
        assertEquals(4, TrueTypeFont.parse(subset.bytes).numGlyphs)
    }

    @Test
    fun `the components of a composite are renumbered to their place in the subset`() {
        val font = TrueTypeFont.parse(sourceFontBytes())

        val subset = TrueTypeSubset.of(font, listOf(3))
        val glyf = assertNotNull(TrueTypeFont.parse(subset.bytes).glyf)

        // Glyph 3 of the font is glyph 2 of the subset, and it is drawn out of
        // the glyphs that were 1 and 5, now 1 and 3.
        assertEquals(2, subset.subsetGlyphIdOf(3))
        val composite = assertIs<Glyph.Composite>(glyf.glyphOf(2))
        assertEquals(listOf(subset.subsetGlyphIdOf(1), subset.subsetGlyphIdOf(5)), composite.componentGlyphIds)
        assertEquals(listOf(1, 3), composite.componentGlyphIds)
        // The closure of the renumbered composite is the whole subset bar the
        // glyph nothing referenced.
        assertEquals(setOf(1, 2, 3), glyf.componentClosureOf(listOf(2)))
    }

    @Test
    fun `the rest of a component record survives the renumbering`() {
        // Only the two bytes of glyphIndex are rewritten: the flags that say how
        // long the record is and the arguments that place the component are the
        // font's own bytes.
        val font = TrueTypeFont.parse(sourceFontBytes())

        val subset = TrueTypeSubset.of(font, listOf(3))
        val glyf = assertNotNull(TrueTypeFont.parse(subset.bytes).glyf)
        val components = assertIs<Glyph.Composite>(glyf.glyphOf(2)).components

        assertEquals(listOf(5, 7), components.map { it.argument1 })
        assertEquals(listOf(6, 8), components.map { it.argument2 })
        assertTrue(components.first().hasMoreComponents, "the first record of two does not continue")
        assertTrue(components.first().argumentsAreXyValues)
        assertTrue(!components.last().hasMoreComponents, "the record sequence does not end")
    }

    @Test
    fun `notdef is kept and numbered zero even when nothing asked for it`() {
        // "The glyph name .notdef shall be defined in the font subset."
        val font = TrueTypeFont.parse(sourceFontBytes())

        val subset = TrueTypeSubset.of(font, listOf(2))

        assertEquals(listOf(0, 2), subset.glyphIds)
        assertEquals(0, subset.subsetGlyphIdOf(0))
        // Glyph 0 of this font has no outline, and that survives: "if a glyph has
        // no outline or instructions, then loca[n] = loca[n+1]".
        assertEquals(Glyph.Blank, assertNotNull(TrueTypeFont.parse(subset.bytes).glyf).glyphOf(0))
    }

    @Test
    fun `every glyph kept keeps its advance width and left side bearing`() {
        val font = TrueTypeFont.parse(sourceFontBytes())

        val subset = TrueTypeSubset.of(font, listOf(2, 3, 4))
        val embedded = TrueTypeFont.parse(subset.bytes)

        assertEquals(listOf(0, 1, 2, 3, 4, 5), subset.glyphIds)
        for (glyphId in subset.glyphIds) {
            val subsetId = subset.subsetGlyphIdOf(glyphId)
            assertEquals(font.advanceWidthOf(glyphId), embedded.advanceWidthOf(subsetId), "glyph $glyphId")
            assertEquals(font.leftSideBearingOf(glyphId), embedded.leftSideBearingOf(subsetId), "glyph $glyphId")
        }
        assertEquals(6, embedded.hhea.numberOfHMetrics)
    }

    @Test
    fun `a glyph description is copied out of the font byte for byte`() {
        val font = TrueTypeFont.parse(sourceFontBytes())
        val original = assertNotNull(assertNotNull(font.glyf).readerOrNullOf(2)).toByteArray()

        val subset = TrueTypeSubset.of(font, listOf(2))
        val glyf = assertNotNull(TrueTypeFont.parse(subset.bytes).glyf)
        val copied = assertNotNull(glyf.readerOrNullOf(subset.subsetGlyphIdOf(2))).toByteArray()

        // The description may be followed by padding to a four-byte boundary, so
        // that every 'loca' offset stays even; the description itself is the
        // font's own contours, flags and instructions, unread and unrewritten.
        assertTrue(copied.size >= original.size, "a description of ${copied.size} bytes for ${original.size}")
        assertTrue(copied.size - original.size < 4, "${copied.size - original.size} bytes of padding")
        assertContentEquals(original, copied.copyOf(original.size))
    }

    @Test
    fun `the subset carries the tables an embedded font program has to have and no others`() {
        // "The font program shall include these tables: 'glyf', 'head', 'hhea',
        // 'hmtx', 'loca', and 'maxp'. The 'cvt ' (notice the trailing SPACE),
        // 'fpgm', and 'prep' tables shall also be included if they are required by
        // the font instructions."
        val font = TrueTypeFont.parse(
            sourceFontBytes(
                extraTables = listOf(
                    "cvt " holding bigEndian { i16(30).i16(-40) },
                    "fpgm" holding bigEndian { u8(0xB0).u8(0x01) },
                    "prep" holding bigEndian { u8(0xB8).u8(0x02) },
                ),
            ),
        )

        val subset = TrueTypeSubset.of(font, listOf(3))
        val directory = directoryOf(subset.bytes)

        assertEquals(
            listOf("cvt ", "fpgm", "glyf", "head", "hhea", "hmtx", "loca", "maxp", "prep"),
            directory.tables.map { it.tag },
        )
        for (tag in TrueTypeSubset.INSTRUCTION_TABLES) {
            assertContentEquals(
                font.directory.readerOf(tag).toByteArray(),
                directory.readerOf(tag).toByteArray(),
                "the '$tag' table was not carried across unchanged",
            )
        }
    }

    @Test
    fun `a font whose instructions need no tables gets a subset without them`() {
        val font = TrueTypeFont.parse(sourceFontBytes())

        val directory = directoryOf(TrueTypeSubset.of(font, listOf(3)).bytes)

        assertEquals(listOf("glyf", "head", "hhea", "hmtx", "loca", "maxp"), directory.tables.map { it.tag })
    }

    @Test
    fun `the subset has no character map because an embedded program is addressed by glyph`() {
        // 'cmap' is not among the tables Table 124 requires of a FontFile2, and a
        // subset that renumbered its glyphs cannot carry the font's own: every id
        // in it would point at the wrong glyph.
        val font = TrueTypeFont.parse(sourceFontBytes())
        assertNotNull(font.cmap, "the font this subset is cut from has no character map to drop")

        val embedded = TrueTypeFont.parse(TrueTypeSubset.of(font, listOf(3)).bytes)

        assertNull(embedded.cmap)
        val refused = assertFailsWith<IllegalArgumentException> { embedded.glyphIdOf('A'.code) }
        assertTrue("'cmap'" in refused.message.orEmpty(), refused.message)
    }

    @Test
    fun `the records of the table directory are sorted by tag and aligned to four bytes`() {
        // "The records in the array must be sorted in ascending order by tag" and
        // "All tables must begin on four-byte boundaries".
        val font = TrueTypeFont.parse(sourceFontBytes(extraTables = listOf("cvt " holding bigEndian { i16(7) })))

        val subset = TrueTypeSubset.of(font, listOf(2, 3))
        val directory = directoryOf(subset.bytes)

        assertEquals(directory.tables.map { it.tag }.sorted(), directory.tables.map { it.tag })
        for (record in directory.tables) {
            assertEquals(0, record.offset % 4, "the '${record.tag}' table begins at ${record.offset}")
            assertTrue(record.offset + record.length <= subset.bytes.size, "the '${record.tag}' table")
        }
        // "The length of each table should be recorded in the table record with
        // the actual length of data, not the padded length": the 'cvt ' table of
        // this font is two bytes and is recorded as two.
        assertEquals(2, directory.tables.first { it.tag == "cvt " }.length)
    }

    @Test
    fun `the binary search fields of the directory describe the number of tables`() {
        // searchRange is "(2**floor(log2(numTables))) * 16", entrySelector is
        // "log2(searchRange/16)" and rangeShift is "(numTables * 16) - searchRange".
        // The parser derives them rather than reading them, so this is the one
        // place they are checked at all — and a font this library writes has to
        // "provide valid values for these fields to maintain compatibility with
        // all existing implementations".
        val bare = TrueTypeSubset.of(TrueTypeFont.parse(sourceFontBytes()), listOf(3))
        val withInstructions = TrueTypeSubset.of(
            TrueTypeFont.parse(
                sourceFontBytes(
                    extraTables = listOf(
                        "cvt " holding bigEndian { i16(7) },
                        "fpgm" holding bigEndian { u8(0xB0) },
                        "prep" holding bigEndian { u8(0xB8) },
                    ),
                ),
            ),
            listOf(3),
        )

        assertEquals(listOf(6, 64, 2, 32), searchFieldsOf(bare.bytes))
        assertEquals(listOf(9, 128, 3, 16), searchFieldsOf(withInstructions.bytes))
    }

    @Test
    fun `every table record carries the checksum of the table it points at`() {
        // "Table checksums are the unsigned sum of the uint32 units of a given
        // table." Every table but one: "The 'head' table is a special case in
        // checksum calculations, as it includes a checksumAdjustment field that is
        // calculated and written after the table's checksum is calculated and
        // written into the table directory entry, necessarily invalidating that
        // checksum value." What its record holds instead is the next test.
        val font = TrueTypeFont.parse(sourceFontBytes(extraTables = listOf("cvt " holding bigEndian { i16(7) })))

        val subset = TrueTypeSubset.of(font, listOf(2, 3))

        for (record in directoryOf(subset.bytes).tables.filter { it.tag != "head" }) {
            assertEquals(
                checksumFromTheClause(subset.bytes, record.offset, record.length),
                record.checksum,
                "the recorded checksum of the '${record.tag}' table",
            )
        }
    }

    @Test
    fun `the checksum of the whole subset comes to the value the head table adjusts it to`() {
        // "Calculate the checksum for the entire font. Subtract that value from
        // 0xB1B0AFBA. Store the result in the 'head' table checksumAdjustment
        // field." Storing the difference is what makes the sum over the finished
        // font come to 0xB1B0AFBA, which is the whole point of the field.
        val font = TrueTypeFont.parse(sourceFontBytes())

        val subset = TrueTypeSubset.of(font, listOf(3))

        assertEquals(0xB1B0AFBAL, checksumFromTheClause(subset.bytes, 0, subset.bytes.size))
    }

    @Test
    fun `the head record checksum is the one a verifier recomputes with a zero adjustment`() {
        // "An application attempting to verify that the 'head' table has not
        // changed should calculate the checksum for that table assuming that the
        // checksumAdjustment value is zero, rather than the actual value in the
        // font."
        val font = TrueTypeFont.parse(sourceFontBytes())

        val subset = TrueTypeSubset.of(font, listOf(3))
        val directory = directoryOf(subset.bytes)
        val record = directory.tables.first { it.tag == "head" }
        val head = directory.readerOf("head").toByteArray()

        assertTrue(head.copyOfRange(8, 12).any { it != 0.toByte() }, "the checksumAdjustment was left at zero")
        head.putUint32(8, 0)
        assertEquals(checksumFromTheClause(head, 0, head.size), record.checksum)
    }

    @Test
    fun `the loca format of the subset is the one the rebuilt table is in`() {
        // The source font is in the long format and the subset is small enough for
        // the short one: "The format is specified by the indexToLocFormat entry in
        // the 'head' table", and it describes the table that is there, not the one
        // it was cut from.
        val font = TrueTypeFont.parse(sourceFontBytes(longOffsets = true))
        assertTrue(font.head.hasLongLocaOffsets, "the source font is not in the long format")

        val embedded = TrueTypeFont.parse(TrueTypeSubset.of(font, listOf(3)).bytes)

        assertEquals(0, embedded.head.indexToLocFormat)
        assertEquals(4, embedded.numGlyphs)
        assertIs<Glyph.Composite>(assertNotNull(embedded.glyf).glyphOf(2))
        // Everything else of 'head' is the font's own.
        assertEquals(font.unitsPerEm, embedded.unitsPerEm)
        assertEquals(listOf(font.head.xMin, font.head.yMin, font.head.xMax, font.head.yMax), embedded.head.box())
    }

    @Test
    fun `the maxima of the source font are carried across as the bounds they are`() {
        val font = TrueTypeFont.parse(sourceFontBytes())

        val embedded = TrueTypeFont.parse(TrueTypeSubset.of(font, listOf(2)).bytes)

        assertEquals(2, embedded.numGlyphs)
        assertEquals(font.maxp.version, embedded.maxp.version)
        assertEquals(font.hhea.ascender, embedded.hhea.ascender)
        assertEquals(font.hhea.descender, embedded.hhea.descender)
        assertEquals(font.hhea.lineGap, embedded.hhea.lineGap)
    }

    @Test
    fun `a subset of fewer glyphs is a smaller font program`() {
        val font = TrueTypeFont.parse(sourceFontBytes())

        val one = TrueTypeSubset.of(font, listOf(2))
        val all = TrueTypeSubset.of(font, 0 until 6)

        assertEquals(2, one.numGlyphs)
        assertEquals(6, all.numGlyphs)
        assertTrue(one.bytes.size < all.bytes.size, "${one.bytes.size} bytes against ${all.bytes.size}")
    }

    @Test
    fun `a glyph the subset does not hold is refused rather than answered with notdef`() {
        val font = TrueTypeFont.parse(sourceFontBytes())

        val subset = TrueTypeSubset.of(font, listOf(2))

        assertTrue(2 in subset)
        assertTrue(4 !in subset)
        val refused = assertFailsWith<IllegalArgumentException> { subset.subsetGlyphIdOf(4) }
        assertTrue("4" in refused.message.orEmpty(), refused.message)
    }

    @Test
    fun `a glyph the font does not have is refused`() {
        val font = TrueTypeFont.parse(sourceFontBytes())

        val refused = assertFailsWith<IllegalArgumentException> { TrueTypeSubset.of(font, listOf(6)) }

        assertTrue("6" in refused.message.orEmpty(), refused.message)
    }

    @Test
    fun `a font with no glyph descriptions in this format cannot be subset`() {
        // A font with CFF outlines carries its glyphs in a 'CFF ' table, and
        // rebuilding 'glyf' and 'loca' for it would produce a font of no glyphs.
        val font = TrueTypeFont.parse(minimalFontBytes())
        assertNull(font.glyf)

        val refused = assertFailsWith<IllegalArgumentException> { TrueTypeSubset.of(font, listOf(1)) }

        assertTrue("'glyf'" in refused.message.orEmpty(), refused.message)
    }

    @Test
    fun `a font whose maxp does not describe TrueType outlines cannot be subset`() {
        // "Fonts with TrueType outlines must use Version 1.0 of this table, where
        // all data is required." The subset carries that table's limits across,
        // and version 0.5 has none to carry.
        val font = TrueTypeFont.parse(sourceFontBytes(maxp = maxpTableBytes(numGlyphs = 6, version = 0x00005000L)))

        val refused = assertFailsWith<IllegalArgumentException> { TrueTypeSubset.of(font, listOf(3)) }

        assertTrue("'maxp'" in refused.message.orEmpty(), refused.message)
    }

    /**
     * The six glyphs of the table in this class's documentation. Every simple one
     * has a different number of points, so that a description copied from the
     * wrong offset would be a description of the wrong length.
     */
    private val outlines: List<ByteArray> = listOf(
        ByteArray(0),
        simpleGlyphBytes(contours = listOf(listOf(GlyphPoint(0, 0), GlyphPoint(40, 80)))),
        simpleGlyphBytes(
            contours = listOf(listOf(GlyphPoint(0, 0), GlyphPoint(10, 20), GlyphPoint(30, 40))),
            instructions = byteArrayOf(0x2B, 0x59),
        ),
        compositeGlyphBytes(
            components = listOf(
                ComponentRecord(glyphId = 1, argument1 = 5, argument2 = 6),
                ComponentRecord(glyphId = 5, argument1 = 7, argument2 = 8),
            ),
        ),
        simpleGlyphBytes(
            contours = listOf(listOf(GlyphPoint(1, 1), GlyphPoint(2, 2), GlyphPoint(3, 3), GlyphPoint(4, 4))),
        ),
        simpleGlyphBytes(contours = listOf(listOf(GlyphPoint(3, 3), GlyphPoint(4, 4), GlyphPoint(5, 5)))),
    )

    /** Six distinct advance widths and left side bearings, one pair per glyph. */
    private val metrics: List<Pair<Int, Int>> =
        listOf(500 to 10, 600 to 20, 700 to 30, 800 to -40, 900 to 50, 1000 to 60)

    private fun sourceFontBytes(
        longOffsets: Boolean = false,
        extraTables: List<NamedTable> = emptyList(),
        maxp: ByteArray = maxpTableBytes(numGlyphs = 6),
        glyfAndLoca: GlyfAndLoca = glyfAndLocaBytes(outlines, longOffsets),
    ): ByteArray = trueTypeFontBytes(
        listOf(
            "cmap" holding cmapTableBytes(
                listOf(
                    CmapRecord(
                        platformId = 3,
                        encodingId = 1,
                        subtable = cmapFormat4Bytes(
                            listOf(Format4Segment(startCode = 0x41, endCode = 0x45, idDelta = -0x40)),
                        ),
                    ),
                ),
            ),
            "glyf" holding glyfAndLoca.glyf,
            "head" holding headTableBytes(indexToLocFormat = if (longOffsets) 1 else 0),
            "hhea" holding hheaTableBytes(numberOfHMetrics = 6),
            "hmtx" holding hmtxTableBytes(metrics = metrics),
            "loca" holding glyfAndLoca.loca,
            "maxp" holding maxp,
        ) + extraTables,
    )

    private fun HeadTable.box(): List<Int> = listOf(xMin, yMin, xMax, yMax)

    private fun directoryOf(bytes: ByteArray): TableDirectory =
        TableDirectory.parse(BigEndianReader.over(bytes, "the subset"))

    /** numTables, searchRange, entrySelector and rangeShift, read raw. */
    private fun searchFieldsOf(bytes: ByteArray): List<Int> {
        val reader = BigEndianReader.over(bytes, "the subset")
        reader.seek(4)
        return listOf(reader.uint16(), reader.uint16(), reader.uint16(), reader.uint16())
    }

    /**
     * The checksum, written from the clause a second time so that the sums the
     * subsetter records are checked against the specification rather than against
     * the function that produced them.
     *
     * The clause's C adds `uint32` units up to `Table+((Length+3) & ~3)`, with a
     * note that data of a length that is not a multiple of four "must be … treated
     * as though it contains zero padding to a length that is a multiple of four".
     * Here that rounding is a loop over four bytes with zeros past the end, and
     * the `uint32` wraparound is a remainder rather than a mask.
     */
    private fun checksumFromTheClause(bytes: ByteArray, from: Int, length: Int): Long {
        var sum = 0L
        var at = from
        while (at < from + length) {
            var unit = 0L
            for (index in 0 until 4) {
                val byte = if (at + index < from + length) bytes[at + index].toInt() and 0xFF else 0
                unit = unit * 256L + byte
            }
            sum = (sum + unit) % 4294967296L
            at += 4
        }
        return sum
    }
}
