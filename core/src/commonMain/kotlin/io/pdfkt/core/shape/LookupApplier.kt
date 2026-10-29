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

// OpenType 1.9.1 §"GSUB — Glyph Substitution Table" (LookupTypes 1–8),
// §"GPOS — Glyph Positioning Table" (LookupTypes 1–9), §"OpenType Layout
// common table formats" — "Sequence Context Format 1, 2, 3", "Chained Sequence
// Context Format 1, 2, 3", "Sequence Lookup Record"; "Lookup Table" (LookupFlag).

/** What a shaped font provides the lookups: its tables and its glyphs. */
internal class LayoutFont(
    val gsub: LayoutTable?,
    val gpos: LayoutTable?,
    val gdef: GdefTable?,
    val glyphOf: (Int) -> Int,
    val advanceOf: (Int) -> Int,
)

/**
 * One lookup as a plan applies it: which glyphs it applies to ([mask]), and
 * how its matching treats syllables and joiners.
 *
 * @property perSyllable whether a match may not leave the syllable it starts in
 *   — what the Indic shaper's basic features are applied with, so a conjunct
 *   is never formed across two syllables.
 * @property manualZwj whether a ZERO WIDTH JOINER is part of what the lookup
 *   matches rather than skipped — the joiner "requests" a form, and the
 *   features that read it as a request have to see it.
 */
internal class PlannedLookup(
    val index: Int,
    val mask: Long,
    val perSyllable: Boolean = false,
    val manualZwj: Boolean = false,
)

/**
 * Applies the lookups of a `GSUB` or `GPOS` table to a [GlyphBuffer].
 */
internal class LookupApplier(
    private val font: LayoutFont,
    private val table: LayoutTable,
    private val buffer: GlyphBuffer,
    private val substituting: Boolean,
) {
    private var current: PlannedLookup = PlannedLookup(0, ALL)
    private var lookup: Lookup = Lookup(0, 0, emptyList(), 0)
    private var depth = 0

    fun apply(planned: PlannedLookup) {
        current = planned
        lookup = table.lookup(planned.index)
        if (substituting && lookup.type == GSUB_REVERSE_CHAINING) {
            var i = buffer.size - 1
            while (i >= 0) {
                val glyph = buffer[i]
                if (glyph.mask and planned.mask != 0L && !ignoredByFlags(glyph, lookup)) {
                    lookup.subtables.firstOrNull { reverseChainingSingle(it, i) }
                }
                i--
            }
            return
        }
        var i = 0
        while (i < buffer.size) {
            val glyph = buffer[i]
            if (glyph.mask and planned.mask != 0L && !ignoredByFlags(glyph, lookup)) {
                val next = applyAt(lookup, i)
                i = if (next >= 0) next else i + 1
            } else {
                i++
            }
        }
    }

    /** Applies the first subtable of [lookup] that applies at [i]; the index after what it consumed, or -1. */
    private fun applyAt(lookup: Lookup, i: Int): Int {
        for (subtable in lookup.subtables) {
            val next = if (substituting) substitute(lookup.type, subtable, i) else position(lookup.type, subtable, i)
            if (next >= 0) return next
        }
        return -1
    }

    // ---------------------------------------------------------------- matching

    /** "LookupFlag … specifies … which glyphs the lookup skips." */
    private fun ignoredByFlags(glyph: ShapingGlyph, lookup: Lookup): Boolean {
        val flag = lookup.flag
        when (glyph.glyphClass) {
            GdefTable.BASE -> if (flag and Lookup.IGNORE_BASE_GLYPHS != 0) return true
            GdefTable.LIGATURE -> if (flag and Lookup.IGNORE_LIGATURES != 0) return true
            GdefTable.MARK -> {
                if (flag and Lookup.IGNORE_MARKS != 0) return true
                if (flag and Lookup.USE_MARK_FILTERING_SET != 0) {
                    if (font.gdef?.inMarkGlyphSet(lookup.markFilteringSet, glyph.id) != true) return true
                } else {
                    val type = (flag and Lookup.MARK_ATTACHMENT_TYPE_MASK) ushr 8
                    if (type != 0 && glyph.markAttachClass != type) return true
                }
            }
        }
        return false
    }

    /**
     * Whether matching passes over [glyph] — a glyph the lookup ignores, or a
     * joiner the lookup does not read. A ZERO WIDTH NON-JOINER is passed over
     * only by positioning: in substitution it is what stops a ligature or a
     * conjunct, which is the reason it is in the text.
     */
    private fun skippable(glyph: ShapingGlyph, context: Boolean): Boolean {
        if (ignoredByFlags(glyph, lookup)) return true
        return when (glyph.codePoint) {
            ZWJ -> !substituting || context || !current.manualZwj
            ZWNJ -> !substituting
            else -> false
        }
    }

    /** The next glyph after [from] that matching reaches, or -1. */
    private fun next(from: Int, context: Boolean): Int {
        var i = from + 1
        while (i < buffer.size) {
            val glyph = buffer[i]
            if (current.perSyllable && glyph.syllable != buffer[from].syllable) return -1
            if (!skippable(glyph, context)) {
                if (!context && glyph.mask and current.mask == 0L) return -1
                return i
            }
            i++
        }
        return -1
    }

    /** The glyph before [from] that matching reaches, or -1. */
    private fun previous(from: Int): Int {
        var i = from - 1
        while (i >= 0) {
            val glyph = buffer[i]
            if (current.perSyllable && glyph.syllable != buffer[from].syllable) return -1
            if (!skippable(glyph, true)) return i
            i--
        }
        return -1
    }

    /**
     * The glyph before [from] that a mark-to-mark attachment considers: the
     * lookup's mark filtering applies, but not its flags that ignore whole
     * classes — the mark attaches to the mark right before it, or to nothing.
     */
    private fun previousMark(from: Int): Int {
        val saved = lookup
        lookup = Lookup(saved.type, saved.flag and IGNORE_CLASSES.inv(), saved.subtables, saved.markFilteringSet)
        val found = previous(from)
        lookup = saved
        return found
    }

    /**
     * The positions of an input sequence starting at [start], whose
     * remaining [count] glyphs each satisfy [matches], or null if it does not
     * match.
     */
    private fun matchInput(start: Int, count: Int, matches: (Int, ShapingGlyph) -> Boolean): IntArray? {
        val positions = IntArray(count + 1)
        positions[0] = start
        var at = start
        for (k in 1..count) {
            at = next(at, context = false)
            if (at < 0 || !matches(k, buffer[at])) return null
            positions[k] = at
        }
        return positions
    }

    private fun matchBacktrack(start: Int, count: Int, matches: (Int, ShapingGlyph) -> Boolean): Boolean {
        var at = start
        for (k in 0 until count) {
            at = previous(at)
            if (at < 0 || !matches(k, buffer[at])) return false
        }
        return true
    }

    private fun matchLookahead(end: Int, count: Int, matches: (Int, ShapingGlyph) -> Boolean): Boolean {
        var at = end
        for (k in 0 until count) {
            at = next(at, context = true)
            if (at < 0 || !matches(k, buffer[at])) return false
        }
        return true
    }

    // ------------------------------------------------------------ substitution

    private fun substitute(type: Int, data: BigEndianReader, i: Int): Int = when (type) {
        1 -> singleSubstitution(data, i)
        2 -> multipleSubstitution(data, i)
        3 -> alternateSubstitution(data, i)
        4 -> ligatureSubstitution(data, i)
        5 -> sequenceContext(data, i)
        6 -> chainedSequenceContext(data, i)
        8 -> if (reverseChainingSingle(data, i)) i + 1 else -1
        else -> -1
    }

    private fun singleSubstitution(data: BigEndianReader, i: Int): Int {
        val glyph = buffer[i]
        data.seek(0)
        val format = data.uint16()
        val coverage = coverage(data, data.uint16())
        val index = coverage.indexOf(glyph.id)
        if (index < 0) return -1
        val substitute = when (format) {
            1 -> (glyph.id + data.int16()) and 0xFFFF
            2 -> {
                val count = data.uint16()
                if (index >= count) return -1
                data.uint16At(6 + index * 2)
            }
            else -> return -1
        }
        replace(glyph, substitute)
        return i + 1
    }

    private fun multipleSubstitution(data: BigEndianReader, i: Int): Int {
        val glyph = buffer[i]
        data.seek(0)
        data.uint16() // format
        val index = coverage(data, data.uint16()).indexOf(glyph.id)
        if (index < 0) return -1
        val count = data.uint16()
        if (index >= count) return -1
        val sequence = data.uint16At(6 + index * 2)
        data.seek(sequence)
        val glyphCount = data.uint16()
        val ids = IntArray(glyphCount) { data.uint16() }
        if (ids.size == 1) {
            replace(glyph, ids[0])
            return i + 1
        }
        // "the GlyphCount … must always be greater than 0", but fonts delete with an empty sequence.
        if (ids.isEmpty()) {
            buffer.glyphs.removeAt(i)
            return i
        }
        val copies = ids.mapIndexed { k, id ->
            glyph.copy().also {
                replace(it, id)
                it.multiplied = true
                it.ligatureComponent = k + 1
                if (k > 0) it.text = ""
            }
        }
        buffer.glyphs.removeAt(i)
        buffer.glyphs.addAll(i, copies)
        return i + ids.size
    }

    /** The first alternate: nothing in this library selects among them, and "aalt" is not applied. */
    private fun alternateSubstitution(data: BigEndianReader, i: Int): Int {
        val glyph = buffer[i]
        data.seek(0)
        data.uint16()
        val index = coverage(data, data.uint16()).indexOf(glyph.id)
        if (index < 0) return -1
        val set = data.uint16At(6 + index * 2)
        if (data.uint16At(set) == 0) return -1
        replace(glyph, data.uint16At(set + 2))
        return i + 1
    }

    private fun ligatureSubstitution(data: BigEndianReader, i: Int): Int {
        val first = buffer[i]
        data.seek(0)
        data.uint16()
        val index = coverage(data, data.uint16()).indexOf(first.id)
        if (index < 0) return -1
        val setCount = data.uint16()
        if (index >= setCount) return -1
        val set = data.uint16At(6 + index * 2)
        val ligatureCount = data.uint16At(set)
        for (l in 0 until ligatureCount) {
            val ligature = set + data.uint16At(set + 2 + l * 2)
            data.seek(ligature)
            val ligatureGlyph = data.uint16()
            val componentCount = data.uint16()
            val components = IntArray(componentCount - 1) { data.uint16() }
            val positions = matchInput(i, components.size) { k, glyph -> glyph.id == components[k - 1] } ?: continue
            formLigature(positions, ligatureGlyph)
            return i + 1
        }
        return -1
    }

    /**
     * Replaces the glyphs at [positions] with [ligatureGlyph] at the first of
     * them. The marks between the components stay where they are and are
     * told which component they followed, which is what mark-to-ligature
     * attachment places them by.
     */
    private fun formLigature(positions: IntArray, ligatureGlyph: Int) {
        val start = positions[0]
        val end = positions.last() + 1
        buffer.mergeClusters(start, end)
        val first = buffer[start]
        val allMarks = positions.all { buffer[it].isMark }
        val id = if (allMarks) 0 else buffer.newLigatureId()
        var component = 0
        var p = 0
        for (k in start until end) {
            if (p < positions.size && positions[p] == k) {
                component++
                p++
            } else if (buffer[k].isMark && id != 0) {
                buffer[k].ligatureId = id
                buffer[k].ligatureComponent = component
            }
        }
        first.text = positions.joinToString("") { buffer[it].text }
        replace(first, ligatureGlyph)
        first.ligated = true
        first.ligatureId = id
        first.ligatureComponents = positions.size
        if (font.gdef?.hasGlyphClasses != true) first.glyphClass = if (allMarks) GdefTable.MARK else GdefTable.LIGATURE
        for (k in positions.size - 1 downTo 1) buffer.glyphs.removeAt(positions[k])
    }

    private fun reverseChainingSingle(data: BigEndianReader, i: Int): Boolean {
        val glyph = buffer[i]
        data.seek(0)
        data.uint16()
        val coverage = coverage(data, data.uint16())
        val index = coverage.indexOf(glyph.id)
        if (index < 0) return false
        val backtrack = coverages(data)
        val lookahead = coverages(data)
        val count = data.uint16()
        if (index >= count) return false
        val substitute = data.uint16At(data.position + index * 2)
        if (!matchBacktrack(i, backtrack.size) { k, g -> g.id in backtrack[k] }) return false
        if (!matchLookahead(i, lookahead.size) { k, g -> g.id in lookahead[k] }) return false
        replace(glyph, substitute)
        return true
    }

    private fun replace(glyph: ShapingGlyph, id: Int) {
        glyph.id = id
        glyph.substituted = true
        val gdef = font.gdef
        if (gdef != null && gdef.hasGlyphClasses) {
            glyph.glyphClass = gdef.glyphClass(id)
            glyph.markAttachClass = gdef.markAttachClass(id)
        }
    }

    // --------------------------------------------------------------- contexts

    /**
     * Sequence Context, formats 1–3: an input sequence, then the lookups its
     * Sequence Lookup Records name, at the positions they name.
     */
    private fun sequenceContext(data: BigEndianReader, i: Int): Int {
        val glyph = buffer[i]
        data.seek(0)
        when (data.uint16()) {
            1 -> {
                val index = coverage(data, data.uint16()).indexOf(glyph.id)
                if (index < 0) return -1
                val count = data.uint16()
                if (index >= count) return -1
                val set = data.uint16At(6 + index * 2)
                if (set == 0) return -1
                return firstRule(data, set) { rule ->
                    data.seek(rule)
                    val glyphCount = data.uint16()
                    val records = data.uint16()
                    val input = IntArray(glyphCount - 1) { data.uint16() }
                    val positions = matchInput(i, input.size) { k, g -> g.id == input[k - 1] } ?: return@firstRule -1
                    applyRecords(positions, lookupRecords(data, records))
                }
            }
            2 -> {
                val coverage = coverage(data, data.uint16())
                if (glyph.id !in coverage) return -1
                val classes = classDef(data, data.uint16())
                val count = data.uint16()
                val cls = classes[glyph.id]
                if (cls >= count) return -1
                val set = data.uint16At(8 + cls * 2)
                if (set == 0) return -1
                return firstRule(data, set) { rule ->
                    data.seek(rule)
                    val glyphCount = data.uint16()
                    val records = data.uint16()
                    val input = IntArray(glyphCount - 1) { data.uint16() }
                    val positions = matchInput(i, input.size) { k, g -> classes[g.id] == input[k - 1] }
                        ?: return@firstRule -1
                    applyRecords(positions, lookupRecords(data, records))
                }
            }
            3 -> {
                val glyphCount = data.uint16()
                val records = data.uint16()
                val coverageOffsets = IntArray(glyphCount) { data.uint16() }
                val lookupRecords = lookupRecords(data, records)
                if (glyph.id !in coverage(data, coverageOffsets[0])) return -1
                val positions = matchInput(i, glyphCount - 1) { k, g -> g.id in coverage(data, coverageOffsets[k]) }
                    ?: return -1
                return applyRecords(positions, lookupRecords)
            }
            else -> return -1
        }
    }

    private fun chainedSequenceContext(data: BigEndianReader, i: Int): Int {
        val glyph = buffer[i]
        data.seek(0)
        when (data.uint16()) {
            1 -> {
                val index = coverage(data, data.uint16()).indexOf(glyph.id)
                if (index < 0) return -1
                val count = data.uint16()
                if (index >= count) return -1
                val set = data.uint16At(6 + index * 2)
                if (set == 0) return -1
                return firstRule(data, set) { rule ->
                    data.seek(rule)
                    val backtrack = IntArray(data.uint16()) { data.uint16() }
                    val input = IntArray(data.uint16() - 1) { data.uint16() }
                    val lookahead = IntArray(data.uint16()) { data.uint16() }
                    val records = lookupRecords(data, data.uint16())
                    chained(
                        i,
                        backtrack.size,
                        { k, g -> g.id == backtrack[k] },
                        input.size,
                        { k, g -> g.id == input[k - 1] },
                        lookahead.size,
                        { k, g -> g.id == lookahead[k] },
                        records,
                    )
                }
            }
            2 -> {
                val coverage = coverage(data, data.uint16())
                if (glyph.id !in coverage) return -1
                val backtrackClasses = classDef(data, data.uint16())
                val inputClasses = classDef(data, data.uint16())
                val lookaheadClasses = classDef(data, data.uint16())
                val count = data.uint16()
                val cls = inputClasses[glyph.id]
                if (cls >= count) return -1
                val set = data.uint16At(12 + cls * 2)
                if (set == 0) return -1
                return firstRule(data, set) { rule ->
                    data.seek(rule)
                    val backtrack = IntArray(data.uint16()) { data.uint16() }
                    val input = IntArray(data.uint16() - 1) { data.uint16() }
                    val lookahead = IntArray(data.uint16()) { data.uint16() }
                    val records = lookupRecords(data, data.uint16())
                    chained(
                        i,
                        backtrack.size,
                        { k, g -> backtrackClasses[g.id] == backtrack[k] },
                        input.size,
                        { k, g -> inputClasses[g.id] == input[k - 1] },
                        lookahead.size,
                        { k, g -> lookaheadClasses[g.id] == lookahead[k] },
                        records,
                    )
                }
            }
            3 -> {
                val backtrack = coverages(data)
                val input = coverages(data)
                val lookahead = coverages(data)
                val records = lookupRecords(data, data.uint16())
                if (input.isEmpty() || glyph.id !in input[0]) return -1
                return chained(
                    i,
                    backtrack.size,
                    { k, g -> g.id in backtrack[k] },
                    input.size - 1,
                    { k, g -> g.id in input[k] },
                    lookahead.size,
                    { k, g -> g.id in lookahead[k] },
                    records,
                )
            }
            else -> return -1
        }
    }

    @Suppress("LongParameterList")
    private fun chained(
        i: Int,
        backtrackCount: Int,
        backtrack: (Int, ShapingGlyph) -> Boolean,
        inputCount: Int,
        input: (Int, ShapingGlyph) -> Boolean,
        lookaheadCount: Int,
        lookahead: (Int, ShapingGlyph) -> Boolean,
        records: List<Pair<Int, Int>>,
    ): Int {
        val positions = matchInput(i, inputCount, input) ?: return -1
        if (!matchBacktrack(i, backtrackCount, backtrack)) return -1
        if (!matchLookahead(positions.last(), lookaheadCount, lookahead)) return -1
        return applyRecords(positions, records)
    }

    /** Tries each rule of the rule set at [set] in order; the result of the first that matched, or -1. */
    private fun firstRule(data: BigEndianReader, set: Int, rule: (Int) -> Int): Int {
        val count = data.uint16At(set)
        val offsets = IntArray(count) { data.uint16At(set + 2 + it * 2) }
        for (offset in offsets) {
            val result = rule(set + offset)
            if (result >= 0) return result
        }
        return -1
    }

    private fun lookupRecords(data: BigEndianReader, count: Int): List<Pair<Int, Int>> =
        (0 until count).map { data.uint16() to data.uint16() }

    /**
     * Applies the lookups of a matched rule — "the lookups … are applied in
     * the order listed" — at the input positions they name. A nested lookup
     * that changes the number of glyphs moves the positions after it, so
     * they are shifted as it does.
     *
     * @return the index after the matched input, where application resumes.
     */
    private fun applyRecords(matched: IntArray, records: List<Pair<Int, Int>>): Int {
        val positions = matched.toMutableList()
        var end = matched.last() + 1
        if (depth >= MAX_NESTING) return end
        for ((sequenceIndex, lookupIndex) in records) {
            if (sequenceIndex >= positions.size) continue
            val at = positions[sequenceIndex]
            if (at >= buffer.size) continue
            val before = buffer.size
            val nested = table.lookup(lookupIndex)
            if (ignoredByFlags(buffer[at], nested)) continue
            val savedLookup = lookup
            depth++
            lookup = nested
            applyAt(nested, at)
            lookup = savedLookup
            depth--
            var delta = buffer.size - before
            if (delta == 0) continue
            end += delta
            if (end < at) {
                delta += at - end
                end = at
            }
            var next = sequenceIndex + 1
            if (delta > 0) {
                repeat(delta) { positions.add(next, 0) }
                next += delta
            } else {
                delta = maxOf(delta, next - positions.size)
                repeat(-delta) { if (next < positions.size) positions.removeAt(next) }
            }
            for (j in sequenceIndex + 1 until minOf(next, positions.size)) positions[j] = positions[j - 1] + 1
            for (j in next until positions.size) positions[j] += delta
        }
        return minOf(end, buffer.size).coerceAtLeast(matched[0] + 1)
    }

    // ------------------------------------------------------------- positioning

    private fun position(type: Int, data: BigEndianReader, i: Int): Int = when (type) {
        1 -> singleAdjustment(data, i)
        2 -> pairAdjustment(data, i)
        3 -> cursiveAttachment(data, i)
        4 -> markToBase(data, i)
        5 -> markToLigature(data, i)
        6 -> markToMark(data, i)
        7 -> sequenceContext(data, i)
        8 -> chainedSequenceContext(data, i)
        else -> -1
    }

    private fun singleAdjustment(data: BigEndianReader, i: Int): Int {
        val glyph = buffer[i]
        data.seek(0)
        val format = data.uint16()
        val index = coverage(data, data.uint16()).indexOf(glyph.id)
        if (index < 0) return -1
        val valueFormat = data.uint16()
        when (format) {
            1 -> applyValue(data, data.position, valueFormat, glyph)
            2 -> {
                val count = data.uint16()
                if (index >= count) return -1
                applyValue(data, data.position + index * valueSize(valueFormat), valueFormat, glyph)
            }
            else -> return -1
        }
        return i + 1
    }

    private fun pairAdjustment(data: BigEndianReader, i: Int): Int {
        val first = buffer[i]
        data.seek(0)
        val format = data.uint16()
        val index = coverage(data, data.uint16()).indexOf(first.id)
        if (index < 0) return -1
        val format1 = data.uint16()
        val format2 = data.uint16()
        val j = next(i, context = false)
        if (j < 0) return -1
        val second = buffer[j]
        val size1 = valueSize(format1)
        val size2 = valueSize(format2)
        when (format) {
            1 -> {
                val setCount = data.uint16()
                if (index >= setCount) return -1
                val set = data.uint16At(10 + index * 2)
                val pairCount = data.uint16At(set)
                val record = 2 + size1 + size2
                var low = 0
                var high = pairCount - 1
                while (low <= high) {
                    val middle = (low + high) ushr 1
                    val at = set + 2 + middle * record
                    val glyph = data.uint16At(at)
                    when {
                        second.id < glyph -> high = middle - 1
                        second.id > glyph -> low = middle + 1
                        else -> {
                            applyValue(data, at + 2, format1, first)
                            applyValue(data, at + 2 + size1, format2, second)
                            return if (format2 != 0) j + 1 else j
                        }
                    }
                }
                return -1
            }
            2 -> {
                val classes1 = classDef(data, data.uint16())
                val classes2 = classDef(data, data.uint16())
                val class1Count = data.uint16()
                val class2Count = data.uint16()
                val c1 = classes1[first.id]
                val c2 = classes2[second.id]
                if (c1 >= class1Count || c2 >= class2Count) return -1
                val record = 16 + (c1 * class2Count + c2) * (size1 + size2)
                applyValue(data, record, format1, first)
                applyValue(data, record + size1, format2, second)
                return if (format2 != 0) j + 1 else j
            }
            else -> return -1
        }
    }

    private fun cursiveAttachment(data: BigEndianReader, i: Int): Int {
        val glyph = buffer[i]
        data.seek(0)
        data.uint16()
        val coverage = coverage(data, data.uint16())
        val count = data.uint16()
        val index = coverage.indexOf(glyph.id)
        if (index < 0 || index >= count) return -1
        val entry = data.uint16At(6 + index * 4)
        if (entry == 0) return -1
        val j = previous(i)
        if (j < 0) return -1
        val prevIndex = coverage.indexOf(buffer[j].id)
        if (prevIndex < 0 || prevIndex >= count) return -1
        val exit = data.uint16At(6 + prevIndex * 4 + 2)
        if (exit == 0) return -1
        val (entryX, entryY) = anchor(data, entry)
        val (exitX, exitY) = anchor(data, exit)
        val prev = buffer[j]
        if (!buffer.rightToLeft) {
            prev.xAdvance = exitX + prev.xOffset
            val d = entryX + glyph.xOffset
            glyph.xAdvance -= d
            glyph.xOffset -= d
        } else {
            val d = exitX + prev.xOffset
            prev.xAdvance -= d
            prev.xOffset -= d
            glyph.xAdvance = entryX + glyph.xOffset
        }
        var child = j
        var parent = i
        var yOffset = entryY - exitY
        if (!lookup.rightToLeft) {
            child = i
            parent = j
            yOffset = -yOffset
        }
        // A chain that already runs the other way is cut, so attachment has no cycle.
        if (buffer[parent].attachChain == child - parent) buffer[parent].attachChain = 0
        buffer[child].attachType = ShapingGlyph.ATTACH_CURSIVE
        buffer[child].attachChain = parent - child
        buffer[child].yOffset = yOffset
        return i + 1
    }

    private fun markToBase(data: BigEndianReader, i: Int): Int {
        val mark = buffer[i]
        data.seek(0)
        data.uint16()
        val markIndex = coverage(data, data.uint16()).indexOf(mark.id)
        if (markIndex < 0) return -1
        val baseCoverage = coverage(data, data.uint16())
        val classCount = data.uint16()
        val markArray = data.uint16()
        val baseArray = data.uint16()
        // The base: the glyph before the mark that is not a mark.
        var j = i - 1
        while (j >= 0 && buffer[j].isMark) j--
        if (j < 0) return -1
        val baseIndex = baseCoverage.indexOf(buffer[j].id)
        if (baseIndex < 0) return -1
        val (markClass, markAnchor) = markRecord(data, markArray, markIndex)
        if (markClass >= classCount) return -1
        val baseAnchor = data.uint16At(baseArray + 2 + (baseIndex * classCount + markClass) * 2)
        if (baseAnchor == 0) return -1
        attach(mark, i, j, anchor(data, baseArray + baseAnchor), anchor(data, markArray + markAnchor))
        return i + 1
    }

    private fun markToLigature(data: BigEndianReader, i: Int): Int {
        val mark = buffer[i]
        data.seek(0)
        data.uint16()
        val markIndex = coverage(data, data.uint16()).indexOf(mark.id)
        if (markIndex < 0) return -1
        val ligatureCoverage = coverage(data, data.uint16())
        val classCount = data.uint16()
        val markArray = data.uint16()
        val ligatureArray = data.uint16()
        var j = i - 1
        while (j >= 0 && buffer[j].isMark) j--
        if (j < 0) return -1
        val ligature = buffer[j]
        val ligatureIndex = ligatureCoverage.indexOf(ligature.id)
        if (ligatureIndex < 0) return -1
        val (markClass, markAnchor) = markRecord(data, markArray, markIndex)
        if (markClass >= classCount) return -1
        val attach = ligatureArray + data.uint16At(ligatureArray + 2 + ligatureIndex * 2)
        val componentCount = data.uint16At(attach)
        if (componentCount == 0) return -1
        val component = if (ligature.ligatureId != 0 &&
            mark.ligatureId == ligature.ligatureId &&
            mark.ligatureComponent > 0
        ) {
            minOf(componentCount, mark.ligatureComponent) - 1
        } else {
            componentCount - 1
        }
        val ligatureAnchor = data.uint16At(attach + 2 + (component * classCount + markClass) * 2)
        if (ligatureAnchor == 0) return -1
        attach(mark, i, j, anchor(data, attach + ligatureAnchor), anchor(data, markArray + markAnchor))
        return i + 1
    }

    private fun markToMark(data: BigEndianReader, i: Int): Int {
        val mark = buffer[i]
        data.seek(0)
        data.uint16()
        val markIndex = coverage(data, data.uint16()).indexOf(mark.id)
        if (markIndex < 0) return -1
        val mark2Coverage = coverage(data, data.uint16())
        val classCount = data.uint16()
        val markArray = data.uint16()
        val mark2Array = data.uint16()
        val j = previousMark(i)
        if (j < 0) return -1
        val mark2 = buffer[j]
        if (!mark2.isMark) return -1
        val sameBase = mark.ligatureId == mark2.ligatureId &&
            (mark.ligatureId == 0 || mark.ligatureComponent == mark2.ligatureComponent)
        val ligatureMark = (mark.ligatureId > 0 && mark.ligatureComponent == 0) ||
            (mark2.ligatureId > 0 && mark2.ligatureComponent == 0)
        if (!sameBase && !ligatureMark) return -1
        val mark2Index = mark2Coverage.indexOf(mark2.id)
        if (mark2Index < 0) return -1
        val (markClass, markAnchor) = markRecord(data, markArray, markIndex)
        if (markClass >= classCount) return -1
        val mark2Anchor = data.uint16At(mark2Array + 2 + (mark2Index * classCount + markClass) * 2)
        if (mark2Anchor == 0) return -1
        attach(mark, i, j, anchor(data, mark2Array + mark2Anchor), anchor(data, markArray + markAnchor))
        return i + 1
    }

    private fun attach(mark: ShapingGlyph, i: Int, j: Int, target: Pair<Int, Int>, own: Pair<Int, Int>) {
        mark.xOffset = target.first - own.first
        mark.yOffset = target.second - own.second
        mark.attachType = ShapingGlyph.ATTACH_MARK
        mark.attachChain = j - i
    }

    private fun markRecord(data: BigEndianReader, markArray: Int, index: Int): Pair<Int, Int> {
        val at = markArray + 2 + index * 4
        return data.uint16At(at) to data.uint16At(at + 2)
    }

    /** An Anchor table, formats 1–3: the coordinates, without the contour point or device adjustments. */
    private fun anchor(data: BigEndianReader, offset: Int): Pair<Int, Int> =
        data.uint16At(offset + 2).toShort().toInt() to data.uint16At(offset + 4).toShort().toInt()

    private fun applyValue(data: BigEndianReader, offset: Int, format: Int, glyph: ShapingGlyph) {
        var at = offset
        fun field(): Int = data.uint16At(at).toShort().toInt().also { at += 2 }
        if (format and 0x01 != 0) glyph.xOffset += field()
        if (format and 0x02 != 0) glyph.yOffset += field()
        if (format and 0x04 != 0) glyph.xAdvance += field()
        if (format and 0x08 != 0) glyph.yAdvance += field()
    }

    private fun valueSize(format: Int): Int = (format and 0xFF).countOneBits() * 2

    // ---------------------------------------------------------------- caches

    private fun coverage(data: BigEndianReader, offset: Int): Coverage =
        coverageCache.getOrPut(data to offset) { Coverage(data.sliceFrom(offset)) }

    private fun classDef(data: BigEndianReader, offset: Int): ClassDef =
        if (offset == 0) EMPTY_CLASS_DEF else classCache.getOrPut(data to offset) { ClassDef(data.sliceFrom(offset)) }

    /** A count and that many coverage offsets, read at the cursor. */
    private fun coverages(data: BigEndianReader): List<Coverage> {
        val count = data.uint16()
        val offsets = IntArray(count) { data.uint16() }
        val after = data.position
        return offsets.map { coverage(data, it) }.also { data.seek(after) }
    }

    private val coverageCache: MutableMap<Pair<BigEndianReader, Int>, Coverage> get() = caches.coverages
    private val classCache: MutableMap<Pair<BigEndianReader, Int>, ClassDef> get() = caches.classes
    private val caches: LayoutCaches get() = table.caches

    companion object {
        const val ALL: Long = -1L
        const val ZWJ: Int = 0x200D
        const val ZWNJ: Int = 0x200C
        const val GSUB_REVERSE_CHAINING: Int = 8
        const val MAX_NESTING: Int = 8
        const val IGNORE_CLASSES: Int = Lookup.IGNORE_BASE_GLYPHS or Lookup.IGNORE_LIGATURES or Lookup.IGNORE_MARKS

        private val EMPTY_CLASS_DEF: ClassDef = ClassDef(
            BigEndianReader.over(byteArrayOf(0, 2, 0, 0), "an empty ClassDef"),
        )
    }
}

/** The parsed coverage and class tables of one layout table, kept for the life of its font. */
internal class LayoutCaches {
    val coverages: MutableMap<Pair<BigEndianReader, Int>, Coverage> = HashMap()
    val classes: MutableMap<Pair<BigEndianReader, Int>, ClassDef> = HashMap()
}
