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
import io.pdfkt.core.block.ImageBlock
import io.pdfkt.core.block.ImageFit
import io.pdfkt.core.block.InlineRun
import io.pdfkt.core.block.PageBreakBlock
import io.pdfkt.core.block.ParagraphBlock
import io.pdfkt.core.block.SectionBlock
import io.pdfkt.core.block.TextAlign
import io.pdfkt.core.block.TextRun
import io.pdfkt.core.block.VerticalAlign
import io.pdfkt.core.doc.BandPages
import io.pdfkt.core.doc.BandSpec
import io.pdfkt.core.doc.Clock
import io.pdfkt.core.doc.Metadata
import io.pdfkt.core.doc.PageNumbering
import io.pdfkt.core.image.ImageRef
import io.pdfkt.core.image.imageFromBytes
import io.pdfkt.core.image.imageFromFile
import io.pdfkt.core.model.Pt
import io.pdfkt.core.model.Rgb
import io.pdfkt.core.style.Theme
import kotlinx.io.files.Path

// No clause applies: the DSL is this library's own design. Everything below the seam — pagination,
// fonts, content streams, the file
// itself — belongs to :core.

/**
 * DSL marker for pdfkt's builders — it stops a nested block from reaching the
 * scope around it by accident (a paragraph inside a list item, a cell inside a
 * row).
 *
 * It is named for the library and not for a kind of document, because there is
 * one kind now: `document { }` is the entry point and every `<Noun>Scope` under
 * it carries this annotation.
 */
@DslMarker
public annotation class PdfktDsl

/**
 * A document being written: page setup, a theme, and the blocks in the order
 * they were written.
 *
 * This is the whole of the DSL. What it builds is a [Document] — the blocks and
 * the page they go on, frozen — which [document] hands back and
 * [Document.toDocumentSpec] gives the engine as the one thing it renders;
 * nothing here knows what a PDF looks like, and nothing here measures, fits or
 * breaks anything — those live in L4, where they are tested against page plans
 * instead of against a rendered file.
 *
 * The scope exists only for the duration of the `document { }` block: it is
 * mutable so that the block may say `pageSize = ...` and write a paragraph, and
 * what comes out the other side is not.
 *
 * The block builders come from [BlockSink], so the document, a quotation, a list
 * item and a table cell take exactly the same set.
 */
@PdfktDsl
public class DocumentScope internal constructor() : BlockSink {
    private val blocks = mutableListOf<Block>()

    /** The band at the top of the page, as [header] last described it. */
    private var head: BandSpec? = null

    /** The band at the foot of the page, as [footer] last described it. */
    private var foot: BandSpec? = null

    /**
     * The pictures this document draws, in the order they first appeared, each
     * beside the array or the path it was read from — see [imageRef].
     */
    private val images = mutableListOf<Pair<Any, ImageRef>>()

    /** The paper. */
    public var pageSize: PageSize = PageSize.A4

    /** What is kept clear around the text. */
    public var margins: Margins = Margins.Fixed()

    /**
     * How the document is set — fonts, sizes, spacing and colours every block
     * falls back to.
     *
     * A block that states its own font, size or colour overrides the theme for
     * itself; everything else reads it at render time, so setting the theme after
     * the blocks it applies to is the same as setting it before them.
     */
    public var theme: Theme = Theme.Default

    /** What the document says about itself — the entries of §14.3.3 a caller supplies. */
    public var metadata: Metadata = Metadata()

    /**
     * Where the document's dates come from. There is no default, and that is the
     * policy rather than an omission: reading the time is a platform capability, this library has
     * none in common code, and a defaulted date would be silently wrong in
     * every document.
     *
     * ```kotlin
     * clock = Clock { PdfDate.ofEpochSeconds(System.currentTimeMillis() / 1000, offsetMinutes = 60) }
     * ```
     */
    public var clock: Clock? = null

    /**
     * Append an already-built block — the entry point a converter uses, e.g. a
     * CommonMark AST walked into blocks.
     */
    override fun add(block: Block) {
        blocks.add(block)
    }

    /**
     * Append a fenced code block. The name the containers use is [codeBlock];
     * this is the document's own spelling of it, kept because the document is not
     * an [InlineRunSink] and so has no inline `code` to be confused with.
     */
    public fun code(
        text: String,
        backgroundColor: Rgb? = null,
        fontSize: Pt? = null,
        padding: Pt = DEFAULT_CODE_PADDING,
    ) {
        codeBlock(text = text, backgroundColor = backgroundColor, fontSize = fontSize, padding = padding)
    }

    /**
     * Set the running head: one line of [text] at the top of the pages [pages]
     * selects, inside the top margin rather than instead of it.
     *
     * ```kotlin
     * header("Отчёт за квартал")
     * header("Продолжение", pages = BandPages.ExceptFirst)
     * footer("pdfkt")
     * ```
     *
     * The strip [height] is reserved on every page whether or not a line is drawn
     * there, which is what makes the pagination independent of the colophon
     * — a cover page whose head is blank
     * is a page whose text begins where every other page's text begins. Text that
     * does not fit the strip it was given stops the document at the seam, before
     * a byte is written, rather than spilling into the first paragraph.
     *
     * The line is set in the theme, like any other paragraph: a document in an
     * alphabet the standard 14 fonts do not cover needs a [theme] whose
     * `bodyFamily` covers it here too.
     *
     * Calling any of the [header] overloads a second time replaces the band;
     * there is one head per document, as there is one [pageSize].
     *
     * @param text the line, the same on every page it is drawn on. One that reads
     *   the page it is drawn on — "Page 3 of 12", a running title that follows the
     *   sections — is the overload taking a function of the numbering.
     * @param align where the line sits across the strip.
     * @param height the strip reserved for it, taken off the content area of
     *   every page.
     * @param pages which pages carry it: all of them, the first only — a cover —
     *   or all but the first.
     */
    public fun header(
        text: String,
        align: TextAlign = TextAlign.Left,
        height: Pt = DEFAULT_BAND_HEIGHT,
        pages: BandPages = BandPages.All,
    ) {
        head = colophon(text, align, height, pages)
    }

    /**
     * Set a running head that says one thing on the first page and another on the
     * rest — a title page naming the document, and a running title after it.
     *
     * ```kotlin
     * header(firstPage = "Отчёт за IV квартал 2026", otherPages = "Отчёт за квартал")
     * ```
     *
     * It is one band and not two, because a page carries one head: which of the
     * two lines is drawn is decided per page, when the band is planned, and both
     * are set in the same strip and to the same side. Two lines that need
     * different strips are two documents' worth of head, and past what a colophon
     * is.
     *
     * @param firstPage the line on page 1.
     * @param otherPages the line on every page after it.
     */
    public fun header(
        firstPage: String,
        otherPages: String,
        align: TextAlign = TextAlign.Left,
        height: Pt = DEFAULT_BAND_HEIGHT,
    ) {
        head = colophon(firstPage, otherPages, align, height)
    }

    /**
     * Set the running head from the blocks written into it — the block form, and
     * the one a colophon that is more than a plain line is written in.
     *
     * ```kotlin
     * header { paragraph { text("Q1 Report") { bold = true } } }
     * header(pages = BandPages.ExceptFirst) { paragraph("Страница ${numbering.page} из ${numbering.pages}") }
     * ```
     *
     * The receiver is a [ColophonScope]: every block builder the document itself
     * has, plus the [ColophonScope.numbering] of the page being written and the
     * [ColophonScope.pageOfPages] line built out of it. The block is run once per
     * page the band is drawn on, which is what lets a colophon read that page —
     * and the count is known before any band is planned, because the reserved
     * strip's size does not depend on what goes in it (`BandSpec`). So a head may
     * say how many pages the document has without the document being rendered
     * twice.
     *
     * Its content is set flush left unless a paragraph inside it says otherwise,
     * and so is [footer]'s: the left/right split the text-form overloads carry is
     * a convenience for the common case, and a block form has no
     * side of its own to prefer. A foot with a page
     * number on the right says `align = TextAlign.Right` and means it.
     *
     * Everything else is the text form's: one head per document, whichever
     * spelling described it last; the strip reserved on every page whether the
     * band is drawn there or not; content that does not fit the strip stopping the
     * document at the seam rather than spilling into the first paragraph.
     *
     * A block that writes nothing is refused, on the page it writes nothing on:
     * a head is drawn or it is not, and which pages carry one is [pages]. That
     * refusal is also what a caller who writes `header { "one line" }` — a
     * lambda whose string goes nowhere —
     * meets, instead of a band that silently comes out blank.
     *
     * @param height the strip reserved on every page, drawn on or not.
     * @param pages which pages carry it.
     * @param block the colophon, written the way the document is written.
     */
    public fun header(
        height: Pt = DEFAULT_BAND_HEIGHT,
        pages: BandPages = BandPages.All,
        block: ColophonScope.() -> Unit,
    ) {
        headerBand(height, pages) { numbering -> colophonContent(numbering, block) }
    }

    /**
     * Set the running head from the blocks it draws on a given page — the raw
     * form, which is to a band what [add] is to a block.
     *
     * The [header] overloads are the two shapes a colophon is usually written in
     * — a line of text set in the theme, or blocks written the way the document
     * is written. This is what both are built out of, and it is here for the
     * caller who holds blocks already: an application lowering its own header
     * type onto this one uses it, and that is what makes a photo album a
     * document with pictures in it rather than a second engine
     *
     * It is named apart from [header] rather than overloading it, and that is not
     * a preference: a trailing lambda tells two overloads apart only by the type
     * it returns, which Kotlin resolves before the lambda has been typed. This
     * one hands back a list of blocks and the block form hands back nothing, so
     * as overloads of one name neither call could be resolved at all — the same
     * collision that took the `header { "Page ${it.page}" }` overload out of this
     * class.
     *
     * The [content] function is [BandSpec.content] itself: it is called once per
     * page the band is drawn on, with the numbering of that page, and whatever it
     * hands back is planned into the reserved strip. Blocks that do not fit the
     * strip stop the document at the seam, as any other band's do.
     *
     * @param height the strip reserved on every page, drawn on or not.
     * @param pages which pages carry the band.
     * @param content the blocks of the band on a given page.
     */
    public fun headerBand(
        height: Pt = DEFAULT_BAND_HEIGHT,
        pages: BandPages = BandPages.All,
        content: (PageNumbering) -> List<Block>,
    ) {
        head = BandSpec(height, pages, content)
    }

    /**
     * Set the running foot: one line of [text] at the foot of the pages [pages]
     * selects, on the same terms as [header] — and aligned right by default,
     * where a page number or a house name is looked for.
     */
    public fun footer(
        text: String,
        align: TextAlign = TextAlign.Right,
        height: Pt = DEFAULT_BAND_HEIGHT,
        pages: BandPages = BandPages.All,
    ) {
        foot = colophon(text, align, height, pages)
    }

    /**
     * Set a running foot that says one thing on the first page and another on the
     * rest, on the same terms as the [header] that does.
     */
    public fun footer(
        firstPage: String,
        otherPages: String,
        align: TextAlign = TextAlign.Right,
        height: Pt = DEFAULT_BAND_HEIGHT,
    ) {
        foot = colophon(firstPage, otherPages, align, height)
    }

    /**
     * Set the running foot from the blocks written into it — the block form, on
     * the same terms as the [header] that takes one, alignment included, and
     * where "Page 3 of 12" belongs.
     *
     * ```kotlin
     * footer(pages = BandPages.ExceptFirst) { paragraph(pageOfPages(), align = TextAlign.Right) }
     * ```
     *
     * Flush left unless a paragraph says otherwise: the right-hand default of the
     * text-form [footer] does not carry over here, so that a head and a foot
     * written the same way come out the same way.
     */
    public fun footer(
        height: Pt = DEFAULT_BAND_HEIGHT,
        pages: BandPages = BandPages.All,
        block: ColophonScope.() -> Unit,
    ) {
        footerBand(height, pages) { numbering -> colophonContent(numbering, block) }
    }

    /**
     * Set the running foot from the blocks it draws on a given page — the raw
     * form of a foot, on the same terms as [headerBand].
     */
    public fun footerBand(
        height: Pt = DEFAULT_BAND_HEIGHT,
        pages: BandPages = BandPages.All,
        content: (PageNumbering) -> List<Block>,
    ) {
        foot = BandSpec(height, pages, content)
    }

    /**
     * Turn the current page size on its side.
     *
     * There is no orientation enum: a
     * page is a size, and landscape is that size with its sides swapped. Calling
     * it twice turns the page back.
     */
    public fun landscape() {
        val size = pageSize.toSize()
        pageSize = PageSize.Custom(size.height, size.width)
    }

    /**
     * Break the page here: whatever is written next starts a new one.
     *
     * ```kotlin
     * paragraph("The first page.")
     * pageBreak()
     * paragraph("The second.")
     * ```
     *
     * A break at the top of a page that has nothing on it yet is skipped, so a
     * document that ends with one does not end on a blank leaf, and two in a row
     * do not leave an empty page between them.
     *
     * It is a member of the document and not of [BlockSink] on purpose: a break
     * is a statement about the sequence of pages, and a table cell, a list item
     * or a quotation is planned into a box whose height the fragmenter has
     * already decided — a break inside one would be a request the box has no way
     * to honour.
     */
    public fun pageBreak() {
        add(PageBreakBlock)
    }

    /**
     * Put the picture in [bytes] here, in the flow, between the block before it
     * and the block after it.
     *
     * ```kotlin
     * heading(level = 1, "Q4")
     * paragraph("Revenue by month:")
     * image(chartBytes)
     * ```
     *
     * A JPEG and a PNG are both taken, and which of the two arrived is read off
     * the bytes rather than asked of the caller: a PNG datastream begins with the
     * signature of its §5.2 and a JPEG with the SOI marker `0xFFD8` (ITU-T T.81
     * §B.2.1), so a file that says what it is is not made to say it twice. What
     * each becomes in the document is `:core`'s and unchanged by this: the JPEG
     * is embedded as it arrived under `/DCTDecode` (ISO 32000-2:2020 §7.4.8), and
     * the PNG is decoded and written back under `/FlateDecode`, its alpha channel
     * becoming the `/SMask` of Table 87.
     *
     * How large it comes out is the engine's business, not the DSL's: an image is
     * fitted to the column and, if a page has not the room, moved whole to the
     * next one — "an image is not cut across a page break" — and one too tall for
     * a whole page is scaled down to it rather than dropped. Nothing here measures anything.
     *
     * The same array added twice is one image in the file. Two arrays of equal
     * contents are two, deliberately: comparing every picture in a document with
     * every other one is a cost paid on every document to save bytes in the rare
     * one, and a caller who holds the same picture in two arrays can hand over
     * one of them.
     *
     * @param bytes the file, whole and from its first byte. It is not copied and
     *   not decoded twice — the reference built from it is kept for as long as
     *   this document is.
     * @param fit how it fills the space it is given: `Contain` keeps its aspect
     *   ratio, `Stretch` takes the column and the height that was computed for it.
     * @param rotationDegrees a quarter turn about the centre of the box it lands
     *   in, counter-clockwise — where the orientation a camera recorded ends up.
     *   The box is chosen *after* the turn, so a portrait photograph rotated 90°
     *   fills the column as the reader sees it.
     * @throws IllegalArgumentException if [rotationDegrees] is not a quarter
     *   turn, or if [bytes] are neither a JPEG this library embeds nor a PNG it
     *   decodes — a document silently missing a picture is the plainest case of
     *   the loss this library forbids.
     */
    public fun image(bytes: ByteArray, fit: ImageFit = ImageFit.Contain, rotationDegrees: Int = 0) {
        add(imageBlock(fit, rotationDegrees) { imageRef(bytes) })
    }

    /**
     * Put the picture in the file at [path] here, on the terms of the [image]
     * that takes bytes — with the difference that a JPEG is not held: its frame
     * header is read now, for its size, and the file is copied into the PDF when
     * the document is written. This is the form for a document of many large
     * photographs; the files must stay as they are until it has been written.
     *
     * The same path added twice is one image in the file.
     *
     * @throws IllegalArgumentException as the other [image] does, and if there
     *   is no file at [path].
     */
    public fun image(path: Path, fit: ImageFit = ImageFit.Contain, rotationDegrees: Int = 0) {
        add(imageBlock(fit, rotationDegrees) { imageRef(path) })
    }

    /**
     * Set the pages [block] writes on paper of their own: a new page of
     * [pageSize] with [margins], as many more of the same as the content needs,
     * and after the section the document's own paper again.
     *
     * ```kotlin
     * section(PageSize.Custom(595.276.pt, 595.276.pt), verticalAlign = VerticalAlign.Middle) {
     *     paragraph("Лето 2026", align = TextAlign.Center)
     * }
     * for (photo in photos) {
     *     section(PageSize.Custom(photo.width, photo.height), margins = Margins.Fixed(0.pt, 0.pt, 0.pt, 0.pt)) {
     *         image(photo.bytes)
     *     }
     * }
     * ```
     *
     * This is how one document carries pages of different sizes — a square
     * cover, then every photograph on a sheet of its own proportions; a report
     * with one landscape table in it. The [pageSize] and [margins] of the
     * document are not changed by it: they are what the blocks outside every
     * section are set on, and what a section that does not say otherwise takes.
     *
     * A section always starts a page and the blocks after it always start
     * another, so a [pageBreak] next to one asks for nothing more and adds no
     * blank page. A section with nothing in it is one blank page of its paper.
     * The document's header and footer are drawn on its pages too, in strips
     * cut from that page's own size and margins.
     *
     * @param pageSize the paper of the section's pages.
     * @param margins what is kept clear around their content. Zero on all four
     *   sides is a page the content reaches the edges of — a photograph at full
     *   bleed.
     * @param verticalAlign where a page's content sits when it does not fill
     *   the page: [VerticalAlign.Middle] centres a title page top to bottom, and
     *   each page of a section that runs onto several is aligned on its own.
     * @throws IllegalArgumentException when the document is built, if the
     *   margins and bands leave no room on the section's paper.
     */
    public fun section(
        pageSize: PageSize = this.pageSize,
        margins: Margins = this.margins,
        verticalAlign: VerticalAlign = VerticalAlign.Top,
        block: SectionScope.() -> Unit,
    ) {
        val content = SectionScope(::imageRef, ::imageRef).apply(block).build()
        add(
            SectionBlock(
                pageSize = pageSize.toSize(),
                margins = margins.toInsets(),
                verticalAlign = verticalAlign,
                content = content,
            ),
        )
    }

    /**
     * The document's reference to [bytes], built on their first appearance and
     * handed back on every later one.
     *
     * An id names one image within one document (`ImageRef.id`), and the catalog
     * writes one image XObject per id, so this is what makes a logo drawn in six
     * sections six `Do` operators over one stream. The arrays are compared by
     * identity, the way the catalog itself compares samples first — "the usual
     * case is one array handed round".
     */
    private fun imageRef(bytes: ByteArray): ImageRef {
        for ((source, ref) in images) {
            if (source === bytes) return ref
        }
        val ref = imageFromBytes("$IMAGE_ID_PREFIX${images.size + 1}", bytes)
        images += bytes to ref
        return ref
    }

    /** The document's reference to the file at [path], as [imageRef] is to an array — by path. */
    private fun imageRef(path: Path): ImageRef {
        for ((source, ref) in images) {
            if (source == path) return ref
        }
        val ref = imageFromFile("$IMAGE_ID_PREFIX${images.size + 1}", path)
        images += path to ref
        return ref
    }

    /**
     * The document this scope described, as it stood when the `document { }`
     * block returned.
     *
     * The blocks are copied out rather than shared, so the [Document] does not
     * change afterwards even if this scope were somehow written into again — the
     * scope is what mutates and the document is what does not.
     */
    internal fun build(): Document = Document(
        pageSize = pageSize,
        margins = margins,
        theme = theme,
        metadata = metadata,
        clock = clock,
        header = head,
        footer = foot,
        blocks = blocks.toList(),
    )
}

/**
 * One line of text as the band it is drawn in, on the pages [pages] selects.
 *
 * The blocks are built once and handed back for every page, because the text is
 * the same on every page: [BandSpec.content] is a function of the numbering so
 * that a band *may* depend on which page it is on, not so that one which does not
 * has to be rebuilt eleven times.
 */
private fun colophon(text: String, align: TextAlign, height: Pt, pages: BandPages): BandSpec {
    val blocks = colophonBlocks(text, align)
    return BandSpec(height, pages) { blocks }
}

/**
 * The band that says [firstPage] on the first page and [otherPages] on the rest.
 *
 * Its rule is [BandPages.All] and the choice is made in the content function,
 * which is what `BandPages` says a differing first page is: "A band that differs
 * between the first page and the rest is one `BandSpec.content` that reads
 * `PageNumbering.page`, which is why there is no fourth case here." Both lines
 * are built once, for the same reason the constant one is.
 */
private fun colophon(firstPage: String, otherPages: String, align: TextAlign, height: Pt): BandSpec {
    val first = colophonBlocks(firstPage, align)
    val rest = colophonBlocks(otherPages, align)
    return BandSpec(height, BandPages.All) { numbering ->
        if (numbering.page == FIRST_PAGE) first else rest
    }
}

/**
 * One line of a colophon as the blocks of its band.
 *
 * One paragraph, of one run, carrying no spacing of its own — the strip is the
 * room the band was given, and a gap above the line would be a second margin
 * inside the first. Everything else about it is the theme's: a colophon set in
 * the document's own face is what makes it look like part of the document.
 */
private fun colophonBlocks(text: String, align: TextAlign): List<Block> = listOf(
    ParagraphBlock(
        content = listOf(TextRun(text)),
        align = align,
        spacingBefore = Pt.ZERO,
        spacingAfter = Pt.ZERO,
    ),
)

/**
 * The strip a colophon gets when the caller does not say.
 *
 * One value, read by every spelling of a band, so two spellings cannot disagree
 * about how much room a running head takes and print the same header at two
 * heights on the same paper. It is stated in the DSL rather than in `:core`
 * because how much of a page goes to a band is a decision about the page —
 * which belongs to the DSL, not to the engine, whose business
 * is only what fits in the strip once it has one.
 */
public val DEFAULT_BAND_HEIGHT: Pt = 28.pt

/** The page a document's first page is, counted as a reader counts. */
private const val FIRST_PAGE: Int = 1

/**
 * The block of the picture [imageRef] reads, drawn with [fit], turned by
 * [rotationDegrees] — what [DocumentScope.image] and [SectionScope.image] both
 * add. The turn is checked before [imageRef] reads the bytes, so a picture that
 * is refused is not first registered with the document.
 *
 * @throws IllegalArgumentException if [rotationDegrees] is not a quarter turn.
 */
internal fun imageBlock(fit: ImageFit, rotationDegrees: Int, imageRef: () -> ImageRef): ImageBlock {
    require(rotationDegrees in QUARTER_TURNS) {
        "an image is turned by a quarter of a circle at a time — ${QUARTER_TURNS.joinToString(", ")} " +
            "degrees, which is what an orientation is — and this one asks for $rotationDegrees. The " +
            "engine turns an image by any angle, but it fits it to the column as though it had not " +
            "been turned, so the corners of an image turned by anything else reach outside the " +
            "column: say it with add(ImageBlock(imageFromBytes(id, bytes), rotation = " +
            "$rotationDegrees.0f)) and mean it"
    }
    return ImageBlock(image = imageRef(), fit = fit, rotation = rotationDegrees.toFloat())
}

/**
 * What a picture is called within the document, before its number — the
 * `ImageRef.id` the catalog keys one image XObject by.
 */
private const val IMAGE_ID_PREFIX: String = "image-"

/**
 * The turns an image may be given: the orientations a camera records, and the
 * ones the planner fits the column to after turning.
 */
private val QUARTER_TURNS: List<Int> = listOf(0, 90, 180, 270)

/**
 * The inline content of one paragraph or heading.
 *
 * The builders themselves live in [InlineRunSink] — every scope that takes
 * inline content shares them, so `paragraph { bold("x") }` and `cell { bold("x") }`
 * read the same.
 */
@PdfktDsl
public class InlineScope internal constructor() : InlineRunSink {
    private val runs = mutableListOf<InlineRun>()

    override fun add(run: InlineRun) {
        runs.add(run)
    }

    /** The runs collected so far, in the order they were written. */
    public fun runs(): List<InlineRun> = runs.toList()
}
