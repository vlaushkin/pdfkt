/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.font.truetype

// OpenType 1.9.1 §"OpenType font file" — "Data Types", "Table Directory";
// §"head", §"hhea", §"maxp", §"hmtx", §"cmap", §"loca", §"glyf"

/**
 * Font bytes written by hand from the field tables of the specification, for the
 * tests to read back.
 *
 * A parser tested only against fonts someone else wrote is tested against
 * whatever those fonts happen to contain. These builders are the other half:
 * they write the layout the specification states, field by field, so a test can
 * put a value in one field and assert what the parser makes of it — a
 * `magicNumber` that is wrong, a `numberOfHMetrics` shorter than the glyph
 * count, the segment arrays of the very example the `cmap` clause works through.
 *
 * They are also deliberately independent of the parser: nothing here calls into
 * the classes under test, and the offsets are written out rather than derived
 * from the constants the parser uses. Two encodings of the same layout that have
 * to agree is the point.
 */
internal class BigEndianBytes {
    private val out = ArrayList<Byte>()

    /** How many bytes have been written. */
    val size: Int get() = out.size

    fun u8(value: Int): BigEndianBytes = apply { out += (value and 0xFF).toByte() }

    fun u16(value: Int): BigEndianBytes = apply {
        u8(value ushr 8)
        u8(value)
    }

    /** `int16`, `FWORD`: written as the two's-complement bytes of [value]. */
    fun i16(value: Int): BigEndianBytes = u16(value and 0xFFFF)

    fun u32(value: Long): BigEndianBytes = apply {
        u16((value ushr 16).toInt() and 0xFFFF)
        u16(value.toInt() and 0xFFFF)
    }

    /** `Tag`: "Array of four uint8s". */
    fun tag(value: String): BigEndianBytes = apply {
        require(value.length == 4) { "a tag is four bytes, not \"$value\"" }
        value.forEach { u8(it.code) }
    }

    fun bytes(value: ByteArray): BigEndianBytes = apply { value.forEach { out += it } }

    /** [count] zero bytes — a field this test does not care about. */
    fun zeros(count: Int): BigEndianBytes = apply { repeat(count) { u8(0) } }

    fun build(): ByteArray = out.toByteArray()
}

internal fun bigEndian(block: BigEndianBytes.() -> Unit): ByteArray = BigEndianBytes().apply(block).build()

/** One `TableRecord`'s worth of a font under construction. */
internal class NamedTable(val tag: String, val bytes: ByteArray)

internal infix fun String.holding(bytes: ByteArray): NamedTable = NamedTable(this, bytes)

/**
 * A font file: a table directory followed by [tables], each aligned to a
 * four-byte boundary as "All tables must begin on four-byte boundaries, and any
 * remaining space between tables must be padded with zeros" requires.
 *
 * [searchRange], [entrySelector] and [rangeShift] default to values that are
 * plainly wrong, because the specification tells parsers not to read them:
 * "it is strongly recommended that parsing implementations not rely on the
 * searchRange, entrySelector and rangeShift fields in the font but derive them
 * independently from numTables". A font this library refuses to open over those
 * three fields would be a font it read something it was told not to.
 */
internal fun trueTypeFontBytes(
    tables: List<NamedTable>,
    sfntVersion: Long = 0x00010000L,
    searchRange: Int = 0xDEAD,
    entrySelector: Int = 0xBEEF,
    rangeShift: Int = 0xF00D,
    numTables: Int = tables.size,
): ByteArray {
    val directorySize = 12 + 16 * tables.size
    val offsets = ArrayList<Int>(tables.size)
    var next = directorySize
    for (table in tables) {
        offsets += next
        next += table.bytes.size
        next += (4 - next % 4) % 4
    }
    return bigEndian {
        u32(sfntVersion)
        u16(numTables)
        u16(searchRange)
        u16(entrySelector)
        u16(rangeShift)
        tables.forEachIndexed { index, table ->
            tag(table.tag)
            u32(0) // checksum — carried, not verified; see TableDirectory
            u32(offsets[index].toLong())
            u32(table.bytes.size.toLong())
        }
        tables.forEachIndexed { index, table ->
            zeros(offsets[index] - size)
            bytes(table.bytes)
        }
    }
}

/** A `head` table, 54 bytes, per its field table. */
internal fun headTableBytes(
    unitsPerEm: Int = 1000,
    indexToLocFormat: Int = 0,
    xMin: Int = -100,
    yMin: Int = -200,
    xMax: Int = 900,
    yMax: Int = 800,
    macStyle: Int = 0,
    flags: Int = 0,
    magicNumber: Long = 0x5F0F3CF5L,
    majorVersion: Int = 1,
): ByteArray = bigEndian {
    u16(majorVersion)
    u16(0) // minorVersion
    u32(0x00010000L) // fontRevision
    u32(0) // checksumAdjustment
    u32(magicNumber)
    u16(flags)
    u16(unitsPerEm)
    u32(0).u32(0) // created — LONGDATETIME
    u32(0).u32(0) // modified — LONGDATETIME
    i16(xMin)
    i16(yMin)
    i16(xMax)
    i16(yMax)
    u16(macStyle)
    u16(8) // lowestRecPPEM
    i16(2) // fontDirectionHint
    i16(indexToLocFormat)
    i16(0) // glyphDataFormat
}

/** An `hhea` table, 36 bytes, per its field table. */
internal fun hheaTableBytes(
    ascender: Int = 800,
    descender: Int = -200,
    lineGap: Int = 100,
    advanceWidthMax: Int = 1000,
    numberOfHMetrics: Int = 1,
    metricDataFormat: Int = 0,
    majorVersion: Int = 1,
): ByteArray = bigEndian {
    u16(majorVersion)
    u16(0) // minorVersion
    i16(ascender)
    i16(descender)
    i16(lineGap)
    u16(advanceWidthMax)
    i16(0) // minLeftSideBearing
    i16(0) // minRightSideBearing
    i16(0) // xMaxExtent
    i16(1) // caretSlopeRise
    i16(0) // caretSlopeRun
    i16(0) // caretOffset
    zeros(8) // four reserved int16 fields
    i16(metricDataFormat)
    u16(numberOfHMetrics)
}

/** A `maxp` table: version 1.0 by default, "which fonts with TrueType outlines must use". */
internal fun maxpTableBytes(numGlyphs: Int, version: Long = 0x00010000L): ByteArray = bigEndian {
    u32(version)
    u16(numGlyphs)
    if (version == 0x00010000L) zeros(26) // the thirteen uint16 fields of version 1.0
}

/**
 * A `post` table of version 3.0, "the version used for OpenType fonts that do
 * not have glyph names" — the header alone, which is all of version 3.0 and all
 * that is read of any version.
 *
 * [italicAngle] is written as the `Fixed` the field is: the degrees multiplied
 * by 2^16.
 */
internal fun postTableBytes(
    italicAngle: Double = 0.0,
    isFixedPitch: Boolean = false,
    version: Long = 0x00030000L,
): ByteArray = bigEndian {
    u32(version)
    u32(((italicAngle * 65536.0).toLong()) and 0xFFFFFFFFL)
    i16(-100) // underlinePosition
    i16(50) // underlineThickness
    u32(if (isFixedPitch) 1L else 0L)
    u32(0).u32(0).u32(0).u32(0) // minMemType42, maxMemType42, minMemType1, maxMemType1
}

/**
 * An `hmtx` table: one `LongHorMetric` per entry of [metrics], then one `FWORD`
 * per entry of [leftSideBearings] for the glyphs past the last record.
 */
internal fun hmtxTableBytes(metrics: List<Pair<Int, Int>>, leftSideBearings: List<Int> = emptyList()): ByteArray =
    bigEndian {
        metrics.forEach { (advanceWidth, lsb) ->
            u16(advanceWidth)
            i16(lsb)
        }
        leftSideBearings.forEach { i16(it) }
    }

/** One segment of a `cmap` format 4 subtable. */
internal class Format4Segment(val startCode: Int, val endCode: Int, val idDelta: Int, val idRangeOffset: Int = 0)

/**
 * A `cmap` format 4 subtable over [segments], with [glyphIdArray] after the
 * `idRangeOffset` array.
 *
 * The final segment ending at 0xFFFF is added if [segments] does not end with
 * one, since "For the search to terminate, the final startCode and endCode
 * values must be 0xFFFF."
 */
internal fun cmapFormat4Bytes(segments: List<Format4Segment>, glyphIdArray: List<Int> = emptyList()): ByteArray {
    val all =
        if (segments.isNotEmpty() && segments.last().endCode == 0xFFFF) {
            segments
        } else {
            segments + Format4Segment(startCode = 0xFFFF, endCode = 0xFFFF, idDelta = 1)
        }
    val length = 16 + 8 * all.size + 2 * glyphIdArray.size
    return bigEndian {
        u16(4) // format
        u16(length)
        u16(0) // language
        u16(2 * all.size) // segCountX2
        u16(0xDEAD) // searchRange — derived by the parser, not read
        u16(0xBEEF) // entrySelector
        u16(0xF00D) // rangeShift
        all.forEach { u16(it.endCode) }
        u16(0) // reservedPad
        all.forEach { u16(it.startCode) }
        all.forEach { i16(it.idDelta) }
        all.forEach { u16(it.idRangeOffset) }
        glyphIdArray.forEach { u16(it) }
    }
}

/**
 * The `idRangeOffset` that makes the segment at [segmentIndex] of a subtable of
 * [segCount] segments read `glyphIdArray[glyphIndex]` for its `startCode`.
 *
 * "This sum is used as an offset from the current location within idRangeOffset
 * itself": `idRangeOffset[i]` sits `2 × i` bytes into the array, `glyphIdArray`
 * starts `2 × segCount` bytes into it, so the distance between them is
 * `2 × (segCount − i + k)`.
 */
internal fun idRangeOffsetTo(segmentIndex: Int, segCount: Int, glyphIndex: Int): Int =
    2 * (segCount - segmentIndex + glyphIndex)

/** One `SequentialMapGroup` of a `cmap` format 12 subtable. */
internal class Format12Group(val startCharCode: Long, val endCharCode: Long, val startGlyphId: Int)

/** A `cmap` format 12 subtable over [groups]. */
internal fun cmapFormat12Bytes(groups: List<Format12Group>): ByteArray = bigEndian {
    u16(12) // format
    u16(0) // reserved
    u32((16 + 12L * groups.size)) // length
    u32(0) // language
    u32(groups.size.toLong()) // numGroups
    groups.forEach {
        u32(it.startCharCode)
        u32(it.endCharCode)
        u32(it.startGlyphId.toLong())
    }
}

/**
 * A `loca` table over [offsets], as `Offset32` entries if [longOffsets] and as
 * `Offset16` ones otherwise.
 *
 * "The local offset divided by 2 is stored" is what makes the short format
 * unable to express an odd offset, so an odd one is refused here rather than
 * halved into a table that locates something else: a font in that format pads
 * its glyph descriptions to even boundaries instead.
 */
internal fun locaTableBytes(offsets: List<Int>, longOffsets: Boolean): ByteArray = bigEndian {
    offsets.forEach { offset ->
        if (longOffsets) {
            u32(offset.toLong())
        } else {
            require(offset % 2 == 0) { "a short 'loca' table cannot hold the odd offset $offset" }
            u16(offset / 2)
        }
    }
}

/**
 * One point of a contour of a simple glyph, "not stored in absolute terms but as
 * a vector relative to the previous point".
 */
internal class GlyphPoint(val dx: Int, val dy: Int, val onCurve: Boolean = true)

/**
 * A simple glyph description: the glyph header, then the `Simple Glyph table` of
 * the clause over [contours].
 *
 * The packed forms the flags allow are deliberately not used — every point gets
 * its own flag byte and a signed 16-bit delta, which is what a flag byte of
 * `ON_CURVE_POINT` alone (neither `X_SHORT_VECTOR` nor
 * `X_IS_SAME_OR_POSITIVE_X_SHORT_VECTOR`) means. The parser under test does not
 * decode coordinates; what these bytes are here for is to be the right *length*,
 * so that a `loca` table built over several glyphs locates each of them where
 * the test says it does.
 */
internal fun simpleGlyphBytes(
    contours: List<List<GlyphPoint>>,
    xMin: Int = 0,
    yMin: Int = 0,
    xMax: Int = 100,
    yMax: Int = 100,
    instructions: ByteArray = ByteArray(0),
    numberOfContours: Int = contours.size,
): ByteArray = bigEndian {
    i16(numberOfContours)
    i16(xMin)
    i16(yMin)
    i16(xMax)
    i16(yMax)
    var lastPoint = -1
    contours.forEach { contour ->
        lastPoint += contour.size
        u16(lastPoint) // endPtsOfContours[]
    }
    u16(instructions.size)
    bytes(instructions)
    val points = contours.flatten()
    points.forEach { u8(if (it.onCurve) 0x01 else 0x00) } // ON_CURVE_POINT, 16-bit deltas
    points.forEach { i16(it.dx) }
    points.forEach { i16(it.dy) }
}

/**
 * One `Component Glyph record` to write: the flags exactly as the test wants
 * them, save for `MORE_COMPONENTS`, which says where the sequence ends and is
 * therefore set by the builder.
 *
 * [transform] is written as raw `F2DOT14` values after the arguments — the test
 * says how many, so that a record whose flags and transform disagree can be
 * built and refused.
 */
internal class ComponentRecord(
    val glyphId: Int,
    val flags: Int = 0x0002, // ARGS_ARE_XY_VALUES
    val argument1: Int = 0,
    val argument2: Int = 0,
    val transform: List<Int> = emptyList(),
)

/**
 * A composite glyph description: the glyph header with a negative
 * `numberOfContours`, then one data block per entry of [components].
 *
 * `MORE_COMPONENTS` (0x0020) is set on every component but the last, since
 * "A flag within the data for each component is used to indicate if there are
 * additional components in the sequence"; `WE_HAVE_INSTRUCTIONS` (0x0100) is set
 * on the last one when there are [instructions], since "If the flag is set on
 * any component glyph, then a uint16 value is read immediately after the last
 * component glyph to get the byte length for instructions".
 */
internal fun compositeGlyphBytes(
    components: List<ComponentRecord>,
    xMin: Int = 0,
    yMin: Int = 0,
    xMax: Int = 100,
    yMax: Int = 100,
    instructions: ByteArray = ByteArray(0),
    numberOfContours: Int = -1,
): ByteArray = bigEndian {
    i16(numberOfContours)
    i16(xMin)
    i16(yMin)
    i16(xMax)
    i16(yMax)
    components.forEachIndexed { index, component ->
        val last = index == components.size - 1
        var flags = component.flags
        if (!last) flags = flags or 0x0020
        if (last && instructions.isNotEmpty()) flags = flags or 0x0100
        u16(flags)
        u16(component.glyphId)
        if (flags and 0x0001 != 0) { // ARG_1_AND_2_ARE_WORDS
            i16(component.argument1)
            i16(component.argument2)
        } else {
            u8(component.argument1 and 0xFF)
            u8(component.argument2 and 0xFF)
        }
        component.transform.forEach { i16(it) }
    }
    if (instructions.isNotEmpty()) {
        u16(instructions.size)
        bytes(instructions)
    }
}

/** A `glyf` table and the `loca` table that locates the glyphs in it. */
internal class GlyfAndLoca(val glyf: ByteArray, val loca: ByteArray)

/**
 * A `glyf` table holding [glyphs] in glyph id order — "glyph data blocks within
 * the 'glyf' table must be in glyph ID order" — and the matching `loca` table of
 * `glyphs.size + 1` offsets.
 *
 * A zero-length entry of [glyphs] is a glyph with no description, which the
 * clause writes as `loca[n] = loca[n+1]`. Descriptions are padded to an even
 * boundary when [longOffsets] is false, because a short `loca` stores half of
 * each offset and cannot express an odd one.
 */
internal fun glyfAndLocaBytes(glyphs: List<ByteArray>, longOffsets: Boolean = true): GlyfAndLoca {
    val offsets = ArrayList<Int>(glyphs.size + 1)
    val glyf = BigEndianBytes()
    glyphs.forEach { glyph ->
        offsets += glyf.size
        glyf.bytes(glyph)
        if (!longOffsets && glyf.size % 2 != 0) glyf.zeros(1)
    }
    offsets += glyf.size
    return GlyfAndLoca(glyf.build(), locaTableBytes(offsets, longOffsets))
}

/** One `EncodingRecord` of a `cmap` header, and the subtable it points at. */
internal class CmapRecord(val platformId: Int, val encodingId: Int, val subtable: ByteArray)

/** A `cmap` table: the header, then [records]' subtables in order. */
internal fun cmapTableBytes(records: List<CmapRecord>, version: Int = 0): ByteArray {
    var next = 4 + 8 * records.size
    val offsets = ArrayList<Int>(records.size)
    for (record in records) {
        offsets += next
        next += record.subtable.size
    }
    return bigEndian {
        u16(version)
        u16(records.size)
        records.forEachIndexed { index, record ->
            u16(record.platformId)
            u16(record.encodingId)
            u32(offsets[index].toLong())
        }
        records.forEach { bytes(it.subtable) }
    }
}

/**
 * A font of the five tables required of every font, enough for
 * [TrueTypeFont.parse] — one segment of `cmap`, three glyphs, unless the caller
 * says otherwise.
 *
 * [glyf] and [loca] are left out unless given: they are required "for fonts with
 * TrueType outlines" rather than of every font, and nothing about measuring text
 * reads them.
 */
internal fun minimalFontBytes(
    head: ByteArray = headTableBytes(),
    hhea: ByteArray = hheaTableBytes(numberOfHMetrics = 2),
    maxp: ByteArray = maxpTableBytes(numGlyphs = 3),
    hmtx: ByteArray = hmtxTableBytes(metrics = listOf(500 to 10, 600 to 20), leftSideBearings = listOf(30)),
    cmap: ByteArray = cmapTableBytes(
        listOf(
            CmapRecord(
                platformId = 3,
                encodingId = 1,
                subtable = cmapFormat4Bytes(
                    listOf(Format4Segment(startCode = 0x41, endCode = 0x42, idDelta = -0x40)),
                ),
            ),
        ),
    ),
    glyf: ByteArray? = null,
    loca: ByteArray? = null,
): ByteArray = trueTypeFontBytes(
    listOfNotNull(
        "cmap" holding cmap,
        "head" holding head,
        "hhea" holding hhea,
        "hmtx" holding hmtx,
        "maxp" holding maxp,
        glyf?.let { "glyf" holding it },
        loca?.let { "loca" holding it },
    ),
)
