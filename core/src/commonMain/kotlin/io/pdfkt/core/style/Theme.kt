/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.style

import io.pdfkt.core.block.HeadingBlock
import io.pdfkt.core.model.Pt
import io.pdfkt.core.model.Rgb
import io.pdfkt.core.model.pt

// No clause applies: L4 is this library's own design. Only §9.6.2.2 constrains the defaults — the fourteen
// standard fonts are what a document can be set in without embedding anything.

/**
 * Everything a block does not say about itself: which fonts, which sizes, which
 * colours, how much space.
 *
 * One half of the resolve stage's input, the block tree being the
 * other. After resolving,
 * no styled block refers to a theme: every value has been chosen, so the
 * fragmenter and the emitter never consult one and a theme cannot change a
 * document halfway down a page.
 *
 * Every value is a *default*. A block or run that states its own overrides it,
 * for itself, and nothing else.
 *
 * The defaults are the standard 14 fonts of §9.6.2.2 for one reason: `:core`
 * carries no font programs. "An 11 MB embedded-font blob is acceptable on
 * mobile and fatal in a browser bundle", so
 * the fonts that come free with the format are what a document gets when nobody
 * supplies any — and the price, a Latin repertoire, is paid loudly by
 * `UnencodableCharacterException` rather than quietly by a blank page.
 *
 * @property bodyFamily the typeface of ordinary text.
 * @property bodyFontSize its size.
 * @property lineHeight the multiple of the font's own line height that
 *   consecutive baselines are set apart by. 1.0 is the font's own spacing.
 * @property textColor the colour of ordinary text.
 * @property headingFamilies the typeface of each heading level, 1 to 6.
 * @property headingSizes the size of each heading level.
 * @property headingSpacingBefore the space above each heading level.
 * @property headingSpacingAfter the space below each heading level.
 * @property headingBold whether headings are set in their family's bold face.
 * @property codeFamily the monospaced typeface of code blocks and inline code.
 * @property codeFontSize its size.
 * @property codeBackground the panel drawn behind a code block, or null.
 * @property paragraphSpacingBefore the space above a paragraph.
 * @property paragraphSpacingAfter the space below a paragraph — and below every
 *   other block that does not have a spacing of its own, which is what keeps a
 *   list, a table and a quotation in the same rhythm as the text around them.
 * @property linkColor the colour of link text.
 * @property linkUnderline whether link text is underlined.
 * @property quoteBarColor the bar beside a blockquote.
 * @property ruleThickness the thickness of a horizontal rule.
 * @property ruleColor its colour.
 * @property ruleSpacing the space above and below it.
 * @property listMarkerSuffix what an ordered list's number is followed by.
 * @property tableHeaderBold whether a table's header cells are set bold.
 * @property emojiPolicy what happens to emoji in the text.
 */
public data class Theme(
    public val bodyFamily: FontFamily = FontFamily.HELVETICA,
    public val bodyFontSize: Pt = 11.pt,
    public val lineHeight: Float = DEFAULT_LINE_HEIGHT,
    public val textColor: Rgb = Rgb.BLACK,
    public val headingFamilies: Map<Int, FontFamily> = HEADING_LEVELS.associateWith { FontFamily.HELVETICA },
    public val headingSizes: Map<Int, Pt> = DEFAULT_HEADING_SIZES,
    public val headingSpacingBefore: Map<Int, Pt> = DEFAULT_HEADING_SPACING_BEFORE,
    public val headingSpacingAfter: Map<Int, Pt> = DEFAULT_HEADING_SPACING_AFTER,
    public val headingBold: Boolean = true,
    public val codeFamily: FontFamily = FontFamily.COURIER,
    public val codeFontSize: Pt = 10.pt,
    public val codeBackground: Rgb? = DEFAULT_CODE_BACKGROUND,
    public val paragraphSpacingBefore: Pt = Pt.ZERO,
    public val paragraphSpacingAfter: Pt = 6.pt,
    public val linkColor: Rgb = DEFAULT_LINK_COLOR,
    public val linkUnderline: Boolean = true,
    public val quoteBarColor: Rgb = DEFAULT_QUOTE_BAR_COLOR,
    public val ruleThickness: Pt = 0.5.pt,
    public val ruleColor: Rgb = DEFAULT_RULE_COLOR,
    public val ruleSpacing: Pt = 6.pt,
    public val listMarkerSuffix: String = ".",
    public val tableHeaderBold: Boolean = true,
    public val emojiPolicy: EmojiPolicy = EmojiPolicy.None,
) {
    init {
        require(bodyFontSize.value > 0.0f) { "the body font size must be positive, got $bodyFontSize" }
        require(codeFontSize.value > 0.0f) { "the code font size must be positive, got $codeFontSize" }
        require(lineHeight > 0.0f) { "the line height multiplier must be positive, got $lineHeight" }
        requireEveryLevel("headingFamilies", headingFamilies)
        requireEveryLevel("headingSizes", headingSizes)
        requireEveryLevel("headingSpacingBefore", headingSpacingBefore)
        requireEveryLevel("headingSpacingAfter", headingSpacingAfter)
        headingSizes.forEach { (level, size) ->
            require(size.value > 0.0f) { "the size of a level $level heading must be positive, got $size" }
        }
    }

    /** The typeface a heading of [level] is set in. */
    public fun headingFamily(level: Int): FontFamily = headingFamilies.getValue(level)

    /** The size a heading of [level] is set at. */
    public fun headingSize(level: Int): Pt = headingSizes.getValue(level)

    public companion object {
        private val HEADING_LEVELS: IntRange = HeadingBlock.MIN_LEVEL..HeadingBlock.MAX_LEVEL

        private const val DEFAULT_LINE_HEIGHT: Float = 1.2f

        private val DEFAULT_HEADING_SIZES: Map<Int, Pt> =
            mapOf(1 to 24.pt, 2 to 20.pt, 3 to 16.pt, 4 to 14.pt, 5 to 12.pt, 6 to 11.pt)

        private val DEFAULT_HEADING_SPACING_BEFORE: Map<Int, Pt> =
            mapOf(1 to 18.pt, 2 to 14.pt, 3 to 12.pt, 4 to 10.pt, 5 to 8.pt, 6 to 6.pt)

        private val DEFAULT_HEADING_SPACING_AFTER: Map<Int, Pt> =
            mapOf(1 to 8.pt, 2 to 6.pt, 3 to 6.pt, 4 to 4.pt, 5 to 4.pt, 6 to 4.pt)

        private val DEFAULT_LINK_COLOR: Rgb = Rgb.of(0x0066CC)
        private val DEFAULT_CODE_BACKGROUND: Rgb = Rgb.of(0xF5F5F5)
        private val DEFAULT_QUOTE_BAR_COLOR: Rgb = Rgb.gray(0.5f)
        private val DEFAULT_RULE_COLOR: Rgb = Rgb.gray(0.5f)

        /**
         * A theme must answer for every heading level, because a heading resolves
         * its font, its size and its spacing out of these maps — a gap would be a
         * document that renders five levels and throws on the sixth, at whatever
         * point in the run the caller first used it.
         */
        private fun requireEveryLevel(name: String, values: Map<Int, *>) {
            val missing = HEADING_LEVELS.filterNot { it in values }
            require(missing.isEmpty()) {
                "Theme.$name must cover heading levels ${HEADING_LEVELS.first}..${HEADING_LEVELS.last}; " +
                    "missing $missing"
            }
        }

        /**
         * The theme a document is set in when the caller states none.
         *
         * Last in the companion on purpose: constructing it reads every default
         * above, and a companion's properties are initialised in the order they
         * are written.
         */
        public val Default: Theme = Theme()
    }
}
