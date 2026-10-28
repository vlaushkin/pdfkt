/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.doc

import io.pdfkt.core.block.VerticalAlign
import io.pdfkt.core.emit.PlanEmitter
import io.pdfkt.core.font.FontCatalog
import io.pdfkt.core.fragment.Fragmenter
import io.pdfkt.core.image.ImageCatalog
import io.pdfkt.core.model.Insets
import io.pdfkt.core.model.Size
import io.pdfkt.core.obj.PdfDate
import io.pdfkt.core.plan.PagePlan
import io.pdfkt.core.resolve.Resolver
import io.pdfkt.core.resolve.StyledBlock
import io.pdfkt.core.resolve.StyledPageBreak
import io.pdfkt.core.resolve.StyledSection
import io.pdfkt.core.style.Theme
import kotlinx.io.Sink

// ISO 32000-2:2020 §7.5.5 "File trailer", §14.3.3 "Document information
// dictionary", §14.4 "File identifiers". The stages themselves are this
// library's own design.

/**
 * The engine's one entry point: a document in, a PDF file out.
 *
 * ```
 * render(
 *     DocumentSpec(
 *         pageSize = Size.A4,
 *         margins = Insets.uniform(56.pt),
 *         content = blocks,
 *         metadata = Metadata(title = "Report"),
 *         clock = Clock { PdfDate.ofEpochSeconds(seconds, offsetMinutes = 60) },
 *     ),
 *     Theme.Default,
 *     sink,
 * )
 * ```
 *
 * The body of it is the pipeline read out loud —
 * `blocks + theme → resolve → fragment → plan → emit` — and nothing else. That
 * is the point of the seam: every caller reaches the engine through this function,
 * so anything a caller would otherwise do for itself is done once, here or
 * below, where it is tested against plans rather than against a rendered file.
 *
 * One [FontCatalog] and one [ImageCatalog] serve every stage, which each stage
 * requires of its caller: a font measured from one catalog and written from
 * another would be two fonts, and an image planned against one and written from
 * another would be a reference to nothing.
 *
 * The clock is read exactly once ([Clock.now]), before anything is written. A
 * document has one creation time; reading twice could straddle a second and
 * write a file whose `/ModDate` precedes its `/CreationDate`.
 *
 * The bands of [DocumentSpec.header] and [DocumentSpec.footer] are reserved
 * before the fragmenter runs — it is given [DocumentSpec.contentArea], which is
 * the page between them — and planned into their strips after it has, by
 * [BandPlanner]. That order is the whole of why the reserve exists: it fixes the
 * content area without knowing what goes in the bands, the content area fixes how
 * many pages there are, and the page count is what a band saying "Page 3 of 12"
 * needs before it can be planned at all. Every page's bands are planned before
 * the first page is emitted, so a band that does not fit its strip stops the
 * document instead of the thirty-seventh page of it.
 *
 * @param spec the document, including the clock its dates come from.
 * @param theme how it is set — the one input that changes what the document
 *   looks like without changing what it says.
 * @param sink where the file goes. It is written and flushed, not closed: the
 *   caller owns it.
 * @throws io.pdfkt.core.font.UnencodableCharacterException if a character cannot
 *   be drawn in the font it was resolved to. Per the no-silent-loss policy,
 *   the file is not written rather than written with a hole in it.
 * @throws io.pdfkt.core.fragment.UnplaceableBlockException if a block cannot be
 *   placed even on an empty page.
 * @throws BandOverflowException if a header or footer is taller than the strip
 *   reserved for it on any page. Nothing has been written to [sink] when it is
 *   raised.
 */
public fun render(spec: DocumentSpec, theme: Theme, sink: Sink) {
    val stamp = spec.clock.now()
    val fonts = FontCatalog()
    val images = ImageCatalog()
    val styled = Resolver(theme, fonts).resolve(spec.content)
    val laidOut = layOut(spec, styled, fonts)
    val pages: List<PagePlan> = laidOut.map { it.first }
    val bands = BandPlanner(theme, fonts).plan(spec, laidOut.map { it.second })
    val emitter = PlanEmitter(PdfDocumentWriter(sink), fonts, images)
    for ((index, page) in pages.withIndex()) emitter.emit(PagePlan(page.size, page.items + bands[index]))
    emitter.finish(
        fileIdentifier = fileIdentifierOf(stamp, spec.metadata, pages.size),
        info = documentInformation(spec.metadata, creationDate = stamp),
    )
}

/**
 * The paper of one page: its size and what is kept clear around it — the
 * document's own, or a section's.
 */
internal data class PageSetup(val size: Size, val margins: Insets)

/**
 * [styled] fragmented onto pages, each beside the paper it is on.
 *
 * The top level of the document is a sequence of flows: the blocks between two
 * sections are fragmented onto the document's own paper, and each section onto
 * its own, every flow starting a page of its own. A flow with nothing in it but
 * page breaks — the blocks between two sections that follow each other, or a
 * break written just before one — asks for no page and gets none, which is the
 * rule a break at the top of an empty page already follows. A section with
 * nothing in it is one blank page of its paper, as an empty document is one
 * blank page of the document's.
 */
internal fun layOut(
    spec: DocumentSpec,
    styled: List<StyledBlock>,
    fonts: FontCatalog,
): List<Pair<PagePlan, PageSetup>> {
    val document = PageSetup(spec.pageSize, spec.margins)
    val pages = mutableListOf<Pair<PagePlan, PageSetup>>()
    val flow = mutableListOf<StyledBlock>()

    fun fragment(setup: PageSetup, align: VerticalAlign, blocks: List<StyledBlock>) {
        val template = spec.pageTemplate(setup.size, setup.margins)
        Fragmenter(fonts, template, align).fragment(blocks).mapTo(pages) { page -> page to setup }
    }

    fun flush() {
        if (flow.any { block -> block !is StyledPageBreak }) fragment(document, VerticalAlign.Top, flow.toList())
        flow.clear()
    }

    for (block in styled) {
        if (block is StyledSection) {
            flush()
            fragment(PageSetup(block.pageSize, block.margins), block.verticalAlign, block.content)
        } else {
            flow += block
        }
    }
    flush()
    if (pages.isEmpty()) fragment(document, VerticalAlign.Top, emptyList())
    return pages
}

/**
 * The `/ID` of the trailer for a document created at [stamp].
 *
 * §14.4 wants two things of it. The first is what it is derived from: "The first
 * byte string shall be a permanent identifier based on the contents of the PDF
 * file at the time it was originally created", and both strings are that one for
 * a file being first written. The second is uniqueness, and the clause
 * recommends rather than requires a way of reaching it:
 *
 * > PDF writers should attempt to ensure the uniqueness of file identifiers.
 * > This may be achieved by computing them by means of a message digest
 * > algorithm such as MD5 (described in Internet RFC 1321), using the following
 * > information:
 * >
 * >  • The current time;
 * >  • A string representation of the PDF file's location;
 * >  • The size of the PDF file in bytes.
 *
 * Of those three this layer has the first and neither of the others: a [Sink] is
 * not a location — it may be a socket — and the size is not known until after
 * the trailer that carries the identifier has been written. No digest is
 * computed either; there is no hash in this library, and RFC 1321 is a
 * specification for another day rather than something to reach for a
 * ready-made implementation of.
 *
 * So the identifier is made of what is honestly available: the creation time to
 * the second, how many pages were planned, and the title. Two documents of the
 * same title and length created in the same second collide, which is the limit
 * of the claim and is stated here rather than dressed up as a digest.
 *
 * The result is at least the sixteen bytes Table 15 requires by construction —
 * a formatted date is twenty-two characters — and padded regardless, so that
 * the guarantee is one and not a coincidence of the date format.
 */
internal fun fileIdentifierOf(stamp: PdfDate, metadata: Metadata, pages: Int): ByteArray {
    val text = "${stamp.format()} $pages ${metadata.title ?: ""}"
    return text.padEnd(FILE_IDENTIFIER_MIN_BYTES, ' ').encodeToByteArray()
}

/** "Each PDF file identifier byte-string shall have a minimum length of 16 bytes" (Table 15). */
private const val FILE_IDENTIFIER_MIN_BYTES: Int = 16
