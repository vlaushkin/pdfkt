/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.text

import io.pdfkt.core.text.BidiClass.AL
import io.pdfkt.core.text.BidiClass.AN
import io.pdfkt.core.text.BidiClass.B
import io.pdfkt.core.text.BidiClass.BN
import io.pdfkt.core.text.BidiClass.CS
import io.pdfkt.core.text.BidiClass.EN
import io.pdfkt.core.text.BidiClass.ES
import io.pdfkt.core.text.BidiClass.ET
import io.pdfkt.core.text.BidiClass.FSI
import io.pdfkt.core.text.BidiClass.L
import io.pdfkt.core.text.BidiClass.LRE
import io.pdfkt.core.text.BidiClass.LRI
import io.pdfkt.core.text.BidiClass.LRO
import io.pdfkt.core.text.BidiClass.NSM
import io.pdfkt.core.text.BidiClass.ON
import io.pdfkt.core.text.BidiClass.PDF
import io.pdfkt.core.text.BidiClass.PDI
import io.pdfkt.core.text.BidiClass.R
import io.pdfkt.core.text.BidiClass.RLE
import io.pdfkt.core.text.BidiClass.RLI
import io.pdfkt.core.text.BidiClass.RLO
import io.pdfkt.core.text.BidiClass.S
import io.pdfkt.core.text.BidiClass.WS

// Unicode Standard Annex #9 "Unicode Bidirectional Algorithm": §3.3.1 "The
// Paragraph Level" (P2, P3), §3.3.2 "Explicit Levels and Directions" (X1–X8),
// §3.3.3 (X9), §3.3.4 (X10), §3.3.5 "Resolving Weak Types" (W1–W7), §3.3.6
// "Resolving Neutral and Isolate Formatting Types" (N0–N2, BD16), §3.3.7
// "Resolving Implicit Levels" (I1, I2), §3.4 "Reordering Resolved Levels" (L1, L2).

/**
 * The embedding levels of one paragraph, by the Unicode Bidirectional
 * Algorithm: what makes Hebrew and Arabic run right to left among Latin and
 * digits, and the numbers in them left to right.
 *
 * @property codePoints the paragraph's characters.
 * @property paragraphLevel 0 for a left-to-right paragraph, 1 for a
 *   right-to-left one — given, or found by P2 and P3 from the first strong
 *   character.
 */
internal class BidiParagraph(val codePoints: IntArray, requestedLevel: Int?) {
    private val originalTypes = IntArray(codePoints.size) { UnicodeProperties.bidiClass(codePoints[it]) }
    private val types = originalTypes.copyOf()
    private val matchingPdi = IntArray(codePoints.size) { -1 }
    private val matchingInitiator = IntArray(codePoints.size) { -1 }

    val paragraphLevel: Int

    /** The level of each character; characters X9 removes keep the level of what precedes them. */
    val levels: IntArray = IntArray(codePoints.size)

    /** Whether X9 removed the character: embeddings, overrides, PDF and boundary neutrals. */
    val removed: BooleanArray = BooleanArray(codePoints.size)

    init {
        matchIsolates()
        paragraphLevel = requestedLevel ?: firstStrongLevel(0, codePoints.size, default = 0)
        explicitLevels()
        for (sequence in isolatingRunSequences()) sequence.resolve()
        for (i in codePoints.indices) {
            if (removed[i]) continue
            val level = levels[i]
            levels[i] = when (types[i]) {
                L -> if (level % 2 == 0) level else level + 1
                R -> if (level % 2 == 0) level + 1 else level
                AN, EN -> if (level % 2 == 0) level + 2 else level + 1
                else -> level
            }
        }
        // A removed character takes the level of the character before it, so
        // a line through it is not cut in two — "X9 … may be retained … with
        // the embedding level of the preceding character".
        for (i in codePoints.indices) {
            if (removed[i]) levels[i] = if (i > 0) levels[i - 1] else paragraphLevel
        }
    }

    // ------------------------------------------------------------ paragraphs

    /** BD9: each isolate initiator's matching PDI, and back. */
    private fun matchIsolates() {
        val stack = ArrayList<Int>()
        for (i in codePoints.indices) {
            when (originalTypes[i]) {
                LRI, RLI, FSI -> stack += i
                PDI -> if (stack.isNotEmpty()) {
                    val initiator = stack.removeAt(stack.size - 1)
                    matchingPdi[initiator] = i
                    matchingInitiator[i] = initiator
                }
                B -> stack.clear()
            }
        }
    }

    /** P2, P3: the level the first strong character between [from] and [to] gives, isolates skipped. */
    private fun firstStrongLevel(from: Int, to: Int, default: Int): Int {
        var i = from
        while (i < to) {
            when (originalTypes[i]) {
                L -> return 0
                R, AL -> return 1
                LRI, RLI, FSI -> {
                    val pdi = matchingPdi[i]
                    if (pdi < 0) return default
                    i = pdi
                }
                B -> return default
            }
            i++
        }
        return default
    }

    // ------------------------------------------------------- explicit levels

    private class Status(val level: Int, val override: Int, val isolate: Boolean)

    /** X1–X9. */
    private fun explicitLevels() {
        val stack = ArrayList<Status>()
        stack += Status(paragraphLevel, ON, false)
        var overflowIsolates = 0
        var overflowEmbeddings = 0
        var validIsolates = 0
        for (i in codePoints.indices) {
            val top = stack.last()
            when (val type = originalTypes[i]) {
                RLE, LRE, RLO, LRO -> {
                    val level = if (type == RLE || type == RLO) nextOdd(top.level) else nextEven(top.level)
                    levels[i] = top.level
                    removed[i] = true
                    if (level <= MAX_DEPTH && overflowIsolates == 0 && overflowEmbeddings == 0) {
                        stack += Status(
                            level,
                            if (type == RLO) {
                                R
                            } else if (type == LRO) {
                                L
                            } else {
                                ON
                            },
                            false,
                        )
                    } else if (overflowIsolates == 0) {
                        overflowEmbeddings++
                    }
                }
                RLI, LRI, FSI -> {
                    levels[i] = top.level
                    if (top.override != ON) types[i] = top.override
                    val rtl = when (type) {
                        RLI -> true
                        LRI -> false
                        else -> firstStrongLevel(
                            i + 1,
                            matchingPdi[i].let {
                                if (it <
                                    0
                                ) {
                                    codePoints.size
                                } else {
                                    it
                                }
                            },
                            0,
                        ) ==
                            1
                    }
                    val level = if (rtl) nextOdd(top.level) else nextEven(top.level)
                    if (level <= MAX_DEPTH && overflowIsolates == 0 && overflowEmbeddings == 0) {
                        validIsolates++
                        stack += Status(level, ON, true)
                    } else {
                        overflowIsolates++
                    }
                }
                PDI -> {
                    if (overflowIsolates > 0) {
                        overflowIsolates--
                    } else if (validIsolates > 0) {
                        overflowEmbeddings = 0
                        while (!stack.last().isolate) stack.removeAt(stack.size - 1)
                        stack.removeAt(stack.size - 1)
                        validIsolates--
                    }
                    val current = stack.last()
                    levels[i] = current.level
                    if (current.override != ON) types[i] = current.override
                }
                PDF -> {
                    levels[i] = top.level
                    removed[i] = true
                    if (overflowIsolates > 0) {
                        Unit
                    } else if (overflowEmbeddings > 0) {
                        overflowEmbeddings--
                    } else if (!top.isolate && stack.size >= 2) {
                        stack.removeAt(stack.size - 1)
                    }
                }
                B -> levels[i] = paragraphLevel
                BN -> {
                    levels[i] = top.level
                    removed[i] = true
                }
                else -> {
                    levels[i] = top.level
                    if (top.override != ON) types[i] = top.override
                }
            }
        }
    }

    // ------------------------------------------------ isolating run sequences

    /** X10: the level runs, chained across isolates into isolating run sequences. */
    private fun isolatingRunSequences(): List<RunSequence> {
        val runs = ArrayList<IntArray>()
        var current = ArrayList<Int>()
        var currentLevel = -1
        for (i in codePoints.indices) {
            if (removed[i]) continue
            if (levels[i] != currentLevel && current.isNotEmpty()) {
                runs += current.toIntArray()
                current = ArrayList()
            }
            currentLevel = levels[i]
            current += i
        }
        if (current.isNotEmpty()) runs += current.toIntArray()

        val runOf = HashMap<Int, Int>()
        runs.forEachIndexed { index, run -> runOf[run.first()] = index }
        val sequences = ArrayList<RunSequence>()
        for (run in runs) {
            val first = run.first()
            // A run that begins with a matched PDI continues the sequence of its initiator.
            if (originalTypes[first] == PDI && matchingInitiator[first] >= 0) continue
            val indices = ArrayList<Int>()
            var next: IntArray? = run
            while (next != null) {
                indices.addAll(next.toList())
                val last = next.last()
                val pdi = if (originalTypes[last] in ISOLATE_INITIATORS) matchingPdi[last] else -1
                next = if (pdi >= 0) runOf[pdi]?.let { runs[it] } else null
            }
            sequences += RunSequence(indices.toIntArray())
        }
        return sequences
    }

    private inner class RunSequence(val indices: IntArray) {
        val level = levels[indices[0]]
        val sos: Int
        val eos: Int
        val sequenceTypes = IntArray(indices.size) { types[indices[it]] }

        init {
            val first = indices.first()
            var before = first - 1
            while (before >= 0 && removed[before]) before--
            val beforeLevel = if (before >= 0) levels[before] else paragraphLevel
            sos = if (maxOf(beforeLevel, level) % 2 == 0) L else R
            val last = indices.last()
            var after = last + 1
            while (after < codePoints.size && removed[after]) after++
            val afterLevel = if (after < codePoints.size && !(originalTypes[last] in ISOLATE_INITIATORS)) {
                levels[after]
            } else {
                paragraphLevel
            }
            eos = if (maxOf(afterLevel, level) % 2 == 0) L else R
        }

        fun resolve() {
            weakTypes()
            brackets()
            neutrals()
            for (k in indices.indices) types[indices[k]] = sequenceTypes[k]
        }

        private fun weakTypes() {
            val t = sequenceTypes
            // W1
            for (k in t.indices) {
                if (t[k] == NSM) {
                    t[k] = if (k == 0) {
                        sos
                    } else if (t[k - 1] in ISOLATES) {
                        ON
                    } else {
                        t[k - 1]
                    }
                }
            }
            // W2
            var lastStrong = sos
            for (k in t.indices) {
                when (t[k]) {
                    L, R, AL -> lastStrong = t[k]
                    EN -> if (lastStrong == AL) t[k] = AN
                }
            }
            // W3
            for (k in t.indices) if (t[k] == AL) t[k] = R
            // W4
            for (k in 1 until t.size - 1) {
                if (t[k] == ES && t[k - 1] == EN && t[k + 1] == EN) {
                    t[k] = EN
                } else if (t[k] == CS && t[k - 1] == EN && t[k + 1] == EN) {
                    t[k] = EN
                } else if (t[k] == CS && t[k - 1] == AN && t[k + 1] == AN) {
                    t[k] = AN
                }
            }
            // W5
            var k = 0
            while (k < t.size) {
                if (t[k] == ET) {
                    var end = k
                    while (end < t.size && t[end] == ET) end++
                    val adjacent = (k > 0 && t[k - 1] == EN) || (end < t.size && t[end] == EN)
                    if (adjacent) for (m in k until end) t[m] = EN
                    k = end
                } else {
                    k++
                }
            }
            // W6
            for (m in t.indices) if (t[m] == ES || t[m] == ET || t[m] == CS) t[m] = ON
            // W7
            lastStrong = sos
            for (m in t.indices) {
                when (t[m]) {
                    L, R -> lastStrong = t[m]
                    EN -> if (lastStrong == L) t[m] = L
                }
            }
        }

        /** N0 over the bracket pairs of BD16. */
        private fun brackets() {
            val t = sequenceTypes
            val embedding = if (level % 2 == 0) L else R
            val pairs = ArrayList<Pair<Int, Int>>()
            val stack = ArrayList<Pair<Int, Int>>() // closing bracket expected, position
            for (k in indices.indices) {
                if (t[k] != ON) continue
                val (pair, opens) = UnicodeProperties.pairedBracket(codePoints[indices[k]]) ?: continue
                val own = canonicalBracket(codePoints[indices[k]])
                if (opens) {
                    if (stack.size == MAX_BRACKETS) break
                    stack += canonicalBracket(pair) to k
                } else {
                    for (s in stack.indices.reversed()) {
                        if (stack[s].first == own) {
                            pairs += stack[s].second to k
                            while (stack.size > s) stack.removeAt(stack.size - 1)
                            break
                        }
                    }
                }
            }
            pairs.sortBy { it.first }
            for ((open, close) in pairs) {
                var foundEmbedding = false
                var foundOpposite = false
                for (k in open + 1 until close) {
                    val strong = strongOf(t[k]) ?: continue
                    if (strong == embedding) foundEmbedding = true else foundOpposite = true
                }
                val direction = when {
                    foundEmbedding -> embedding
                    foundOpposite -> {
                        var before = sos
                        for (k in open - 1 downTo 0) {
                            val strong = strongOf(t[k]) ?: continue
                            before = strong
                            break
                        }
                        if (before != embedding) before else embedding
                    }
                    else -> null
                } ?: continue
                t[open] = direction
                t[close] = direction
                for (k in open + 1 until t.size) {
                    if (originalTypes[indices[k]] != NSM) break
                    t[k] = direction
                }
                for (k in close + 1 until t.size) {
                    if (originalTypes[indices[k]] != NSM) break
                    t[k] = direction
                }
            }
        }

        private fun strongOf(type: Int): Int? = when (type) {
            L -> L
            R, AL, EN, AN -> R
            else -> null
        }

        /** N1, N2. */
        private fun neutrals() {
            val t = sequenceTypes
            val embedding = if (level % 2 == 0) L else R
            var k = 0
            while (k < t.size) {
                if (t[k] !in NEUTRALS) {
                    k++
                    continue
                }
                var end = k
                while (end < t.size && t[end] in NEUTRALS) end++
                val before = if (k == 0) sos else strongOf(t[k - 1]) ?: embedding
                val after = if (end == t.size) eos else strongOf(t[end]) ?: embedding
                val resolved = if (before == after) before else embedding
                for (m in k until end) t[m] = resolved
                k = end
            }
        }
    }

    // --------------------------------------------------------------- lines

    /**
     * The levels of the line [start] until [end] after L1: segment and
     * paragraph separators, and the whitespace before them and at the end of
     * the line, at the paragraph level.
     */
    fun lineLevels(start: Int, end: Int): IntArray {
        val line = levels.copyOfRange(start, end)
        var trailing = true
        for (i in end - 1 downTo start) {
            val type = originalTypes[i]
            when {
                type == S || type == B -> {
                    line[i - start] = paragraphLevel
                    trailing = true
                }
                trailing && (type == WS || type in ISOLATES || removed[i]) -> line[i - start] = paragraphLevel
                else -> trailing = false
            }
        }
        return line
    }

    companion object {
        /** BD2: "max_depth … 125". */
        const val MAX_DEPTH: Int = 125

        /** BD16: "a stack … of 63 elements". */
        const val MAX_BRACKETS: Int = 63

        private val ISOLATE_INITIATORS = setOf(LRI, RLI, FSI)
        private val ISOLATES = setOf(LRI, RLI, FSI, PDI)
        private val NEUTRALS = setOf(B, S, WS, ON, LRI, RLI, FSI, PDI)

        private fun nextOdd(level: Int): Int = if (level % 2 == 0) level + 1 else level + 2

        private fun nextEven(level: Int): Int = if (level % 2 == 0) level + 2 else level + 1

        /** BD16: U+2329 and U+232A match U+3008 and U+3009, their canonical equivalents. */
        private fun canonicalBracket(codePoint: Int): Int = when (codePoint) {
            0x2329 -> 0x3008
            0x232A -> 0x3009
            else -> codePoint
        }

        /**
         * L2: the visual order of a line whose characters have [levels] —
         * "From the highest level … to the lowest odd level on each line,
         * reverse any contiguous sequence of characters that are at that
         * level or higher." The result lists the logical indices left to right.
         */
        fun visualOrder(levels: IntArray): IntArray {
            val order = IntArray(levels.size) { it }
            if (levels.isEmpty()) return order
            val highest = levels.max()
            val lowestOdd = levels.filter { it % 2 == 1 }.minOrNull() ?: return order
            for (level in highest downTo lowestOdd) {
                var i = 0
                while (i < order.size) {
                    if (levels[order[i]] >= level) {
                        var end = i
                        while (end < order.size && levels[order[end]] >= level) end++
                        order.reverse(i, end)
                        i = end
                    } else {
                        i++
                    }
                }
            }
            return order
        }
    }
}
