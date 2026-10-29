/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.text

// Unicode Standard Annex #44 "Unicode Character Database" §5.7 "Property
// Value Aliases" (the value names), §4.2.9 "@missing" (the defaults).

/**
 * A character property over ranges of code points, decoded from one of the
 * [UnicodeTables] strings on first use and answered by binary search.
 */
internal class RangeProperty(encoded: String, private val default: Int) {
    private val firsts: IntArray
    private val lasts: IntArray
    private val values: IntArray

    init {
        val numbers = decode(encoded)
        val count = numbers.size / 3
        firsts = IntArray(count) { numbers[it * 3] }
        lasts = IntArray(count) { numbers[it * 3 + 1] }
        values = IntArray(count) { numbers[it * 3 + 2] }
    }

    operator fun get(codePoint: Int): Int {
        var low = 0
        var high = firsts.size - 1
        while (low <= high) {
            val middle = (low + high) ushr 1
            when {
                codePoint < firsts[middle] -> high = middle - 1
                codePoint > lasts[middle] -> low = middle + 1
                else -> return values[middle]
            }
        }
        return default
    }
}

/** Decodes a [UnicodeTables] string: base-36 numbers separated by commas. */
internal fun decode(encoded: String): IntArray {
    if (encoded.isEmpty()) return IntArray(0)
    val parts = encoded.split(',')
    return IntArray(parts.size) { parts[it].toInt(36) }
}

/** The Bidi_Class values, numbered as [UnicodeTables.BIDI_CLASSES] lists them. */
internal object BidiClass {
    const val L: Int = 0
    const val R: Int = 1
    const val AL: Int = 2
    const val EN: Int = 3
    const val ES: Int = 4
    const val ET: Int = 5
    const val AN: Int = 6
    const val CS: Int = 7
    const val NSM: Int = 8
    const val BN: Int = 9
    const val B: Int = 10
    const val S: Int = 11
    const val WS: Int = 12
    const val ON: Int = 13
    const val LRE: Int = 14
    const val LRO: Int = 15
    const val RLE: Int = 16
    const val RLO: Int = 17
    const val PDF: Int = 18
    const val LRI: Int = 19
    const val RLI: Int = 20
    const val FSI: Int = 21
    const val PDI: Int = 22
}

/** The Joining_Type values: "U Non_Joining, R Right_Joining, L Left_Joining, D Dual_Joining, C Join_Causing, T Transparent". */
internal object JoiningType {
    const val U: Int = 0
    const val R: Int = 1
    const val L: Int = 2
    const val D: Int = 3
    const val C: Int = 4
    const val T: Int = 5
}

/**
 * The properties of a character that text layout asks about, over the tables
 * [UnicodeTables] holds. Each table is decoded the first time it is asked.
 */
internal object UnicodeProperties {
    private val bidiClass by lazy { RangeProperty(UnicodeTables.BIDI_CLASS, BidiClass.L) }
    private val joiningType by lazy { RangeProperty(UnicodeTables.JOINING_TYPE, JoiningType.U) }
    private val script by lazy { RangeProperty(UnicodeTables.SCRIPT, unknownScript) }
    private val combiningClass by lazy { RangeProperty(UnicodeTables.COMBINING_CLASS, 0) }
    private val mark by lazy { RangeProperty(UnicodeTables.MARK, -1) }
    private val indicSyllabic by lazy { RangeProperty(UnicodeTables.INDIC_SYLLABIC_CATEGORY, 0) }
    private val indicPositional by lazy { RangeProperty(UnicodeTables.INDIC_POSITIONAL_CATEGORY, 0) }

    private val unknownScript: Int = UnicodeTables.SCRIPTS.indexOf("Unknown").let { if (it < 0) -1 else it }

    private val mirrors: Map<Int, Int> by lazy { pairs(decode(UnicodeTables.MIRRORING)) }

    private val brackets: Map<Int, Pair<Int, Boolean>> by lazy {
        val numbers = decode(UnicodeTables.BRACKETS)
        buildMap { for (i in numbers.indices step 3) put(numbers[i], numbers[i + 1] to (numbers[i + 2] == 0)) }
    }

    private val decompositions: Map<Int, IntArray> by lazy {
        val numbers = decode(UnicodeTables.DECOMPOSITIONS)
        buildMap {
            for (i in numbers.indices step 3) {
                val second = numbers[i + 2] ushr 1
                put(numbers[i], if (second == 0) intArrayOf(numbers[i + 1]) else intArrayOf(numbers[i + 1], second))
            }
        }
    }

    private val compositions: Map<Long, Int> by lazy {
        val numbers = decode(UnicodeTables.DECOMPOSITIONS)
        buildMap {
            for (i in numbers.indices step 3) {
                val second = numbers[i + 2] ushr 1
                val excluded = numbers[i + 2] and 1 == 1
                if (second != 0 && !excluded) put(pairKey(numbers[i + 1], second), numbers[i])
            }
        }
    }

    fun bidiClass(codePoint: Int): Int = bidiClass[codePoint]

    fun joiningType(codePoint: Int): Int = joiningType[codePoint]

    /** Canonical_Combining_Class: 0 for a starter, the class of a combining mark otherwise. */
    fun combiningClass(codePoint: Int): Int = combiningClass[codePoint]

    /** The Script property, as its long name: "Latin", "Arabic", "Devanagari", "Common", "Inherited". */
    fun script(codePoint: Int): String = script[codePoint].let { if (it < 0) "Unknown" else UnicodeTables.SCRIPTS[it] }

    /** Whether the General_Category is a mark: Mn, Mc or Me. */
    fun isMark(codePoint: Int): Boolean = mark[codePoint] >= 0

    /** Whether the General_Category is Mn, a nonspacing mark. */
    fun isNonspacingMark(codePoint: Int): Boolean = mark[codePoint] == 0

    fun indicSyllabicCategory(codePoint: Int): String =
        UnicodeTables.INDIC_SYLLABIC_CATEGORIES[indicSyllabic[codePoint]]

    fun indicPositionalCategory(codePoint: Int): String =
        UnicodeTables.INDIC_POSITIONAL_CATEGORIES[indicPositional[codePoint]]

    /** Bidi_Mirroring_Glyph, or null for a character with none. */
    fun mirror(codePoint: Int): Int? = mirrors[codePoint]

    /** Bidi_Paired_Bracket and whether the bracket opens, or null for a character that is not one. */
    fun pairedBracket(codePoint: Int): Pair<Int, Boolean>? = brackets[codePoint]

    /** The canonical decomposition of [codePoint], one level deep, or null. */
    fun decomposition(codePoint: Int): IntArray? = decompositions[codePoint]

    /** The character that canonically decomposes to [first] [second], or null. */
    fun composition(first: Int, second: Int): Int? = compositions[pairKey(first, second)]

    /**
     * Default_Ignorable_Code_Point, for the characters a shaper meets: they
     * "should be rendered … as completely invisible" when a font has no glyph
     * for them (UAX #44 §5.7.1; The Unicode Standard §5.21).
     */
    fun isDefaultIgnorable(codePoint: Int): Boolean = when (codePoint) {
        0x00AD, 0x034F, 0x061C, 0x115F, 0x1160, 0x17B4, 0x17B5, 0x3164, 0xFEFF, 0xFFA0 -> true
        in 0x180B..0x180F, in 0x200B..0x200F, in 0x202A..0x202E, in 0x2060..0x206F,
        in 0xFE00..0xFE0F, in 0xFFF0..0xFFF8, in 0x1BCA0..0x1BCA3, in 0x1D173..0x1D17A,
        in 0xE0000..0xE0FFF,
        -> true
        else -> false
    }

    private fun pairs(numbers: IntArray): Map<Int, Int> =
        buildMap { for (i in numbers.indices step 2) put(numbers[i], numbers[i + 1]) }

    private fun pairKey(a: Int, b: Int): Long = (a.toLong() shl 32) or b.toLong()
}
