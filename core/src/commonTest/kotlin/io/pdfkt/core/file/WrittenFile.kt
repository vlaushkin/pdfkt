/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.file

// ISO 32000-2:2020 §7.5.4 "Cross-reference table", §7.5.5 "File trailer"

/**
 * A file this library wrote, taken apart the way §7.5.5 says a file is read:
 *
 * > The trailer of a PDF file enables a PDF processor to quickly find the
 * > cross-reference table and certain special objects. PDF processors should
 * > read a PDF file from its end.
 *
 * Nothing here searches for the parts of the file it wants — that is the point.
 * `startxref` is read from the end, the cross-reference section is read at the
 * offset `startxref` gives, and the entries are read at the offsets the section
 * gives. A structure that only survives being scanned for would not survive this.
 *
 * It is a test fixture and holds no PDF knowledge beyond the three clauses it
 * cites; the library does not read files.
 */
internal class WrittenFile(val bytes: ByteArray) {
    /** The byte offset written on the line after the last `startxref` keyword. */
    val startCrossReference: Int = bytes.lineAt(bytes.afterLast(STARTXREF)).toInt()

    /** The line found *at* that offset, which the clause says spells `xref`. */
    val crossReferenceKeyword: String = bytes.lineAt(startCrossReference)

    private val subsectionAt: Int = startCrossReference + crossReferenceKeyword.length + 1

    /** The subsection header: "two integers separated by a SPACE (20h)". */
    val subsectionHeader: String = bytes.lineAt(subsectionAt)

    private val entriesAt: Int = subsectionAt + subsectionHeader.length + 1

    private val trailerAt: Int = bytes.afterLast(TRAILER) - TRAILER.length

    /**
     * Every byte between the subsection header and the `trailer` keyword — the
     * entries and nothing else, so that a claim about their size is a claim
     * about the file and not about how this fixture chose to cut them up.
     */
    val entryBytes: ByteArray = bytes.copyOfRange(entriesAt, trailerAt)

    /** The trailer dictionary, written on the line after the `trailer` keyword. */
    val trailerDictionary: String = bytes.lineAt(trailerAt + TRAILER.length)

    /** Everything after the line that carries the `startxref` offset. */
    val afterStartCrossReference: String =
        bytes
            .copyOfRange(
                bytes.afterLast(STARTXREF) + bytes.lineAt(bytes.afterLast(STARTXREF)).length + 1,
                bytes.size,
            ).decodeToString()

    /** The [index]-th 20-byte entry, decoded. */
    fun entry(index: Int): String = entryBytes.textAt(index * ENTRY_BYTES, ENTRY_BYTES)

    /** How many entries the region holds, if every one of them is 20 bytes long. */
    val entryCount: Int get() = entryBytes.size / ENTRY_BYTES

    private companion object {
        const val STARTXREF = "startxref\n"
        const val TRAILER = "trailer\n"
    }
}

/** "Each entry shall be exactly 20 bytes long, including the end-of-line marker." */
internal const val ENTRY_BYTES: Int = 20

/**
 * The format of an in-use entry, `nnnnnnnnnn ggggg n eol`, and of a free one,
 * with `f` for `n` — as the clause writes them, with the end-of-line sequence
 * being one of "SP CR, SP LF, or CR LF".
 */
internal val CROSS_REFERENCE_ENTRY: Regex = Regex("^[0-9]{10} [0-9]{5} [nf]( \r| \n|\r\n)$")

/** The text from [from] up to, but not including, the next LINE FEED. */
internal fun ByteArray.lineAt(from: Int): String {
    var end = from
    while (end < size && this[end] != LINE_FEED) end++
    return copyOfRange(from, end).decodeToString()
}

/** The text of [length] bytes at [from]. */
internal fun ByteArray.textAt(from: Int, length: Int): String = copyOfRange(from, from + length).decodeToString()

/** The index just past the last occurrence of [text]. */
internal fun ByteArray.afterLast(text: String): Int {
    val needle = text.encodeToByteArray()
    var found = -1
    for (start in 0..size - needle.size) {
        var index = 0
        while (index < needle.size && this[start + index] == needle[index]) index++
        if (index == needle.size) found = start
    }
    if (found < 0) error("the file holds no \"$text\"")
    return found + needle.size
}

private const val LINE_FEED: Byte = 0x0A
