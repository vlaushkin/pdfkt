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
import kotlin.test.assertNull
import kotlin.test.assertTrue

// OpenType 1.9.1 §"OpenType font file" — "Organization of an OpenType Font",
// "Table Directory", "Font Collections"

/**
 * The directory a font opens with: "The OpenType font starts with the table
 * directory, which is a directory of the top-level tables in a font."
 *
 * The claim worth testing hardest is a negative one. `searchRange`,
 * `entrySelector` and `rangeShift` are precomputed search parameters, and the
 * specification asks parsers to ignore them and derive what they need from
 * `numTables` — "incorrect values could potentially be used as an attack vector
 * against some implementations". [trueTypeFontBytes] therefore writes nonsense
 * into all three by default, and every test in this file that parses a font is
 * also a test that the nonsense was not read.
 */
class TableDirectoryTest {
    private fun directoryOf(bytes: ByteArray): TableDirectory =
        TableDirectory.parse(BigEndianReader.over(bytes, "the font program"))

    @Test
    fun `a font with TrueType outlines opens with 0x00010000`() {
        val directory = directoryOf(minimalFontBytes())

        assertEquals(TableDirectory.TRUE_TYPE_OUTLINES, directory.sfntVersion)
    }

    @Test
    fun `a font with CFF outlines opens with OTTO`() {
        val bytes = trueTypeFontBytes(
            listOf("head" holding headTableBytes()),
            sfntVersion = TableDirectory.COMPACT_FONT_FORMAT_OUTLINES,
        )

        assertEquals(TableDirectory.COMPACT_FONT_FORMAT_OUTLINES, directoryOf(bytes).sfntVersion)
    }

    @Test
    fun `the search parameters of the directory are not read`() {
        // Every one of the three is wrong; the directory still parses, because
        // the number of records comes from numTables alone.
        val directory = directoryOf(minimalFontBytes())

        assertEquals(listOf("cmap", "head", "hhea", "hmtx", "maxp"), directory.tables.map { it.tag })
    }

    @Test
    fun `a table is reached by the offset and length of its record`() {
        val head = headTableBytes(unitsPerEm = 2048)
        val directory = directoryOf(minimalFontBytes(head = head))

        val reader = directory.readerOf("head")

        assertEquals(head.size, reader.size)
        assertEquals(2048, HeadTable.parse(reader).unitsPerEm)
    }

    @Test
    fun `a table the font does not have is absent rather than empty`() {
        val directory = directoryOf(minimalFontBytes())

        assertTrue("glyf" !in directory)
        assertNull(directory.readerOrNull("glyf"))
    }

    @Test
    fun `asking for a missing table says which tables the font has`() {
        val directory = directoryOf(minimalFontBytes())

        val refused = assertFailsWith<IllegalArgumentException> { directory.readerOf("glyf") }

        assertTrue("'glyf'" in refused.message.orEmpty(), refused.message)
        assertTrue("'cmap'" in refused.message.orEmpty(), refused.message)
    }

    @Test
    fun `a font of a collection is read through the offset the TTC header gives it`() {
        // "Font Collection ID string: 'ttcf'", the version, numFonts, and "the
        // offset to the TableDirectory for each font from the beginning of the file".
        val first = minimalFontBytes()
        val second = trueTypeFontBytes(
            listOf("head" holding headTableBytes()),
            sfntVersion = TableDirectory.COMPACT_FONT_FORMAT_OUTLINES,
        )
        val header = 12 + 2 * 4
        val bytes = bigEndian {
            tag("ttcf")
            u16(1)
            u16(0)
            u32(2)
            u32(header.toLong())
            u32((header + first.size).toLong())
        } + first + second
        val file = BigEndianReader.over(bytes, "the font program")

        assertEquals(TableDirectory.TRUE_TYPE_OUTLINES, TableDirectory.parse(file, 0).sfntVersion)
        assertEquals(TableDirectory.COMPACT_FONT_FORMAT_OUTLINES, TableDirectory.parse(file, 1).sfntVersion)
        val refused = assertFailsWith<IllegalArgumentException> { TableDirectory.parse(file, 2) }
        assertTrue("holds 2 fonts" in refused.message.orEmpty(), refused.message)
    }

    @Test
    fun `a single font has no font but font 0`() {
        val refused = assertFailsWith<IllegalArgumentException> {
            TableDirectory.parse(BigEndianReader.over(minimalFontBytes(), "the font program"), 1)
        }

        assertTrue("collection" in refused.message.orEmpty(), refused.message)
    }

    @Test
    fun `bytes that are not a font are refused with the version they open with`() {
        val refused = assertFailsWith<IllegalArgumentException> {
            directoryOf(bigEndian { tag("%PDF") })
        }

        assertTrue("sfntVersion" in refused.message.orEmpty(), refused.message)
    }

    @Test
    fun `a record reaching past the end of the file is refused`() {
        val sound = minimalFontBytes()
        // The record for 'cmap' is the first, and its length is the last uint32
        // of it: 12 bytes of directory header, then tag + checksum + offset.
        val broken = sound.copyOf()
        broken[12 + 12] = 0x7F
        broken[12 + 13] = 0x00

        val refused = assertFailsWith<IllegalArgumentException> { directoryOf(broken) }

        assertTrue("past the end" in refused.message.orEmpty(), refused.message)
    }

    @Test
    fun `two records for one tag are refused rather than resolved`() {
        // "If a font resource does contain more than one table of a given type,
        // behaviour is unpredictable."
        val bytes = trueTypeFontBytes(
            listOf("head" holding headTableBytes(), "head" holding headTableBytes(unitsPerEm = 2048)),
        )

        val refused = assertFailsWith<IllegalArgumentException> { directoryOf(bytes) }

        assertTrue("two records" in refused.message.orEmpty(), refused.message)
    }

    @Test
    fun `more records than the file can hold are refused`() {
        val bytes = trueTypeFontBytes(listOf("head" holding headTableBytes()), numTables = 4096)

        val refused = assertFailsWith<IllegalArgumentException> { directoryOf(bytes) }

        assertTrue("4096" in refused.message.orEmpty(), refused.message)
    }
}
