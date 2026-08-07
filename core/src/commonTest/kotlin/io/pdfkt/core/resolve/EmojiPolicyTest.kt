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
import io.pdfkt.core.block.ListBlock
import io.pdfkt.core.block.ListItem
import io.pdfkt.core.block.ParagraphBlock
import io.pdfkt.core.block.StyledRun
import io.pdfkt.core.block.TextRun
import io.pdfkt.core.font.FontCatalog
import io.pdfkt.core.font.UnencodableCharacterException
import io.pdfkt.core.style.EmojiPolicy
import io.pdfkt.core.style.Theme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

// The policies `Strip` and `Placeholder` are pure text transforms in resolve —
// L3 uninvolved.

/**
 * What the two text-transform emoji policies do, and what they leave alone.
 *
 * Both are lossy on purpose, and the test of a deliberate loss is that it loses
 * exactly what it said it would: the emoji, whole, and nothing around it. The
 * spaces on either side are asserted every time — a policy that tidies while it
 * strips is a policy that edits the caller's text.
 */
class EmojiPolicyTest {
    private fun texts(policy: EmojiPolicy, text: String): List<String> =
        runsOf(policy, ParagraphBlock(listOf(TextRun(text)))).map { it.text }

    private fun runsOf(policy: EmojiPolicy, block: Block): List<StyledTextRun> {
        val resolved = Resolver(Theme.Default.copy(emojiPolicy = policy), FontCatalog()).resolve(listOf(block))
        return (resolved.single() as StyledParagraph).content.filterIsInstance<StyledTextRun>()
    }

    /**
     * Strip removes the emoji and joins what is left: the two halves are drawn
     * identically, so they are one run — and the two spaces that surrounded the
     * emoji are both still there.
     */
    @Test
    fun `strip removes the emoji and leaves the spacing`() {
        assertEquals(listOf("Hello  world"), texts(EmojiPolicy.Strip, "Hello $WAVE world"))
    }

    /** A whole ZWJ sequence goes at once — no joiners left behind, no half a family. */
    @Test
    fun `strip removes a zero-width-joiner sequence whole`() {
        assertEquals(listOf("family: "), texts(EmojiPolicy.Strip, "family: $FAMILY"))
    }

    /** Text that is entirely emoji strips to nothing at all, not to an empty run. */
    @Test
    fun `text that is all emoji strips to no runs`() {
        assertEquals(emptyList(), texts(EmojiPolicy.Strip, WAVE + FAMILY))
    }

    /** Placeholder names the code point the way Unicode names it, in the run's own font. */
    @Test
    fun `placeholder writes the code point of the emoji`() {
        assertEquals(listOf("Hello [U+1F44B] world"), texts(EmojiPolicy.Placeholder, "Hello $WAVE world"))
    }

    /**
     * All of the code points, not just the first: a family is four people and
     * three joiners, and naming only the man would be discarding the rest of
     * what the placeholder stands for.
     */
    @Test
    fun `placeholder names every code point of a sequence`() {
        assertEquals(
            listOf("[U+1F468 U+200D U+1F469 U+200D U+1F467 U+200D U+1F466]"),
            texts(EmojiPolicy.Placeholder, FAMILY),
        )
    }

    /** The placeholder is drawn in the run's own font, at its size, in its colour. */
    @Test
    fun `a placeholder is drawn in the run's own style`() {
        val block = ParagraphBlock(listOf(StyledRun(listOf(TextRun("hi $WAVE")), bold = true)))
        val run = runsOf(EmojiPolicy.Placeholder, block).single()
        assertEquals("hi [U+1F44B]", run.text)
        assertEquals(Theme.Default.bodyFamily.bold, run.font)
    }

    /** None leaves the text as it was — and then the font has to have the glyph. */
    @Test
    fun `none leaves the emoji in the text`() {
        val supplied = Theme.Default.copy(emojiPolicy = EmojiPolicy.None)
        val refused = assertFailsWith<UnencodableCharacterException> {
            Resolver(supplied, FontCatalog()).resolve(listOf(ParagraphBlock(listOf(TextRun("Hi $WAVE")))))
        }
        assertEquals(0x1F44B, refused.codePoint)
        assertEquals(3, refused.index, "the wave is at index 3 of \"Hi 👋\"")
    }

    /**
     * A character that merely *can* be an emoji is not one: © has no emoji
     * presentation of its own (UTS #51 §2.1), and a copyright line survives a
     * strip policy intact.
     */
    @Test
    fun `strip leaves a text-presentation character alone`() {
        assertEquals(listOf("© 2026 pdfkt"), texts(EmojiPolicy.Strip, "© 2026 pdfkt"))
    }

    /** A digit is a digit: a numbered paragraph does not lose its number to a keycap rule. */
    @Test
    fun `strip leaves digits alone`() {
        assertEquals(listOf("1. Introduction"), texts(EmojiPolicy.Strip, "1. Introduction"))
    }

    /** The policy reaches every text in the document, including a list's items. */
    @Test
    fun `the policy applies inside a list`() {
        val list = ListBlock(listOf(ListItem(listOf(ParagraphBlock(listOf(TextRun("done $WAVE")))))))
        val resolved = Resolver(Theme.Default.copy(emojiPolicy = EmojiPolicy.Strip), FontCatalog())
            .resolve(listOf(list)).single() as StyledList
        val paragraph = resolved.items.single().content.single() as StyledParagraph
        assertEquals("done ", (paragraph.content.single() as StyledTextRun).text)
    }

    /** And a code block, whose lines stay lines however much text a policy removes. */
    @Test
    fun `the policy applies inside a code block and keeps the lines`() {
        val code = CodeBlock("val a = 1 // $WAVE\n$WAVE\nval b = 2")
        val resolved = Resolver(Theme.Default.copy(emojiPolicy = EmojiPolicy.Strip), FontCatalog())
            .resolve(listOf(code)).single() as StyledCode
        assertEquals(listOf("val a = 1 // ", "", "val b = 2"), resolved.lines.map { it.text })
    }

    /**
     * Nothing but the emoji changes. Whatever the policy, deleting every
     * placeholder and every emoji from both sides leaves the same text — which
     * is the property "pure text transform" actually means.
     */
    @Test
    fun `a policy touches nothing but the emoji`() {
        val source = "Report $WAVE 2026: 100% done, see § 4 ($FAMILY)"
        val stripped = texts(EmojiPolicy.Strip, source).single()
        val placeheld = texts(EmojiPolicy.Placeholder, source).single()
        assertEquals(stripped, placeheld.replace(Regex("\\[U\\+[0-9A-FU+ ]+\\]"), ""))
        assertTrue(stripped.startsWith("Report "), "the text before the emoji is untouched")
        assertTrue(stripped.endsWith("see § 4 ()"), "the text after it is untouched")
    }

    private companion object {
        /** U+1F44B WAVING HAND SIGN. */
        const val WAVE: String = "👋"

        /** U+1F468 U+200D U+1F469 U+200D U+1F467 U+200D U+1F466 — family: man, woman, girl, boy. */
        const val FAMILY: String = "👨‍👩‍👧‍👦"
    }
}
