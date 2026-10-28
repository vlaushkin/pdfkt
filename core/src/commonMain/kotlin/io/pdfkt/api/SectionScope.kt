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
import io.pdfkt.core.block.ImageFit
import io.pdfkt.core.block.PageBreakBlock
import io.pdfkt.core.image.ImageRef

// No clause applies: the DSL is this library's own design.

/**
 * The pages of one [DocumentScope.section], being written: every block builder
 * the document has, its [image] and its [pageBreak] — which here starts another
 * page of the section's paper — and nothing that is a statement about the
 * document as a whole. A section has no section inside it, and no header or
 * metadata of its own.
 *
 * A picture added here is the document's picture: the same array written into
 * two sections, or into a section and the document, is one image in the file,
 * as it is when written twice into the document.
 */
@PdfktDsl
public class SectionScope internal constructor(private val imageRef: (ByteArray) -> ImageRef) : BlockSink {
    private val blocks = mutableListOf<Block>()

    override fun add(block: Block) {
        blocks.add(block)
    }

    /**
     * Put the picture in [bytes] here, on the terms of [DocumentScope.image] —
     * fitted to the section's column, and on a page that has not the room for
     * it, moved whole to the next page of the section.
     */
    public fun image(bytes: ByteArray, fit: ImageFit = ImageFit.Contain, rotationDegrees: Int = 0) {
        add(imageBlock(bytes, fit, rotationDegrees, imageRef))
    }

    /** Break the page here: whatever is written next starts another page of the section's paper. */
    public fun pageBreak() {
        add(PageBreakBlock)
    }

    /** What this section collected, in the order it was written. */
    internal fun build(): List<Block> = blocks.toList()
}
