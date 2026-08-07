/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.emit

import io.pdfkt.core.doc.PdfDocumentWriter
import io.pdfkt.core.font.FontRef
import io.pdfkt.core.font.Standard14Name
import io.pdfkt.core.image.ImageColorSpace
import io.pdfkt.core.image.ImageRef
import io.pdfkt.core.model.Insets
import io.pdfkt.core.model.Pt
import io.pdfkt.core.model.Rect
import io.pdfkt.core.model.Rgb
import io.pdfkt.core.model.Size
import io.pdfkt.core.model.pt
import io.pdfkt.core.plan.Decoration
import io.pdfkt.core.plan.PagePlan
import io.pdfkt.core.plan.PlacedItem
import io.pdfkt.core.plan.PlacedRun
import io.pdfkt.core.plan.Stroke
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// ISO 32000-2:2020 §7.8.3 "Resource dictionaries", §8.5.3 "Path-painting operators",
// §8.6.8 "Colour operators", §8.9.4 "Image coordinate system",
// §9.4 "Text objects", §12.5.6.5 "Link annotations"

/**
 * The walk from a hand-built [PagePlan] to the operators of a page.
 *
 * No clause defines a plan — L4 is this library's own design — so what is
 * asserted here is the *translation*: that each variant of `PlacedItem` becomes
 * the operators its clause defines, at the coordinates the plan gave, with the
 * resources the page needs and nothing it does not.
 *
 * The plans are written by hand, which is the point of the design: the layout
 * that will produce them does not exist yet, and the emitter can be finished and
 * proven without it.
 */
class PlanEmitterTest {
    /**
     * A line of text: one text object (§9.4.1) with the font, the colour and the
     * absolute position of `Tm` (Table 106), inside the `q` … `Q` that keeps its
     * colour off the next item (§8.4.2).
     */
    @Test
    fun `a text item becomes one text object at the baseline the plan gave`() {
        val file = emitted(PagePlan(Size.LETTER, listOf(helloAt(72.pt, 700.pt))))
        assertContains(
            file,
            """
            q
            BT
            0 0 0 rg
            /F1 24 Tf
            1 0 0 1 72 700 Tm
            (Hello) Tj
            ET
            Q
            """.trimIndent(),
        )
    }

    /**
     * Runs of one line each place themselves absolutely, at the item's origin
     * plus their own offset — `Tm` "shall not be concatenated onto the current
     * text matrix, but shall replace it" (Table 106), so no run's position
     * depends on what the run before it drew.
     */
    @Test
    fun `each run of a line is placed by its own offset`() {
        val bold =
            PlacedRun(
                text = "World",
                font = FontRef.Standard14(Standard14Name.HELVETICA_BOLD),
                size = 24.pt,
                color = Rgb(1.0f, 0.0f, 0.0f),
                xOffset = 64.pt,
                width = 70.pt,
            )
        val file = emitted(PagePlan(Size.LETTER, listOf(PlacedItem.Text(72.pt, 700.pt, listOf(hello(), bold)))))
        assertContains(file, "1 0 0 1 72 700 Tm\n(Hello) Tj\n")
        assertContains(file, "1 0 0 rg\n/F2 24 Tf\n1 0 0 1 136 700 Tm\n(World) Tj\n")
    }

    /**
     * A decoration is a filled rectangle, and a rectangle is a path — which "shall
     * not begin inside" a text object (§8.2, Figure 9). So it is drawn after the
     * `ET`, at the run's own width, without the emitter measuring anything.
     */
    @Test
    fun `a decoration is drawn as a rectangle after the text object`() {
        val underlined = hello().copy(decorations = listOf(Decoration.Underline(offset = -(2.pt), thickness = 1.pt)))
        val file = emitted(PagePlan(Size.LETTER, listOf(PlacedItem.Text(72.pt, 700.pt, listOf(underlined)))))
        assertContains(file, "ET\n0 0 0 rg\n72 698 60 1 re\nf\nQ\n")
    }

    /**
     * Table 58's `re` with the plan's box, painted by the operator of Table 59
     * that the item's fill and stroke ask for: `f`, `S`, or `B` for both.
     */
    @Test
    fun `a rectangle is painted by the operator its fill and stroke ask for`() {
        val box = Rect(72.pt, 72.pt, 100.pt, 50.pt)
        val filled = PlacedItem.Rect(box, fill = Rgb(1.0f, 0.0f, 0.0f))
        val stroked = PlacedItem.Rect(box, stroke = Stroke(Rgb.BLACK, 2.pt))
        val both = PlacedItem.Rect(box, fill = Rgb.gray(0.5f), stroke = Stroke(Rgb.BLACK, 2.pt))
        val file = emitted(PagePlan(Size.LETTER, listOf(filled, stroked, both)))
        assertContains(file, "q\n1 0 0 rg\n72 72 100 50 re\nf\nQ\n")
        assertContains(file, "q\n0 0 0 RG\n2 w\n72 72 100 50 re\nS\nQ\n")
        assertContains(file, "q\n0.5 0.5 0.5 rg\n0 0 0 RG\n2 w\n72 72 100 50 re\nB\nQ\n")
    }

    /**
     * §8.9.4: the image occupies the unit square, and "An image can be placed on
     * the output page in any position, orientation, and size by using the cm
     * operator so as to map the unit square … Typically, this is done within a
     * pair of q and Q operators."
     *
     * Unrotated, that matrix is the extent and the corner of the box.
     */
    @Test
    fun `an unrotated image is placed by the extent and corner of its box`() {
        val plan =
            PagePlan(
                Size.LETTER,
                listOf(PlacedItem.Image(Rect(100.pt, 200.pt, 150.pt, 80.pt), swatch())),
            )
        assertContains(emitted(plan), "q\n150 0 0 80 100 200 cm\n/Im1 Do\nQ\n")
    }

    /**
     * A quarter turn about the centre of the box. The clause's own example
     * rotates by concatenating `cos sin -sin cos 0 0` between the translation and
     * the scale; the product for 90° is `0 w -h 0 …`, and the translation is what
     * carries the turned square back onto the centre of the box.
     *
     * A 100 × 40 box centred at (150, 220) therefore paints the image across
     * 40 units horizontally and 100 vertically — outside the box, which is what
     * rotating a placed rectangle means and why a planner that turns a photo
     * gives it the box it wants after the turn.
     */
    @Test
    fun `a rotated image turns about the centre of its box`() {
        val plan =
            PagePlan(
                Size.LETTER,
                listOf(PlacedItem.Image(Rect(100.pt, 200.pt, 100.pt, 40.pt), swatch(), rotation = 90.0f)),
            )
        assertContains(emitted(plan), "q\n0 100 -40 0 170 170 cm\n/Im1 Do\nQ\n")
    }

    /**
     * A link is not painted: it becomes an annotation in `/Annots`, holding the
     * `/Rect` of Table 166 in the `[llx lly urx ury]` form of §7.9.5 and the URI
     * action of Table 210.
     */
    @Test
    fun `a link becomes an annotation and no operator`() {
        val plan =
            PagePlan(
                Size.LETTER,
                listOf(PlacedItem.Link(Rect(72.pt, 700.pt, 60.pt, 24.pt), "https://www.iso.org/standard/75839.html")),
            )
        val file = emitted(plan)
        assertContains(
            file,
            "<</Type /Annot /Subtype /Link /Rect [72 700 132 724] /Border [0 0 0] " +
                "/A <</S /URI /URI (https://www.iso.org/standard/75839.html)>>>>",
        )
        assertContains(file, "/Annots [")
    }

    /**
     * §7.8.3: "A PDF writer should only include resource definitions for
     * resources that are actually referenced by the content streams of the
     * associated page." A page of text names no `/XObject`; a page of one image
     * names no `/Font`.
     */
    @Test
    fun `a page names only the resources it used`() {
        val sink = Buffer()
        val emitter = PlanEmitter(PdfDocumentWriter(sink))
        emitter.emit(PagePlan(Size.LETTER, listOf(helloAt(72.pt, 700.pt))))
        emitter.emit(
            PagePlan(Size.LETTER, listOf(PlacedItem.Image(Rect(0.pt, 0.pt, 10.pt, 10.pt), swatch()))),
        )
        emitter.finish(FILE_IDENTIFIER)
        val file = sink.readByteArray().decodeToString()
        assertContains(file, "/Resources <</Font <</F1 ")
        assertContains(file, "/Resources <</XObject <</Im1 ")
        assertFalse("/Font <</F1 1 0 R>> /XObject" in file, "a page named a resource it never used")
    }

    /**
     * The plan is the page, and the same plan emitted twice is two pages of one
     * document that share the font behind them — resolution is per document.
     */
    @Test
    fun `two plans become two pages sharing one font object`() {
        val sink = Buffer()
        val emitter = PlanEmitter(PdfDocumentWriter(sink))
        val plan = PagePlan(Size.LETTER, listOf(helloAt(72.pt, 700.pt)))
        emitter.emit(plan)
        emitter.emit(plan)
        emitter.finish(FILE_IDENTIFIER)
        val file = sink.readByteArray().decodeToString()
        assertEquals(2, "/Type /Page ".countOf(file) + "/Type /Page\n".countOf(file))
        assertEquals(1, "/BaseFont /Helvetica".countOf(file))
    }

    /** The page size of the plan is the `/MediaBox` of the page (Table 31). */
    @Test
    fun `the page size of the plan is the media box`() {
        val plan = PagePlan(Size(200.pt, 100.pt), listOf(helloAt(10.pt, 10.pt)))
        assertContains(emitted(plan), "/MediaBox [0 0 200 100]")
    }

    /** An empty plan is a legitimate page: a blank sheet, with an empty stream. */
    @Test
    fun `a plan with no items is a blank page`() {
        val file = emitted(PagePlan(Size.LETTER))
        assertContains(file, "/Resources <<>>")
        assertContains(file, "<</Length 0>>\nstream\n\nendstream")
    }

    /**
     * A document is written in order to be displayed. Nothing emitted means
     * nothing to display, and it is refused here rather than becoming a file
     * `qpdf` calls clean and no reader shows.
     */
    @Test
    fun `finishing without a plan is refused`() {
        assertFailsWith<IllegalStateException> { PlanEmitter(PdfDocumentWriter(Buffer())).finish(FILE_IDENTIFIER) }
    }

    /** One emitter writes one document. */
    @Test
    fun `an emitter is finished once`() {
        val emitter = PlanEmitter(PdfDocumentWriter(Buffer()))
        emitter.emit(PagePlan(Size.LETTER))
        emitter.finish(FILE_IDENTIFIER)
        assertFailsWith<IllegalStateException> { emitter.emit(PagePlan(Size.LETTER)) }
        assertFailsWith<IllegalStateException> { emitter.finish(FILE_IDENTIFIER) }
    }

    /**
     * The no-silent-loss policy, at the emitter: a
     * character the font cannot show stops the page rather than leaving a gap in
     * it. Helvetica is a single-byte encoding (§9.6.2.2) and has no Cyrillic.
     */
    @Test
    fun `a character the font cannot show fails the page it is on`() {
        val cyrillic = hello().copy(text = "Привет")
        assertFailsWith<Exception> {
            emitted(PagePlan(Size.LETTER, listOf(PlacedItem.Text(72.pt, 700.pt, listOf(cyrillic)))))
        }
    }

    /** A margin box is the page deflated by its insets — the fragmenter's frame. */
    @Test
    fun `the content area of a page is its size less the margins`() {
        val content = Size.LETTER.toRect().deflate(Insets.uniform(72.pt))
        val plan = PagePlan(Size.LETTER, listOf(PlacedItem.Rect(content, fill = Rgb.WHITE)))
        assertContains(emitted(plan), "1 1 1 rg\n72 72 468 648 re\nf\n")
    }

    /** Twenty-four point Helvetica, sixty units wide as the line breaker measured it. */
    private fun hello(): PlacedRun = PlacedRun(
        text = "Hello",
        font = FontRef.Standard14(Standard14Name.HELVETICA),
        size = 24.pt,
        color = Rgb.BLACK,
        xOffset = Pt.ZERO,
        width = 60.pt,
    )

    private fun helloAt(x: Pt, y: Pt): PlacedItem.Text = PlacedItem.Text(x, y, listOf(hello()))

    /** A 2 × 2 DeviceRGB swatch — twelve bytes, all of them ASCII (§8.9.3). */
    private fun swatch(): ImageRef = ImageRef(
        id = "swatch",
        width = 2,
        height = 2,
        colorSpace = ImageColorSpace.DEVICE_RGB,
        data = ByteArray(12) { index -> if (index % 3 == 0) 0x7F else 0x20 },
    )
}

/** The file a one-emitter document of these plans comes out as. */
private fun emitted(vararg plans: PagePlan): String {
    val sink = Buffer()
    val emitter = PlanEmitter(PdfDocumentWriter(sink))
    for (plan in plans) emitter.emit(plan)
    emitter.finish(FILE_IDENTIFIER)
    return sink.readByteArray().decodeToString()
}

private fun assertContains(file: String, expected: String) {
    assertTrue(expected in file, "the file does not contain\n$expected\n\nit is\n$file")
}

private fun String.countOf(text: String): Int {
    var count = 0
    var index = text.indexOf(this)
    while (index >= 0) {
        count++
        index = text.indexOf(this, index + 1)
    }
    return count
}

/** "Each PDF file identifier byte-string shall have a minimum length of 16 bytes" (§14.4). */
private val FILE_IDENTIFIER: ByteArray = ByteArray(16) { index -> (index + 1).toByte() }
