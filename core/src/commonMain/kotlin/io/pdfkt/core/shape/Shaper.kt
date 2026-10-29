/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.shape

import io.pdfkt.core.text.JoiningType
import io.pdfkt.core.text.UnicodeProperties

// OpenType 1.9.1 §"OpenType Layout Common Table Formats" ("Features and
// lookups"), §"Registered features" (the default features of each script);
// Microsoft Typography "Developing OpenType Fonts for Arabic Script" ("Shaping
// engine", "Joining"), "… for Thai/Lao"; The Unicode Standard §9.2 "Arabic"
// (Cursive Joining, Table 9-3), §16.4 "Thai" (SARA AM); UAX #24 "Unicode Script
// Property" (Common and Inherited).

/** One glyph of shaped text, in visual order: drawn at the pen plus its offsets, then the pen moves by its advance. */
internal class ShapedGlyph(val id: Int, val cluster: Int, val xAdvance: Int, val xOffset: Int, val yOffset: Int) {
    override fun toString(): String = "$id=$cluster@$xOffset,$yOffset+$xAdvance"
}

/**
 * Text set in one font, shaped: the characters mapped to glyphs, the glyphs
 * substituted and positioned by the font's `GSUB` and `GPOS`, as the shaping
 * model of each script asks.
 */
internal class Shaper(private val font: LayoutFont) {
    private val plans = HashMap<Pair<String, Boolean>, Plan>()

    /**
     * [text] shaped in one direction, the glyphs in visual order — the order
     * they are drawn in from left to right.
     *
     * The text is split into runs of one script, which are shaped one at a
     * time: "the shaping engine … processes … runs of a single script", and
     * which script decides which features apply and in what order.
     */
    fun shape(text: String, rightToLeft: Boolean): List<ShapedGlyph> {
        val runs = scriptRuns(text).map { (start, end, script) -> shapeRun(text, start, end, script, rightToLeft) }
        return if (rightToLeft) runs.reversed().flatten() else runs.flatten()
    }

    private fun shapeRun(text: String, start: Int, end: Int, script: String, rightToLeft: Boolean): List<ShapedGlyph> {
        val kind = ShapingModel.of(script)
        val glyphs = ArrayList<ShapingGlyph>()
        var i = start
        while (i < end) {
            val codePoint = codePointAt(text, i)
            var mapped = codePoint
            if (rightToLeft) {
                UnicodeProperties.mirror(codePoint)?.let { mirror -> if (font.glyphOf(mirror) != 0) mapped = mirror }
            }
            glyphs += ShapingGlyph(0, i, mapped)
            i += if (codePoint > 0xFFFF) 2 else 1
        }
        normalise(glyphs, kind)
        if (kind == ShapingModel.THAI) decomposeSaraAm(glyphs)
        for (glyph in glyphs) {
            glyph.id = font.glyphOf(glyph.codePoint)
            classify(glyph)
        }
        val buffer = GlyphBuffer(glyphs, rightToLeft)
        val plan = plans.getOrPut(script to rightToLeft) { Plan.of(font, script, kind, rightToLeft) }
        for (glyph in glyphs) glyph.mask = Plan.GLOBAL
        when (kind) {
            ShapingModel.ARABIC -> plan.setArabicForms(buffer)
            ShapingModel.INDIC -> {
                IndicShaper.prepare(buffer, plan, font, script)
                for (glyph in buffer.glyphs) {
                    if (glyph.id == 0) {
                        glyph.id = font.glyphOf(glyph.codePoint)
                        classify(glyph)
                    }
                }
            }
            else -> Unit
        }
        plan.substitute(font, buffer)
        position(buffer, plan, kind)
        // Default ignorable characters — the joiners, the variation selectors —
        // "should be rendered … as completely invisible": they did their work
        // in the lookups and are not drawn, whether or not the font has a glyph.
        val visible = buffer.glyphs.filterNot { !it.substituted && UnicodeProperties.isDefaultIgnorable(it.codePoint) }
        val ordered = if (rightToLeft) visible.reversed() else visible
        return ordered.map { ShapedGlyph(it.id, it.cluster, it.xAdvance, it.xOffset, it.yOffset) }
    }

    /**
     * The glyph class of a glyph not yet substituted: `GDEF`'s, or — for a
     * font that has no glyph classes — a mark for a combining mark and a base
     * for anything else.
     */
    private fun classify(glyph: ShapingGlyph) {
        val gdef = font.gdef
        if (gdef != null && gdef.hasGlyphClasses) {
            glyph.glyphClass = gdef.glyphClass(glyph.id)
            glyph.markAttachClass = gdef.markAttachClass(glyph.id)
        } else {
            glyph.glyphClass =
                if (UnicodeProperties.isNonspacingMark(glyph.codePoint)) GdefTable.MARK else GdefTable.BASE
        }
    }

    /**
     * Characters the font has no glyph for that decompose canonically into
     * characters it has — "é" as "e" and a combining acute — are drawn as their
     * decomposition. The Indic scripts' two-part vowel signs are always
     * decomposed, since their first part is drawn before the consonant: "the
     * shaping engine decomposes … split matras".
     */
    private fun normalise(glyphs: MutableList<ShapingGlyph>, kind: ShapingModel) {
        var i = 0
        while (i < glyphs.size) {
            val glyph = glyphs[i]
            val parts = UnicodeProperties.decomposition(glyph.codePoint)
            // The Indic scripts are decomposed whether or not the font has the
            // composed character, so a split matra's first part can move.
            val always = kind == ShapingModel.INDIC
            if (parts != null && (always || font.glyphOf(glyph.codePoint) == 0) && parts.all { drawable(it) }) {
                glyphs.removeAt(i)
                glyphs.addAll(i, parts.map { ShapingGlyph(0, glyph.cluster, it) })
                continue
            }
            i++
        }
        reorderMarks(glyphs)
        // A base and a mark the font has one glyph for are drawn as that glyph:
        // "e" and a combining acute as "é". Only where nothing of a higher or
        // equal combining class comes between them, the condition under
        // which the two are canonically equivalent to the one.
        i = 1
        while (i < glyphs.size) {
            val mark = glyphs[i]
            val markClass = UnicodeProperties.combiningClass(mark.codePoint)
            if (markClass == 0) {
                i++
                continue
            }
            var starter = i - 1
            while (starter >= 0 && UnicodeProperties.combiningClass(glyphs[starter].codePoint) != 0) starter--
            val blocked = starter < 0 ||
                (starter + 1 until i).any {
                    UnicodeProperties.combiningClass(glyphs[it].codePoint) >= markClass
                }
            // A split matra is not put back together: its first part is a
            // mark, and the decomposition was made to move it.
            val splitMatra = kind == ShapingModel.INDIC && UnicodeProperties.isMark(glyphs[starter].codePoint)
            val composed = if (blocked ||
                splitMatra
            ) {
                null
            } else {
                UnicodeProperties.composition(glyphs[starter].codePoint, mark.codePoint)
            }
            if (composed != null && font.glyphOf(composed) != 0) {
                glyphs[starter].codePoint = composed
                glyphs[starter].cluster = minOf(glyphs[starter].cluster, mark.cluster)
                glyphs.removeAt(i)
            } else {
                i++
            }
        }
    }

    /**
     * The canonical ordering of combining marks: each run of them sorted, stably,
     * by combining class — with the Hebrew and Arabic points in the order their
     * fonts are built for, the SBL Hebrew order ("shin dot and sin dot, dagesh,
     * rafe, holam, then the vowels, then meteg") and shadda before the vowels
     * it combines with.
     */
    private fun reorderMarks(glyphs: MutableList<ShapingGlyph>) {
        var i = 0
        while (i < glyphs.size) {
            if (orderingClass(glyphs[i].codePoint) == 0) {
                i++
                continue
            }
            var end = i
            while (end < glyphs.size && orderingClass(glyphs[end].codePoint) != 0) end++
            if (end - i > 1) {
                val sorted = glyphs.subList(i, end).sortedBy { orderingClass(it.codePoint) }
                if (sorted.zip(glyphs.subList(i, end)).any { (a, b) -> a !== b }) {
                    val cluster = minOf(glyphs[maxOf(0, i - 1)].cluster, sorted.minOf { it.cluster })
                    for (k in i until end) glyphs[k] = sorted[k - i]
                    for (k in maxOf(0, i - 1) until end) glyphs[k].cluster = cluster
                }
            }
            i = end
        }
    }

    private fun orderingClass(codePoint: Int): Int {
        val ccc = UnicodeProperties.combiningClass(codePoint)
        return MODIFIED_CLASSES[ccc] ?: ccc
    }

    private fun drawable(codePoint: Int): Boolean =
        font.glyphOf(codePoint) != 0 || UnicodeProperties.decomposition(codePoint)?.all { drawable(it) } == true

    /**
     * SARA AM, "a combination of NIKHAHIT and SARA AA", is drawn as the two:
     * the nikhahit goes over the consonant, before any tone mark, and the sara
     * aa after it. Lao's SO AM the same.
     */
    private fun decomposeSaraAm(glyphs: MutableList<ShapingGlyph>) {
        var i = 0
        while (i < glyphs.size) {
            val glyph = glyphs[i]
            val (nikhahit, saraAa, above) = when (glyph.codePoint) {
                THAI_SARA_AM -> Triple(0x0E4D, 0x0E32, ::isThaiAboveMark)
                LAO_SARA_AM -> Triple(0x0ECD, 0x0EB2, ::isLaoAboveMark)
                else -> {
                    i++
                    continue
                }
            }
            var start = i
            while (start > 0 && above(glyphs[start - 1].codePoint)) start--
            val cluster = glyphs[start].cluster.coerceAtMost(glyph.cluster)
            glyphs.removeAt(i)
            glyphs.add(i, ShapingGlyph(0, glyph.cluster, saraAa))
            glyphs.add(start, ShapingGlyph(0, glyph.cluster, nikhahit))
            val base = if (start > 0) start - 1 else start
            val merged = minOf(cluster, glyphs[base].cluster)
            for (k in base..i + 1) glyphs[k].cluster = merged
            i += 2
        }
    }

    private fun position(buffer: GlyphBuffer, plan: Plan, kind: ShapingModel) {
        for (glyph in buffer.glyphs) {
            glyph.xAdvance = font.advanceOf(glyph.id)
            glyph.attachChain = 0
        }
        plan.position(font, buffer)
        // "Mark glyphs … should have zero advance width": a mark's advance in
        // the font is the box it is drawn in, not a distance to move on by —
        // except for Indic scripts, whose spacing vowel signs are marks with
        // an advance of their own.
        if (kind != ShapingModel.INDIC) {
            for (glyph in buffer.glyphs) if (glyph.isMark) glyph.xAdvance = 0
        }
        for (i in buffer.glyphs.indices) propagate(buffer, i)
    }

    /**
     * An attached glyph's offset made relative to where it is drawn from: a
     * mark is drawn after the glyphs between it and its base have moved the
     * pen, so their advances are taken back off; a cursive glyph's vertical
     * offset is its parent's plus its own.
     */
    private fun propagate(buffer: GlyphBuffer, i: Int) {
        val glyph = buffer[i]
        val chain = glyph.attachChain
        if (chain == 0) return
        glyph.attachChain = 0
        val j = i + chain
        if (j < 0 || j >= buffer.size) return
        propagate(buffer, j)
        val parent = buffer[j]
        if (glyph.attachType == ShapingGlyph.ATTACH_CURSIVE) {
            glyph.yOffset += parent.yOffset
            return
        }
        glyph.xOffset += parent.xOffset
        glyph.yOffset += parent.yOffset
        if (!buffer.rightToLeft) {
            for (k in j until i) glyph.xOffset -= buffer[k].xAdvance
        } else {
            for (k in j + 1..i) glyph.xOffset += buffer[k].xAdvance
        }
    }

    /**
     * The runs of one script in [text]: `start, end, script`. Characters of
     * the Common and Inherited scripts — spaces, punctuation, digits,
     * combining marks — belong to the run around them, as "characters with
     * Script=Common or Inherited … take the script of the preceding character".
     */
    private fun scriptRuns(text: String): List<Triple<Int, Int, String>> {
        val runs = ArrayList<Triple<Int, Int, String>>()
        var start = 0
        var script: String? = null
        var i = 0
        while (i < text.length) {
            val codePoint = codePointAt(text, i)
            val own = UnicodeProperties.script(codePoint)
            if (own != "Common" && own != "Inherited" && own != "Unknown") {
                if (script == null) {
                    script = own
                } else if (own != script) {
                    runs += Triple(start, i, script)
                    start = i
                    script = own
                }
            }
            i += if (codePoint > 0xFFFF) 2 else 1
        }
        if (text.isNotEmpty()) runs += Triple(start, text.length, script ?: "Common")
        return runs
    }

    private fun isThaiAboveMark(codePoint: Int): Boolean =
        codePoint == 0x0E31 || codePoint in 0x0E34..0x0E37 || codePoint in 0x0E47..0x0E4E

    private fun isLaoAboveMark(codePoint: Int): Boolean =
        codePoint == 0x0EB1 || codePoint in 0x0EB4..0x0EB7 || codePoint == 0x0EBB || codePoint in 0x0EC8..0x0ECD

    private companion object {
        /**
         * The Hebrew fixed-position classes 10–26 permuted into the SBL Hebrew
         * order, and the Arabic classes 27–35 with shadda first.
         */
        val MODIFIED_CLASSES: Map<Int, Int> = mapOf(
            10 to 22, 11 to 15, 12 to 16, 13 to 17, 14 to 23, 15 to 18, 16 to 19, 17 to 20, 18 to 21,
            19 to 14, 20 to 24, 21 to 12, 22 to 25, 23 to 13, 24 to 10, 25 to 11, 26 to 26,
            27 to 28, 28 to 29, 29 to 30, 30 to 31, 31 to 32, 32 to 33, 33 to 27, 34 to 34, 35 to 35,
        )

        const val THAI_SARA_AM = 0x0E33
        const val LAO_SARA_AM = 0x0EB3
    }
}

/** Which shaping model a script is shaped by. */
internal enum class ShapingModel {
    DEFAULT,
    ARABIC,
    THAI,
    INDIC,
    ;

    companion object {
        fun of(script: String): ShapingModel = when (script) {
            "Arabic", "Syriac", "Nko", "Mongolian", "Mandaic", "Manichaean", "Psalter_Pahlavi", "Adlam" -> ARABIC
            "Thai", "Lao" -> THAI
            in IndicShaper.SCRIPTS -> INDIC
            else -> DEFAULT
        }
    }
}

/**
 * The OpenType script tags of a Unicode script, the preferred first: the
 * Indic scripts' "new" tags, then the old ones a font of the first
 * specification has.
 */
internal fun scriptTagsOf(script: String): List<String> = when (script) {
    "Latin" -> listOf("latn")
    "Cyrillic" -> listOf("cyrl")
    "Greek" -> listOf("grek")
    "Armenian" -> listOf("armn")
    "Georgian" -> listOf("geor")
    "Hebrew" -> listOf("hebr")
    "Arabic" -> listOf("arab")
    "Syriac" -> listOf("syrc")
    "Thai" -> listOf("thai")
    "Lao" -> listOf("lao ")
    "Devanagari" -> listOf("dev2", "deva")
    "Bengali" -> listOf("bng2", "beng")
    "Gurmukhi" -> listOf("gur2", "guru")
    "Gujarati" -> listOf("gjr2", "gujr")
    "Tamil" -> listOf("tml2", "taml")
    "Telugu" -> listOf("tel2", "telu")
    "Kannada" -> listOf("knd2", "knda")
    "Malayalam" -> listOf("mlm2", "mlym")
    "Oriya" -> listOf("ory2", "orya")
    "Han" -> listOf("hani")
    "Hiragana", "Katakana" -> listOf("kana")
    "Hangul" -> listOf("hang")
    else -> emptyList()
}

/**
 * Which features a run of one script is shaped with, in which stages: the
 * lookups of the features of one stage are applied together, in lookup
 * order, and each stage after the previous one — "the features … in the
 * order specified" by the script's shaping model.
 */
internal class Plan private constructor(
    private val gsubStages: List<Stage>,
    private val gposLookups: List<PlannedLookup>,
    private val masks: Map<String, Long>,
    /** The `GSUB` lookups of each feature of the plan. */
    val featureLookups: Map<String, List<Int>>,
) {
    class Stage(val lookups: List<PlannedLookup>, val pause: ((GlyphBuffer, Plan) -> Unit)? = null)

    fun maskOf(feature: String): Long = masks[feature] ?: 0L

    fun substitute(font: LayoutFont, buffer: GlyphBuffer) {
        val gsub = font.gsub
        for (stage in gsubStages) {
            if (gsub != null) {
                val applier = LookupApplier(font, gsub, buffer, substituting = true)
                for (lookup in stage.lookups) applier.apply(lookup)
            }
            stage.pause?.invoke(buffer, this)
        }
    }

    fun position(font: LayoutFont, buffer: GlyphBuffer) {
        val gpos = font.gpos ?: return
        val applier = LookupApplier(font, gpos, buffer, substituting = false)
        for (lookup in gposLookups) applier.apply(lookup)
    }

    /**
     * The Arabic joining forms, as features on each letter: "The shaping engine
     * … determines the joining … of each character" from its Joining_Type and
     * its neighbours', transparent characters passed over.
     */
    fun setArabicForms(buffer: GlyphBuffer) {
        val forms = IntArray(buffer.size) { NONE }
        var previous = -1
        var previousType = JoiningType.U
        for (i in 0 until buffer.size) {
            val type = UnicodeProperties.joiningType(buffer[i].codePoint)
            if (type == JoiningType.T) continue
            val joinsRight = type == JoiningType.D || type == JoiningType.R || type == JoiningType.C
            val previousJoinsLeft = previousType == JoiningType.D ||
                previousType == JoiningType.L ||
                previousType == JoiningType.C
            if (previous >= 0 && joinsRight && previousJoinsLeft) {
                forms[previous] = when (forms[previous]) {
                    ISOL -> INIT
                    FINA -> MEDI
                    else -> forms[previous]
                }
                forms[i] = FINA
            } else {
                forms[i] = ISOL
            }
            if (type == JoiningType.U) forms[i] = NONE
            previous = i
            previousType = type
        }
        for (i in 0 until buffer.size) {
            val type = UnicodeProperties.joiningType(buffer[i].codePoint)
            val form = forms[i]
            // A right-joining letter has no initial or medial form, a
            // left-joining one no final or medial one: they are isolated or
            // final, isolated or initial.
            val effective = when {
                type == JoiningType.C || form == NONE -> NONE
                type == JoiningType.R && form == INIT -> ISOL
                type == JoiningType.R && form == MEDI -> FINA
                type == JoiningType.L && form == FINA -> ISOL
                type == JoiningType.L && form == MEDI -> INIT
                else -> form
            }
            if (effective != NONE) buffer[i].mask = buffer[i].mask or maskOf(ARABIC_FORMS[effective])
        }
    }

    companion object {
        const val GLOBAL: Long = 1L

        const val NONE = -1
        const val ISOL = 0
        const val FINA = 1
        const val MEDI = 2
        const val INIT = 3
        val ARABIC_FORMS = listOf("isol", "fina", "medi", "init")

        private val COMMON_GSUB = listOf("ccmp", "locl", "rlig", "calt", "clig", "liga", "rclt")
        private val COMMON_GPOS = listOf("abvm", "blwm", "curs", "dist", "kern", "mark", "mkmk")

        /** A feature of the plan: its tag, whether it is on for every glyph, and how its lookups match. */
        class Feature(
            val tag: String,
            val global: Boolean = true,
            val perSyllable: Boolean = false,
            val manualZwj: Boolean = false,
        )

        fun of(font: LayoutFont, script: String, kind: ShapingModel, rightToLeft: Boolean): Plan {
            val tags = scriptTagsOf(script)
            val masks = HashMap<String, Long>()
            var nextBit = 1
            fun maskFor(feature: Feature): Long = if (feature.global) {
                GLOBAL
            } else {
                masks.getOrPut(feature.tag) { 1L shl nextBit++ }
            }

            val stages = ArrayList<Pair<List<Feature>, ((GlyphBuffer, Plan) -> Unit)?>>()
            val direction = if (rightToLeft) listOf("rtla", "rtlm") else listOf("ltra", "ltrm")
            when (kind) {
                ShapingModel.ARABIC -> {
                    stages += listOf(Feature("ccmp", manualZwj = true), Feature("locl", manualZwj = true)) to null
                    for (form in listOf("isol", "fina", "fin2", "fin3", "medi", "med2", "init")) {
                        stages += listOf(Feature(form, global = false, manualZwj = true)) to null
                    }
                    stages += listOf(Feature("rlig", manualZwj = true)) to null
                    stages += (
                        listOf(Feature("calt", manualZwj = true), Feature("rclt", manualZwj = true), Feature("mset")) +
                            (direction + listOf("clig", "liga")).map { Feature(it) }
                        ) to null
                }
                ShapingModel.INDIC -> stages += IndicShaper.stages(font, script)
                else -> stages += (direction + COMMON_GSUB).map { Feature(it) } to null
            }

            val gsub = font.gsub
            val featureIndices = gsub?.featureIndicesFor(tags) ?: emptyList()
            val featureLookups = HashMap<String, List<Int>>()
            val gsubStages = stages.map { (features, pause) ->
                val lookups = HashMap<Int, PlannedLookup>()
                for (feature in features) {
                    val mask = maskFor(feature)
                    val ofFeature = gsub?.lookupsOf(featureIndices, feature.tag) ?: emptyList()
                    featureLookups[feature.tag] = ofFeature.filter { it < (gsub?.lookupCount ?: 0) }
                    for (index in ofFeature) {
                        if (index >= (gsub?.lookupCount ?: 0)) continue
                        val existing = lookups[index]
                        lookups[index] = PlannedLookup(
                            index,
                            (existing?.mask ?: 0L) or mask,
                            perSyllable = feature.perSyllable,
                            manualZwj = feature.manualZwj,
                        )
                    }
                }
                Stage(lookups.values.sortedBy { it.index }, pause)
            }

            val gpos = font.gpos
            val gposIndices = gpos?.featureIndicesFor(tags) ?: emptyList()
            val gposLookups = (
                COMMON_GPOS +
                    (if (kind == ShapingModel.INDIC) listOf("abvm", "blwm", "dist") else emptyList())
                )
                .distinct()
                .flatMap { gpos?.lookupsOf(gposIndices, it) ?: emptyList() }
                .filter { it < (gpos?.lookupCount ?: 0) }
                .distinct()
                .sorted()
                .map { PlannedLookup(it, GLOBAL) }
            return Plan(gsubStages, gposLookups, masks, featureLookups)
        }
    }
}

internal fun codePointAt(text: String, index: Int): Int {
    val high = text[index]
    if (high.isHighSurrogate() && index + 1 < text.length && text[index + 1].isLowSurrogate()) {
        return ((high.code - 0xD800) shl 10) + (text[index + 1].code - 0xDC00) + 0x10000
    }
    return high.code
}
