/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.font

import io.pdfkt.core.content.ContentStreamBuilder
import io.pdfkt.core.doc.PdfDocumentWriter
import io.pdfkt.core.doc.pdfRectangle
import io.pdfkt.core.obj.PdfDict
import io.pdfkt.core.obj.PdfName
import io.pdfkt.core.obj.RASTER_DPI
import io.pdfkt.core.obj.assertQpdfCheckIsClean
import io.pdfkt.core.obj.extractedText
import io.pdfkt.core.obj.filteredStream
import io.pdfkt.core.obj.firstPageOf
import io.pdfkt.core.obj.rasteriseColourPage
import io.pdfkt.core.obj.referenceIn
import io.pdfkt.core.obj.showObject
import io.pdfkt.core.obj.skipWithoutPdftoppm
import io.pdfkt.core.obj.skipWithoutPdftotext
import io.pdfkt.core.obj.skipWithoutQpdf
import kotlinx.io.asSink
import kotlinx.io.buffered
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// ISO 32000-2:2020 §9.6.4 "Type 3 fonts" (Tables 110, 111),
// §9.6.5.3 "Encodings for Type 3 fonts", §8.9.5.1 "Image dictionaries —
// General" (Table 87), §8.9.5.4 "Soft-mask images", §9.10.3 "ToUnicode CMaps",
// §7.8.3 "Resource dictionaries"
// Unicode Technical Standard #51 §2.3 "Emoji ZWJ Sequences"

/**
 * The seam of [EmojiRasterizer], driven by a real platform rasterizer: `java.awt`
 * draws the pictures, `javax.imageio` encodes them as PNG, and a reader that
 * never saw this library walks the chain §9.6.4 defines down to them.
 *
 * `RasterizedEmojiFontTest` asserts the objects, over pictures a test wrote out
 * byte by byte. Nothing about that says a PNG some other stack produced decodes,
 * that the alpha such a stack puts in it becomes the `SMask` of §8.9.5.4, or
 * that what comes out the far end is in colour and in the right place. That is
 * what this asserts, with qpdf, pdftotext and pdftoppm as tools on the bytes —
 * never as sources.
 *
 * `java.awt` is in **this test and nowhere else**: `commonMain` is platform-free
 * by design, which is the whole reason the
 * rasterizer is an interface the caller implements rather than something the
 * library does.
 *
 * The pictures are discs, not emoji: this proves the seam, and what a platform
 * draws into it is the platform's business. A disc has a colour of its own per
 * sequence, an alpha the page has to honour, and edges a test can find.
 */
class AwtRasterizedEmojiTest {
    /**
     * The chain of §9.6.4 followed from the page down, object by object, by a
     * reader that has only the file — down to the PNG `java.awt` drew.
     */
    @Test
    fun `qpdf follows the object chain from the page down to the rasterized picture`() {
        if (skipWithoutQpdf("§9.6.4")) return

        withDocument(WAVE + FAMILY) { path ->
            assertQpdfCheckIsClean(path)

            // Table 110: a Type 3 font, reached through the resource dictionary
            // the content stream's Tf named it in (§7.8.3).
            val font = emojiFontIn(path)
            assertTrue("/Subtype /Type3" in font, font)
            // The em the rasterizer was asked for, so a unit of glyph space is
            // one of its pixels. qpdf writes the array with spaces of its own.
            assertTrue(Regex("/FontMatrix \\[ ${1.0 / EM_PIXELS} 0 0 ${1.0 / EM_PIXELS} 0 0 ]") in font, font)
            assertTrue("/FirstChar 1" in font, font)

            // One code per emoji — and the family is one emoji, five code points
            // and two joiners notwithstanding (UTS #51 §2.3).
            assertTrue("/LastChar 2" in font, font)
            val differences = assertNotNull(
                Regex("/Differences\\s*\\[\\s*1\\s+((?:/g\\d+\\s*)+)]").find(font)?.groupValues?.get(1),
                "the font's encoding names no glyphs:\n$font",
            ).trim().split(Regex("\\s+"))
            assertEquals(2, differences.size, differences.toString())

            // Table 111: "The stream shall include as its first operator either
            // d0 or d1", and this one paints an image, so d0.
            val charProcs = dictionaryIn(font, "CharProcs")
            val description = filteredStream(
                path,
                assertNotNull(referenceIn(charProcs, differences.first().removePrefix("/")), charProcs),
            )
            assertTrue(Regex("^${ADVANCE_PIXELS}(\\.0)? 0 d0\\n").containsMatchIn(description), description)
            val painted = assertNotNull(
                Regex("/(Im\\d+) Do").find(description)?.groupValues?.get(1),
                "the glyph description paints no XObject:\n$description",
            )

            // §9.6.4: the picture is in the font's own Resources, and it is the
            // size the rasterizer said it was — Table 87's Width and Height.
            val xObjects = dictionaryIn(dictionaryIn(font, "Resources"), "XObject")
            val image = showObject(path, assertNotNull(referenceIn(xObjects, painted), xObjects))
            assertTrue("/Subtype /Image" in image, image)
            assertTrue("/Width $PICTURE_PIXELS" in image, image)
            assertTrue("/Height $PICTURE_PIXELS" in image, image)
            assertTrue("/ColorSpace /DeviceRGB" in image, image)

            // "an image XObject … serving as a soft-mask image" (§8.9.5.4): what
            // the alpha channel of the rasterizer's PNG became, without which
            // the disc would arrive on a black square.
            assertTrue("/SMask" in image, image)
        }
    }

    /**
     * "In the absence of a ToUnicode entry, no information would be available
     * about what the glyphs mean" (§9.10.3) — and here one code stands for a
     * whole ZWJ sequence, which is what a rasterizer can do and a `cmap` cannot.
     */
    @Test
    fun `the ToUnicode CMap maps the one code of a ZWJ sequence back to all of it`() {
        if (skipWithoutQpdf("§9.10.3")) return

        withDocument(FAMILY) { path ->
            val font = emojiFontIn(path)
            val cmap = filteredStream(path, assertNotNull(referenceIn(font, "ToUnicode"), font))

            // "consistent with the encoding that the font uses" — a simple font's
            // codes are single bytes (§9.6.1).
            assertTrue("begincodespacerange\n<00> <FF>" in cmap, cmap)
            val utf16 = FAMILY.map { "%04X".format(it.code) }.joinToString("")
            assertTrue("<01> <$utf16>" in cmap, "the CMap does not map code 1 to the family:\n$cmap")
        }
    }

    /**
     * What the `ToUnicode` CMap is for, seen from outside: a tool that knows
     * nothing of this library reads the page and gets the emoji back — the whole
     * sequence, not the parts of it.
     */
    @Test
    fun `pdftotext extracts the sequences that were drawn`() {
        if (skipWithoutPdftotext("§9.10.3")) return

        withDocument(WAVE + FAMILY) { path ->
            assertEquals(WAVE + FAMILY, extractedText(path))
        }
    }

    /**
     * The page as a renderer draws it. Everything above asserts objects and the
     * entries between them, and a font whose `cm` were transposed or scaled by
     * the em twice would satisfy all of it and paint a page nobody wants.
     *
     * The disc `java.awt` drew is red, its picture is [PICTURE_PIXELS] pixels of
     * an em of [EM_PIXELS] and its bearings put it on the baseline — so at
     * [SIZE] points the middle of it is where this computes, and the corner of
     * its box, which the alpha of the PNG left transparent, is still white page.
     */
    @Test
    fun `the picture renders in colour where the bearings put it`() {
        if (skipWithoutPdftoppm("§9.6.4")) return

        withDocument(WAVE) { path ->
            val raster = rasteriseColourPage(path, page = 1)
            val scale = SIZE / EM_PIXELS

            // The rectangle the glyph description maps its image onto, in points
            // of the page: the picture stands at the origin of the glyph, whose
            // top edge is BEARING_Y pixels above the baseline.
            val left = TEXT_X + BEARING_X * scale
            val top = TEXT_Y + BEARING_Y * scale
            val middle = pixel(left + PICTURE_PIXELS * scale / 2, top - PICTURE_PIXELS * scale / 2, raster)

            val (red, green, blue) = raster.pixelAt(middle.first, middle.second)
            assertTrue(
                red - maxOf(green, blue) > COLOURFUL,
                "the middle of the disc is ($red, $green, $blue), and java.awt drew it red",
            )

            // A corner of the same box: outside the disc, where the PNG's alpha
            // is zero, so the page shows through (§8.9.5.4).
            val corner = pixel(left + CORNER * scale, top - CORNER * scale, raster)
            assertEquals(
                Triple(WHITE, WHITE, WHITE),
                raster.pixelAt(corner.first, corner.second),
                "the transparent corner of the picture is not white page",
            )
        }
    }

    // ---- The rasterizer ----------------------------------------------------

    /**
     * A picture per emoji, drawn by `java.awt` into a `BufferedImage` and handed
     * over as the PNG `javax.imageio` writes — which is exactly the shape a
     * caller's own rasterizer takes.
     *
     * The colour is derived from the sequence, so two emoji are two pictures;
     * the alpha outside the disc is zero, which is what makes the `SMask` of
     * §8.9.5.4 turn up in the file.
     */
    private val awt = EmojiRasterizer { sequence, emPixels ->
        val side = PICTURE_PIXELS * emPixels / EM_PIXELS
        val image = BufferedImage(side, side, BufferedImage.TYPE_INT_ARGB)
        val canvas = image.createGraphics()
        canvas.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        canvas.color = Color(RED, sequence.hashCode() and GREEN_MASK, 0)
        canvas.fillOval(0, 0, side, side)
        canvas.dispose()

        val png = ByteArrayOutputStream()
        assertTrue(ImageIO.write(image, "png", png), "javax.imageio wrote no PNG")
        RasterGlyph(
            png = png.toByteArray(),
            width = side,
            height = side,
            bearingX = BEARING_X * emPixels / EM_PIXELS,
            bearingY = BEARING_Y * emPixels / EM_PIXELS,
            advance = ADVANCE_PIXELS * emPixels / EM_PIXELS,
        )
    }

    // ---- Writing and reading the file --------------------------------------

    /** The Type 3 font (Table 110) the first page names `/E1` (§7.8.3). */
    private fun emojiFontIn(path: Path): String {
        val page = showObject(path, firstPageOf(path))
        val font = assertNotNull(referenceIn(page, RESOURCE_NAME), "the page names no font /$RESOURCE_NAME:\n$page")
        return showObject(path, font)
    }

    /**
     * The dictionary `/key` of [dictionary] carries, cut out of it: from the
     * `<<` after the key to the `>>` that balances it. `CharProcs` and
     * `Resources` are typed "dictionary" by Table 110 and are written in place.
     */
    private fun dictionaryIn(dictionary: String, key: String): String {
        val start = dictionary.indexOf("/$key").also {
            assertTrue(it >= 0, "there is no /$key entry:\n$dictionary")
        }.let { dictionary.indexOf("<<", it) }
        assertTrue(start >= 0, "the /$key entry is not a dictionary:\n$dictionary")
        var depth = 0
        var at = start
        while (at < dictionary.length) {
            when {
                dictionary.startsWith("<<", at) -> {
                    depth++
                    at += 2
                }

                dictionary.startsWith(">>", at) -> {
                    depth--
                    at += 2
                    if (depth == 0) return dictionary.substring(start, at)
                }

                else -> at++
            }
        }
        throw AssertionError("the /$key dictionary is not closed:\n$dictionary")
    }

    /** Where a point of the page falls in the raster, which counts from the top. */
    private fun pixel(x: Double, y: Double, raster: io.pdfkt.core.obj.ColourPageRaster): Pair<Int, Int> = Pair(
        (x * RASTER_DPI / POINTS_PER_INCH).toInt().coerceIn(0, raster.width - 1),
        ((PAGE_HEIGHT - y) * RASTER_DPI / POINTS_PER_INCH).toInt().coerceIn(0, raster.height - 1),
    )

    /** Writes a one-page document showing [text] in the rasterized font, and runs [block] on it. */
    private fun withDocument(text: String, block: (Path) -> Unit) {
        val path = Files.createTempFile("pdfkt-rasterized-emoji", ".pdf")
        try {
            val font = RasterizedEmojiFont("awt", awt, EM_PIXELS)
            val name = PdfName(RESOURCE_NAME)

            val content = ContentStreamBuilder()
            content.beginText()
            content.textFont(name, SIZE)
            content.nextLine(TEXT_X, TEXT_Y)
            content.showText(font.encode(text))
            content.endText()

            Files.newOutputStream(path).asSink().buffered().use { sink ->
                val document = PdfDocumentWriter(sink)
                val fonts = PdfDict(mapOf(name to document.define(font.fontDictionary(document::define))))
                document.addPage(
                    mediaBox = pdfRectangle(0.0, 0.0, PAGE_WIDTH, PAGE_HEIGHT),
                    resources = PdfDict(mapOf(PdfName("Font") to fonts)),
                    content = content.build(),
                )
                document.finish(fileIdentifier = FILE_IDENTIFIER)
            }
            block(path)
        } finally {
            path.deleteIfExists()
        }
    }

    private companion object {
        /** What the page calls the emoji font (§7.8.3). */
        const val RESOURCE_NAME: String = "E1"

        /** U+1F44B WAVING HAND SIGN, one code point. */
        const val WAVE: String = "👋"

        /** U+1F468 ZWJ U+1F469 ZWJ U+1F467 — one emoji of five code points (UTS #51 §2.3). */
        const val FAMILY: String = "👨‍👩‍👧"

        /** The em the rasterizer is asked for, and the pixels it answers with. */
        const val EM_PIXELS: Int = 64
        const val PICTURE_PIXELS: Int = 48
        const val ADVANCE_PIXELS: Int = 56
        const val BEARING_X: Int = 4
        const val BEARING_Y: Int = 48

        /** Well inside the box and well outside the disc drawn in it. */
        const val CORNER: Int = 3

        /** The colour java.awt fills the disc with: red, and a green that varies by sequence. */
        const val RED: Int = 220
        const val GREEN_MASK: Int = 0x3F

        /** The page the emoji is drawn on, and where on it: US Letter, §14.11.2's default. */
        const val PAGE_WIDTH: Double = 612.0
        const val PAGE_HEIGHT: Double = 792.0
        const val TEXT_X: Double = 72.0
        const val TEXT_Y: Double = 600.0

        /** The `Tf` size: "a number representing a scale factor" (Table 103). */
        const val SIZE: Double = 48.0

        /** "1 unit in user space, or 1 ⁄ 72 inch" (§9.2.2) — the raster's own scale. */
        const val POINTS_PER_INCH: Double = 72.0

        /** How far apart two components have to be for a pixel to be ink rather than grey. */
        const val COLOURFUL: Int = 60

        const val WHITE: Int = 255

        /** A fixed identifier, so two runs of this test produce the same bytes (§14.4). */
        val FILE_IDENTIFIER: ByteArray = ByteArray(16) { it.toByte() }
    }
}
