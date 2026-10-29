/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.font

import io.pdfkt.core.content.TextArrayElement
import io.pdfkt.core.obj.PdfDict
import io.pdfkt.core.obj.PdfName
import io.pdfkt.core.obj.PdfObject
import io.pdfkt.core.obj.PdfRef
import io.pdfkt.core.text.UnicodeProperties

// ISO 32000-2:2020 §7.8.3 "Resource dictionaries" (Table 34),
// §9.6.2.1 "Type 1 fonts — General" (Table 109),
// §9.6.2.2 "Standard Type 1 fonts (standard 14 fonts) (PDF 1.0-1.7)",
// §9.7 "Composite fonts"

/**
 * The fonts of one document: which [FontRef] the document uses, what each of
 * them measures with, and what each is called in a resource dictionary.
 *
 * A [FontRef] is a name a caller writes; measuring with it means parsing
 * something — an AFM table, a font program of several megabytes — and that parse
 * is worth doing once. Doing it once *per document* is the whole design:
 * resolution (`FontRef` → parsed program or AFM table) happens once per
 * document in a **document-scoped catalog**. No global caches: a global font
 * cache needs atomic shared state; a catalog that lives and dies
 * with one render has no concurrency to manage.
 *
 * So: one instance per document, created by whoever starts the render, discarded
 * with it. Nothing here is `object`, nothing is `companion`-scoped, and the only
 * state is the map below. That is what makes the class safe without a lock — two
 * documents rendering at once are two catalogs that share nothing, and one
 * catalog belongs to one render and is not thread-safe within it, exactly like
 * `PdfDocumentWriter`.
 *
 * It is also the document's font inventory: an emitter walks [fonts] to write
 * the font dictionaries and, for each supplied font, the subset of the program
 * the pages turned out to need.
 *
 * ## What resolution keys on
 *
 * What the reference *names*, never the instance it arrived in: a layout resolves
 * a font once per run of text and has no reason to hold objects. A
 * [FontRef.Standard14] is named by its member of the fourteen, a
 * [FontRef.Supplied] by its [FontRef.Supplied.id].
 */
public class FontCatalog {
    /**
     * The fonts resolved so far, keyed as [keyOf] and in first-use order — the
     * order [fonts] reports and the order the resource names run in.
     */
    private val entries: MutableMap<String, ResolvedFont> = mutableMapOf()

    /**
     * The document's entry for [ref], parsing what it names on the first call and
     * returning that same entry on every later one.
     *
     * @throws IllegalArgumentException if [ref] is a [FontRef.Supplied] whose id
     *   this document already resolved for a *different* font program. An id
     *   names one program within one document; the alternative to refusing is a
     *   document that silently draws the second font with the first one's glyphs
     *   — wrong on every page, and reported as a success.
     */
    public fun resolve(ref: FontRef): ResolvedFont {
        val key = keyOf(ref)
        val existing = entries[key]
        if (existing != null) {
            requireSameProgram(existing.ref, ref)
            return existing
        }
        val resolved = resolvedFontOf(ref, PdfName("$RESOURCE_NAME_PREFIX${entries.size + 1}"))
        entries[key] = resolved
        return resolved
    }

    /**
     * What the layout engine asks for: how [ref] measures in this document. The
     * same call as [resolve], with the part of the answer L4 has any use for —
     * resource names and font programs are the emitter's business.
     */
    public fun metricsOf(ref: FontRef): FontMetrics = resolve(ref).metrics

    /**
     * Whether [ref] has a glyph for [codePoint], asked without making it one of
     * this document's fonts: a fallback that is asked and turns out not to have
     * the character is not written into the file.
     */
    internal fun canDraw(ref: FontRef, codePoint: Int): Boolean = when (ref) {
        is FontRef.Standard14 -> try {
            ref.name.encode(codePointToString(codePoint))
            true
        } catch (_: UnencodableCharacterException) {
            false
        } catch (_: UnsupportedOperationException) {
            false
        }

        is FontRef.Supplied -> {
            val written = entries[keyOf(ref)]?.written as? EmbeddedFont
            (written ?: probes.getOrPut(ref.id) { EmbeddedFont(ref) }).canDraw(codePoint)
        }

        is FontRef.ColorEmoji, is FontRef.RasterizedEmoji -> false
    }

    /** Supplied programs parsed only to be asked what they cover — see [canDraw]. */
    private val probes: MutableMap<String, EmbeddedFont> = mutableMapOf()

    /**
     * Every font this document has resolved, in the order it was first used.
     *
     * A snapshot: resolving more fonts afterwards does not change the list that
     * was handed out.
     */
    public fun fonts(): List<ResolvedFont> = entries.values.toList()

    /**
     * The `/Font` subdictionary of a resource dictionary (Table 34: "A
     * dictionary that maps resource names to font dictionaries") for every font
     * this document has resolved — the one call that turns a catalog into the
     * part of a page a content stream's `Tf` operator can reach.
     *
     * §7.8.3 states the need it answers: "In some cases, an operator shall refer
     * to a PDF object that is defined outside the content stream, such as a font
     * dictionary … This shall be accomplished by defining such objects as named
     * resources and referring to them by name from within the content stream."
     * The names are the [ResolvedFont.resourceName]s the catalog handed out, so
     * the name a page names a font by is the name the layout drew it under.
     *
     * Each font dictionary becomes an indirect object, which is the form
     * EXAMPLE 2 of the clause shows — `<</Font <</F5 6 0 R /F6 8 0 R …>>>>` —
     * and the form that lets several pages share one font. Defining is the
     * caller's, through [define]: an object number is only unique relative to
     * the file handing it out (§7.3.10), and this layer knows nothing about
     * files. `PdfDocumentWriter::define` is what a document passes.
     *
     * A catalog that resolved nothing yields an empty dictionary; a page with no
     * fonts should leave `/Font` out rather than write one, since Table 34 marks
     * every entry Optional.
     *
     * Call this once the document has drawn everything: a supplied font is
     * written as a subset of the glyphs the pages showed ([EmbeddedFont]), and a
     * page drawn after the subset was cut would show glyphs the file does not
     * carry.
     */
    public fun fontResources(define: (PdfObject) -> PdfRef): PdfDict =
        PdfDict(entries.values.associate { font -> font.resourceName to define(font.fontDictionary(define)) })

    /**
     * The three font paths, and the one place the choice between them
     * is made. It is a `when` over a sealed interface with one arm each, so the
     * compiler is what guarantees a further path cannot appear quietly:
     *
     * | Path | When | PDF machinery |
     * |---|---|---|
     * | Simple | [FontRef.Standard14] | Type 1 dictionary, a byte encoding, AFM widths, nothing embedded (§9.6.2.2) |
     * | Embedded | [FontRef.Supplied] | Type 0 / CIDFontType2 / Identity-H, subset, `FontFile2`,
     * `ToUnicode` (§9.7, §9.10.3) |
     * | Colour | [FontRef.ColorEmoji] | Type 3 dictionary, CharProcs painting the `CBDT` bitmaps,
     * `ToUnicode` (§9.6.4) |
     * | Colour | [FontRef.RasterizedEmoji] | the same Type 3 dictionary, over the pictures a
     * caller's rasterizer drew (§9.6.4) |
     *
     * The two supplied paths are each one object that both measures and writes,
     * because for a font whose program is in the file those are the same question
     * asked twice: the widths a layout measures a line with and the array a
     * reader advances by come out of one table — `hmtx` for the embedded path,
     * the strike's metrics for the colour one — and the glyphs a page showed are
     * what gets written. Keeping them apart is how the two drift.
     */
    private fun resolvedFontOf(ref: FontRef, resourceName: PdfName): ResolvedFont = when (ref) {
        is FontRef.Standard14 ->
            ResolvedFont(ref, resourceName, TextSpaceMetrics(Standard14Metrics(ref.name)), written = null)

        is FontRef.Supplied ->
            EmbeddedFont(ref).let { ResolvedFont(ref, resourceName, TextSpaceMetrics(it), written = it) }

        is FontRef.ColorEmoji ->
            ColorEmojiFont(ref.id, ref.bytes).let {
                ResolvedFont(ref, resourceName, TextSpaceMetrics(it), written = it)
            }

        is FontRef.RasterizedEmoji ->
            RasterizedEmojiFont(ref.id, ref.rasterizer, ref.emPixels).let {
                ResolvedFont(ref, resourceName, TextSpaceMetrics(it), written = it)
            }
    }

    private companion object {
        /**
         * `/F1`, `/F2`, … — the names the fonts of this document are known by in
         * the `/Font` subdictionary of a resource dictionary (§7.8.3).
         *
         * Any name would do: the clause fixes neither the spelling nor a
         * numbering, and "The scope of a resource name shall be local to a
         * particular content stream", so a name only has to be unique among the
         * fonts of the resource dictionary it appears in. Numbering them per
         * document rather than per stream is a convention of this catalog — it
         * makes a name mean the same font throughout a file, which is one fewer
         * thing to get wrong when a page's resource dictionary is assembled from
         * the subset of fonts that page used.
         */
        const val RESOURCE_NAME_PREFIX: String = "F"

        /**
         * What a reference names, as a key. The two prefixes are two namespaces
         * that cannot collide, so a supplied font whose id happens to be
         * `Helvetica` is still its own font.
         */
        fun keyOf(ref: FontRef): String = when (ref) {
            is FontRef.Standard14 -> "standard-14:${ref.name.baseFont.name}"
            is FontRef.Supplied -> "supplied:${ref.id}"
            is FontRef.ColorEmoji -> "colour-emoji:${ref.id}"
            is FontRef.RasterizedEmoji -> "rasterized-emoji:${ref.id}"
        }

        /**
         * Two references that key the same shall be the same font. Only the
         * references carrying a program can break this: a [FontRef.Standard14]
         * carries nothing but the choice of font, while an id is a name a caller
         * picked and can reuse by accident.
         *
         * The comparison is by identity first — the usual case is one array
         * handed round — and by contents otherwise, which is the only answer
         * that is actually true. It costs a scan of the program on a call that
         * supplies an equal copy; supplying the *same* array, or resolving a
         * font already resolved, costs nothing.
         */
        fun requireSameProgram(resolved: FontRef, incoming: FontRef) {
            if (resolved is FontRef.RasterizedEmoji && incoming is FontRef.RasterizedEmoji) {
                requireSameRasterizer(resolved, incoming)
                return
            }
            val existing = programOf(resolved) ?: return
            val arriving = programOf(incoming) ?: return
            require(existing === arriving || existing.contentEquals(arriving)) {
                "two different font programs supplied under the id \"${idOf(incoming)}\" " +
                    "(${existing.size} bytes, then ${arriving.size}): an id names one font " +
                    "program within one document, so this document would draw one of the two with the " +
                    "other's glyphs. Give the second program an id of its own"
            }
            if (resolved is FontRef.Supplied && incoming is FontRef.Supplied) {
                require(resolved.index == incoming.index) {
                    "fonts ${resolved.index} and ${incoming.index} of one font collection supplied under " +
                        "the id \"${incoming.id}\": an id names one font within one document, so this " +
                        "document would draw one of the two with the other's glyphs. Give each font of " +
                        "the collection an id of its own"
                }
            }
        }

        /**
         * The same rule for the reference that carries no program but a
         * function: an id names one source of pictures within one document.
         *
         * There is nothing to compare two rasterizers by but identity — a
         * `fun interface` is a function, and two lambdas that draw the same
         * emoji identically are still two objects — so this refuses more than
         * the byte comparison above does, and refuses the case it exists for: a
         * document that would draw the second rasterizer's emoji with the
         * first's pictures. The size is part of it, since the pictures of one of
         * these fonts are all at one resolution.
         */
        fun requireSameRasterizer(resolved: FontRef.RasterizedEmoji, incoming: FontRef.RasterizedEmoji) {
            require(resolved.rasterizer === incoming.rasterizer && resolved.emPixels == incoming.emPixels) {
                "two different emoji rasterizers supplied under the id \"${incoming.id}\" (at " +
                    "${resolved.emPixels} and ${incoming.emPixels} pixels to the em): an id names one " +
                    "source of pictures within one document, so this document would draw the second " +
                    "one's emoji with the first one's pictures. Give the second rasterizer an id of " +
                    "its own"
            }
        }

        /** The program a reference carries, and null for the two that carry none. */
        fun programOf(ref: FontRef): ByteArray? = when (ref) {
            is FontRef.Standard14 -> null
            is FontRef.Supplied -> ref.bytes
            is FontRef.ColorEmoji -> ref.bytes
            is FontRef.RasterizedEmoji -> null
        }

        /** What a reference calls its program, for the message above. */
        fun idOf(ref: FontRef): String = when (ref) {
            is FontRef.Standard14 -> ref.name.baseFont.name
            is FontRef.Supplied -> ref.id
            is FontRef.ColorEmoji -> ref.id
            is FontRef.RasterizedEmoji -> ref.id
        }
    }
}

/**
 * One font of one document: what it was asked for by, what it measures with, and
 * what it is called in a resource dictionary.
 *
 * Obtained from [FontCatalog.resolve] and identified by *being* that entry —
 * two catalogs asked for the same [FontRef] hand out two of these, because they
 * are two documents and the parsed font behind each belongs to one of them.
 *
 * @property ref the reference this was resolved from.
 * @property resourceName the name this font is known by in the `/Font`
 *   subdictionary of a page's resource dictionary (§7.8.3) — the name a content
 *   stream's `Tf` operator selects it with.
 * @property metrics what the layout engine measures with.
 */
public class ResolvedFont internal constructor(
    public val ref: FontRef,
    public val resourceName: PdfName,
    public val metrics: FontMetrics,
    /**
     * The font behind this entry that writes itself into the file, for the two
     * references that carry a program, and null for one of the fourteen — which
     * embeds nothing.
     */
    internal val written: WrittenFont?,
) {
    /**
     * The font dictionary this font is written into the file as — Table 109 for
     * the simple path, Table 119 and everything under it for the embedded one,
     * Table 110 for the colour one — and the object [FontCatalog.fontResources]
     * names [resourceName] after.
     *
     * The `when` is the same paths [FontCatalog] resolves by, so a font is
     * written by the machinery it was measured by and there is no further
     * possibility for the two to drift into.
     *
     * @param define makes an object indirect. A simple font needs none of it; the
     *   embedded path defines the CIDFont, the descriptor and three streams
     *   through it (§9.7.6.1, §9.8, §9.9), and the colour path one stream per
     *   glyph and per picture (§9.6.4).
     */
    public fun fontDictionary(define: (PdfObject) -> PdfRef): PdfDict = when (ref) {
        is FontRef.Standard14 -> ref.name.fontDictionary()
        is FontRef.Supplied, is FontRef.ColorEmoji, is FontRef.RasterizedEmoji ->
            writtenFont().fontDictionary(define)
    }

    /**
     * The character codes [text] is shown as — what a text-showing operator's
     * string operand carries, "interpreted as a sequence of character codes"
     * (§9.4.3).
     *
     * The codes come from the encoding [fontDictionary] declares, so a page
     * drawn through this call and a reader following that entry agree on which
     * glyph every byte selects: one byte per character through Annex D for the
     * fourteen, two bytes of glyph index through `Identity-H` for a supplied
     * font (§9.7.5.2), and one byte of the font's own `Differences` array for a
     * colour font (§9.6.5.3).
     *
     * @throws UnencodableCharacterException at the first character this font
     *   cannot show, naming it, its position and the remedy.
     * @throws UnsupportedOperationException for the two symbolic standard fonts
     *   — see [Standard14Name.encode].
     */
    public fun encode(text: String): ByteArray = when (ref) {
        is FontRef.Standard14 -> ref.name.encode(text)
        is FontRef.Supplied, is FontRef.ColorEmoji, is FontRef.RasterizedEmoji -> writtenFont().encode(text)
    }

    /**
     * [text] as the font draws it — see [WrittenFont.show]. A standard font
     * shapes nothing: a right-to-left run of it is its characters in reverse,
     * the mirrored ones mirrored (UAX #9 §L4), marked with the text in reading
     * order.
     */
    internal fun show(text: String, rightToLeft: Boolean): List<TextShow> = when (ref) {
        is FontRef.Standard14 -> {
            if (!rightToLeft) {
                listOf(TextShow.Show(listOf(TextArrayElement.Codes(ref.name.encode(text)))))
            } else {
                val reversed = buildString {
                    val codePoints = ArrayList<Int>()
                    forEachCodePoint(text) { _, codePoint -> codePoints += codePoint }
                    for (codePoint in codePoints.reversed()) {
                        append(codePointToString(UnicodeProperties.mirror(codePoint) ?: codePoint))
                    }
                }
                listOf(
                    TextShow.BeginActualText(text),
                    TextShow.Show(listOf(TextArrayElement.Codes(ref.name.encode(reversed)))),
                    TextShow.EndActualText,
                )
            }
        }
        is FontRef.Supplied, is FontRef.ColorEmoji, is FontRef.RasterizedEmoji -> writtenFont().show(text, rightToLeft)
    }

    override fun toString(): String = "ResolvedFont(/${resourceName.name} -> $ref)"

    private fun writtenFont(): WrittenFont = checkNotNull(written) {
        "the supplied font \"$ref\" was resolved without a font program to write. Entries are built " +
            "by FontCatalog.resolve, which builds one for every reference that carries a program"
    }
}
