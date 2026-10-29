/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.font.cff

import io.pdfkt.core.font.truetype.BigEndianWriter

// Adobe Technical Note #5176 "The Compact Font Format Specification" §18
// "CID-keyed Fonts", §13 "Charsets" (format 0), §19 "FDSelect" (format 3);
// ISO 32000-2:2020 §9.7.4.2 "Glyph selection in CIDFonts", §9.9 Table 125
// ("CIDFontType0C"), §9.9.2 "Font subsets".

/**
 * A CID-keyed CFF font holding the glyphs a document draws, ready to be
 * embedded as a `FontFile3` stream of subtype `CIDFontType0C`.
 *
 * Whatever the source — a name-keyed CFF, a CID-keyed CFF, a CFF2 — the subset
 * is written in the one form a Type 0 CIDFont's program takes: "the CIDFont
 * program … shall be … CID-keyed" with the CID of each glyph in its charset.
 *
 * The CIDs are the glyph indices of the source font. That is what the CIDs of
 * every other supplied font in this library are (see the `EmbeddedFont`
 * class), so the content streams, the `W` array and the `ToUnicode` CMap are
 * written the same way whatever outlines the font has; for a Type 0 CIDFont
 * "the CID shall be mapped to a GID through the charset", and the charset
 * written here maps each glyph back to the index it had.
 *
 * Every charstring is flattened ([CharstringFlattener]), so the subset has no
 * subroutines, and its Private DICTs are the source's with the `Subrs` entry
 * left out and every blend at its default.
 */
internal object CffSubset {
    /**
     * The subset of [font] holding [glyphIds] and `.notdef`, under the
     * PostScript [name].
     */
    fun of(font: CffFont, glyphIds: Iterable<Int>, name: String): ByteArray {
        val kept = (glyphIds.toSortedSet() + NOTDEF).toSortedSet().toList()
        kept.forEach { require(it in 0 until font.numGlyphs) { "glyph $it of a font with ${font.numGlyphs}" } }
        val flattener = CharstringFlattener(font)
        val charStrings = kept.map { flattener.flatten(it) }

        // The Font DICTs the kept glyphs use, renumbered in first-use order.
        val fds = kept.map { font.fdIndexOf(it) }.distinct()
        val fdOf = kept.map { fds.indexOf(font.fdIndexOf(it)) }
        val privates = fds.map { privateDictOf(font.fontDicts[it].private) }

        val strings = listOf(REGISTRY, ORDERING).map { it.encodeToByteArray() }
        val header = byteArrayOf(1, 0, 4, 4)
        val nameIndex = indexBytes(listOf(name.encodeToByteArray()))
        val stringIndex = indexBytes(strings)
        val globalSubrIndex = indexBytes(emptyList())
        val charset = charsetOf(kept)
        val fdSelect = fdSelectOf(fdOf)
        val charStringIndex = indexBytes(charStrings)

        // The Top DICT has a size that does not depend on the offsets in it,
        // since every offset is written in the five-byte form; lay it out
        // once with zeros to learn the size, then again with the offsets.
        fun topDict(charsetAt: Int, fdSelectAt: Int, charStringsAt: Int, fdArrayAt: Int): ByteArray =
            CffDictWriter().apply {
                // "The Top DICT … begins with the ROS operator."
                entry(
                    CffDict.ROS,
                    CffDict.longInt(STANDARD_STRINGS),
                    CffDict.longInt(STANDARD_STRINGS + 1),
                    CffDict.longInt(0),
                )
                font.fontMatrix?.let { entry(it) }
                entry(CffDict.CID_COUNT, CffDict.longInt(kept.last() + 1))
                entry(CffDict.CHARSET, CffDict.longInt(charsetAt))
                entry(CffDict.FD_SELECT, CffDict.longInt(fdSelectAt))
                entry(CffDict.CHAR_STRINGS, CffDict.longInt(charStringsAt))
                entry(CffDict.FD_ARRAY, CffDict.longInt(fdArrayAt))
            }.toByteArray()

        val topIndexSize = CffIndex.sizeOf(listOf(topDict(0, 0, 0, 0)))
        val charsetAt = header.size + nameIndex.size + topIndexSize + stringIndex.size + globalSubrIndex.size
        val fdSelectAt = charsetAt + charset.size
        val charStringsAt = fdSelectAt + fdSelect.size

        fun fontDicts(privateAt: List<Int>): List<ByteArray> = fds.indices.map { index ->
            CffDictWriter().apply {
                font.fontDicts[fds[index]].fontMatrix?.let { entry(it) }
                entry(CffDict.PRIVATE, CffDict.longInt(privates[index].size), CffDict.longInt(privateAt[index]))
            }.toByteArray()
        }
        val fdArrayAt = charStringsAt + charStringIndex.size
        val fdArraySize = CffIndex.sizeOf(fontDicts(fds.map { 0 }))
        var next = fdArrayAt + fdArraySize
        val privateAt = privates.map { private -> next.also { next += private.size } }

        val out = BigEndianWriter(next)
        out.bytes(header)
        out.bytes(nameIndex)
        CffIndex.write(out, listOf(topDict(charsetAt, fdSelectAt, charStringsAt, fdArrayAt)))
        out.bytes(stringIndex)
        out.bytes(globalSubrIndex)
        check(out.size == charsetAt)
        out.bytes(charset)
        out.bytes(fdSelect)
        out.bytes(charStringIndex)
        CffIndex.write(out, fontDicts(privateAt))
        privates.forEach { out.bytes(it) }
        check(out.size == next)
        return out.toByteArray()
    }

    /**
     * The Private DICT of a Font DICT without `Subrs` — the subset has none —
     * and without CFF2's `vsindex`, which [CffDict.read] has already applied.
     */
    private fun privateDictOf(source: CffDict): ByteArray = CffDictWriter().apply {
        source.entries.filter { it.operator != CffDict.SUBRS && it.operator != CffDict.VSINDEX }.forEach { entry(it) }
    }.toByteArray()

    /**
     * Charset format 0: "an array of … SIDs" — CIDs, in a CID-keyed font —
     * for every glyph but `.notdef`, "omitted since it is always GID 0".
     */
    private fun charsetOf(kept: List<Int>): ByteArray = BigEndianWriter().apply {
        u8(0)
        kept.drop(1).forEach { u16(it) }
    }.toByteArray()

    /** FDSelect format 3: ranges of glyphs with one Font DICT, and a sentinel. */
    private fun fdSelectOf(fdOf: List<Int>): ByteArray {
        val ranges = ArrayList<Pair<Int, Int>>()
        fdOf.forEachIndexed { glyph, fd -> if (ranges.lastOrNull()?.second != fd) ranges += glyph to fd }
        return BigEndianWriter().apply {
            u8(3)
            u16(ranges.size)
            ranges.forEach { (first, fd) ->
                u16(first)
                u8(fd)
            }
            u16(fdOf.size)
        }.toByteArray()
    }

    private fun indexBytes(objects: List<ByteArray>): ByteArray =
        BigEndianWriter().apply { CffIndex.write(this, objects) }.toByteArray()

    private const val NOTDEF = 0

    /** "The first custom string … has SID 391", after the standard strings. */
    private const val STANDARD_STRINGS = 391

    /** ISO 32000-2:2020 Table 114's `Adobe-Identity-0`, which the CIDFont declares too. */
    private const val REGISTRY = "Adobe"
    private const val ORDERING = "Identity"
}
