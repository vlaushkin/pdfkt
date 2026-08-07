/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.api

import io.pdfkt.core.block.CodeBlock
import io.pdfkt.core.block.HeadingBlock
import io.pdfkt.core.block.PageBreakBlock
import io.pdfkt.core.block.ParagraphBlock
import io.pdfkt.core.block.TextAlign
import io.pdfkt.core.block.TextRun
import io.pdfkt.core.doc.Clock
import io.pdfkt.core.doc.PageNumbering
import io.pdfkt.core.model.Size
import io.pdfkt.core.model.pt
import io.pdfkt.core.obj.PdfDate
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The clock every test that renders pins, so the bytes of a document are fixed. */
internal val PINNED_CLOCK: Clock = Clock { PdfDate(2026, 8, 5, 12, 0, 0, offsetMinutes = 0) }

/**
 * The `document { }` entry point: what it collects, in what order, and what
 * it refuses.
 *
 * The DSL is asserted against the block tree it produces rather than against a
 * rendered file — the DSL's whole job is to build that tree and hand it
 * over, so the tree is the observable output here. What
 * the engine then draws is asserted where it is decided, in :core.
 */
class DocumentScopeTest {

    @Test
    fun theDocumentStartsOnA4WithHalfInchMargins() {
        val document = document {
            clock = PINNED_CLOCK
            paragraph("body")
        }

        assertEquals(PageSize.A4, document.pageSize)
        assertEquals(Margins.Fixed(36.pt, 36.pt, 36.pt, 36.pt), document.margins)
        assertEquals(Size.A4, document.toDocumentSpec().pageSize)
    }

    @Test
    fun blocksAreCollectedInTheOrderTheyWereWritten() {
        val document = document {
            pageSize = PageSize.Letter
            margins = Margins.Fixed(top = 20.pt, right = 20.pt, bottom = 20.pt, left = 20.pt)

            heading(level = 1, "Quarterly Report")
            paragraph { +"This is the Q4 2026 summary." }
            paragraph("Trailing note", align = TextAlign.Center)
        }

        assertEquals(PageSize.Letter, document.pageSize)
        assertEquals(Margins.Fixed(20.pt, 20.pt, 20.pt, 20.pt), document.margins)

        val blocks = document.blocks
        assertEquals(3, blocks.size)

        val heading = blocks[0] as HeadingBlock
        assertEquals(1, heading.level)
        assertEquals(listOf(TextRun("Quarterly Report")), heading.content)

        val first = blocks[1] as ParagraphBlock
        assertEquals(listOf(TextRun("This is the Q4 2026 summary.")), first.content)
        assertEquals(TextAlign.Left, first.align)

        val second = blocks[2] as ParagraphBlock
        assertEquals(listOf(TextRun("Trailing note")), second.content)
        assertEquals(TextAlign.Center, second.align)
    }

    @Test
    fun anInlineScopeAccumulatesItsRuns() {
        val document = document {
            paragraph {
                +"first "
                text("second ")
                add(TextRun("third"))
            }
        }

        val paragraph = document.blocks.single() as ParagraphBlock
        assertEquals(listOf(TextRun("first "), TextRun("second "), TextRun("third")), paragraph.content)
    }

    @Test
    fun aHeadingKeepsWhatFollowsIt() {
        val document = document { heading(level = 3) { +"Section" } }

        val heading = document.blocks.single() as HeadingBlock
        assertEquals(3, heading.level)
        assertTrue(heading.keepWithNext, "a heading separated from its text has failed at its one job")
        assertTrue(heading.keepTogether)
    }

    @Test
    fun prebuiltBlocksAreAcceptedAsTheyAre() {
        val block = ParagraphBlock(content = listOf(TextRun("converted")), indentLeft = 12.pt)
        val document = document { add(block) }

        assertEquals(listOf(block), document.blocks)
    }

    @Test
    fun aHeadingLevelOutsideOneToSixIsRefused() {
        assertFailsWith<IllegalArgumentException> { document { heading(level = 0, "bad") } }
        assertFailsWith<IllegalArgumentException> { document { heading(level = 7, "bad") } }
    }

    @Test
    fun anEmptyDocumentIsRefused() {
        val refused = assertFailsWith<IllegalArgumentException> {
            document { clock = PINNED_CLOCK }.toByteArray()
        }
        assertTrue(
            refused.message.orEmpty().contains("no blocks"),
            "the message should say what is missing, got: ${refused.message}",
        )
    }

    @Test
    fun aDocumentWithNothingWrittenIntoItStillDescribesItself() {
        // The refusal above is about a *file*: a document that has not been
        // written into yet has a paper, margins, a colophon and a title all the
        // same, and a caller lowering onto this DSL asks for exactly that — an
        // album whose photographs have not been added is a `documentSpec()`.
        val spec = document {
            clock = PINNED_CLOCK
            pageSize = PageSize.Letter
            header("Cover", pages = BandPages.FirstOnly)
        }.toDocumentSpec()

        assertEquals(Size.LETTER, spec.pageSize)
        assertTrue(spec.content.isEmpty(), "nothing was written into it, so it has no blocks")
        assertNotNull(spec.header, "and it still knows what its running head would be")
    }

    @Test
    fun aDocumentWithoutAClockIsRefused() {
        // "Real clock, injected": there is no default to
        // fall back to, so the omission is reported rather than papered over with
        // a date this library invented.
        val refused = assertFailsWith<IllegalArgumentException> {
            document { paragraph("body") }.toByteArray()
        }
        assertTrue(
            refused.message.orEmpty().contains("clock"),
            "the message should name the clock, got: ${refused.message}",
        )
    }

    @Test
    fun theColophonsAreCarriedToTheSeamAsBands() {
        val document = document {
            clock = PINNED_CLOCK
            header("Отчёт за квартал")
            footer("Черновик")
            paragraph("body")
        }

        val spec = document.toDocumentSpec()
        val head = assertNotNull(spec.header, "header() should have put a band on the document")
        val foot = assertNotNull(spec.footer, "footer() should have put a band on the document")

        assertEquals(DEFAULT_BAND_HEIGHT, head.height, "the default strip is the standard band height")
        assertEquals(DEFAULT_BAND_HEIGHT, foot.height)

        // The band's content is a function of the page numbering, and a colophon
        // that says the same thing on every page says it on every page.
        val onFirst = head.content(PageNumbering(page = 1, pages = 3)).single() as ParagraphBlock
        val onLast = head.content(PageNumbering(page = 3, pages = 3)).single() as ParagraphBlock
        assertEquals(listOf(TextRun("Отчёт за квартал")), onFirst.content)
        assertEquals(onFirst, onLast)

        // The defaults are the two sides a running head and a running foot are
        // read on, and they differ.
        assertEquals(TextAlign.Left, onFirst.align)
        assertEquals(
            TextAlign.Right,
            (foot.content(PageNumbering(page = 1, pages = 3)).single() as ParagraphBlock).align,
        )
    }

    @Test
    fun aColophonIsReplacedRatherThanRepeated() {
        val document = document {
            clock = PINNED_CLOCK
            header("first try")
            header("second try", align = TextAlign.Center, height = 40.pt)
            paragraph("body")
        }

        val head = assertNotNull(document.toDocumentSpec().header)
        assertEquals(40.pt, head.height)
        val line = head.content(PageNumbering(page = 1, pages = 1)).single() as ParagraphBlock
        assertEquals(listOf(TextRun("second try")), line.content)
        assertEquals(TextAlign.Center, line.align)
    }

    @Test
    fun aDocumentWithNoColophonAsksForNoStrip() {
        val spec = document {
            clock = PINNED_CLOCK
            paragraph("body")
        }.toDocumentSpec()

        assertNull(spec.header, "a document that asked for no header should reserve nothing for one")
        assertNull(spec.footer)
        assertEquals(spec.printableArea, spec.contentArea, "with no bands the content gets the whole column")
    }

    @Test
    fun aPageBreakIsABlockInTheSequenceWhereItWasWritten() {
        val document = document {
            clock = PINNED_CLOCK
            paragraph("first")
            pageBreak()
            paragraph("second")
        }

        val blocks = document.blocks
        assertEquals(3, blocks.size)
        assertEquals(PageBreakBlock, blocks[1])
        assertEquals(listOf(TextRun("second")), (blocks[2] as ParagraphBlock).content)
    }

    @Test
    fun everyBlockNestsInsideEveryContainer() {
        // A quotation holding a code block and a list renders, rather than
        // reporting a child type it cannot draw: the containers share one set of
        // builders and the engine plans them all with one function.
        val document = document {
            clock = PINNED_CLOCK
            blockquote {
                add(CodeBlock("val x = 1"))
                bulletList { item { +"a" } }
                table {
                    row {
                        cell {
                            +"nested"
                            blockquote { +"deeper still" }
                        }
                    }
                }
            }
        }

        assertTrue(document.toByteArray().isNotEmpty())
    }

    @Test
    fun renderingProducesAFileThatBeginsAndEndsAsAPdfDoes() {
        val bytes = document {
            clock = PINNED_CLOCK
            heading(level = 1, "Quarterly Report")
            paragraph { +"This is the Q4 2026 summary." }
        }.toByteArray()

        assertTrue(bytes.size > MINIMAL_FILE, "expected a document, got ${bytes.size} bytes")
        assertEquals("%PDF", bytes.copyOfRange(0, 4).decodeToString())
        assertTrue(bytes.decodeToString().trimEnd().endsWith("%%EOF"), "a PDF file ends with %%EOF")
    }
}

/** Below this even an empty page cannot have been written. */
private const val MINIMAL_FILE: Int = 100

/** landscape() swaps the page's sides; twice turns it back. */
class LandscapeTest {
    @kotlin.test.Test
    fun `landscape swaps the sides and a second call restores them`() {
        val scope = document { landscape() }
        val turned = scope.pageSize.toSize()
        val a4 = PageSize.A4.toSize()
        kotlin.test.assertEquals(a4.height, turned.width)
        kotlin.test.assertEquals(a4.width, turned.height)

        val back = document {
            landscape()
            landscape()
        }.pageSize.toSize()
        kotlin.test.assertEquals(a4, back)
    }
}

/**
 * `richDocument { }` is an alias of `document { }`,
 * kept for one release so that a caller migrates on their own schedule.
 *
 * What it hands back is the same [Document], and the same document renders to
 * the same bytes: the two spellings are one function, not two code paths that
 * could drift.
 */
class DeprecatedRichDocumentTest {

    @Test
    fun theOldNameBuildsTheSameDocument() {
        @Suppress("DEPRECATION")
        val old: Document = richDocument {
            clock = PINNED_CLOCK
            pageSize = PageSize.Letter
            heading(level = 1, "Quarterly Report")
            paragraph("body")
        }
        val new: Document = document {
            clock = PINNED_CLOCK
            pageSize = PageSize.Letter
            heading(level = 1, "Quarterly Report")
            paragraph("body")
        }

        assertEquals(new.blocks, old.blocks)
        assertEquals(new.pageSize, old.pageSize)
        assertContentEquals(new.toByteArray(), old.toByteArray(), "the two spellings render one document")
    }
}
