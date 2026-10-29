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

// OpenType 1.9.1 §"GSUB" LookupTypes 1, 4, 5 and 6 — read only far enough to
// say whether a lookup would replace a given sequence of glyphs.

/**
 * Whether a `GSUB` lookup would replace exactly a given sequence of glyphs,
 * asked without a buffer: the question a shaper puts to a font to learn which
 * forms it has before it decides how to order a syllable.
 */
internal object LookupProbe {
    fun wouldApply(table: LayoutTable, lookup: Lookup, glyphs: IntArray): Boolean =
        lookup.subtables.any { subtable -> applies(table, lookup.type, subtable, glyphs) }

    private fun applies(table: LayoutTable, type: Int, data: BigEndianReader, glyphs: IntArray): Boolean {
        data.seek(0)
        val format = data.uint16()
        if ((type == 5 || type == 6) && format == 3) return context(table, data, format, glyphs, chained = type == 6)
        val coverage = Coverage(data.sliceFrom(data.uint16()))
        if (glyphs[0] !in coverage) return false
        return when (type) {
            1 -> glyphs.size == 1
            4 -> ligature(data, coverage.indexOf(glyphs[0]), glyphs)
            5 -> context(table, data, format, glyphs, chained = false)
            6 -> context(table, data, format, glyphs, chained = true)
            else -> false
        }
    }

    private fun ligature(data: BigEndianReader, index: Int, glyphs: IntArray): Boolean {
        if (index >= data.uint16At(4)) return false
        val set = data.uint16At(6 + index * 2)
        val count = data.uint16At(set)
        for (l in 0 until count) {
            val ligature = set + data.uint16At(set + 2 + l * 2)
            val components = data.uint16At(ligature + 2)
            if (components != glyphs.size) continue
            if ((1 until components).all { data.uint16At(ligature + 2 + it * 2) == glyphs[it] }) return true
        }
        return false
    }

    /** A rule of a context subtable whose input is exactly [glyphs] and which has no backtrack or lookahead. */
    private fun context(
        table: LayoutTable,
        data: BigEndianReader,
        format: Int,
        glyphs: IntArray,
        chained: Boolean,
    ): Boolean {
        when (format) {
            1, 2 -> {
                val classes = if (format == 2) {
                    val offsetAt = if (chained) 6 else 4
                    ClassDef(data.sliceFrom(data.uint16At(offsetAt)))
                } else {
                    null
                }
                val countAt = when {
                    format == 1 -> 4
                    chained -> 10
                    else -> 6
                }
                val key = classes?.get(glyphs[0]) ?: Coverage(data.sliceFrom(data.uint16At(2))).indexOf(glyphs[0])
                if (key >= data.uint16At(countAt)) return false
                val set = data.uint16At(countAt + 2 + key * 2)
                if (set == 0) return false
                val rules = data.uint16At(set)
                for (r in 0 until rules) {
                    var at = set + data.uint16At(set + 2 + r * 2)
                    if (chained) {
                        if (data.uint16At(at) != 0) continue
                        at += 2
                    }
                    val inputCount = data.uint16At(at)
                    if (inputCount != glyphs.size) continue
                    if (!chained) at += 2 // seqLookupCount comes before the input in a Sequence Rule
                    val input = (1 until inputCount).map { data.uint16At(at + 2 + (it - 1) * 2) }
                    val matches = input.withIndex().all { (k, value) ->
                        if (classes != null) classes[glyphs[k + 1]] == value else glyphs[k + 1] == value
                    }
                    if (!matches) continue
                    if (chained && data.uint16At(at + 2 + (inputCount - 1) * 2) != 0) continue
                    return true
                }
                return false
            }
            3 -> {
                if (chained) {
                    if (data.uint16At(2) != 0) return false
                    val inputCount = data.uint16At(4)
                    if (inputCount != glyphs.size) return false
                    val lookaheadAt = 6 + inputCount * 2
                    if (data.uint16At(lookaheadAt) != 0) return false
                    return (0 until inputCount).all {
                        glyphs[it] in Coverage(data.sliceFrom(data.uint16At(6 + it * 2)))
                    }
                }
                val inputCount = data.uint16At(2)
                if (inputCount != glyphs.size) return false
                return (0 until inputCount).all { glyphs[it] in Coverage(data.sliceFrom(data.uint16At(6 + it * 2))) }
            }
        }
        return false
    }
}
