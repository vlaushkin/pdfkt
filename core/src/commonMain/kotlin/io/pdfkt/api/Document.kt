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
import io.pdfkt.core.doc.BandSpec
import io.pdfkt.core.doc.Clock
import io.pdfkt.core.doc.DocumentSpec
import io.pdfkt.core.doc.Metadata
import io.pdfkt.core.doc.render
import io.pdfkt.core.style.Theme
import kotlinx.io.Buffer
import kotlinx.io.Sink
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray

// No clause applies to the entry point itself: which shape a caller writes a
// document in is this library's own design. The
// clauses it reaches are :core's — §7.5 for the file [writeTo] produces and
// §14.3.3 for what [Document.metadata] says about it.

/**
 * A document, written and closed: the blocks, the paper they go on, and how they
 * are set.
 *
 * There is one way to get one — [document] — and no way to change one
 * afterwards: what a caller holds is what was described inside the braces,
 * whatever else happens to the scope, the theme or the arrays that went
 * in. Rendering it is [toByteArray] or [writeTo], and
 * both may be called as often as wanted: a document is not consumed by being
 * written.
 *
 * It carries no PDF knowledge. [toDocumentSpec] states it as the one thing the
 * engine takes and `render` does the rest — pagination, fonts, content streams,
 * the file itself.
 *
 * @property pageSize the paper.
 * @property margins what is kept clear around the text.
 * @property theme how the document is set — fonts, sizes, spacing and colours
 *   every block falls back to.
 * @property metadata what the document says about itself (§14.3.3).
 * @property clock where `/CreationDate` and `/ModDate` come from, or `null` if
 *   the caller set none — which is what [toDocumentSpec] refuses.
 */
public class Document internal constructor(
    public val pageSize: PageSize,
    public val margins: Margins,
    public val theme: Theme,
    public val metadata: Metadata,
    public val clock: Clock?,
    /** The band at the top of the page, as `header` described it. */
    internal val header: BandSpec?,
    /** The band at the foot of the page, as `footer` described it. */
    internal val footer: BandSpec?,
    /** The blocks of this document, in the order they were written; already copied out of the scope. */
    public val blocks: List<Block>,
) {

    /**
     * This document as the engine takes it.
     *
     * A document nothing was written into describes itself here all the same —
     * its paper, its margins, its bands and what it says about itself are decided
     * before its first paragraph is, and a caller lowering another vocabulary
     * onto this one asks for exactly that — an album builder does, for an album
     * whose photographs have not been added. What such a document cannot be is
     * *written*: that is [writeTo]'s refusal, because a PDF must have at least
     * one page.
     *
     * @throws IllegalArgumentException if no [clock] was set — there is no
     *   default to fall back to.
     */
    public fun toDocumentSpec(): DocumentSpec = DocumentSpec(
        pageSize = pageSize.toSize(),
        margins = margins.toInsets(),
        header = header,
        footer = footer,
        content = blocks,
        metadata = metadata,
        clock = requireNotNull(clock) {
            "a document's /CreationDate and /ModDate come from a clock the caller supplies — set " +
                "`clock = Clock { PdfDate.ofEpochSeconds(seconds, offsetMinutes) }`"
        },
    )
}

/**
 * Write a document.
 *
 * ```kotlin
 * import io.pdfkt.api.document
 * import io.pdfkt.api.writeTo
 *
 * val doc = document {
 *     clock = Clock { PdfDate.ofEpochSeconds(seconds, offsetMinutes = 60) }
 *
 *     paragraph {
 *         text("Report: ") { bold = true }
 *         text("Q4 2026 summary")
 *     }
 *     table {
 *         row {
 *             cell { paragraph { text("Metric") } }
 *             cell { paragraph { text("Value") } }
 *         }
 *     }
 *     image(logo)
 * }
 * ```
 *
 * This is the entry point, and the only one: an album of photographs, a report,
 * a converted Markdown file are all documents, and the vocabulary of any one of
 * them is not a second entry point. The same skeleton in
 * docxkt writes a DOCX — same verbs, same style spelling — so a developer who
 * knows one library writes the other by changing the import and the extension.
 *
 * The block is run once, immediately, against a fresh [DocumentScope]; what it
 * described is frozen into the [Document] handed back. Rendering is separate,
 * and deliberately: [Document.writeTo] takes the sink, so where the bytes go —
 * a file, a socket, a byte array — is the caller's business and never this
 * library's.
 *
 * @return the document, ready to be rendered as often as wanted.
 */
public fun document(block: DocumentScope.() -> Unit): Document {
    val scope = DocumentScope()
    scope.block()
    return scope.build()
}

/**
 * Build a rich text document.
 *
 * The old name of [document], kept for one release so that a caller migrates on
 * their own schedule. It builds the same [Document]
 * through the same scope; only the name is different, and "rich text" is a name
 * for a scenario, which is what this API stopped having.
 */
@Deprecated(
    message = "the entry point is document { } — one document, whatever is written in it",
    replaceWith = ReplaceWith("document(block)", "io.pdfkt.api.document"),
)
public fun richDocument(block: DocumentScope.() -> Unit): Document = document(block)

/**
 * Render this document and hand back the file.
 *
 * The whole of it: a PDF is written from front to back and its cross-reference
 * table carries the offsets of everything before it (ISO 32000-2:2020 §7.5.4),
 * so there is no partial file to hand back and nothing is streamed to the caller
 * before the last byte is known.
 *
 * @throws IllegalArgumentException if nothing was written into the document, or
 *   if it has no clock — see [writeTo].
 */
@Throws(Exception::class) // no-silent-loss errors must be catchable across the ObjC bridge too
public fun Document.toByteArray(): ByteArray {
    val buffer = Buffer()
    writeTo(buffer)
    return buffer.readByteArray()
}

/**
 * Render this document into [sink], which is written and flushed but not closed.
 *
 * Closing is the caller's, because opening was: a sink that came out of a `use`
 * block is closed by it, and one that is a socket this library has no business
 * closing at all.
 *
 * An empty document is refused here: a PDF must have at least one page (§7.7.3.2
 * gives the page tree at least one leaf), and a caller who rendered nothing
 * meant to render something.
 *
 * @throws IllegalArgumentException if nothing was written into the document, or
 *   if no clock was set (see [Document.toDocumentSpec]).
 */
@Throws(Exception::class) // no-silent-loss errors must be catchable across the ObjC bridge too
public fun Document.writeTo(sink: Sink) {
    require(blocks.isNotEmpty()) { "a document with no blocks has nothing to set on its first page" }
    render(toDocumentSpec(), theme, sink)
}

/**
 * Render this document into the file at [path], creating it or replacing what
 * is there.
 *
 * The file is written as it is rendered, object by object, so the whole PDF is
 * never in memory — with pictures added by path, a document of any number of
 * photographs is written in the memory of the largest one's page.
 *
 * @throws IllegalArgumentException as [writeTo] does.
 */
@Throws(Exception::class)
public fun Document.writeTo(path: Path) {
    SystemFileSystem.sink(path).buffered().use { writeTo(it) }
}
