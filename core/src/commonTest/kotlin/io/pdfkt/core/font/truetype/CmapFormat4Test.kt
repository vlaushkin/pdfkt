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

// OpenType 1.9.1 §"cmap" — "Format 4: Segment mapping to delta values"

/**
 * `cmap` format 4, against the worked example the clause itself gives.
 *
 * The clause builds a subtable mapping "characters 10-20, 30-90, and 153-480
 * onto a contiguous range of glyph indices", prints its four arrays, and then
 * prints the mappings it yields. That example is the first test here, verbatim:
 * it fixes the segment search, the delta arithmetic and the modulo rule in one
 * set of numbers that came from the specification rather than from this parser.
 *
 * The rest covers what the example does not — the `idRangeOffset` indexing
 * trick, the missing-glyph rule inside it, and the wrap the clause mentions
 * twice ("If the result … is less than zero, add 65536 to obtain a valid glyph
 * ID").
 */
class CmapFormat4Test {
    private fun subtableOf(bytes: ByteArray): CmapFormat4Subtable =
        CmapFormat4Subtable.parse(BigEndianReader.over(bytes, "a 'cmap' format 4 subtable"))

    /**
     * > endCode: 20 90 480 0xffff
     * > startCode: 10 30 153 0xffff
     * > idDelta: -9 -18 -80 1
     * > idRangeOffset: 0 0 0 0
     */
    private val exampleFromTheClause = subtableOf(
        cmapFormat4Bytes(
            listOf(
                Format4Segment(startCode = 10, endCode = 20, idDelta = -9),
                Format4Segment(startCode = 30, endCode = 90, idDelta = -18),
                Format4Segment(startCode = 153, endCode = 480, idDelta = -80),
                Format4Segment(startCode = 0xFFFF, endCode = 0xFFFF, idDelta = 1),
            ),
        ),
    )

    @Test
    fun `the mappings the clause prints for its own example`() {
        // "10 ⇒ 10 - 9 = 1  20 ⇒ 20 - 9 = 11  30 ⇒ 30 - 18 = 12
        //  90 ⇒ 90 - 18 = 72  153 ⇒ 153 - 80 = 73  480 ⇒ 480 - 80 = 400
        //  0xffff ⇒ 0"
        assertEquals(1, exampleFromTheClause.glyphIdOf(10))
        assertEquals(11, exampleFromTheClause.glyphIdOf(20))
        assertEquals(12, exampleFromTheClause.glyphIdOf(30))
        assertEquals(72, exampleFromTheClause.glyphIdOf(90))
        assertEquals(73, exampleFromTheClause.glyphIdOf(153))
        assertEquals(400, exampleFromTheClause.glyphIdOf(480))
        assertEquals(0, exampleFromTheClause.glyphIdOf(0xFFFF))
    }

    @Test
    fun `a code between two segments is the missing glyph`() {
        // "If the corresponding startCode is less than or equal to the character
        // code, then you use the corresponding idDelta and idRangeOffset …
        // otherwise, the missingGlyph is returned."
        assertEquals(0, exampleFromTheClause.glyphIdOf(9))
        assertEquals(0, exampleFromTheClause.glyphIdOf(21))
        assertEquals(0, exampleFromTheClause.glyphIdOf(25))
        assertEquals(0, exampleFromTheClause.glyphIdOf(152))
        assertEquals(0, exampleFromTheClause.glyphIdOf(481))
    }

    @Test
    fun `every code of a segment maps in step with the segment`() {
        for (code in 30..90) {
            assertEquals(code - 18, exampleFromTheClause.glyphIdOf(code), "code $code")
        }
    }

    @Test
    fun `a non zero idRangeOffset reads the glyph out of the glyph id array`() {
        // Two segments, the second being the required 0xFFFF terminator, so the
        // distance from idRangeOffset[0] to glyphIdArray[0] is 2 × (2 − 0 + 0).
        val subtable = subtableOf(
            cmapFormat4Bytes(
                segments = listOf(
                    Format4Segment(
                        startCode = 0x41,
                        endCode = 0x43,
                        idDelta = 0,
                        idRangeOffset = idRangeOffsetTo(segmentIndex = 0, segCount = 2, glyphIndex = 0),
                    ),
                    Format4Segment(startCode = 0xFFFF, endCode = 0xFFFF, idDelta = 1),
                ),
                glyphIdArray = listOf(5, 0, 7),
            ),
        )

        assertEquals(5, subtable.glyphIdOf(0x41))
        assertEquals(7, subtable.glyphIdOf(0x43))
    }

    @Test
    fun `a zero in the glyph id array is the missing glyph and the delta is not added`() {
        // "If the value obtained from the indexing operation is not 0 (which
        // indicates missingGlyph), idDelta[i] is added to it."
        val subtable = subtableOf(
            cmapFormat4Bytes(
                segments = listOf(
                    Format4Segment(
                        startCode = 0x41,
                        endCode = 0x43,
                        idDelta = 3,
                        idRangeOffset = idRangeOffsetTo(segmentIndex = 0, segCount = 2, glyphIndex = 0),
                    ),
                    Format4Segment(startCode = 0xFFFF, endCode = 0xFFFF, idDelta = 1),
                ),
                glyphIdArray = listOf(5, 0, 7),
            ),
        )

        assertEquals(8, subtable.glyphIdOf(0x41))
        assertEquals(0, subtable.glyphIdOf(0x42))
        assertEquals(10, subtable.glyphIdOf(0x43))
    }

    @Test
    fun `the delta added to a code wraps modulo 65536`() {
        // "If the result after adding idDelta[i] + c is less than zero, add 65536
        // to obtain a valid glyph ID."
        val subtable = subtableOf(
            cmapFormat4Bytes(listOf(Format4Segment(startCode = 0x41, endCode = 0x41, idDelta = -32768))),
        )

        assertEquals(32833, subtable.glyphIdOf(0x41))
    }

    @Test
    fun `the delta added to a glyph from the array wraps modulo 65536`() {
        val subtable = subtableOf(
            cmapFormat4Bytes(
                segments = listOf(
                    Format4Segment(
                        startCode = 0x41,
                        endCode = 0x41,
                        idDelta = 2,
                        idRangeOffset = idRangeOffsetTo(segmentIndex = 0, segCount = 2, glyphIndex = 0),
                    ),
                    Format4Segment(startCode = 0xFFFF, endCode = 0xFFFF, idDelta = 1),
                ),
                glyphIdArray = listOf(0xFFFF),
            ),
        )

        assertEquals(1, subtable.glyphIdOf(0x41))
    }

    @Test
    fun `nothing above the basic multilingual plane is mapped`() {
        // "the standard character-to-glyph-index mapping subtable for fonts that
        // support only Unicode Basic Multilingual Plane characters (U+0000 to
        // U+FFFF)"
        assertEquals(0, exampleFromTheClause.glyphIdOf(0x10000))
        assertEquals(0, exampleFromTheClause.glyphIdOf(0x1F600))
    }

    @Test
    fun `a subtable that does not end at 0xFFFF is refused`() {
        // "For the search to terminate, the final startCode and endCode values
        // must be 0xFFFF."
        val bytes = cmapFormat4Bytes(listOf(Format4Segment(startCode = 10, endCode = 20, idDelta = -9)))
        val truncated = bytes.copyOf()
        // Overwrite the last endCode — the second of two, at 14 + 2.
        truncated[16] = 0x00
        truncated[17] = 0x20

        val refused = assertFailsWith<IllegalArgumentException> { subtableOf(truncated) }

        assertTrue("0xffff" in refused.message.orEmpty(), refused.message)
    }

    @Test
    fun `segments out of order are refused`() {
        val refused = assertFailsWith<IllegalArgumentException> {
            subtableOf(
                cmapFormat4Bytes(
                    listOf(
                        Format4Segment(startCode = 100, endCode = 200, idDelta = 0),
                        Format4Segment(startCode = 10, endCode = 20, idDelta = 0),
                        Format4Segment(startCode = 0xFFFF, endCode = 0xFFFF, idDelta = 1),
                    ),
                ),
            )
        }

        assertTrue("sorted" in refused.message.orEmpty(), refused.message)
    }

    @Test
    fun `a segment that runs backwards is refused`() {
        val refused = assertFailsWith<IllegalArgumentException> {
            subtableOf(
                cmapFormat4Bytes(
                    listOf(
                        Format4Segment(startCode = 200, endCode = 100, idDelta = 0),
                        Format4Segment(startCode = 0xFFFF, endCode = 0xFFFF, idDelta = 1),
                    ),
                ),
            )
        }

        assertTrue("backwards" in refused.message.orEmpty(), refused.message)
    }

    @Test
    fun `a subtable shorter than the segments it announces is refused`() {
        val bytes = cmapFormat4Bytes(listOf(Format4Segment(startCode = 10, endCode = 20, idDelta = -9)))
        val broken = bytes.copyOf()
        // segCountX2 lives at offset 6; claim far more segments than are there.
        broken[6] = 0x01
        broken[7] = 0x00

        val refused = assertFailsWith<IllegalArgumentException> { subtableOf(broken) }

        assertTrue("too few" in refused.message.orEmpty(), refused.message)
    }
}
