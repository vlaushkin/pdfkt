/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.emit

import io.pdfkt.core.content.ContentStreamBuilder
import io.pdfkt.core.doc.PdfDocumentWriter
import io.pdfkt.core.doc.pdfRectangle
import io.pdfkt.core.font.FontCatalog
import io.pdfkt.core.image.ImageCatalog
import io.pdfkt.core.image.ResolvedImage
import io.pdfkt.core.model.Rect
import io.pdfkt.core.model.Rgb
import io.pdfkt.core.model.Size
import io.pdfkt.core.obj.PdfArray
import io.pdfkt.core.obj.PdfDict
import io.pdfkt.core.obj.PdfInt
import io.pdfkt.core.obj.PdfName
import io.pdfkt.core.obj.PdfObject
import io.pdfkt.core.obj.PdfString
import io.pdfkt.core.plan.PagePlan
import io.pdfkt.core.plan.PlacedItem
import io.pdfkt.core.plan.PlacedRun
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// ISO 32000-2:2020 §7.8.3 "Resource dictionaries", §8.4.2 "Graphics state stack",
// §8.5.2 "Path construction", §8.5.3 "Path-painting operators",
// §8.6.8 "Colour operators", §8.8 "External objects", §8.9.4 "Image coordinate system",
// §9.4 "Text objects", §12.5.6.5 "Link annotations", §12.6.4.8 "URI actions"

/**
 * The last stage of the pipeline `blocks + theme → resolve → fragment → plan →
 * emit`: it walks [PagePlan]s and writes the pages they describe.
 *
 * It is deliberately the dumbest part of the layout engine:
 *
 * > **Emission becomes trivial** — a dumb walk over the plan. All the subtlety
 * > lives in one place.
 *
 * So there is no geometry here beyond turning a box into the operands of an
 * operator, no measuring — the runs carry their widths — no line breaking, no
 * fitting, and no decision that could disagree with the plan. Every `when` over
 * a plan type is exhaustive by the compiler, which is what makes "the emitter
 * silently skipped an item" a compile error rather than a blank spot on a page.
 *
 * ## Why the pages are written at [finish]
 *
 * Two things about a document are only known once every page has been planned:
 * which glyphs a supplied font must carry — a subset is cut to what the pages
 * showed — and what the resource dictionaries
 * name. `FontCatalog.fontResources` says as much: "Call this once the document
 * has drawn everything." So [emit] builds a page's content stream immediately,
 * which is where a broken plan is caught, and holds it; [finish] writes the font
 * and image objects once, gives every page a resource dictionary naming only
 * what that page used, and ends the document.
 *
 * That per-page filtering is §7.8.3's own advice, followed rather than restated:
 * "A PDF writer should only include resource definitions for resources that are
 * actually referenced by the content streams of the associated page."
 *
 * ## Using it
 *
 * ```
 * val emitter = PlanEmitter(PdfDocumentWriter(sink))
 * emitter.emit(pagePlan)
 * emitter.finish(fileIdentifier = …)
 * ```
 *
 * The catalogs are the emitter's own by default and can be handed in instead,
 * for a caller that resolved fonts while planning — measuring a line needs
 * `FontCatalog.metricsOf`, so the fragmenter above holds one, and it should be
 * the same one. Both are per document, exactly as they are per emitter.
 *
 * One instance writes the pages of one document, is not thread-safe, and is
 * finished exactly once. While it is in use it is the only thing that may add
 * pages to [document]: it appends them all at [finish], and a page added
 * elsewhere in between would come out ahead of them.
 */
public class PlanEmitter(
    private val document: PdfDocumentWriter,
    public val fonts: FontCatalog = FontCatalog(),
    public val images: ImageCatalog = ImageCatalog(),
) {
    /** The pages planned so far, in the order [emit] took them. */
    private val pages: MutableList<EmittedPage> = mutableListOf()

    private var finished: Boolean = false

    /**
     * Turns one plan into the content stream of one page and holds it until
     * [finish].
     *
     * Everything that can go wrong with a plan goes wrong here, at the item that
     * caused it: a character the font cannot show
     * (`UnencodableCharacterException`), a coordinate that is not a number
     * (`PdfReal`), an image whose id names a different image (`ImageCatalog`).
     *
     * @throws IllegalStateException if this emitter is finished.
     */
    public fun emit(plan: PagePlan) {
        check(!finished) { FINISHED }
        val content = ContentStreamBuilder()
        val fontNames = mutableSetOf<PdfName>()
        val imageNames = mutableSetOf<PdfName>()
        val annotations = mutableListOf<PdfDict>()
        for (item in plan.items) {
            when (item) {
                is PlacedItem.Text -> drawText(content, item, fontNames)
                is PlacedItem.Rect -> drawRectangle(content, item)
                is PlacedItem.Image -> drawImage(content, item, imageNames)
                is PlacedItem.Link -> annotations += linkAnnotation(item)
            }
        }
        pages += EmittedPage(plan.size, content.build(), fontNames, imageNames, annotations)
    }

    /**
     * Writes every page emitted so far, the fonts and images they used, and the
     * document around them.
     *
     * @param fileIdentifier the `/ID` of the trailer, per
     *   [PdfDocumentWriter.finish].
     * @param info the document information dictionary (§14.3.3), or null. It
     *   becomes an object of the document here rather than at the caller,
     *   because Table 15 requires `/Info` to be an indirect reference and the
     *   objects of this document are written through this emitter.
     * @throws IllegalStateException if no plan was emitted — a document with no
     *   pages is refused a layer down, and refusing it here says which layer the
     *   caller is missing a page from.
     */
    public fun finish(fileIdentifier: ByteArray, info: PdfDict? = null) {
        check(!finished) { FINISHED }
        check(pages.isNotEmpty()) {
            "no page plan was emitted: a document is written in order to be displayed, and this one " +
                "would have nothing to display"
        }
        val fontResources = fonts.fontResources { value -> document.define(value) }
        val imageResources = images.imageResources { value -> document.define(value) }
        for (page in pages) {
            document.addPage(
                mediaBox = mediaBoxOf(page.size),
                resources = page.resources(fontResources, imageResources),
                content = page.content,
                annotations = page.annotations,
            )
        }
        val information = info?.let { dictionary -> document.define(dictionary) }
        finished = true
        document.finish(fileIdentifier = fileIdentifier, info = information)
    }

    /**
     * A line of text: one text object (§9.4.1), one run after another, and the
     * decorations afterwards.
     *
     * Each run sets its own font, size and colour and then places itself with
     * `Tm`, which "shall not be concatenated onto the current text matrix, but
     * shall replace it" (Table 106) — an absolute position per run, so no run's
     * geometry depends on what the previous one drew. The alternative, moving by
     * the difference with `Td`, would make the plan's x-offsets a chain that a
     * single wrong width breaks for the rest of the line.
     *
     * The colour operators sit inside the text object, which §8.6.8 allows:
     * "Colour operators may appear at the page description level or inside text
     * objects." The decorations do not — they are paths, and a path object
     * "shall not begin inside" a text object (§8.2, Figure 9) — so they are
     * drawn after the `ET`, still inside the `q` … `Q` that isolates this item's
     * colour from the next.
     */
    private fun drawText(content: ContentStreamBuilder, item: PlacedItem.Text, fontNames: MutableSet<PdfName>) {
        content.save()
        content.beginText()
        for (run in item.runs) {
            val font = fonts.resolve(run.font)
            fontNames += font.resourceName
            content.nonStrokingColor(run.color)
            content.textFont(font.resourceName, run.size.value.toDouble())
            content.textMatrix(
                1.0,
                0.0,
                0.0,
                1.0,
                (item.x + run.xOffset).value.toDouble(),
                item.y.value.toDouble(),
            )
            content.showText(font.encode(run.text))
        }
        content.endText()
        for (run in item.runs) drawDecorations(content, item, run)
        content.restore()
    }

    /**
     * The underlines and strikes of one run, as filled rectangles the width the
     * run reported.
     *
     * PDF draws neither, and neither is a property of the text: a decoration is
     * a rectangle from the run's start to its end, one `thickness` tall, with
     * its bottom edge at `offset` from the baseline. Filling it —
     * rather than stroking a line — is why the thickness is the whole height and
     * not a line width straddling a path (§8.4.3.2).
     */
    private fun drawDecorations(content: ContentStreamBuilder, item: PlacedItem.Text, run: PlacedRun) {
        for (decoration in run.decorations) {
            content.nonStrokingColor(run.color)
            content.rectangle(
                (item.x + run.xOffset).value.toDouble(),
                (item.y + decoration.offset).value.toDouble(),
                run.width.value.toDouble(),
                decoration.thickness.value.toDouble(),
            )
            content.fill()
        }
    }

    /**
     * A rectangle: `re` (Table 58) and the painting operator its fill and stroke
     * ask for.
     *
     * Which operator is the whole of the logic here. `f` fills, `S` strokes, and
     * `B` does both — "This operator shall produce the same result as
     * constructing two identical path objects, painting the first with f and the
     * second with S" (Table 59) — so a bordered box is one path rather than two.
     * The fourth case, neither, cannot arrive: `PlacedItem.Rect` refuses it.
     */
    private fun drawRectangle(content: ContentStreamBuilder, item: PlacedItem.Rect) {
        content.save()
        item.fill?.let { fill -> content.nonStrokingColor(fill) }
        item.stroke?.let { stroke ->
            content.strokingColor(stroke.color)
            content.lineWidth(stroke.width.value.toDouble())
        }
        content.rectangle(
            item.box.x.value.toDouble(),
            item.box.y.value.toDouble(),
            item.box.width.value.toDouble(),
            item.box.height.value.toDouble(),
        )
        when {
            item.fill != null && item.stroke != null -> content.fillAndStroke()
            item.fill != null -> content.fill()
            else -> content.stroke()
        }
        content.restore()
    }

    /**
     * An image: the matrix that puts it where the plan says, and `Do`.
     *
     * §8.9.4 fixes what an image XObject occupies before any transformation —
     * "the unit square of user space, bounded by user coordinates (0, 0) and
     * (1, 1), corresponds to the boundary of the image in image space" — and how
     * it is placed: "An image can be placed on the output page in any position,
     * orientation, and size by using the cm operator to modify the current
     * transformation matrix (CTM) so as to map the unit square of user space to
     * the rectangle or parallelogram in which the image shall be painted.
     * Typically, this is done within a pair of q and Q operators to isolate the
     * effect of the transformation".
     *
     * That is exactly what this writes: `q`, one `cm`, `Do`, `Q`.
     */
    private fun drawImage(content: ContentStreamBuilder, item: PlacedItem.Image, imageNames: MutableSet<PdfName>) {
        val image: ResolvedImage = images.resolve(item.image)
        imageNames += image.resourceName
        content.save()
        val matrix = imageMatrix(item.box, item.rotation)
        content.concat(matrix[0], matrix[1], matrix[2], matrix[3], matrix[4], matrix[5])
        content.paintXObject(image.resourceName)
        content.restore()
    }

    /**
     * A link annotation over [PlacedItem.Link.box], with the URI action §12.6.4.8
     * describes.
     *
     * Table 176 needs one entry of its own, `/Subtype /Link`, and Table 166 two
     * more: `/Rect`, "The annotation rectangle, defining the location of the
     * annotation on the page in default user space units", written in the
     * `[llx lly urx ury]` form of §7.9.5. `/Type /Annot` is optional and written
     * because it costs four bytes to keep the object self-describing.
     *
     * `/A` is "An action that shall be performed when the link annotation is
     * activated" (Table 176) and holds Table 210's two required entries: `/S
     * /URI` and the target, "The uniform resource identifier to resolve, encoded
     * in UTF-8".
     *
     * `/Border` is written as `[0 0 0]` against a default of `[0 0 1]` (Table
     * 166) — "if the border width is 0, no border is drawn". A reader's own
     * one-unit box around every link is not something the layout asked for; what
     * a link looks like is the text item the plan drew beside it.
     *
     * No appearance stream is written, and none is needed: Table 166 requires
     * one of every annotation except "Annotations whose Subtype value is Popup,
     * Projection or Link".
     */
    private fun linkAnnotation(item: PlacedItem.Link): PdfDict = PdfDict(
        mapOf(
            PdfName("Type") to PdfName("Annot"),
            PdfName("Subtype") to PdfName("Link"),
            PdfName("Rect") to
                pdfRectangle(
                    item.box.left.value.toDouble(),
                    item.box.bottom.value.toDouble(),
                    item.box.right.value.toDouble(),
                    item.box.top.value.toDouble(),
                ),
            PdfName("Border") to PdfArray(listOf(PdfInt(0), PdfInt(0), PdfInt(0))),
            PdfName("A") to
                PdfDict(
                    mapOf(
                        PdfName("S") to PdfName("URI"),
                        PdfName("URI") to PdfString(item.target.encodeToByteArray()),
                    ),
                ),
        ),
    )

    private companion object {
        const val FINISHED: String =
            "this emitter is finished; one emitter writes the pages of one document"
    }
}

/** One page, built and waiting for the resource dictionaries [PlanEmitter.finish] writes. */
private class EmittedPage(
    val size: Size,
    val content: ByteArray,
    val fontNames: Set<PdfName>,
    val imageNames: Set<PdfName>,
    val annotations: List<PdfDict>,
) {
    /**
     * This page's resource dictionary (§7.8.3): the entries of the document-wide
     * subdictionaries whose names this page's content stream actually uses.
     *
     * A page that used no font writes no `/Font` and one that drew no image no
     * `/XObject` — Table 34 marks every entry Optional, and §7.8.3 asks for
     * exactly this restraint.
     */
    fun resources(fonts: PdfDict, images: PdfDict): PdfDict {
        val entries = mutableMapOf<PdfName, PdfObject>()
        val pageFonts = fonts.entries.filterKeys { name -> name in fontNames }
        if (pageFonts.isNotEmpty()) entries[PdfName("Font")] = PdfDict(pageFonts)
        val pageImages = images.entries.filterKeys { name -> name in imageNames }
        if (pageImages.isNotEmpty()) entries[PdfName("XObject")] = PdfDict(pageImages)
        return PdfDict(entries)
    }
}

/** The `/MediaBox` of a page of this size: `[0 0 width height]` (§7.9.5, Table 31). */
private fun mediaBoxOf(size: Size): PdfArray =
    pdfRectangle(0.0, 0.0, size.width.value.toDouble(), size.height.value.toDouble())

/** `rg` with a colour of the plan (§8.6.8). */
private fun ContentStreamBuilder.nonStrokingColor(color: Rgb) {
    nonStrokingColor(color.red.toDouble(), color.green.toDouble(), color.blue.toDouble())
}

/** `RG` with a colour of the plan (§8.6.8). */
private fun ContentStreamBuilder.strokingColor(color: Rgb) {
    strokingColor(color.red.toDouble(), color.green.toDouble(), color.blue.toDouble())
}

/**
 * The six operands of the `cm` that maps the unit square of §8.9.4 onto [box],
 * turned by [rotation] degrees counter-clockwise about the box's centre.
 *
 * §8.9.4's example builds the same placement out of three concatenations —
 * translate, rotate, scale, applied to the unit square in the reverse of the
 * order they are written — and one matrix is their product. Writing the product
 * keeps the arithmetic in one place where it is testable against the degenerate
 * case: at a rotation of zero it is `width 0 0 height x y`, the plain scale and
 * translate.
 *
 * Deriving it: the unit square is first centred (subtract ½ from both
 * coordinates), then scaled by the box's extent, then rotated, then moved to the
 * box's centre. With `c = cos θ` and `s = sin θ` that product is
 *
 * ```
 * [ w·c        w·s       0 ]
 * [ -h·s       h·c       0 ]
 * [ cx-(a+c)/2 cy-(b+d)/2 1 ]
 * ```
 *
 * where the translation is what carries the centred square back to the centre of
 * the box.
 *
 * A term of `-height × sin 0` comes out as negative zero, which needs no
 * handling here: §7.3.3 has one zero and the serializer writes both of them `0`.
 */
private fun imageMatrix(box: Rect, rotation: Float): DoubleArray {
    val turn = turnOf(rotation)
    val cosine = turn[0]
    val sine = turn[1]
    val width = box.width.value.toDouble()
    val height = box.height.value.toDouble()
    val a = width * cosine
    val b = width * sine
    val c = -height * sine
    val d = height * cosine
    val centreX = ((box.left + box.right) / 2.0f).value.toDouble()
    val centreY = ((box.bottom + box.top) / 2.0f).value.toDouble()
    return doubleArrayOf(a, b, c, d, centreX - (a + c) / 2.0, centreY - (b + d) / 2.0)
}

/**
 * The cosine and the sine of an angle given in degrees, with the four quarter
 * turns answered exactly.
 *
 * They have to be: `cos(π ⁄ 2)` in binary floating point is 6.12 × 10⁻¹⁷ and not
 * zero, and §7.3.3 forbids exponential notation — so a photo turned upright by
 * its EXIF orientation, which is always a quarter turn, would carry
 * `0.0000000000000000612…` into the `cm` of every page it appears on. The number
 * is noise either way; writing thirty digits of it is not.
 *
 * Any other angle goes through the library functions, where the noise is the
 * true answer's own.
 */
private fun turnOf(degrees: Float): DoubleArray {
    val normalized = degrees.toDouble().mod(FULL_TURN)
    return when (normalized) {
        0.0 -> doubleArrayOf(1.0, 0.0)
        QUARTER_TURN -> doubleArrayOf(0.0, 1.0)
        STRAIGHT_ANGLE -> doubleArrayOf(-1.0, 0.0)
        THREE_QUARTER_TURN -> doubleArrayOf(0.0, -1.0)
        else -> {
            val radians = normalized * PI / STRAIGHT_ANGLE
            doubleArrayOf(cos(radians), sin(radians))
        }
    }
}

/** Degrees in a straight angle — the conversion `rotation × π / 180` uses. */
private const val STRAIGHT_ANGLE: Double = 180.0

private const val QUARTER_TURN: Double = 90.0

private const val THREE_QUARTER_TURN: Double = 270.0

private const val FULL_TURN: Double = 360.0
