/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.fragment

import io.pdfkt.core.font.FontCatalog
import io.pdfkt.core.font.FontMetrics
import io.pdfkt.core.font.codePointAt
import io.pdfkt.core.font.codePointToString
import io.pdfkt.core.model.Pt
import io.pdfkt.core.plan.Decoration
import io.pdfkt.core.plan.PlacedRun
import io.pdfkt.core.resolve.StyledBreak
import io.pdfkt.core.resolve.StyledInline
import io.pdfkt.core.resolve.StyledTextRun

// No clause applies: L4 is this library's own design. §9.2.4 is what the widths mean — "the distance the current
// text position shall move … when the glyph is painted" — and it is asked of
// `FontMetrics`, not read here.

/**
 * Styled runs in, placed lines out — the second half of the inline flow:
 *
 * > The line breaker then consumes styled runs against the column width:
 * > break opportunities: after spaces and soft hyphens; a token longer than the
 * > line breaks per character — losing characters is forbidden.
 *
 * The strategy is greedy, and deliberately so: text is accumulated left to right until the column width is
 * exceeded and the line is then cut at the last break opportunity. Knuth–Plass
 * would set better paragraphs for materially more complexity, and justification
 * quality is not what this library is judged on.
 *
 * ## What a line may and may not do
 *
 * - **No run spans lines.** A run that reaches a break is cut, and both halves
 *   are runs of their own in the same style. This is the invariant the plan
 *   model is built on, and it is what
 *   lets the emitter draw a run without ever asking where the line ends.
 * - **Nothing is dropped.** Not one character reaches a width it does not fit
 *   and disappears: a token longer than the whole column is broken between
 *   characters, and a column too narrow for even one character still takes one
 *   per line. An overflowing character is visible and reports itself; a dropped
 *   one is a blank the caller finds in production, which is the failure the
 *   "no silent loss" policy exists to prevent.
 * - **White space at a break hangs.** The space a line broke after stays in the
 *   text of that line, and its width is left out of [TextLine.width]. It is
 *   therefore neither lost from the document nor counted against the column,
 *   which is what keeps a ragged right edge straight — text broken at a space
 *   lines up with text broken anywhere else.
 *
 * ## Break opportunities
 *
 * After a breaking space, and at a soft hyphen. Which characters break is an
 * explicit table ([isBreakingSpace]) rather than `Char.isWhitespace`, because
 * that function does not answer the same for U+00A0 on every Kotlin target and a
 * line must not break in a different place on the web than on the desktop.
 *
 * A soft hyphen (U+00AD) is a mark saying "a hyphen may be shown here", not a
 * character to draw: it is left out of the text everywhere the line does not
 * break, and becomes a HYPHEN-MINUS where it does. Hyphenation *dictionaries* —
 * finding those points in a word nobody marked — stay out of scope,
 * as do justification and bidi.
 *
 * ## Using it
 *
 * ```
 * val lines = LineBreaker(fonts).breakIntoLines(paragraph.content, column, paragraph.lineHeight)
 * ```
 *
 * The catalog is the document's, the same one the emitter writes the fonts from:
 * measuring a line is what registers a font with the document, and a font
 * measured from one catalog and written from another would be two fonts.
 */
public class LineBreaker(private val fonts: FontCatalog) {
    /**
     * [content] cut into the lines it makes at [width], in order.
     *
     * @param content one paragraph's worth of styled inline content, as the
     *   resolve stage produced it.
     * @param width the column to break against. Every line's [TextLine.width]
     *   stays within it except where a single character does not fit, which the
     *   caller sees rather than loses.
     * @param lineHeight the multiple of the font's own line height that
     *   consecutive baselines are set apart by — `StyledParagraph.lineHeight`.
     * @param firstLineIndent how far the first line alone is set in. The lines
     *   after it start at zero, and a hard break does not bring the indent back:
     *   it is the *paragraph's* first line, not every line that follows a break.
     * @return one [TextLine] per line. Empty content gives no lines at all;
     *   content that is there but draws nothing — a code block's blank line, a
     *   run the emoji policy emptied — gives one line of no runs, which still
     *   has a height, because a blank line is a line. A hard break with nothing
     *   after it leaves such a line behind for the same reason: it asked for a
     *   new line, and the caller who wrote it can see it in their own text.
     */
    public fun breakIntoLines(
        content: List<StyledInline>,
        width: Pt,
        lineHeight: Float = 1.0f,
        firstLineIndent: Pt = Pt.ZERO,
    ): List<TextLine> {
        require(width.value > 0.0f) {
            "a column of $width has no room for any text: a line is broken against a width, and a " +
                "width that is not positive is a column nothing fits in"
        }
        require(lineHeight > 0.0f) {
            "the line height multiplier must be positive, got $lineHeight"
        }
        return LineBreaking(fonts, content, width, lineHeight, firstLineIndent).lines()
    }
}

/**
 * One run of the breaker over one paragraph — the mutable half, kept out of
 * [LineBreaker] so that a breaker holds nothing between calls and two paragraphs
 * cannot see each other's state.
 */
private class LineBreaking(
    private val fonts: FontCatalog,
    private val content: List<StyledInline>,
    private val columnWidth: Pt,
    private val lineHeightFactor: Float,
    firstLineIndent: Pt,
) {
    private val lines: MutableList<TextLine> = mutableListOf()

    /** The pieces placed on the line being built, in order, before they are joined into runs. */
    private val fragments: MutableList<Fragment> = mutableListOf()

    /** How far in the current line starts. The first line's indent, then zero. */
    private var indent: Pt = firstLineIndent

    /**
     * How far the current line reaches, from its left edge, white space
     * included — where the next thing placed would start.
     *
     * The space between two words is counted here and not in what a token is
     * fitted by: the word after a space starts past it, while the space a line
     * ends at hangs into the margin. Both fall out of one rule — the extent is
     * measured up to the token, and a token is measured without the space it
     * ends at ([Token.width]).
     */
    private var advance: Pt = firstLineIndent

    /** Whether a line has been begun and not yet flushed — see [lines]. */
    private var pendingLine: Boolean = content.isNotEmpty()

    /** Whether the last token placed ended at a soft hyphen, so a break here shows one. */
    private var hyphenPending: Boolean = false

    /**
     * The style to measure a line by when the line has no text of its own: the
     * last style that was drawn, or — for a blank first line — the first style
     * the paragraph mentions. A blank line still occupies the height of the text
     * it was written among.
     */
    private var styleInForce: StyledTextRun? = content.filterIsInstance<StyledTextRun>().firstOrNull()

    fun lines(): List<TextLine> {
        for (item in tokenize()) {
            when (item) {
                is Token -> place(item)
                HardBreak -> {
                    flush(hyphenate = false)
                    pendingLine = true
                }
            }
        }
        if (pendingLine) flush(hyphenate = false)
        return lines
    }

    // ---------------------------------------------------------------- tokens

    /**
     * The content as the units a line is built out of: tokens that may not be
     * broken except character by character, and the hard breaks between them.
     *
     * A token spans runs and stops only at a break opportunity, which is what
     * makes `**bold**face` one word: where the caller changed the styling is not
     * a place a line may break. Each token carries its pieces — one per style it
     * passes through — and a trailing space, when it ends at one, is a piece of
     * its own so that the space can hang off the end of a line.
     */
    private fun tokenize(): List<Item> {
        val items = mutableListOf<Item>()
        var parts = mutableListOf<Fragment>()
        val word = StringBuilder()
        var wordStyle: StyledTextRun? = null

        fun flushWord() {
            val style = wordStyle
            if (style == null || word.isEmpty()) return
            parts += fragmentOf(style, word.toString(), space = false)
            word.clear()
        }

        fun endToken(hyphenated: Boolean) {
            flushWord()
            if (parts.isEmpty()) return
            items += Token(parts, hyphenated)
            parts = mutableListOf()
        }

        for (inline in content) {
            when (inline) {
                is StyledTextRun -> {
                    wordStyle = inline
                    var index = 0
                    while (index < inline.text.length) {
                        val codePoint = codePointAt(inline.text, index)
                        val character = codePointToString(codePoint)
                        index += character.length
                        when {
                            codePoint == SOFT_HYPHEN -> endToken(hyphenated = true)
                            isBreakingSpace(codePoint) -> {
                                flushWord()
                                parts += fragmentOf(inline, character, space = true)
                                endToken(hyphenated = false)
                            }

                            else -> word.append(character)
                        }
                    }
                    flushWord()
                }

                // A hard break ends the line whatever the width; a soft one only
                // says that it may end here, which is what a token boundary is.
                is StyledBreak -> {
                    endToken(hyphenated = false)
                    if (inline.hard) items += HardBreak
                }
            }
        }
        endToken(hyphenated = false)
        return items
    }

    // ------------------------------------------------------------- placement

    /**
     * One token onto the current line, or onto the next one.
     *
     * The greedy rule, and the two ways out of it: a token that does not fit the
     * rest of the line moves to the next line, and a token that does not fit an
     * empty line is broken between characters — there is no third line to move
     * it to, and dropping it is not an option this library has.
     *
     * A token that ends at a soft hyphen is measured with the hyphen included,
     * because a line ending there has to show one. It makes such a token move a
     * hyphen's width early when it turns out not to be the end of the line after
     * all; the alternative is a hyphen printed past the edge of the column.
     */
    private fun place(token: Token) {
        val reserve = if (token.hyphenated) hyphenWidth(token.parts.last().style) else Pt.ZERO
        if (fits(advance + token.width + reserve)) {
            append(token)
            return
        }
        if (fragments.isNotEmpty()) {
            flush(hyphenate = hyphenPending)
            place(token)
            return
        }
        // The line is empty, so there is nowhere left to move the token to. It
        // is broken between characters unless it was only the hyphen that did
        // not fit — a word that fits the column is not cut up over a mark saying
        // where it could have been.
        if (fits(token.width)) append(token) else splitBetweenCharacters(token)
    }

    /**
     * A token wider than the whole column, one character at a time.
     *
     * The rule that makes it terminate is that a line always takes at least one
     * character: a column narrower than a single glyph would otherwise be a
     * paragraph that never advances, and this library answers such a column with
     * a page that visibly overflows rather than with an empty one or a hang.
     *
     * Characters, not `Char`s: a code point beyond the basic multilingual plane
     * is two UTF-16 units of one character, and a line ending between them would
     * carry half a surrogate pair into a text-showing operator.
     */
    private fun splitBetweenCharacters(token: Token) {
        for (part in token.parts) {
            var index = 0
            while (index < part.text.length) {
                val codePoint = codePointAt(part.text, index)
                val character = codePointToString(codePoint)
                index += character.length
                val fragment = fragmentOf(part.style, character, part.space)
                if (fragments.isNotEmpty() && !fits(advance + fragment.width)) flush(hyphenate = false)
                append(fragment)
            }
        }
        hyphenPending = token.hyphenated
    }

    private fun append(token: Token) {
        for (part in token.parts) append(part)
        hyphenPending = token.hyphenated
    }

    private fun append(fragment: Fragment) {
        fragments += fragment
        advance += fragment.width
        styleInForce = fragment.style
        pendingLine = true
    }

    /**
     * The line built so far, finished and set aside.
     *
     * @param hyphenate whether the break being taken is one a soft hyphen marked,
     *   in which case the hyphen it stood for is drawn at the end of this line —
     *   the one place the character the caller wrote becomes ink.
     */
    private fun flush(hyphenate: Boolean) {
        if (hyphenate && fragments.isNotEmpty()) {
            val style = fragments.last().style
            append(fragmentOf(style, HYPHEN, space = false))
        }
        lines += lineOf()
        fragments.clear()
        indent = Pt.ZERO
        advance = Pt.ZERO
        pendingLine = false
        hyphenPending = false
    }

    // ----------------------------------------------------------------- lines

    /**
     * The fragments of the current line as the line itself: pieces of one style
     * joined back together, each run measured once, and the line measured from
     * the runs.
     *
     * Joining matters twice over. A run per fragment would be a run per word —
     * every one of them setting the same font and colour again in the content
     * stream — and, worse, a decoration per word: an underline drawn as one
     * rectangle per word has gaps where the spaces are, which is not what an
     * underline looks like.
     */
    private fun lineOf(): TextLine {
        val groups = mutableListOf<Group>()
        for (fragment in fragments) {
            val last = groups.lastOrNull()
            if (last != null && last.style.sameStyleAs(fragment.style)) {
                last.text.append(fragment.text)
            } else {
                groups += Group(fragment.style, StringBuilder(fragment.text))
            }
        }

        val runs = mutableListOf<PlacedRun>()
        val links = mutableListOf<LinkSpan>()
        var x = indent
        var contentEnd = indent
        var openTarget: String? = null
        for (group in groups) {
            val text = group.text.toString()
            val width = measure(group.style, text)
            runs += PlacedRun(
                text = text,
                font = group.style.font,
                size = group.style.size,
                color = group.style.color,
                xOffset = x,
                width = width,
                decorations = decorationsOf(group.style),
            )
            val target = group.style.link
            val start = x
            x += width
            // Runs that follow one another and point at the same place are one
            // link. The span is grown to the end of this run rather than by its
            // width: a sum of the same widths in a different order is not the
            // same `Float`, and a link's box has to end where its text does.
            if (target != null) {
                if (target == openTarget) {
                    links[links.lastIndex] = links.last().let { open -> open.copy(width = x - open.xOffset) }
                } else {
                    links += LinkSpan(target, start, width)
                }
            }
            openTarget = target
            val hanging = trailingSpaces(text)
            if (hanging.length < text.length) contentEnd = x - measure(group.style, hanging)
        }

        val styles = if (groups.isEmpty()) listOfNotNull(styleInForce) else groups.map { it.style }
        return TextLine(
            runs = runs,
            width = contentEnd,
            ascent = Pt(styles.maxOfOrNull { metricsOf(it).ascent(it.size.value) } ?: 0.0f),
            descent = Pt(styles.minOfOrNull { metricsOf(it).descent(it.size.value) } ?: 0.0f),
            height = Pt((styles.maxOfOrNull { metricsOf(it).lineHeight(it.size.value) } ?: 0.0f) * lineHeightFactor),
            links = links,
        )
    }

    /**
     * The rules drawn along a run of this style.
     *
     * PDF draws neither an underline nor a strike, and the metrics contract L4
     * sees answers neither's position, so the geometry is decided here,
     * out of quantities that contract does answer:
     *
     * - the **thickness** is a twentieth of the size, and the **underline**
     *   crosses a tenth of the size below the baseline. Those are the values the
     *   standard fourteen state for themselves — `UnderlineThickness 50` and
     *   `UnderlinePosition -100` per 1000 units of glyph space, in every one of
     *   the AFM tables this library carries.
     * - the **strike** crosses halfway between the baseline and the top of the
     *   font's tallest glyphs. It therefore crosses lowercase letters as well as
     *   capitals: the x-height of the fourteen is around three quarters of their
     *   ascent (Helvetica's 523 against 718), which puts the middle of the
     *   lowercase text below the strike rather than above it.
     *
     * Both offsets name the **bottom edge** of a filled rectangle
     * ([Decoration]), so the position the font states — which is where the rule
     * is centred — has half a thickness taken off it.
     */
    private fun decorationsOf(style: StyledTextRun): List<Decoration> {
        if (!style.underline && !style.strike) return emptyList()
        val size = style.size.value
        val thickness = Pt(size * DECORATION_THICKNESS_EM)
        val decorations = mutableListOf<Decoration>()
        if (style.underline) {
            decorations += Decoration.Underline(Pt(size * UNDERLINE_POSITION_EM) - thickness / 2.0f, thickness)
        }
        if (style.strike) {
            decorations += Decoration.Strike(Pt(metricsOf(style).ascent(size) / 2.0f) - thickness / 2.0f, thickness)
        }
        return decorations
    }

    // ------------------------------------------------------------ measuring

    private fun fragmentOf(style: StyledTextRun, text: String, space: Boolean): Fragment =
        Fragment(style, text, measure(style, text), space)

    private fun measure(style: StyledTextRun, text: String): Pt =
        if (text.isEmpty()) Pt.ZERO else Pt(metricsOf(style).widthOf(text, style.size.value))

    private fun metricsOf(style: StyledTextRun): FontMetrics = fonts.metricsOf(style.font)

    private fun hyphenWidth(style: StyledTextRun): Pt = measure(style, HYPHEN)

    /**
     * Whether a line reaching [extent] is still inside the column.
     *
     * The tolerance is what keeps the last word of an exactly-filled line where
     * it belongs: a line's extent is a sum of `Float` widths and the column is
     * one number, so text that fits to the ten-thousandth of a point can come out
     * a bit-width over it. A thousandth of a point is a seventy-thousandth of an
     * inch — smaller than anything a device resolves, and far smaller than the
     * word that would otherwise drop to the next line.
     */
    private fun fits(extent: Pt): Boolean = extent.value <= columnWidth.value + FIT_TOLERANCE

    private class Group(val style: StyledTextRun, val text: StringBuilder)

    private companion object {
        const val HYPHEN: String = "-"

        /** `UnderlineThickness 50` of 1000 units of glyph space, in every AFM table of the fourteen. */
        const val DECORATION_THICKNESS_EM: Float = 0.05f

        /** `UnderlinePosition -100`, likewise: where the rule is centred, below the baseline. */
        const val UNDERLINE_POSITION_EM: Float = -0.1f

        const val FIT_TOLERANCE: Float = 1.0e-3f

        fun StyledTextRun.sameStyleAs(other: StyledTextRun): Boolean = copy(text = "") == other.copy(text = "")

        /** The white space at the end of [text] — what hangs into the margin when a line breaks. */
        fun trailingSpaces(text: String): String {
            var start = text.length
            while (start > 0 && isBreakingSpace(text[start - 1].code)) start--
            return text.substring(start)
        }
    }
}

/** What a line is built out of: a stretch of one style, measured. */
private class Fragment(
    val style: StyledTextRun,
    val text: String,
    val width: Pt,
    /** A space that ended a token, and so may hang off the end of a line. */
    val space: Boolean,
)

/** A unit of the tokenized content: either something to place, or a break to take. */
private sealed interface Item

/**
 * Text with no break opportunity inside it, in the styles it passes through.
 *
 * @property hyphenated the token ends at a soft hyphen, so a line ending here
 *   shows a hyphen and one continuing past it shows nothing.
 */
private class Token(val parts: List<Fragment>, val hyphenated: Boolean) : Item {
    /** How far the token moves the text position, hanging space included. */
    val advance: Pt = parts.fold(Pt.ZERO) { total, part -> total + part.width }

    /** The same without the space it ends at, which is what it is fitted by. */
    val width: Pt = advance - (parts.last().takeIf { it.space }?.width ?: Pt.ZERO)
}

/** An explicit break: the line ends here whatever room is left. */
private data object HardBreak : Item

/** SOFT HYPHEN — a break opportunity, and a hyphen only where the break is taken. */
private const val SOFT_HYPHEN: Int = 0x00AD

/**
 * Whether a line may break after this character.
 *
 * An explicit table rather than `Char.isWhitespace`, for two reasons. The first
 * is that the function does not mean the same thing on every target this library
 * builds for — U+00A0 is a space character by Unicode's own classification and
 * not a whitespace character by the JVM's — and a document must not break its
 * lines in different places in a browser than on a desktop. The second is that
 * the answer is a typographic decision and not a Unicode fact: NO-BREAK SPACE
 * (U+00A0) and NARROW NO-BREAK SPACE (U+202F) are spaces that exist precisely in
 * order not to break, and they are absent here for that reason.
 *
 * The rest are the spaces of Unicode's General Punctuation block, which are
 * ordinary breaking spaces of fixed widths, plus TAB and the IDEOGRAPHIC SPACE.
 */
private fun isBreakingSpace(codePoint: Int): Boolean = when (codePoint) {
    0x0009, 0x0020, 0x1680, 0x205F, 0x3000 -> true
    in 0x2000..0x200A -> true
    else -> false
}
