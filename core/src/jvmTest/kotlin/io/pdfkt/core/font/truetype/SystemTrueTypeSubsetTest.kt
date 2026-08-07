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
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// OpenType 1.9.1 §"OpenType font file" — "Calculating Checksums";
// §"glyf", §"loca", §"hmtx", §"head", §"maxp"
// ISO 32000-2:2020 §9.9 "Embedded font programs" (Table 124)

/**
 * The subsetter against font programs it did not write: the system's DejaVu
 * fonts (Debian's `fonts-dejavu-core`).
 *
 * The synthetic tests in `commonTest` cut up a font this repository built out of
 * the field tables, six glyphs at a time. They cannot say what happens to the
 * shapes a type designer's tools actually emit — composites nested two deep,
 * glyphs with hinting instructions in them, a `glyf` table far too large for the
 * short `loca` format. This file cuts up 6253 real glyphs and reads every one of
 * them back.
 *
 * No font binary is committed; the fonts are read from the system, and a missing
 * one fails rather than skips.
 */
class SystemTrueTypeSubsetTest {
    private val sans = TrueTypeFont.parse(systemFontBytes("DejaVuSans.ttf", WHY))
    private val mono = TrueTypeFont.parse(systemFontBytes("DejaVuSansMono.ttf", WHY))

    /** The glyphs of a line a document might actually draw. */
    private val drawn: List<Int> = "Привет, мир! The quick brown fox — 0123456789"
        .map { sans.glyphIdOf(it.code) }
        .distinct()

    @Test
    fun `a subset of the glyphs a line draws parses back as a font program`() {
        val subset = TrueTypeSubset.of(sans, drawn)

        val embedded = TrueTypeFont.parse(subset.bytes)

        assertEquals(subset.numGlyphs, embedded.numGlyphs)
        assertEquals(sans.unitsPerEm, embedded.unitsPerEm)
        assertEquals(sans.hhea.ascender, embedded.hhea.ascender)
        assertEquals(sans.hhea.descender, embedded.hhea.descender)
        assertTrue(subset.numGlyphs >= drawn.size, "${subset.numGlyphs} glyphs for ${drawn.size} drawn")
        // Every glyph the line draws is in the subset, and every glyph in the
        // subset is one of the font's.
        for (glyphId in drawn) assertTrue(glyphId in subset, "the glyph $glyphId of the line was dropped")
        for (glyphId in subset.glyphIds) assertTrue(glyphId < sans.numGlyphs, "glyph $glyphId")
    }

    @Test
    fun `every glyph kept is the font's own outline under a new number`() {
        val glyf = assertNotNull(sans.glyf)
        val subset = TrueTypeSubset.of(sans, drawn)

        val embedded = TrueTypeFont.parse(subset.bytes)
        val embeddedGlyf = assertNotNull(embedded.glyf)

        for (glyphId in subset.glyphIds) {
            val subsetId = subset.subsetGlyphIdOf(glyphId)
            assertEquals(sans.advanceWidthOf(glyphId), embedded.advanceWidthOf(subsetId), "glyph $glyphId")
            assertEquals(sans.leftSideBearingOf(glyphId), embedded.leftSideBearingOf(subsetId), "glyph $glyphId")
            val original = glyf.readerOrNullOf(glyphId)
            val copied = embeddedGlyf.readerOrNullOf(subsetId)
            if (original == null) {
                assertNull(copied, "glyph $glyphId has an outline it did not have")
                continue
            }
            val bytes = assertNotNull(copied, "glyph $glyphId lost its outline").toByteArray()
            val header = glyf.glyphOf(glyphId).headerOrNull()
            assertEquals(header, embeddedGlyf.glyphOf(subsetId).headerOrNull(), "glyph $glyphId")
            // A simple glyph is copied byte for byte; a composite differs only in
            // the two bytes of each component's glyphIndex.
            if (glyf.glyphOf(glyphId) is Glyph.Simple) {
                assertContentEquals(original.toByteArray(), bytes.copyOf(original.size), "glyph $glyphId")
            }
        }
    }

    @Test
    fun `a composite keeps its components and they are the subset's own glyphs`() {
        // й is и with a breve over it. Both travel into the subset, and the
        // component records of й point at where they landed in it.
        val glyf = assertNotNull(sans.glyf)
        val shortI = sans.glyphIdOf('й'.code)
        val components = assertIs<Glyph.Composite>(glyf.glyphOf(shortI)).componentGlyphIds

        val subset = TrueTypeSubset.of(sans, listOf(shortI))
        val embedded = TrueTypeFont.parse(subset.bytes)
        val embeddedGlyf = assertNotNull(embedded.glyf)

        assertTrue(components.isNotEmpty(), "й is a composite of nothing")
        for (component in components) assertTrue(component in subset, "the component $component was dropped")
        assertEquals(
            components.map { subset.subsetGlyphIdOf(it) },
            assertIs<Glyph.Composite>(embeddedGlyf.glyphOf(subset.subsetGlyphIdOf(shortI))).componentGlyphIds,
        )
        // Nothing in the subset references a glyph outside it: the closure of the
        // renumbered font over all of its glyphs is all of its glyphs.
        assertEquals(
            (0 until embedded.numGlyphs).toSet(),
            embeddedGlyf.componentClosureOf(0 until embedded.numGlyphs),
        )
    }

    @Test
    fun `the size of a subset scales with the number of glyphs kept`() {
        val sizes = listOf(1, 10, 100, 1000, sans.numGlyphs).map { count ->
            TrueTypeSubset.of(sans, 0 until count).bytes.size
        }

        assertEquals(sizes.sorted(), sizes, "the subsets do not grow with the glyphs kept: $sizes")
        // A line's worth of glyphs is a fraction of the font it came from, which
        // is the whole reason for subsetting.
        val line = TrueTypeSubset.of(sans, drawn).bytes.size
        val whole = systemFontBytes("DejaVuSans.ttf", WHY).size
        assertTrue(line * 10 < whole, "$line bytes of subset against $whole bytes of font")
    }

    @Test
    fun `a subset of the whole font is the whole font renumbered by nothing`() {
        val subset = TrueTypeSubset.of(sans, 0 until sans.numGlyphs)

        val embedded = TrueTypeFont.parse(subset.bytes)

        assertEquals(sans.numGlyphs, embedded.numGlyphs)
        for (glyphId in 0 until sans.numGlyphs) {
            assertEquals(glyphId, subset.subsetGlyphIdOf(glyphId))
            assertEquals(sans.advanceWidthOf(glyphId), embedded.advanceWidthOf(glyphId), "glyph $glyphId")
        }
        // "The format is specified by the indexToLocFormat entry in the 'head'
        // table": 6253 glyphs of outlines are far past what the short format's
        // halved uint16 offsets reach, so the subsetter had to pick the long one.
        assertTrue(embedded.head.hasLongLocaOffsets, "a whole font in the short 'loca' format")
        assertEquals(1, embedded.head.indexToLocFormat)
    }

    @Test
    fun `a small subset of a font in the long loca format comes out in the short one`() {
        assertTrue(sans.head.hasLongLocaOffsets, "DejaVu Sans is not in the long 'loca' format")

        val subset = TrueTypeSubset.of(sans, drawn)
        val embedded = TrueTypeFont.parse(subset.bytes)

        assertEquals(0, embedded.head.indexToLocFormat)
        assertEquals(subset.numGlyphs, embedded.numGlyphs)
    }

    @Test
    fun `the checksums of a subset of a real font add up to what the clause requires`() {
        // Every table's recorded checksum is the sum over its bytes, and the sum
        // over the whole font is 0xB1B0AFBA because the 'head' table's
        // checksumAdjustment was set to the difference. 'head' itself is left out
        // of the first check for the reason the clause gives — its own checksum is
        // "necessarily invalidated" by the adjustment written after it — and is
        // checked as the clause tells a verifier to, in the commonTest of this
        // package.
        for (subset in listOf(TrueTypeSubset.of(sans, drawn), TrueTypeSubset.of(mono, 0 until mono.numGlyphs))) {
            val directory = TableDirectory.parse(BigEndianReader.over(subset.bytes, "the subset"))
            for (record in directory.tables.filter { it.tag != "head" }) {
                assertEquals(
                    checksumFromTheClause(subset.bytes, record.offset, record.length),
                    record.checksum,
                    "the recorded checksum of the '${record.tag}' table of $subset",
                )
            }

            assertEquals(0xB1B0AFBAL, checksumFromTheClause(subset.bytes, 0, subset.bytes.size), "$subset")
        }
    }

    @Test
    fun `the tables a real font's instructions need travel with the subset`() {
        // "The 'cvt ' (notice the trailing SPACE), 'fpgm', and 'prep' tables shall
        // also be included if they are required by the font instructions." DejaVu
        // is hinted, so all three are there, and the glyph descriptions copied into
        // the subset carry instructions that call into them.
        val subset = TrueTypeSubset.of(sans, drawn)

        val directory = TableDirectory.parse(BigEndianReader.over(subset.bytes, "the subset"))

        assertEquals(
            listOf("cvt ", "fpgm", "glyf", "head", "hhea", "hmtx", "loca", "maxp", "prep"),
            directory.tables.map { it.tag },
        )
        for (tag in TrueTypeSubset.INSTRUCTION_TABLES) {
            assertContentEquals(
                sans.directory.readerOf(tag).toByteArray(),
                directory.readerOf(tag).toByteArray(),
                "the '$tag' table of the font was not carried across unchanged",
            )
        }
    }

    @Test
    fun `a monospaced font keeps its one advance width through a subset`() {
        // Four 'hmtx' records for 3377 glyphs in the font; the subset writes one
        // record per glyph, and every one of them has to come out at the width the
        // last record of the original stood for.
        val advance = mono.hhea.advanceWidthMax
        val glyphs = "ABCабв".map { mono.glyphIdOf(it.code) }

        val subset = TrueTypeSubset.of(mono, glyphs)
        val embedded = TrueTypeFont.parse(subset.bytes)

        assertEquals(4, mono.hhea.numberOfHMetrics)
        assertEquals(embedded.numGlyphs, embedded.hhea.numberOfHMetrics)
        for (glyphId in glyphs) assertEquals(advance, embedded.advanceWidthOf(subset.subsetGlyphIdOf(glyphId)))
    }

    /** The bounding box of a glyph, or null for one with no description. */
    private fun Glyph.headerOrNull(): List<Int>? = when (this) {
        is Glyph.Simple -> header.box()
        is Glyph.Composite -> header.box()
        Glyph.Blank -> null
    }

    private fun GlyphHeader.box(): List<Int> = listOf(numberOfContours, xMin, yMin, xMax, yMax)

    /**
     * The checksum, written from the clause a second time — see the note on the
     * function of the same name in `TrueTypeSubsetTest`.
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

    private companion object {
        /** What goes unchecked when the system has no fonts — see [systemFontBytes]. */
        const val WHY: String = "This test cuts up a subsetter"
    }
}
