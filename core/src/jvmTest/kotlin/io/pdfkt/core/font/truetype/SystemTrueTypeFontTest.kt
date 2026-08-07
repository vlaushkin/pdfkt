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
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// OpenType 1.9.1 §"cmap", §"head", §"hhea", §"hmtx", §"maxp", §"glyf", §"loca"
// ISO 32000-2:2020 §9.2.4 "Glyph positioning and metrics"

/**
 * The parser against font programs it did not write: the system's DejaVu
 * fonts (Debian's `fonts-dejavu-core`).
 *
 * The synthetic tests in `commonTest` say the parser reads the layout the
 * specification states. They cannot say the specification was read correctly,
 * because the same misreading produces both the writer and the reader. A font
 * built by somebody else, years ago, from the same document is the independent
 * half — and it is a font, not a rendering library: nothing here compares
 * against how another program parses it.
 *
 * No font binary is committed. The fonts are read from the system at the paths
 * below, and a missing one fails the test rather than skipping it: a check that
 * quietly runs over nothing is the failure mode this rule exists to prevent.
 *
 * The two fonts are picked for what they are shaped like:
 *
 * - **DejaVu Sans** is proportional and carries both a `cmap` format 4 and a
 *   format 12 subtable, so the two can be held against each other over the whole
 *   basic multilingual plane.
 * - **DejaVu Sans Mono** has 3377 glyphs and four `hmtx` records, which is the
 *   optimisation of the `hmtx` clause — "the advance width value of the last
 *   record applies to all remaining glyph IDs" — at its most extreme. A parser
 *   that missed it would give almost every glyph in that font a width read out
 *   of the left side bearings.
 */
class SystemTrueTypeFontTest {
    private val sans = TrueTypeFont.parse(systemFontBytes("DejaVuSans.ttf", WHY))
    private val mono = TrueTypeFont.parse(systemFontBytes("DejaVuSansMono.ttf", WHY))

    /** The ASCII characters a document actually draws with. */
    private val printableAscii: List<Int> = (0x20..0x7E).toList()

    /**
     * Cyrillic: the two alphabets of U+0410..U+044F, plus Ё and ё, which sit
     * outside the block's main run.
     */
    private val cyrillic: List<Int> = (0x410..0x44F).toList() + listOf(0x401, 0x451)

    @Test
    fun `an installed system font parses`() {
        assertEquals(TableDirectory.TRUE_TYPE_OUTLINES, sans.directory.sfntVersion)
        assertEquals(2048, sans.unitsPerEm)
        assertEquals(6253, sans.numGlyphs)
        assertTrue(sans.maxp.describesTrueTypeOutlines)
        assertTrue(sans.head.hasLongLocaOffsets)
    }

    @Test
    fun `every ASCII and Cyrillic code point resolves to a glyph of the font`() {
        for (codePoint in printableAscii + cyrillic) {
            val glyphId = sans.glyphIdOf(codePoint)
            assertNotEquals(0, glyphId, "U+${codePoint.toString(16)} has no glyph in DejaVu Sans")
            assertTrue(glyphId < sans.numGlyphs, "U+${codePoint.toString(16)} maps past the last glyph")
        }
    }

    @Test
    fun `every ASCII and Cyrillic glyph has an advance width`() {
        for (codePoint in printableAscii + cyrillic) {
            val advance = sans.advanceWidthOf(sans.glyphIdOf(codePoint))
            assertTrue(advance > 0, "U+${codePoint.toString(16)} advances by $advance")
            assertTrue(advance <= sans.hhea.advanceWidthMax, "U+${codePoint.toString(16)} advances by $advance")
        }
    }

    @Test
    fun `a proportional font gives its glyphs different widths`() {
        val narrow = sans.advanceWidthOf(sans.glyphIdOf('i'.code))
        val wide = sans.advanceWidthOf(sans.glyphIdOf('W'.code))

        assertTrue(narrow < wide, "i advances by $narrow and W by $wide")
    }

    @Test
    fun `Latin A and Cyrillic A are different glyphs`() {
        // They are drawn alike, and a cmap parse that fell back to the Latin
        // block would pass every ASCII test in this file while producing
        // documents whose Cyrillic came out as Latin. The glyph ids differ; the
        // advance widths do not, which is why this asserts on the ids.
        assertNotEquals(sans.glyphIdOf('A'.code), sans.glyphIdOf('А'.code))
        assertNotEquals(sans.glyphIdOf('a'.code), sans.glyphIdOf('а'.code))
    }

    @Test
    fun `a code point the font has no glyph for is the missing glyph`() {
        // U+E0002 is an unassigned code point in a supplementary plane.
        assertEquals(0, sans.glyphIdOf(0xE0002))
    }

    @Test
    fun `the font carries both cmap formats and they agree over the whole plane`() {
        // "If a font includes Unicode subtables for both 16-bit encoding
        // (typically, format 4) and also 32-bit encoding (formats 10 or 12), then
        // the characters supported by the subtable for 32-bit encoding should be
        // a superset of the characters supported by the subtable for 16-bit
        // encoding."
        val cmap = assertNotNull(sans.cmap, "a font file with no character map")
        val format4 = cmap.subtablesOfFormat(CmapFormat4Subtable.FORMAT)
        val format12 = cmap.subtablesOfFormat(CmapFormat12Subtable.FORMAT)
        assertTrue(format4.isNotEmpty(), "DejaVu Sans has no format 4 subtable")
        assertTrue(format12.isNotEmpty(), "DejaVu Sans has no format 12 subtable")

        var mapped = 0
        for (codePoint in 0..0xFFFF) {
            val fromFormat4 = format4.first().glyphIdOf(codePoint)
            val fromFormat12 = format12.first().glyphIdOf(codePoint)
            assertEquals(fromFormat4, fromFormat12, "U+${codePoint.toString(16)}")
            if (fromFormat4 != 0) mapped++
        }
        assertTrue(mapped > 3000, "only $mapped code points of the plane mapped to a glyph")
    }

    @Test
    fun `the 32 bit subtable is the one characters are mapped through`() {
        // "the 32-bit encoding should be used by applications"
        assertEquals(CmapFormat12Subtable.FORMAT, assertNotNull(sans.cmap).characterMap.format)
    }

    @Test
    fun `the widest glyph of the font is the width hhea announces`() {
        // "advanceWidthMax | Maximum advance width value in 'hmtx' table." A
        // cross-check between two tables written independently of this parser: a
        // wrong hmtx stride would move the maximum.
        for (font in listOf(sans, mono)) {
            val widest = (0 until font.numGlyphs).maxOf { font.advanceWidthOf(it) }

            assertEquals(font.hhea.advanceWidthMax, widest, "$font")
        }
    }

    @Test
    fun `a monospaced font advances every glyph by the same width`() {
        // Four hmtx records for 3377 glyphs: everything past glyph 3 takes the
        // last record's advance width.
        assertEquals(4, mono.hhea.numberOfHMetrics)
        assertEquals(3377, mono.numGlyphs)

        val advance = mono.advanceWidthOf(mono.glyphIdOf('A'.code))
        assertEquals(mono.hhea.advanceWidthMax, advance)
        for (codePoint in printableAscii + cyrillic) {
            assertEquals(advance, mono.advanceWidthOf(mono.glyphIdOf(codePoint)), "U+${codePoint.toString(16)}")
        }
    }

    @Test
    fun `the extents of the font are read off hhea`() {
        assertTrue(sans.hhea.ascender > 0, "an ascender of ${sans.hhea.ascender}")
        assertTrue(sans.hhea.descender < 0, "a descender of ${sans.hhea.descender}")
        assertTrue(sans.hhea.ascender - sans.hhea.descender > sans.unitsPerEm)
    }

    @Test
    fun `every glyph of the font has a description that reads`() {
        // 'loca' and 'glyf' are read against each other over the whole font: 6253
        // offsets, every one of them the start of something this parser has to
        // make a glyph description out of. A synthetic font exercises the shapes
        // this library thought to write; a real one exercises the shapes a type
        // designer's tools actually emit.
        val glyf = assertNotNull(sans.glyf)
        var composites = 0
        var blanks = 0
        for (glyphId in 0 until sans.numGlyphs) {
            when (val glyph = glyf.glyphOf(glyphId)) {
                is Glyph.Composite -> {
                    composites++
                    assertTrue(glyph.components.isNotEmpty(), "glyph $glyphId is a composite of nothing")
                    assertEquals(-1, glyph.header.numberOfContours, "glyph $glyphId")
                }

                is Glyph.Simple -> assertTrue(glyph.header.numberOfContours >= 0, "glyph $glyphId")
                Glyph.Blank -> blanks++
            }
        }

        assertTrue(composites > 100, "only $composites of ${sans.numGlyphs} glyphs are composites")
        assertTrue(blanks > 0, "no glyph of the font is without an outline")
    }

    @Test
    fun `the space character has no outline`() {
        // "This also applies to any other glyphs without an outline, such as the
        // glyph for the space character: if a glyph has no outline or
        // instructions, then loca[n] = loca[n+1]."
        val glyf = assertNotNull(sans.glyf)
        val space = sans.glyphIdOf(' '.code)

        assertNotEquals(0, space)
        assertEquals(Glyph.Blank, glyf.glyphOf(space))
        assertTrue(sans.advanceWidthOf(space) > 0, "a space that advances by nothing")
    }

    @Test
    fun `a precomposed Cyrillic letter is drawn out of the glyphs of its parts`() {
        // й is и with a breve over it, and a font that has both draws the first
        // out of the second: "A composite, or compound, glyph describes an
        // outline indirectly by referencing other glyphs … This is useful when
        // the same contours are needed for multiple glyphs".
        val glyf = assertNotNull(sans.glyf)
        val shortI = sans.glyphIdOf('й'.code)
        val i = sans.glyphIdOf('и'.code)

        val composite = assertIs<Glyph.Composite>(glyf.glyphOf(shortI), "й is not a composite glyph")
        assertTrue(i in composite.componentGlyphIds, "й is built from ${composite.componentGlyphIds}, not from и")
        assertEquals(2, composite.components.size, "й is built from ${composite.componentGlyphIds}")

        // "This flag must always be set for the first component of a composite
        // glyph" — the base letter is placed by an offset vector rather than by
        // aligning two point numbers, so the record's arguments are signed.
        val first: GlyphComponent = composite.components.first()
        assertTrue(first.argumentsAreXyValues, "the first component of й places itself by point numbers")
        assertTrue(composite.components.last().hasMoreComponents.not(), "the record sequence of й does not end")
    }

    @Test
    fun `the bounding box of a composite covers the mark it puts on the letter`() {
        // "These values are obtained directly from the point coordinate data for
        // the glyph, comparing all on-curve and off-curve points" — the points of
        // a composite are the ones its components contribute, so the breve over
        // и reaches higher than и itself does.
        val glyf = assertNotNull(sans.glyf)
        val shortI: GlyphHeader = assertIs<Glyph.Composite>(glyf.glyphOf(sans.glyphIdOf('й'.code))).header
        val i: GlyphHeader = assertIs<Glyph.Simple>(glyf.glyphOf(sans.glyphIdOf('и'.code))).header

        assertTrue(shortI.yMax > i.yMax, "й reaches to ${shortI.yMax} and и to ${i.yMax}")
        assertTrue(shortI.yMax <= sans.head.yMax, "й reaches past the bounding box of the font")
    }

    @Test
    fun `subsetting a precomposed Cyrillic letter keeps the glyphs it is drawn from`() {
        // The acceptance of this parse: a subset built from the glyphs a document
        // draws must carry the components too, or the viewer draws й out of
        // whatever glyphs those ids point at in the subset.
        val glyf = assertNotNull(sans.glyf)
        val shortI = sans.glyphIdOf('й'.code)
        val yo = sans.glyphIdOf('ё'.code)

        val closure = glyf.componentClosureOf(listOf(shortI, yo))

        assertTrue(closure.containsAll(listOf(shortI, yo)), "the closure dropped the glyphs it was asked about")
        assertTrue(sans.glyphIdOf('и'.code) in closure, "и is not in the closure of й")
        assertTrue(sans.glyphIdOf('е'.code) in closure, "е is not in the closure of ё")
        assertTrue(closure.size >= 6, "two composites of two components closed over ${closure.size} glyphs")
        assertEquals(closure.toList().sorted(), closure.toList(), "the closure is not in glyph id order")
        // Every glyph pulled in is one of the font's, and every component of
        // everything in the closure is in the closure: the subset is closed.
        for (glyphId in closure) {
            assertTrue(glyphId < sans.numGlyphs, "glyph $glyphId is past the last glyph of the font")
            assertTrue(closure.containsAll(glyf.glyphOf(glyphId).componentGlyphIds), "glyph $glyphId")
        }
    }

    @Test
    fun `the closure of a letter drawn by one glyph is that glyph alone`() {
        val glyf = assertNotNull(sans.glyf)
        val a = sans.glyphIdOf('a'.code)

        assertIs<Glyph.Simple>(glyf.glyphOf(a))
        assertEquals(setOf(a), glyf.componentClosureOf(listOf(a)))
    }

    @Test
    fun `the closure of every glyph of the font is reached without looping`() {
        // "This graph must be acyclic, with every path through the graph leading
        // to a simple glyph as a leaf node." Closing over all 6253 glyphs at once
        // is the whole graph walked, and it terminates.
        val glyf = assertNotNull(sans.glyf)

        val closure = glyf.componentClosureOf(0 until sans.numGlyphs)

        assertEquals(sans.numGlyphs, closure.size)
    }

    @Test
    fun `the bounding box of a glyph lies within the bounding box of the font`() {
        // "The bounding rectangle from each glyph is defined as the rectangle with
        // a lower left corner of (xMin, yMin) and an upper right corner of (xMax,
        // yMax)", and 'head' gives the font's own as the union over its glyphs.
        val glyf = assertNotNull(sans.glyf)
        val simple = assertIs<Glyph.Simple>(glyf.glyphOf(sans.glyphIdOf('W'.code)))

        assertTrue(simple.header.xMin >= sans.head.xMin, "${simple.header} against ${sans.head}")
        assertTrue(simple.header.yMin >= sans.head.yMin, "${simple.header} against ${sans.head}")
        assertTrue(simple.header.xMax <= sans.head.xMax, "${simple.header} against ${sans.head}")
        assertTrue(simple.header.yMax <= sans.head.yMax, "${simple.header} against ${sans.head}")
        assertTrue(simple.header.numberOfContours > 0, "a W of no contours")
    }

    @Test
    fun `a monospaced font locates its glyphs too`() {
        val glyf = assertNotNull(mono.glyf)

        assertEquals(mono.numGlyphs, glyf.numGlyphs)
        assertIs<Glyph.Simple>(glyf.glyphOf(mono.glyphIdOf('A'.code)))
    }

    @Test
    fun `the bounding box of the font contains its glyphs`() {
        assertTrue(sans.head.xMin < 0)
        assertTrue(sans.head.yMin < 0)
        assertTrue(sans.head.xMax > 0)
        assertTrue(sans.head.yMax > sans.hhea.ascender)
    }

    private companion object {
        /** What goes unchecked when the system has no fonts — see [systemFontBytes]. */
        const val WHY: String = "This test checks a parser"
    }
}
