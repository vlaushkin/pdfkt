/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.shape

// No clause applies: the buffer is this library's own design. What it records
// is what the OpenType Layout lookups read and write — glyph ids, the glyph
// classes of GDEF, ligature components for mark-to-ligature attachment, and
// the positioning values of GPOS.

/**
 * One glyph of a run being shaped.
 *
 * @property cluster the index in the run's text of the first character this
 *   glyph stands for. Glyphs that stand for one stretch of text together —
 *   a ligature, a reordered syllable — share the lowest index of it.
 */
internal class ShapingGlyph(var id: Int, var cluster: Int, var codePoint: Int) {
    /** Which features apply to this glyph: one bit per feature the plan allotted one. */
    var mask: Long = 0

    /** The GDEF class: [GdefTable.BASE], [GdefTable.LIGATURE], [GdefTable.MARK], [GdefTable.COMPONENT], or 0. */
    var glyphClass: Int = 0
    var markAttachClass: Int = 0

    /** A ligature this glyph is, or a mark that sits on one of its components: an id shared by both. */
    var ligatureId: Int = 0

    /** For a mark on a ligature, the component it follows, from 1; 0 otherwise. */
    var ligatureComponent: Int = 0

    /** How many components a ligature glyph was formed from. */
    var ligatureComponents: Int = 1

    /** Whether a substitution produced this glyph. */
    var substituted: Boolean = false

    /** Whether a ligature substitution produced this glyph. */
    var ligated: Boolean = false

    /** Whether a multiple substitution produced this glyph. */
    var multiplied: Boolean = false

    var xAdvance: Int = 0
    var yAdvance: Int = 0
    var xOffset: Int = 0
    var yOffset: Int = 0

    /** For an attached glyph, how far back (negative) or forward its anchor glyph is; 0 for none. */
    var attachChain: Int = 0
    var attachType: Int = 0

    // The Indic shaper's analysis of the character.
    var category: Int = 0
    var position: Int = 0
    var syllable: Int = 0

    val isMark: Boolean get() = glyphClass == GdefTable.MARK
    val isLigature: Boolean get() = glyphClass == GdefTable.LIGATURE
    val isBase: Boolean get() = glyphClass == GdefTable.BASE || glyphClass == 0

    fun copy(): ShapingGlyph = ShapingGlyph(id, cluster, codePoint).also {
        it.mask = mask
        it.glyphClass = glyphClass
        it.markAttachClass = markAttachClass
        it.ligatureId = ligatureId
        it.ligatureComponent = ligatureComponent
        it.ligatureComponents = ligatureComponents
        it.substituted = substituted
        it.ligated = ligated
        it.multiplied = multiplied
        it.category = category
        it.position = position
        it.syllable = syllable
    }

    override fun toString(): String = "g$id@$cluster"

    companion object {
        const val ATTACH_MARK: Int = 1
        const val ATTACH_CURSIVE: Int = 2
    }
}

/**
 * A run being shaped: its glyphs, in logical order until positioning ends, and
 * the text they came from.
 */
internal class GlyphBuffer(val glyphs: MutableList<ShapingGlyph>, val rightToLeft: Boolean) {
    val size: Int get() = glyphs.size

    operator fun get(index: Int): ShapingGlyph = glyphs[index]

    /** The kind of each syllable, for a shaper that finds syllables, by syllable number from 1. */
    var syllableKinds: IntArray = IntArray(0)

    private var nextLigatureId = 1

    fun newLigatureId(): Int = nextLigatureId++.also { if (nextLigatureId > 0xFFFF) nextLigatureId = 1 }

    /**
     * Gives the glyphs [start] until [end] one cluster, the lowest of theirs,
     * and every neighbour that shared a cluster with one of them the same —
     * so a stretch of text keeps being drawn by one contiguous run of glyphs.
     */
    fun mergeClusters(start: Int, end: Int) {
        if (end - start < 2) return
        var low = start
        var high = end
        val cluster = (start until end).minOf { glyphs[it].cluster }
        val first = glyphs[start].cluster
        val last = glyphs[end - 1].cluster
        while (low > 0 && glyphs[low - 1].cluster == first) low--
        while (high < size && glyphs[high].cluster == last) high++
        for (i in low until high) glyphs[i].cluster = cluster
    }
}
