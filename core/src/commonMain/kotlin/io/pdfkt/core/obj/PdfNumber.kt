/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.obj

import kotlinx.io.Sink
import kotlinx.io.writeString
import kotlin.jvm.JvmInline

// ISO 32000-2:2020 §7.3.3 "Numeric objects"

/**
 * An integer object — "Integer objects represent mathematical integers".
 *
 * Backed by a [Long] rather than an [Int]. Annex C, "Advice on maximising
 * portability", observes only that integer values *can often* be expressed
 * within 32 bits; it is informative advice to writers about what processors
 * are likely to accept, not a limit the clause imposes, and clause 7 itself
 * needs 64 bits in places — a cross-reference stream's byte offset (§7.5.8)
 * on a file larger than 2 GB is an integer object that does not fit in 32.
 * Nothing is clamped here: what a caller puts in is what comes out.
 */
@JvmInline
public value class PdfInt(public val value: Long) : PdfObject

/**
 * A real object — "Real objects represent mathematical real numbers".
 *
 * NaN and the infinities are rejected at construction. They are not
 * mathematical real numbers, the grammar of the clause — digits, an optional
 * sign and a period — has no spelling for them, and the clause forbids the
 * exponential form in which a writer might be tempted to smuggle one out.
 * Rejecting at construction rather than at write time puts the failure where
 * the bad value was computed, which is the only place it can be fixed; the
 * alternative, writing something a processor would reject or silently read as
 * a different number, is the silent loss this library's design rules out.
 *
 * Precision is documented on [writePdfReal]: a real object holds the double it
 * was given, and rounding happens once, when it is written.
 */
@JvmInline
public value class PdfReal(public val value: Double) : PdfObject {
    init {
        require(!value.isNaN() && !value.isInfinite()) {
            "a real object represents a mathematical real number; " +
                "$value has no representation in ISO 32000-2:2020 §7.3.3"
        }
    }
}

/**
 * Writes an integer object: "one or more decimal digits optionally preceded by
 * a sign".
 *
 * The sign is written only when the value is negative. The clause admits a
 * leading PLUS SIGN — its Example 1 lists `+17` — but admitting a spelling is a
 * requirement on readers, not an instruction to writers, and of the two
 * spellings of a positive integer the shorter one is chosen everywhere here.
 *
 * [Long.toString] is a decimal expansion with no separators and no locale in
 * it — the radix-10 digits of the value and nothing else — on every target.
 * The same is emphatically not true of [Double.toString]; see [writePdfReal].
 */
internal fun Sink.writePdfInt(obj: PdfInt) {
    writeString(obj.value.toString())
}

/**
 * Writes a real object: "one or more decimal digits with an optional sign and a
 * leading, trailing, or embedded PERIOD (2Eh) (decimal point)".
 *
 * The clause closes with a prohibition that decides the whole implementation:
 *
 * > A PDF writer shall not use the PostScript language syntax for numbers with
 * > non-decimal radices (such as 16#FFFE) or in exponential format (such as
 * > 6.02E23).
 *
 * [Double.toString] produces exactly that forbidden form for large and small
 * magnitudes, and produces *different* forbidden forms per target — a JVM says
 * `1.0E20` where JS says `1e+20`, and a JVM says `4.0` where JS says `4`. So no
 * part of this comes from the platform: the digits are derived from the IEEE
 * 754 bits by integer arithmetic, which is what makes the bytes both
 * locale-independent and identical on JVM, Apple, JS and WasmJS. There is no
 * locale to be independent *of* in commonMain, but that is an accident of the
 * source set, not a property of the output; deriving the digits is what makes
 * it a property.
 *
 * The written form is canonical, so equal values write equal bytes:
 *
 * - the value is rounded to at most [SIGNIFICANT_DIGITS] significant decimal
 *   digits, ties to even;
 * - trailing zeros in the fraction are dropped, and with them the period, so a
 *   real whose value is integral is written as an integer. The clause allows
 *   this explicitly — "Wherever a real number is expected, an integer may be
 *   used instead. For example, it is not necessary to write the number 1.0 in
 *   real format; the integer 1 is sufficient" — and it is why the trailing
 *   period of the clause's own `4.` never needs to be written;
 * - the integer part is always written, so the leading period of the clause's
 *   `-.002` is likewise never needed: `-0.002`, one byte longer, is the same
 *   number for any reader;
 * - zero, including negative zero, is written `0`.
 *
 * No case produces an exponent, a radix marker or a separator, whatever the
 * magnitude: 1e-320 is written as a period and 320 digits rather than in the
 * one form the clause forbids.
 */
internal fun Sink.writePdfReal(obj: PdfReal) {
    writeString(formatReal(obj.value))
}

/**
 * The number of significant decimal digits kept when writing a real object.
 *
 * 15 is the largest number of significant decimal digits that survives the
 * decimal → binary → decimal round trip for every value: a number a caller
 * spelled with no more than 15 digits is written back exactly as they spelled
 * it, and the noise a binary representation adds below that — the tail of
 * digits that makes 0.1 + 0.2 into 0.30000000000000004 — rounds away. Keeping
 * the two or three digits beyond it that a double can also carry would write
 * that noise into every content stream in exchange for precision no PDF
 * processor is required to have: Annex C, "Advice on maximising portability",
 * notes that processors often represent real numbers in IEEE 754 *single*
 * precision, which is seven digits.
 *
 * A magnitude larger than 15 digits is therefore rounded — `PdfReal` is the
 * approximate half of the clause, and [PdfInt] is the exact half. An integer
 * that must survive intact is an integer object.
 */
private const val SIGNIFICANT_DIGITS = 15

/**
 * A decimal number held exactly, as an unsigned string of [digits] scaled by a
 * power of ten: the value is `digits * 10^-scale`. A negative [scale] is a
 * value with trailing zeros that are not written out yet.
 *
 * [digits] never carries a leading zero, so its length is the number of
 * significant digits of the value.
 */
private class Decimal(val digits: String, val scale: Int)

/** ISO 32000-2:2020 §7.3.3 — see [writePdfReal] for the rules this obeys. */
private fun formatReal(value: Double): String {
    if (value == 0.0) return "0" // also -0.0: the clause has one zero
    val text = render(round(exactValueOf(value)))
    return if (value < 0.0) "-$text" else text
}

/**
 * The exact decimal value of a finite, non-zero double.
 *
 * IEEE 754 gives the magnitude as `mantissa * 2^exponent` with an integer
 * mantissa, so it is a dyadic rational and its decimal expansion is finite:
 * for a positive exponent the value is `mantissa` doubled that many times, and
 * for a negative one `mantissa * 5^-exponent` scaled by `10^exponent`, since
 * `2^-n = 5^n / 10^n`. Both are integer arithmetic on decimal digits — no
 * rounding has happened yet at this point, and none can.
 */
private fun exactValueOf(value: Double): Decimal {
    val bits = value.toRawBits()
    val biasedExponent = ((bits ushr 52) and 0x7FF).toInt()
    val fraction = bits and 0x000F_FFFF_FFFF_FFFFL
    // A biased exponent of zero is a subnormal: no implicit leading one bit,
    // and the exponent is that of the smallest normal.
    val mantissa = if (biasedExponent == 0) fraction else fraction or (1L shl 52)
    val exponent = if (biasedExponent == 0) -1074 else biasedExponent - 1075

    val steps = if (exponent < 0) -exponent else exponent
    val factor = if (exponent < 0) 5 else 2
    val start = mantissa.toString()
    // Multiplying by 5 adds at most one digit per step, by 2 fewer than that.
    val digits = IntArray(start.length + steps + 1)
    for (i in start.indices) {
        digits[start.length - 1 - i] = start[i].code - '0'.code // least significant first
    }
    var length = start.length
    repeat(steps) {
        var carry = 0
        for (i in 0 until length) {
            val product = digits[i] * factor + carry
            digits[i] = product % 10
            carry = product / 10
        }
        while (carry != 0) {
            digits[length++] = carry % 10
            carry /= 10
        }
    }

    val builder = StringBuilder(length)
    for (i in length - 1 downTo 0) builder.append('0' + digits[i])
    return Decimal(builder.toString(), if (exponent < 0) steps else 0)
}

/** Rounds to [SIGNIFICANT_DIGITS], ties to even. */
private fun round(value: Decimal): Decimal {
    val digits = value.digits
    if (digits.length <= SIGNIFICANT_DIGITS) return value
    val kept = digits.substring(0, SIGNIFICANT_DIGITS)
    val scale = value.scale - (digits.length - SIGNIFICANT_DIGITS)
    return Decimal(if (roundsUp(digits, SIGNIFICANT_DIGITS)) increment(kept) else kept, scale)
}

/**
 * Whether dropping everything from [keep] onwards rounds the kept digits up:
 * past halfway it does, short of halfway it does not, and exactly halfway it
 * goes to the even digit rather than always away from zero, so that repeated
 * rounding of a set of numbers does not drift upwards.
 */
private fun roundsUp(digits: String, keep: Int): Boolean {
    val first = digits[keep]
    if (first != '5') return first > '5'
    for (i in keep + 1 until digits.length) {
        if (digits[i] != '0') return true
    }
    return (digits[keep - 1].code - '0'.code) % 2 != 0
}

/**
 * Adds one to a string of decimal digits. All nines carry into a new leading
 * digit — `999` becomes `1000`, one digit longer, which the caller's scale
 * still describes correctly.
 */
private fun increment(digits: String): String {
    val chars = digits.toCharArray()
    for (i in chars.indices.reversed()) {
        if (chars[i] != '9') {
            chars[i] = chars[i] + 1
            return chars.concatToString()
        }
        chars[i] = '0'
    }
    return "1" + chars.concatToString()
}

/**
 * Writes out the magnitude in positional notation — the only notation the
 * clause admits.
 */
private fun render(value: Decimal): String {
    var end = value.digits.length
    var scale = value.scale
    // A trailing zero in the fraction says nothing about the value.
    while (scale > 0 && value.digits[end - 1] == '0') {
        end--
        scale--
    }
    val digits = value.digits.substring(0, end)
    return when {
        // An integral value: no period, per the clause's "the integer 1 is
        // sufficient". A negative scale is the zeros rounding took away.
        scale <= 0 -> digits + "0".repeat(-scale)
        digits.length > scale ->
            digits.substring(0, digits.length - scale) + "." + digits.substring(digits.length - scale)
        // Smaller than one: the zeros between the period and the first
        // significant digit are part of the number.
        else -> "0." + "0".repeat(scale - digits.length) + digits
    }
}
