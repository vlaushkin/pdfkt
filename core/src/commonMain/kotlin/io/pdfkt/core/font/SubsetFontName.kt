/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.font

// ISO 32000-2:2020 §9.9.2 "Font subsets", §9.6.3 "TrueType fonts",
// §7.3.5 "Name objects"

/**
 * The `/BaseFont` and `/FontName` of a subset — `EOODIA+Poetica`, in the shape
 * §9.9.2 gives and the shape of its own example:
 *
 * > For a font subset, the PostScript name of the font, that is, the value of
 * > the font's `BaseFont` entry and the font descriptor's `FontName` entry,
 * > shall begin with a tag followed by a plus sign (+) followed by the
 * > PostScript name of the font from which the subset was created. The tag shall
 * > consist of exactly six uppercase letters; the choice of letters is
 * > arbitrary, but different subsets of the same font in the same PDF file shall
 * > have different tags.
 *
 * @param id the name the caller gave the font program ([FontRef.Supplied.id]).
 * @param glyphIds the glyphs the subset keeps, which is what makes one subset of
 *   a font a different subset from another.
 */
internal fun subsetFontName(id: String, glyphIds: List<Int>): String =
    "${subsetTag(id, glyphIds)}+${postScriptNameOf(id)}"

/**
 * Six uppercase letters that differ whenever the subset differs.
 *
 * The requirement is not uniqueness in the abstract but within one file:
 * "different subsets of the same font in the same PDF file shall have different
 * tags". Two things distinguish subsets of one document — the font program, named
 * by its [id] (a [FontCatalog] refuses two programs under one id), and the set of
 * glyphs kept — so the tag is a hash of exactly those two.
 *
 * Deriving it rather than counting subsets off a register keeps the tag a
 * function of the subset: the same document written twice is the same bytes,
 * which is what lets a test assert on a file rather than on a file modulo six
 * letters. A counter would also be per-writer state, and this library keeps its
 * font state in a per-document catalog and nowhere else.
 *
 * The hash is FNV-1a over the id and the glyph ids — a published,
 * non-cryptographic hash chosen because it mixes short inputs that differ in one
 * byte, which is the case here: two subsets of one font commonly differ by a
 * single glyph.
 */
private fun subsetTag(id: String, glyphIds: List<Int>): String {
    var hash = FNV_OFFSET_BASIS
    for (byte in id.encodeToByteArray()) hash = (hash xor (byte.toLong() and BYTE_MASK)) * FNV_PRIME
    for (glyphId in glyphIds) {
        for (shift in GLYPH_ID_SHIFTS) hash = (hash xor ((glyphId.toLong() shr shift) and BYTE_MASK)) * FNV_PRIME
    }

    // The sign bit is dropped rather than folded in: it is one bit of a hash
    // whose other 63 are already in play, and a negative remainder would produce
    // a character below 'A'.
    var remaining = hash and Long.MAX_VALUE
    val tag = StringBuilder(TAG_LENGTH)
    repeat(TAG_LENGTH) {
        tag.append('A' + (remaining % LETTERS).toInt())
        remaining /= LETTERS
    }
    return tag.toString()
}

/**
 * [id] as a PostScript name.
 *
 * §9.6.3 gives two sources for the `/BaseFont` of a font program, and this is
 * the second: "In the absence of a PostScript language name in the 'name' table,
 * a PostScript language name should be derived from the name by which the font
 * is known in the host operating system." The id is precisely that — the name
 * the caller knows the program by — and this library does not read the `name`
 * table, because nothing about a font addressed by glyph index through
 * `Identity-H` depends on what the font is called (§9.7.4.2: the encoding is the
 * CMap, and the glyphs are the embedded program's).
 *
 * A caller's id is a label, not a name object, so the characters a name object
 * cannot carry unescaped are replaced rather than escaped: §7.3.5 makes
 * delimiters and whitespace writable as `#` plus two hex digits, and a
 * `/BaseFont` full of `#20` is a name no human can match against the font they
 * supplied. Letters, digits, `-`, `.` and `_` survive; everything else becomes a
 * hyphen.
 */
private fun postScriptNameOf(id: String): String {
    val name = StringBuilder(id.length)
    for (character in id) {
        name.append(if (character.isAsciiLetterOrDigit() || character in NAME_PUNCTUATION) character else '-')
    }
    return name.toString()
}

/**
 * Deliberately not [Char.isLetterOrDigit], which answers for the whole of
 * Unicode: a PostScript name is written in a PDF name object, whose characters
 * are bytes (§7.3.5), and `й` is not one byte.
 */
private fun Char.isAsciiLetterOrDigit(): Boolean = this in 'A'..'Z' || this in 'a'..'z' || this in '0'..'9'

/** "The tag shall consist of exactly six uppercase letters". */
private const val TAG_LENGTH: Int = 6

/** `A`–`Z`. */
private const val LETTERS: Long = 26

/** Characters a PostScript name keeps beside letters and digits. */
private const val NAME_PUNCTUATION: String = "-._"

/** FNV-1a, 64-bit: the offset basis and the prime the algorithm is defined with. */
private const val FNV_OFFSET_BASIS: Long = -3750763034362895579L // 0xCBF29CE484222325
private const val FNV_PRIME: Long = 1099511628211L // 0x100000001B3

private const val BYTE_MASK: Long = 0xFF

/** A glyph id is four bytes, hashed one byte at a time like the id's. */
private val GLYPH_ID_SHIFTS: IntArray = intArrayOf(0, 8, 16, 24)
