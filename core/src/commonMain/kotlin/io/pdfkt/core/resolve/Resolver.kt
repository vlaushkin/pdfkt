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
import io.pdfkt.core.block.BlockquoteBlock
import io.pdfkt.core.block.BreakRun
import io.pdfkt.core.block.CodeBlock
import io.pdfkt.core.block.HeadingBlock
import io.pdfkt.core.block.HorizontalRuleBlock
import io.pdfkt.core.block.ImageBlock
import io.pdfkt.core.block.InlineRun
import io.pdfkt.core.block.LinkRun
import io.pdfkt.core.block.ListBlock
import io.pdfkt.core.block.PageBreakBlock
import io.pdfkt.core.block.ParagraphBlock
import io.pdfkt.core.block.SectionBlock
import io.pdfkt.core.block.StyledRun
import io.pdfkt.core.block.TableBlock
import io.pdfkt.core.block.TableCell
import io.pdfkt.core.block.TextAlign
import io.pdfkt.core.block.TextRun
import io.pdfkt.core.font.FontCatalog
import io.pdfkt.core.font.FontRef
import io.pdfkt.core.font.UnencodableCharacterException
import io.pdfkt.core.font.forEachCodePoint
import io.pdfkt.core.model.Pt
import io.pdfkt.core.model.Rgb
import io.pdfkt.core.style.EmojiPolicy
import io.pdfkt.core.style.FontFamily
import io.pdfkt.core.style.Theme
import io.pdfkt.core.text.TextSegment
import io.pdfkt.core.text.UnicodeProperties
import io.pdfkt.core.text.segmentEmoji

// No clause applies: L4 is this library's own design. §9.6.5.1 and Annex D are what
// decide whether a character can be drawn, and that is asked here, of the font.

/**
 * The first stage of the pipeline: blocks and a theme in, styled blocks out
 *
 * > **Resolve** — apply the theme: fonts, sizes, colours, spacing. Produces
 * > styled blocks; no geometry yet.
 *
 * Three things happen here and nothing else does.
 *
 * **The theme is applied.** Every null in the block tree — "take the theme's
 * spacing", "take the theme's colour" — is replaced by the value it stood for.
 * After this stage no styled block refers to a theme, which is what makes the
 * stages below it deterministic: a fragmenter cannot accidentally style
 * anything, because there is nothing left to style with.
 *
 * **Inline runs are split by renderability.** A run of text ends where the next
 * character would be drawn differently — a change of font, size, colour,
 * decoration or link. Text that survives the split unchanged is merged back
 * together, so "how the caller nested their markup" leaves no trace in the
 * output: two spans that look identical *are* one run.
 *
 * **Emoji are dealt with by policy.** Where the text splits into emoji and
 * non-emoji is a Unicode question, answered by
 * [io.pdfkt.core.text.segmentEmoji] per UTS #51, and what happens to the emoji
 * is the theme's [EmojiPolicy]. [EmojiPolicy.Strip] and [EmojiPolicy.Placeholder]
 * are "pure text transforms in resolve — L3 uninvolved": they
 * rewrite the text and no font is asked for a picture.
 * [EmojiPolicy.EmbeddedColorFont] is the one that involves L3, and it involves
 * nothing else here: the emoji stays as it was written and the run it becomes
 * names the colour font instead of the run's own. That is a change of font, so
 * it splits a run exactly as a change of font does, and everything below this
 * stage measures and draws it with the machinery it already had.
 *
 * What does *not* happen here: nothing is measured, no line is broken, no
 * coordinate is computed. That is the next two stages, and keeping them apart is
 * what lets a test assert what a document is set in without producing a page.
 *
 * ## No silent loss
 *
 * Every run of text this stage produces is offered to the font that will draw
 * it, and a character the font cannot draw stops the document here — with the
 * character, its position in the string the caller wrote, and the remedy
 * ([UnencodableCharacterException]). It would be found later anyway, when the
 * line breaker measured it; finding it here means the report can still point at
 * the caller's own text rather than at some fragment of it.
 *
 * @param theme the defaults every block falls back to.
 * @param fonts the document's font catalog. Resolving is what registers a font
 *   with the document, so the fonts a document uses are exactly the fonts its
 *   text ended up in — including the bold face a run of bold text pulled in.
 */
public class Resolver(private val theme: Theme, private val fonts: FontCatalog) {
    /** [blocks] with the theme applied, in order, one styled block each. */
    public fun resolve(blocks: List<Block>): List<StyledBlock> = blocks.map { resolveBlock(it, bodyStyle()) }

    /**
     * One block, with [base] as the inline style its text inherits.
     *
     * The dispatch is one exhaustive `when` over the sealed block hierarchy — the
     * same shape the fragmenter uses, for the same reason: a
     * block type added later cannot be forgotten here, because the compiler will
     * not let it be.
     */
    private fun resolveBlock(block: Block, base: InlineStyle): StyledBlock = when (block) {
        is ParagraphBlock -> StyledParagraph(
            content = resolveInline(block.content, base),
            lineHeight = theme.lineHeight,
            align = block.align,
            indentLeft = block.indentLeft,
            indentRight = block.indentRight,
            firstLineIndent = block.firstLineIndent,
            direction = block.direction,
            spacingBefore = block.spacingBefore ?: theme.paragraphSpacingBefore,
            spacingAfter = block.spacingAfter ?: theme.paragraphSpacingAfter,
            keepWithNext = block.keepWithNext,
            keepTogether = block.keepTogether,
        )

        is HeadingBlock -> StyledParagraph(
            content = resolveInline(block.content, base.asHeading(block.level)),
            lineHeight = theme.lineHeight,
            spacingBefore = block.spacingBefore ?: theme.headingSpacingBefore.getValue(block.level),
            spacingAfter = block.spacingAfter ?: theme.headingSpacingAfter.getValue(block.level),
            keepWithNext = block.keepWithNext,
            keepTogether = block.keepTogether,
        )

        is ListBlock -> StyledList(
            items = block.items.mapIndexed { position, item ->
                StyledListItem(
                    marker = markerRun(block, position, base),
                    indent = block.indentPerLevel,
                    content = item.content.map { resolveBlock(it, base) },
                )
            },
            spacingBefore = block.spacingBefore ?: theme.paragraphSpacingBefore,
            spacingAfter = block.spacingAfter ?: theme.paragraphSpacingAfter,
            keepWithNext = block.keepWithNext,
            keepTogether = block.keepTogether,
        )

        is TableBlock -> StyledTable(
            columns = block.columns,
            rows = block.rows.map { row -> row.map { resolveCell(it, base) } },
            header = block.header?.map { resolveCell(it, base.copy(bold = base.bold || theme.tableHeaderBold)) },
            borders = block.borders,
            cellPadding = block.cellPadding,
            repeatHeader = block.repeatHeader,
            keepHeaderWithFirstRow = block.keepHeaderWithFirstRow,
            spacingBefore = block.spacingBefore ?: theme.paragraphSpacingBefore,
            spacingAfter = block.spacingAfter ?: theme.paragraphSpacingAfter,
            keepWithNext = block.keepWithNext,
            keepTogether = block.keepTogether,
        )

        is CodeBlock -> resolveCode(block, base)

        is BlockquoteBlock -> StyledQuote(
            children = block.children.map { resolveBlock(it, base) },
            barColor = block.barColor ?: theme.quoteBarColor,
            barWidth = block.barWidth,
            indentLeft = block.indentLeft,
            spacingBefore = block.spacingBefore ?: theme.paragraphSpacingBefore,
            spacingAfter = block.spacingAfter ?: theme.paragraphSpacingAfter,
            keepWithNext = block.keepWithNext,
            keepTogether = block.keepTogether,
        )

        is HorizontalRuleBlock -> StyledRule(
            thickness = block.thickness ?: theme.ruleThickness,
            color = block.color ?: theme.ruleColor,
            spacingBefore = block.spacingBefore ?: theme.ruleSpacing,
            spacingAfter = block.spacingAfter ?: theme.ruleSpacing,
            keepWithNext = block.keepWithNext,
            keepTogether = block.keepTogether,
        )

        is ImageBlock -> StyledImage(
            image = block.image,
            fit = block.fit,
            width = block.width,
            height = block.height,
            rotation = block.rotation,
            spacingBefore = block.spacingBefore ?: theme.paragraphSpacingBefore,
            spacingAfter = block.spacingAfter ?: theme.paragraphSpacingAfter,
            keepWithNext = block.keepWithNext,
            keepTogether = block.keepTogether,
        )

        is PageBreakBlock -> StyledPageBreak

        is SectionBlock -> StyledSection(
            pageSize = block.pageSize,
            margins = block.margins,
            verticalAlign = block.verticalAlign,
            content = block.content.map { resolveBlock(it, base) },
            fitHeight = block.fitHeight,
        )
    }

    /**
     * Pre-formatted text, cut into lines at the breaks the caller wrote.
     *
     * The line endings are normalised first — a document assembled from files
     * written on three operating systems should not come out with three
     * different ideas of what a line is — and the offsets used to report an
     * undrawable character are offsets into that normalised text, so they still
     * point at the character the caller can see.
     *
     * A line of a code block is one run of one font ([StyledCode]), which is the
     * one place [EmojiPolicy.EmbeddedColorFont] cannot be applied: drawing the
     * emoji from the colour font and the code from the code font would need two.
     * That is refused, naming the emoji — see [requireNoColorEmoji].
     */
    private fun resolveCode(block: CodeBlock, base: InlineStyle): StyledCode {
        val style = base.copy(
            family = block.font?.let { FontFamily.of(it) } ?: theme.codeFamily,
            size = block.fontSize ?: theme.codeFontSize,
            bold = false,
            italic = false,
        )
        val text = block.text.replace(CARRIAGE_RETURN_LINE_FEED, LINE_FEED).replace(CARRIAGE_RETURN, LINE_FEED)
        requireNoColorEmoji(text)
        val lines = mutableListOf<StyledTextRun>()
        var start = 0
        while (start <= text.length) {
            val end = text.indexOf(LINE_FEED, start).let { if (it < 0) text.length else it }
            val runs = merge(textRuns(text.substring(start, end), style, text, start)).filterIsInstance<StyledTextRun>()
            // Every run of a code line is in the one style the block resolved to,
            // so merging collapses them; taking one of several would lose text.
            check(runs.size <= 1) { "a code line resolved to ${runs.size} runs of different styles: $runs" }
            lines += runs.singleOrNull() ?: emptyRun(style)
            start = end + 1
        }
        return StyledCode(
            lines = lines,
            lineHeight = theme.lineHeight,
            background = block.background ?: theme.codeBackground,
            padding = block.padding,
            spacingBefore = block.spacingBefore ?: theme.paragraphSpacingBefore,
            spacingAfter = block.spacingAfter ?: theme.paragraphSpacingAfter,
            keepWithNext = block.keepWithNext,
            keepTogether = block.keepTogether,
        )
    }

    /**
     * Refuses a code block that carries an emoji while the emoji policy draws
     * emoji from a colour font.
     *
     * The two are incompatible by construction rather than by omission: a
     * [StyledCode] line is a single [StyledTextRun] — pre-formatted text is set
     * in one font, and the fragmenter places each line as one — while this
     * policy is precisely a change of font in the middle of a line. Drawing the
     * emoji in the code font instead would be applying a policy the document did
     * not choose, and dropping it would be the silent loss this library forbids, so
     * the document stops here with the emoji named and the two policies that
     * need no font offered.
     */
    private fun requireNoColorEmoji(text: String) {
        val policy = theme.emojiPolicy
        val drawnFrom = when (policy) {
            is EmojiPolicy.EmbeddedColorFont ->
                "the colour font \"${policy.id}\" " +
                    "(EmojiPolicy.EmbeddedColorFont)"

            is EmojiPolicy.Rasterized ->
                "the rasterized emoji font \"${policy.id}\" " +
                    "(EmojiPolicy.Rasterized)"

            EmojiPolicy.None, EmojiPolicy.Strip, EmojiPolicy.Placeholder -> return
        }
        val emoji = segmentEmoji(text).filterIsInstance<TextSegment.Emoji>().firstOrNull() ?: return
        throw IllegalArgumentException(
            "a code block carries the emoji \"${emoji.text}\" (${placeholderFor(emoji.text)}) at index " +
                "${emoji.index}, and this document draws emoji from $drawnFrom. A line of a code block " +
                "is one run of one font, so this line would have to be set in the code font and in the " +
                "emoji font at once. Nothing was dropped and nothing was substituted: choose " +
                "EmojiPolicy.Strip or EmojiPolicy.Placeholder, which draw emoji with no font at all, " +
                "or take the emoji out of the code block",
        )
    }

    /**
     * One cell, with the theme applied to its blocks and the cell's own
     * alignment passed on to the paragraphs directly in it.
     *
     * Alignment is a style, so it is applied here rather than by the fragmenter,
     * which would otherwise have to carry an alignment down every recursion into
     * a cell. What "directly in it" excludes is a nested list, table or
     * quotation: those are containers with an alignment question of their own,
     * and a cell set flush right is a statement about the cell's text.
     *
     * The default is what a cell that has nothing to say looks like: a
     * [TextAlign.Left] cell changes nothing, so a centred paragraph inside an
     * ordinary cell stays centred.
     */
    private fun resolveCell(cell: TableCell, base: InlineStyle): StyledCell = StyledCell(
        content = cell.content.map { aligned(resolveBlock(it, base), cell.align) },
        align = cell.align,
        verticalAlign = cell.verticalAlign,
        background = cell.background,
    )

    /**
     * The marker beside one item of [list], counting [ListBlock.startNumber] from
     * the first.
     *
     * It is drawn in the surrounding text's font, undecorated: a marker inside a
     * link is still a marker, and underlining it would say something the caller
     * did not.
     */
    private fun markerRun(list: ListBlock, position: Int, base: InlineStyle): StyledTextRun? {
        val text = listMarkerText(list.markerStyle, list.startNumber + position, theme.listMarkerSuffix)
        return checkedRun(text, base.undecorated(), text, 0, verbatim = true)
    }

    /**
     * The inline tree flattened: the nesting is walked, each level inheriting
     * from the one above, and the result is merged so that no split survives
     * that nothing would draw differently.
     */
    private fun resolveInline(runs: List<InlineRun>, base: InlineStyle): List<StyledInline> =
        merge(mutableListOf<StyledInline>().also { appendInline(runs, base, it) })

    private fun appendInline(runs: List<InlineRun>, style: InlineStyle, into: MutableList<StyledInline>) {
        for (run in runs) {
            when (run) {
                is TextRun -> into += textRuns(run.text, style, run.text, 0)
                is StyledRun -> appendInline(run.children, style.with(run), into)
                is LinkRun -> appendInline(run.children, style.linked(run.href), into)
                is BreakRun -> into += StyledBreak(run.hard)
            }
        }
    }

    /**
     * One string of text, as the runs it becomes once the emoji policy has had
     * its say.
     *
     * @param text the text to draw.
     * @param source the string [text] is part of, for reporting a character that
     *   cannot be drawn — the caller's own string, not the fragment.
     * @param offset where [text] begins in [source].
     */
    private fun textRuns(text: String, style: InlineStyle, source: String, offset: Int): List<StyledTextRun> =
        segmentEmoji(text).flatMap { segment ->
            val at = offset + segment.index
            when (segment) {
                is TextSegment.Plain -> fallbackRuns(segment.text, style, source, at, verbatim = true)
                is TextSegment.Emoji -> when (val policy = theme.emojiPolicy) {
                    EmojiPolicy.None -> fallbackRuns(segment.text, style, source, at, verbatim = true)
                    EmojiPolicy.Strip -> emptyList()
                    EmojiPolicy.Placeholder ->
                        fallbackRuns(placeholderFor(segment.text), style, source, at, verbatim = false)

                    // The emoji, whole, in the font the policy draws emoji from
                    // — an emoji-policy boundary, which is a change of font and
                    // nothing else:
                    // the size, the colour, the decorations and the link are
                    // the surrounding text's, so an emoji inside a link is
                    // inside the link. Which of the two fonts it is — a colour
                    // program's bitmaps or a rasterizer's pictures — is the
                    // policy's business and nothing below cares.
                    is EmojiPolicy.EmbeddedColorFont ->
                        listOfNotNull(checkedRun(segment.text, style, source, at, verbatim = true, font = policy.font))

                    is EmojiPolicy.Rasterized ->
                        listOfNotNull(checkedRun(segment.text, style, source, at, verbatim = true, font = policy.font))
                }
            }
        }

    /**
     * [text] in [style]'s face, and where that face has no glyph for a
     * character, in the first of the family's fallbacks that has one — as many
     * runs as there are changes of font. A character no font of the family has
     * is left with the face, which refuses it as it would without fallbacks.
     */
    private fun fallbackRuns(
        text: String,
        style: InlineStyle,
        source: String,
        offset: Int,
        verbatim: Boolean,
    ): List<StyledTextRun> {
        val fallbacks = style.family.fallbacks
        if (fallbacks.isEmpty()) return listOfNotNull(checkedRun(text, style, source, offset, verbatim))
        val runs = mutableListOf<StyledTextRun>()
        var start = 0
        var current: FontRef? = null
        forEachCodePoint(text) { index, codePoint ->
            val held = current
            val font = when {
                // A combining mark, a joiner, a variation selector belongs to
                // the character before it: they are shaped together, so they
                // are drawn by one font — the one that drew that character.
                held != null && joinsPrevious(codePoint) && fonts.canDraw(held, codePoint) -> held
                // A space between two words of a fallback stays in it, so the
                // words are one run rather than three.
                held != null && held != style.font && codePoint == SPACE && fonts.canDraw(held, codePoint) -> held
                fonts.canDraw(style.font, codePoint) -> style.font
                else -> fallbacks.firstOrNull { fonts.canDraw(it, codePoint) } ?: style.font
            }
            if (held != null && font != held) {
                runs +=
                    listOfNotNull(
                        checkedRun(text.substring(start, index), style, source, offset + start, verbatim, held),
                    )
                start = index
            }
            current = font
        }
        current?.let { font ->
            runs +=
                listOfNotNull(checkedRun(text.substring(start), style, source, offset + start, verbatim, font))
        }
        return runs
    }

    /** Whether [codePoint] is shaped with the character before it rather than on its own. */
    private fun joinsPrevious(codePoint: Int): Boolean = UnicodeProperties.isMark(codePoint) ||
        UnicodeProperties.isDefaultIgnorable(codePoint) ||
        UnicodeProperties.script(codePoint) == "Inherited"

    /**
     * A run of [text] in [style], having asked the font whether it can draw it.
     *
     * Asking costs an encoding pass that the line breaker will repeat when it
     * measures; that is the price of reporting the failure against the string the
     * caller wrote, while its indices still mean something. Empty text is no run
     * at all — an emoji stripped out of the middle of a sentence should not leave
     * a run of nothing behind for the line breaker to reason about.
     *
     * @param font which font draws this run, [InlineStyle.font] unless the emoji
     *   policy sends the run to a colour font of its own.
     * @throws UnencodableCharacterException naming the character, its position in
     *   [source] and the remedy.
     */
    private fun checkedRun(
        text: String,
        style: InlineStyle,
        source: String,
        offset: Int,
        verbatim: Boolean,
        font: FontRef = style.font,
    ): StyledTextRun? {
        if (text.isEmpty()) return null
        try {
            fonts.resolve(font).encode(text)
        } catch (refused: UnencodableCharacterException) {
            throw locate(refused, font, text, source, offset, verbatim)
        }
        return StyledTextRun(
            text = text,
            font = font,
            size = style.size,
            color = style.color,
            underline = style.underline,
            strike = style.strike,
            link = style.link,
        )
    }

    /**
     * The same refusal, told against the caller's string instead of the fragment
     * the splitter handed to the font.
     *
     * Only text that reached the font verbatim can be relocated: a placeholder
     * the emoji policy generated is not in the caller's string at all, and moving
     * its index there would point at a character that is not the one being
     * complained about. Those are reported as they came.
     *
     * A colour font is handed one emoji at a time, so the index it refused is an
     * index into that one emoji; the report names the whole of it, because a
     * sequence one code point of which has no picture is a sequence with no
     * picture, and because pointing at the third code point of a family is
     * pointing at something nobody wrote separately.
     */
    private fun locate(
        refused: UnencodableCharacterException,
        font: FontRef,
        text: String,
        source: String,
        offset: Int,
        verbatim: Boolean,
    ): UnencodableCharacterException {
        if (!verbatim) return refused
        val index = offset + refused.index
        return when (font) {
            is FontRef.Standard14 -> UnencodableCharacterException.outsideEncodingTable(
                text = source,
                index = index,
                codePoint = refused.codePoint,
                fontName = refused.fontName,
                encodingName = refused.encodingName,
            )

            is FontRef.Supplied -> UnencodableCharacterException.outsideFontProgram(
                text = source,
                index = index,
                codePoint = refused.codePoint,
                fontName = refused.fontName,
            )

            is FontRef.ColorEmoji -> UnencodableCharacterException.inEmojiSequence(
                text = source,
                index = offset,
                sequence = text,
                refused = refused,
            )

            // A rasterizer is asked for the whole sequence and refuses the whole
            // sequence, so the refusal already says what it is about; what it
            // does not know is where in the caller's string the emoji stands.
            is FontRef.RasterizedEmoji -> UnencodableCharacterException.relocated(
                text = source,
                index = offset + refused.index,
                refused = refused,
            )
        }
    }

    private fun bodyStyle(): InlineStyle = InlineStyle(theme.bodyFamily, theme.bodyFontSize, theme.textColor)

    /** The style of a heading: the theme's family and size for the level, bold if the theme says so. */
    private fun InlineStyle.asHeading(level: Int): InlineStyle = copy(
        family = theme.headingFamily(level),
        size = theme.headingSize(level),
        bold = bold || theme.headingBold,
    )

    /**
     * This style with a run's overrides applied. Everything composes rather than
     * replaces — bold inside bold is bold, and a size inside a size is the inner
     * one, because that is the one the caller wrote closest to the text.
     */
    private fun InlineStyle.with(run: StyledRun): InlineStyle = copy(
        family = if (run.code) theme.codeFamily else family,
        size = run.fontSize ?: if (run.code) theme.codeFontSize else size,
        color = run.color ?: color,
        bold = bold || run.bold,
        italic = italic || run.italic,
        underline = underline || run.underline,
        strike = strike || run.strike,
    )

    /** This style as a link: the theme's colour, the theme's underline, and the target. */
    private fun InlineStyle.linked(href: String): InlineStyle = copy(
        color = theme.linkColor,
        underline = underline || theme.linkUnderline,
        link = href,
    )

    private companion object {
        const val LINE_FEED: String = "\n"
        const val CARRIAGE_RETURN: String = "\r"
        const val CARRIAGE_RETURN_LINE_FEED: String = "\r\n"

        /**
         * Adjacent runs that would be drawn identically, joined back into one.
         *
         * The splitting above is a consequence of how the caller nested their
         * markup and of where the emoji were; neither is a property of the text
         * on the page. Two runs in the same font, size, colour, decorations and
         * link are one run, and saying so here means the line breaker never sees
         * a seam that does not exist — and that stripping an emoji out of
         * "Hello 👋 world" leaves one run of "Hello  world", not two halves.
         */
        fun merge(content: List<StyledInline>): List<StyledInline> {
            val merged = mutableListOf<StyledInline>()
            for (item in content) {
                val previous = merged.lastOrNull()
                if (item is StyledTextRun && previous is StyledTextRun && previous.sameStyleAs(item)) {
                    merged[merged.lastIndex] = previous.copy(text = previous.text + item.text)
                } else {
                    merged += item
                }
            }
            return merged
        }

        fun StyledTextRun.sameStyleAs(other: StyledTextRun): Boolean = copy(text = "") == other.copy(text = "")

        /** A block set flush to what its cell asked for, if the cell asked for anything. */
        fun aligned(block: StyledBlock, align: TextAlign): StyledBlock =
            if (align == TextAlign.Start || block !is StyledParagraph) block else block.copy(align = align)

        /** A line of a code block that the emoji policy emptied, or that was empty to begin with. */
        fun emptyRun(style: InlineStyle): StyledTextRun = StyledTextRun("", style.font, style.size, style.color)

        /**
         * The code points of one emoji, written as Unicode writes a sequence:
         * `[U+1F44B]`, `[U+1F468 U+200D U+1F469]`.
         *
         * All of them, not just the first: a family emoji is four people and
         * three joiners, and a placeholder that named only the first would be
         * silently discarding the rest of what it stands for.
         */
        fun placeholderFor(emoji: String): String {
            val names = mutableListOf<String>()
            forEachCodePoint(emoji) { _, codePoint ->
                names += UnencodableCharacterException.codePointName(codePoint)
            }
            return names.joinToString(separator = " ", prefix = "[", postfix = "]")
        }
    }
}

/**
 * The style a run of text inherits: which typeface, at what size, in what
 * colour, and the flags that pick a face out of the family.
 *
 * A family plus two flags rather than a font, because "bold" is a choice between
 * font programs and cannot be made until both the family and the flags are known
 * — see [FontFamily].
 */
private data class InlineStyle(
    val family: FontFamily,
    val size: Pt,
    val color: Rgb,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strike: Boolean = false,
    val link: String? = null,
) {
    /** The font this style draws with: the family's face for the two flags. */
    val font: FontRef get() = family.face(bold, italic)

    /** The same style with nothing drawn along the text — for a list marker. */
    fun undecorated(): InlineStyle = copy(underline = false, strike = false, link = null)
}

/** U+0020 SPACE — what separates two words of a fallback font's script. */
private const val SPACE: Int = 0x20
