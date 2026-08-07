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
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

// OpenType 1.9.1 §"cmap": "'cmap' Header", "Encoding records and encodings",
// "Format 12: Segmented coverage"

/**
 * The `cmap` header, the choice between the subtables it lists, and format 12.
 *
 * Format 12 is where everything above the basic multilingual plane lives, and
 * the choice is what decides whether a font that has both formats can reach it:
 *
 * > If a font includes Unicode subtables for both 16-bit encoding (typically,
 * > format 4) and also 32-bit encoding (formats 10 or 12) … the 32-bit encoding
 * > should be used by applications.
 *
 * A parser that takes the first subtable it recognises instead would work on
 * every Latin document and lose every character past U+FFFF, silently, which is
 * the failure mode the no-silent-loss policy exists to prevent.
 */
class CmapTableTest {
    private fun cmap(bytes: ByteArray): CmapTable = CmapTable.parse(BigEndianReader.over(bytes, "the 'cmap' table"))

    private fun format12(vararg groups: Format12Group): CmapFormat12Subtable = CmapFormat12Subtable.parse(
        BigEndianReader.over(cmapFormat12Bytes(groups.toList()), "a 'cmap' format 12 subtable"),
    )

    private val latinFormat4 = cmapFormat4Bytes(
        listOf(Format4Segment(startCode = 0x41, endCode = 0x5A, idDelta = -0x40)),
    )

    private val latinAndSupplementaryFormat12 = cmapFormat12Bytes(
        listOf(
            Format12Group(startCharCode = 0x41, endCharCode = 0x5A, startGlyphId = 1),
            Format12Group(startCharCode = 0x1F600, endCharCode = 0x1F601, startGlyphId = 900),
        ),
    )

    @Test
    fun `a group maps a run of codes onto a run of glyphs`() {
        // "startGlyphID | Glyph index corresponding to the starting character
        // code", and "Glyph IDs for subsequent characters follow in sequence".
        val subtable = format12(
            Format12Group(startCharCode = 0x41, endCharCode = 0x5A, startGlyphId = 36),
        )

        assertEquals(36, subtable.glyphIdOf(0x41))
        assertEquals(37, subtable.glyphIdOf(0x42))
        assertEquals(61, subtable.glyphIdOf(0x5A))
    }

    @Test
    fun `a code outside every group is the missing glyph`() {
        val subtable = format12(
            Format12Group(startCharCode = 0x41, endCharCode = 0x5A, startGlyphId = 36),
            Format12Group(startCharCode = 0x410, endCharCode = 0x44F, startGlyphId = 100),
        )

        assertEquals(0, subtable.glyphIdOf(0x40))
        assertEquals(0, subtable.glyphIdOf(0x5B))
        assertEquals(0, subtable.glyphIdOf(0x40F))
        assertEquals(0, subtable.glyphIdOf(0x450))
    }

    @Test
    fun `supplementary plane codes are what format 12 is for`() {
        // "the standard character-to-glyph-index mapping subtable for fonts
        // supporting Unicode character repertoires that include
        // supplementary-plane characters (U+10000 to U+10FFFF)"
        val subtable = format12(
            Format12Group(startCharCode = 0x10000, endCharCode = 0x1000F, startGlyphId = 7),
            Format12Group(startCharCode = 0x10FFF0, endCharCode = 0x10FFFF, startGlyphId = 40),
        )

        assertEquals(7, subtable.glyphIdOf(0x10000))
        assertEquals(22, subtable.glyphIdOf(0x1000F))
        assertEquals(55, subtable.glyphIdOf(0x10FFFF))
    }

    @Test
    fun `every group of a large subtable is found`() {
        // The search over the groups is a binary one; a subtable of many groups
        // is what tells it apart from a scan that happens to work.
        val groups = (0 until 500).map {
            Format12Group(
                startCharCode = (it * 10L),
                endCharCode = (it * 10L + 4),
                startGlyphId = it + 1,
            )
        }
        val subtable = CmapFormat12Subtable.parse(
            BigEndianReader.over(cmapFormat12Bytes(groups), "a 'cmap' format 12 subtable"),
        )

        assertEquals(500, subtable.groupCount)
        for (group in 0 until 500) {
            assertEquals(group + 1, subtable.glyphIdOf(group * 10), "the start of group $group")
            assertEquals(group + 5, subtable.glyphIdOf(group * 10 + 4), "the end of group $group")
            assertEquals(0, subtable.glyphIdOf(group * 10 + 5), "the gap after group $group")
        }
    }

    @Test
    fun `groups out of order are refused`() {
        // "Groups must be sorted by increasing startCharCode. A group's
        // endCharCode must be less than the startCharCode of the following
        // group, if any."
        val refused = assertFailsWith<IllegalArgumentException> {
            format12(
                Format12Group(startCharCode = 0x100, endCharCode = 0x200, startGlyphId = 1),
                Format12Group(startCharCode = 0x50, endCharCode = 0x60, startGlyphId = 20),
            )
        }

        assertTrue("sorted" in refused.message.orEmpty(), refused.message)
    }

    @Test
    fun `a group running past the last possible glyph is refused`() {
        val refused = assertFailsWith<IllegalArgumentException> {
            format12(Format12Group(startCharCode = 0, endCharCode = 0x10FFFF, startGlyphId = 1))
        }

        assertTrue("uint16" in refused.message.orEmpty(), refused.message)
    }

    @Test
    fun `the header lists every encoding record`() {
        val table = cmap(
            cmapTableBytes(
                listOf(
                    CmapRecord(platformId = 0, encodingId = 3, subtable = latinFormat4),
                    CmapRecord(platformId = 3, encodingId = 1, subtable = latinFormat4),
                    CmapRecord(platformId = 3, encodingId = 10, subtable = latinAndSupplementaryFormat12),
                ),
            ),
        )

        assertEquals(3, table.encodings.size)
        assertEquals(listOf(0, 3, 3), table.encodings.map { it.platformId })
        assertEquals(listOf(3, 1, 10), table.encodings.map { it.encodingId })
        assertEquals(listOf(4, 4, 12), table.encodings.map { it.format })
    }

    @Test
    fun `format 12 is chosen over format 4 when the font has both`() {
        val table = cmap(
            cmapTableBytes(
                listOf(
                    CmapRecord(platformId = 3, encodingId = 1, subtable = latinFormat4),
                    CmapRecord(platformId = 3, encodingId = 10, subtable = latinAndSupplementaryFormat12),
                ),
            ),
        )

        assertEquals(CmapFormat12Subtable.FORMAT, table.characterMap.format)
        assertEquals(900, table.glyphIdOf(0x1F600))
    }

    @Test
    fun `the order the records are listed in does not change the choice`() {
        val table = cmap(
            cmapTableBytes(
                listOf(
                    CmapRecord(platformId = 0, encodingId = 4, subtable = latinAndSupplementaryFormat12),
                    CmapRecord(platformId = 3, encodingId = 1, subtable = latinFormat4),
                ),
            ),
        )

        assertEquals(CmapFormat12Subtable.FORMAT, table.characterMap.format)
    }

    @Test
    fun `a font with only format 4 maps through it`() {
        val table = cmap(cmapTableBytes(listOf(CmapRecord(platformId = 3, encodingId = 1, subtable = latinFormat4))))

        assertEquals(CmapFormat4Subtable.FORMAT, table.characterMap.format)
        assertEquals(1, table.glyphIdOf(0x41))
        assertEquals(0, table.glyphIdOf(0x1F600))
    }

    @Test
    fun `both formats of a font that has both stay reachable`() {
        // A font may carry a format 4 subtable "for compatibility with older
        // applications"; the parse keeps it, so a test can hold the two against
        // each other.
        val table = cmap(
            cmapTableBytes(
                listOf(
                    CmapRecord(platformId = 3, encodingId = 1, subtable = latinFormat4),
                    CmapRecord(platformId = 3, encodingId = 10, subtable = latinAndSupplementaryFormat12),
                ),
            ),
        )

        assertEquals(1, table.subtablesOfFormat(CmapFormat4Subtable.FORMAT).size)
        assertEquals(1, table.subtablesOfFormat(CmapFormat12Subtable.FORMAT).size)
        assertEquals(
            table.subtablesOfFormat(CmapFormat4Subtable.FORMAT).single().glyphIdOf(0x41),
            table.subtablesOfFormat(CmapFormat12Subtable.FORMAT).single().glyphIdOf(0x41),
        )
    }

    @Test
    fun `a variation sequence subtable is not a character map`() {
        // "A format 14 subtable must only be used under platform ID 0 and
        // encoding ID 5", and it maps sequences rather than characters.
        val format14 = bigEndian {
            u16(14)
            u32(10)
            u32(0)
        }
        val table = cmap(
            cmapTableBytes(
                listOf(
                    CmapRecord(platformId = 0, encodingId = 5, subtable = format14),
                    CmapRecord(platformId = 3, encodingId = 1, subtable = latinFormat4),
                ),
            ),
        )

        assertEquals(CmapFormat4Subtable.FORMAT, table.characterMap.format)
    }

    @Test
    fun `a font whose only subtable is not a Unicode one is refused`() {
        // Platform 3, encoding 0 is "Symbol": a format 4 subtable this parser can
        // read, over an encoding that is "not a Unicode encoding".
        val refused = assertFailsWith<IllegalArgumentException> {
            cmap(cmapTableBytes(listOf(CmapRecord(platformId = 3, encodingId = 0, subtable = latinFormat4))))
        }

        assertTrue("platform 3, encoding 0, format 4" in refused.message.orEmpty(), refused.message)
    }

    @Test
    fun `a cmap table of an unknown version is refused`() {
        val refused = assertFailsWith<IllegalArgumentException> {
            cmap(
                cmapTableBytes(
                    listOf(CmapRecord(platformId = 3, encodingId = 1, subtable = latinFormat4)),
                    version = 1,
                ),
            )
        }

        assertTrue("version 1" in refused.message.orEmpty(), refused.message)
    }

    @Test
    fun `an encoding record pointing outside the table is refused`() {
        val bytes = cmapTableBytes(listOf(CmapRecord(platformId = 3, encodingId = 1, subtable = latinFormat4)))
        val broken = bytes.copyOf()
        // The subtableOffset is the last uint32 of the only record: version and
        // numTables, then platformID and encodingID.
        broken[8] = 0x7F

        assertFailsWith<IllegalArgumentException> { cmap(broken) }
    }
}
