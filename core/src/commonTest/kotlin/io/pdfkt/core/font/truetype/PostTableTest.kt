/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.font.truetype

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// OpenType 1.9.1 §"post — PostScript Table"
// ISO 32000-2:2020 §9.8.1 "Font descriptors — General" (Table 120)

/**
 * The `post` header, which is where a font descriptor's `ItalicAngle` comes from.
 *
 * The field is a `Fixed`, "32-bit signed fixed-point number (16.16)", and the
 * angles that matter are negative — "The value shall be negative for fonts that
 * slope to the right, as almost all italic fonts do" (Table 120). A parser that
 * read the field as unsigned would turn every italic font upright by about 65 536
 * degrees, and a page would still render.
 */
class PostTableTest {
    @Test
    fun `an upright font declares no slope`() {
        val post = parse(postTableBytes(italicAngle = 0.0))

        assertEquals(0.0, post.italicAngle)
        assertFalse(post.isFixedPitch, "a proportional font declaring itself monospaced")
    }

    @Test
    fun `a font that slopes to the right declares a negative angle`() {
        val post = parse(postTableBytes(italicAngle = -12.0))

        assertEquals(-12.0, post.italicAngle)
    }

    @Test
    fun `the fractional half of the fixed point field is read`() {
        // 16.16: the sixteen low bits are the fraction, so an eighth of a degree
        // is a value no integer read of the field could produce.
        val post = parse(postTableBytes(italicAngle = -9.125))

        assertEquals(-9.125, post.italicAngle)
    }

    @Test
    fun `a monospaced font says so`() {
        assertTrue(parse(postTableBytes(isFixedPitch = true)).isFixedPitch)
    }

    @Test
    fun `a version the header is shared by reads the same`() {
        // Versions 1.0, 2.0, 2.5, 3.0 and 4.0 differ in what follows the header,
        // never in the header, so the version is read past rather than checked.
        val post = parse(postTableBytes(italicAngle = -30.0, version = 0x00020000L))

        assertEquals(-30.0, post.italicAngle)
    }

    @Test
    fun `a font that carries no post table is read as one that declares nothing`() {
        // Table 124 does not require 'post' of an embedded program, so a font can
        // arrive without one; a descriptor written from such a font declares no
        // slope rather than a guessed one.
        val font = TrueTypeFont.parse(minimalFontBytes())

        assertEquals(null, font.directory.readerOrNull("post"))
    }

    private fun parse(bytes: ByteArray): PostTable = PostTable.parse(BigEndianReader.over(bytes, "the 'post' table"))
}
