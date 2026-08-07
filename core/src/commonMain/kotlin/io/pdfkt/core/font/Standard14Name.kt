/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.font

import io.pdfkt.core.obj.PdfDict
import io.pdfkt.core.obj.PdfName

// ISO 32000-2:2020 §9.6.2.2 "Standard Type 1 fonts (standard 14 fonts) (PDF 1.0-1.7)",
// §9.6.2.1 "Type 1 fonts — General" (Table 109 "Entries in a Type 1 font dictionary"),
// §9.6.5 "Character encoding", Annex D "Character sets and encodings"

/**
 * One of the fourteen fonts §9.6.2.2 names, by "the PostScript language name of
 * the font" that Table 109 makes the `/BaseFont` of its dictionary.
 *
 * The clause is a sentence and a list:
 *
 * > The PostScript language names of 14 Type 1 fonts, known as the standard 14
 * > fonts, are as follows: Times-Roman, Helvetica, Courier, Symbol, Times-Bold,
 * > Helvetica-Bold, Courier-Bold, ZapfDingbats, Times-Italic,
 * > Helvetica-Oblique, Courier-Oblique, Times-BoldItalic,
 * > Helvetica-BoldOblique, Courier-BoldOblique.
 *
 * The entries below are that list, in that order, spelled as the clause spells
 * them — the spelling is the whole of the interface, since it is what a reader
 * matches against in order to find the font program: "These fonts, or their
 * font metrics and suitable substitution fonts, shall be available to the PDF
 * processor." A document naming one of them therefore carries no font program
 * and no metrics, which is what makes it the font a page can be written with
 * before this library can embed anything.
 *
 * They are an enumeration rather than strings because the set is closed by the
 * clause and the guarantee only holds for its members: `/BaseFont /Helvetica`
 * is a font every processor has, `/BaseFont /Helvetica-Light` is a name that
 * resolves to whatever the processor decides to substitute.
 */
public enum class Standard14Name(public val baseFont: PdfName) {
    TIMES_ROMAN(PdfName("Times-Roman")),
    HELVETICA(PdfName("Helvetica")),
    COURIER(PdfName("Courier")),
    SYMBOL(PdfName("Symbol")),
    TIMES_BOLD(PdfName("Times-Bold")),
    HELVETICA_BOLD(PdfName("Helvetica-Bold")),
    COURIER_BOLD(PdfName("Courier-Bold")),
    ZAPF_DINGBATS(PdfName("ZapfDingbats")),
    TIMES_ITALIC(PdfName("Times-Italic")),
    HELVETICA_OBLIQUE(PdfName("Helvetica-Oblique")),
    COURIER_OBLIQUE(PdfName("Courier-Oblique")),
    TIMES_BOLD_ITALIC(PdfName("Times-BoldItalic")),
    HELVETICA_BOLD_OBLIQUE(PdfName("Helvetica-BoldOblique")),
    COURIER_BOLD_OBLIQUE(PdfName("Courier-BoldOblique")),
    ;

    /**
     * Whether this font's character set is something other than the Latin one,
     * so that the Latin-text encodings of Annex D do not describe it — §9.6.5.1
     * draws the line and then names the two:
     *
     * > In PDF, a font is classified as either nonsymbolic or symbolic according
     * > to whether all of its characters are members of the standard Latin
     * > character set; see D.2, "Latin character set and encodings". … Symbolic
     * > fonts contain other character sets, to which the encodings mentioned
     * > previously ordinarily do not apply. Such font programs have built-in
     * > encodings that are usually unique to each font. The standard 14 fonts
     * > include two symbolic fonts, Symbol and ZapfDingbats, whose encodings and
     * > character sets are documented in Annex D.
     *
     * The distinction is not decoration: it decides which table turns a
     * character into a code. The twelve Latin fonts use [WinAnsiEncoding]
     * (Table D.2); these two use the built-in encodings of D.5, "Symbol set and
     * encoding" and D.6, "ZapfDingbats set and encoding", which are different
     * tables mapping different characters. Encoding `A` for Symbol through
     * Table D.2 would ask its font program for a glyph named `A`, which Symbol
     * does not have — its code 101 is `Alpha`.
     */
    public val symbolic: Boolean get() = this == SYMBOL || this == ZAPF_DINGBATS

    /**
     * The Type 1 font dictionary (Table 109) naming this font: which font it
     * is, and — for the twelve Latin ones — the encoding its character codes
     * are to be read by.
     *
     * ```
     * <</Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding>>
     * <</Type /Font /Subtype /Type1 /BaseFont /Symbol>>
     * ```
     *
     * `/Type` and `/Subtype` are Required and fixed by the table — "shall be
     * Font for a font dictionary", "shall be Type1 for a Type 1 font" — and
     * `/BaseFont` is this font's [baseFont].
     *
     * `/Encoding` is Optional and is "A specification of the font's character
     * encoding if different from its built-in encoding. The value of Encoding
     * shall be either the name of a predefined encoding (MacRomanEncoding,
     * MacExpertEncoding, or WinAnsiEncoding, as described in Annex D…)". This
     * library writes the name of the predefined encoding it encodes with:
     * [encode] turns a string into codes through Table D.2, so the entry states
     * the table the bytes on the page were produced by rather than leaving a
     * reader to guess it from the font program's own encoding array (§9.6.5.2:
     * "An Encoding entry in the PDF font dictionary, if present, shall override
     * a Type 1 font's mapping from character codes to character names").
     *
     * The two [symbolic] fonts get no `/Encoding`, which selects their built-in
     * one. That is the only correct answer for them: WinAnsiEncoding names Latin
     * glyphs Symbol and ZapfDingbats do not contain, and §9.6.5.2 says what a
     * reader does with a name a Type 1 program has no glyph for — "If an
     * encoding maps to a character name that does not exist in the Type 1 font
     * program, the .notdef glyph shall be substituted." Naming the wrong
     * encoding would therefore turn a page of symbols into a page of nothing,
     * with the file still valid.
     *
     * Of the entries not written:
     *
     * - `/FirstChar`, `/LastChar`, `/Widths` and `/FontDescriptor` are absent
     *   together, which is the only way they may be absent: "For the standard
     *   14 fonts, the entries FirstChar, LastChar, Widths, and FontDescriptor
     *   shall either all be present or all be absent. Ordinarily, these
     *   dictionary keys may be absent; specifying them enables a standard font
     *   to be overridden". Overriding is exactly what a document that does not
     *   embed a font program must not do, and the clause says who supplies the
     *   metrics instead: "For compatibility reasons PDF processors shall
     *   provide glyph widths and font descriptor data for those standard fonts
     *   for use in processing PDF files when the entries are absent."
     *
     *   The widths this library needs in order to *lay text out* are a separate
     *   thing from the widths a reader needs in order to *render* it: the first
     *   come from the AFM metrics of the standard fonts and
     *   are not written into the file at all unless they are being overridden;
     * - `/Name` is "deprecated in PDF 2.0", and this library writes PDF 2.0;
     * - `/ToUnicode` is Optional. It is what makes extraction exact for a font
     *   whose codes are not the ones a reader would guess (§9.10.3); for the
     *   twelve Latin fonts `/Encoding` has already said which codes these are.
     *
     * The dictionary is a direct object, so a caller may put it straight into
     * the `/Font` subdictionary of a resource dictionary (§7.8.3) or define it
     * as an indirect object and refer to it — Table 109 requires an indirect
     * reference for the `/FontDescriptor` this does not write, and for nothing
     * here. [FontCatalog.fontResources] does the latter, which is how a document
     * writes the fonts it used.
     */
    public fun fontDictionary(): PdfDict = PdfDict(
        buildMap {
            put(PdfName("Type"), PdfName("Font"))
            put(PdfName("Subtype"), PdfName("Type1"))
            put(PdfName("BaseFont"), baseFont)
            if (!symbolic) put(PdfName("Encoding"), PdfName(WinAnsiEncoding.NAME))
        },
    )

    /**
     * The character codes [text] is shown as, for the encoding [fontDictionary]
     * names — one byte per character, since "Glyphs in the font shall be
     * selected by single-byte character codes obtained from a string that is
     * shown by the text-showing operators" (§9.6.1).
     *
     * This is the other half of the `/Encoding` entry: the entry says which
     * table a reader shall map codes back through, and this produces the codes
     * through the same table. They cannot disagree, because they are one
     * transcription of Table D.2 ([WinAnsiEncoding]) read in its two directions.
     *
     * @throws UnencodableCharacterException at the first character Table D.2
     *   assigns no code to, naming it, its position and the remedy — never a
     *   substitute glyph and never a dropped one.
     * @throws UnsupportedOperationException for the two [symbolic] fonts, whose
     *   built-in encodings are D.5 and D.6.
     */
    public fun encode(text: String): ByteArray {
        if (symbolic) throw builtInEncodingNotWritten(text)
        return WinAnsiEncoding.encode(text, baseFont.name)
    }

    /**
     * Symbol and ZapfDingbats reached with a Latin-text encoding.
     *
     * Annex D.5 "Symbol set and encoding" and D.6 "ZapfDingbats set and
     * encoding" are the tables that would answer, and they are not transcribed
     * yet. Encoding `A` through Table D.2 instead would ask these fonts for a
     * glyph named `A`, which neither has — Symbol's code 101 octal is `Alpha` —
     * so it is refused outright rather than half-applied.
     */
    private fun builtInEncodingNotWritten(text: String): UnsupportedOperationException = UnsupportedOperationException(
        "cannot encode \"$text\" in ${baseFont.name}: it is one of the two symbolic fonts " +
            "of ISO 32000-2:2020 §9.6.5.1, whose character set and built-in encoding are " +
            "Annex D.5 \"Symbol set and encoding\" and D.6 \"ZapfDingbats set and encoding\" — " +
            "not the ${WinAnsiEncoding.NAME} of Table D.2, which names glyphs this font does not " +
            "have. Those tables are not written yet, and this library will not draw or measure a " +
            "character with the wrong glyph",
    )
}
