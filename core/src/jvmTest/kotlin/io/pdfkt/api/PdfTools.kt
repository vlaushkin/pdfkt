/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.api

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.deleteIfExists
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// ISO 32000-2:2020 §7.5 "File structure", §8.3.2.3 "User space", §9.4 "Text
// objects" — what the three readers below are pointed at. Nothing here is a
// clause implemented; it is the instrument the facade's documents are measured
// with.

/**
 * The three readers this module's documents are held up against — qpdf for the
 * structure, pdftotext for the words, pdftoppm for the marks — and the temporary
 * file a `document { }` is rendered into for them.
 *
 * All three are used as *tools*: what is compared is what they made of our
 * bytes, never their source.
 *
 * The engine's own tests carry the same instrument for `:core`'s documents. It
 * is not shared across the two modules and deliberately so: a test source set is
 * not published, wiring one module's tests into another's classpath to save
 * fifty lines would tie the facade's build to the engine's test layout, and the
 * questions the two ask differ — everything here goes through the DSL and never
 * touches a plan, because a facade that needed a plan to be checked would not be
 * a facade.
 */
internal class ToolRun(val status: Int, val bytes: ByteArray) {
    /**
     * What the tool wrote, as text. Every reader here but one asks a question
     * whose answer is text; the one that does not is the stream of an image
     * XObject, which is a JPEG and is compared as [bytes].
     */
    val output: String get() = bytes.decodeToString()
}

/** [command] run to completion, its error stream folded into its output. */
internal fun tool(vararg command: String): ToolRun {
    val process = ProcessBuilder(command.toList()).redirectErrorStream(true).start()
    val bytes = process.inputStream.readBytes()
    assertTrue(process.waitFor(TOOL_TIMEOUT_SECONDS, TimeUnit.SECONDS), "${command.first()} did not finish")
    return ToolRun(process.exitValue(), bytes)
}

/**
 * [document] rendered through the seam and written to a file that lives for the
 * duration of [block].
 *
 * This is the whole of how a test here reaches a PDF: `toByteArray()` and
 * nothing else. No test in this module builds a `DocumentSpec`, resolves,
 * fragments or emits — what is being checked is that a caller who writes the
 * DSL gets those behaviours, so reaching past the DSL to arrange them would be
 * checking the engine twice and the facade not at all.
 */
internal fun withRendered(document: Document, block: (Path) -> Unit) {
    val path = Files.createTempFile("pdfkt-richtext", ".pdf")
    try {
        Files.write(path, document.toByteArray())
        block(path)
    } finally {
        path.deleteIfExists()
    }
}

/** `qpdf --check` found nothing to say about the file. */
internal fun assertQpdfCheckIsClean(path: Path) {
    val check = tool("qpdf", "--check", path.toString())
    assertEquals(0, check.status, "qpdf --check rejected the file:\n${check.output}")
    assertFalse(check.output.contains("WARNING"), "qpdf --check warned about the file:\n${check.output}")
}

/** How many pages the file has, according to qpdf. */
internal fun pageCount(path: Path): Int {
    val run = tool("qpdf", "--show-npages", path.toString())
    assertEquals(0, run.status, "qpdf could not count the pages:\n${run.output}")
    return run.output.trim().toInt()
}

/**
 * The whole file as an object graph, the way qpdf describes it — every object,
 * every entry of every dictionary and every reference between them at once.
 *
 * `--json-stream-data=none` leaves the payloads out: a structural question is
 * about the dictionaries and the edges between them, and an embedded font
 * program rendered as base64 would be megabytes of noise around the answer.
 */
internal fun objectGraph(path: Path): String {
    val run = tool("qpdf", "--json", "--json-stream-data=none", path.toString())
    assertEquals(0, run.status, "qpdf could not describe the file:\n${run.output}")
    return run.output
}

/**
 * One image XObject of a file: the dictionary as qpdf prints it, and the stream
 * as it stands in the file.
 *
 * `--raw-stream-data` is the encoded bytes, so for a `/DCTDecode` stream it is
 * the JPEG that went in — which is the assertion that an embedded photograph
 * was embedded and not re-encoded (ISO 32000-2:2020 §7.4.8).
 */
internal class EmbeddedImage(val dictionary: String, val streamData: ByteArray)

/**
 * Every image XObject of [path], in the order qpdf reports the objects.
 *
 * The images are found by their `/Subtype`, which Table 87 makes required for
 * an image ("Shall be Image for an image XObject"), and the object each
 * dictionary belongs to is the last object key printed before it — qpdf's JSON
 * prints one object per key, so the key above a dictionary entry owns it.
 */
internal fun imageXObjects(path: Path): List<EmbeddedImage> {
    val dump = objectGraph(path)
    return IMAGE_SUBTYPE.findAll(dump).map { subtype ->
        val key = OBJECT_KEY.findAll(dump.substring(0, subtype.range.first)).lastOrNull()
        assertTrue(key != null, "no object holds an image dictionary:\n$dump")
        val id = "${key.groupValues[1]},${key.groupValues[2]}"

        val shown = tool("qpdf", "--show-object=$id", path.toString())
        assertEquals(0, shown.status, "qpdf could not show object $id:\n${shown.output}")
        val stream = tool("qpdf", "--show-object=$id", "--raw-stream-data", path.toString())
        assertEquals(0, stream.status, "qpdf could not read the stream of object $id:\n${stream.output}")
        EmbeddedImage(shown.output, stream.bytes)
    }.toList()
}

/** How an image XObject appears in qpdf's JSON dump of the objects (Table 87). */
private val IMAGE_SUBTYPE: Regex = Regex(""""/Subtype":\s*"/Image"""")

/** `"obj:12 0 R"`, the key qpdf's JSON gives an indirect object. */
private val OBJECT_KEY: Regex = Regex(""""obj:(\d+) (\d+) R"""")

/**
 * The text of [path] as pdftotext reads it, with the outer white space cut.
 *
 * @param layout whether to ask the tool to keep the arrangement of the page.
 *   Off, it reports the text in the reading order it works out, which puts a
 *   column of list markers in one place and the lines they mark in another. On,
 *   it reconstructs the lines from where the marks actually are — which is what
 *   a test asking whether a *layout* came out as it was written wants, since the
 *   tool is then answering from the geometry rather than from a guess about it.
 */
internal fun extractedText(path: Path, layout: Boolean = false): String {
    val command = listOfNotNull("pdftotext", "-q", if (layout) "-layout" else null, path.toString(), "-")
    val run = tool(*command.toTypedArray())
    assertEquals(0, run.status, "pdftotext could not read the file:\n${run.output}")
    return run.output.trim()
}

/** The pages of [path] — pdftotext separates them with a form feed. */
internal fun extractedPages(path: Path, layout: Boolean = true): List<String> =
    extractedText(path, layout).split(FORM_FEED).filter { page -> page.isNotBlank() }

/** The lines of one extracted page, the empty ones dropped. */
internal fun linesOf(page: String): List<String> =
    page.lines().map { line -> line.trim() }.filter { line -> line.isNotEmpty() }

/** The words of one extracted page, in the order the tool reports them. */
internal fun wordsOf(page: String): List<String> = page.split(WHITE_SPACE).filter { word -> word.isNotEmpty() }

/**
 * A page of a file, drawn by a renderer that never saw this library, as the grey
 * value of every pixel.
 *
 * There are questions no structural tool answers. `qpdf --check` says the file
 * holds together and `pdftotext` says which characters are in it and in what
 * order — neither says the second line was drawn *below* the first rather than
 * over it, because a document whose every block sits at one baseline extracts
 * exactly like one that is laid out. A raster answers it: text on separate lines
 * leaves separate horizontal bands of ink, and text drawn on top of itself
 * leaves one.
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
     * How far the ink on rows [rows] reaches across the page: the leftmost and
     * the rightmost inked column, or null when those rows carry no mark at all.
     *
     * What alignment is, measured. `pdftotext -layout` reconstructs columns by
     * guessing at character widths; a rendered page shows where the marks
     * actually are, so a line set flush right is a line whose last mark is at the
     * right margin — which is the claim, and the only way to tell it from a line
     * that is merely long.
     */
    fun inkColumns(rows: IntRange, threshold: Int = INK_THRESHOLD): IntRange? {
        var left = width
        var right = -1
        for (y in rows) {
            val start = y * width
            for (x in 0 until width) {
                if ((pixels[start + x].toInt() and BYTE_MASK) <= threshold) {
                    if (x < left) left = x
                    if (x > right) right = x
                }
            }
        }
        return if (right < 0) null else left..right
    }

    /**
     * The horizontal bands of ink on the page, top to bottom: every maximal run
     * of rows carrying a mark, separated by rows carrying none.
     *
     * One line of text is one band. Two lines are two, so long as the leading
     * leaves a row of white between the descenders of the upper and the
     * ascenders of the lower — which is what a line height above 1.0 buys and
     * what this counts.
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
         * Well below the grey a renderer's antialiasing leaves beside a glyph, so
         * that the haze around a descender does not join two lines into one band,
         * and well above the lightest thing this library draws — a horizontal
         * rule at 50% grey (`Theme.ruleColor`) lands at 128.
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
internal fun rasterisePage(path: Path, page: Int, dpi: Int = RASTER_DPI): PageRaster =
    parseGreyMap(renderPage(path, page, dpi, grey = true))

/** Page [page] of [path] as the renderer wrote it to its standard output. */
private fun renderPage(path: Path, page: Int, dpi: Int, grey: Boolean): ByteArray {
    val process =
        ProcessBuilder(
            listOfNotNull(
                "pdftoppm",
                if (grey) "-gray" else null,
                "-r",
                dpi.toString(),
                "-f",
                page.toString(),
                "-l",
                page.toString(),
                "-singlefile",
                path.toString(),
            ),
        ).start()
    val bytes = process.inputStream.readBytes()
    val errors = process.errorStream.readBytes().decodeToString()
    assertTrue(process.waitFor(TOOL_TIMEOUT_SECONDS, TimeUnit.SECONDS), "pdftoppm did not finish")
    assertEquals(0, process.exitValue(), "pdftoppm could not render page $page:\n$errors")
    return bytes
}

/**
 * A page as red, green and blue per pixel, for the one question a grey map
 * cannot answer: whether what is on the page is *coloured*.
 *
 * Everything this library draws is black text on white paper, with one
 * exception — a colour emoji, which is a picture the font carries.
 * So a pixel whose three components are far apart
 * did not come from a letter, a rule or the paper: it came out of the bitmap of
 * an emoji.
 */
internal class ColourPageRaster(val width: Int, val height: Int, private val pixels: ByteArray) {
    init {
        assertEquals(width * height * RGB, pixels.size, "the raster is not $width by $height RGB pixels")
    }

    /** How many pixels of the page are a colour rather than a shade of grey. */
    fun colouredPixels(threshold: Int = COLOURFUL): Int {
        var found = 0
        for (at in 0 until width * height) {
            val red = pixels[at * RGB].toInt() and PageRaster.BYTE_MASK
            val green = pixels[at * RGB + 1].toInt() and PageRaster.BYTE_MASK
            val blue = pixels[at * RGB + 2].toInt() and PageRaster.BYTE_MASK
            if (maxOf(red, green, blue) - minOf(red, green, blue) > threshold) found++
        }
        return found
    }

    companion object {
        /**
         * How far apart the components of one pixel have to be for it to be a
         * colour. Well above what a renderer's antialiasing leaves around black
         * text on white paper, and well below the distance in any pixel of a
         * yellow face or a red party popper.
         */
        const val COLOURFUL: Int = 30

        const val RGB: Int = 3
    }
}

/** Page [page] of [path], one-based, rendered in colour at [dpi] dots per inch. */
internal fun rasteriseColourPage(path: Path, page: Int, dpi: Int = RASTER_DPI): ColourPageRaster {
    val bytes = renderPage(path, page, dpi, grey = false)
    val map = parseNetpbm(bytes, "P6", componentsPerPixel = ColourPageRaster.RGB)
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
 * A binary netpbm map of [magic], with [componentsPerPixel] bytes per pixel: the
 * magic number, three numbers, one whitespace character, and then the samples.
 *
 * Parsed here rather than through an image library: the format is four tokens
 * and a block of bytes, and a test harness that reads it in twenty lines does not
 * need a decoder in the way.
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

    assertEquals(magic, token(), "pdftoppm did not write a binary $magic map")
    val width = token().toInt()
    val height = token().toInt()
    val maximum = token().toInt()
    assertEquals(PageRaster.BYTE_MASK, maximum, "a map of more than one byte per component")
    // Exactly one whitespace character separates the header from the samples.
    at++
    val samples = width * height * componentsPerPixel
    return NetpbmMap(width, height, bytes.copyOfRange(at, at + samples))
}

/**
 * The system's font programs (Debian's `fonts-dejavu-core` and
 * `fonts-noto-color-emoji`), for the tests that draw
 * something the fourteen standard fonts cannot draw — an alphabet, or a picture.
 *
 * No font binary is committed: the fonts are read from the system, and a missing
 * one fails the test rather than skipping it. A check that quietly runs over
 * nothing is the failure mode this rule exists to prevent.
 *
 * @param fileName the file to read, e.g. `DejaVuSans.ttf`.
 * @param why what is left unchecked if the font is missing, for the message a
 *   developer without the package installed will read.
 * @param debianPackage which package puts [fileName] where this looks for it.
 */
internal fun systemFontBytes(fileName: String, why: String, debianPackage: String = "fonts-dejavu-core"): ByteArray {
    val path = FONT_DIRECTORIES.map { directory -> Path.of(directory, fileName) }
        .firstOrNull { candidate -> Files.isReadable(candidate) }
    requireNotNull(path) {
        "$fileName is not installed. $why against a font program from the system rather than a committed " +
            "binary, and skipping would report it as checked against a real font when it was checked " +
            "against nothing. Install the $debianPackage package; the paths tried were " +
            FONT_DIRECTORIES.joinToString(", ")
    }
    return Files.readAllBytes(path)
}

/**
 * Whether a tool is missing, in which case the test that asked says so and
 * stops: the tool is a dependency of the check and not of the library, and a
 * machine without it should still be able to run the suite. Where all three
 * are installed the assertions do run.
 */
private fun skipWithout(name: String, versionFlag: String, what: String): Boolean {
    val installed = try {
        ProcessBuilder(name, versionFlag).redirectErrorStream(true).start().waitFor() == 0
    } catch (absent: IOException) {
        println("$name could not be started: ${absent.message}")
        false
    }
    if (!installed) println("$name is not installed — skipping the check of $what")
    return !installed
}

internal fun skipWithoutQpdf(what: String = "the file's structure"): Boolean = skipWithout("qpdf", "--version", what)

internal fun skipWithoutPdftotext(what: String = "the text of the file"): Boolean = skipWithout("pdftotext", "-v", what)

internal fun skipWithoutPdftoppm(what: String = "the marks on the page"): Boolean = skipWithout("pdftoppm", "-v", what)

/**
 * Where `fonts-dejavu-core` and `fonts-noto-color-emoji` put their files. More
 * than one path is tried because a Debian layout and a plain one differ; none of
 * them is a fallback to a *different* font.
 */
private val FONT_DIRECTORIES: List<String> = listOfNotNull(
    System.getenv("PDFKT_TEST_FONT_DIR"),
    "/usr/share/fonts/truetype/dejavu",
    "/usr/share/fonts/dejavu",
    "/usr/share/fonts/TTF",
    "/usr/share/fonts/truetype/noto",
    "/usr/share/fonts/noto",
)

/**
 * The resolution the pages are rendered at.
 *
 * Two lines of eleven-point text set 1.2 line heights apart leave about two
 * points of white between them; at this resolution that is four rows of pixels,
 * which is enough for the gap to survive antialiasing and few enough that a page
 * costs milliseconds.
 */
internal const val RASTER_DPI: Int = 150

/** FORM FEED (0Ch) — what pdftotext puts between the pages of a file. */
internal const val FORM_FEED: String = "\u000C"

internal val WHITE_SPACE: Regex = Regex("\\s+")

private const val TOOL_TIMEOUT_SECONDS: Long = 60
