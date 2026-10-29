/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.font.truetype

// OpenType 1.9.1 §"OpenType font file" — "Data Types",
// "Organization of an OpenType Font" (four-byte boundaries)

/**
 * The counterpart of [BigEndianReader]: a growing buffer that writes the types
 * the OpenType specification is written in, in the byte order the format states
 * once for all of it — "All OpenType fonts use big-endian (network) byte order".
 *
 * It exists because this library writes font programs as well as reading them:
 * a subset (see [TrueTypeSubset]) is a table directory and a handful of tables
 * assembled from scratch. Nothing here knows what a table is; the alignment rule
 * of the clause — "All tables must begin on four-byte boundaries, and any
 * remaining space between tables must be padded with zeros" — is the one piece
 * of the format that shows up, as [padToFourByteBoundary].
 */
internal class BigEndianWriter(initialCapacity: Int = INITIAL_CAPACITY) {
    private var buffer: ByteArray = ByteArray(maxOf(initialCapacity, 1))

    /** How many bytes have been written, which is also where the next one goes. */
    var size: Int = 0
        private set

    /** `uint8` — "8-bit unsigned integer". */
    fun u8(value: Int) {
        reserve(1)
        buffer[size] = (value and 0xFF).toByte()
        size += 1
    }

    /** `uint16` — "16-bit unsigned integer". */
    fun u16(value: Int) {
        u8(value ushr 8)
        u8(value)
    }

    /** `int16`, `FWORD` — the two's-complement bytes of [value]. */
    fun i16(value: Int) {
        u16(value and 0xFFFF)
    }

    /** `uint32`, `Offset32` — "32-bit unsigned integer". */
    fun u32(value: Long) {
        u16(((value ushr 16) and 0xFFFF).toInt())
        u16((value and 0xFFFF).toInt())
    }

    /**
     * `Tag` — "Array of four uint8s … Each byte within the array must have a
     * value in the range 0x20 to 0x7E". Both are checked here rather than
     * trusted, so a tag this library writes is one [BigEndianReader.tag] reads
     * back.
     */
    fun tag(value: String) {
        require(value.length == 4) { "a tag is four bytes, and \"$value\" is ${value.length} characters" }
        for (character in value) {
            require(character.code in TAG_BYTE_MIN..TAG_BYTE_MAX) {
                "the tag \"$value\" has the character ${hex(character.code.toLong())}, outside the " +
                    "${hex(TAG_BYTE_MIN.toLong())}..${hex(TAG_BYTE_MAX.toLong())} a tag's bytes are " +
                    "required to be in"
            }
            u8(character.code)
        }
    }

    /** Appends [value] verbatim — a table carried across into a subset. */
    fun bytes(value: ByteArray) {
        reserve(value.size)
        value.copyInto(buffer, size)
        size += value.size
    }

    /** Takes back everything written from [length] on. */
    fun truncate(length: Int) {
        require(length in 0..size) { "cannot cut $size bytes down to $length" }
        size = length
    }

    /** Appends [count] zero bytes. */
    fun zeros(count: Int) {
        require(count >= 0) { "cannot write $count zero bytes" }
        reserve(count)
        buffer.fill(0, size, size + count)
        size += count
    }

    /**
     * Pads with zeros up to the next four-byte boundary, which is where the next
     * table may begin: "All tables must begin on four-byte boundaries, and any
     * remaining space between tables must be padded with zeros."
     */
    fun padToFourByteBoundary() {
        zeros((FOUR_BYTE_BOUNDARY - size % FOUR_BYTE_BOUNDARY) % FOUR_BYTE_BOUNDARY)
    }

    /** The bytes written, as an array of exactly [size] bytes. */
    fun toByteArray(): ByteArray = buffer.copyOf(size)

    private fun reserve(count: Int) {
        if (size + count <= buffer.size) return
        var capacity = buffer.size
        while (capacity < size + count) capacity *= 2
        buffer = buffer.copyOf(capacity)
    }

    private companion object {
        const val INITIAL_CAPACITY: Int = 256
        const val TAG_BYTE_MIN: Int = 0x20
        const val TAG_BYTE_MAX: Int = 0x7E
        const val FOUR_BYTE_BOUNDARY: Int = 4
    }
}

/**
 * Writes [value] as a `uint16` at [offset] of this array.
 *
 * Font tables are written once and then corrected in place twice: a table
 * carried across from the font being subset has a count in it that the subset
 * changes ([TrueTypeSubset]), and the `head` table's `checksumAdjustment` cannot
 * be known until the bytes it is a checksum of exist.
 */
internal fun ByteArray.putUint16(offset: Int, value: Int) {
    require(offset >= 0 && offset + 2 <= size) {
        "a uint16 cannot be written at offset $offset of a $size-byte table"
    }
    this[offset] = ((value ushr 8) and 0xFF).toByte()
    this[offset + 1] = (value and 0xFF).toByte()
}

/** Writes [value] as a `uint32` at [offset] of this array. */
internal fun ByteArray.putUint32(offset: Int, value: Long) {
    require(offset >= 0 && offset + 4 <= size) {
        "a uint32 cannot be written at offset $offset of a $size-byte table"
    }
    putUint16(offset, ((value ushr 16) and 0xFFFF).toInt())
    putUint16(offset + 2, (value and 0xFFFF).toInt())
}
