/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.font.cff

import io.pdfkt.core.font.truetype.BigEndianReader
import io.pdfkt.core.font.truetype.BigEndianWriter

// Adobe Technical Note #5177 "The Type 2 Charstring Format" §3.2 "Charstring
// Number Encoding", §4 "Charstring Operators" (hintmask, callsubr, endchar),
// Appendix B "Type 2 Charstring Implementation Limits"; OpenType 1.9.1
// §"CFF2 Charstring Format" — "blend", "vsindex", "Changes from Type 2".

/**
 * Rewrites the charstring of one glyph as a self-contained Type 2 charstring
 * of a CFF (version 1) font: every subroutine call replaced by the body it
 * calls, and — for a CFF2 source — every `blend` reduced to its default
 * values and an `endchar` put at the end.
 *
 * Inlining the subroutines is what lets a subset carry only the glyphs it
 * draws: a font's subroutines are shared by thousands of glyphs, and keeping
 * them would keep most of the font. The charstring this produces draws exactly
 * what the original drew at the default instance — the operators and their
 * operands are copied byte for byte, and only calls, returns and blends, which
 * draw nothing, are taken out.
 *
 * The operand stack is followed only as far as that requires: which bytes
 * each operand was, for the subroutine number a call takes and the values a
 * blend drops, and how many operands each stem operator had, since "the
 * number of data bytes [of hintmask] … is exactly enough to hold the number
 * of hints" declared before it.
 */
internal class CharstringFlattener(private val font: CffFont) {
    /** The flattened charstring of [glyphId]. */
    fun flatten(glyphId: Int): ByteArray = Run(font, font.fontDictOf(glyphId)).run(font.charStrings[glyphId])

    private class Run(private val font: CffFont, private val dict: CffFontDict) {
        private val out = BigEndianWriter()

        /** Where in [out] each operand on the stack starts, bottom first. */
        private val operands = ArrayList<Int>()

        /** The value of each operand on the stack, for those read back. */
        private val values = ArrayList<Double>()
        private var stems = 0
        private var vsindex = dict.private.int(CffDict.VSINDEX) ?: 0
        private var depth = 0
        private var ended = false

        fun run(charstring: BigEndianReader): ByteArray {
            interpret(charstring)
            if (!ended) {
                // CFF2: "The charstring itself may end with a call(g)subr; the
                // subroutine must then end with an endchar operator" is the
                // Type 2 rule, and CFF2 has no endchar at all — a CFF font's
                // charstring has to finish with one.
                require(font.isCff2) { "a charstring that ends without endchar" }
                out.u8(ENDCHAR)
            }
            require(operands.size <= MAX_STACK) { "a charstring with ${operands.size} operands left at its end" }
            return out.toByteArray()
        }

        private fun interpret(code: BigEndianReader) {
            code.seek(0)
            while (code.position < code.size && !ended) {
                val b0 = code.uint8()
                when {
                    b0 == SHORT_INT -> number(code.int16().toDouble(), byteArrayOf(b0.toByte()), code, 2)
                    b0 in 32..246 -> push(byteArrayOf(b0.toByte()), (b0 - 139).toDouble())
                    b0 in 247..250 -> {
                        val b1 = code.uint8()
                        push(byteArrayOf(b0.toByte(), b1.toByte()), ((b0 - 247) * 256 + b1 + 108).toDouble())
                    }
                    b0 in 251..254 -> {
                        val b1 = code.uint8()
                        push(byteArrayOf(b0.toByte(), b1.toByte()), (-(b0 - 251) * 256 - b1 - 108).toDouble())
                    }
                    b0 == FIXED -> number(code.uint32().toInt() / 65536.0, byteArrayOf(b0.toByte()), code, 4)
                    else -> operator(b0, code)
                }
            }
        }

        /** An operand of [width] bytes after [lead], already read as [value]. */
        private fun number(value: Double, lead: ByteArray, code: BigEndianReader, width: Int) {
            val bytes = lead + code.sliceAt(code.position - width, width, "an operand").toByteArray()
            push(bytes, value)
        }

        private fun push(bytes: ByteArray, value: Double) {
            operands += out.size
            values += value
            out.bytes(bytes)
        }

        /** Drops the top [count] operands, bytes and all. */
        private fun drop(count: Int) {
            require(count <= operands.size) { "an operator needs $count operands; the stack has ${operands.size}" }
            if (count == 0) return
            val at = operands[operands.size - count]
            out.truncate(at)
            repeat(count) {
                operands.removeAt(operands.size - 1)
                values.removeAt(values.size - 1)
            }
        }

        private fun clear() {
            operands.clear()
            values.clear()
        }

        private fun operator(b0: Int, code: BigEndianReader) {
            when (b0) {
                CALLSUBR, CALLGSUBR -> {
                    require(values.isNotEmpty()) { "a subroutine call with no subroutine number" }
                    val subrs = if (b0 == CALLSUBR) dict.localSubrs else font.globalSubrs
                    val number = values.last().toInt() + bias(subrs.count)
                    drop(1)
                    require(number in 0 until subrs.count) {
                        "a call to subroutine $number of ${subrs.count} ${if (b0 == CALLSUBR) "local" else "global"} ones"
                    }
                    depth++
                    require(depth <= MAX_SUBR_DEPTH) { "subroutines nested deeper than $MAX_SUBR_DEPTH" }
                    interpret(subrs[number])
                    depth--
                }
                RETURN -> Unit // the inlined body just goes on
                ENDCHAR -> {
                    // "endchar … may have four extra arguments", the accented
                    // character of the Type 1 seac — two glyphs named by a
                    // standard encoding a CID-keyed font does not have.
                    require(operands.size < 4) {
                        "a charstring composing an accented character with endchar (the seac of Type 1); " +
                            "that names its two glyphs through a standard encoding that a CID-keyed font, " +
                            "which is what a subset is written as, does not have"
                    }
                    out.u8(ENDCHAR)
                    ended = true
                    clear()
                }
                BLEND -> {
                    require(font.isCff2) { "a blend in a CFF (version 1) charstring" }
                    val n = values.last().toInt()
                    val k = font.regionCount(vsindex)
                    drop(1)
                    // Keep the n defaults, drop the n × k deltas after them.
                    drop(n * k)
                }
                VSINDEX -> {
                    require(font.isCff2) { "a vsindex in a CFF (version 1) charstring" }
                    vsindex = values.last().toInt()
                    drop(1)
                }
                HSTEM, VSTEM, HSTEMHM, VSTEMHM -> {
                    stems += operands.size / 2
                    emit(b0)
                }
                HINTMASK, CNTRMASK -> {
                    // "If hstem and vstem hints are both declared at the
                    // beginning of a charstring, and this sequence is followed
                    // directly by the hintmask or cntrmask operators, the vstem
                    // hint operator need not be included."
                    stems += operands.size / 2
                    emit(b0)
                    val bytes = (stems + 7) / 8
                    out.bytes(code.sliceAt(code.position, bytes, "a hint mask").toByteArray())
                    code.skip(bytes)
                }
                ESCAPE -> {
                    val b1 = code.uint8()
                    require(operands.size <= MAX_STACK) { "an operator with ${operands.size} operands" }
                    out.u8(ESCAPE)
                    out.u8(b1)
                    clear()
                }
                else -> {
                    require(b0 in PATH_OPERATORS) { "the charstring byte $b0, which is no Type 2 operator" }
                    emit(b0)
                }
            }
        }

        private fun emit(operator: Int) {
            require(operands.size <= MAX_STACK) {
                "an operator with ${operands.size} operands, more than the $MAX_STACK of a Type 2 charstring"
            }
            out.u8(operator)
            clear()
        }

        /**
         * "The subroutine number … is added to a bias" that depends on how many
         * subroutines there are: 107 below 1240, 1131 below 33900, 32768 above.
         */
        private fun bias(count: Int): Int = when {
            count < 1240 -> 107
            count < 33900 -> 1131
            else -> 32768
        }
    }

    private companion object {
        const val HSTEM = 1
        const val VSTEM = 3
        const val CALLSUBR = 10
        const val RETURN = 11
        const val ESCAPE = 12
        const val ENDCHAR = 14
        const val VSINDEX = 15
        const val BLEND = 16
        const val HSTEMHM = 18
        const val HINTMASK = 19
        const val CNTRMASK = 20
        const val VSTEMHM = 23
        const val SHORT_INT = 28
        const val CALLGSUBR = 29
        const val FIXED = 255

        /** rmoveto, hmoveto, vmoveto, the lines and the curves. */
        val PATH_OPERATORS = setOf(4, 5, 6, 7, 8, 21, 22, 24, 25, 26, 27, 30, 31)

        /** Appendix B: "Argument stack 48". */
        const val MAX_STACK = 48

        /** Appendix B: "Subr nesting, stack limit 10". */
        const val MAX_SUBR_DEPTH = 10
    }
}
