/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.shape

import io.pdfkt.core.text.UnicodeProperties

// Microsoft Typography "Developing OpenType Fonts for Devanagari Script" (and
// the same document for Bengali, Gurmukhi, Gujarati, Tamil, Telugu, Kannada
// and Malayalam): "Shaping engine" — "Analyze the characters", "Find
// syllables", "Initial reordering", "Apply the basic shaping forms", "Final
// reordering", "Apply the presentation forms"; Unicode Standard Annex #44,
// Indic_Syllabic_Category and Indic_Positional_Category.

/**
 * The shaping model of the Indic scripts: text is split into syllables, the
 * characters of each syllable are reordered into the order their glyphs are
 * drawn in, the basic forms (nukta, reph, below-base, half forms …) are
 * applied one feature at a time, the pre-base matra and the reph are moved to
 * their final places, and the presentation forms are applied.
 *
 * Every step is the script development specifications' — "the shaping engine
 * … reorders" the pre-base matra in front of the base consonant, and so on.
 * Which consonant is the base depends on which forms the font has, so the
 * shaper asks the font: a consonant the font draws a below-base form of is
 * not the base.
 */
internal object IndicShaper {
    val SCRIPTS: Set<String> = setOf(
        "Devanagari",
        "Bengali",
        "Gurmukhi",
        "Gujarati",
        "Tamil",
        "Telugu",
        "Kannada",
        "Malayalam",
    )

    // ------------------------------------------------------------ categories

    private const val X = 0
    private const val C = 1
    private const val V = 2
    private const val N = 3
    private const val H = 4
    private const val ZWNJ = 5
    private const val ZWJ = 6
    private const val M = 7
    private const val SM = 8
    private const val A = 9
    private const val PLACEHOLDER = 10
    private const val DOTTED_CIRCLE = 11
    private const val RS = 12
    private const val REPHA = 13
    private const val RA = 14
    private const val CM = 15
    private const val SYMBOL = 16
    private const val CS = 17

    /** The letters the syllable patterns below are written in, one per category. */
    private const val LETTERS = "XCVNHZJMSAPDrFRmYK"

    private val CONSONANTS = setOf(C, CS, RA, CM, V, PLACEHOLDER, DOTTED_CIRCLE)

    // ------------------------------------------------------------- positions

    private const val POS_START = 0
    private const val POS_RA_TO_BECOME_REPH = 1
    private const val POS_PRE_M = 2
    private const val POS_PRE_C = 3
    private const val POS_BASE_C = 4
    private const val POS_AFTER_MAIN = 5
    private const val POS_ABOVE_C = 6
    private const val POS_BEFORE_SUB = 7
    private const val POS_BELOW_C = 8
    private const val POS_AFTER_SUB = 9
    private const val POS_BEFORE_POST = 10
    private const val POS_POST_C = 11
    private const val POS_AFTER_POST = 12
    private const val POS_FINAL_C = 13
    private const val POS_SMVD = 14
    private const val POS_END = 15

    // ------------------------------------------------------------ the scripts

    private enum class RephMode { IMPLICIT, EXPLICIT, LOGICAL_REPHA }

    /**
     * What the specifications state per script: its virama, where the reph
     * goes ("Reph … is reordered … after the main consonant / before the
     * post-base consonants / after the post-base consonants"), how a reph is
     * written, and whether a below-base form may come before the base.
     */
    private class ScriptConfig(
        val virama: Int,
        val ra: Int,
        val rephPosition: Int,
        val rephMode: RephMode,
        val belowBeforeBase: Boolean,
    )

    private val CONFIGS: Map<String, ScriptConfig> = mapOf(
        "Devanagari" to ScriptConfig(0x094D, 0x0930, POS_BEFORE_POST, RephMode.IMPLICIT, true),
        "Bengali" to ScriptConfig(0x09CD, 0x09B0, POS_AFTER_SUB, RephMode.IMPLICIT, true),
        "Gurmukhi" to ScriptConfig(0x0A4D, 0x0A30, POS_BEFORE_SUB, RephMode.IMPLICIT, true),
        "Gujarati" to ScriptConfig(0x0ACD, 0x0AB0, POS_BEFORE_POST, RephMode.IMPLICIT, true),
        "Tamil" to ScriptConfig(0x0BCD, 0x0BB0, POS_AFTER_POST, RephMode.IMPLICIT, true),
        "Telugu" to ScriptConfig(0x0C4D, 0x0C30, POS_AFTER_POST, RephMode.EXPLICIT, false),
        "Kannada" to ScriptConfig(0x0CCD, 0x0CB0, POS_AFTER_POST, RephMode.IMPLICIT, false),
        "Malayalam" to ScriptConfig(0x0D4D, 0x0D30, POS_AFTER_MAIN, RephMode.LOGICAL_REPHA, true),
    )

    // -------------------------------------------------------------- features

    private val BASIC = listOf("nukt", "akhn", "rphf", "rkrf", "pref", "blwf", "abvf", "half", "pstf", "vatu", "cjct")
    private val MASKED = setOf("rphf", "pref", "blwf", "abvf", "half", "pstf", "init")
    private val PRESENTATION = listOf("init", "pres", "abvs", "blws", "psts", "haln")

    /**
     * The stages of an Indic plan: `locl` and `ccmp`, then the initial
     * reordering; each basic shaping feature in a stage of its own — "the
     * shaping engine applies … one at a time"; the final reordering; and the
     * presentation forms together with the features every script has.
     */
    fun stages(
        font: LayoutFont,
        script: String,
    ): List<Pair<List<Plan.Companion.Feature>, ((GlyphBuffer, Plan) -> Unit)?>> {
        val config = CONFIGS.getValue(script)
        val stages = ArrayList<Pair<List<Plan.Companion.Feature>, ((GlyphBuffer, Plan) -> Unit)?>>()
        stages +=
            listOf(feature("locl"), feature("ccmp")) to
            { buffer, plan -> initialReordering(buffer, plan, font, config) }
        BASIC.forEachIndexed { index, tag ->
            val pause: ((GlyphBuffer, Plan) -> Unit)? =
                if (index ==
                    BASIC.lastIndex
                ) {
                    { buffer, plan -> finalReordering(buffer, plan, script, config) }
                } else {
                    null
                }
            stages += listOf(feature(tag)) to pause
        }
        stages += (PRESENTATION + listOf("calt", "clig", "liga", "rlig", "rclt")).map { feature(it) } to null
        return stages
    }

    private fun feature(tag: String): Plan.Companion.Feature =
        Plan.Companion.Feature(tag, global = tag !in MASKED, perSyllable = true, manualZwj = true)

    // -------------------------------------------------------------- analysis

    /**
     * "Analyze the characters": each character's category and position, and
     * the syllable it belongs to.
     */
    fun prepare(buffer: GlyphBuffer, plan: Plan, font: LayoutFont, script: String) {
        val config = CONFIGS.getValue(script)
        for (glyph in buffer.glyphs) {
            val (category, position) = classify(glyph.codePoint, script, config)
            glyph.category = category
            glyph.position = position
        }
        findSyllables(buffer)
        insertDottedCircles(buffer, font)
    }

    /**
     * A syllable that is not well formed — a matra or a virama with no
     * consonant to go on — is drawn on a dotted circle, "the shaping engine
     * inserts a dotted circle … to indicate the invalid combination", after
     * its reph if it begins with one.
     */
    private fun insertDottedCircles(buffer: GlyphBuffer, font: LayoutFont) {
        val circle = font.glyphOf(DOTTED_CIRCLE_CHARACTER)
        if (circle == 0) return
        var i = 0
        while (i < buffer.size) {
            val syllable = buffer[i].syllable
            if (buffer.syllableKinds.getOrElse(syllable - 1) { OTHER } != BROKEN_CLUSTER) {
                i++
                continue
            }
            var at = i
            if (buffer[at].category == REPHA) at++
            val inserted = ShapingGlyph(0, buffer[i].cluster, DOTTED_CIRCLE_CHARACTER).also {
                it.category = DOTTED_CIRCLE
                it.position = POS_BASE_C
                it.syllable = syllable
                it.mask = buffer[i].mask
            }
            buffer.glyphs.add(at, inserted)
            buffer.syllableKinds[syllable - 1] = STANDALONE_CLUSTER
            while (i < buffer.size && buffer[i].syllable == syllable) i++
        }
    }

    private fun classify(codePoint: Int, script: String, config: ScriptConfig): Pair<Int, Int> {
        val category = when (codePoint) {
            0x200C -> ZWNJ
            0x200D -> ZWJ
            0x25CC -> DOTTED_CIRCLE
            0x00A0, 0x00D7, in 0x2010..0x2014, 0x2012 -> PLACEHOLDER
            0x0951, 0x0952, 0x0953, 0x0954 -> A
            0x0D4E -> REPHA
            else -> when (UnicodeProperties.indicSyllabicCategory(codePoint)) {
                "Avagraha" -> SYMBOL
                "Bindu", "Visarga", "Syllable_Modifier", "Gemination_Mark" -> SM
                "Cantillation_Mark" -> A
                "Consonant", "Consonant_Dead", "Consonant_Head_Letter" -> C
                "Consonant_Medial", "Consonant_Final", "Consonant_Subjoined", "Consonant_Succeeding_Repha" -> CM
                "Consonant_Placeholder", "Number", "Brahmi_Joining_Number", "Number_Joiner" -> PLACEHOLDER
                "Consonant_Preceding_Repha" -> REPHA
                "Consonant_With_Stacker" -> CS
                "Invisible_Stacker", "Virama" -> H
                "Nukta", "Tone_Mark" -> N
                "Register_Shifter" -> RS
                "Vowel_Dependent" -> M
                "Vowel_Independent", "Vowel" -> V
                "Joiner" -> ZWJ
                "Non_Joiner" -> ZWNJ
                else -> X
            }
        }
        val position = when {
            category in CONSONANTS -> POS_BASE_C
            category == M -> matraPosition(codePoint, script)
            category == SM || category == A -> POS_SMVD
            else -> POS_END
        }
        return (if (category == C && codePoint == config.ra) RA else category) to position
    }

    /**
     * Where a dependent vowel sign is drawn relative to the consonants:
     * Indic_Positional_Category, refined per script as the specifications
     * order the marks ("above-base matras … after sub-joined consonants").
     */
    private fun matraPosition(codePoint: Int, script: String): Int {
        val ipc = UnicodeProperties.indicPositionalCategory(codePoint)
        return when {
            ipc == "Left" || ipc == "Visual_Order_Left" -> POS_PRE_M
            ipc == "Right" || ipc == "Top_And_Right" || ipc == "Bottom_And_Right" -> when (script) {
                "Devanagari" -> POS_AFTER_SUB
                "Telugu" -> if (codePoint <= 0x0C42) POS_BEFORE_SUB else POS_AFTER_SUB
                "Kannada" -> if (codePoint < 0x0CC3 || codePoint > 0x0CD6) POS_BEFORE_SUB else POS_AFTER_SUB
                else -> POS_AFTER_POST
            }
            ipc == "Top" || ipc == "Top_And_Bottom" || ipc == "Top_And_Left" || ipc == "Top_And_Left_And_Right" ->
                when (script) {
                    "Gurmukhi" -> POS_AFTER_POST
                    "Telugu", "Kannada" -> POS_BEFORE_SUB
                    else -> POS_AFTER_SUB
                }
            ipc == "Bottom" || ipc == "Overstruck" -> when (script) {
                "Gurmukhi", "Gujarati", "Tamil", "Malayalam" -> POS_AFTER_POST
                "Telugu", "Kannada" -> POS_BEFORE_SUB
                else -> POS_AFTER_SUB
            }
            else -> POS_AFTER_SUB
        }
    }

    // ------------------------------------------------------------- syllables

    private const val CN = "[CR]J?(?:(?:Z?r)?(?:NN?)?)"
    private const val N_GROUP = "(?:(?:Z?r)?(?:NN?)?)"
    private const val HALANT_GROUP = "[JZ]?H(?:JN?)?"
    private const val SYLLABLE_TAIL = "(?:[JZ]?SS?Z?)?A*"
    private const val MATRA_GROUP = "[JZ]*MN?H?"
    private const val COMPLEX_TAIL =
        "(?:$HALANT_GROUP$CN)*m?(?:(?:$HALANT_GROUP|HZ)|(?:$MATRA_GROUP)*)$SYLLABLE_TAIL"
    private const val REPH = "(?:RH|F)"

    /**
     * The syllables of the specifications — "a consonant syllable", "a vowel
     * syllable", "a standalone cluster" — written over the category letters:
     * consonants with their nuktas and halants, then the matras, syllable
     * modifiers and vedic signs that close them.
     */
    private val SYLLABLES: List<Regex> = listOf(
        Regex("[FK]?$CN$COMPLEX_TAIL"),
        Regex("$REPH?V$N_GROUP(?:J|$COMPLEX_TAIL)"),
        Regex("(?:[FK]?P|$REPH?D)$N_GROUP$COMPLEX_TAIL"),
        Regex("YN?$SYLLABLE_TAIL"),
        Regex("$REPH?$N_GROUP$COMPLEX_TAIL"),
    )

    /** Kinds of syllable, as [SYLLABLES] lists them; the last is a character outside any. */
    private const val STANDALONE_CLUSTER = 2
    private const val SYMBOL_CLUSTER = 3
    private const val BROKEN_CLUSTER = 4
    private const val OTHER = 5
    private const val DOTTED_CIRCLE_CHARACTER = 0x25CC

    private fun findSyllables(buffer: GlyphBuffer) {
        val letters = buildString { for (glyph in buffer.glyphs) append(LETTERS[glyph.category]) }
        var at = 0
        var serial = 1
        val kinds = ArrayList<Int>()
        while (at < letters.length) {
            var best = 0
            var kind = OTHER
            SYLLABLES.forEachIndexed { index, pattern ->
                val length = pattern.matchAt(letters, at)?.value?.length ?: 0
                if (length > best) {
                    best = length
                    kind = index
                }
            }
            if (best == 0) best = 1
            for (k in at until at + best) buffer[k].syllable = serial
            kinds += kind
            serial++
            at += best
        }
        buffer.syllableKinds = kinds.toIntArray()
    }

    /** The syllables of [buffer] as `start, end` ranges. */
    private fun syllables(buffer: GlyphBuffer): List<IntRange> {
        val ranges = ArrayList<IntRange>()
        var start = 0
        while (start < buffer.size) {
            var end = start + 1
            while (end < buffer.size && buffer[end].syllable == buffer[start].syllable) end++
            ranges += start until end
            start = end
        }
        return ranges
    }

    // ---------------------------------------------------- initial reordering

    private fun initialReordering(buffer: GlyphBuffer, plan: Plan, font: LayoutFont, config: ScriptConfig) {
        val kinds = buffer.syllableKinds
        val virama = font.glyphOf(config.virama)
        // "Which consonant forms a below-base or post-base form is decided by
        // the font": a consonant the font has such a form for is not the base.
        if (virama != 0) {
            for (glyph in buffer.glyphs) {
                if (glyph.position == POS_BASE_C && glyph.category != V) {
                    glyph.position = consonantPosition(plan, font, glyph.id, virama)
                }
            }
        }
        for (range in syllables(buffer)) {
            val kind = kinds.getOrElse(buffer[range.first].syllable - 1) { OTHER }
            if (kind == SYMBOL_CLUSTER || kind == OTHER) continue
            reorderSyllable(buffer, plan, font, config, range.first, range.last + 1)
        }
    }

    private fun consonantPosition(plan: Plan, font: LayoutFont, consonant: Int, virama: Int): Int {
        val afterVirama = intArrayOf(virama, consonant)
        val beforeVirama = intArrayOf(consonant, virama)
        fun forms(feature: String) =
            wouldSubstitute(plan, font, feature, afterVirama) || wouldSubstitute(plan, font, feature, beforeVirama)
        return when {
            forms("blwf") || forms("vatu") -> POS_BELOW_C
            forms("pstf") || forms("pref") -> POS_POST_C
            else -> POS_BASE_C
        }
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    private fun reorderSyllable(
        buffer: GlyphBuffer,
        plan: Plan,
        font: LayoutFont,
        config: ScriptConfig,
        start: Int,
        end: Int,
    ) {
        val glyphs = buffer.glyphs
        // Every glyph of the syllable is one cluster: its characters are drawn
        // in another order than they are written, by glyphs that stand for them together.
        buffer.mergeClusters(start, end)

        // "Find the base consonant" — after a reph, if there is one.
        var base = end
        var hasReph = false
        var limit = start
        if (plan.maskOf("rphf") != 0L &&
            start + 3 <= end &&
            (
                (config.rephMode == RephMode.IMPLICIT && !isJoiner(glyphs[start + 2])) ||
                    (config.rephMode == RephMode.EXPLICIT && glyphs[start + 2].category == ZWJ)
                )
        ) {
            val two = intArrayOf(glyphs[start].id, glyphs[start + 1].id)
            val three = intArrayOf(glyphs[start].id, glyphs[start + 1].id, glyphs[start + 2].id)
            if (wouldSubstitute(plan, font, "rphf", two) ||
                (config.rephMode == RephMode.EXPLICIT && wouldSubstitute(plan, font, "rphf", three))
            ) {
                limit += 2
                while (limit < end && isJoiner(glyphs[limit])) limit++
                base = start
                hasReph = true
            }
        } else if (config.rephMode == RephMode.LOGICAL_REPHA && glyphs[start].category == REPHA) {
            limit += 1
            while (limit < end && isJoiner(glyphs[limit])) limit++
            base = start
            hasReph = true
        }

        // "Starting from the end of the syllable, move backwards until a
        // consonant is found that does not have a below-base or post-base form."
        var i = end
        var seenBelow = false
        do {
            i--
            if (isConsonant(glyphs[i])) {
                if (glyphs[i].position != POS_BELOW_C && (glyphs[i].position != POS_POST_C || seenBelow)) {
                    base = i
                    break
                }
                if (glyphs[i].position == POS_BELOW_C) seenBelow = true
                base = i
            } else if (start < i && glyphs[i].category == ZWJ && glyphs[i - 1].category == H) {
                // "A ZWJ after a halant stops the base search", requesting a half form.
                break
            }
        } while (i > limit)
        if (hasReph && base == start && limit - base <= 2) hasReph = false

        // Positions: everything before the base is pre-base, the base is the base.
        for (k in start until base) glyphs[k].position = minOf(POS_PRE_C, glyphs[k].position)
        if (base < end) glyphs[base].position = POS_BASE_C
        if (hasReph) glyphs[start].position = POS_RA_TO_BECOME_REPH

        // Nuktas, halants, joiners and medials go with the character before them.
        var lastPosition = POS_START
        for (k in start until end) {
            val glyph = glyphs[k]
            if (glyph.category == ZWJ ||
                glyph.category == ZWNJ ||
                glyph.category == N ||
                glyph.category == RS ||
                glyph.category == CM ||
                glyph.category == H
            ) {
                glyph.position = lastPosition
                if (glyph.category == H && glyph.position == POS_PRE_M) {
                    for (j in k downTo start + 1) {
                        if (glyphs[j - 1].position != POS_PRE_M) {
                            glyph.position = glyphs[j - 1].position
                            break
                        }
                    }
                }
            } else if (glyph.position != POS_SMVD) {
                lastPosition = glyph.position
            }
        }
        // A post-base consonant takes what comes between it and the consonant or matra before it.
        var last = base
        for (k in base + 1 until end) {
            if (isConsonant(glyphs[k])) {
                for (j in last + 1 until k) if (glyphs[j].position < POS_SMVD) glyphs[j].position = glyphs[k].position
                last = k
            } else if (glyphs[k].category == M) {
                last = k
            }
        }

        // "Reorder the characters" — a stable sort by position.
        val sorted = glyphs.subList(start, end).sortedBy { it.position }
        for (k in start until end) glyphs[k] = sorted[k - start]

        // Several pre-base matras keep the order they were written in.
        base = end
        var firstLeftMatra = end
        var lastLeftMatra = end
        for (k in start until end) {
            if (glyphs[k].position == POS_BASE_C) {
                base = k
                break
            }
            if (glyphs[k].position == POS_PRE_M) {
                if (firstLeftMatra == end) firstLeftMatra = k
                lastLeftMatra = k
            }
        }
        if (firstLeftMatra < lastLeftMatra) {
            glyphs.subList(firstLeftMatra, lastLeftMatra + 1).reverse()
            var from = firstLeftMatra
            for (j in firstLeftMatra..lastLeftMatra) {
                if (glyphs[j].category == M) {
                    glyphs.subList(from, j + 1).reverse()
                    from = j + 1
                }
            }
        }

        // Which glyphs each basic feature applies to.
        val rphf = plan.maskOf("rphf")
        val half = plan.maskOf("half")
        val blwf = plan.maskOf("blwf")
        val abvf = plan.maskOf("abvf")
        val pstf = plan.maskOf("pstf")
        val pref = plan.maskOf("pref")
        var k = start
        while (k < end && glyphs[k].position == POS_RA_TO_BECOME_REPH) {
            glyphs[k].mask = glyphs[k].mask or rphf
            k++
        }
        val preBase = if (config.belowBeforeBase) half or blwf else half
        for (j in start until base) glyphs[j].mask = glyphs[j].mask or preBase
        for (j in base + 1 until end) glyphs[j].mask = glyphs[j].mask or blwf or abvf or pstf

        // "pref" on a halant and Ra after the base the font has a pre-base form for.
        if (pref != 0L && base + 2 < end) {
            var j = base + 1
            while (j + 1 < end) {
                if (wouldSubstitute(plan, font, "pref", intArrayOf(glyphs[j].id, glyphs[j + 1].id))) {
                    glyphs[j].mask = glyphs[j].mask or pref
                    glyphs[j + 1].mask = glyphs[j + 1].mask or pref
                    break
                }
                j++
            }
        }

        // "A ZWNJ … prevents the formation of a half form" of the consonant before it.
        for (j in base + 1 until end) {
            if (glyphs[j].category == ZWNJ) {
                var m = j
                do {
                    m--
                    glyphs[m].mask = glyphs[m].mask and half.inv()
                } while (m > start && !isConsonant(glyphs[m]))
            }
        }
    }

    // ------------------------------------------------------ final reordering

    @Suppress("CyclomaticComplexMethod", "LongMethod", "NestedBlockDepth")
    private fun finalReordering(buffer: GlyphBuffer, plan: Plan, script: String, config: ScriptConfig) {
        val glyphs = buffer.glyphs
        val pref = plan.maskOf("pref")
        for (range in syllables(buffer)) {
            val start = range.first
            val end = range.last + 1
            var tryPref = pref != 0L

            // "Find the base consonant" again: the glyphs have been substituted.
            var base = start
            while (base < end) {
                if (glyphs[base].position >= POS_BASE_C) {
                    if (tryPref && base + 1 < end) {
                        for (j in base + 1 until end) {
                            if (glyphs[j].mask and pref != 0L) {
                                if (!(glyphs[j].substituted && glyphs[j].ligated && !glyphs[j].multiplied)) {
                                    base = j
                                    while (base < end && isHalant(glyphs[base])) base++
                                    if (base < end) glyphs[base].position = POS_BASE_C
                                    tryPref = false
                                }
                                break
                            }
                        }
                        if (base == end) break
                    }
                    if (script == "Malayalam") {
                        var j = base + 1
                        while (j < end) {
                            while (j < end && isJoiner(glyphs[j])) j++
                            if (j == end || !isHalant(glyphs[j])) break
                            j++
                            while (j < end && isJoiner(glyphs[j])) j++
                            if (j < end && isConsonant(glyphs[j]) && glyphs[j].position == POS_BELOW_C) {
                                base = j
                                glyphs[base].position = POS_BASE_C
                            }
                            j++
                        }
                    }
                    if (start < base && glyphs[base].position > POS_BASE_C) base--
                    break
                }
                base++
            }
            if (base == end && start < base && glyphs[base - 1].category == ZWJ && !glyphs[base - 1].ligated) base--
            if (base < end) {
                while (start < base && (isOneOf(glyphs[base], N) || isOneOf(glyphs[base], H))) base--
            }

            // "Reorder the pre-base matra": just before the base, after the
            // last halant that is not part of a half form.
            if (start + 1 < end && start < base) {
                var newPosition = if (base == end) base - 2 else base - 1
                if (script != "Malayalam" && script != "Tamil") {
                    while (true) {
                        while (newPosition > start &&
                            !(isOneOf(glyphs[newPosition], M) || isOneOf(glyphs[newPosition], H))
                        ) {
                            newPosition--
                        }
                        if (isHalant(glyphs[newPosition]) && glyphs[newPosition].position != POS_PRE_M) {
                            if (newPosition + 1 < end &&
                                glyphs[newPosition + 1].category == ZWJ &&
                                newPosition > start
                            ) {
                                newPosition--
                                continue
                            }
                        } else {
                            newPosition = start
                        }
                        break
                    }
                }
                if (start < newPosition && glyphs[newPosition].position != POS_PRE_M) {
                    var j = newPosition
                    while (j > start) {
                        if (glyphs[j - 1].position == POS_PRE_M) {
                            val old = j - 1
                            if (old < base && base <= newPosition) base--
                            val moved = glyphs.removeAt(old)
                            glyphs.add(newPosition, moved)
                            newPosition--
                        }
                        j--
                    }
                }
            }

            // "Reorder reph" to where the script puts it.
            if (start + 1 < end &&
                glyphs[start].position == POS_RA_TO_BECOME_REPH &&
                ((glyphs[start].category == REPHA) xor (glyphs[start].ligated && !glyphs[start].multiplied))
            ) {
                val newPosition = rephTarget(glyphs, config, start, end, base)
                val reph = glyphs.removeAt(start)
                glyphs.add(newPosition, reph)
                if (start < base && base <= newPosition) base--
            }

            // "Reorder pre-base-reordering consonants": a pref form goes where a pre-base matra would.
            if (tryPref && base + 1 < end) {
                for (j in base + 1 until end) {
                    if (glyphs[j].mask and pref != 0L) {
                        if (glyphs[j].ligated && !glyphs[j].multiplied) {
                            var newPosition = base
                            if (script != "Malayalam" && script != "Tamil") {
                                while (newPosition > start &&
                                    !(isOneOf(glyphs[newPosition - 1], M) || isOneOf(glyphs[newPosition - 1], H))
                                ) {
                                    newPosition--
                                }
                            }
                            if (newPosition > start && isHalant(glyphs[newPosition - 1])) {
                                if (newPosition < end && isJoiner(glyphs[newPosition])) newPosition++
                            }
                            val moved = glyphs.removeAt(j)
                            glyphs.add(newPosition, moved)
                            if (newPosition <= base && base < j) base++
                        }
                        break
                    }
                }
            }

            // "init" on a pre-base matra that begins a word.
            if (glyphs[start].position == POS_PRE_M) {
                val previous = if (start > 0) glyphs[start - 1].codePoint else -1
                if (start == 0 ||
                    !isLetterOrMark(previous)
                ) {
                    glyphs[start].mask = glyphs[start].mask or plan.maskOf("init")
                }
            }
        }
    }

    /** Where the reph of the syllable [start] until [end] goes, by the script's rule. */
    private fun rephTarget(glyphs: List<ShapingGlyph>, config: ScriptConfig, start: Int, end: Int, base: Int): Int {
        // After the first explicit halant between the reph and the base.
        fun afterHalant(): Int? {
            var position = start + 1
            while (position < base && !isHalant(glyphs[position])) position++
            if (position < base && isHalant(glyphs[position])) {
                if (position + 1 < base && isJoiner(glyphs[position + 1])) position++
                return position
            }
            return null
        }
        if (config.rephPosition != POS_AFTER_POST) {
            afterHalant()?.let { return it }
            if (config.rephPosition == POS_AFTER_MAIN) {
                var position = base
                while (position + 1 < end && glyphs[position + 1].position <= POS_AFTER_MAIN) position++
                if (position < end) return position
            }
            if (config.rephPosition == POS_AFTER_SUB) {
                var position = base
                while (position + 1 < end &&
                    glyphs[position + 1].position !in setOf(POS_POST_C, POS_AFTER_POST, POS_SMVD)
                ) {
                    position++
                }
                if (position < end) return position
            }
        }
        afterHalant()?.let { return it }
        // Otherwise at the end of the syllable, before its syllable modifiers.
        var position = end - 1
        while (position > start && glyphs[position].position == POS_SMVD) position--
        if (isHalant(glyphs[position])) {
            for (k in base + 1 until position) if (glyphs[k].category == M) position--
        }
        return position
    }

    // --------------------------------------------------------------- helpers

    /** The category test of the specifications, which a glyph a ligature formed no longer passes. */
    private fun isOneOf(glyph: ShapingGlyph, category: Int): Boolean = !glyph.ligated && glyph.category == category

    private fun isHalant(glyph: ShapingGlyph): Boolean = isOneOf(glyph, H)

    private fun isJoiner(glyph: ShapingGlyph): Boolean = isOneOf(glyph, ZWJ) || isOneOf(glyph, ZWNJ)

    private fun isConsonant(glyph: ShapingGlyph): Boolean = !glyph.ligated && glyph.category in CONSONANTS

    private fun isLetterOrMark(codePoint: Int): Boolean {
        if (codePoint < 0) return false
        if (UnicodeProperties.isMark(codePoint) || codePoint == 0x200C || codePoint == 0x200D) return true
        val script = UnicodeProperties.script(codePoint)
        return script != "Common" && script != "Inherited" && script != "Unknown"
    }

    /**
     * Whether the font's lookups for [feature] would replace exactly
     * [glyphs] — the question the shaper asks to know which forms the font
     * has: a ligature of exactly those glyphs, a single substitution of a
     * lone glyph, or a context rule with no context around the input.
     */
    private fun wouldSubstitute(plan: Plan, font: LayoutFont, feature: String, glyphs: IntArray): Boolean {
        val gsub = font.gsub ?: return false
        val lookups = plan.featureLookups[feature] ?: return false
        return lookups.any { LookupProbe.wouldApply(gsub, gsub.lookup(it), glyphs) }
    }
}
