/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.doc

import io.pdfkt.core.block.Block
import io.pdfkt.core.block.ImageBlock
import io.pdfkt.core.block.ImageFit
import io.pdfkt.core.image.colourJpeg
import io.pdfkt.core.image.jpegImage
import io.pdfkt.core.model.Insets
import io.pdfkt.core.model.Pt
import io.pdfkt.core.model.Size
import io.pdfkt.core.model.pt
import io.pdfkt.core.obj.PdfDate
import io.pdfkt.core.obj.assertQpdfCheckIsClean
import io.pdfkt.core.obj.qpdf
import io.pdfkt.core.obj.skipWithoutQpdf
import io.pdfkt.core.style.Theme
import kotlinx.io.asSink
import kotlinx.io.buffered
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// ISO 32000-2:2020 §7.4.8 "DCTDecode filter",
// §8.9.4 "Image coordinate system", §8.9.5.1 "Image dictionaries" (Table 87)

/**
 * A photograph on a page, from a JPEG file to the operators that paint it.
 *
 * This is where the image lane meets the seam: a real JPEG — made by the JDK's
 * encoder when the test runs, so no binary is committed and the dimensions are
 * the test's own — becomes an [ImageBlock], and the file that comes out is asked
 * three things by qpdf, which knows nothing about this library.
 *
 * - **Is it embedded, undecoded?** `/Subtype /Image` with `/Filter /DCTDecode`,
 *   `/Width` and `/Height` the JPEG's own, and a stream that is the file that
 *   went in, byte for byte. §7.4.8 makes that possible: the encoded data are the
 *   stream, so embedding is copying.
 * - **Is it fitted?** §8.9.4 says an image "occupies the unit square" and is
 *   placed by "the cm operator … so as to map the unit square of user space to
 *   the rectangle … in which the image shall be painted", so the whole geometry
 *   of the planner is legible in the six numbers of one `cm`. Those numbers are
 *   what the aspect-fit and the centring are asserted on.
 * - **Is an oversized photograph scaled rather than dropped?** The layout rule:
 *   if it cannot split (an oversized image), it scales or errors — explicitly,
 *   per the no-silent-loss policy.
 */
class ImageBlockRenderTest {
    /**
     * A landscape photograph, wider than it is tall, on a page it fits: it fills
     * the column, keeps its aspect ratio, and sits at the top of the content
     * area — and the JPEG comes back out of the file exactly as it went in.
     */
    @Test
    fun `a real JPEG is embedded as DCTDecode and fitted to the column`() {
        if (skipWithoutQpdf("§7.4.8")) return

        val jpeg = colourJpeg(width = 320, height = 200)
        val spec = specOf(ImageBlock(jpegImage("photo", jpeg)))

        withRendered(spec) { path ->
            assertQpdfCheckIsClean(path)
            assertEquals("1", qpdf("--show-npages", path.toString()).output.trim())

            val image = imageObject(path)
            assertContains(image.dictionary, "/Subtype /Image")
            assertContains(image.dictionary, "/Filter /DCTDecode")
            assertContains(image.dictionary, "/Width 320")
            assertContains(image.dictionary, "/Height 200")
            assertContains(image.dictionary, "/ColorSpace /DeviceRGB")
            assertContains(image.dictionary, "/BitsPerComponent 8")
            assertContentEquals(jpeg, image.streamData, "the JPEG did not come back out of the file")

            // The unit square mapped onto the column: as wide as the content
            // area, as tall as 200/320 of that, its top edge at the top of the
            // area and its left edge at the left of it — which is where centring
            // puts an image that fills the column.
            val painted = paintedImage(path)
            val content = spec.contentArea
            assertClose(content.width, painted.width, "the image was not fitted to the column")
            assertClose(content.width * (200.0f / 320.0f), painted.height, "the aspect ratio was not kept")
            assertClose(content.left, painted.x, "the image is not centred in the column")
            assertClose(content.top - painted.height, painted.y, "the image does not start at the top")
        }
    }

    /**
     * A photograph taller than the whole page: it is scaled down onto the page,
     * keeping its aspect ratio, and centred in the column — never dropped, never
     * clipped, and never quietly stretched to fit.
     *
     * The image dictionary is unchanged by it. Fitting is a matrix, not a
     * resampling: `/Width` and `/Height` are still the samples the encoder wrote
     * (§8.9.5.1), and the size on the page is the `cm` of §8.9.4.
     */
    @Test
    fun `an oversized JPEG is scaled onto the page and centred`() {
        if (skipWithoutQpdf("§8.9.4")) return

        val jpeg = colourJpeg(width = 600, height = 1800)
        val spec = specOf(ImageBlock(jpegImage("tall", jpeg), fit = ImageFit.Contain))

        withRendered(spec) { path ->
            assertQpdfCheckIsClean(path)
            assertEquals("1", qpdf("--show-npages", path.toString()).output.trim())

            val image = imageObject(path)
            assertContains(image.dictionary, "/Width 600")
            assertContains(image.dictionary, "/Height 1800")
            assertContentEquals(jpeg, image.streamData, "an oversized image was re-encoded instead of scaled")

            val painted = paintedImage(path)
            val content = spec.contentArea
            assertClose(content.height, painted.height, "the image was not scaled down to the page")
            assertEquals(
                600.0f / 1800.0f,
                painted.width / painted.height,
                RATIO_TOLERANCE,
                "the aspect ratio was not kept: ${painted.width} by ${painted.height}",
            )
            assertTrue(
                painted.width < content.width,
                "a photograph three times as tall as it is wide filled the column: ${painted.width}",
            )
            assertClose(
                content.left + (content.width - painted.width) / 2.0f,
                painted.x,
                "the scaled image is not centred in the column",
            )
        }
    }

    // ---------------------------------------------------------------- the file

    /** A one-block document on A4, with margins no band reserve touches. */
    private fun specOf(block: Block): DocumentSpec = DocumentSpec(
        pageSize = Size.A4,
        margins = Insets.uniform(MARGIN),
        content = listOf(block),
        metadata = Metadata(title = "Photograph"),
        clock = PINNED,
    )

    private fun withRendered(spec: DocumentSpec, block: (Path) -> Unit) {
        val path = Files.createTempFile("pdfkt-image", ".pdf")
        try {
            Files.newOutputStream(path).asSink().buffered().use { sink -> render(spec, Theme.Default, sink) }
            block(path)
        } finally {
            path.deleteIfExists()
        }
    }

    /**
     * The one image XObject of the file: its dictionary as qpdf prints it, and
     * its stream as it stands in the file — `--raw-stream-data` is the encoded
     * bytes, which for a DCTDecode stream are the JPEG.
     */
    private fun imageObject(path: Path): EmbeddedImage {
        val dump = qpdf("--json", path.toString())
        assertEquals(0, dump.status, "qpdf could not dump the file:\n${dump.output}")
        val at = dump.output.indexOf(IMAGE_SUBTYPE)
        assertTrue(at >= 0, "the file holds no image XObject:\n${dump.output}")
        assertEquals(
            1,
            dump.output.split(IMAGE_SUBTYPE).size - 1,
            "the file does not hold exactly one image XObject:\n${dump.output}",
        )
        val key = OBJECT_KEY.findAll(dump.output.substring(0, at)).lastOrNull()
        assertTrue(key != null, "no object holds the image dictionary:\n${dump.output}")
        val id = "${key.groupValues[1]},${key.groupValues[2]}"

        val shown = qpdf("--show-object=$id", path.toString())
        assertEquals(0, shown.status, "qpdf could not show object $id:\n${shown.output}")
        val stream = qpdf("--show-object=$id", "--raw-stream-data", path.toString())
        assertEquals(0, stream.status, "qpdf could not read the stream of object $id:\n${stream.output}")
        return EmbeddedImage(shown.output, stream.bytes)
    }

    /**
     * Where the page paints its image: the `cm` of §8.9.4, read out of the
     * content stream with every filter removed.
     *
     * The matrix is `[a 0 0 d e f]` for an unrotated image, so `a` and `d` are
     * the width and the height the unit square is mapped to and `e`, `f` are
     * where its lower left corner lands — the box of the plan, in the file.
     */
    private fun paintedImage(path: Path): PaintedImage {
        val expanded = Files.createTempFile("pdfkt-image-qdf", ".pdf")
        try {
            val run = qpdf("--qdf", "--object-streams=disable", path.toString(), expanded.toString())
            assertTrue(run.status == 0 || run.status == 3, "qpdf could not expand the file:\n${run.output}")
            val content = String(Files.readAllBytes(expanded), Charsets.ISO_8859_1)
            val painted = IMAGE_MATRIX.findAll(content).toList()
            assertEquals(1, painted.size, "the page does not paint exactly one image:\n$content")
            val numbers = painted.single().groupValues
            return PaintedImage(
                width = Pt(numbers[1].toFloat()),
                height = Pt(numbers[2].toFloat()),
                x = Pt(numbers[3].toFloat()),
                y = Pt(numbers[4].toFloat()),
            )
        } finally {
            expanded.deleteIfExists()
        }
    }

    private fun assertClose(expected: Pt, actual: Pt, message: String) =
        assertEquals(expected.value, actual.value, TOLERANCE, "$message: expected $expected, got $actual")

    /** One image XObject of a written file. */
    private class EmbeddedImage(val dictionary: String, val streamData: ByteArray)

    /** Where an image landed on the page, in default user space units (§8.3.2.3). */
    private class PaintedImage(val width: Pt, val height: Pt, val x: Pt, val y: Pt)

    private companion object {
        val MARGIN: Pt = 56.pt

        /** A tenth of a point: the plan is in `Float`, the file in decimal digits. */
        const val TOLERANCE: Float = 0.1f

        /**
         * What an aspect ratio may drift by — a factor and not a length, so a
         * tenth of anything would be a photograph visibly out of shape.
         */
        const val RATIO_TOLERANCE: Float = 0.002f

        /** The clock the file is written through — the dates are not what is under test. */
        val PINNED: Clock = Clock { PdfDate(2026, 8, 5, 9, 0, 0, offsetMinutes = 0) }

        /** How an image XObject appears in qpdf's JSON dump of the objects. */
        const val IMAGE_SUBTYPE: String = """"/Subtype": "/Image""""

        /** `"obj:12 0 R"`, the key qpdf's JSON gives an indirect object. */
        val OBJECT_KEY: Regex = Regex(""""obj:(\d+) (\d+) R"""")

        /** `a 0 0 d e f cm` followed by the `Do` that paints an image (§8.9.4). */
        val IMAGE_MATRIX: Regex =
            Regex("""(-?[\d.]+) 0 0 (-?[\d.]+) (-?[\d.]+) (-?[\d.]+) cm\s+/\w+ Do""")
    }
}
