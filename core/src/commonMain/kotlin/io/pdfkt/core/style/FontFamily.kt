/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.style

import io.pdfkt.core.font.FontRef
import io.pdfkt.core.font.Standard14Name

// ISO 32000-2:2020 §9.6.2.2 "Standard Type 1 fonts (standard 14 fonts)".
// The grouping itself is this library's design.

/**
 * Four faces of one typeface, so that "bold" is a choice of font program rather
 * than something done to a font program.
 *
 * A PDF has no notion of applying weight to a font: §9.6.2.2 lists
 * `Helvetica-Bold` beside `Helvetica` as its own entry of the fourteen, and an
 * embedded font program contains the outlines it contains. So a run marked bold
 * has to be drawn with a *different* font, and something has to hold the four
 * together — that is this type, and it is why [io.pdfkt.core.style.Theme]
 * carries families rather than fonts.
 *
 * A family missing a face falls back rather than refusing: many supplied fonts
 * ship as a single file, and a document set in one of them should typeset, with
 * its bold text set in the face it has, rather than fail. What must never be
 * faked is a *glyph* — that is the no-silent-loss line, and it is a different
 * question from which of a caller's font files to reach for.
 *
 * @property regular the face ordinary text is set in.
 * @property bold the face bold text is set in; the regular face when there is
 *   no separate one.
 * @property italic likewise for italic.
 * @property boldItalic likewise for both at once, defaulting to the bold face.
 * @property fallbacks the fonts a character is drawn from when the face a run
 *   is set in has no glyph for it — the first of them that has one, in order.
 *   This is not faking a glyph: the character is drawn, from a font the caller
 *   named for the purpose. A character none of them has is refused as it is
 *   without fallbacks. Only fonts that draw text belong here — a standard font
 *   or a supplied one; emoji are the emoji policy's.
 */
public data class FontFamily(
    public val regular: FontRef,
    public val bold: FontRef = regular,
    public val italic: FontRef = regular,
    public val boldItalic: FontRef = bold,
    public val fallbacks: List<FontRef> = emptyList(),
) {
    init {
        for (fallback in fallbacks) {
            require(fallback is FontRef.Standard14 || fallback is FontRef.Supplied) {
                "$fallback is not a font to fall back to for text: a fallback is a standard font or a " +
                    "supplied font program, and emoji are drawn as the theme's EmojiPolicy says"
            }
        }
    }

    /** This family, drawing what its faces have no glyph for from [fallbacks], in that order. */
    public fun withFallbacks(fallbacks: List<FontRef>): FontFamily = copy(fallbacks = fallbacks)

    /** The face for a given combination of the two style flags. */
    public fun face(bold: Boolean = false, italic: Boolean = false): FontRef = when {
        bold && italic -> boldItalic
        bold -> this.bold
        italic -> this.italic
        else -> regular
    }

    public companion object {
        /**
         * The four Helvetica faces of §9.6.2.2, embedded in nothing — the family
         * [io.pdfkt.core.style.Theme.Default] sets a document's text in.
         */
        public val HELVETICA: FontFamily = standard14(
            Standard14Name.HELVETICA,
            Standard14Name.HELVETICA_BOLD,
            Standard14Name.HELVETICA_OBLIQUE,
            Standard14Name.HELVETICA_BOLD_OBLIQUE,
        )

        /** The four Courier faces of §9.6.2.2 — the monospaced ones, which the default theme sets code in. */
        public val COURIER: FontFamily = standard14(
            Standard14Name.COURIER,
            Standard14Name.COURIER_BOLD,
            Standard14Name.COURIER_OBLIQUE,
            Standard14Name.COURIER_BOLD_OBLIQUE,
        )

        /**
         * A family of one supplied font program, used for all four faces.
         *
         * The honest shape for a caller who has one file: bold text comes out in
         * the weight the file carries, and nothing is synthesised.
         */
        public fun of(font: FontRef): FontFamily = FontFamily(font)

        private fun standard14(
            regular: Standard14Name,
            bold: Standard14Name,
            italic: Standard14Name,
            boldItalic: Standard14Name,
        ): FontFamily = FontFamily(
            regular = FontRef.Standard14(regular),
            bold = FontRef.Standard14(bold),
            italic = FontRef.Standard14(italic),
            boldItalic = FontRef.Standard14(boldItalic),
        )
    }
}
