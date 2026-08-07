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
import io.pdfkt.core.block.TableBlock
import io.pdfkt.core.block.TableBorderStyle
import io.pdfkt.core.block.TableCell
import io.pdfkt.core.block.TableColumns
import io.pdfkt.core.image.jpegImage
import io.pdfkt.core.image.pngImage
import io.pdfkt.core.model.Insets
import io.pdfkt.core.model.Pt
import io.pdfkt.core.model.Size
import io.pdfkt.core.model.pt
import io.pdfkt.core.obj.ColourPageRaster
import io.pdfkt.core.obj.PdfDate
import io.pdfkt.core.obj.assertQpdfCheckIsClean
import io.pdfkt.core.obj.qpdf
import io.pdfkt.core.obj.rasteriseColourPage
import io.pdfkt.core.obj.skipWithoutPdftoppm
import io.pdfkt.core.obj.skipWithoutQpdf
import io.pdfkt.core.style.Theme
import kotlinx.io.asSink
import kotlinx.io.buffered
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// ISO 32000-2:2020 §8.9.5.1 "Image dictionaries" (Table 87),
// §7.4.4 "FlateDecode filter", §7.4.8 "DCTDecode filter",
// §8.6.4 "Device colour spaces", §11.6.5.2 "Soft-mask images" (Table 143)

/**
 * A PNG and a JPEG on one page, side by side, from the two file formats to the
 * pixels a renderer makes of them.
 *
 * The two take opposite routes into the file and both routes are asserted here.
 * The JPEG is embedded: §7.4.8 makes its encoding a PDF filter, so the stream is
 * the file. The PNG is not: nothing in PDF understands chunks or a per-scanline
 * filter byte, so it is decoded to samples and deflated back under
 * `/FlateDecode` (§7.4.4), with its alpha channel lifted out into the `/SMask`
 * of Table 87 — "a subsidiary image XObject defining a soft-mask image".
 *
 * What the raster proves is the part no structural tool can: that the colours
 * came out and the mask was honoured. The PNG is red on its left half and fully
 * transparent on its right, so a renderer that ignored `/SMask` paints red where
 * this test demands the white of the page, and one that lost the colour space
 * paints grey where it demands red.
 */
class PngBlockRenderTest {
    /**
     * The file: two image XObjects the page's resources name, one `/DCTDecode`
     * and one `/FlateDecode`, and the soft mask the second one points at —
     * DeviceGray, as Table 143 requires.
     */
    @Test
    fun `a PNG and a JPEG side by side are two XObjects and one soft mask`() {
        if (skipWithoutQpdf("§8.9.5.1")) return

        withRendered(sideBySide()) { path ->
            assertQpdfCheckIsClean(path)
            assertEquals("1", qpdf("--show-npages", path.toString()).output.trim())

            val images = imageObjects(path)
            assertEquals(
                3,
                images.size,
                "a PNG with alpha, its soft mask and a JPEG are three image XObjects:\n$images",
            )

            val png = images.single { it.contains("/FlateDecode") && it.contains("/SMask") }
            assertContains(png, "/Subtype /Image")
            assertContains(png, "/ColorSpace /DeviceRGB")
            assertContains(png, "/BitsPerComponent 8")
            assertContains(png, "/Width $PNG_SIDE")
            assertContains(png, "/Height $PNG_SIDE")

            val jpeg = images.single { it.contains("/DCTDecode") }
            assertContains(jpeg, "/ColorSpace /DeviceRGB")
            assertContains(jpeg, "/Width $JPEG_SIDE")

            // The mask is the third: DeviceGray, the size of the image it masks,
            // and carrying no mask of its own (Table 143).
            val mask = images.single { it.contains("/DeviceGray") }
            assertContains(mask, "/FlateDecode")
            assertContains(mask, "/Width $PNG_SIDE")
            assertTrue("/SMask" !in mask, "a soft-mask image dictionary's SMask shall be absent:\n$mask")

            // The chain: the /SMask entry of the image is the object the mask
            // dictionary was found in, and the page's resources name only the
            // two images a `Do` can paint — never the mask.
            val maskRef = SMASK_ENTRY.find(png)?.groupValues?.get(1)
            assertTrue(maskRef != null, "the image does not point at its soft mask:\n$png")
            assertEquals(mask, shownObject(path, "$maskRef,0"))
            assertEquals(2, pageXObjectNames(path).size, "the page names its images and nothing else")
        }
    }

    /**
     * The page: the PNG's opaque half is red, its transparent half is the white
     * of the page, and the JPEG beside it is blue.
     *
     * Three samples, one in the middle of each of the three quarters the two
     * cells are divided into by the PNG's own alpha — which is the whole claim
     * of this test in three pixels.
     */
    @Test
    fun `both images render, and the transparent half of the PNG lets the page through`() {
        if (skipWithoutQpdf("§11.6.5.2") || skipWithoutPdftoppm("§11.6.5.2")) return

        val spec = sideBySide()
        withRendered(spec) { path ->
            val raster = rasteriseColourPage(path, page = 1, dpi = DPI)
            // Where the two images landed, read out of the page's own `cm`
            // operators (§8.9.4) rather than recomputed here: the sample points
            // then follow from the boxes whatever the table did with them.
            val painted = paintedImages(path).sortedBy { box -> box.x }
            assertEquals(2, painted.size, "the page does not paint exactly two images")
            val (png, jpeg) = painted
            assertTrue(png.x < jpeg.x, "the PNG is the left of the two cells")

            assertColour(
                RED,
                raster.at(png.x + png.width * 0.25f, png.y + png.height * 0.5f),
                "the opaque half of the PNG",
            )
            assertColour(
                WHITE,
                raster.at(png.x + png.width * 0.75f, png.y + png.height * 0.5f),
                "the transparent half of the PNG — /SMask was not honoured",
            )
            assertColour(
                BLUE,
                raster.at(jpeg.x + jpeg.width * 0.5f, jpeg.y + jpeg.height * 0.5f),
                "the JPEG beside it",
            )
        }
    }

    // ------------------------------------------------------------- the document

    /**
     * One row of two cells: the PNG on the left, the JPEG on the right. A table
     * is what puts two blocks beside each other — the fragmenter stacks
     * everything else — and a cell holds any block, images included, through the
     * same fragmenter.
     */
    private fun sideBySide(): DocumentSpec {
        val png = ImageBlock(pngImage("badge", halfTransparentRedPng()), fit = ImageFit.Contain)
        val jpeg = ImageBlock(jpegImage("photo", solidBlueJpeg()), fit = ImageFit.Contain)
        return specOf(
            TableBlock(
                columns = TableColumns.Equal(2),
                rows = listOf(listOf(TableCell(listOf(png)), TableCell(listOf(jpeg)))),
                borders = TableBorderStyle.None,
                cellPadding = Pt.ZERO,
            ),
        )
    }

    private fun specOf(block: Block): DocumentSpec = DocumentSpec(
        pageSize = Size.A4,
        margins = Insets.uniform(MARGIN),
        content = listOf(block),
        metadata = Metadata(title = "A PNG and a JPEG"),
        clock = PINNED,
    )

    private fun withRendered(spec: DocumentSpec, block: (Path) -> Unit) {
        val path = Files.createTempFile("pdfkt-png", ".pdf")
        try {
            Files.newOutputStream(path).asSink().buffered().use { sink -> render(spec, Theme.Default, sink) }
            block(path)
        } finally {
            path.deleteIfExists()
        }
    }

    // -------------------------------------------------------------- the sources

    /**
     * A PNG written by the JDK's encoder, so no binary is committed and the
     * datastream is one this library never wrote: red at full opacity across the
     * left half, and fully transparent — colour and alpha both zero — across the
     * right.
     */
    private fun halfTransparentRedPng(): ByteArray {
        val image = BufferedImage(PNG_SIDE, PNG_SIDE, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until PNG_SIDE) {
            for (x in 0 until PNG_SIDE) {
                image.setRGB(x, y, if (x < PNG_SIDE / 2) OPAQUE_RED else 0)
            }
        }
        return written(image, "png")
    }

    /** A solid blue JPEG, likewise encoded when the test runs. */
    private fun solidBlueJpeg(): ByteArray {
        val image = BufferedImage(JPEG_SIDE, JPEG_SIDE, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until JPEG_SIDE) {
            for (x in 0 until JPEG_SIDE) image.setRGB(x, y, PURE_BLUE)
        }
        return written(image, "jpeg")
    }

    private fun written(image: BufferedImage, format: String): ByteArray {
        val bytes = ByteArrayOutputStream()
        assertTrue(ImageIO.write(image, format, bytes), "the JDK has no $format writer")
        return bytes.toByteArray()
    }

    // ---------------------------------------------------------------- the file

    /** Every image XObject of the file, as qpdf prints its dictionary. */
    private fun imageObjects(path: Path): List<String> {
        val dump = qpdf("--json", path.toString())
        assertEquals(0, dump.status, "qpdf could not dump the file:\n${dump.output}")
        return OBJECT_KEY.findAll(dump.output)
            .map { key -> "${key.groupValues[1]},${key.groupValues[2]}" }
            .map { id -> shownObject(path, id) }
            .filter { shown -> "/Subtype /Image" in shown }
            .toList()
    }

    private fun shownObject(path: Path, id: String): String {
        val shown = qpdf("--show-object=$id", path.toString())
        assertEquals(0, shown.status, "qpdf could not show object $id:\n${shown.output}")
        return shown.output
    }

    /** The names of the `/XObject` subdictionary of the page's resources (§7.8.3). */
    private fun pageXObjectNames(path: Path): List<String> = withExpanded(path) { content ->
        val subdictionary = XOBJECT_RESOURCES.find(content)
        assertTrue(subdictionary != null, "the page has no /XObject resources:\n$content")
        RESOURCE_NAME.findAll(subdictionary.groupValues[1]).map { it.value }.toList()
    }

    /** [read] over the file with every stream unfiltered, which is what `--qdf` produces. */
    private fun <T> withExpanded(path: Path, read: (String) -> T): T {
        val expanded = Files.createTempFile("pdfkt-png-qdf", ".pdf")
        try {
            val run = qpdf("--qdf", "--object-streams=disable", path.toString(), expanded.toString())
            assertTrue(run.status == 0 || run.status == 3, "qpdf could not expand the file:\n${run.output}")
            return read(String(Files.readAllBytes(expanded), Charsets.ISO_8859_1))
        } finally {
            expanded.deleteIfExists()
        }
    }

    // ---------------------------------------------------------------- the page

    /**
     * Where each image is painted: the `cm` of §8.9.4 mapping the unit square
     * "to the rectangle … in which the image shall be painted", read out of the
     * content stream with every filter removed.
     */
    private fun paintedImages(path: Path): List<PaintedBox> = withExpanded(path) { content ->
        IMAGE_MATRIX.findAll(content).map { match ->
            val numbers = match.groupValues
            PaintedBox(
                width = numbers[1].toFloat(),
                height = numbers[2].toFloat(),
                x = numbers[3].toFloat(),
                y = numbers[4].toFloat(),
            )
        }.toList()
    }

    /** A rectangle of default user space the page paints an image into (§8.3.2.3). */
    private class PaintedBox(val width: Float, val height: Float, val x: Float, val y: Float)

    /**
     * The pixel at a point of default user space, whose origin is the lower left
     * corner of the page (§8.3.2.3) while a raster's is the upper left one — so
     * the vertical axis is measured the other way round.
     */
    private fun ColourPageRaster.at(x: Float, y: Float): Triple<Int, Int, Int> =
        pixelAt(pixels(x), height - 1 - pixels(y))

    /** A length in default user space units as a pixel of a raster at [DPI]. */
    private fun pixels(length: Float): Int = (length * DPI / POINTS_PER_INCH).toInt()

    /**
     * A rendered pixel is [expected] give or take antialiasing and the loss a
     * JPEG's quantisation costs — generous per component, because what is under
     * test is whether red came out red, not what shade of it.
     */
    private fun assertColour(expected: Triple<Int, Int, Int>, actual: Triple<Int, Int, Int>, what: String) {
        val close = (expected.first - actual.first).let { it * it } +
            (expected.second - actual.second).let { it * it } +
            (expected.third - actual.third).let { it * it }
        assertTrue(close <= COLOUR_TOLERANCE * COLOUR_TOLERANCE, "$what is $actual, not $expected")
    }

    private companion object {
        val MARGIN: Pt = 56.pt

        /** The clock the file is written through — the dates are not what is under test. */
        val PINNED: Clock = Clock { PdfDate(2026, 8, 5, 9, 0, 0, offsetMinutes = 0) }

        const val PNG_SIDE: Int = 64
        const val JPEG_SIDE: Int = 48

        /** Alpha, red, green, blue — opaque red, and 0 is transparent black. */
        const val OPAQUE_RED: Int = -0x1000000 or 0xFF0000
        const val PURE_BLUE: Int = 0x0000FF

        val RED: Triple<Int, Int, Int> = Triple(255, 0, 0)
        val BLUE: Triple<Int, Int, Int> = Triple(0, 0, 255)
        val WHITE: Triple<Int, Int, Int> = Triple(255, 255, 255)

        /** How far a rendered colour may sit from the one asked for, as a distance in RGB. */
        const val COLOUR_TOLERANCE: Int = 60

        const val DPI: Int = 150
        const val POINTS_PER_INCH: Float = 72.0f

        /** `"obj:12 0 R"`, the key qpdf's JSON gives an indirect object. */
        val OBJECT_KEY: Regex = Regex(""""obj:(\d+) (\d+) R"""")

        /** `/SMask 12 0 R` in a dictionary qpdf printed. */
        val SMASK_ENTRY: Regex = Regex("""/SMask (\d+) \d+ R""")

        /** The `/XObject` subdictionary of a resource dictionary, as qpdf's QDF form writes it. */
        val XOBJECT_RESOURCES: Regex = Regex("""/XObject\s*<<([^>]*)>>""")

        val RESOURCE_NAME: Regex = Regex("""/Im\d+""")

        /** `a 0 0 d e f cm` followed by the `Do` that paints an image (§8.9.4). */
        val IMAGE_MATRIX: Regex = Regex("""(-?[\d.]+) 0 0 (-?[\d.]+) (-?[\d.]+) (-?[\d.]+) cm\s+/\w+ Do""")
    }
}
