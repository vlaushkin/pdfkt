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
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.roundToInt
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// ISO 32000-2:2020 §7.7.3 "Page tree" and §9.4 "Text objects" — the structure the
// two pages and their lines are read back out of. Where a running head goes and
// what reserves its strip is this library's own design.

/**
 * The shape the rich text facade was asked for: a page with margins, a colophon
 * at the head and another at the foot, two pages because the caller said so, and
 * a body in an alphabet no standard-14 font draws.
 *
 * It is the shape of a scanned page carried into a document — a running head
 * naming the source, a running foot naming the state of the text, and one page
 * per original — and it is written here the way a consumer writes it: the DSL,
 * `toByteArray()`, and nothing else. **There is no `io.pdfkt.core` import in this
 * file, and that is the first assertion**: without the colophon forms, a caller
 * reaching for either would have to build a `BandSpec` and a `DocumentSpec` by
 * hand, which
 * is the engine's vocabulary, not the facade's. [theFileNamesNoEngineType] holds
 * the claim against the file's own source so that it stays true.
 *
 * The rest is measured on a rendered page rather than extracted, because the two
 * questions a band raises are geometric. Extraction says a header is *in* the
 * file; only the marks say it is in the strip reserved for it, above the text
 * rather than on top of it, and flush to the side it was aligned to. A colophon
 * drawn over the first line of the body extracts exactly like one drawn above it.
 */
class RichColophonGateTest {

    /** The system's DejaVu Sans: the body is Cyrillic, which no standard-14 font draws. */
    private val sans: ByteArray = systemFontBytes(
        "DejaVuSans.ttf",
        why = "a document whose colophons and body are Cyrillic is set",
    )

    @Test
    @DisplayName("the two pages carry both colophons and the body the break put on each")
    fun theTwoPagesCarryTheirColophonsAndTheBodyTheBreakPutThere() {
        if (skipWithoutQpdf("§7.7.3")) return
        if (skipWithoutPdftotext("§9.4")) return

        withRendered(ocrDocument()) { path ->
            assertQpdfCheckIsClean(path)
            assertEquals(PAGES, pageCount(path), "pageBreak() should have ended the first page and begun a second")

            val pages = extractedPages(path)
            assertEquals(PAGES, pages.size, "pdftotext and qpdf disagree about how many pages there are")

            for ((index, page) in pages.withIndex()) {
                assertTrue(page.contains(HEAD), "page ${index + 1} lost its running head")
                assertTrue(page.contains(FOOT), "page ${index + 1} lost its running foot")
            }

            assertTrue(pages[0].contains(FIRST_BODY), "the first page lost the text written before the break")
            assertTrue(pages[1].contains(SECOND_BODY), "the second page lost the text written after the break")
            assertFalse(pages[0].contains(SECOND_BODY), "the text after the break stayed on the first page")
            assertFalse(pages[1].contains(FIRST_BODY), "the text before the break followed the break onto page 2")
        }
    }

    /**
     * Where the marks are: inside the margins, the colophons in the strips
     * reserved for them, the body below the head and above the foot, and each
     * colophon flush to the side it was given.
     */
    @Test
    @DisplayName("each colophon sits in its own strip inside the margins and flush to its own side")
    fun eachColophonSitsInItsStripInsideTheMarginsAndFlushToItsSide() {
        if (skipWithoutPdftoppm("the marks on the page")) return

        withRendered(ocrDocument()) { path ->
            val raster = rasterisePage(path, page = 1)
            val bands = raster.inkBands()
            assertTrue(
                bands.size >= LEAST_BANDS,
                "a page with a head, a body and a foot leaves at least $LEAST_BANDS bands of ink, not ${bands.size}",
            )

            val head = bands.first()
            val foot = bands.last()
            val body = bands.subList(1, bands.size - 1)

            // The margins: nothing is drawn in them, at either end of the page.
            assertTrue(
                head.first >= TOP_MARGIN.px - SLOP,
                "the topmost mark is at row ${head.first}, above the ${TOP_MARGIN}pt top margin",
            )
            assertTrue(
                foot.last <= raster.height - BOTTOM_MARGIN.px + SLOP,
                "the lowest mark is at row ${foot.last}, below the ${BOTTOM_MARGIN}pt bottom margin",
            )

            // The strips: each colophon is inside the reserve it was given, which
            // begins where the margin ends.
            assertTrue(
                head.last <= TOP_MARGIN.px + bandPx + SLOP,
                "the running head reaches row ${head.last}, past its ${bandPx}px strip",
            )
            assertTrue(
                foot.first >= raster.height - BOTTOM_MARGIN.px - bandPx - SLOP,
                "the running foot begins at row ${foot.first}, above its ${bandPx}px strip",
            )

            // The reserve is taken off the content area rather than shared with
            // it: the body begins below the header's strip and ends above the
            // footer's. This is the assertion a header drawn over the first
            // paragraph fails, and no structural tool asks it.
            assertTrue(
                body.first().first > TOP_MARGIN.px + bandPx,
                "the body begins at row ${body.first().first}, inside the header's strip",
            )
            assertTrue(
                body.last().last < raster.height - BOTTOM_MARGIN.px - bandPx,
                "the body ends at row ${body.last().last}, inside the footer's strip",
            )

            // The sides: the head is set flush left and the foot flush right, so
            // each reaches its own margin and stops well short of the other.
            val headInk = assertNotNull(raster.inkColumns(head), "the running head left no mark")
            val footInk = assertNotNull(raster.inkColumns(foot), "the running foot left no mark")

            assertTrue(
                headInk.first <= LEFT_MARGIN.px + SLOP,
                "the running head begins at column ${headInk.first}, not at the ${LEFT_MARGIN}pt left margin",
            )
            assertTrue(
                headInk.last < raster.width - RIGHT_MARGIN.px - CLEAR.px,
                "the running head reaches column ${headInk.last}: it is not set flush left",
            )

            assertTrue(
                footInk.last >= raster.width - RIGHT_MARGIN.px - SLOP,
                "the running foot ends at column ${footInk.last}, not at the ${RIGHT_MARGIN}pt right margin",
            )
            assertTrue(
                footInk.first > LEFT_MARGIN.px + CLEAR.px,
                "the running foot begins at column ${footInk.first}: it is not set flush right",
            )
        }
    }

    /**
     * The import list of this file, read off the disk: no name in it begins with
     * `io.pdfkt.core`.
     *
     * The compiler already enforces it for as long as nobody adds one — every
     * engine type this document could have named would need an import to resolve.
     * What this adds is the next edit: a colophon or a break that quietly went
     * back to `BandSpec` and `DocumentSpec` would make the file compile and this
     * test fail, which is where the claim belongs. The claim of this file is
     * that the OCR shape goes through the DSL alone.
     */
    @Test
    @DisplayName("the file names no engine type")
    fun theFileNamesNoEngineType() {
        val source = SOURCE_PATHS.map { candidate -> Path.of(candidate) }
            .firstOrNull { candidate -> Files.isReadable(candidate) }
        val path = assertNotNull(
            source,
            "this test reads its own source and could not find it; the paths tried, relative to the " +
                "working directory ${Path.of("").toAbsolutePath()}, were ${SOURCE_PATHS.joinToString(", ")}",
        )

        val engineImports = Files.readAllLines(path)
            .map { line -> line.trim() }
            .filter { line -> line.startsWith("import ") && line.contains(ENGINE_PACKAGE) }

        assertTrue(
            engineImports.isEmpty(),
            "the DSL is meant to cover this document on its own, but the file imports the engine: " +
                engineImports.joinToString("; "),
        )
    }

    // ------------------------------------------------------------ the document

    /**
     * The document under test, written with the facade package as the only pdfkt
     * name in scope.
     */
    private fun ocrDocument(): Document = document {
        pageSize = PageSize.A4
        margins = Margins.Fixed(
            top = TOP_MARGIN.pt,
            right = RIGHT_MARGIN.pt,
            bottom = BOTTOM_MARGIN.pt,
            left = LEFT_MARGIN.pt,
        )
        // Every face the document can reach is the supplied one: a colophon left
        // at the standard-14 default would stop the render on its first Cyrillic
        // letter, which is the no-silent-loss policy working rather than a fault
        // of this sample.
        theme = Theme(bodyFamily = FontFamily.of(FontRefSupplied(sans, id = FONT_ID)))
        metadata = Metadata(title = TITLE, author = AUTHOR)
        clock = Clock { PdfDate.ofEpochSeconds(PINNED_EPOCH_SECONDS, offsetMinutes = OFFSET_MINUTES) }

        header(HEAD)
        footer(FOOT)

        paragraph(FIRST_BODY)
        pageBreak()
        paragraph(SECOND_BODY)
    }

    // --------------------------------------------------------------- the ruler

    /** This many points as rows or columns of the raster, at the resolution it was drawn at. */
    private val Int.px: Int get() = (this * RASTER_DPI / POINTS_PER_INCH).roundToInt()

    /** The default strip a colophon is given, in pixels of the raster. */
    private val bandPx: Int get() = (DEFAULT_BAND_HEIGHT.value * RASTER_DPI / POINTS_PER_INCH).roundToInt()

    private companion object {
        const val FONT_ID = "DejaVuSans"

        /** Four different margins, so that an inset applied to the wrong side shows up as one. */
        const val TOP_MARGIN = 50
        const val RIGHT_MARGIN = 35
        const val BOTTOM_MARGIN = 40
        const val LEFT_MARGIN = 45

        const val PAGES = 2

        /** A head, at least one line of body, and a foot. */
        const val LEAST_BANDS = 3

        const val TITLE = "Распознанный текст"
        const val AUTHOR = "pdfkt"
        const val HEAD = "Отчёт за квартал"
        const val FOOT = "Черновик"
        const val FIRST_BODY = "Первая страница набрана кириллицей и заканчивается разрывом."
        const val SECOND_BODY = "Вторая страница начинается сразу после разрыва, поставленного из DSL."

        /** 2026-08-06T09:30:00 three hours east of UT, so the file carries a non-zero offset. */
        const val PINNED_EPOCH_SECONDS = 1_785_997_800L
        const val OFFSET_MINUTES = 180

        const val POINTS_PER_INCH = 72.0

        /**
         * How far a mark may fall outside the box it is measured against, in
         * pixels: a glyph's side bearing and the renderer's antialiasing, and
         * nothing like the distance between one margin and the other.
         */
        const val SLOP = 8

        /**
         * How much white a colophon has to leave on the side it is *not* set
         * against, in points. Both lines are far shorter than the column, so a
         * line that reached within this of the far margin was not aligned at all.
         */
        const val CLEAR = 100

        const val ENGINE_PACKAGE = "io.pdfkt.core"

        /**
         * Where this file is, from the working directory a test runs in — the
         * module, with the repository root as the fallback for a runner that
         * starts higher up.
         */
        val SOURCE_PATHS: List<String> = listOf(
            "src/jvmTest/kotlin/io/pdfkt/api/RichColophonGateTest.kt",
            "core/src/jvmTest/kotlin/io/pdfkt/api/RichColophonGateTest.kt",
        )
    }
}
