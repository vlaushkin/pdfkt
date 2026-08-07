/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.image

// Portable Network Graphics (PNG) Specification (Third Edition),
// W3C Recommendation 24 June 2025
//   §5.1 "PNG datastream", §5.2 "PNG signature", §5.3 "Chunk layout",
//   §5.4 "Chunk naming conventions", §5.5 "CRC algorithm",
//   §7.1 "Integers and byte order", §13.2 "Error checking"
//
// The structure every PNG has, whatever it holds: eight signature bytes and a
// sequence of length-type-data-CRC records. Nothing here knows what any chunk
// means — PngImage.kt is where IHDR becomes dimensions — so that the parser
// above walks a structure already checked rather than indexing into bytes and
// hoping.

/**
 * One chunk of a PNG datastream, §5.3: where it sits, what type it is and which
 * bytes are its data field.
 *
 * The data are held as a range rather than a copy because most chunks are
 * walked over and never read, and because the IDAT chunks are copied straight
 * into one buffer ("the compressed datastream is then the concatenation of the
 * contents of the data fields of all the IDAT chunks", §11.2.3).
 *
 * @property type the four bytes of the chunk type as one big-endian integer.
 *   "Encoders and decoders shall treat the chunk types as fixed binary values,
 *   not character strings" (§5.3), so that is what a comparison is against;
 *   [name] exists for the messages only.
 * @property at the offset of the chunk's length field, from the start of the
 *   datastream.
 * @property dataAt the offset of the first byte of the data field.
 * @property size the length field: "the number of bytes in the chunk's data
 *   field … Zero is a valid length".
 */
internal class PngChunk(val type: Int, val at: Int, val dataAt: Int, val size: Int) {
    /**
     * Whether the chunk is critical, §5.4: "Ancillary bit: first byte, 0
     * (uppercase) = critical, 1 (lowercase) = ancillary". Bit 5 of the byte is
     * what distinguishes the two cases of a letter.
     */
    val isCritical: Boolean get() = (type ushr 24) and PROPERTY_BIT == 0

    /** The type as its four ISO 646 letters, which is how the messages name it. */
    val name: String get() = chunkTypeName(type)

    /** The data field, copied out. For IDAT, [dataAt] and [size] are used instead. */
    fun data(datastream: ByteArray): ByteArray = datastream.copyOfRange(dataAt, dataAt + size)

    /** The offset just past the chunk's CRC field — where the next chunk begins. */
    val end: Int get() = dataAt + size + CRC_SIZE
}

/**
 * A cursor over a PNG datastream that hands out chunks, §5.1: "The PNG
 * datastream consists of a PNG signature followed by a sequence of chunks."
 *
 * Every chunk it returns has been checked as a chunk — its length is a length
 * the datastream has the bytes for, its type is four bytes in the ranges §5.3
 * restricts them to, and its CRC matches the bytes it covers. §13.1 asks for
 * exactly that order of business: "A CRC should be checked before processing
 * the chunk data."
 */
internal class PngChunkWalk(private val datastream: ByteArray) {
    /** The offset of the next chunk, or of the signature before it is read. */
    private var at: Int = 0

    /**
     * Reads and checks the eight signature bytes of §5.2.
     *
     * "Decoders should verify that all eight bytes of the PNG signature are
     * correct" (§13.1) — the check that separates "this is not a PNG" from
     * every later message, which would otherwise describe a chunk structure
     * found in bytes that never had one.
     */
    fun readSignature() {
        if (datastream.size < PNG_SIGNATURE.size) {
            throw PngFormatException(
                datastream.size,
                "the data are ${datastream.size} byte" + (if (datastream.size == 1) "" else "s") +
                    " long, and a PNG datastream begins with the ${PNG_SIGNATURE.size} bytes of the signature",
                SIGNATURE_CLAUSE,
            )
        }
        for (index in PNG_SIGNATURE.indices) {
            if (datastream[index] != PNG_SIGNATURE[index]) {
                throw PngFormatException(
                    index,
                    "the first ${PNG_SIGNATURE.size} bytes of a PNG datastream are " +
                        "${hexBytes(PNG_SIGNATURE)} and these are " +
                        hexBytes(datastream.copyOfRange(0, PNG_SIGNATURE.size)),
                    SIGNATURE_CLAUSE,
                )
            }
        }
        at = PNG_SIGNATURE.size
    }

    /** Whether any bytes remain after the chunks read so far. */
    fun hasNext(): Boolean = at < datastream.size

    /**
     * The next chunk, leaving the cursor on the byte after its CRC field.
     *
     * @param after what was read last, for the refusal — "after the IHDR chunk".
     */
    fun next(after: String): PngChunk {
        val lengthAt = at
        if (lengthAt + LENGTH_SIZE + TYPE_SIZE > datastream.size) {
            throw PngFormatException(
                datastream.size,
                "the datastream ends $after with ${datastream.size - lengthAt} byte" +
                    (if (datastream.size - lengthAt == 1) "" else "s") +
                    " left over; a chunk begins with a four-byte length and a four-byte type",
                CHUNK_CLAUSE,
            )
        }
        val length = datastream.int32At(lengthAt)
        // "Although encoders and decoders should treat the length as unsigned,
        // its value shall not exceed 2^31-1 bytes" — which is exactly the range
        // a signed 32-bit integer holds, so a negative value here is the
        // datastream declaring more than the clause allows.
        if (length < 0) {
            throw PngFormatException(
                lengthAt,
                "the chunk declares a data field of ${datastream.uint32At(lengthAt)} bytes; a chunk's length " +
                    "\"shall not exceed 2^31-1 bytes\"",
                CHUNK_CLAUSE,
            )
        }

        val typeAt = lengthAt + LENGTH_SIZE
        val type = datastream.int32At(typeAt)
        for (index in 0 until TYPE_SIZE) {
            val byte = datastream.uint8At(typeAt + index)
            val isLetter = byte in UPPERCASE_RANGE || byte in LOWERCASE_RANGE
            if (!isLetter) {
                throw PngFormatException(
                    typeAt + index,
                    "byte $index of the chunk type $after is 0x${hexByte(byte)}; \"each byte of a chunk type is " +
                        "restricted to the hexadecimal values 41 to 5A and 61 to 7A\", the ISO 646 letters",
                    CHUNK_CLAUSE,
                )
            }
        }

        val dataAt = typeAt + TYPE_SIZE
        val crcAt = dataAt + length
        if (crcAt + CRC_SIZE > datastream.size) {
            throw PngFormatException(
                datastream.size,
                "the ${chunkTypeName(type)} chunk at byte $lengthAt declares $length bytes of data followed by a " +
                    "four-byte CRC, and only ${datastream.size - dataAt} bytes of the datastream remain",
                CHUNK_CLAUSE,
            )
        }

        // The CRC covers "the preceding bytes in the chunk, including the chunk
        // type field and chunk data fields, but not including the length field".
        val computed = pngCrc(datastream, typeAt, crcAt)
        val stored = datastream.int32At(crcAt)
        if (computed != stored) {
            throw PngFormatException(
                crcAt,
                "the ${chunkTypeName(type)} chunk at byte $lengthAt carries the CRC " +
                    "0x${hexInt(stored)} and its type and data compute to 0x${hexInt(computed)}; " +
                    "\"a CRC mismatch … indicates a corrupted datastream\" (§13.1)",
                CRC_CLAUSE,
            )
        }

        at = crcAt + CRC_SIZE
        return PngChunk(type = type, at = lengthAt, dataAt = dataAt, size = length)
    }
}

/**
 * The CRC of `datastream[from until until]`, §5.5.
 *
 * The clause states the algorithm rather than showing one: the polynomial
 *
 * > x32 + x26 + x23 + x22 + x16 + x12 + x11 + x10 + x8 + x7 + x5 + x4 + x2 + x + 1
 *
 * then "the 32-bit CRC is initialized to all 1's, and then the data from each
 * byte is processed from the least significant bit (1) to the most significant
 * bit (128). After all the data bytes are processed, the CRC is inverted (its
 * ones complement is taken)."
 *
 * Feeding the register a bit at a time is that sentence transcribed, and
 * [CRC_TABLE] is the same thing done eight bits at a time — "practical
 * calculation of the CRC often employs a precalculated table to accelerate the
 * computation", and the table here is computed from the polynomial when the
 * class loads rather than written down.
 */
internal fun pngCrc(datastream: ByteArray, from: Int, until: Int): Int {
    var register = ALL_ONES
    for (index in from until until) {
        register = CRC_TABLE[(register xor datastream[index].toInt()) and BYTE_MASK] xor (register ushr BITS_PER_BYTE)
    }
    return register.inv()
}

/** The four bytes of a chunk type as their ISO 646 letters — for messages only. */
internal fun chunkTypeName(type: Int): String =
    CharArray(TYPE_SIZE) { ((type ushr (BITS_PER_BYTE * (TYPE_SIZE - 1 - it))) and BYTE_MASK).toChar() }
        .concatToString()

/**
 * "The first eight bytes of a PNG datastream always contain the following
 * hexadecimal values: 89 50 4E 47 0D 0A 1A 0A" (§5.2).
 */
internal val PNG_SIGNATURE: ByteArray = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

/**
 * The exponents of the §5.5 polynomial below x32, whose coefficient is implicit
 * in the shift.
 */
private val CRC_EXPONENTS: IntArray = intArrayOf(26, 23, 22, 16, 12, 11, 10, 8, 7, 5, 4, 2, 1, 0)

/**
 * The §5.5 polynomial as the register sees it: "the least significant bit of
 * the 32-bit CRC is defined to be the coefficient of the x31 term", so the
 * coefficient of x^k is bit 31 - k and the register shifts towards its least
 * significant bit.
 */
private val CRC_POLYNOMIAL: Int = CRC_EXPONENTS.fold(0) { bits, exponent -> bits or (1 shl (31 - exponent)) }

/** What the register holds after one byte of value `n` has been fed to an empty one. */
private val CRC_TABLE: IntArray = IntArray(1 shl BITS_PER_BYTE) { n ->
    var register = n
    repeat(BITS_PER_BYTE) {
        register = if (register and 1 != 0) CRC_POLYNOMIAL xor (register ushr 1) else register ushr 1
    }
    register
}

/** "The 32-bit CRC is initialized to all 1's" (§5.5). */
private const val ALL_ONES: Int = -1

/** A PNG four-byte unsigned integer (§7.1): the length, the type and the CRC fields. */
private const val LENGTH_SIZE: Int = 4
private const val TYPE_SIZE: Int = 4
private const val CRC_SIZE: Int = 4

/** Bit 5 of a chunk type byte, "the property bits" of §5.4 — set in a lowercase letter. */
private const val PROPERTY_BIT: Int = 32

/** `A`-`Z` and `a`-`z`, "the hexadecimal values 41 to 5A and 61 to 7A" (§5.3). */
private val UPPERCASE_RANGE: IntRange = 0x41..0x5A
private val LOWERCASE_RANGE: IntRange = 0x61..0x7A

private const val BITS_PER_BYTE: Int = 8
private const val BYTE_MASK: Int = 0xFF

private const val SIGNATURE_CLAUSE: String = "PNG Specification (Third Edition) §5.2 \"PNG signature\""
private const val CHUNK_CLAUSE: String = "PNG Specification (Third Edition) §5.3 \"Chunk layout\""
private const val CRC_CLAUSE: String = "PNG Specification (Third Edition) §5.5 \"CRC algorithm\""

/** The unsigned byte at [offset]. Bounds are the caller's, having checked the length. */
private fun ByteArray.uint8At(offset: Int): Int = this[offset].toInt() and BYTE_MASK

/**
 * The four-byte value at [offset] as a signed integer. "All integers that
 * require more than one byte shall be in network byte order … the most
 * significant byte comes first" (§7.1).
 */
private fun ByteArray.int32At(offset: Int): Int = (uint8At(offset) shl 24) or (uint8At(offset + 1) shl 16) or
    (uint8At(offset + 2) shl 8) or uint8At(offset + 3)

/** The same four bytes as the unsigned value they denote — for a message about a length too large. */
private fun ByteArray.uint32At(offset: Int): Long = int32At(offset).toLong() and 0xFFFFFFFFL

private fun hexByte(value: Int): String {
    val digits = "0123456789ABCDEF"
    return "${digits[(value shr 4) and 0x0F]}${digits[value and 0x0F]}"
}

private fun hexInt(value: Int): String =
    (3 downTo 0).joinToString("") { hexByte((value ushr (BITS_PER_BYTE * it)) and BYTE_MASK) }

private fun hexBytes(bytes: ByteArray): String = bytes.joinToString(" ") { hexByte(it.toInt() and BYTE_MASK) }
