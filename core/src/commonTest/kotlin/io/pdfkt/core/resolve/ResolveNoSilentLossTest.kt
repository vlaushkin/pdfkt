/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.resolve

import io.pdfkt.core.block.Block
import io.pdfkt.core.block.CodeBlock
import io.pdfkt.core.block.HeadingBlock
import io.pdfkt.core.block.ListBlock
import io.pdfkt.core.block.ListItem
import io.pdfkt.core.block.ListMarkerStyle
import io.pdfkt.core.block.ParagraphBlock
import io.pdfkt.core.block.StyledRun
import io.pdfkt.core.block.TextRun
import io.pdfkt.core.font.FontCatalog
import io.pdfkt.core.font.UnencodableCharacterException
import io.pdfkt.core.font.WinAnsiEncoding
import io.pdfkt.core.style.EmojiPolicy
import io.pdfkt.core.style.Theme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

// ISO 32000-2:2020 Annex D "Character sets and encodings" (Table D.2),
// §9.6.5.1 "Character encoding — General". Engine policy: no silent loss.

/**
 * What happens to a character the font cannot draw: it stops the document, here,
 * with its position in the string the caller wrote.
 *
 * The failure this guards against is a renderer that produces fully blank
 * Cyrillic documents with every test green — so the assertion is not just that
 * something was thrown but that the report can be acted on: which character,
 * where in *the caller's* text, and what to do about it.
 */
class ResolveNoSilentLossTest {
    private val resolver = Resolver(Theme.Default, FontCatalog())

    private fun refusalFrom(block: Block, theme: Theme = Theme.Default): UnencodableCharacterException = try {
        Resolver(theme, FontCatalog()).resolve(listOf(block))
        fail("expected the standard 14 font to refuse the text of $block")
    } catch (refused: UnencodableCharacterException) {
        refused
    }

    /**
     * Cyrillic in a Latin font: refused, naming the character and its index in
     * the paragraph's own string — `text.substring(index)` starts at it, as the
     * exception documents.
     */
    @Test
    fun `cyrillic in a standard 14 font is refused with its position`() {
        val text = "Hello, Привет"
        val refused = refusalFrom(ParagraphBlock(listOf(TextRun(text))))
        assertEquals(text, refused.text, "the report quotes the caller's own string")
        assertEquals(7, refused.index)
        assertEquals("П", refused.character)
        assertEquals(0x041F, refused.codePoint)
        assertEquals(WinAnsiEncoding.NAME, refused.encodingName)
        assertTrue(refused.message!!.contains("FontRef.Supplied"), "the remedy is named: supply a font")
    }

    /**
     * The index is into the caller's whole string, not into the fragment the
     * emoji splitter handed to the font. Here an emoji is stripped out first, so
     * the text that reaches the font begins two characters in — and the report
     * still points at the character as the caller can count it.
     */
    @Test
    fun `the position survives the text being split into segments`() {
        val text = "$WAVE Привет"
        val stripping = Theme.Default.copy(emojiPolicy = EmojiPolicy.Strip)
        val refused = refusalFrom(ParagraphBlock(listOf(TextRun(text))), stripping)
        assertEquals(text, refused.text)
        assertEquals(3, refused.index, "the П follows the wave (two Chars) and a space")
        assertEquals("П", refused.character)
    }

    /** A nested run is reported against its own text, which is the string that carries the character. */
    @Test
    fun `a character inside nested markup is reported against its own run`() {
        val refused = refusalFrom(
            ParagraphBlock(listOf(TextRun("ok "), StyledRun(listOf(TextRun("Ж")), bold = true))),
        )
        assertEquals("Ж", refused.text)
        assertEquals(0, refused.index)
        assertTrue(refused.fontName.contains("Bold"), "the font that refused is the face the run resolved to")
    }

    /** A heading is refused as loudly as a paragraph: a title is not a place to lose a letter. */
    @Test
    fun `a heading is refused too`() {
        val refused = refusalFrom(HeadingBlock(1, listOf(TextRun("Глава"))))
        assertEquals("Г", refused.character)
        assertEquals(0, refused.index)
    }

    /** So is a code block, and the index points into the code, line breaks counted. */
    @Test
    fun `a code block is refused with the position in the code`() {
        val code = "val x = 1\nval y = \"Ж\""
        val refused = refusalFrom(CodeBlock(code))
        assertEquals(code, refused.text)
        assertEquals("Ж", refused.character)
        assertEquals(19, refused.index, "the Ж is on the second line, the line feed counted as one character")
    }

    /**
     * A marker the theme's font has no glyph for is refused like any other text.
     * That is the point of markers being text: U+25E6 is not in Table D.2, and a
     * list that asked for it with a standard 14 font gets an error rather than a
     * page of items with nothing beside them.
     */
    @Test
    fun `a marker the font cannot draw is refused`() {
        val list = ListBlock(
            items = listOf(ListItem(listOf(ParagraphBlock(listOf(TextRun("item")))))),
            markerStyle = ListMarkerStyle.Circle,
        )
        val refused = refusalFrom(list)
        assertEquals("◦", refused.character)
        assertEquals(0x25E6, refused.codePoint)
    }

    /** The markers Table D.2 does carry go through: • and – are Latin-set characters. */
    @Test
    fun `the bullet and dash markers are inside the latin character set`() {
        listOf(ListMarkerStyle.Bullet to "•", ListMarkerStyle.Dash to "–").forEach { (style, expected) ->
            val list = ListBlock(
                items = listOf(ListItem(listOf(ParagraphBlock(listOf(TextRun("item")))))),
                markerStyle = style,
            )
            val resolved = resolver.resolve(listOf(list)).single() as StyledList
            assertEquals(expected, resolved.items.single().marker?.text)
        }
    }

    private companion object {
        /** U+1F44B WAVING HAND SIGN. */
        const val WAVE: String = "👋"
    }
}
