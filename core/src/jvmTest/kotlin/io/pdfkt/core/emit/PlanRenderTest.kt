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
import io.pdfkt.core.obj.assertQpdfCheckIsClean
import io.pdfkt.core.obj.extractedText
import io.pdfkt.core.obj.qpdf
import io.pdfkt.core.obj.skipWithoutPdftotext
import io.pdfkt.core.obj.skipWithoutQpdf
import io.pdfkt.core.plan.Decoration
import io.pdfkt.core.plan.PagePlan
import io.pdfkt.core.plan.PlacedItem
import io.pdfkt.core.plan.PlacedRun
import io.pdfkt.core.plan.Stroke
import kotlinx.io.asSink
import kotlinx.io.buffered
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// ISO 32000-2:2020 §7.7.3 "Page tree", §7.8.3 "Resource dictionaries",
// §8.5.3 "Path-painting operators", §8.9.5 "Image dictionaries",
// §9.4 "Text objects", §12.5.6.5 "Link annotations"

/**
 * A page plan written by hand, walked into a real file, and read back by tools
 * that know nothing about this library.
 *
 * This is the gate the plan model is worth having only if it passes: the layout
 * engine that will produce plans does not exist yet, and this shows that
 * everything downstream of it does — that a `PagePlan` holding text, rectangles,
 * an image and a link becomes a document `qpdf --check` accepts, whose text
 * `pdftotext` reads back exactly as it was drawn, and whose image and annotation
 * a reader finds where the plan put them.
 *
 * qpdf and pdftotext are used as *tools*; their output is compared, never their
 * source.
 */
class PlanRenderTest {
    /**
     * One page of everything the plan model can hold.
     *
     * The geometry is the one a layout would produce: a US Letter page
     * (§8.3.2.3 — 612 × 792 units) deflated by one-inch margins, a heading at
     * the top of the content area, a rule under it, a paragraph line below that,
     * an image in the middle of the column and a link over the heading. Nothing
     * here measures or fits anything: every coordinate is stated, which is what
     * a plan is.
     */
    @Test
    fun `a hand written plan with text a rectangle and an image renders and reads back`() {
        if (skipWithoutQpdf("§7.7")) return
        if (skipWithoutPdftotext("§9.6.2.2")) return

        val page = Size.LETTER
        val column = page.toRect().deflate(Insets.uniform(72.pt))
        val heading =
            PlacedItem.Text(
                x = column.left,
                y = column.top - 24.pt,
                runs =
                listOf(
                    PlacedRun(
                        text = HEADING,
                        font = FontRef.Standard14(Standard14Name.HELVETICA_BOLD),
                        size = 24.pt,
                        color = Rgb.of(0x003366),
                        xOffset = Pt.ZERO,
                        width = 220.pt,
                        decorations = listOf(Decoration.Underline(offset = -(4.pt), thickness = 1.pt)),
                    ),
                ),
            )
        val rule =
            PlacedItem.Rect(
                box = Rect(column.left, column.top - 36.pt, column.width, 2.pt),
                fill = Rgb.gray(0.25f),
            )
        val body =
            PlacedItem.Text(
                x = column.left,
                y = column.top - 72.pt,
                runs =
                listOf(
                    PlacedRun(
                        text = BODY,
                        font = FontRef.Standard14(Standard14Name.HELVETICA),
                        size = 12.pt,
                        color = Rgb.BLACK,
                        xOffset = Pt.ZERO,
                        width = 200.pt,
                    ),
                ),
            )
        val frame =
            PlacedItem.Rect(
                box = Rect(column.left, column.top - 300.pt, 200.pt, 200.pt),
                fill = Rgb.WHITE,
                stroke = Stroke(Rgb.BLACK, 1.pt),
            )
        val picture =
            PlacedItem.Image(
                box = Rect(column.left + 10.pt, column.top - 290.pt, 180.pt, 180.pt),
                image = gradient(),
            )
        val link =
            PlacedItem.Link(
                box = Rect(column.left, column.top - 28.pt, 220.pt, 28.pt),
                target = TARGET,
            )
        val plan = PagePlan(page, listOf(heading, rule, body, frame, picture, link))

        withTemporaryFile { path ->
            Files.newOutputStream(path).asSink().buffered().use { sink ->
                val emitter = PlanEmitter(PdfDocumentWriter(sink))
                emitter.emit(plan)
                emitter.finish(fileIdentifier = FILE_IDENTIFIER)
            }

            assertQpdfCheckIsClean(path)

            // The two text items, in the order a reader walks the page down.
            assertEquals("$HEADING\n$BODY", extractedText(path))

            val pages = qpdf("--show-npages", path.toString())
            assertEquals("1", pages.output.trim(), "the document does not have exactly one page")

            val structure = qpdf("--json", path.toString())
            assertEquals(0, structure.status, "qpdf could not dump the file:\n${structure.output}")
            assertTrue(
                """"/Subtype": "/Image"""" in structure.output,
                "no image XObject in the file:\n${structure.output}",
            )
            assertTrue(
                """"/Subtype": "/Link"""" in structure.output,
                "no link annotation in the file:\n${structure.output}",
            )
            assertTrue(TARGET in structure.output, "the link does not carry its URI:\n${structure.output}")
            assertTrue(
                """"/BaseFont": "/Helvetica-Bold"""" in structure.output,
                "the heading's font is not in the file:\n${structure.output}",
            )
        }
    }

    /**
     * Two plans, two pages, in the order they were emitted — and one image
     * object behind both, because resolution is per document.
     */
    @Test
    fun `two plans become two pages that share one image object`() {
        if (skipWithoutQpdf("§7.7.3")) return
        if (skipWithoutPdftotext("§9.4")) return

        val image = gradient()
        val plans =
            listOf("First page", "Second page").map { text ->
                PagePlan(
                    Size.LETTER,
                    listOf(
                        PlacedItem.Text(
                            x = 72.pt,
                            y = 700.pt,
                            runs =
                            listOf(
                                PlacedRun(
                                    text = text,
                                    font = FontRef.Standard14(Standard14Name.HELVETICA),
                                    size = 18.pt,
                                    color = Rgb.BLACK,
                                    xOffset = Pt.ZERO,
                                    width = 100.pt,
                                ),
                            ),
                        ),
                        PlacedItem.Image(Rect(72.pt, 400.pt, 120.pt, 120.pt), image),
                    ),
                )
            }

        withTemporaryFile { path ->
            Files.newOutputStream(path).asSink().buffered().use { sink ->
                val emitter = PlanEmitter(PdfDocumentWriter(sink))
                for (plan in plans) emitter.emit(plan)
                emitter.finish(fileIdentifier = FILE_IDENTIFIER)
            }

            assertQpdfCheckIsClean(path)
            assertEquals("2", qpdf("--show-npages", path.toString()).output.trim())
            // pdftotext separates pages with a FORM FEED, so this is also the
            // assertion that the pages came out in the order they were emitted.
            assertEquals(
                listOf("First page", "Second page"),
                extractedText(path).split(FORM_FEED).map { pageText -> pageText.trim() },
            )

            val dump = qpdf("--json", path.toString()).output
            assertEquals(
                1,
                Regex(""""/Subtype": "/Image"""").findAll(dump).count(),
                "the image was written once per page instead of once per document:\n$dump",
            )
        }
    }

    /** A 16 × 16 DeviceRGB gradient, built here so that no binary is committed. */
    private fun gradient(): ImageRef {
        val side = 16
        val samples = ByteArray(side * side * ImageColorSpace.DEVICE_RGB.components)
        for (row in 0 until side) {
            for (column in 0 until side) {
                val at = (row * side + column) * ImageColorSpace.DEVICE_RGB.components
                samples[at] = (column * FULL_BYTE / (side - 1)).toByte()
                samples[at + 1] = (row * FULL_BYTE / (side - 1)).toByte()
                samples[at + 2] = FULL_BYTE.toByte()
            }
        }
        return ImageRef(
            id = "gradient",
            width = side,
            height = side,
            colorSpace = ImageColorSpace.DEVICE_RGB,
            data = samples,
        )
    }

    private fun withTemporaryFile(block: (Path) -> Unit) {
        val path = Files.createTempFile("pdfkt-plan", ".pdf")
        try {
            block(path)
        } finally {
            path.deleteIfExists()
        }
    }

    private companion object {
        const val HEADING: String = "A page built from a plan"

        const val BODY: String = "Text, a rectangle and an image, placed by hand."

        const val TARGET: String = "https://www.iso.org/standard/75839.html"

        const val FULL_BYTE: Int = 255

        /** FORM FEED (0Ch) — what pdftotext puts between the pages of a file. */
        const val FORM_FEED: Char = '\u000C'

        /** "Each PDF file identifier byte-string shall have a minimum length of 16 bytes" (§14.4). */
        val FILE_IDENTIFIER: ByteArray = "plan-emitter-fixture".encodeToByteArray()
    }
}
