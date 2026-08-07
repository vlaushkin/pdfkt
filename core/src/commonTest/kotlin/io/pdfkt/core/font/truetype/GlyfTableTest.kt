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
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

// OpenType 1.9.1 §"glyf — Glyph Data" — "Glyph headers",
// "Simple glyph description", "Composite glyph description"; §"loca"

/**
 * `glyf`, read for the two things a document generator asks of it: where a
 * glyph's description is, and which other glyphs it is drawn out of.
 *
 * The component records are where a parser earns its keep. They are
 * variable-length, there is no count of them and no offset to the next one —
 * only the `MORE_COMPONENTS` flag of the one being read — so the width of every
 * field has to come out right or the walk lands in the middle of a record and
 * reports components the font never named. The fonts built here therefore mix
 * the shapes the clause allows in one glyph: byte arguments and word arguments,
 * no transform and a two-by-two one.
 */
class GlyfTableTest {
    /** Three points on one contour: 29 bytes of description. */
    private val square = simpleGlyphBytes(
        contours = listOf(listOf(GlyphPoint(0, 0), GlyphPoint(100, 0), GlyphPoint(0, 100))),
        xMin = 0,
        yMin = 0,
        xMax = 100,
        yMax = 100,
    )

    private val breve = simpleGlyphBytes(
        contours = listOf(listOf(GlyphPoint(10, 700), GlyphPoint(40, 20))),
        xMin = 10,
        yMin = 700,
        xMax = 50,
        yMax = 720,
    )

    private fun glyf(glyphs: List<ByteArray>, longOffsets: Boolean = true): GlyfTable {
        val tables = glyfAndLocaBytes(glyphs, longOffsets)
        val loca = LocaTable.parse(
            BigEndianReader.over(tables.loca, "the 'loca' table"),
            numGlyphs = glyphs.size,
            longOffsets = longOffsets,
        )
        return GlyfTable.parse(BigEndianReader.over(tables.glyf, "the 'glyf' table"), loca)
    }

    @Test
    fun `a simple glyph is read as far as its header`() {
        // "If the number of contours is greater than or equal to zero, this is a
        // simple glyph."
        val glyph = glyf(listOf(square)).glyphOf(0)

        val simple = assertIs<Glyph.Simple>(glyph)
        assertEquals(1, simple.header.numberOfContours)
        assertEquals(0, simple.header.xMin)
        assertEquals(0, simple.header.yMin)
        assertEquals(100, simple.header.xMax)
        assertEquals(100, simple.header.yMax)
        assertEquals(emptyList(), simple.componentGlyphIds)
    }

    @Test
    fun `a glyph with no description at all is blank`() {
        // "if a glyph has no outline or instructions, then loca[n] = loca[n+1]" —
        // the space character, and a font whose .notdef is empty.
        val table = glyf(listOf(ByteArray(0), square))

        assertEquals(Glyph.Blank, table.glyphOf(0))
        assertNull(table.readerOrNullOf(0))
        assertIs<Glyph.Simple>(table.glyphOf(1))
    }

    @Test
    fun `a glyph description is located where loca says it is`() {
        val table = glyf(listOf(square, breve))

        assertEquals(square.size, table.readerOrNullOf(0)!!.size)
        assertEquals(breve.size, table.readerOrNullOf(1)!!.size)
        // The second description reads its own header, not the tail of the first.
        assertEquals(700, (table.glyphOf(1) as Glyph.Simple).header.yMin)
    }

    @Test
    fun `a composite reports the glyphs it is built from in the order the font gives them`() {
        val composite = compositeGlyphBytes(
            components = listOf(
                ComponentRecord(glyphId = 1, flags = 0x0003, argument1 = -30, argument2 = 40),
                ComponentRecord(glyphId = 2, flags = 0x0002, argument1 = 10, argument2 = -20),
            ),
        )
        val table = glyf(listOf(ByteArray(0), square, breve, composite))

        val parsed = assertIs<Glyph.Composite>(table.glyphOf(3))
        assertEquals(-1, parsed.header.numberOfContours)
        assertEquals(listOf(1, 2), parsed.componentGlyphIds)
    }

    @Test
    fun `component records of different widths are walked at the width their flags give them`() {
        // 0x0003 is ARG_1_AND_2_ARE_WORDS | ARGS_ARE_XY_VALUES: two int16
        // arguments, no transform. 0x0082 is ARGS_ARE_XY_VALUES |
        // WE_HAVE_A_TWO_BY_TWO: two int8 arguments and "four F2DOT14 values are
        // appended". The two records are 8 and 14 bytes; a parser that assumed
        // either shape would read the second record out of the first one's
        // arguments.
        val composite = compositeGlyphBytes(
            components = listOf(
                ComponentRecord(glyphId = 1, flags = 0x0003, argument1 = -30, argument2 = 40),
                ComponentRecord(
                    glyphId = 2,
                    flags = 0x0082,
                    argument1 = 10,
                    argument2 = -20,
                    transform = listOf(0x4000, 0, 0, 0x2000), // 1.0, 0, 0, 0.5
                ),
            ),
        )
        val table = glyf(listOf(ByteArray(0), square, breve, composite))

        val components = assertIs<Glyph.Composite>(table.glyphOf(3)).components
        assertEquals(2, components.size)
        assertEquals(1, components[0].glyphId)
        assertEquals(-30, components[0].argument1)
        assertEquals(40, components[0].argument2)
        assertTrue(components[0].argumentsAreWords)
        assertTrue(components[0].argumentsAreXyValues)
        assertTrue(components[0].hasMoreComponents)
        assertEquals(2, components[1].glyphId)
        assertEquals(10, components[1].argument1)
        assertEquals(-20, components[1].argument2)
        assertFalse(components[1].argumentsAreWords)
        assertFalse(components[1].hasMoreComponents)
    }

    @Test
    fun `arguments that are point numbers rather than offsets are unsigned`() {
        // "If this is set, the arguments are signed xy values; otherwise, they are
        // unsigned point numbers." Without ARGS_ARE_XY_VALUES, a byte of 200 is
        // point 200 and not the offset −56.
        val composite = compositeGlyphBytes(
            components = listOf(ComponentRecord(glyphId = 1, flags = 0x0000, argument1 = 200, argument2 = 3)),
        )
        val table = glyf(listOf(ByteArray(0), square, composite))

        val component = assertIs<Glyph.Composite>(table.glyphOf(2)).components.single()
        assertEquals(200, component.argument1)
        assertEquals(3, component.argument2)
        assertFalse(component.argumentsAreXyValues)
    }

    @Test
    fun `the glyph id of a component is recorded where a subset would rewrite it`() {
        // "the data block for each child component starts with two uint16 values:
        // a flags field, and a glyph ID" — so the id sits two bytes into the
        // record, and the first record starts after the ten-byte glyph header.
        val composite = compositeGlyphBytes(
            components = listOf(
                ComponentRecord(glyphId = 1, flags = 0x0003, argument1 = 0, argument2 = 0),
                ComponentRecord(glyphId = 2, flags = 0x0002, argument1 = 0, argument2 = 0),
            ),
        )
        val table = glyf(listOf(ByteArray(0), square, breve, composite))
        val components = assertIs<Glyph.Composite>(table.glyphOf(3)).components

        assertEquals(12, components[0].glyphIdOffset)
        assertEquals(20, components[1].glyphIdOffset)
        val description = table.readerOrNullOf(3)!!
        assertEquals(1, description.uint16At(components[0].glyphIdOffset))
        assertEquals(2, description.uint16At(components[1].glyphIdOffset))
    }

    @Test
    fun `instructions after the last component do not become another component`() {
        // "If the flag is set on any component glyph, then a uint16 value is read
        // immediately after the last component glyph to get the byte length for
        // instructions." Those bytes are inside the glyph's description and are
        // not a record.
        val composite = compositeGlyphBytes(
            components = listOf(ComponentRecord(glyphId = 1, flags = 0x0002)),
            instructions = byteArrayOf(0x2E, 0x2F, 0x18, 0x00),
        )
        val table = glyf(listOf(ByteArray(0), square, composite))

        assertEquals(listOf(1), table.glyphOf(2).componentGlyphIds)
    }

    @Test
    fun `the closure of simple glyphs is the glyphs themselves`() {
        val table = glyf(listOf(ByteArray(0), square, breve))

        assertEquals(setOf(1, 2), table.componentClosureOf(listOf(2, 1)))
    }

    @Test
    fun `the closure of a composite pulls in the glyphs it is drawn out of`() {
        val composite = compositeGlyphBytes(
            components = listOf(
                ComponentRecord(glyphId = 1, flags = 0x0002),
                ComponentRecord(glyphId = 2, flags = 0x0002),
            ),
        )
        val table = glyf(listOf(ByteArray(0), square, breve, composite))

        assertEquals(setOf(1, 2, 3), table.componentClosureOf(listOf(3)))
    }

    @Test
    fun `the closure follows composites nested inside composites`() {
        // "Composite glyphs may be nested within other composite glyphs … Thus, a
        // composite glyph description is a directed graph." Glyph 4 references
        // glyph 3, which references glyphs 1 and 2; a closure one level deep
        // would subset glyph 4 with the components of nothing.
        val inner = compositeGlyphBytes(
            components = listOf(ComponentRecord(glyphId = 1), ComponentRecord(glyphId = 2)),
        )
        val outer = compositeGlyphBytes(
            components = listOf(ComponentRecord(glyphId = 3), ComponentRecord(glyphId = 1)),
        )
        val table = glyf(listOf(ByteArray(0), square, breve, inner, outer))

        assertEquals(setOf(1, 2, 3, 4), table.componentClosureOf(listOf(4)))
    }

    @Test
    fun `the closure is returned in glyph id order`() {
        val inner = compositeGlyphBytes(components = listOf(ComponentRecord(glyphId = 2)))
        val outer = compositeGlyphBytes(components = listOf(ComponentRecord(glyphId = 3)))
        val table = glyf(listOf(ByteArray(0), square, breve, inner, outer))

        assertEquals(listOf(0, 2, 3, 4), table.componentClosureOf(listOf(4, 0)).toList())
    }

    @Test
    fun `a composite that references itself does not go round for ever`() {
        // The clause requires the graph to be acyclic; a font is input from
        // outside, so the traversal terminates on one that is not.
        val cyclic = compositeGlyphBytes(components = listOf(ComponentRecord(glyphId = 1)))
        val table = glyf(listOf(ByteArray(0), cyclic))

        assertEquals(setOf(1), table.componentClosureOf(listOf(1)))
    }

    @Test
    fun `a component naming a glyph the font does not have is refused`() {
        val composite = compositeGlyphBytes(components = listOf(ComponentRecord(glyphId = 9)))
        val table = glyf(listOf(ByteArray(0), square, composite))

        val message = assertFailsWith<IllegalArgumentException> { table.glyphOf(2) }.message
        assertTrue(message!!.contains("glyph 2 is a composite whose component 0 is glyph 9"), message)
    }

    @Test
    fun `a component setting two of the transformation flags is refused`() {
        // "The WE_HAVE_A_SCALE, WE_HAVE_AN_X_AND_Y_SCALE and WE_HAVE_A_TWO_BY_TWO
        // flags are mutually exclusive: no more than one of these may be set."
        // How long the record is depends on which, so there is no reading on.
        val composite = compositeGlyphBytes(
            components = listOf(ComponentRecord(glyphId = 1, flags = 0x004A, transform = listOf(0x4000))),
        )
        val table = glyf(listOf(ByteArray(0), square, composite))

        val message = assertFailsWith<IllegalArgumentException> { table.glyphOf(2) }.message
        assertTrue(message!!.contains("mutually exclusive"), message)
    }

    @Test
    fun `a description shorter than a glyph header is refused`() {
        val table = glyf(listOf(ByteArray(6)))

        val message = assertFailsWith<IllegalArgumentException> { table.glyphOf(0) }.message
        assertTrue(message!!.contains("10-byte header"), message)
    }

    @Test
    fun `a loca table locating glyphs past the end of glyf is refused`() {
        val loca = LocaTable.parse(
            BigEndianReader.over(locaTableBytes(listOf(0, 29, 400), longOffsets = true), "the 'loca' table"),
            numGlyphs = 2,
            longOffsets = true,
        )

        val message = assertFailsWith<IllegalArgumentException> {
            GlyfTable.parse(BigEndianReader.over(square + breve, "the 'glyf' table"), loca)
        }.message

        assertTrue(message!!.contains("accounts for 400 bytes"), message)
    }

    @Test
    fun `a short loca table locates the same glyphs as a long one`() {
        val glyphs = listOf(ByteArray(0), square, breve)

        for (glyphId in glyphs.indices) {
            assertEquals(
                glyf(glyphs, longOffsets = true).glyphOf(glyphId).toString(),
                glyf(glyphs, longOffsets = false).glyphOf(glyphId).toString(),
            )
        }
    }

    @Test
    fun `the closure of a glyph the font does not have is refused`() {
        val table = glyf(listOf(square))

        assertFailsWith<IllegalArgumentException> { table.componentClosureOf(listOf(0, 7)) }
    }
}
