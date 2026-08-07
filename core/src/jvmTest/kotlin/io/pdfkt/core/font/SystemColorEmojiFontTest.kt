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
import io.pdfkt.core.font.truetype.TrueTypeFont
import io.pdfkt.core.font.truetype.systemFontBytes
import io.pdfkt.core.obj.PdfDict
import io.pdfkt.core.obj.PdfName
import io.pdfkt.core.obj.RASTER_DPI
import io.pdfkt.core.obj.assertQpdfCheckIsClean
import io.pdfkt.core.obj.extractedText
import io.pdfkt.core.obj.filteredStream
import io.pdfkt.core.obj.firstPageOf
import io.pdfkt.core.obj.qpdf
import io.pdfkt.core.obj.rasteriseColourPage
import io.pdfkt.core.obj.referenceIn
import io.pdfkt.core.obj.showObject
import io.pdfkt.core.obj.skipWithoutPdftoppm
import io.pdfkt.core.obj.skipWithoutPdftotext
import io.pdfkt.core.obj.skipWithoutQpdf
import kotlinx.io.asSink
import kotlinx.io.buffered
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// ISO 32000-2:2020 §9.6.4 "Type 3 fonts" (Tables 110, 111),
// §9.6.5.3 "Encodings for Type 3 fonts", §8.8.1 "External objects — General",
// §8.9.5.1 "Image dictionaries — General" (Table 87),
// §9.10.3 "ToUnicode CMaps", §7.8.3 "Resource dictionaries"

/**
 * A real colour font, a real file, and a reader that never saw this library
 * walking the chain §9.6.4 defines: the system's `NotoColorEmoji.ttf`
 * (Debian's `fonts-noto-color-emoji`).
 *
 * `ColorEmojiFontTest` asserts the objects, over a font this project wrote by
 * hand. Nothing about that says the objects survive being written into a file,
 * that a processor can follow the references from a page's `/Resources` down to
 * the PNG of an emoji, or that the parser and the emitter agree about a font
 * somebody else built. That is what qpdf does here — as a tool, on the bytes,
 * never as a source.
 *
 * No font binary is committed, and a missing one fails rather than skips.
 */
class SystemColorEmojiFontTest {
    private val emojiBytes: ByteArray =
        systemFontBytes("NotoColorEmoji.ttf", WHY, debianPackage = "fonts-noto-color-emoji")

    private val program: TrueTypeFont = TrueTypeFont.parse(emojiBytes)

    private val strike = assertNotNull(program.cblc?.largestStrike)

    /**
     * The chain of §9.6.4 followed from the page down, object by object, by a
     * reader that has only the file.
     *
     * Each step names the entry it follows and what the clause requires of it, so
     * a break anywhere along it fails with the entry that was missing rather than
     * with "the document did not open".
     */
    @Test
    fun `qpdf follows the object chain from the page down to the emoji bitmap`() {
        if (skipWithoutQpdf("§9.6.4")) return

        withDocument(EMOJI) { path ->
            assertQpdfCheckIsClean(path)

            // Table 110: a Type 3 font, reached through the resource dictionary
            // the content stream's Tf named it in (§7.8.3).
            val font = colorFontIn(path)
            assertTrue("/Subtype /Type3" in font, font)
            assertTrue("/FontMatrix [" in font, font)
            assertTrue("/FirstChar 1" in font, font)
            assertTrue("/LastChar ${EMOJI_COUNT}" in font, font)

            // "A Type 3 font's mapping from character codes to glyph names shall
            // be entirely defined by its Encoding entry" (§9.6.5.3).
            val differences = assertNotNull(
                Regex("/Differences\\s*\\[\\s*1\\s+((?:/g\\d+\\s*)+)]").find(font)?.groupValues?.get(1),
                "the font's encoding names no glyphs:\n$font",
            ).trim().split(Regex("\\s+"))
            assertEquals(EMOJI_COUNT, differences.size, differences.toString())

            // Table 110: the glyph descriptions, one per name of the encoding.
            val charProcs = dictionaryIn(font, "CharProcs")
            val firstGlyph = assertNotNull(
                referenceIn(charProcs, differences.first().removePrefix("/")),
                "CharProcs has no entry for ${differences.first()}:\n$charProcs",
            )

            // Table 111: "The stream shall include as its first operator either
            // d0 or d1", and this one paints an image, so d0.
            val description = filteredStream(path, firstGlyph)
            assertTrue(Regex("^[0-9.]+ 0 d0\\n").containsMatchIn(description), description)
            val painted = assertNotNull(
                Regex("/(Im\\d+) Do").find(description)?.groupValues?.get(1),
                "the glyph description paints no XObject:\n$description",
            )

            // §9.6.4: "If any glyph descriptions refer to named resources they
            // shall be looked up in the Resources entry of the Type 3 font
            // dictionary" — where the emoji's own PNG is, as Table 87's image.
            val resources = dictionaryIn(font, "Resources")
            val xObjects = dictionaryIn(resources, "XObject")
            val image = showObject(path, assertNotNull(referenceIn(xObjects, painted), xObjects))
            assertTrue("/Subtype /Image" in image, image)
            assertTrue("/Filter /FlateDecode" in image, image)

            // The picture in the file is the size the strike says that emoji is.
            val bitmap = assertNotNull(program.colorBitmapOf(program.glyphIdOf(EMOJI.codePointAt(0)), strike))
            assertTrue("/Width ${bitmap.metrics.width}" in image, image)
            assertTrue("/Height ${bitmap.metrics.height}" in image, image)
        }
    }

    /**
     * The same chain as qpdf's own JSON, which is the acceptance these checks
     * are written against: a form of the file produced by a reader, in which the
     * objects and the entries that link them are all present at once.
     */
    @Test
    fun `qpdf json shows the whole chain in one object graph`() {
        if (skipWithoutQpdf("§9.6.4")) return

        withDocument(EMOJI) { path ->
            val json = qpdf("--json", "--json-stream-data=none", path.toString())
            assertEquals(0, json.status, "qpdf could not describe the file:\n${json.output}")

            for (entry in CHAIN) {
                assertTrue(entry in json.output, "the object graph has no $entry:\n${json.output.take(4000)}")
            }
        }
    }

    /**
     * "Widths … each element being the glyph width for the character code that
     * equals FirstChar plus the array index … interpreted in glyph space as
     * specified by FontMatrix", and the operand of `d0` "shall be consistent with
     * the corresponding width in the font's Widths array" (Table 111).
     *
     * Both numbers are the advance the font's `CBLC` states for that emoji, in
     * pixels of the strike, converted at the em — read back here out of the file
     * and computed again from the font program.
     */
    @Test
    fun `the widths are the CBLC advances of the emoji drawn`() {
        if (skipWithoutQpdf("§9.6.4")) return

        withDocument(EMOJI) { path ->
            val font = colorFontIn(path)
            val widths = showObject(path, assertNotNull(referenceIn(font, "Widths"), font))
                .substringAfter('[').substringBefore(']')
                .trim().split(Regex("\\s+")).map { it.toDouble() }
            assertEquals(EMOJI_COUNT, widths.size, "the Widths array does not span FirstChar..LastChar")

            EMOJI.codePoints().toArray().forEachIndexed { index, codePoint ->
                val bitmap = assertNotNull(program.colorBitmapOf(program.glyphIdOf(codePoint), strike))
                val expected = bitmap.metrics.advance.toDouble() * program.unitsPerEm / strike.ppemX
                // To the fifteen significant digits a real object is written to
                // (§7.3.3, and `writePdfReal` on why that many).
                assertEquals(
                    expected,
                    widths[index],
                    absoluteTolerance = expected * 1e-14,
                    message = "the width of U+${codePoint.toString(16).uppercase()}",
                )
            }

            // The same number the glyph description declares to the font machinery.
            val charProcs = dictionaryIn(font, "CharProcs")
            val glyphId = program.glyphIdOf(EMOJI.codePointAt(0))
            val description = filteredStream(path, assertNotNull(referenceIn(charProcs, "g$glyphId"), charProcs))
            assertEquals(widths[0], description.substringBefore(' ').toDouble())
        }
    }

    /**
     * The `FontMatrix` "mapping glyph space to text space", read back out of the
     * file: the em of the font program, so an emoji drawn at 24 points is 24
     * points of advance and not some multiple of it.
     */
    @Test
    fun `the font matrix scales the glyph space to the em of the program`() {
        if (skipWithoutQpdf("§9.6.4")) return

        withDocument(EMOJI) { path ->
            val matrix = assertNotNull(
                Regex("/FontMatrix\\s*\\[([^]]*)]").find(colorFontIn(path))?.groupValues?.get(1),
            ).trim().split(Regex("\\s+")).map { it.toDouble() }

            assertEquals(listOf(0.0, 0.0, 0.0, 0.0), listOf(matrix[1], matrix[2], matrix[4], matrix[5]))
            assertEquals(1.0 / program.unitsPerEm, matrix[0])
            assertEquals(1.0 / program.unitsPerEm, matrix[3])
            // What the matrix has to get right is the order of magnitude: a
            // width in glyph space times the matrix is the text space
            // displacement of §9.2.4, and an emoji is about as wide as it is
            // tall — a matrix off by the em or by the ppem would put the number
            // out by a factor of tens.
            val bitmap = assertNotNull(program.colorBitmapOf(program.glyphIdOf(EMOJI.codePointAt(0)), strike))
            val inTextSpace = bitmap.metrics.advance.toDouble() * program.unitsPerEm / strike.ppemX * matrix[0]
            assertTrue(inTextSpace > 0.5 && inTextSpace < 2.0, "an emoji advances by $inTextSpace of an em")
            assertEquals(bitmap.metrics.advance.toDouble() / strike.ppemX, inTextSpace, absoluteTolerance = 1e-9)
        }
    }

    /**
     * "In the absence of a ToUnicode entry, no information would be available
     * about what the glyphs mean" (§9.10.3) — and the codes here are numbers this
     * library handed out, which stand for nothing anywhere else. The CMap is read
     * back out of the file, decoded by qpdf rather than by this library.
     */
    @Test
    fun `the ToUnicode CMap in the file maps the codes back to the emoji that were drawn`() {
        if (skipWithoutQpdf("§9.10.3")) return

        withDocument(EMOJI) { path ->
            val font = colorFontIn(path)
            val cmap = filteredStream(path, assertNotNull(referenceIn(font, "ToUnicode"), font))

            // "consistent with the encoding that the font uses" — a simple font's
            // codes are single bytes (§9.6.1).
            assertTrue("begincodespacerange\n<00> <FF>" in cmap, cmap)
            EMOJI.codePoints().toArray().forEachIndexed { index, codePoint ->
                val utf16 = String(Character.toChars(codePoint))
                    .map { "%04X".format(it.code) }
                    .joinToString("")
                val mapping = "<%02X> <%s>".format(index + 1, utf16)
                assertTrue(mapping in cmap, "the CMap does not map code ${index + 1} as $mapping:\n$cmap")
            }
        }
    }

    /**
     * The emoji the pages drew, and no more: a font of two emoji carries two
     * glyph descriptions and two pictures, however many times they were shown.
     */
    @Test
    fun `the file carries one glyph description and one picture per distinct emoji`() {
        if (skipWithoutQpdf("§9.6.4")) return

        withDocument(EMOJI + EMOJI) { path ->
            val font = colorFontIn(path)
            val charProcs = dictionaryIn(font, "CharProcs")
            val resources = dictionaryIn(font, "Resources")
            val xObjects = dictionaryIn(resources, "XObject")

            assertEquals(EMOJI_COUNT, Regex("/g\\d+ ").findAll(charProcs).count(), charProcs)
            assertEquals(EMOJI_COUNT, Regex("/Im\\d+ ").findAll(xObjects).count(), xObjects)
            assertTrue("/LastChar $EMOJI_COUNT" in font, font)
        }
    }

    /**
     * What the `ToUnicode` CMap is for, seen from outside: a tool that knows
     * nothing of this library reads the page and gets the emoji back as
     * characters. The codes on the page are numbers this library invented, so
     * without the CMap there would be nothing to read.
     */
    @Test
    fun `pdftotext extracts the emoji as the characters that were drawn`() {
        if (skipWithoutPdftotext("§9.10.3")) return

        withDocument(EMOJI) { path ->
            assertEquals(EMOJI, extractedText(path))
        }
    }

    /**
     * The page as a renderer draws it: the emoji is *in colour*, and it is where
     * the strike's metrics put it.
     *
     * Everything above this asserts the objects and the entries between them,
     * and a font whose `cm` were transposed, scaled by the em twice or written
     * with the bitmap's top where its bottom belongs would satisfy all of it and
     * paint a page nobody wants. This renders the file and looks: the rectangle
     * the metrics predict carries ink of more than one colour, and the page
     * outside it is white.
     */
    @Test
    fun `the emoji renders in colour where the strike metrics put it`() {
        if (skipWithoutPdftoppm("§9.6.4")) return

        withDocument(EMOJI) { path ->
            val raster = rasteriseColourPage(path, page = 1)
            val bitmap = assertNotNull(program.colorBitmapOf(program.glyphIdOf(EMOJI.codePointAt(0)), strike))
            val scale = SIZE / strike.ppemY.toDouble()

            // The rectangle the glyph description maps its image onto, in points
            // of the page and then in pixels of the raster.
            val left = TEXT_X + bitmap.metrics.bearingX * SIZE / strike.ppemX
            val top = TEXT_Y + bitmap.metrics.bearingY * scale
            val x = ((left + bitmap.metrics.width * scale / 2) * RASTER_DPI / POINTS_PER_INCH).toInt()
            val y = (
                (PAGE_HEIGHT - (top - bitmap.metrics.height * scale / 2)) * RASTER_DPI / POINTS_PER_INCH
                ).toInt()

            val (red, green, blue) = raster.pixelAt(x, y)
            assertTrue(
                maxOf(red, green, blue) - minOf(red, green, blue) > COLOURFUL,
                "the middle of the first emoji is ($red, $green, $blue), which is a shade of grey",
            )
            // And the page below the line of text is untouched.
            val (r, g, b) = raster.pixelAt(x, raster.height - 1 - y)
            assertEquals(Triple(WHITE, WHITE, WHITE), Triple(r, g, b), "the page below the text is not white")
        }
    }

    // ---- Writing and reading the file --------------------------------------

    /** The Type 3 font (Table 110) the first page names `/E1` (§7.8.3). */
    private fun colorFontIn(path: Path): String {
        val page = showObject(path, firstPageOf(path))
        val font = assertNotNull(referenceIn(page, RESOURCE_NAME), "the page names no font /$RESOURCE_NAME:\n$page")
        return showObject(path, font)
    }

    /**
     * The dictionary `/key` of [dictionary] carries, cut out of it: from the
     * `<<` after the key to the `>>` that balances it.
     *
     * `CharProcs` and `Resources` are typed "dictionary" by Table 110 and are
     * written in place, so this is where a reader finds them; the streams under
     * them are references, which is what `referenceIn` then reads.
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

    /** Writes a one-page document showing [text] in the colour font, and runs [block] on it. */
    private fun withDocument(text: String, block: (Path) -> Unit) {
        val path = Files.createTempFile("pdfkt-color-emoji", ".pdf")
        try {
            val font = ColorEmojiFont("NotoColorEmoji", emojiBytes)
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
        const val WHY: String = "This test writes a Type 3 colour font"

        /** What the page calls the colour font; the font catalog has a say in this. */
        const val RESOURCE_NAME: String = "E1"

        /**
         * A grinning face and a party popper: two emoji outside every encoding of
         * Annex D, each one code point, so no `GSUB` sequence is involved.
         */
        const val EMOJI: String = "😀🎉"

        /** How many distinct emoji [EMOJI] holds — its code points, not its chars. */
        const val EMOJI_COUNT: Int = 2

        /** The page the emoji is drawn on, and where on it: US Letter, §14.11.2's default. */
        const val PAGE_WIDTH: Double = 612.0
        const val PAGE_HEIGHT: Double = 792.0
        const val TEXT_X: Double = 72.0
        const val TEXT_Y: Double = 720.0

        /** The `Tf` size: "a number representing a scale factor" (Table 103). */
        const val SIZE: Double = 24.0

        /** "1 unit in user space, or 1 ⁄ 72 inch" (§9.2.2) — the raster's own scale. */
        const val POINTS_PER_INCH: Double = 72.0

        /**
         * How far apart the components of one pixel have to be for it to be a
         * colour rather than a shade of grey. A grinning face is yellow: its red
         * and green are near the maximum and its blue is not.
         */
        const val COLOURFUL: Int = 30

        /** The paper: a page with nothing drawn on it renders white. */
        const val WHITE: Int = 255

        /** The entries the object graph has to contain for the chain to exist. */
        val CHAIN: List<String> = listOf(
            "\"/Subtype\": \"/Type3\"",
            "\"/FontMatrix\"",
            "\"/CharProcs\"",
            "\"/Differences\"",
            "\"/FirstChar\"",
            "\"/LastChar\"",
            "\"/Widths\"",
            "\"/Resources\"",
            "\"/Subtype\": \"/Image\"",
            "\"/ToUnicode\"",
        )

        /** Pinned, so the file this asserts about is the same file every run (§14.4). */
        val FILE_IDENTIFIER: ByteArray = ByteArray(16) { (0x30 + it).toByte() }
    }
}
