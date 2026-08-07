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

// OpenType 1.9.1 §"OpenType font file" — "Data Types"

/**
 * The one rule the whole format rests on — "All OpenType fonts use big-endian
 * (network) byte order" — and the widths the "Data Types" table gives each type.
 *
 * The cases that matter are the ones where a signed Kotlin type would answer
 * differently from the specification's type: `uint16` 0xFFFF is 65535 and not
 * −1, `uint32` 0xFFFFFFFF is 4294967295 and not −1, while `int16` 0xFFFF is −1
 * and `int8` 0x80 is −128. Every one of those appears in a real font — 0xFFFF
 * ends the segment list of every `cmap` format 4 subtable, and `int16` carries
 * the deltas and the side bearings.
 */
class BigEndianReaderTest {
    private fun readerOver(vararg bytes: Int): BigEndianReader =
        BigEndianReader.over(ByteArray(bytes.size) { bytes[it].toByte() }, "the bytes under test")

    @Test
    fun `uint16 is composed most significant byte first`() {
        assertEquals(0x1234, readerOver(0x12, 0x34).uint16())
    }

    @Test
    fun `uint16 spans the whole unsigned range`() {
        assertEquals(65535, readerOver(0xFF, 0xFF).uint16())
        assertEquals(0, readerOver(0x00, 0x00).uint16())
    }

    @Test
    fun `int16 is signed and read as two byte complement`() {
        assertEquals(-1, readerOver(0xFF, 0xFF).int16())
        assertEquals(-32768, readerOver(0x80, 0x00).int16())
        assertEquals(32767, readerOver(0x7F, 0xFF).int16())
    }

    @Test
    fun `uint32 spans the whole unsigned range`() {
        assertEquals(4294967295L, readerOver(0xFF, 0xFF, 0xFF, 0xFF).uint32())
        assertEquals(0x5F0F3CF5L, readerOver(0x5F, 0x0F, 0x3C, 0xF5).uint32())
    }

    @Test
    fun `uint8 and int8 differ on the high half of the range`() {
        assertEquals(128, readerOver(0x80).uint8())
        assertEquals(-128, readerOver(0x80).int8())
    }

    @Test
    fun `a tag is the four characters of its bytes`() {
        assertEquals("cmap", readerOver(0x63, 0x6D, 0x61, 0x70).tag())
        // "It must have one to four non-space characters, padded with trailing
        // spaces (byte value 0x20)."
        assertEquals("cvt ", readerOver(0x63, 0x76, 0x74, 0x20).tag())
    }

    @Test
    fun `a tag byte outside the printable range is refused`() {
        // "Each byte within the array must have a value in the range 0x20 to 0x7E."
        val refused = assertFailsWith<IllegalArgumentException> { readerOver(0x63, 0x6D, 0x61, 0x00).tag() }

        assertTrue("0x0" in refused.message.orEmpty(), refused.message)
    }

    @Test
    fun `reads run in sequence`() {
        val reader = readerOver(0x00, 0x01, 0xFF, 0xFE, 0x00, 0x00, 0x00, 0x08)

        assertEquals(1, reader.uint16())
        assertEquals(-2, reader.int16())
        assertEquals(8L, reader.uint32())
        assertEquals(8, reader.position)
    }

    @Test
    fun `a read past the end names what it was reading`() {
        val reader = BigEndianReader.over(ByteArray(3), "the 'head' table")

        val refused = assertFailsWith<IllegalArgumentException> {
            reader.uint32()
        }

        assertTrue("the 'head' table" in refused.message.orEmpty(), refused.message)
    }

    @Test
    fun `a positioned read leaves the cursor alone`() {
        val reader = readerOver(0x00, 0x01, 0x00, 0x02)

        assertEquals(1, reader.uint16())
        assertEquals(2, reader.uint16At(2))
        assertEquals(2, reader.position)
    }

    @Test
    fun `a slice is a window on the same bytes and starts from zero`() {
        val reader = readerOver(0x00, 0x00, 0x12, 0x34, 0x56, 0x78)

        val slice = reader.sliceAt(2, 4, "a subtable")

        assertEquals(4, slice.size)
        assertEquals(0x1234, slice.uint16())
        assertEquals(0x5678, slice.uint16())
    }

    @Test
    fun `a slice past the end of its parent is refused where the claim is made`() {
        val reader = BigEndianReader.over(ByteArray(10), "the font program")

        val refused = assertFailsWith<IllegalArgumentException> { reader.sliceAt(8, 4, "the 'glyf' table") }

        assertTrue("the 'glyf' table" in refused.message.orEmpty(), refused.message)
        assertTrue("the font program" in refused.message.orEmpty(), refused.message)
    }

    @Test
    fun `a slice cannot be read past its own end even where its parent has bytes`() {
        val reader = BigEndianReader.over(ByteArray(100), "the font program")

        val slice = reader.sliceAt(0, 4, "a subtable")

        assertFailsWith<IllegalArgumentException> { slice.uint16At(4) }
    }
}
