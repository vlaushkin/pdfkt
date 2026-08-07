/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.resolve

import io.pdfkt.core.block.BlockquoteBlock
import io.pdfkt.core.block.CodeBlock
import io.pdfkt.core.block.HeadingBlock
import io.pdfkt.core.block.HorizontalRuleBlock
import io.pdfkt.core.block.LinkRun
import io.pdfkt.core.block.ListBlock
import io.pdfkt.core.block.ListItem
import io.pdfkt.core.block.ListMarkerStyle
import io.pdfkt.core.block.PageBreakBlock
import io.pdfkt.core.block.ParagraphBlock
import io.pdfkt.core.block.StyledRun
import io.pdfkt.core.block.TableBlock
import io.pdfkt.core.block.TableCell
import io.pdfkt.core.block.TableColumns
import io.pdfkt.core.block.TextAlign
import io.pdfkt.core.block.TextRun
import io.pdfkt.core.font.FontCatalog
import io.pdfkt.core.font.FontRef
import io.pdfkt.core.font.Standard14Name
import io.pdfkt.core.model.Rgb
import io.pdfkt.core.model.pt
import io.pdfkt.core.style.FontFamily
import io.pdfkt.core.style.Theme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// No clause applies: L4 is this library's own design.

/**
 * What the resolve stage does with a theme.
 *
 * The assertions are all of the same shape: a block that says nothing about how
 * it looks comes out saying everything about how it looks, and a block that says
 * something keeps what it said.
 */
class ResolveThemeTest {
    private val theme = Theme.Default

    private fun resolver(theme: Theme = this.theme): Resolver = Resolver(theme, FontCatalog())

    private fun paragraph(text: String): ParagraphBlock = ParagraphBlock(listOf(TextRun(text)))

    private fun runsOf(block: StyledBlock): List<StyledTextRun> =
        (block as StyledParagraph).content.filterIsInstance<StyledTextRun>()

    /** Plain text comes out in the theme's body font, size and colour. */
    @Test
    fun `body text takes the theme font size and colour`() {
        val resolved = resolver().resolve(listOf(paragraph("Hello")))
        val run = runsOf(resolved.single()).single()
        assertEquals("Hello", run.text)
        assertEquals(FontRef.Standard14(Standard14Name.HELVETICA), run.font)
        assertEquals(theme.bodyFontSize, run.size)
        assertEquals(theme.textColor, run.color)
        assertEquals(theme.paragraphSpacingAfter, resolved.single().spacingAfter)
    }

    /**
     * Bold is a different font program, not a modified one (§9.6.2.2 lists
     * `Helvetica-Bold` as its own font), so the run comes out in the family's
     * bold face.
     */
    @Test
    fun `bold and italic choose a face of the family`() {
        val block = ParagraphBlock(
            listOf(
                StyledRun(listOf(TextRun("bold")), bold = true),
                StyledRun(listOf(TextRun("italic")), italic = true),
                StyledRun(listOf(StyledRun(listOf(TextRun("both")), italic = true)), bold = true),
            ),
        )
        val runs = runsOf(resolver().resolve(listOf(block)).single())
        assertEquals(
            listOf(
                Standard14Name.HELVETICA_BOLD,
                Standard14Name.HELVETICA_OBLIQUE,
                Standard14Name.HELVETICA_BOLD_OBLIQUE,
            ),
            runs.map { (it.font as FontRef.Standard14).name },
        )
        assertEquals(listOf("bold", "italic", "both"), runs.map { it.text })
    }

    /** A family with one face answers every combination with that face, and nothing is synthesised. */
    @Test
    fun `a family of one face is used for every combination`() {
        val single = FontFamily.of(FontRef.Standard14(Standard14Name.TIMES_ROMAN))
        val block = ParagraphBlock(listOf(StyledRun(listOf(TextRun("bold")), bold = true, italic = true)))
        val run = runsOf(resolver(theme.copy(bodyFamily = single)).resolve(listOf(block)).single()).single()
        assertEquals(FontRef.Standard14(Standard14Name.TIMES_ROMAN), run.font)
    }

    /** A heading resolves to a paragraph in the level's font, size and spacing. */
    @Test
    fun `a heading takes its level's font size and spacing`() {
        val resolved = resolver().resolve(listOf(HeadingBlock(2, listOf(TextRun("Chapter")))))
        val heading = resolved.single() as StyledParagraph
        val run = heading.content.filterIsInstance<StyledTextRun>().single()
        assertEquals(theme.headingSizes.getValue(2), run.size)
        assertEquals(FontRef.Standard14(Standard14Name.HELVETICA_BOLD), run.font, "headingBold picks the bold face")
        assertEquals(theme.headingSpacingBefore.getValue(2), heading.spacingBefore)
        assertEquals(theme.headingSpacingAfter.getValue(2), heading.spacingAfter)
        assertTrue(heading.keepWithNext, "a heading keeps with what follows")
    }

    /** A block that states its own spacing keeps it, and only it. */
    @Test
    fun `a block's own spacing overrides the theme`() {
        val block = ParagraphBlock(listOf(TextRun("x")), spacingBefore = 20.pt, spacingAfter = 30.pt)
        val resolved = resolver().resolve(listOf(block)).single()
        assertEquals(20.pt, resolved.spacingBefore)
        assertEquals(30.pt, resolved.spacingAfter)
    }

    /** Inline code takes the theme's monospaced family and code size. */
    @Test
    fun `inline code takes the theme's code font and size`() {
        val block = ParagraphBlock(listOf(StyledRun(listOf(TextRun("f(x)")), code = true)))
        val run = runsOf(resolver().resolve(listOf(block)).single()).single()
        assertEquals(FontRef.Standard14(Standard14Name.COURIER), run.font)
        assertEquals(theme.codeFontSize, run.size)
    }

    /** An explicit size beats the size the code flag would have imposed: the caller wrote it closer to the text. */
    @Test
    fun `an explicit size overrides the code size`() {
        val block = ParagraphBlock(listOf(StyledRun(listOf(TextRun("f(x)")), code = true, fontSize = 30.pt)))
        val run = runsOf(resolver().resolve(listOf(block)).single()).single()
        assertEquals(30.pt, run.size)
        assertEquals(FontRef.Standard14(Standard14Name.COURIER), run.font)
    }

    /** A code block is cut into lines where the caller broke them, blank lines included. */
    @Test
    fun `a code block resolves to one run per line`() {
        val code = CodeBlock("fun main() {\n\n    println(1)\n}")
        val resolved = resolver().resolve(listOf(code)).single() as StyledCode
        assertEquals(listOf("fun main() {", "", "    println(1)", "}"), resolved.lines.map { it.text })
        assertTrue(resolved.lines.all { it.font == FontRef.Standard14(Standard14Name.COURIER) })
        assertTrue(resolved.lines.all { it.size == theme.codeFontSize })
        assertEquals(theme.codeBackground, resolved.background)
    }

    /** The three line endings are one line ending: a document is not three files. */
    @Test
    fun `code line endings are normalised`() {
        val code = CodeBlock("a\r\nb\rc\nd")
        val resolved = resolver().resolve(listOf(code)).single() as StyledCode
        assertEquals(listOf("a", "b", "c", "d"), resolved.lines.map { it.text })
    }

    /** A link takes the theme's colour and underline, and carries its target to the plan. */
    @Test
    fun `a link takes the theme's colour and underline`() {
        val block = ParagraphBlock(listOf(LinkRun("https://example.org", listOf(TextRun("here")))))
        val run = runsOf(resolver().resolve(listOf(block)).single()).single()
        assertEquals(theme.linkColor, run.color)
        assertTrue(run.underline)
        assertEquals("https://example.org", run.link)
    }

    /** A colour stated on a run beats the theme's, and only for that run. */
    @Test
    fun `a run's colour overrides the theme's`() {
        val red = Rgb(1.0f, 0.0f, 0.0f)
        val block = ParagraphBlock(
            listOf(StyledRun(listOf(TextRun("warning")), color = red), TextRun(" and the rest")),
        )
        val runs = runsOf(resolver().resolve(listOf(block)).single())
        assertEquals(listOf(red, theme.textColor), runs.map { it.color })
    }

    /** A rule with nothing said about it is the theme's rule. */
    @Test
    fun `a horizontal rule takes the theme's thickness colour and spacing`() {
        val resolved = resolver().resolve(listOf(HorizontalRuleBlock())).single() as StyledRule
        assertEquals(theme.ruleThickness, resolved.thickness)
        assertEquals(theme.ruleColor, resolved.color)
        assertEquals(theme.ruleSpacing, resolved.spacingBefore)
        assertEquals(theme.ruleSpacing, resolved.spacingAfter)
    }

    /** A quotation takes the theme's bar colour and resolves its children by the same rules. */
    @Test
    fun `a blockquote resolves its children`() {
        val quote = BlockquoteBlock(listOf(paragraph("quoted"), HeadingBlock(3, listOf(TextRun("titled")))))
        val resolved = resolver().resolve(listOf(quote)).single() as StyledQuote
        assertEquals(theme.quoteBarColor, resolved.barColor)
        assertEquals(2, resolved.children.size)
        assertEquals(theme.headingSizes.getValue(3), runsOf(resolved.children[1]).single().size)
    }

    /** A table's header cells are set bold when the theme says so; the body cells are not. */
    @Test
    fun `table header cells are set bold by the theme`() {
        val table = TableBlock(
            columns = TableColumns.Equal(1),
            rows = listOf(listOf(TableCell(listOf(paragraph("body"))))),
            header = listOf(TableCell(listOf(paragraph("head")))),
        )
        val resolved = resolver().resolve(listOf(table)).single() as StyledTable
        val header = runsOf(resolved.header!!.single().content.single()).single()
        val body = runsOf(resolved.rows.single().single().content.single()).single()
        assertEquals(FontRef.Standard14(Standard14Name.HELVETICA_BOLD), header.font)
        assertEquals(FontRef.Standard14(Standard14Name.HELVETICA), body.font)
    }

    /** A cell holds blocks, so a nested table resolves through the same path and needs none of its own. */
    @Test
    fun `a table cell resolves nested blocks`() {
        val inner = TableBlock(
            columns = TableColumns.Equal(1),
            rows = listOf(listOf(TableCell(listOf(paragraph("deep"))))),
        )
        val outer = TableBlock(
            columns = TableColumns.Fixed(listOf(100.pt)),
            rows = listOf(listOf(TableCell(listOf(inner, ListBlock(listOf(ListItem(listOf(paragraph("i"))))))))),
        )
        val resolved = resolver().resolve(listOf(outer)).single() as StyledTable
        val cell = resolved.rows.single().single()
        assertTrue(cell.content[0] is StyledTable)
        assertTrue(cell.content[1] is StyledList)
    }

    /**
     * A cell's alignment is passed on to the paragraphs directly in it, since
     * alignment is a style and this is the stage that applies styles. The
     * default says nothing, so a paragraph that asked for its own keeps it.
     */
    @Test
    fun `a cell passes its alignment to the paragraphs directly in it`() {
        val table = TableBlock(
            columns = TableColumns.Equal(2),
            rows = listOf(
                listOf(
                    TableCell(listOf(paragraph("right")), align = TextAlign.Right),
                    TableCell(listOf(ParagraphBlock(listOf(TextRun("centred")), align = TextAlign.Center))),
                ),
            ),
        )
        val resolved = resolver().resolve(listOf(table)).single() as StyledTable
        val cells = resolved.rows.single()
        assertEquals(TextAlign.Right, (cells[0].content.single() as StyledParagraph).align)
        assertEquals(TextAlign.Center, (cells[1].content.single() as StyledParagraph).align)
    }

    /** A list's markers are counted and turned into text here, in the theme's body font. */
    @Test
    fun `list markers are resolved to text`() {
        val list = ListBlock(
            items = List(3) { ListItem(listOf(paragraph("item"))) },
            ordered = true,
            startNumber = 9,
        )
        val resolved = resolver().resolve(listOf(list)).single() as StyledList
        assertEquals(listOf("9.", "10.", "11."), resolved.items.map { it.marker?.text })
        assertEquals(FontRef.Standard14(Standard14Name.HELVETICA), resolved.items[0].marker!!.font)
        assertEquals(theme.bodyFontSize, resolved.items[0].marker!!.size)
        assertEquals(18.pt, resolved.items[0].indent, "the list's own indent, carried through")
    }

    /** The marker's suffix is the theme's, so a document can number "1)" instead of "1.". */
    @Test
    fun `the theme decides what an ordered marker is followed by`() {
        val list = ListBlock(items = listOf(ListItem(listOf(paragraph("x")))), ordered = true)
        val resolved = resolver(theme.copy(listMarkerSuffix = ")")).resolve(listOf(list)).single() as StyledList
        assertEquals("1)", resolved.items.single().marker?.text)
    }

    /** A marker is never a link and never underlined, whatever it sits inside. */
    @Test
    fun `a bullet marker carries no decoration`() {
        val list = ListBlock(items = listOf(ListItem(listOf(paragraph("x")))), markerStyle = ListMarkerStyle.Bullet)
        val marker = (resolver().resolve(listOf(list)).single() as StyledList).items.single().marker!!
        assertEquals("•", marker.text)
        assertEquals(false, marker.underline)
        assertNull(marker.link)
    }

    /** A page break resolves to itself: there is nothing about it for a theme to say. */
    @Test
    fun `a page break resolves to a page break`() {
        assertEquals(listOf(StyledPageBreak), resolver().resolve(listOf(PageBreakBlock)))
    }

    /** Resolving registers the fonts the text ended up in — and only those. */
    @Test
    fun `resolving registers the fonts the document uses`() {
        val fonts = FontCatalog()
        val block = ParagraphBlock(listOf(TextRun("plain "), StyledRun(listOf(TextRun("bold")), bold = true)))
        Resolver(theme, fonts).resolve(listOf(block))
        assertEquals(
            listOf(Standard14Name.HELVETICA, Standard14Name.HELVETICA_BOLD),
            fonts.fonts().map { (it.ref as FontRef.Standard14).name },
        )
    }
}
