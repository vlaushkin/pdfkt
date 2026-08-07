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
import javax.imageio.ImageIO
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// ISO 32000-2:2020 §7.4.8 "DCTDecode filter", §7.4.4 "LZWDecode and FlateDecode
// filters", §8.8.1 "External objects" (Table 86), §8.9.5.1 "Image dictionaries"
// (Table 87), §9.4 "Text objects" — what the readers below are pointed at.

/**
 * The mixed-document shape: one `document { }` holding a heading, a
 * paragraph, pictures and a table, written the way a consumer writes it — the
 * DSL, `toByteArray()`, and nothing else. **There is no `io.pdfkt.core` import
 * in this file**, which is the claim `image()` exists to make true: without
 * it, a document with a picture in it would mean building an `ImageRef` and an
 * `ImageBlock` by hand, which is the engine's vocabulary in a caller's file.
 *
 * Both formats are in the document, because `image()` takes both and decides
 * between them by reading the bytes. They leave opposite marks in the file and
 * both are asserted: the JPEG is embedded as it arrived under `/DCTDecode`
 * (§7.4.8 makes the encoded data the stream), the PNG is decoded to samples and
 * deflated under `/FlateDecode` (§7.4.4). Neither picture is committed — both
 * are encoded when the test runs, by an encoder that never saw this library.
 *
 * "Beside the text" is asked twice, because the two questions differ.
 * `pdftotext` says the words came out in the order they were written, which a
 * picture must not disturb; a rendered page says the picture is *between* the
 * paragraph and the table rather than drawn over either — and a document whose
 * every block sits at one origin extracts exactly like one that is laid out.
 */
class RichImageGateTest {

    /** The photograph: mid blue, wider than it is tall, encoded as a JPEG. */
    private val photo: ByteArray =
        encoded("jpg", width = PHOTO_WIDTH, height = PHOTO_HEIGHT, colour = Color(20, 90, 160))

    /** The badge: dark red, a wide strip, encoded as a PNG. */
    private val badge: ByteArray =
        encoded("png", width = BADGE_WIDTH, height = BADGE_HEIGHT, colour = Color(150, 30, 30))

    @Test
    @DisplayName("the JPEG is embedded under DCTDecode and the PNG deflated, beside the document's text")
    fun theJpegIsEmbeddedUnderDctDecodeAndThePngDeflated() {
        if (skipWithoutQpdf("§7.4.8")) return

        withRendered(reportDocument()) { path ->
            assertQpdfCheckIsClean(path)
            assertEquals(1, pageCount(path), "the report is one page: heading, text, two pictures and a table")

            val images = imageXObjects(path)
            assertEquals(2, images.size, "the document draws two pictures and the file holds ${images.size}")

            val jpeg = images.single { image -> image.dictionary.contains("/DCTDecode") }
            assertContains(jpeg.dictionary, "/Subtype /Image")
            assertContains(jpeg.dictionary, "/Width $PHOTO_WIDTH")
            assertContains(jpeg.dictionary, "/Height $PHOTO_HEIGHT")
            assertContains(jpeg.dictionary, "/ColorSpace /DeviceRGB")
            assertContains(jpeg.dictionary, "/BitsPerComponent 8")
            assertContentEquals(photo, jpeg.streamData, "the JPEG did not come back out of the file unchanged")

            val png = images.single { image -> image.dictionary.contains("/FlateDecode") }
            assertContains(png.dictionary, "/Subtype /Image")
            assertContains(png.dictionary, "/Width $BADGE_WIDTH")
            assertContains(png.dictionary, "/Height $BADGE_HEIGHT")
            assertContains(png.dictionary, "/ColorSpace /DeviceRGB")
        }
    }

    /**
     * The words, in the order they were written. A picture is not text and
     * leaves none, so what this asks is that putting one in the middle of a
     * document does not move anything around it.
     */
    @Test
    @DisplayName("pdftotext reads the heading, the paragraph and the table in the order the DSL wrote them")
    fun pdftotextReadsTheDocumentInTheOrderItWasWritten() {
        if (skipWithoutPdftotext("§9.4")) return

        withRendered(reportDocument()) { path ->
            val page = extractedPages(path).single()
            val order = listOf(TITLE, LEAD, MONTH_COLUMN, FIRST_MONTH, SECOND_MONTH, CLOSING)

            var at = -1
            for (text in order) {
                val found = page.indexOf(text, startIndex = at + 1)
                assertTrue(found > at, "\"$text\" is missing or out of order in the extracted page:\n$page")
                at = found
            }
        }
    }

    /**
     * Where the pictures are: below the paragraph, above the table, and drawn as
     * blocks of their own rather than over the text.
     *
     * Measured as ink on a rendered page. A picture is a solid colour, so it
     * leaves a band hundreds of rows deep where a line of eleven-point type
     * leaves about twenty — that is what tells the two apart here, and no
     * structural tool answers it.
     */
    @Test
    @DisplayName("the pictures leave their own bands between the paragraph and the table")
    fun thePicturesLeaveTheirOwnBandsBetweenTheParagraphAndTheTable() {
        if (skipWithoutPdftoppm("the marks on the page")) return

        withRendered(reportDocument()) { path ->
            val bands = rasterisePage(path, page = 1).inkBands()
            val pictures = bands.filter { band -> band.last - band.first >= PICTURE_BAND_ROWS }
            assertTrue(
                pictures.isNotEmpty(),
                "no band of the page is deep enough to be a picture; the deepest is " +
                    "${bands.maxOf { band -> band.last - band.first }} rows",
            )

            val above = bands.count { band -> band.last < pictures.first().first }
            val below = bands.count { band -> band.first > pictures.last().last }
            assertTrue(above >= LEAST_BANDS_ABOVE, "only $above bands of text sit above the pictures")
            assertTrue(below >= LEAST_BANDS_BELOW, "only $below bands sit below the pictures")
        }
    }

    // ------------------------------------------------------------ the document

    /**
     * The document under test, with the facade package as the only pdfkt name in
     * scope: one heading, one paragraph, the two pictures, a table and a closing
     * line.
     */
    private fun reportDocument(): Document = document {
        pageSize = PageSize.A4
        margins = Margins.Fixed(top = MARGIN.pt, right = MARGIN.pt, bottom = MARGIN.pt, left = MARGIN.pt)
        metadata = Metadata(title = TITLE, author = AUTHOR)
        clock = Clock { PdfDate.ofEpochSeconds(PINNED_EPOCH_SECONDS, offsetMinutes = OFFSET_MINUTES) }

        heading(level = 1, TITLE)
        paragraph(LEAD)

        image(photo)
        image(badge)

        table {
            columns = TableColumnsFractional(listOf(2f, 1f))
            header {
                cell(MONTH_COLUMN)
                cell(REVENUE_COLUMN, align = TextAlign.Right)
            }
            row {
                cell(FIRST_MONTH)
                cell("1 240", align = TextAlign.Right)
            }
            row {
                cell(SECOND_MONTH)
                cell("1 310", align = TextAlign.Right)
            }
        }

        paragraph(CLOSING)
    }

    /**
     * A picture of one colour, encoded by the JDK when the test runs.
     *
     * `TYPE_INT_RGB` for both: an opaque picture has no alpha channel to lift
     * out, so the PNG becomes one image XObject and not an image and its
     * `/SMask` — which is what makes "two pictures, two image XObjects" a fair
     * count rather than a coincidence.
     */
    private fun encoded(format: String, width: Int, height: Int, colour: Color): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        graphics.color = colour
        graphics.fillRect(0, 0, width, height)
        graphics.dispose()

        val encoded = ByteArrayOutputStream()
        check(ImageIO.write(image, format, encoded)) { "this JDK has no $format encoder to build a fixture with" }
        return encoded.toByteArray()
    }

    private companion object {
        const val MARGIN = 40

        /** Wider than it is tall, so the column decides its size and the page has room for both. */
        const val PHOTO_WIDTH = 320
        const val PHOTO_HEIGHT = 120

        const val BADGE_WIDTH = 200
        const val BADGE_HEIGHT = 50

        const val TITLE = "Quarterly Report"
        const val AUTHOR = "pdfkt"
        const val LEAD = "Revenue by month, and what the quarter came to."
        const val MONTH_COLUMN = "Month"
        const val REVENUE_COLUMN = "Revenue"
        const val FIRST_MONTH = "October"
        const val SECOND_MONTH = "November"
        const val CLOSING = "Figures are provisional until the audit closes."

        /** 2026-08-06T09:30:00 three hours east of UT, so the file carries a non-zero offset. */
        const val PINNED_EPOCH_SECONDS = 1_785_997_800L
        const val OFFSET_MINUTES = 180

        /**
         * How deep a band has to be to be a picture rather than a line of type,
         * in rows of the raster: well above a line of eleven-point text at 150
         * dots per inch, which is some twenty rows, and well below the height of
         * either picture fitted to the column, which is hundreds.
         */
        const val PICTURE_BAND_ROWS = 250

        /** The heading and the paragraph. */
        const val LEAST_BANDS_ABOVE = 2

        /** The table, whose rules join its rows into one band, and the closing line. */
        const val LEAST_BANDS_BELOW = 2
    }
}
