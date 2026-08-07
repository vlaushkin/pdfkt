/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.resolve

import io.pdfkt.core.block.ListMarkerStyle

// No clause applies: L4 is this library's own design. Marker text belongs to
// the engine, not to any caller: every consumer of the DSL gets the same
// markers because they are composed here, in L4.

/**
 * The text of the marker for item [number] of a list, counting from 1.
 *
 * Markers are text and nothing else: they are drawn with a font, measured with a
 * font, and refused by a font that has no glyph for them, exactly like the words
 * beside them. That is why the unordered styles are characters rather than drawn
 * shapes — a bullet the theme's font does not carry is a fact the caller should
 * hear about, not something the engine papers over with a hand-drawn circle.
 *
 * @param suffix what an ordered marker's number is followed by, `.` by default.
 */
internal fun listMarkerText(style: ListMarkerStyle, number: Int, suffix: String): String = when (style) {
    ListMarkerStyle.Bullet -> BULLET
    ListMarkerStyle.Dash -> EN_DASH
    ListMarkerStyle.Circle -> WHITE_BULLET
    ListMarkerStyle.Square -> BLACK_SMALL_SQUARE
    ListMarkerStyle.Decimal -> "$number$suffix"
    ListMarkerStyle.LowerAlpha -> alphabetic(number).lowercase() + suffix
    ListMarkerStyle.UpperAlpha -> alphabetic(number) + suffix
    ListMarkerStyle.LowerRoman -> roman(number).lowercase() + suffix
    ListMarkerStyle.UpperRoman -> roman(number) + suffix
}

/**
 * [number] in the alphabet: 1 is `A`, 26 is `Z`, 27 is `AA` — bijective base 26,
 * the counting an outline uses, where there is no digit for zero and so no `A0`
 * between `Z` and `AA`.
 *
 * A number below 1 has no letters to be written in and is written as itself; a
 * list that starts at zero is a list that asked for something the alphabet does
 * not do, and inventing an answer would hide it.
 */
private fun alphabetic(number: Int): String {
    if (number < 1) return number.toString()
    val letters = StringBuilder()
    var remaining = number
    while (remaining > 0) {
        val index = (remaining - 1) % ALPHABET_SIZE
        letters.append('A' + index)
        remaining = (remaining - 1) / ALPHABET_SIZE
    }
    return letters.reverse().toString()
}

/**
 * [number] in Roman numerals, in the subtractive form — 4 is `IV`, 9 is `IX`,
 * 1994 is `MCMXCIV`.
 *
 * Above 3999 the numerals simply repeat `M`, which is the additive form the
 * system had before overbars were invented for it: 4000 is `MMMM`. Below 1 there
 * is no numeral at all — Roman numerals have no zero and no sign — so the number
 * is written as itself rather than as nothing.
 */
private fun roman(number: Int): String {
    if (number < 1) return number.toString()
    val numerals = StringBuilder()
    var remaining = number
    while (remaining >= THOUSAND) {
        numerals.append('M')
        remaining -= THOUSAND
    }
    ROMAN_VALUES.forEachIndexed { index, value ->
        while (remaining >= value) {
            numerals.append(ROMAN_NUMERALS[index])
            remaining -= value
        }
    }
    return numerals.toString()
}

/** U+2022 BULLET, in the Latin character set of Annex D (Table D.2, code 200 octal). */
private const val BULLET: String = "•"

/** U+2013 EN DASH (Table D.2, code 261 octal). */
private const val EN_DASH: String = "–"

/** U+25E6 WHITE BULLET — outside Table D.2, so a standard 14 font refuses it. */
private const val WHITE_BULLET: String = "◦"

/** U+25AA BLACK SMALL SQUARE — likewise outside Table D.2. */
private const val BLACK_SMALL_SQUARE: String = "▪"

private const val ALPHABET_SIZE: Int = 26
private const val THOUSAND: Int = 1000

/** The values below a thousand, largest first, subtractive pairs included. */
private val ROMAN_VALUES: IntArray = intArrayOf(900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1)

private val ROMAN_NUMERALS: List<String> =
    listOf("CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I")
