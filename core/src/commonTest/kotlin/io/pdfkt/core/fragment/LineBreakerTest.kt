/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.fragment

import io.pdfkt.core.font.FontCatalog
import io.pdfkt.core.font.FontRef
import io.pdfkt.core.font.SUPPLIED_FONT_METRICS
import io.pdfkt.core.font.SUPPLIED_FONT_OUTLINES
import io.pdfkt.core.font.Standard14Name
import io.pdfkt.core.font.truetype.CmapRecord
import io.pdfkt.core.font.truetype.Format12Group
import io.pdfkt.core.font.truetype.cmapFormat12Bytes
import io.pdfkt.core.font.truetype.cmapTableBytes
import io.pdfkt.core.font.truetype.glyfAndLocaBytes
import io.pdfkt.core.font.truetype.headTableBytes
import io.pdfkt.core.font.truetype.hheaTableBytes
import io.pdfkt.core.font.truetype.hmtxTableBytes
import io.pdfkt.core.font.truetype.holding
import io.pdfkt.core.font.truetype.maxpTableBytes
import io.pdfkt.core.font.truetype.trueTypeFontBytes
import io.pdfkt.core.model.Pt
import io.pdfkt.core.model.Rgb
import io.pdfkt.core.model.pt
import io.pdfkt.core.plan.Decoration
import io.pdfkt.core.resolve.StyledBreak
import io.pdfkt.core.resolve.StyledInline
import io.pdfkt.core.resolve.StyledTextRun
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// No clause applies: L4 is this library's own design.

/**
 * What the line breaker promises, asserted against the design and not against
 * anyone's implementation.
 *
 * The measurements come from the AFM tables of the standard fourteen (§9.6.2.2),
 * so a width in this file is a real width and a line that "fits" is one a reader
 * will also see fitting. Nothing here asserts a particular number of lines for a
 * particular sentence — that would be a test of Helvetica's metrics — but the
 * properties: every character comes back, no line reaches past the column, and
 * every break is at a place a break was allowed.
 */
class LineBreakerTest {
    private val fonts = FontCatalog()

    private val breaker = LineBreaker(fonts)

    private fun style(
        text: String,
        size: Pt = 12.pt,
        bold: Boolean = false,
        underline: Boolean = false,
        strike: Boolean = false,
        link: String? = null,
    ): StyledTextRun = StyledTextRun(
        text = text,
        font = FontRef.Standard14(if (bold) Standard14Name.HELVETICA_BOLD else Standard14Name.HELVETICA),
        size = size,
        color = Rgb.BLACK,
        underline = underline,
        strike = strike,
        link = link,
    )

    private fun lines(vararg content: StyledInline, width: Pt = 200.pt, firstLineIndent: Pt = Pt.ZERO): List<TextLine> =
        breaker.breakIntoLines(content.toList(), width, firstLineIndent = firstLineIndent)

    /**
     * The greedy rule, stated as what it forbids: no line ends while the next
     * word would still have fitted on it.
     *
     * Asserting "four words on the first line" would be asserting Helvetica's
     * widths; asserting that nothing was left out and that nothing more could
     * have been fitted in is asserting the algorithm.
     */
    @Test
    fun `a line takes every word that fits and breaks before the first that does not`() {
        val text = "The quick brown fox jumps over the lazy dog and keeps on running past the margin"
        val column = 120.pt
        val broken = lines(style(text), width = column)

        assertTrue(broken.size > 1, "the text is wider than the column but came back as one line")
        assertEquals(text, broken.joinToString(separator = "") { it.text })
        for (line in broken) {
            assertTrue(line.width <= column, "a line reaches past the column: ${line.width} in $column")
        }
        for (index in 0 until broken.size - 1) {
            val line = broken[index]
            val nextWord = broken[index + 1].text.substringBefore(' ')
            // The next word would start after the space this line hung, so that
            // is what it would have had to fit past.
            val width = widthOf(" $nextWord")
            assertTrue(
                (line.width + width).value > column.value,
                "\"$nextWord\" ($width) would still have fitted on line $index (${line.width} of $column)",
            )
        }
    }

    /**
     * The core guarantee, at the level a plan can be asserted at: a
     * token of 200 characters that has to be broken between characters gives 200
     * characters back. Not 180 — see `LineBreakRenderTest`, which asserts the
     * same number through a file and a text extractor.
     */
    @Test
    fun `a token longer than the column breaks per character and keeps every one`() {
        val token = LONG_TOKEN
        assertEquals(TOKEN_LENGTH, token.length)

        val broken = lines(style(token), width = 60.pt)

        assertTrue(broken.size > 1, "a 200-character token fitted a 60 point column, which cannot be")
        val returned = broken.joinToString(separator = "") { it.text }
        assertEquals(TOKEN_LENGTH, returned.length, "characters were lost: ${TOKEN_LENGTH - returned.length}")
        assertEquals(token, returned)
        for (line in broken) {
            assertTrue(line.runs.isNotEmpty(), "an empty line in the middle of a token")
            assertTrue(line.width <= 60.pt, "a line of the broken token reaches past the column: ${line.width}")
        }
    }

    /**
     * A column narrower than one character is still a column that makes
     * progress: one character per line, overflowing where it must.
     *
     * The alternative — a line that takes nothing because nothing fits — is an
     * empty page produced forever, and the reason the rule is "at least one
     * character" rather than "as much as fits".
     */
    @Test
    fun `a column narrower than a single character takes one character per line`() {
        val broken = lines(style("abc"), width = 1.pt)

        assertEquals(listOf("a", "b", "c"), broken.map { it.text })
    }

    /**
     * A character outside the basic multilingual plane is one character, never
     * half a surrogate pair.
     *
     * U+1D400 MATHEMATICAL BOLD CAPITAL A and its two neighbours are drawn from
     * a font written here for them, because no member of the standard fourteen
     * has a glyph outside the basic multilingual plane — and a line ending
     * between the two UTF-16 units of one character would carry half of it into
     * a text-showing operator (§9.4.3).
     */
    @Test
    fun `breaking between characters never splits a surrogate pair`() {
        val astral = FontRef.Supplied(astralFontBytes(), "astral")
        val text = "𝐀𝐁𝐂"
        val run = StyledTextRun(text, astral, 12.pt, Rgb.BLACK)

        val broken = LineBreaker(FontCatalog()).breakIntoLines(listOf(run), 1.pt)

        assertEquals(3, broken.size, "three characters came back as ${broken.size} lines")
        assertEquals(text, broken.joinToString(separator = "") { it.text })
        for (line in broken) {
            assertEquals(2, line.text.length, "a line carries half a surrogate pair: ${line.text.length} units")
        }
    }

    /** A run that reaches a break becomes two runs of the same style, one per line. */
    @Test
    fun `a placed run never spans lines`() {
        val broken = lines(style("aaaa bbbb cccc dddd"), width = 60.pt)

        assertTrue(broken.size > 1)
        for (line in broken) {
            for (run in line.runs) {
                assertTrue(run.text.isNotEmpty(), "an empty run reached the plan")
            }
        }
        val styles = broken.flatMap { it.runs }.map { it.font to it.size }.distinct()
        assertEquals(1, styles.size, "one style came back as $styles")
    }

    /** Pieces of one style that end up on one line are one run: an underline with gaps in it is not an underline. */
    @Test
    fun `words of the same style on one line are one run`() {
        val broken = lines(style("one two three"), width = 300.pt)

        assertEquals(1, broken.size)
        assertEquals(listOf("one two three"), broken.single().runs.map { it.text })
    }

    /** A style change is not a break opportunity: a word split by markup is still a word. */
    @Test
    fun `a line does not break inside a word that changes style`() {
        val broken = lines(style("aaaa "), style("bbbb", bold = true), style("cccc"), width = 60.pt)

        val lineWithB = broken.single { "bbbb" in it.text }
        assertTrue("bbbbcccc" in lineWithB.text, "the word was broken where only the styling changed: $broken")
    }

    /** A hard break ends the line whatever room is left; a soft one only says it may end there. */
    @Test
    fun `a hard break ends the line and a soft break does not`() {
        val hard = lines(style("one"), StyledBreak(hard = true), style("two"), width = 300.pt)
        assertEquals(listOf("one", "two"), hard.map { it.text })

        val soft = lines(style("one"), StyledBreak(hard = false), style("two"), width = 300.pt)
        assertEquals(listOf("onetwo"), soft.map { it.text })
    }

    /** A soft break is a break opportunity, so a narrow column takes it. */
    @Test
    fun `a soft break is taken when the line would otherwise overflow`() {
        val broken = lines(style("aaaaaaa"), StyledBreak(hard = false), style("bbbbbbb"), width = 60.pt)

        assertEquals(listOf("aaaaaaa", "bbbbbbb"), broken.map { it.text })
    }

    /**
     * A soft hyphen is a mark, not a character: invisible where the line runs
     * past it, a hyphen where the line breaks at it.
     */
    @Test
    fun `a soft hyphen shows a hyphen only where the line breaks`() {
        // The soft hyphen is written as its escape: it is invisible in the
        // source, and a test of a character nobody can see in the file is a test
        // that breaks when an editor tidies it away.
        val word = "extra\u00ADordinary"

        assertEquals(listOf("extraordinary"), lines(style(word), width = 300.pt).map { it.text })
        assertEquals(listOf("extra-", "ordinary"), lines(style(word), width = 46.pt).map { it.text })
    }

    /**
     * White space at a break stays in the document and out of the line's extent.
     *
     * Both halves matter: a space dropped at every line end is text the caller
     * wrote and the file does not carry, and a space counted into the width is a
     * right edge that wobbles by a space from line to line.
     */
    @Test
    fun `the space a line breaks after hangs past the column`() {
        val broken = lines(style("aaaa bbbb"), width = 40.pt)

        assertEquals(listOf("aaaa ", "bbbb"), broken.map { it.text })
        assertCloseTo(widthOf("aaaa"), broken.first().width, "the hanging space was counted into the line's width")
    }

    /** Decorations attach to the runs, at a geometry taken from the font's own size. */
    @Test
    fun `decorations attach to the runs that carry them`() {
        val broken = lines(style("plain "), style("marked", underline = true, strike = true), width = 300.pt)

        val runs = broken.single().runs
        assertEquals(2, runs.size)
        assertEquals(emptyList(), runs[0].decorations)

        val underline = runs[1].decorations.filterIsInstance<Decoration.Underline>().single()
        val strike = runs[1].decorations.filterIsInstance<Decoration.Strike>().single()
        assertTrue(underline.offset.value < 0.0f, "an underline above the baseline: ${underline.offset}")
        assertTrue(strike.offset.value > 0.0f, "a strike below the baseline: ${strike.offset}")
        assertTrue(underline.thickness.value > 0.0f)
        assertEquals(underline.thickness, strike.thickness)
        // The strike crosses the glyphs: below the top of the tallest of them.
        val ascent = fonts.metricsOf(runs[1].font).ascent(runs[1].size.value)
        assertTrue(strike.offset.value < ascent, "the strike is drawn above the glyphs it should cross")
    }

    /** A decoration is drawn to the run's width, so a broken run decorates each half separately. */
    @Test
    fun `a decorated run keeps its decoration on both sides of a break`() {
        val broken = lines(style("aaaa bbbb", underline = true), width = 40.pt)

        assertEquals(2, broken.size)
        for (line in broken) {
            val run = line.runs.single()
            val underline = run.decorations.filterIsInstance<Decoration.Underline>().single()
            assertTrue(underline.thickness.value > 0.0f)
        }
    }

    /** A link becomes a span along the line, joined across the runs that share a target. */
    @Test
    fun `link spans cover the runs that carry the target`() {
        val target = "https://www.iso.org/standard/75839.html"
        val broken = lines(
            style("see "),
            style("the ", link = target),
            style("standard", bold = true, link = target),
            style(" now"),
            width = 300.pt,
        )

        val line = broken.single()
        val span = line.links.single()
        assertEquals(target, span.target)
        val linked = line.runs.filter { it.text == "the " || it.text == "standard" }
        assertEquals(2, linked.size)
        assertEquals(linked.first().xOffset, span.xOffset)
        assertCloseTo(linked.last().xOffset + linked.last().width, span.xOffset + span.width)
    }

    /** A link broken across two lines is one span per line: both halves are clickable. */
    @Test
    fun `a link broken across lines becomes one span on each`() {
        val target = "https://example.org"
        val broken = lines(style("aaaa bbbb", link = target), width = 40.pt)

        assertEquals(2, broken.size)
        for (line in broken) assertEquals(target, line.links.single().target)
    }

    /** Each run starts where the one before it ended, so a line reads left to right. */
    @Test
    fun `x offsets follow the widths of the runs before them`() {
        val broken = lines(style("one "), style("two", bold = true), style(" three"), width = 300.pt)

        val runs = broken.single().runs
        assertEquals(Pt.ZERO, runs.first().xOffset)
        for (index in 1 until runs.size) {
            assertCloseTo(runs[index - 1].xOffset + runs[index - 1].width, runs[index].xOffset)
        }
    }

    /** The first line alone is set in, and the width it is broken against shrinks with it. */
    @Test
    fun `the first line indent moves the first line only`() {
        val indent = 24.pt
        val broken = lines(style("aaaa bbbb cccc"), width = 60.pt, firstLineIndent = indent)

        assertEquals(indent, broken.first().runs.first().xOffset)
        assertEquals(Pt.ZERO, broken[1].runs.first().xOffset)
        assertTrue(broken.first().width <= 60.pt, "the indented line reaches past the column: ${broken.first().width}")
    }

    /** The line's height comes from the tallest run on it, and the theme's multiple applies to that. */
    @Test
    fun `line metrics come from the tallest run on the line`() {
        val small = style("small ", size = 10.pt)
        val large = style("large", size = 30.pt)
        val single = breaker.breakIntoLines(listOf(small, large), 300.pt).single()

        val largeMetrics = fonts.metricsOf(large.font)
        assertEquals(largeMetrics.ascent(30.0f), single.ascent.value)
        assertEquals(largeMetrics.descent(30.0f), single.descent.value)
        assertEquals(largeMetrics.lineHeight(30.0f), single.height.value)
        assertTrue(single.descent.value < 0.0f, "Table 120 has the descent negative, got ${single.descent}")

        val spaced = breaker.breakIntoLines(listOf(small, large), 300.pt, lineHeight = 2.0f).single()
        assertEquals(single.height * 2.0f, spaced.height, "the line height multiplier did not reach the line")
    }

    /**
     * A line with nothing on it is still a line: a blank line of a code block
     * has to take the height of the text around it, or the blank vanishes.
     */
    @Test
    fun `an empty run gives one empty line that still has a height`() {
        val blank = breaker.breakIntoLines(listOf(style("")), 300.pt).single()

        assertEquals(emptyList(), blank.runs)
        assertTrue(blank.height.value > 0.0f, "a blank line of no height would collapse the gap it stands for")
        assertEquals(Pt.ZERO, blank.width)
    }

    /** Content that is not there at all is not a line. */
    @Test
    fun `content with nothing in it gives no lines`() {
        assertEquals(emptyList(), breaker.breakIntoLines(emptyList(), 300.pt))
    }

    /** A column of zero width is a caller's mistake, and one they hear about. */
    @Test
    fun `a column that is not positive is refused`() {
        val refused =
            runCatching { breaker.breakIntoLines(listOf(style("text")), Pt.ZERO) }.exceptionOrNull()
        assertNotNull(refused)
        assertTrue(refused is IllegalArgumentException, "a zero column was refused with $refused")
    }

    /**
     * Equality up to float-summation order: on Kotlin/JS a Float is carried
     * as a JS number, so a sum of widths lands one ulp away from the JVM's.
     * The tolerance is far below anything the writer's fixed-precision
     * number formatting could ever show in the file.
     */
    private fun assertCloseTo(expected: Pt, actual: Pt, message: String? = null) {
        assertTrue(
            abs(expected.value - actual.value) < 0.001f,
            (message?.plus(". ") ?: "") + "Expected <${'$'}expected>, actual <${'$'}actual>.",
        )
    }

    private fun widthOf(text: String): Pt =
        Pt(fonts.metricsOf(FontRef.Standard14(Standard14Name.HELVETICA)).widthOf(text, 12.0f))

    /**
     * A font program whose character map reaches beyond the basic multilingual
     * plane: the glyphs of `SuppliedFontBytes`, selected by a `cmap` format 12
     * subtable — the format the OpenType specification names for exactly this
     * ("Formats 4 or 12 are appropriate for most new fonts, depending on the
     * Unicode character repertoire supported") — over U+1D400 to U+1D402.
     */
    private fun astralFontBytes(): ByteArray {
        val glyfAndLoca = glyfAndLocaBytes(SUPPLIED_FONT_OUTLINES, longOffsets = true)
        return trueTypeFontBytes(
            listOf(
                "cmap" holding cmapTableBytes(
                    listOf(
                        CmapRecord(
                            platformId = 3,
                            encodingId = 10,
                            subtable = cmapFormat12Bytes(listOf(Format12Group(0x1D400L, 0x1D402L, 1))),
                        ),
                    ),
                ),
                "glyf" holding glyfAndLoca.glyf,
                "head" holding headTableBytes(unitsPerEm = 1000, indexToLocFormat = 1),
                "hhea" holding hheaTableBytes(numberOfHMetrics = 6),
                "hmtx" holding hmtxTableBytes(metrics = SUPPLIED_FONT_METRICS),
                "loca" holding glyfAndLoca.loca,
                "maxp" holding maxpTableBytes(numGlyphs = 6),
            ),
        )
    }

    private companion object {
        const val TOKEN_LENGTH: Int = 200

        /**
         * 200 characters with no break opportunity in them: long enough that a
         * breaker which drops what does not fit would lose characters, which is
         * why the exact count is asserted.
         */
        val LONG_TOKEN: String = buildString {
            repeat(TOKEN_LENGTH) { index -> append('a' + index % 26) }
        }
    }
}
