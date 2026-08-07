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
import io.pdfkt.core.block.InlineRun
import io.pdfkt.core.block.ParagraphBlock
import io.pdfkt.core.block.TextAlign

// No clause applies: the DSL is this library's own design.

/**
 * What a quotation, a list item and a table cell are: somewhere blocks go, that
 * also takes inline content directly.
 *
 * Inline runs added at the top level (`+"text"`, [text], [add]) accumulate into
 * an implicit paragraph, which is closed as soon as a block arrives — so
 * `blockquote { +"Cite"; paragraph("Source") }` is two paragraphs and not a
 * paragraph inside a paragraph. Writing the text of a one-paragraph container
 * without saying "paragraph" is the whole point of the shortcut; flushing on the
 * first block is what keeps it from reordering anything.
 *
 * The three containers differ in one thing only — where their blocks end up —
 * which is why the accumulation lives here rather than three times over.
 */
@PdfktDsl
public abstract class BlockContainerScope internal constructor(private val align: TextAlign) :
    InlineRunSink,
    BlockSink {

    private val children = mutableListOf<Block>()
    private val pending = mutableListOf<InlineRun>()

    /** Append an inline run to this container's implicit paragraph. */
    final override fun add(run: InlineRun) {
        pending.add(run)
    }

    /** Append an already-built block, closing the implicit paragraph before it. */
    final override fun add(block: Block) {
        flushPending()
        children.add(block)
    }

    /** What this container collected, in the order it was written. */
    internal fun build(): List<Block> {
        flushPending()
        return children.toList()
    }

    private fun flushPending() {
        if (pending.isEmpty()) return
        children.add(ParagraphBlock(content = pending.toList(), align = align))
        pending.clear()
    }
}
