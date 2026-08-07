/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.font

// ISO 32000-2:2020 §9.6.2.2 "Standard Type 1 fonts (standard 14 fonts) (PDF 1.0-1.7)",
// §9.6.5 "Character encoding", §9.2.4 "Glyph positioning and metrics",
// §9.8.1 "Font descriptors — General" (Table 120); Adobe Font Metrics File
// Format Specification, technical note #5004, §7.1 "Global Font Information"

/**
 * The simple font path: the measurements of one of the fourteen
 * fonts of §9.6.2.2, which the file does not carry.
 *
 * The clause is explicit that the numbers exist outside the document — "These
 * fonts, or their font metrics and suitable substitution fonts, shall be
 * available to the PDF processor", and Table 109's `Widths` may be absent for
 * exactly these fourteen — so a reader lays the text out from metrics it already
 * has. This library needs the same numbers, for the same fourteen fonts, in
 * order to decide where a line breaks before it writes anything.
 *
 * They come from Adobe's AFM files for those fonts, parsed by [AfmFont] out of
 * the copy compiled into this module ([standard14AfmLines]) — a published
 * specification and a published set of files, on the same footing as every other
 * source this repository is written from. The parse
 * happens once, here, which is once per font per document: this object is built
 * by [FontCatalog] and lives as long as the catalog does.
 *
 * ## Widths need an encoding
 *
 * What an AFM gives is the width of a *glyph* — by its PostScript name, or by
 * its code in the font's own default encoding. What a caller has is a Kotlin
 * `String`. Between the two sits the byte encoding of §9.6.5: [WinAnsiEncoding]
 * turns a character into the code a shown string will carry, and the code names
 * the glyph whose `WX` is the width. So the number a line is broken against is
 * computed from the same bytes the page will be drawn with, rather than from a
 * parallel idea of what the text says.
 *
 * Symbol and ZapfDingbats are outside that: they are the two symbolic fonts of
 * §9.6.5.1, whose character sets and built-in encodings are D.5 and D.6 rather
 * than Table D.2 (see [Standard14Name.symbolic]). Those tables are not written
 * yet, and measuring with the wrong one would answer a plausible number for the
 * wrong glyph, so measuring them raises and says which table is missing.
 */
internal class Standard14Metrics(private val name: Standard14Name) : GlyphSpaceMetrics {
    /** The parsed AFM of this font: its widths, by glyph name and by code. */
    val afm: AfmFont = AfmFont.parse(standard14AfmLines(name))

    /**
     * The sum of the `WX` of the glyphs [text] selects, in glyph space.
     *
     * The route is the one §9.6.5.1 describes, in three steps and no shortcuts:
     * a character becomes a code (Table D.2), a code becomes the PostScript
     * language name of a glyph (Table D.2 again), and a name becomes a width
     * (#5004 §8, `N` and `WX`). Every step can say "no", and every "no" raises.
     *
     * The first step is [Standard14Name.encode] — the same call that produces
     * the bytes of the shown string — so the width a line is broken against is
     * the width of the glyphs the page will actually select.
     *
     * @throws UnencodableCharacterException at the first character
     *   WinAnsiEncoding has no code for, naming it, its position and the remedy.
     * @throws UnsupportedOperationException for Symbol and ZapfDingbats, whose
     *   encodings are D.5 and D.6.
     */
    override fun advanceOf(text: String): Double {
        var total = 0.0
        for (code in name.encode(text)) {
            val glyphName = checkNotNull(WinAnsiEncoding.glyphNameOf(code.toInt() and BYTE_MASK)) {
                "WinAnsiEncoding produced the code ${code.toInt() and BYTE_MASK}, which Table D.2 " +
                    "assigns to no glyph"
            }
            total += afm.widthOfGlyph(glyphName) ?: throw noWidthForGlyph(glyphName)
        }
        return total
    }

    /**
     * A glyph WinAnsiEncoding names and this font's AFM does not describe.
     *
     * Adobe's files for the twelve Latin fonts of §9.6.2.2 carry all 216, so
     * this is unreachable through them — which is exactly why it raises instead
     * of returning 0.0: an unreachable branch that silently answers zero stops
     * being unreachable the day the metrics change, and answers by laying every
     * line out short.
     */
    private fun noWidthForGlyph(glyphName: String): Nothing = throw IllegalStateException(
        "the AFM of ${name.baseFont.name} gives no WX for the glyph \"$glyphName\", which " +
            "${WinAnsiEncoding.NAME} names (ISO 32000-2:2020 Annex D, Table D.2). A width this " +
            "library did not read is a width it will not invent",
    )

    /**
     * `Ascender` of #5004 §7.1 — "For roman font programs: usually the y-value of
     * the top of the lowercase d" — which is what Table 120 asks a font
     * descriptor's `Ascent` to be, "the maximum height above the baseline reached
     * by glyphs in this font", with accents excluded.
     *
     * The keyword is optional in AFM, and two of the fourteen leave it out:
     * Symbol and ZapfDingbats have neither a lowercase d nor a capital H, and
     * their files carry no `Ascender`, `Descender` or `CapHeight` at all. For
     * those the font bounding box answers instead: `FontBBox` is "the lower left
     * corner and the upper right corner of the font bounding box" (#5004 §7.1),
     * so its top edge is the highest any glyph of the font reaches. It is the
     * conservative reading — never shorter than the real ascent — and it is a
     * number the file actually states, which is the whole difference between it
     * and an assumption.
     */
    override val ascent: Double get() = afm.ascender ?: afm.fontBBox.ury

    /**
     * `Descender` of #5004 §7.1 — "typically the y-value of the bottom of the
     * lowercase p" — negative, as Table 120 requires of `Descent`: "The value
     * shall be a negative number." Adobe's files state it negative already.
     *
     * Absent for Symbol and ZapfDingbats, and answered there by the bottom edge
     * of `FontBBox`, for the reason given on [ascent].
     */
    override val descent: Double get() = afm.descender ?: afm.fontBBox.lly

    /**
     * Table 120's `Leading`, "the spacing between baselines of consecutive lines
     * of text", default 0.
     *
     * AFM has no such keyword: the format describes a font program, and the gap
     * between lines is not a property of one. So the default stands, and a line
     * of a standard 14 font is exactly as tall as the font reaches — the theme's
     * line spacing is applied on top of that, in L4.
     */
    override val leading: Double get() = 0.0

    private companion object {
        /** A simple font's character codes are single bytes (§9.6.5.1). */
        const val BYTE_MASK: Int = 0xFF
    }
}
