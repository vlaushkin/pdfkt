/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.resolve

import io.pdfkt.core.block.BreakRun
import io.pdfkt.core.block.InlineRun
import io.pdfkt.core.block.LinkRun
import io.pdfkt.core.block.ParagraphBlock
import io.pdfkt.core.block.StyledRun
import io.pdfkt.core.block.TextRun
import io.pdfkt.core.font.FontCatalog
import io.pdfkt.core.model.Rgb
import io.pdfkt.core.model.pt
import io.pdfkt.core.style.Theme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// No clause applies: L4 is this library's own design.

/**
 * Where a run of text ends, and where it does not.
 *
 * The rule is renderability, which cuts both ways: a change of anything the
 * drawing depends on splits the text, and a nesting of markup that changes
 * nothing does not. The second half is the one worth testing — it is what stops
 * the line breaker from being handed a hundred one-character runs because the
 * caller wrote their markup in pieces.
 */
class ResolveInlineTest {
    private fun resolve(vararg runs: InlineRun): List<StyledInline> {
        val resolved = Resolver(Theme.Default, FontCatalog()).resolve(listOf(ParagraphBlock(runs.toList())))
        return (resolved.single() as StyledParagraph).content
    }

    /** Two text runs the caller wrote separately are drawn identically, so they are one run. */
    @Test
    fun `adjacent text of the same style is one run`() {
        val content = resolve(TextRun("Hello, "), TextRun("world"))
        assertEquals(listOf("Hello, world"), content.filterIsInstance<StyledTextRun>().map { it.text })
        assertEquals(1, content.size)
    }

    /** Nesting that changes nothing leaves no seam: markup is not a property of the page. */
    @Test
    fun `nesting that changes nothing does not split`() {
        val content = resolve(
            TextRun("a"),
            StyledRun(listOf(TextRun("b"), StyledRun(listOf(TextRun("c"))))),
            TextRun("d"),
        )
        assertEquals(listOf("abcd"), content.filterIsInstance<StyledTextRun>().map { it.text })
    }

    /** A change of face splits, because the two halves are drawn from two font programs. */
    @Test
    fun `a font change splits the run`() {
        val content = resolve(TextRun("plain "), StyledRun(listOf(TextRun("bold")), bold = true), TextRun(" plain"))
        assertEquals(listOf("plain ", "bold", " plain"), content.filterIsInstance<StyledTextRun>().map { it.text })
    }

    /** So does a change of size, of colour, of decoration, and of link target. */
    @Test
    fun `size colour decoration and link each split the run`() {
        val blue = Rgb(0.0f, 0.0f, 1.0f)
        assertEquals(2, resolve(TextRun("a"), StyledRun(listOf(TextRun("b")), fontSize = 30.pt)).size)
        assertEquals(2, resolve(TextRun("a"), StyledRun(listOf(TextRun("b")), color = blue)).size)
        assertEquals(2, resolve(TextRun("a"), StyledRun(listOf(TextRun("b")), strike = true)).size)
        assertEquals(2, resolve(TextRun("a"), LinkRun("https://example.org", listOf(TextRun("b")))).size)
    }

    /**
     * Two links to two targets do not merge even though they look the same: the
     * boxes become two annotations, and one run would give one box.
     */
    @Test
    fun `two links to different targets stay two runs`() {
        val content = resolve(
            LinkRun("https://example.org/one", listOf(TextRun("one"))),
            LinkRun("https://example.org/two", listOf(TextRun("two"))),
        )
        val runs = content.filterIsInstance<StyledTextRun>()
        assertEquals(listOf("one", "two"), runs.map { it.text })
        assertEquals(listOf("https://example.org/one", "https://example.org/two"), runs.map { it.link })
    }

    /** Style composes downwards: bold inside a link is bold *and* a link. */
    @Test
    fun `styles compose through nesting`() {
        val content = resolve(
            LinkRun("https://example.org", listOf(StyledRun(listOf(TextRun("go")), bold = true, strike = true))),
        )
        val run = content.filterIsInstance<StyledTextRun>().single()
        assertEquals("https://example.org", run.link)
        assertTrue(run.strike)
        assertTrue(run.underline, "the theme underlines links")
        assertEquals(Theme.Default.linkColor, run.color)
    }

    /** A break is its own thing in the flow — a newline is not something a font draws. */
    @Test
    fun `breaks survive as breaks and keep the runs apart`() {
        val content = resolve(TextRun("first"), BreakRun(hard = true), TextRun("second"))
        assertEquals(3, content.size)
        assertEquals(StyledBreak(hard = true), content[1])
        assertEquals(listOf("first", "second"), content.filterIsInstance<StyledTextRun>().map { it.text })
    }

    /** A soft break is an opportunity, not a command, and says so. */
    @Test
    fun `a soft break resolves to a soft break`() {
        assertEquals(listOf(StyledBreak(hard = false)), resolve(BreakRun(hard = false)))
    }

    /** Empty text is no run at all: there is nothing to draw and nothing to measure. */
    @Test
    fun `empty text produces no run`() {
        assertEquals(emptyList(), resolve(TextRun("")))
        assertEquals(listOf("ab"), resolve(TextRun("a"), TextRun(""), TextRun("b")).map { (it as StyledTextRun).text })
    }

    /** Nothing is measured here: a resolved run says what it is, never where it is or how wide. */
    @Test
    fun `resolving computes no geometry`() {
        val paragraph = Resolver(Theme.Default, FontCatalog())
            .resolve(listOf(ParagraphBlock(listOf(TextRun("Hello")), indentLeft = 12.pt)))
            .single() as StyledParagraph
        assertEquals(12.pt, paragraph.indentLeft, "an indent is a style, and it is carried through")
        assertEquals(Theme.Default.lineHeight, paragraph.lineHeight)
        // StyledTextRun has no x, no y and no width: those are the line breaker's
        // answers, and they cannot be here to be wrong.
        assertEquals(
            StyledTextRun(
                text = "Hello",
                font = Theme.Default.bodyFamily.regular,
                size = Theme.Default.bodyFontSize,
                color = Theme.Default.textColor,
            ),
            paragraph.content.single(),
        )
    }
}
