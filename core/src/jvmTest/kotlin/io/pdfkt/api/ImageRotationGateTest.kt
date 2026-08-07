/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.api

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// ISO 32000-2:2020 §8.3.3 "Common transformations", §8.4.4 "Graphics state
// operators" (the `cm` operator), §8.8.1 "External objects" (the `Do`
// operator), §7.3.3 "Numeric objects" (no exponential notation).

/**
 * `rotationDegrees` written out as a matrix, and seen on the page.
 *
 * §8.3.3 gives rotation by θ as `[cos θ, sin θ, −sin θ, cos θ, 0, 0]`, which
 * `cm` (§8.4.4) concatenates onto the current transformation matrix as
 * `a b c d e f cm`. A quarter turn therefore has cosines of exactly zero on the
 * diagonal — *exactly*, not 6.12 × 10⁻¹⁷, because §7.3.3 forbids exponential
 * notation and a reader that reads `6.12e-17` reads nothing at all
 *
 * The matrix says what was written; it does not say what a reader draws from it.
 * So the last test renders the page and measures the mark: a picture three times
 * taller than it is wide comes out taller than it is wide when it is not turned,
 * and wider than it is tall when it is — whichever way round the quarter turn
 * goes, which is what the matrix above already pinned.
 *
 * The picture is encoded by the JDK when the test runs, for the reason
 * `RichImageGateTest` encodes its own: a JPEG arrives at this library already
 * encoded, and §7.4.8 makes the encoded data the stream.
 */
class ImageRotationGateTest {

    @Test
    @DisplayName("a quarter turn is written as a quarter turn, exactly")
    fun aQuarterTurnIsWrittenAsAQuarterTurn() {
        if (skipWithoutQpdf("§8.3.3")) return

        val content = contentStreamOfFirstPage(albumOf(rotationDegrees = 90))
        assertTrue(
            QUARTER_TURN.containsMatchIn(content),
            "a picture turned by 90° should be drawn under a quarter-turn matrix (§8.3.3)\n$content",
        )
        assertTrue(content.contains("/Im1 Do"), "and painted by the Do operator (§8.8.1)\n$content")
    }

    @Test
    @DisplayName("a picture that is not turned is drawn under a matrix that only scales and moves")
    fun aPictureThatIsNotTurnedIsDrawnUnderAMatrixThatOnlyScalesAndMoves() {
        if (skipWithoutQpdf("§8.3.3")) return

        val content = contentStreamOfFirstPage(albumOf(rotationDegrees = 0))
        assertTrue(
            NO_TURN.containsMatchIn(content),
            "an unturned picture should carry no rotation in its matrix (§8.3.3)\n$content",
        )
        assertTrue(content.contains("/Im1 Do"), "and painted by the Do operator (§8.8.1)\n$content")
    }

    @Test
    @DisplayName("the quarter turn reaches the page: the mark a tall picture leaves comes out wide")
    fun theQuarterTurnReachesThePage() {
        if (skipWithoutPdftoppm("the marks on the page")) return

        val upright = markOnFirstPage(albumOf(rotationDegrees = 0))
        val turned = markOnFirstPage(albumOf(rotationDegrees = 90))

        assertTrue(
            upright.height > upright.width,
            "the picture is ${SOURCE_HEIGHT / SOURCE_WIDTH} times taller than it is wide, so unturned it should " +
                "leave a mark taller than it is wide; it left ${upright.width} by ${upright.height}",
        )
        assertTrue(
            turned.width > turned.height,
            "turned by a quarter it should leave a mark wider than it is tall; " +
                "it left ${turned.width} by ${turned.height}",
        )
    }

    // ------------------------------------------------------------ the document

    /** One page, one picture, turned by [rotationDegrees]. */
    private fun albumOf(rotationDegrees: Int): Document = document {
        pageSize = PageSize.A4
        margins = Margins.Fixed(top = MARGIN.pt, right = MARGIN.pt, bottom = MARGIN.pt, left = MARGIN.pt)
        clock = Clock { PdfDate.ofEpochSeconds(PINNED_EPOCH_SECONDS, offsetMinutes = OFFSET_MINUTES) }
        image(TALL_PICTURE, rotationDegrees = rotationDegrees)
    }

    // ------------------------------------------------------------ reading back

    /**
     * The content stream of page one, as text.
     *
     * `qpdf --qdf` writes the file back with its streams uncompressed
     * (`FlateFileTest` leans on the same property), so the operators this
     * library wrote appear in the output as the text that went in. A tool over
     * the bytes of a file we wrote, which is what the isolation rule permits:
     * external tools are used on our output, never as a source.
     */
    private fun contentStreamOfFirstPage(document: Document): String {
        var content = ""
        withRendered(document) { path ->
            val expanded = Files.createTempFile("pdfkt-rotation-qdf", ".pdf")
            try {
                val run = tool("qpdf", "--qdf", path.toString(), expanded.toString())
                assertEquals(0, run.status, "qpdf --qdf rejected the file:\n${run.output}")
                content = Files.readAllBytes(expanded).decodeToString()
            } finally {
                Files.deleteIfExists(expanded)
            }
        }
        return content
    }

    /** The bounding box of everything inked on page one of [document], in device pixels. */
    private fun markOnFirstPage(document: Document): Mark {
        var mark: Mark? = null
        withRendered(document) { path -> mark = markOn(path) }
        return checkNotNull(mark) { "the page was never rendered" }
    }

    private fun markOn(path: Path): Mark {
        val raster = rasterisePage(path, page = 1)
        val rows = raster.inkBands()
        assertTrue(rows.isNotEmpty(), "the page came out blank — no picture reached it")
        val top = rows.first().first
        val bottom = rows.last().last
        val columns = checkNotNull(raster.inkColumns(top..bottom)) { "rows carrying ink carried no ink" }
        return Mark(width = columns.last - columns.first + 1, height = bottom - top + 1)
    }

    /** What a picture left on the page: the size of its bounding box, in device pixels. */
    private data class Mark(val width: Int, val height: Int)

    private companion object {
        /** `0 b c 0 e f cm` — cos θ on the diagonal is zero for a quarter turn (§8.3.3). */
        val QUARTER_TURN = Regex("""(^|\s)0 -?[\d.]+ -?[\d.]+ 0 -?[\d.]+ -?[\d.]+ cm""")

        /** `a 0 0 d e f cm` — sin θ off the diagonal is zero for no turn at all (§8.3.3). */
        val NO_TURN = Regex("""(^|\s)-?[\d.]+ 0 0 -?[\d.]+ -?[\d.]+ -?[\d.]+ cm""")

        const val MARGIN = 40

        /**
         * Three times taller than it is wide, so which way up it was drawn is a
         * question the bounding box of its mark answers on its own.
         */
        const val SOURCE_WIDTH = 200
        const val SOURCE_HEIGHT = 600

        /** 2026-08-06T09:30:00 three hours east of UT, so the file carries a non-zero offset. */
        const val PINNED_EPOCH_SECONDS = 1_785_997_800L
        const val OFFSET_MINUTES = 180

        /**
         * The picture: a dark frame, encoded here rather than committed.
         *
         * One colour throughout and dark enough to be ink at any threshold, so
         * the bounding box of the mark is the box the picture was drawn in and
         * not the box of whatever inside it happened to be dark.
         */
        val TALL_PICTURE: ByteArray = run {
            val image = BufferedImage(SOURCE_WIDTH, SOURCE_HEIGHT, BufferedImage.TYPE_INT_RGB)
            val graphics = image.createGraphics()
            graphics.color = Color(20, 30, 40)
            graphics.fillRect(0, 0, SOURCE_WIDTH, SOURCE_HEIGHT)
            graphics.dispose()
            val encoded = ByteArrayOutputStream()
            check(ImageIO.write(image, "jpg", encoded)) { "this JDK has no jpg encoder to build a fixture with" }
            encoded.toByteArray()
        }
    }
}
