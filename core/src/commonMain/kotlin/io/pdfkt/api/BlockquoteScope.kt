/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.api

import io.pdfkt.core.block.BlockquoteBlock
import io.pdfkt.core.block.TextAlign
import io.pdfkt.core.model.Pt
import io.pdfkt.core.model.Rgb

// No clause applies: the DSL is this library's own design. The bar, the indent and the nesting are the engine's to draw
// (io.pdfkt.core.block.BlockquoteBlock).

/**
 * The children of a `blockquote { }`.
 *
 * Any block nests inside a quotation — the builders come from [BlockSink], and
 * inline content written straight into it becomes a leading paragraph
 * ([BlockContainerScope]).
 *
 * ```kotlin
 * blockquote {
 *     +"Everything should be made as simple as possible,"
 *     paragraph("but not simpler.")
 *     codeBlock("val x = 1")
 * }
 * ```
 */
public class BlockquoteScope internal constructor() : BlockContainerScope(TextAlign.Start)

/**
 * The quotation a `blockquote { }` describes.
 *
 * An empty one is refused: a quotation with nothing in it is a bar drawn beside
 * nothing, which is a caller's mistake rather than a document.
 */
internal fun buildBlockquote(
    barColor: Rgb?,
    barWidth: Pt,
    indentLeft: Pt,
    block: BlockquoteScope.() -> Unit,
): BlockquoteBlock {
    val scope = BlockquoteScope()
    scope.block()
    val children = scope.build()
    require(children.isNotEmpty()) { "a blockquote quotes at least one block; this one quotes nothing" }
    return BlockquoteBlock(
        children = children,
        barColor = barColor,
        barWidth = barWidth,
        indentLeft = indentLeft,
    )
}
