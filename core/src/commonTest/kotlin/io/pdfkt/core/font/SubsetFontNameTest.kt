/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.font

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

// ISO 32000-2:2020 §9.9.2 "Font subsets", §9.6.3 "TrueType fonts",
// §7.3.5 "Name objects"

/**
 * The name a subset is written under: `EOODIA+Poetica`, in the clause's own
 * example.
 *
 * Two requirements, and they pull in different directions. The tag "shall consist
 * of exactly six uppercase letters", and "different subsets of the same font in
 * the same PDF file shall have different tags" — so a tag is not a constant. But
 * a document written twice should be the same bytes, so it is not a counter or a
 * clock either. Deriving it from what makes one subset differ from another
 * satisfies both, and both are asserted below.
 */
class SubsetFontNameTest {
    @Test
    fun `a name is six uppercase letters a plus sign and the font name`() {
        val name = subsetFontName("Poetica", listOf(0, 4, 9))

        assertTrue(Regex("^[A-Z]{6}\\+Poetica$").matches(name), name)
    }

    /** "different subsets of the same font in the same PDF file shall have different tags" */
    @Test
    fun `two subsets of one font are tagged differently`() {
        val one = subsetFontName("Poetica", listOf(0, 4, 9))
        val another = subsetFontName("Poetica", listOf(0, 4, 9, 12))

        assertNotEquals(one, another)
    }

    /** A subset differing by a single glyph is a different subset. */
    @Test
    fun `a subset that differs by one glyph is tagged differently`() {
        assertNotEquals(
            subsetFontName("Poetica", listOf(0, 4, 9)),
            subsetFontName("Poetica", listOf(0, 4, 10)),
        )
    }

    /** Two fonts cut to the same glyph ids are still two fonts. */
    @Test
    fun `the same glyphs of two font programs are tagged differently`() {
        assertNotEquals(
            subsetFontName("Poetica", listOf(0, 4, 9)),
            subsetFontName("Ryumin-Light", listOf(0, 4, 9)),
        )
    }

    /**
     * The tag is a function of the subset, not of when it was written: the same
     * document produced twice is the same file, which is what lets a test assert
     * on bytes rather than on bytes modulo six letters.
     */
    @Test
    fun `the same subset is tagged the same every time`() {
        assertEquals(
            subsetFontName("Poetica", listOf(0, 4, 9)),
            subsetFontName("Poetica", listOf(0, 4, 9)),
        )
    }

    /**
     * The order of the glyph ids is not a difference between subsets — the same
     * glyphs listed differently are the same glyphs — but the tag is derived from
     * the list, so this records what the caller has to hand over: the subset's own
     * ordering, which [io.pdfkt.core.font.truetype.TrueTypeSubset] ascends.
     */
    @Test
    fun `the tag is derived from the list as given`() {
        assertNotEquals(
            subsetFontName("Poetica", listOf(0, 4, 9)),
            subsetFontName("Poetica", listOf(9, 4, 0)),
        )
    }

    /**
     * §9.6.3 derives the PostScript name from "the name by which the font is
     * known in the host operating system", and a caller's id is a label rather
     * than a name object. A `/BaseFont` full of `#20` escapes (§7.3.5) is a name
     * nobody can match against the font they supplied, so the characters a name
     * cannot carry become hyphens.
     */
    @Test
    fun `characters a PDF name cannot carry become hyphens`() {
        assertTrue(subsetFontName("DejaVu Sans", listOf(0)).endsWith("+DejaVu-Sans"))
        assertTrue(subsetFontName("body/text", listOf(0)).endsWith("+body-text"))
        assertTrue(subsetFontName("(жирный)", listOf(0)).endsWith("+--------"))
    }

    /** Letters, digits and the three punctuation marks a PostScript name keeps. */
    @Test
    fun `letters digits and name punctuation survive`() {
        assertTrue(subsetFontName("Noto_Sans-Bold.v2", listOf(0)).endsWith("+Noto_Sans-Bold.v2"))
    }
}
