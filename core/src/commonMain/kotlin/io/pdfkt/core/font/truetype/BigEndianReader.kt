/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.font.truetype

// OpenType 1.9.1 §"OpenType font file" — "Data Types"
// ISO 32000-2:2020 §9.9 "Embedded font programs"

/**
 * A cursor over part of a font program, reading the types the OpenType
 * specification is written in.
 *
 * The specification states the byte order once, for the whole format, and every
 * field of every table follows it:
 *
 * > All OpenType fonts use big-endian (network) byte order.
 *
 * So there is one reader and no per-table byte handling. Its types are the
 * "Data Types" table of the specification, spelled as the Kotlin type that can
 * actually hold them: a `uint16` is an `Int` because a `Short` is signed and
 * would turn 0xFFFF — the last segment of every `cmap` format 4 subtable — into
 * −1; a `uint32` is a `Long` for the same reason. `FWORD` and `UFWORD` are
 * `int16` and `uint16` "that describes a quantity in font design units", so they
 * are read by [int16] and [uint16] and named at the call site.
 *
 * ## Window, not copy
 *
 * A reader covers a range of a larger array — the font program — rather than
 * owning bytes of its own. Tables are found by offset from the start of the
 * file, and subtables by offset from the start of their table, so [sliceAt] is
 * how a parser descends: it narrows the window to the child structure, and the
 * child's offsets are then read as the specification writes them, from zero.
 * Nothing is copied, and a font of several megabytes is parsed without a second
 * copy of any of it.
 *
 * ## Every read is bounds-checked
 *
 * A font program is input from outside this library, so a length or an offset
 * inside it is a claim, not a fact. Every read that would leave the window
 * raises [IllegalArgumentException] naming [what] is being read, at which
 * offset, and how large the window is. That is the whole error strategy of this
 * package: a font this library cannot read is refused, in a message that says
 * what was wrong with it, and never half-read into a document that would draw
 * the wrong glyphs.
 *
 * @property what what this window covers, for the error messages — "the table
 *   directory", "the 'head' table", "a 'cmap' format 4 subtable".
 */
internal class BigEndianReader private constructor(
    private val bytes: ByteArray,
    private val origin: Int,
    /** The size of the window, in bytes. */
    val size: Int,
    val what: String,
) {
    /** How far into the window the next sequential read starts. */
    var position: Int = 0
        private set

    /** Moves the cursor to [offset], measured from the start of the window. */
    fun seek(offset: Int) {
        require(offset in 0..size) { "$what: cannot seek to $offset, the window is $size bytes" }
        position = offset
    }

    /** Advances the cursor by [count] bytes, for a field that is read by nobody. */
    fun skip(count: Int) {
        seek(position + count)
    }

    /** `uint8` — "8-bit unsigned integer". */
    fun uint8(): Int = uint8At(advance(1))

    /** `int8` — "8-bit signed integer". */
    fun int8(): Int = uint8().toByte().toInt()

    /** `uint16` — "16-bit unsigned integer", in 0..65535. */
    fun uint16(): Int = uint16At(advance(2))

    /** `int16` — "16-bit signed integer", in −32768..32767. */
    fun int16(): Int = uint16().toShort().toInt()

    /** `uint32` — "32-bit unsigned integer", in 0..4294967295. */
    fun uint32(): Long = uint32At(advance(4))

    /**
     * `Tag` — "Array of four uint8s (length = 32 bits) used to identify a table".
     *
     * The specification is precise that this is a byte array and only
     * conventionally a string: "Each byte within the array must have a value in
     * the range 0x20 to 0x7E … As a result, a Tag value can be re-interpreted as
     * a four-character sequence". The range is checked, so the four-character
     * form this returns is always the faithful one — a tag with a byte outside
     * it is a malformed font rather than a name with a replacement character in
     * it.
     */
    fun tag(): String {
        val at = advance(4)
        val characters = CharArray(4)
        for (index in 0 until 4) {
            val byte = uint8At(at + index)
            require(byte in TAG_BYTE_MIN..TAG_BYTE_MAX) {
                "$what: a tag at offset $at has the byte ${hex(byte.toLong())} at position $index, " +
                    "outside the ${hex(TAG_BYTE_MIN.toLong())}..${hex(TAG_BYTE_MAX.toLong())} " +
                    "a tag's bytes are required to be in"
            }
            characters[index] = byte.toChar()
        }
        return characters.concatToString()
    }

    /**
     * The `uint16` at [offset] from the start of the window, leaving the cursor
     * where it was.
     *
     * This is what a `cmap` format 4 subtable needs: the specification maps a
     * character through an address computed from a field's own position rather
     * than by reading forwards (see [CmapFormat4Subtable]).
     */
    fun uint16At(offset: Int): Int {
        checkAvailable(offset, 2)
        val at = origin + offset
        return (byteAt(at) shl 8) or byteAt(at + 1)
    }

    /** The `uint32` at [offset] from the start of the window. */
    fun uint32At(offset: Int): Long {
        checkAvailable(offset, 4)
        return (uint16At(offset).toLong() shl 16) or uint16At(offset + 2).toLong()
    }

    /** The `uint8` at [offset] from the start of the window. */
    fun uint8At(offset: Int): Int {
        checkAvailable(offset, 1)
        return byteAt(origin + offset)
    }

    /**
     * A reader over [length] bytes starting [offset] into this window — the
     * child structure an offset field points at, and [what] it is.
     *
     * Both are checked against this window, so a table record claiming a length
     * past the end of the file is refused at the point the claim is made rather
     * than at the first field that falls off the end.
     */
    fun sliceAt(offset: Int, length: Int, what: String): BigEndianReader {
        require(offset >= 0) { "$what: starts at a negative offset $offset within ${this.what}" }
        require(length >= 0) { "$what: has a negative length $length within ${this.what}" }
        require(offset + length <= size) {
            "$what: needs bytes $offset..${offset + length} of ${this.what}, which is $size bytes"
        }
        return BigEndianReader(bytes, origin + offset, length, what)
    }

    /**
     * The bytes of the window, copied out.
     *
     * This is the one place a copy is deliberate. A subset rebuilds `glyf`,
     * `loca` and `hmtx` and carries the rest of the tables across unchanged
     * (see [TrueTypeSubset]); "unchanged" is these bytes, and they end up in a
     * different file at a different offset, so they cannot stay a window over
     * the font they came from.
     */
    fun toByteArray(): ByteArray = bytes.copyOfRange(origin, origin + size)

    private fun advance(width: Int): Int {
        checkAvailable(position, width)
        val at = position
        position += width
        return at
    }

    private fun checkAvailable(offset: Int, width: Int) {
        require(offset >= 0 && offset + width <= size) {
            "$what: a $width-byte field at offset $offset falls outside the $size bytes it covers"
        }
    }

    private fun byteAt(index: Int): Int = bytes[index].toInt() and 0xFF

    companion object {
        private const val TAG_BYTE_MIN: Int = 0x20
        private const val TAG_BYTE_MAX: Int = 0x7E

        /** A reader over the whole of [bytes]. */
        fun over(bytes: ByteArray, what: String): BigEndianReader =
            BigEndianReader(bytes, origin = 0, size = bytes.size, what = what)
    }
}

/** `0x5f0f3cf5` — the way the specification writes the values it fixes. */
internal fun hex(value: Long): String = "0x" + value.toString(16)
