/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.fragment

import io.pdfkt.core.block.ImageFit
import io.pdfkt.core.block.TextAlign
import io.pdfkt.core.font.FontCatalog
import io.pdfkt.core.model.Pt
import io.pdfkt.core.model.Rect
import io.pdfkt.core.plan.Decoration
import io.pdfkt.core.plan.PlacedItem
import io.pdfkt.core.plan.PlacedRun
import io.pdfkt.core.resolve.StyledBlock
import io.pdfkt.core.resolve.StyledBreak
import io.pdfkt.core.resolve.StyledCode
import io.pdfkt.core.resolve.StyledImage
import io.pdfkt.core.resolve.StyledInline
import io.pdfkt.core.resolve.StyledList
import io.pdfkt.core.resolve.StyledListItem
import io.pdfkt.core.resolve.StyledPageBreak
import io.pdfkt.core.resolve.StyledParagraph
import io.pdfkt.core.resolve.StyledQuote
import io.pdfkt.core.resolve.StyledRule
import io.pdfkt.core.resolve.StyledSection
import io.pdfkt.core.resolve.StyledTable
import io.pdfkt.core.resolve.StyledTextRun

// No clause applies: L4 is this library's own design.

/**
 * The per-block half of the fragmenter: it answers how much of one block fits a
 * given width and height, and where every mark of the part that fits goes.
 *
 * The fragmenter owns the vertical cursor and the page sequence; per-block
 * planners answer one question. Dispatch is one exhaustive `when` over
 * block types — the same shape as the L1 serializer, for the same reason: the
 * compiler proves coverage.
 *
 * It knows nothing about pages. Everything it returns is in the coordinates
 * [FitResult] describes — origin at the top left of the space it was given — and
 * the [Fragmenter] above it does the one translation onto the page. That is what
 * lets the same code plan a paragraph on a page, inside a quotation and inside a
 * list item, and what will let a table cell recurse into it without a path of
 * its own.
 *
 * Measuring is planning: there is no
 * "how tall would this be" method here, because the answer is
 * [FitResult.Fits.consumed] of a plan nobody was obliged to draw.
 *
 * @param fonts the document's font catalog — the same one the emitter writes the
 *   fonts from, since measuring a line is what registers a font with the
 *   document.
 */
public class BlockPlanner(private val fonts: FontCatalog) {
    private val breaker: LineBreaker = LineBreaker(fonts)

    /**
     * The table half of this planner, which recurses straight back into it: a
     * cell is planned by [planSequence], the same function a page, a quotation
     * and a list item are planned by.
     */
    private val tables: TablePlanner = TablePlanner(this)

    /**
     * How much of [block] fits [available], planned against a column of [width].
     *
     * @param width the column the block is set in.
     * @param available how much room is left below the vertical cursor.
     * @param atPageTop whether the page is still empty — the one thing a planner
     *   needs to know about the page it is not otherwise told about. Both of
     *   the layout rules need
     *   it: a block that cannot fit an empty page has nowhere left to go (the
     *   progress guard), and a block that would rather move on than be split is
     *   only allowed that answer while the page is partly used (`keepTogether`).
     * @throws UnplaceableBlockException if the block cannot be placed even on an
     *   empty page.
     */
    public fun planBlock(block: StyledBlock, width: Pt, available: Pt, atPageTop: Boolean): FitResult {
        val fit = planContent(block, width, available, atPageTop)
        // **keepTogether** means a block answers `Defers` instead of `Splits`
        // while the current page is partly used; on an empty page the guard
        // above overrides it.
        //
        // It is the block's own answer and so it is given here rather than in
        // the sequence above: a block asked directly answers what it would
        // answer inside a page. The override is [atPageTop] and nothing else —
        // an empty page is the emptiest there is, so a block that will not
        // split there has nowhere left to go and the progress guard, not this
        // flag, decides what happens to it.
        return if (fit is FitResult.Splits && block.keepTogether && !atPageTop) FitResult.Defers else fit
    }

    /** [planBlock] without the keep rules: the exhaustive dispatch itself. */
    private fun planContent(block: StyledBlock, width: Pt, available: Pt, atPageTop: Boolean): FitResult =
        when (block) {
            is StyledParagraph -> planParagraph(block, width, available)
            is StyledCode -> planCode(block, width, available)
            is StyledList -> planList(block, width, available, atPageTop)
            is StyledQuote -> planQuote(block, width, available, atPageTop)
            is StyledRule -> planRule(block, width, available)
            is StyledImage -> planImage(block, width, available, atPageTop)
            is StyledTable -> tables.planTable(block, width, available, atPageTop)

            // A page break asks the fragmenter for a page; it does not ask a planner
            // for room. `planSequence` acts on it before it ever reaches this
            // dispatch, and a caller who asks anyway is told the truth: it takes no
            // space at all.
            is StyledPageBreak -> FitResult.Fits(emptyList(), Pt.ZERO)

            // A section chooses the paper of the pages it is on, and a box being
            // planned is already on a page whose paper was chosen. The document's
            // top level is the one place that can honour it, and the renderer
            // takes it from there before any planner sees it.
            is StyledSection -> throw IllegalArgumentException(
                "a section is a run of pages on paper of its own, and it belongs at the top level of " +
                    "a document: one inside a list item, a quotation, a table cell or another section " +
                    "would be planned into a box on a page whose size is already decided",
            )
        }

    /**
     * How much of [blocks], one after another, fits [available] — the vertical
     * cursor, in the small.
     *
     * This is what a page is planned by, and also what a quotation, a list item
     * and (later) a table cell are planned by: the difference between a page and
     * a quotation is where the result is translated to, not how the blocks inside
     * it are stacked.
     *
     * The three answers mean for a sequence what they mean for a block:
     * everything was placed, part of it was and [FitResult.Splits.tail] is the
     * rest of the sequence, or nothing was and the whole of it moves on.
     *
     * Two rules of the stacking, both of them decisions the sketch left open:
     *
     * - **Spacing collapses.** The gap between two blocks is the larger of the
     *   first one's `spacingAfter` and the second one's `spacingBefore`, not
     *   their sum: a theme that sets six points after a paragraph and a heading
     *   that asks for twelve before it should give twelve, not eighteen.
     * - **Spacing at the top of a page is dropped**, and so is the trailing
     *   `spacingAfter` of the last block. Space at the edge of a page is what a
     *   margin is, and a paragraph's first line must not sit six points lower on
     *   page two than it does on page one.
     *
     * This is also where `keepWithNext` is read, because it is a rule about a
     * *sequence* and not about any one block: see [breaksBefore].
     *
     * @throws UnplaceableBlockException per the progress guard, if the first
     *   block of an empty page cannot be placed there.
     */
    public fun planSequence(blocks: List<StyledBlock>, width: Pt, available: Pt, atPageTop: Boolean): FitResult =
        planStack(blocks, width, available, atPageTop, grouping = true)

    /**
     * [planSequence], with the `keepWithNext` grouping pass switchable.
     *
     * The switch is what stops the measurement from measuring itself:
     * [breaksBefore] answers how tall a group is by planning that group, and a
     * probe that grouped again would ask the same question of the same blocks
     * for ever. Off, the pass costs nothing anyway — the probe is planned
     * against a container it either fits whole or does not fit at all.
     */
    private fun planStack(
        blocks: List<StyledBlock>,
        width: Pt,
        available: Pt,
        atPageTop: Boolean,
        grouping: Boolean,
    ): FitResult {
        val items = mutableListOf<PlacedItem>()
        var used = Pt.ZERO
        var placed = false
        var spacingAfter = Pt.ZERO

        for ((index, block) in blocks.withIndex()) {
            val atTop = atPageTop && !placed
            if (block is StyledPageBreak) {
                // A break at the top of an empty page asks for a page that is
                // already there. Skipping it is what keeps an album-style
                // document — every photo followed by a forced break — from
                // ending in a blank page.
                if (!placed) continue
                val rest = blocks.subList(index + 1, blocks.size)
                return if (rest.isEmpty()) FitResult.Fits(items, used) else FitResult.Splits(items, used, rest.toList())
            }

            val gap = if (placed) maxOf(spacingAfter, block.spacingBefore) else Pt.ZERO
            val room = available - used - gap
            if (grouping && !atTop && breaksBefore(blocks, index, width, available, room)) {
                val rest = blocks.subList(index, blocks.size).toList()
                return if (placed) FitResult.Splits(items, used, rest) else FitResult.Defers
            }
            val fit = if (room <= Pt.ZERO) FitResult.Defers else planBlock(block, width, room, atTop)
            when (fit) {
                is FitResult.Fits -> {
                    items += fit.plan.map { item -> item.translated(Pt.ZERO, -(used + gap)) }
                    used += gap + fit.consumed
                    placed = placed || fit.plan.isNotEmpty() || fit.consumed > Pt.ZERO
                    spacingAfter = block.spacingAfter
                }

                is FitResult.Splits -> {
                    if (atTop && fit.consumed <= Pt.ZERO) {
                        throw UnplaceableBlockException.splitWithoutProgress(block, width, available)
                    }
                    items += fit.head.map { item -> item.translated(Pt.ZERO, -(used + gap)) }
                    used += gap + fit.consumed
                    return FitResult.Splits(items, used, fit.tail + blocks.subList(index + 1, blocks.size))
                }

                FitResult.Defers -> {
                    if (atTop) throw UnplaceableBlockException.deferredOnEmptyPage(block, width, available)
                    val rest = blocks.subList(index, blocks.size).toList()
                    return if (placed) FitResult.Splits(items, used, rest) else FitResult.Defers
                }
            }
        }
        return FitResult.Fits(items, used)
    }

    // ---------------------------------------------------------- keepWithNext

    /**
     * Whether the blocks chained to `blocks[index]` by `keepWithNext` have to
     * start further down — on the next page, as far as this container is
     * concerned.
     *
     * **keepWithNext** is a grouping pass *before* planning: consecutive
     * blocks chained by the flag form a group; if the group's measured height
     * fits the remaining space, plan normally; if not and it fits a fresh
     * page, break first. Measuring reuses `planBlock` against a probe height
     * — measurement is planning.
     *
     * The probe height is [available] — the height of this container with
     * nothing in it, which for a page is the whole content area. That is not an
     * arbitrary large number chosen to stand in for infinity, and it answers
     * both halves of the rule in one planning pass: the probe comes back
     * [FitResult.Fits] exactly when the group fits a fresh container, and its
     * `consumed` is then the measured height the remaining [room] is compared
     * against. A group that does not fit even a fresh container answers
     * [FitResult.Splits] or [FitResult.Defers], and breaking before it would
     * move it to a page it does not fit either — so it is planned where it is,
     * and splits there.
     *
     * A group of one is not a group: a `keepWithNext` on the last block of a
     * document, or before a forced page break, asks to be kept with nothing.
     */
    private fun breaksBefore(blocks: List<StyledBlock>, index: Int, width: Pt, available: Pt, room: Pt): Boolean {
        if (!blocks[index].keepWithNext) return false
        val group = chainAt(blocks, index)
        if (group.size < 2) return false
        val probe = planStack(group, width, available, atPageTop = true, grouping = false)
        return probe is FitResult.Fits && probe.consumed > room + TOLERANCE
    }

    /**
     * `blocks[index]` and everything chained to it by `keepWithNext`.
     *
     * The chain stops at the first block that does not carry the flag — that
     * block is in the group, since it is what the one before it asked to be kept
     * with — and at a forced page break, which is a caller who asked for both
     * and gets the one that is a request about pages.
     */
    private fun chainAt(blocks: List<StyledBlock>, index: Int): List<StyledBlock> {
        var last = index
        while (last + 1 < blocks.size && blocks[last].keepWithNext && blocks[last + 1] !is StyledPageBreak) last++
        return blocks.subList(index, last + 1).toList()
    }

    // ------------------------------------------------------------ paragraphs

    /**
     * A paragraph: broken into lines once, and then as many of those lines as fit.
     *
     * The break happens against the column the paragraph's own indents leave, and
     * it happens **once** even when the paragraph spans three pages — a second
     * call would be a second answer to a question already answered, and the
     * lines of page two would be free to disagree with the lines of page one.
     */
    private fun planParagraph(paragraph: StyledParagraph, width: Pt, available: Pt): FitResult {
        val column = width - paragraph.indentLeft - paragraph.indentRight
        if (column <= Pt.ZERO) throw UnplaceableBlockException.narrowerThanItsIndents(paragraph, width, available)
        val lines = breaker.breakIntoLines(
            content = paragraph.content,
            width = column,
            lineHeight = paragraph.lineHeight,
            firstLineIndent = paragraph.firstLineIndent,
        )
        return fitLines(lines, paragraph.indentLeft, column, paragraph.align, available) { rest ->
            listOf(
                paragraph.copy(
                    content = inlineOf(rest),
                    firstLineIndent = Pt.ZERO,
                    spacingBefore = Pt.ZERO,
                ),
            )
        }
    }

    /**
     * Pre-formatted text: the caller's own lines, each of them broken again only
     * if it is too wide for the column, on a panel of its background colour.
     *
     * The caller's line endings become hard breaks, which is exactly what they
     * are, and the line breaker then does the rest — so a code block that is too
     * wide wraps rather than running off the page, and no character of it is
     * lost.
     */
    private fun planCode(code: StyledCode, width: Pt, available: Pt): FitResult {
        val padding = code.padding
        val column = width - padding * 2.0f
        val room = available - padding * 2.0f
        if (column <= Pt.ZERO) throw UnplaceableBlockException.narrowerThanItsIndents(code, width, available)
        if (room <= Pt.ZERO) return FitResult.Defers
        val lines = breaker.breakIntoLines(inlineOfCode(code), column, code.lineHeight)
        val fit = fitLines(lines, padding, column, TextAlign.Left, room) { rest ->
            listOf(code.copy(lines = rest.map { line -> codeRunOf(line, code) }, spacingBefore = Pt.ZERO))
        }
        return when (fit) {
            is FitResult.Fits ->
                FitResult.Fits(panelled(code, fit.plan, fit.consumed, width), panelHeight(code, fit.consumed))

            is FitResult.Splits -> FitResult.Splits(
                head = panelled(code, fit.head, fit.consumed, width),
                consumed = panelHeight(code, fit.consumed),
                tail = fit.tail,
            )

            FitResult.Defers -> FitResult.Defers
        }
    }

    /** How tall the panel of a code block whose text took [consumed] is: the text, and the padding twice. */
    private fun panelHeight(code: StyledCode, consumed: Pt): Pt = consumed + code.padding * 2.0f

    /** The text of a code block moved down by its padding, on the panel it is drawn on. */
    private fun panelled(code: StyledCode, text: List<PlacedItem>, consumed: Pt, width: Pt): List<PlacedItem> {
        val height = panelHeight(code, consumed)
        val background = code.background
        val panel =
            if (background == null || height <= Pt.ZERO) {
                emptyList()
            } else {
                listOf(PlacedItem.Rect(Rect(Pt.ZERO, -height, width, height), fill = background))
            }
        return panel + text.map { item -> item.translated(Pt.ZERO, -code.padding) }
    }

    // ----------------------------------------------------------- other blocks

    /**
     * A list: every item's content planned at the item's own indent, with the
     * marker set flush at the list's left edge, on the baseline of the item's
     * first line.
     *
     * Nothing here counts levels. A nested list is a block of an item and is
     * indented again by its own value, which is what makes the indentation
     * compound.
     *
     * **Two items are stacked by the rule two blocks are.** An item is a stack
     * of blocks, so the gap between the last block of one item and the first
     * block of the next collapses to the larger of the two spacings, exactly as
     * in [planStack]. Without it the items sit flush: what a block consumes is
     * measured to the ink of its last line and not to the leading below it — see
     * [fitLines] — so two flush items put the descenders of one line and the
     * ascenders of the next in the same row of the page, which is what a
     * rendered page of the mixed-document gate shows as one band of ink where
     * there should be two.
     */
    private fun planList(list: StyledList, width: Pt, available: Pt, atPageTop: Boolean): FitResult {
        val items = mutableListOf<PlacedItem>()
        var used = Pt.ZERO
        var placed = false
        var spacingAfter = Pt.ZERO

        for ((index, item) in list.items.withIndex()) {
            val column = width - item.indent
            if (column <= Pt.ZERO) throw UnplaceableBlockException.narrowerThanItsIndents(list, width, available)
            val atTop = atPageTop && !placed
            val gap = if (placed) maxOf(spacingAfter, item.spacingBefore) else Pt.ZERO
            val room = available - used - gap
            val fit = if (room <= Pt.ZERO) FitResult.Defers else planSequence(item.content, column, room, atTop)
            when (fit) {
                is FitResult.Fits -> {
                    items += placedItem(item, fit.plan, used + gap)
                    used += gap + fit.consumed
                    placed = placed || fit.plan.isNotEmpty() || fit.consumed > Pt.ZERO
                    spacingAfter = item.spacingAfter
                }

                is FitResult.Splits -> {
                    if (atTop && fit.consumed <= Pt.ZERO) {
                        throw UnplaceableBlockException.splitWithoutProgress(list, width, available)
                    }
                    items += placedItem(item, fit.head, used + gap)
                    used += gap + fit.consumed
                    val continued = StyledListItem(marker = null, indent = item.indent, content = fit.tail)
                    val rest = listOf(continued) + list.items.subList(index + 1, list.items.size)
                    return FitResult.Splits(items, used, listOf(list.copy(items = rest, spacingBefore = Pt.ZERO)))
                }

                FitResult.Defers -> {
                    if (atTop) throw UnplaceableBlockException.deferredOnEmptyPage(list, width, available)
                    val rest = list.items.subList(index, list.items.size).toList()
                    if (!placed) return FitResult.Defers
                    return FitResult.Splits(
                        items,
                        used,
                        listOf(list.copy(items = rest, spacingBefore = Pt.ZERO)),
                    )
                }
            }
        }
        return FitResult.Fits(items, used)
    }

    /**
     * The space an item asks for above it: what its own first block asks for.
     *
     * An item has no spacing of its own — [StyledListItem] carries a marker, an
     * indent and blocks — so the spacing of the blocks at its edges is the
     * spacing of the item, in the same way that a quotation's is.
     */
    private val StyledListItem.spacingBefore: Pt get() = content.firstOrNull()?.spacingBefore ?: Pt.ZERO

    /** The space an item leaves below it: what its own last block leaves. */
    private val StyledListItem.spacingAfter: Pt get() = content.lastOrNull()?.spacingAfter ?: Pt.ZERO

    /**
     * One item's content, moved in by its indent, with its marker beside the
     * first line of it.
     *
     * The marker is measured straight from the font rather than broken into
     * lines: it is one short run by construction — the resolve stage made it out
     * of the marker style and the item's number — and what is wanted of it is not
     * a line but a position beside somebody else's.
     *
     * @param offset how far down the item starts within the list.
     */
    private fun placedItem(item: StyledListItem, content: List<PlacedItem>, offset: Pt): List<PlacedItem> {
        val moved = content.map { placed -> placed.translated(item.indent, -offset) }
        val marker = item.marker ?: return moved
        val metrics = fonts.metricsOf(marker.font)
        val width = Pt(metrics.widthOf(marker.text, marker.size.value))
        val baseline =
            moved.filterIsInstance<PlacedItem.Text>().firstOrNull()?.y
                ?: (-offset - Pt(metrics.ascent(marker.size.value)))
        val run = PlacedRun(
            text = marker.text,
            font = marker.font,
            size = marker.size,
            color = marker.color,
            xOffset = Pt.ZERO,
            width = width,
        )
        return listOf(PlacedItem.Text(Pt.ZERO, baseline, listOf(run))) + moved
    }

    /** A quotation: its children set in from the left, with the bar beside exactly what was placed. */
    private fun planQuote(quote: StyledQuote, width: Pt, available: Pt, atPageTop: Boolean): FitResult {
        val column = width - quote.indentLeft
        if (column <= Pt.ZERO) throw UnplaceableBlockException.narrowerThanItsIndents(quote, width, available)
        return when (val fit = planSequence(quote.children, column, available, atPageTop)) {
            is FitResult.Fits -> FitResult.Fits(barred(quote, fit.plan, fit.consumed), fit.consumed)
            is FitResult.Splits -> FitResult.Splits(
                barred(quote, fit.head, fit.consumed),
                fit.consumed,
                listOf(quote.copy(children = fit.tail, spacingBefore = Pt.ZERO)),
            )

            FitResult.Defers -> FitResult.Defers
        }
    }

    /**
     * The children of a quotation moved in, behind the bar that marks them.
     *
     * The bar is as tall as what was placed and no taller: half a quotation at
     * the foot of a page is marked down to where its text stops, and the rest of
     * the bar is drawn on the next page beside the rest of the text.
     */
    private fun barred(quote: StyledQuote, content: List<PlacedItem>, height: Pt): List<PlacedItem> {
        val moved = content.map { item -> item.translated(quote.indentLeft, Pt.ZERO) }
        if (quote.barWidth <= Pt.ZERO || height <= Pt.ZERO) return moved
        val bar = PlacedItem.Rect(Rect(Pt.ZERO, -height, quote.barWidth, height), fill = quote.barColor)
        return listOf(bar) + moved
    }

    /** A rule across the column: it fits or it does not, and it never splits. */
    private fun planRule(rule: StyledRule, width: Pt, available: Pt): FitResult {
        if (rule.thickness > available) return FitResult.Defers
        val box = Rect(Pt.ZERO, -rule.thickness, width, rule.thickness)
        return FitResult.Fits(listOf(PlacedItem.Rect(box, fill = rule.color)), rule.thickness)
    }

    /**
     * An image, fitted to the column and to what is left of the page.
     *
     * The fitting arithmetic lives here, in the engine, not in any caller. An
     * image is not cut across a page break — half a photograph is not half the
     * information — so the answers are only [FitResult.Fits] and
     * [FitResult.Defers], and the guard's "it scales or errors" is settled here:
     * on an empty page an image left to [ImageFit.Contain] is scaled down to the
     * page, and one whose height the caller fixed is refused rather than
     * silently resized.
     *
     * A quarter turn is fitted by what it *looks* like: the box is chosen so that
     * the picture as the reader sees it, after the turn about the centre of the
     * box, is what fills the column: the planner that rotates a photo picks
     * the box it wants *after* the turn, which is
     * where an EXIF orientation lands.
     */
    private fun planImage(image: StyledImage, width: Pt, available: Pt, atPageTop: Boolean): FitResult {
        val turned = isQuarterTurn(image.rotation)
        val naturalAcross = Pt((if (turned) image.image.height else image.image.width).toFloat())
        val naturalDown = Pt((if (turned) image.image.width else image.image.height).toFloat())
        val boxAcross = image.width ?: width
        val boxDown = image.height ?: naturalDown * (boxAcross / naturalAcross)
        var across = boxAcross
        var down = boxDown
        if (image.fit == ImageFit.Contain) {
            val scale = minOf(boxAcross / naturalAcross, boxDown / naturalDown)
            across = naturalAcross * scale
            down = naturalDown * scale
        }
        if (down > available + TOLERANCE) {
            if (!atPageTop) return FitResult.Defers
            if (image.fit != ImageFit.Contain || image.height != null) {
                throw UnplaceableBlockException.oversizedImage(image, width, available, down)
            }
            val scale = available / down
            across *= scale
            down = available
        }
        // The box is what is drawn; `across` and `down` are what is seen. For a
        // quarter turn they are each other's opposite, and the box is centred on
        // the same point the footprint is, because that is the point it turns
        // about.
        val drawnWidth = if (turned) down else across
        val drawnHeight = if (turned) across else down
        val centreX = width / 2.0f
        val centreY = -down / 2.0f
        val box = Rect(centreX - drawnWidth / 2.0f, centreY - drawnHeight / 2.0f, drawnWidth, drawnHeight)
        return FitResult.Fits(listOf(PlacedItem.Image(box, image.image, image.rotation)), down)
    }

    // ------------------------------------------------------------- the lines

    /**
     * As many of [lines] as fit [available], laid out from the top of the block.
     *
     * A line is taken while its **ink** fits — the baseline plus the descent of
     * its deepest run — and not while its baseline-to-baseline height does. The
     * two differ by whatever the theme opened the leading up by, and that
     * difference is the last line of a page: measuring by the leading would leave
     * a strip of empty page below every last line and move a line to the next
     * page that the page had room for.
     *
     * @param x how far in the lines start, before their own alignment.
     * @param column the width they were broken against, which is what an
     *   alignment measures against.
     * @param tail what the lines that did not fit are as blocks — supplied by the
     *   caller, because a paragraph's leftover lines are a paragraph and a code
     *   block's are a code block.
     */
    private fun fitLines(
        lines: List<TextLine>,
        x: Pt,
        column: Pt,
        align: TextAlign,
        available: Pt,
        tail: (List<TextLine>) -> List<StyledBlock>,
    ): FitResult {
        if (lines.isEmpty()) return FitResult.Fits(emptyList(), Pt.ZERO)
        var top = Pt.ZERO
        var fitting = 0
        for (line in lines) {
            if (top + line.ascent - line.descent > available + TOLERANCE) break
            top += line.height
            fitting++
        }
        if (fitting == 0) return FitResult.Defers
        val placement = layOut(lines.take(fitting), x, column, align)
        return if (fitting == lines.size) {
            FitResult.Fits(placement.items, placement.consumed)
        } else {
            FitResult.Splits(placement.items, placement.consumed, tail(lines.drop(fitting)))
        }
    }

    /** Every line of [lines] placed down from the top of the block, and how far they reach. */
    private fun layOut(lines: List<TextLine>, x: Pt, column: Pt, align: TextAlign): Placement {
        val items = mutableListOf<PlacedItem>()
        var top = Pt.ZERO
        var bottom = Pt.ZERO
        for (line in lines) {
            val shift = when (align) {
                // Justified setting is not implemented, so a justified paragraph is
                // set flush left — which is what it would look like anyway but
                // for the stretching of its spaces.
                TextAlign.Left, TextAlign.Justify -> Pt.ZERO
                TextAlign.Center -> (column - line.width) / 2.0f
                TextAlign.Right -> column - line.width
            }
            val left = x + shift
            val baseline = -(top + line.ascent)
            // A line with no runs is a blank line: it takes its height and draws
            // nothing, and a text object with nothing in it would be an operator
            // pair in the content stream saying so at greater length.
            if (line.runs.isNotEmpty()) items += PlacedItem.Text(left, baseline, line.runs)
            for (span in line.links) {
                val box = Rect(left + span.xOffset, baseline + line.descent, span.width, line.ascent - line.descent)
                items += PlacedItem.Link(box, span.target)
            }
            bottom = top + line.ascent - line.descent
            top += line.height
        }
        return Placement(items, bottom)
    }

    private class Placement(val items: List<PlacedItem>, val consumed: Pt)

    // --------------------------------------------------------------- the tail

    /**
     * Lines that did not fit, as inline content again — what a paragraph's tail
     * is made of.
     *
     * The lines are already broken, and they are separated by hard breaks rather
     * than left to be broken again: a hard break is where the line ended, so the
     * paragraph that continues on the next page has exactly the lines this one
     * measured. Breaking it again against the same width would give the same
     * lines in the ordinary case and *different* ones where a soft hyphen has
     * already become ink — the mark said where a break may go, and once the break
     * is taken the hyphen is a character like any other.
     *
     * Nothing is lost across the split: every run of every leftover line comes
     * back, with the font, size, colour, decorations and link target it was
     * placed with. The link target is read back from the spans of the line, which
     * is where the breaker put it — a run is inside a span when it lies within
     * it, and adjacent runs of one link share one span.
     */
    private fun inlineOf(lines: List<TextLine>): List<StyledInline> {
        val content = mutableListOf<StyledInline>()
        for ((index, line) in lines.withIndex()) {
            if (index > 0) content += StyledBreak(hard = true)
            for (run in line.runs) content += styledRunOf(run, line)
        }
        return content
    }

    private fun styledRunOf(run: PlacedRun, line: TextLine): StyledTextRun = StyledTextRun(
        text = run.text,
        font = run.font,
        size = run.size,
        color = run.color,
        underline = run.decorations.any { decoration -> decoration is Decoration.Underline },
        strike = run.decorations.any { decoration -> decoration is Decoration.Strike },
        link = line.links.firstOrNull { span -> span.covers(run) }?.target,
    )

    /** One placed line of a code block, as the single styled run a code line is. */
    private fun codeRunOf(line: TextLine, code: StyledCode): StyledTextRun {
        val first = line.runs.firstOrNull() ?: return code.lines.first().copy(text = "")
        return styledRunOf(first, line).copy(text = line.runs.joinToString(separator = "") { run -> run.text })
    }

    /** A code block's own lines as inline content: its line endings are hard breaks, because they are. */
    private fun inlineOfCode(code: StyledCode): List<StyledInline> {
        val content = mutableListOf<StyledInline>()
        for ((index, line) in code.lines.withIndex()) {
            if (index > 0) content += StyledBreak(hard = true)
            content += line
        }
        return content
    }

    internal companion object {
        /**
         * How much a length may exceed the room there is and still be taken.
         *
         * A thousandth of a point is a quarter of a micrometre: nothing a reader
         * or a printer can show. What it is here for is the arithmetic — a height
         * summed as `a + b + c` and a room computed as `total - a - b` are the
         * same number only up to the last bit of a `Float`, and without the
         * slack the last line of a page would occasionally move to the next one
         * because of it.
         */
        val TOLERANCE: Pt = Pt(1.0e-3f)

        /** Whether [degrees] is a quarter turn, which is what an EXIF orientation amounts to. */
        fun isQuarterTurn(degrees: Float): Boolean {
            val turn = ((degrees % 180.0f) + 180.0f) % 180.0f
            return turn > 90.0f - QUARTER_TURN_TOLERANCE && turn < 90.0f + QUARTER_TURN_TOLERANCE
        }

        const val QUARTER_TURN_TOLERANCE: Float = 0.5f

        /** Whether [run] lies within this span, which is how a placed run finds its link again. */
        fun LinkSpan.covers(run: PlacedRun): Boolean =
            run.xOffset >= xOffset - TOLERANCE && run.xOffset + run.width <= xOffset + width + TOLERANCE
    }
}

/**
 * The same item, moved by ([dx], [dy]).
 *
 * The one operation the fragmenter needs on a plan, and the reason a planner can
 * work in the coordinates of the space it was given: a page, a quotation and a
 * list item all place their content by planning it at the origin and moving it
 * once.
 */
internal fun PlacedItem.translated(dx: Pt, dy: Pt): PlacedItem = when (this) {
    is PlacedItem.Text -> copy(x = x + dx, y = y + dy)
    is PlacedItem.Rect -> copy(box = box.translated(dx, dy))
    is PlacedItem.Image -> copy(box = box.translated(dx, dy))
    is PlacedItem.Link -> copy(box = box.translated(dx, dy))
}
