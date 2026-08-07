/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.font

import io.pdfkt.core.font.truetype.CmapRecord
import io.pdfkt.core.font.truetype.ComponentRecord
import io.pdfkt.core.font.truetype.Format4Segment
import io.pdfkt.core.font.truetype.GlyphPoint
import io.pdfkt.core.font.truetype.cmapFormat4Bytes
import io.pdfkt.core.font.truetype.cmapTableBytes
import io.pdfkt.core.font.truetype.compositeGlyphBytes
import io.pdfkt.core.font.truetype.glyfAndLocaBytes
import io.pdfkt.core.font.truetype.headTableBytes
import io.pdfkt.core.font.truetype.hheaTableBytes
import io.pdfkt.core.font.truetype.hmtxTableBytes
import io.pdfkt.core.font.truetype.holding
import io.pdfkt.core.font.truetype.maxpTableBytes
import io.pdfkt.core.font.truetype.simpleGlyphBytes
import io.pdfkt.core.font.truetype.trueTypeFontBytes

// OpenType 1.9.1 §"head", §"hhea", §"maxp", §"hmtx", §"cmap", §"loca", §"glyf"
// ISO 32000-2:2020 §9.7 "Composite fonts", §9.9.2 "Font subsets"

/**
 * A font program of six glyphs, written from the OpenType field tables, for the
 * tests of the embedded path to be handed as [FontRef.Supplied] bytes.
 *
 * Six is the smallest number that holds every case the writing has to get right:
 *
 * | Glyph | Character | Why it is there |
 * |---|---|---|
 * | 0 | — | `.notdef`, which "shall be defined in the font subset" (§9.9.2) |
 * | 1 | `A` | a component of glyph 3, and a glyph a document can leave unshown |
 * | 2 | `B` | shown, and renumbered by the subset |
 * | 3 | `C` | a composite, whose components the closure has to pull in |
 * | 4 | `D` | never a component: the glyph a subset must be able to drop |
 * | 5 | `E` | the glyph that lands furthest from its original index |
 *
 * Nothing here reads the classes under test: the tables are written field by
 * field, so a number a test asserts is a number it put in a font program.
 *
 * @param unitsPerEm what the font counts to the em, which is what the conversion
 *   to the glyph space of §9.2.4 divides by.
 * @param macStyle `head`'s style bits; bit 1 is Italic, which Table 121's
 *   `Italic` flag is written from.
 * @param post a `post` table, or none — Table 124 does not require one of an
 *   embedded program, and a font can arrive without it.
 * @param mapsCapitalH whether the character map reaches `H`, which is what
 *   decides whether the font has a cap height to state (Table 120). It selects
 *   glyph 4, the one glyph given a bounding box of its own.
 */
internal fun suppliedFontBytes(
    unitsPerEm: Int = 1000,
    macStyle: Int = 0,
    post: ByteArray? = null,
    mapsCapitalH: Boolean = false,
): ByteArray {
    val glyfAndLoca = glyfAndLocaBytes(SUPPLIED_FONT_OUTLINES, longOffsets = true)
    val segments =
        listOf(Format4Segment(startCode = 0x41, endCode = 0x45, idDelta = -0x40)) +
            if (mapsCapitalH) {
                listOf(
                    Format4Segment(
                        startCode = SUPPLIED_FONT_CAPITAL_H,
                        endCode = SUPPLIED_FONT_CAPITAL_H,
                        idDelta = 4 - SUPPLIED_FONT_CAPITAL_H,
                    ),
                )
            } else {
                emptyList()
            }
    return trueTypeFontBytes(
        listOfNotNull(
            "cmap" holding cmapTableBytes(
                listOf(CmapRecord(platformId = 3, encodingId = 1, subtable = cmapFormat4Bytes(segments))),
            ),
            "glyf" holding glyfAndLoca.glyf,
            "head" holding headTableBytes(unitsPerEm = unitsPerEm, indexToLocFormat = 1, macStyle = macStyle),
            "hhea" holding hheaTableBytes(numberOfHMetrics = 6),
            "hmtx" holding hmtxTableBytes(metrics = SUPPLIED_FONT_METRICS),
            "loca" holding glyfAndLoca.loca,
            "maxp" holding maxpTableBytes(numGlyphs = 6),
            post?.let { "post" holding it },
        ),
    )
}

/** `H`, the glyph a cap height is measured from. */
internal const val SUPPLIED_FONT_CAPITAL_H: Int = 0x48

/** Six distinct advance widths and left side bearings, one pair per glyph. */
internal val SUPPLIED_FONT_METRICS: List<Pair<Int, Int>> =
    listOf(500 to 10, 600 to 20, 700 to 30, 800 to -40, 900 to 50, 1000 to 60)

/**
 * The outlines of the six glyphs. Each simple one has a different number of
 * points, so a description copied from the wrong offset would be a description of
 * the wrong length; glyph 4 carries a bounding box of its own, because it is the
 * glyph a cap height is read off.
 */
internal val SUPPLIED_FONT_OUTLINES: List<ByteArray> = listOf(
    ByteArray(0),
    oneContourGlyph(listOf(GlyphPoint(0, 0), GlyphPoint(40, 80))),
    oneContourGlyph(listOf(GlyphPoint(0, 0), GlyphPoint(10, 20), GlyphPoint(30, 40))),
    compositeGlyphBytes(
        components = listOf(
            ComponentRecord(glyphId = 1, argument1 = 5, argument2 = 6),
            ComponentRecord(glyphId = 5, argument1 = 7, argument2 = 8),
        ),
    ),
    oneContourGlyph(
        listOf(GlyphPoint(1, 1), GlyphPoint(2, 2), GlyphPoint(3, 3), GlyphPoint(4, 4)),
        yMax = 700,
    ),
    oneContourGlyph(listOf(GlyphPoint(3, 3), GlyphPoint(4, 4), GlyphPoint(5, 5))),
)

/** A simple glyph of one contour: the shape all but the composite have here. */
private fun oneContourGlyph(points: List<GlyphPoint>, yMax: Int = 100): ByteArray =
    simpleGlyphBytes(contours = listOf(points), yMax = yMax)
