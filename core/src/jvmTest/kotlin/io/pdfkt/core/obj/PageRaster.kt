/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.obj

import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// ISO 32000-2:2020 §8.3.2.3 "User space", §8.5 "Path construction and painting"

/**
 * A page of a file, drawn by a renderer that never saw this library, as the grey
 * value of every pixel.
 *
 * There are questions no structural tool answers. `qpdf --check` says the file
 * holds together and `pdftotext` says which characters are in it and in what
 * order — neither of them says the second line was drawn *below* the first one
 * rather than over it, because a document whose every block sits at the same
 * baseline extracts exactly like one that is laid out. A raster answers it: text
 * on separate lines leaves separate horizontal bands of ink, and text drawn on
 * top of itself leaves one.
 *
 * `pdftoppm` is used as a *tool* here, in the standing `qpdf` and `pdftotext`
 * have in these tests: what is compared is the pixels it produced from our
 * bytes, never its source.
 */
internal class PageRaster(val width: Int, val height: Int, private val pixels: ByteArray) {
    init {
        assertEquals(width * height, pixels.size, "the raster is not $width by $height pixels")
    }

    /** Whether row [y] carries any pixel at least as dark as [threshold]. */
    fun rowHasInk(y: Int, threshold: Int = INK_THRESHOLD): Boolean {
        val start = y * width
        for (x in 0 until width) {
            if ((pixels[start + x].toInt() and BYTE_MASK) <= threshold) return true
        }
        return false
    }

    /**
     * The horizontal bands of ink on the page, top to bottom: every maximal run
     * of rows that carry a mark, separated by rows that carry none.
     *
     * One line of text is one band. Two lines are two, so long as the leading
     * leaves a row of white between the descenders of the upper and the
     * ascenders of the lower — which is what a line height of more than 1.0
     * buys and what this counts.
     */
    fun inkBands(threshold: Int = INK_THRESHOLD): List<IntRange> {
        val bands = mutableListOf<IntRange>()
        var start = -1
        for (y in 0 until height) {
            val inked = rowHasInk(y, threshold)
            if (inked && start < 0) start = y
            if (!inked && start >= 0) {
                bands += start until y
                start = -1
            }
        }
        if (start >= 0) bands += start until height
        return bands
    }

    companion object {
        /**
         * How dark a pixel has to be to count as a mark, out of 255.
         *
         * Well below the grey a renderer's antialiasing leaves beside a glyph,
         * so that the haze around a descender does not join two lines into one
         * band, and well above the lightest thing this library draws — a
         * horizontal rule at 50% grey (`Theme.ruleColor`) lands at 128.
         */
        const val INK_THRESHOLD: Int = 160

        const val BYTE_MASK: Int = 0xFF
    }
}

/**
 * Page [page] of [path], one-based, rendered at [dpi] dots per inch.
 *
 * The renderer writes a binary grey map (`P5`) to its standard output, which is
 * parsed here rather than through an image library: the format is a magic
 * number, three numbers and one byte per pixel, and a test harness that decodes
 * it in twenty lines does not need a decoder in the way.
 */
internal fun rasterisePage(path: Path, page: Int, dpi: Int = RASTER_DPI): PageRaster {
    val process =
        ProcessBuilder(
            "pdftoppm",
            "-gray",
            "-r",
            dpi.toString(),
            "-f",
            page.toString(),
            "-l",
            page.toString(),
            "-singlefile",
            path.toString(),
        ).start()
    val bytes = process.inputStream.readBytes()
    val errors = process.errorStream.readBytes().decodeToString()
    assertTrue(process.waitFor(60, TimeUnit.SECONDS), "pdftoppm did not finish")
    assertEquals(0, process.exitValue(), "pdftoppm could not render page $page:\n$errors")
    return parseGreyMap(bytes)
}

/**
 * Page [page] of [path] in colour — the same renderer, asked for a pixel map
 * rather than a grey map.
 *
 * Grey answers where the ink is; it cannot answer whether a red image came out
 * red, or whether the transparent half of one let the page through. Those are
 * questions about `/ColorSpace` and `/SMask`, and they need the three
 * components.
 */
internal fun rasteriseColourPage(path: Path, page: Int, dpi: Int = RASTER_DPI): ColourPageRaster {
    val process =
        ProcessBuilder(
            "pdftoppm",
            "-r",
            dpi.toString(),
            "-f",
            page.toString(),
            "-l",
            page.toString(),
            "-singlefile",
            path.toString(),
        ).start()
    val bytes = process.inputStream.readBytes()
    val errors = process.errorStream.readBytes().decodeToString()
    assertTrue(process.waitFor(60, TimeUnit.SECONDS), "pdftoppm did not finish")
    assertEquals(0, process.exitValue(), "pdftoppm could not render page $page:\n$errors")
    return parsePixelMap(bytes)
}

/**
 * A page as red, green and blue per pixel — what a renderer made of the colour
 * spaces and the soft masks in the file.
 */
internal class ColourPageRaster(val width: Int, val height: Int, private val pixels: ByteArray) {
    init {
        assertEquals(width * height * 3, pixels.size, "the raster is not $width by $height RGB pixels")
    }

    /** The colour at ([x], [y]) as three values from 0 to 255. */
    fun pixelAt(x: Int, y: Int): Triple<Int, Int, Int> {
        assertTrue(x in 0 until width && y in 0 until height, "($x, $y) is outside a $width by $height page")
        val at = (y * width + x) * 3
        return Triple(
            pixels[at].toInt() and PageRaster.BYTE_MASK,
            pixels[at + 1].toInt() and PageRaster.BYTE_MASK,
            pixels[at + 2].toInt() and PageRaster.BYTE_MASK,
        )
    }
}

/**
 * A binary pixel map as the tool wrote it: `P6`, then three bytes per pixel —
 * the same header as a grey map, with a different magic number.
 */
private fun parsePixelMap(bytes: ByteArray): ColourPageRaster {
    val map = parseNetpbm(bytes, "P6", componentsPerPixel = 3)
    return ColourPageRaster(map.width, map.height, map.samples)
}

/**
 * A binary grey map as the tool wrote it: `P5`, the width, the height, the
 * maximum value, one whitespace character, and then one byte per pixel.
 */
private fun parseGreyMap(bytes: ByteArray): PageRaster {
    val map = parseNetpbm(bytes, "P5", componentsPerPixel = 1)
    return PageRaster(map.width, map.height, map.samples)
}

/** The header and the samples of a binary netpbm map. */
private class NetpbmMap(val width: Int, val height: Int, val samples: ByteArray)

/**
 * A binary netpbm map of magic number [magic]: the magic number, the width, the
 * height, the maximum value, one whitespace character, and then
 * [componentsPerPixel] bytes per pixel.
 */
private fun parseNetpbm(bytes: ByteArray, magic: String, componentsPerPixel: Int): NetpbmMap {
    var at = 0

    fun token(): String {
        while (at < bytes.size) {
            val byte = bytes[at].toInt().toChar()
            when {
                byte.isWhitespace() -> at++
                byte == '#' -> while (at < bytes.size && bytes[at].toInt().toChar() != '\n') at++
                else -> break
            }
        }
        val start = at
        while (at < bytes.size && !bytes[at].toInt().toChar().isWhitespace()) at++
        return bytes.decodeToString(start, at)
    }

    assertEquals(magic, token(), "pdftoppm did not write a $magic map")
    val width = token().toInt()
    val height = token().toInt()
    val maximum = token().toInt()
    assertEquals(PageRaster.BYTE_MASK, maximum, "a map of more than one byte per component")
    // Exactly one whitespace character separates the header from the samples.
    at++
    val size = width * height * componentsPerPixel
    return NetpbmMap(width, height, bytes.copyOfRange(at, at + size))
}

/**
 * Whether pdftoppm is missing, in which case the test that asked says so and
 * stops — the same arrangement as [skipWithoutQpdf], for the same reason: the
 * tool is a dependency of the check, not of the library.
 */
internal fun skipWithoutPdftoppm(clause: String): Boolean {
    val installed =
        try {
            ProcessBuilder("pdftoppm", "-v").redirectErrorStream(true).start().waitFor() == 0
        } catch (absent: IOException) {
            println("pdftoppm could not be started: ${absent.message}")
            false
        }
    if (!installed) println("pdftoppm is not installed — skipping the raster check of $clause")
    return !installed
}

/**
 * The resolution the pages are rendered at.
 *
 * Two lines of eleven-point text set 1.2 line heights apart leave about two
 * points of white between them; at this resolution that is four rows of pixels,
 * which is enough for the gap to survive antialiasing and few enough that a page
 * costs milliseconds.
 */
internal const val RASTER_DPI: Int = 150
