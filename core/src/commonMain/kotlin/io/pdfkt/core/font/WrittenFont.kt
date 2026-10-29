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
import io.pdfkt.core.obj.PdfObject
import io.pdfkt.core.obj.PdfRef

// ISO 32000-2:2020 §9.4.3 "Text-showing operators", §9.6.4 "Type 3 fonts",
// §9.7 "Composite fonts", §9.9 "Embedded font programs"

/**
 * A font this library writes into the file itself, and which therefore hands
 * out the codes the file's pages show it with.
 *
 * The two are one thing, which is why they are one interface. A code is only
 * meaningful against the encoding the font dictionary declares — "the glyphs
 * … shall be selected by … character codes" through the encoding of §9.6.5 for a
 * simple font, through the CMap of §9.7.5 for a composite one — so whatever
 * decides what the dictionary says has to be what answers [encode]. Splitting
 * them is how a page comes to show bytes the font it names does not map.
 *
 * It also fixes the order: draw first, write afterwards. Both implementations
 * write only what the pages actually showed — a subset of the program for
 * [EmbeddedFont], one CharProcs entry per emoji
 * for [ColorEmojiFont] — so [fontDictionary] closes the font, and a glyph
 * offered after it is refused rather than dropped.
 *
 * [FontRef.Standard14] implements none of this: the fourteen embed nothing,
 * their encoding is Annex D's table and their dictionary says the same thing
 * whatever a document drew.
 */
internal interface WrittenFont : GlyphSpaceMetrics {
    /**
     * The character codes [text] is shown as — what a text-showing operator's
     * string operand carries, "interpreted as a sequence of character codes"
     * (§9.4.3).
     *
     * @throws UnencodableCharacterException at the first character this font
     *   cannot show, naming it, its position and the remedy.
     */
    fun encode(text: String): ByteArray

    /**
     * How [text] is drawn: the codes it is shown as, and — for a font that
     * shapes — where each glyph goes and what text the glyphs stand for.
     *
     * @param rightToLeft whether the text is at an odd embedding level of the
     *   bidirectional algorithm, and so drawn from right to left: its glyphs
     *   come out in the order they are drawn, left to right.
     */
    fun show(text: String, rightToLeft: Boolean): List<TextShow> =
        listOf(TextShow.Show(listOf(io.pdfkt.core.content.TextArrayElement.Codes(encode(text)))))

    /**
     * The font dictionary this font is written into the file as, over the glyphs
     * the pages showed.
     *
     * @param define makes an object indirect and hands back the reference —
     *   "all streams shall be indirect objects" (§7.3.8), and both paths write
     *   streams.
     */
    fun fontDictionary(define: (PdfObject) -> PdfRef): PdfDict
}
