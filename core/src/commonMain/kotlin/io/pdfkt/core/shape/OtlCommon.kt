/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.shape

import io.pdfkt.core.font.truetype.BigEndianReader
import io.pdfkt.core.font.truetype.TrueTypeFont

// OpenType 1.9.1 §"OpenType Layout common table formats": "Script List Table
// and Script Record", "Language System Table", "Feature List Table", "Lookup
// List Table", "Coverage Table", "Class Definition Table"; §"GDEF — Glyph
// Definition Table".

/**
 * A Coverage table: "the glyphs … a lookup subtable applies to", each with its
 * Coverage Index, "the position of the glyph in the table". Read into arrays
 * once, since a lookup asks it at every glyph of every run.
 */
internal class Coverage(data: BigEndianReader) {
    private val firsts: IntArray
    private val lasts: IntArray
    private val startIndices: IntArray

    init {
        data.seek(0)
        when (val format = data.uint16()) {
            1 -> {
                val count = data.uint16()
                firsts = IntArray(count) { data.uint16() }
                lasts = firsts.copyOf()
                startIndices = IntArray(count) { it }
            }
            2 -> {
                val count = data.uint16()
                firsts = IntArray(count)
                lasts = IntArray(count)
                startIndices = IntArray(count)
                for (i in 0 until count) {
                    firsts[i] = data.uint16()
                    lasts[i] = data.uint16()
                    startIndices[i] = data.uint16()
                }
            }
            else -> throw IllegalArgumentException("a Coverage table of format $format; the formats are 1 and 2")
        }
    }

    /** The Coverage Index of [glyph], or -1 if the table does not cover it. */
    fun indexOf(glyph: Int): Int {
        var low = 0
        var high = firsts.size - 1
        while (low <= high) {
            val middle = (low + high) ushr 1
            when {
                glyph < firsts[middle] -> high = middle - 1
                glyph > lasts[middle] -> low = middle + 1
                else -> return startIndices[middle] + glyph - firsts[middle]
            }
        }
        return -1
    }

    operator fun contains(glyph: Int): Boolean = indexOf(glyph) >= 0
}

/**
 * A Class Definition table: "Glyphs not assigned to a class fall into class 0."
 */
internal class ClassDef(data: BigEndianReader) {
    private val firsts: IntArray
    private val lasts: IntArray
    private val classes: IntArray

    init {
        data.seek(0)
        when (val format = data.uint16()) {
            1 -> {
                val start = data.uint16()
                val count = data.uint16()
                firsts = IntArray(count) { start + it }
                lasts = firsts.copyOf()
                classes = IntArray(count) { data.uint16() }
            }
            2 -> {
                val count = data.uint16()
                firsts = IntArray(count)
                lasts = IntArray(count)
                classes = IntArray(count)
                for (i in 0 until count) {
                    firsts[i] = data.uint16()
                    lasts[i] = data.uint16()
                    classes[i] = data.uint16()
                }
            }
            else -> throw IllegalArgumentException("a ClassDef table of format $format; the formats are 1 and 2")
        }
    }

    operator fun get(glyph: Int): Int {
        var low = 0
        var high = firsts.size - 1
        while (low <= high) {
            val middle = (low + high) ushr 1
            when {
                glyph < firsts[middle] -> high = middle - 1
                glyph > lasts[middle] -> low = middle + 1
                else -> return classes[middle]
            }
        }
        return 0
    }
}

/**
 * The glyph classes of `GDEF`: "1 Base glyph (single character, spacing
 * glyph), 2 Ligature glyph (multiple character, spacing glyph), 3 Mark glyph
 * (non-spacing combining glyph), 4 Component glyph (part of single character,
 * spacing glyph)", the mark attachment classes, and the mark glyph sets.
 */
internal class GdefTable(data: BigEndianReader) {
    private val glyphClasses: ClassDef?
    private val markAttachClasses: ClassDef?
    private val markGlyphSets: List<Coverage>

    init {
        data.seek(0)
        val major = data.uint16()
        val minor = data.uint16()
        require(major == 1) { "a GDEF table of version $major.$minor" }
        val glyphClassDef = data.uint16()
        data.uint16() // attachListOffset
        data.uint16() // ligCaretListOffset
        val markAttachClassDef = data.uint16()
        val markGlyphSetsDef = if (minor >= 2) data.uint16() else 0
        glyphClasses = glyphClassDef.takeIf { it != 0 }?.let { ClassDef(data.sliceFrom(it)) }
        markAttachClasses = markAttachClassDef.takeIf { it != 0 }?.let { ClassDef(data.sliceFrom(it)) }
        markGlyphSets = if (markGlyphSetsDef == 0) {
            emptyList()
        } else {
            data.seek(markGlyphSetsDef)
            data.uint16() // format
            val count = data.uint16()
            val offsets = (0 until count).map { data.uint32().toInt() }
            offsets.map { Coverage(data.sliceFrom(markGlyphSetsDef + it)) }
        }
    }

    val hasGlyphClasses: Boolean get() = glyphClasses != null

    fun glyphClass(glyph: Int): Int = glyphClasses?.get(glyph) ?: 0

    fun markAttachClass(glyph: Int): Int = markAttachClasses?.get(glyph) ?: 0

    fun inMarkGlyphSet(set: Int, glyph: Int): Boolean = markGlyphSets.getOrNull(set)?.contains(glyph) ?: false

    companion object {
        const val BASE: Int = 1
        const val LIGATURE: Int = 2
        const val MARK: Int = 3
        const val COMPONENT: Int = 4
    }
}

/** One lookup of a lookup list: "LookupType, LookupFlag, … subtables". */
internal class Lookup(val type: Int, val flag: Int, val subtables: List<BigEndianReader>, val markFilteringSet: Int) {
    val rightToLeft: Boolean get() = flag and RIGHT_TO_LEFT != 0

    companion object {
        const val RIGHT_TO_LEFT: Int = 0x0001
        const val IGNORE_BASE_GLYPHS: Int = 0x0002
        const val IGNORE_LIGATURES: Int = 0x0004
        const val IGNORE_MARKS: Int = 0x0008
        const val USE_MARK_FILTERING_SET: Int = 0x0010
        const val MARK_ATTACHMENT_TYPE_MASK: Int = 0xFF00
    }
}

/**
 * The header shared by `GSUB` and `GPOS` and the three lists under it, read as
 * far as choosing lookups requires: "the Script List … the Feature List … the
 * Lookup List".
 *
 * @param extensionType the lookup type of the format's Extension lookup — 7 in
 *   `GSUB`, 9 in `GPOS` — whose subtables "point to subtables of another type",
 *   resolved here so nothing downstream sees one.
 */
internal class LayoutTable(private val data: BigEndianReader, private val extensionType: Int) {
    private val scriptList: Int
    private val featureList: Int
    private val lookupList: Int

    init {
        data.seek(0)
        val major = data.uint16()
        data.uint16() // minor
        require(major == 1) { "a ${data.what} of major version $major" }
        scriptList = data.uint16()
        featureList = data.uint16()
        lookupList = data.uint16()
    }

    private class Feature(val tag: String, val lookups: IntArray)

    private val features: List<Feature> by lazy {
        data.seek(featureList)
        val count = data.uint16()
        val records = (0 until count).map { data.tag() to data.uint16() }
        records.map { (tag, offset) ->
            data.seek(featureList + offset)
            data.uint16() // featureParamsOffset
            val lookupCount = data.uint16()
            Feature(tag, IntArray(lookupCount) { data.uint16() })
        }
    }

    private val lookupOffsets: IntArray by lazy {
        data.seek(lookupList)
        val count = data.uint16()
        IntArray(count) { data.uint16() }
    }

    private val lookups = HashMap<Int, Lookup>()

    /** The coverage and class tables the lookups of this table have parsed. */
    val caches: LayoutCaches = LayoutCaches()

    /** The script tags the table has a Script table for. */
    val scriptTags: List<String> by lazy {
        data.seek(scriptList)
        val count = data.uint16()
        (0 until count).map { data.tag().also { data.uint16() } }
    }

    /**
     * The feature indices of the language system chosen for [scripts] — the
     * first of them the table has, then `DFLT`, then `latn` — or an empty
     * list for a table that has none of them. Its default language system:
     * "If a Script table … has no LangSys … the DefaultLangSys … is used", and
     * a document states no language.
     */
    fun featureIndicesFor(scripts: List<String>): List<Int> {
        data.seek(scriptList)
        val count = data.uint16()
        val records = (0 until count).associate { data.tag() to data.uint16() }
        val chosen = (scripts + DEFAULT_SCRIPT + LATIN).firstOrNull { it in records } ?: return emptyList()
        val script = scriptList + records.getValue(chosen)
        data.seek(script)
        val defaultLangSys = data.uint16()
        if (defaultLangSys == 0) return emptyList()
        data.seek(script + defaultLangSys)
        data.uint16() // lookupOrderOffset
        val required = data.uint16()
        val featureCount = data.uint16()
        val indices = (0 until featureCount).map { data.uint16() }
        return if (required != NO_REQUIRED_FEATURE) listOf(required) + indices else indices
    }

    /** Whether [scripts] chooses a Script table of their own, not a fallback. */
    fun hasScript(scripts: List<String>): Boolean = scripts.any { it in scriptTags }

    /** The lookup indices the feature [tag] has among [featureIndices]. */
    fun lookupsOf(featureIndices: List<Int>, tag: String): List<Int> =
        featureIndices.mapNotNull { features.getOrNull(it) }.filter { it.tag == tag }.flatMap { it.lookups.toList() }

    val lookupCount: Int get() = lookupOffsets.size

    /** The lookup [index], with Extension subtables resolved to what they extend. */
    fun lookup(index: Int): Lookup = lookups.getOrPut(index) {
        val offset = lookupList + lookupOffsets[index]
        data.seek(offset)
        val declared = data.uint16()
        var type = declared
        val flag = data.uint16()
        val count = data.uint16()
        val subtableOffsets = (0 until count).map { data.uint16() }
        val markFilteringSet = if (flag and Lookup.USE_MARK_FILTERING_SET != 0) data.uint16() else 0
        val subtables = subtableOffsets.map { sub ->
            val subtable = data.sliceFrom(offset + sub)
            if (declared == extensionType) {
                subtable.seek(0)
                subtable.uint16() // format
                val extended = subtable.uint16()
                val extensionOffset = subtable.uint32().toInt()
                type = extended
                subtable.sliceFrom(extensionOffset)
            } else {
                subtable
            }
        }
        Lookup(type, flag, subtables, markFilteringSet)
    }

    companion object {
        const val DEFAULT_SCRIPT: String = "DFLT"
        const val LATIN: String = "latn"
        const val NO_REQUIRED_FEATURE: Int = 0xFFFF
    }
}

/** A window from [offset] to the end of this one: an offset field's target, whose length the format does not state. */
internal fun BigEndianReader.sliceFrom(offset: Int): BigEndianReader = sliceAt(offset, size - offset, what)

/** What shaping needs of [font]: its layout tables, its character map and its advances. */
internal fun layoutFontOf(font: TrueTypeFont): LayoutFont {
    val directory = font.directory
    return LayoutFont(
        gsub = directory.readerOrNull("GSUB")?.let { LayoutTable(it, extensionType = 7) },
        gpos = directory.readerOrNull("GPOS")?.let { LayoutTable(it, extensionType = 9) },
        gdef = directory.readerOrNull("GDEF")?.let { GdefTable(it) },
        glyphOf = { codePoint -> font.glyphIdOf(codePoint) },
        advanceOf = { glyph -> font.advanceWidthOf(glyph) },
    )
}
