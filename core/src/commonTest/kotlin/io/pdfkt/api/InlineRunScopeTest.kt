/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.api

import io.pdfkt.core.block.Block
import io.pdfkt.core.block.BlockquoteBlock
import io.pdfkt.core.block.BreakRun
import io.pdfkt.core.block.InlineRun
import io.pdfkt.core.block.LinkRun
import io.pdfkt.core.block.ListBlock
import io.pdfkt.core.block.ParagraphBlock
import io.pdfkt.core.block.StyledRun
import io.pdfkt.core.block.TableBlock
import io.pdfkt.core.block.TextRun
import io.pdfkt.core.model.Rgb
import io.pdfkt.core.model.pt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The inline builders every scope shares (`bold`, `link`, `styled`, …) against
 * the runs they produce.
 *
 * What a bold run is *set in* is the engine's resolve stage and is asserted
 * there; what this file asserts is that the DSL says what the caller wrote.
 */
class InlineRunScopeTest {

    private fun runsOf(block: InlineScope.() -> Unit): List<InlineRun> {
        val blocks = document { paragraph(block = block) }.blocks
        return assertIs<ParagraphBlock>(blocks.single()).content
    }

    @Test
    fun eachStyleBuilderProducesItsStyledRun() {
        val runs = runsOf {
            bold("b")
            italic("i")
            code("c")
            strike("s")
            underline("u")
        }

        assertEquals(5, runs.size)
        val bold = assertIs<StyledRun>(runs[0])
        assertTrue(bold.bold)
        assertEquals(listOf(TextRun("b")), bold.children)
        assertTrue(assertIs<StyledRun>(runs[1]).italic)
        assertTrue(assertIs<StyledRun>(runs[2]).code)
        assertTrue(assertIs<StyledRun>(runs[3]).strike)
        assertTrue(assertIs<StyledRun>(runs[4]).underline)
    }

    @Test
    fun theColorBuilderCarriesTheColor() {
        val red = Rgb.of(0xCC0000)
        val runs = runsOf { color(red, "danger") }

        assertEquals(red, assertIs<StyledRun>(runs.single()).color)
    }

    @Test
    fun styledCombinesEveryOptionAtOnce() {
        val runs = runsOf {
            styled(
                bold = true,
                italic = true,
                code = true,
                strike = true,
                underline = true,
                color = Rgb.gray(0.5f),
                fontSize = 9.pt,
            ) { +"all" }
        }

        val styled = assertIs<StyledRun>(runs.single())
        assertTrue(styled.bold && styled.italic && styled.code && styled.strike && styled.underline)
        assertEquals(Rgb.gray(0.5f), styled.color)
        assertEquals(9.pt, styled.fontSize)
        assertEquals(listOf(TextRun("all")), styled.children)
    }

    @Test
    fun nestedBuildersNestRatherThanReplace() {
        // Every property of a StyledRun is an override, so nesting composes:
        // `bold { italic("both") }` is a bold run holding an italic one, which
        // the resolve stage reads as both.
        val runs = runsOf { bold { italic("both") } }

        val outer = assertIs<StyledRun>(runs.single())
        assertTrue(outer.bold)
        val inner = assertIs<StyledRun>(outer.children.single())
        assertTrue(inner.italic)
        assertEquals(listOf(TextRun("both")), inner.children)
    }

    @Test
    fun theLinkBuilderCarriesItsTarget() {
        val runs = runsOf { link("https://example.com", "details") }

        val link = assertIs<LinkRun>(runs.single())
        assertEquals("https://example.com", link.href)
        assertEquals(listOf(TextRun("details")), link.children)
    }

    @Test
    fun aLinksTextIsItselfInlineContent() {
        val runs = runsOf { link("https://example.com") { bold("details") } }

        val link = assertIs<LinkRun>(runs.single())
        assertTrue(assertIs<StyledRun>(link.children.single()).bold)
    }

    @Test
    fun aBreakIsHardUnlessItIsAskedToBeSoft() {
        val runs = runsOf {
            br()
            br(soft = true)
        }

        assertEquals(listOf(BreakRun(hard = true), BreakRun(hard = false)), runs)
    }

    @Test
    fun plainTextIsWrittenThreeWaysAndArrivesTheSame() {
        val runs = runsOf {
            +"a"
            text("b")
            add(TextRun("c"))
        }

        assertEquals(listOf(TextRun("a"), TextRun("b"), TextRun("c")), runs)
    }

    @Test
    fun theConfigureBlockCarriesEveryPropertyStyledRunHas() {
        // The property form says exactly what
        // StyledRun carries — the same seven fields, named the same.
        val runs = runsOf {
            text("all") {
                bold = true
                italic = true
                code = true
                strike = true
                underline = true
                color = Rgb.gray(0.5f)
                fontSize = 9.pt
            }
        }

        assertEquals(
            StyledRun(
                children = listOf(TextRun("all")),
                bold = true,
                italic = true,
                code = true,
                underline = true,
                strike = true,
                color = Rgb.gray(0.5f),
                fontSize = 9.pt,
            ),
            runs.single(),
        )
    }

    @Test
    fun thePropertyFormAndTheStyledFormBuildTheSameRun() {
        val byProperties = runsOf {
            text("warning") {
                bold = true
                color = Rgb.of(0xCC0000)
            }
        }
        val byFunction = runsOf { styled(bold = true, color = Rgb.of(0xCC0000)) { +"warning" } }

        assertEquals(byFunction, byProperties)
    }

    @Test
    fun aConfigureBlockThatSetsNothingStillProducesARunThatOverridesNothing() {
        // The shape of the tree does not depend on what the lambda did at
        // runtime: `text(v) { if (total) bold = true }` is a StyledRun either way.
        val runs = runsOf { text("plain") { } }

        assertEquals(StyledRun(listOf(TextRun("plain"))), runs.single())
    }

    @Test
    fun thePropertyFormAndTheHelperFormMixInOneParagraph() {
        val runs = runsOf {
            text("Report: ") { bold = true }
            bold("Q4 2026")
            +" — "
            italic("draft")
            text("tail") { color = Rgb.of(0x0000CC) }
        }

        assertEquals(5, runs.size)
        assertTrue(assertIs<StyledRun>(runs[0]).bold)
        assertTrue(assertIs<StyledRun>(runs[1]).bold)
        assertEquals(TextRun(" — "), runs[2])
        assertTrue(assertIs<StyledRun>(runs[3]).italic)
        assertEquals(Rgb.of(0x0000CC), assertIs<StyledRun>(runs[4]).color)
    }

    @Test
    fun thePropertyFormIsTheSameInEveryContainer() {
        // The overload is on InlineRunSink, so every scope that takes inline
        // content has it — not just a paragraph.
        val document = document {
            blockquote { text("in a quote") { bold = true } }
            bulletList { item { text("in an item") { bold = true } } }
            table {
                row { cell { text("in a cell") { bold = true } } }
            }
        }

        val bolds = document.blocks.flatMap { block -> boldTexts(block) }
        assertEquals(listOf("in a quote", "in an item", "in a cell"), bolds)
    }

    @Test
    fun theInlineBuildersAreTheSameInEveryContainer() {
        // One interface, four scopes: what a paragraph accepts is what a
        // quotation, a list item and a cell accept.
        val document = document {
            blockquote { bold("in a quote") }
            bulletList { item { bold("in an item") } }
            table {
                row { cell { bold("in a cell") } }
            }
        }

        val bolds = document.blocks.flatMap { block -> boldTexts(block) }
        assertEquals(listOf("in a quote", "in an item", "in a cell"), bolds)
    }

    /** Every bold run's text under [block], in document order. */
    private fun boldTexts(block: Block): List<String> = when (block) {
        is ParagraphBlock -> block.content.filterIsInstance<StyledRun>()
            .filter { run -> run.bold }
            .flatMap { run -> run.children.filterIsInstance<TextRun>().map { text -> text.text } }

        is BlockquoteBlock -> block.children.flatMap { child -> boldTexts(child) }
        is ListBlock -> block.items.flatMap { item -> item.content.flatMap { child -> boldTexts(child) } }
        is TableBlock -> block.rows.flatten().flatMap { cell -> cell.content.flatMap { child -> boldTexts(child) } }
        else -> emptyList()
    }
}
