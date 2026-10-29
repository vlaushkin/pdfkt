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

// Adobe Technical Note #5176 "The Compact Font Format Specification" §5 "INDEX
// Data", §4 "DICT Data"; OpenType 1.9.1 §"CFF2 — Compact Font Format (CFF)
// Version 2", "INDEX Data", "DICT Data", "blend operator".

/**
 * An INDEX — "an array of variable-sized objects" — read as the windows of its
 * objects. "An empty INDEX is represented by a count field with a 0 value and
 * no additional fields."
 *
 * @property end where the INDEX stops, which is where the structure after it
 *   starts: the four INDEXes after a CFF header follow one another with no
 *   offsets between them.
 */
internal class CffIndex(val items: List<BigEndianReader>, val end: Int) {
    val count: Int get() = items.size

    operator fun get(index: Int): BigEndianReader = items[index]

    companion object {
        /**
         * The INDEX at [offset] in [data]. CFF counts its objects in a `Card16`
         * and CFF2 in a `uint32` — "count: Number of objects stored in INDEX" is
         * the one field the two formats size differently.
         */
        fun read(data: BigEndianReader, offset: Int, wideCount: Boolean, what: String): CffIndex {
            data.seek(offset)
            val count = if (wideCount) data.uint32().toInt() else data.uint16()
            if (count == 0) return CffIndex(emptyList(), data.position)
            val offSize = data.uint8()
            require(offSize in 1..4) { "$what: an INDEX with offSize $offSize; it is 1 to 4" }
            val offsets = IntArray(count + 1) { readOffset(data, offSize) }
            // "Offsets in the offset array are relative to the byte that precedes
            // the object data", so the first offset is 1.
            val base = data.position - 1
            val items = (0 until count).map { item ->
                require(offsets[item + 1] >= offsets[item]) {
                    "$what: object $item ends at offset ${offsets[item + 1]}, before it starts at ${offsets[item]}"
                }
                data.sliceAt(base + offsets[item], offsets[item + 1] - offsets[item], "$what, object $item")
            }
            return CffIndex(items, base + offsets[count])
        }

        private fun readOffset(data: BigEndianReader, offSize: Int): Int {
            var value = 0
            repeat(offSize) { value = (value shl 8) or data.uint8() }
            return value
        }

        /** Writes [objects] as a CFF (version 1) INDEX — `Card16` count. */
        fun write(out: BigEndianWriter, objects: List<ByteArray>) {
            out.u16(objects.size)
            if (objects.isEmpty()) return
            val last = 1 + objects.sumOf { it.size }
            val offSize = when {
                last <= 0xFF -> 1
                last <= 0xFFFF -> 2
                last <= 0xFFFFFF -> 3
                else -> 4
            }
            out.u8(offSize)
            var offset = 1
            fun put(value: Int) {
                for (shift in (offSize - 1) downTo 0) out.u8((value ushr (shift * 8)) and 0xFF)
            }
            put(offset)
            for (item in objects) {
                offset += item.size
                put(offset)
            }
            for (item in objects) out.bytes(item)
        }

        /** The size [write] gives [objects]. */
        fun sizeOf(objects: List<ByteArray>): Int {
            if (objects.isEmpty()) return 2
            val last = 1 + objects.sumOf { it.size }
            val offSize = when {
                last <= 0xFF -> 1
                last <= 0xFFFF -> 2
                last <= 0xFFFFFF -> 3
                else -> 4
            }
            return 3 + (objects.size + 1) * offSize + last - 1
        }
    }
}

/**
 * One operand of a DICT: the bytes it was written as, which is how it is carried
 * across into a subset, and its value, for the operands this library reads.
 */
internal class CffOperand(val bytes: ByteArray, val value: Double) {
    val int: Int get() = value.toInt()
}

/** One entry of a DICT: "operators … preceded by their operands". */
internal class CffDictEntry(val operator: Int, val operands: List<CffOperand>)

/**
 * A DICT — "a compact representation of a dictionary of key/value pairs" — as
 * its entries in the order written.
 *
 * Operators are numbered as the specification numbers them, with the two-byte
 * ones ("escape", 12, then a second byte) as `1200 + b1`, so `FontMatrix`
 * (12 7) is [FONT_MATRIX] = 1207.
 */
internal class CffDict(val entries: List<CffDictEntry>) {
    fun entry(operator: Int): CffDictEntry? = entries.lastOrNull { it.operator == operator }

    fun ints(operator: Int): List<Int>? = entry(operator)?.operands?.map { it.int }

    fun int(operator: Int): Int? = ints(operator)?.lastOrNull()

    companion object {
        const val CHARSET: Int = 15
        const val CHAR_STRINGS: Int = 17
        const val PRIVATE: Int = 18
        const val SUBRS: Int = 19
        const val DEFAULT_WIDTH_X: Int = 20
        const val NOMINAL_WIDTH_X: Int = 21
        const val VSINDEX: Int = 22
        const val BLEND: Int = 23
        const val VSTORE: Int = 24
        const val FONT_BBOX: Int = 5
        const val FONT_MATRIX: Int = 1207
        const val CHARSTRING_TYPE: Int = 1206
        const val ROS: Int = 1230
        const val CID_COUNT: Int = 1234
        const val FD_ARRAY: Int = 1236
        const val FD_SELECT: Int = 1237

        private const val ESCAPE: Int = 12
        private const val SHORT_INT: Int = 28
        private const val LONG_INT: Int = 29
        private const val REAL: Int = 30

        /**
         * Reads the DICT [data] covers.
         *
         * @param regionCount for a CFF2 DICT, the number of variation regions
         *   of the item variation data `vsindex` selects. "The blend operator
         *   … takes n default values, n × k deltas, and n" — and a subset is the
         *   default instance, so only the n default values are kept: the entry
         *   reads as if the font had been written without variations.
         */
        fun read(data: BigEndianReader, regionCount: (Int) -> Int = { 0 }): CffDict {
            val entries = ArrayList<CffDictEntry>()
            val operands = ArrayList<CffOperand>()
            var vsindex = 0
            data.seek(0)
            while (data.position < data.size) {
                val start = data.position
                val b0 = data.uint8()
                when {
                    b0 == SHORT_INT -> operands += operandOf(data, start, data.int16().toDouble())
                    b0 == LONG_INT -> operands += operandOf(data, start, data.uint32().toInt().toDouble())
                    b0 == REAL -> operands += operandOf(data, start, readReal(data))
                    b0 in 32..246 -> operands += operandOf(data, start, (b0 - 139).toDouble())
                    b0 in 247..250 ->
                        operands +=
                            operandOf(data, start, ((b0 - 247) * 256 + data.uint8() + 108).toDouble())
                    b0 in 251..254 ->
                        operands +=
                            operandOf(data, start, (-(b0 - 251) * 256 - data.uint8() - 108).toDouble())
                    b0 <= 21 || b0 in 22..24 -> {
                        val operator = if (b0 == ESCAPE) 1200 + data.uint8() else b0
                        when (operator) {
                            BLEND -> {
                                val n = requireNotNull(operands.removeLastOrNull()) { "a blend with no operands" }.int
                                val k = regionCount(vsindex)
                                val all = n * (k + 1)
                                require(operands.size >= all) {
                                    "a blend of $n values over $k regions needs $all operands; it has ${operands.size}"
                                }
                                val first = operands.size - all
                                val defaults = operands.subList(first, first + n).toList()
                                while (operands.size > first) operands.removeAt(operands.size - 1)
                                operands += defaults
                            }
                            VSINDEX -> {
                                vsindex = requireNotNull(operands.lastOrNull()) { "a vsindex with no operand" }.int
                                operands.clear()
                            }
                            else -> {
                                entries += CffDictEntry(operator, operands.toList())
                                operands.clear()
                            }
                        }
                    }
                    else -> throw IllegalArgumentException(
                        "${data.what}: the byte $b0 at offset $start is neither an operand nor an operator",
                    )
                }
            }
            return CffDict(entries)
        }

        private fun operandOf(data: BigEndianReader, start: Int, value: Double): CffOperand {
            val end = data.position
            return CffOperand(data.sliceAt(start, end - start, "an operand").toByteArray(), value)
        }

        /** "A real number is terminated by one (or two) 0xf nibbles." */
        private fun readReal(data: BigEndianReader): Double {
            val text = StringBuilder()
            while (true) {
                val byte = data.uint8()
                for (nibble in intArrayOf(byte ushr 4, byte and 0xF)) {
                    when (nibble) {
                        in 0..9 -> text.append(nibble)
                        0xA -> text.append('.')
                        0xB -> text.append('E')
                        0xC -> text.append("E-")
                        0xE -> text.append('-')
                        0xF -> return text.toString().toDoubleOrNull() ?: 0.0
                    }
                }
            }
        }

        /**
         * Writes [value] in the five-byte form — "29 … b1, b2, b3, b4" — which
         * has one size for every value, so a DICT holding offsets can be laid out
         * before the offsets are known.
         */
        fun longInt(value: Int): ByteArray = byteArrayOf(
            LONG_INT.toByte(),
            (value ushr 24).toByte(),
            (value ushr 16).toByte(),
            (value ushr 8).toByte(),
            value.toByte(),
        )

        /** The bytes of [operator], one byte or the escape and a second. */
        fun operatorBytes(operator: Int): ByteArray = if (operator >=
            1200
        ) {
            byteArrayOf(ESCAPE.toByte(), (operator - 1200).toByte())
        } else {
            byteArrayOf(operator.toByte())
        }
    }
}

/** Builds a DICT's bytes, an entry at a time. */
internal class CffDictWriter {
    private val out = BigEndianWriter()

    fun entry(operator: Int, vararg operands: ByteArray) {
        operands.forEach { out.bytes(it) }
        out.bytes(CffDict.operatorBytes(operator))
    }

    fun entry(entry: CffDictEntry) {
        entry(entry.operator, *entry.operands.map { it.bytes }.toTypedArray())
    }

    fun toByteArray(): ByteArray = out.toByteArray()
}
