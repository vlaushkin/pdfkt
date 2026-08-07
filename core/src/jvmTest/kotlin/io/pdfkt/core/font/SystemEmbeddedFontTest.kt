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
import io.pdfkt.core.obj.assertQpdfCheckIsClean
import io.pdfkt.core.obj.filteredStream
import io.pdfkt.core.obj.filteredStreamBytes
import io.pdfkt.core.obj.qpdf
import io.pdfkt.core.obj.referenceIn
import io.pdfkt.core.obj.showObject
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

// ISO 32000-2:2020 §9.7 "Composite fonts" (Tables 114, 115, 119),
// §9.8 "Font descriptors" (Table 120), §9.9 "Embedded font programs"
// (Tables 124, 125), §9.9.2 "Font subsets", §9.10.3 "ToUnicode CMaps"

/**
 * A real font, a real file, and a reader that never saw this library walking the
 * chain §9.7 defines.
 *
 * `EmbeddedFontTest` asserts the objects; nothing about that says the objects
 * survive being written into a file and read back out of it, or that a processor
 * can follow the references from a page's `/Resources` down to the font program.
 * That is what qpdf does here — as a tool, on the bytes, never as a source
 *
 * The text is Cyrillic, which is the point of the whole path: none of these
 * characters exists in the encodings of the fourteen fonts (Annex D), so a
 * document containing them either embeds a font program or is blank.
 */
class SystemEmbeddedFontTest {
    private val sans: ByteArray =
        systemFontBytes("DejaVuSans.ttf", "This test embeds a font program")

    /**
     * The chain of §9.7 followed from the page down, object by object, by a
     * reader that has only the file.
     *
     * Each step names the entry it follows and what the clause requires of it, so
     * a break anywhere along it fails with the entry that was missing rather than
     * with "the document did not open".
     */
    @Test
    fun `qpdf follows the object chain from the page down to the font program`() {
        if (skipWithoutQpdf("§9.7")) return

        withDocument(HELLO) { path ->
            assertQpdfCheckIsClean(path)

            // Table 119: a Type 0 font, reached through the resource dictionary
            // the content stream's Tf named it in (§7.8.3), over a one-element
            // DescendantFonts array, with the Identity-H CMap and a ToUnicode CMap.
            val type0 = type0FontIn(path)
            assertTrue("/Subtype /Type0" in type0, type0)
            assertTrue("/Encoding /Identity-H" in type0, type0)
            assertTrue(Regex("/BaseFont\\s+/[A-Z]{6}\\+DejaVuSans").containsMatchIn(type0), type0)
            val descendant = descendantFontIn(type0)

            // Table 115: the CIDFont, its character collection, its widths and the
            // mapping from the CIDs the pages wrote to the subset's glyph indices.
            val cidFont = showObject(path, descendant)
            assertTrue("/Subtype /CIDFontType2" in cidFont, cidFont)
            assertTrue("/Ordering (Identity)" in cidFont, cidFont)
            assertTrue("/W [" in cidFont, cidFont)
            assertNotNull(referenceIn(cidFont, "CIDToGIDMap"), cidFont)

            // Table 120: the descriptor, which "shall be an indirect reference".
            val descriptor = showObject(path, assertNotNull(referenceIn(cidFont, "FontDescriptor"), cidFont))
            assertTrue("/Type /FontDescriptor" in descriptor, descriptor)
            assertTrue("/Flags" in descriptor && "/StemV" in descriptor, descriptor)

            // Table 124: the font program itself, as FontFile2.
            val fontFile = assertNotNull(referenceIn(descriptor, "FontFile2"), descriptor)
            assertTrue("/Length1" in showObject(path, fontFile), showObject(path, fontFile))
        }
    }

    /**
     * The same chain as qpdf's own JSON, which is the acceptance these checks
     * are written against: a form of the file produced by a reader, in which the
     * objects and the entries that link them are all present at once.
     */
    @Test
    fun `qpdf json shows the whole chain in one object graph`() {
        if (skipWithoutQpdf("§9.7")) return

        withDocument(HELLO) { path ->
            val json = qpdf("--json", "--json-stream-data=none", path.toString())
            assertEquals(0, json.status, "qpdf could not describe the file:\n${json.output}")

            for (entry in CHAIN) {
                assertTrue(entry in json.output, "the object graph has no $entry:\n${json.output.take(4000)}")
            }
        }
    }

    /**
     * The program in the file is a subset: it parses as a font, it holds far
     * fewer glyphs than DejaVu Sans has, and it grows when the document draws
     * more distinct letters.
     *
     * qpdf decodes the stream, so this is the filter and the bytes under it read
     * back by something that is not this library.
     */
    @Test
    fun `the embedded program is a subset of the font and grows with the text`() {
        if (skipWithoutQpdf("§9.9.2")) return

        val short = embeddedProgram("Привет")
        val long = embeddedProgram("Привет, мир! Съешь ещё этих мягких французских булок")

        assertTrue(short.size < sans.size / 10, "${short.size} bytes of subset against ${sans.size} of font")
        assertTrue(short.size < long.size, "a subset of more letters is no larger: ${short.size}, ${long.size}")

        val whole = TrueTypeFont.parse(sans)
        val embedded = TrueTypeFont.parse(short)
        assertEquals(whole.unitsPerEm, embedded.unitsPerEm)
        assertEquals(whole.hhea.ascender, embedded.hhea.ascender)
        // The glyphs kept are the closure of the letters drawn: DejaVu builds
        // some of its Cyrillic out of Latin components, and a subset that
        // dropped those would draw the letters out of whatever the renumbered
        // indices landed on.
        val closure = assertNotNull(whole.glyf).componentClosureOf(HELLO.map { whole.glyphIdOf(it.code) } + 0)
        assertEquals(closure.size, embedded.numGlyphs, "the subset is not the closure of the letters drawn")
        assertTrue(closure.size > HELLO.toSet().size + 1, "no letter of $HELLO is drawn out of components")
        assertTrue(embedded.numGlyphs < whole.numGlyphs / 100, "a subset of ${embedded.numGlyphs} glyphs")
    }

    /**
     * "In the absence of a ToUnicode entry, no information would be available
     * about what the glyphs mean" (§9.10.3). The codes on the page are glyph
     * indices of DejaVu Sans; this asserts the CMap in the file maps them back to
     * the characters that were drawn, decoded by qpdf rather than by this library.
     */
    @Test
    fun `the ToUnicode CMap in the file maps the codes back to the text that was drawn`() {
        if (skipWithoutQpdf("§9.10.3")) return

        withDocument(HELLO) { path ->
            val type0 = type0FontIn(path)
            val cmap = filteredStream(path, assertNotNull(referenceIn(type0, "ToUnicode"), type0))

            assertTrue("begincodespacerange\n<0000> <FFFF>" in cmap, cmap)
            val font = TrueTypeFont.parse(sans)
            for (character in HELLO.toSet()) {
                val code = font.glyphIdOf(character.code)
                val mapping = "<%04X> <%04X>".format(code, character.code)
                assertTrue(mapping in cmap, "the CMap does not map '$character' as $mapping:\n$cmap")
            }
        }
    }

    /**
     * The `CIDToGIDMap` of Table 115, read back out of the file: every code the
     * page wrote is two bytes at twice its own value, and it names the glyph the
     * subset holds that letter at.
     *
     * This is the entry the whole numbering rests on. A file whose map is wrong
     * renders — with the wrong letters.
     */
    @Test
    fun `the CID to GID map in the file sends every code drawn to its glyph in the subset`() {
        if (skipWithoutQpdf("§9.7.4.1")) return

        withDocument(HELLO) { path ->
            val cidFont = showObject(path, descendantFontIn(type0FontIn(path)))
            val map = filteredStreamBytes(path, assertNotNull(referenceIn(cidFont, "CIDToGIDMap"), cidFont))

            val font = TrueTypeFont.parse(sans)
            val embedded = TrueTypeFont.parse(embeddedProgram(HELLO))
            for (character in HELLO.toSet()) {
                val cid = font.glyphIdOf(character.code)
                assertTrue(cid * 2 + 1 < map.size, "the map ends before the code of '$character'")
                val glyphId = ((map[cid * 2].toInt() and 0xFF) shl 8) or (map[cid * 2 + 1].toInt() and 0xFF)
                assertTrue(glyphId in 1 until embedded.numGlyphs, "'$character' maps to glyph $glyphId")
                // The advance the subset holds for that glyph is the advance the
                // whole font holds for the letter: the two numberings line up.
                assertEquals(font.advanceWidthOf(cid), embedded.advanceWidthOf(glyphId), "'$character'")
            }
        }
    }

    // ---- Writing and reading the file --------------------------------------

    /** Writes a one-page document showing [text] in DejaVu Sans, and runs [block] on it. */
    private fun withDocument(text: String, block: (Path) -> Unit) {
        val path = Files.createTempFile("pdfkt-embedded-font", ".pdf")
        try {
            val fonts = FontCatalog()
            val font = fonts.resolve(FontRef.Supplied(sans, "DejaVuSans"))

            val content = ContentStreamBuilder()
            content.beginText()
            content.textFont(font.resourceName, 24.0)
            content.nextLine(72.0, 720.0)
            content.showText(font.encode(text))
            content.endText()

            Files.newOutputStream(path).asSink().buffered().use { sink ->
                val document = PdfDocumentWriter(sink)
                document.addPage(
                    mediaBox = pdfRectangle(0.0, 0.0, 612.0, 792.0),
                    resources = PdfDict(mapOf(PdfName("Font") to fonts.fontResources(document::define))),
                    content = content.build(),
                )
                document.finish(fileIdentifier = FILE_IDENTIFIER)
            }
            block(path)
        } finally {
            path.deleteIfExists()
        }
    }

    /** The bytes of the `FontFile2` of a document showing [text], as qpdf decodes them. */
    private fun embeddedProgram(text: String): ByteArray {
        var program = ByteArray(0)
        withDocument(text) { path -> program = embeddedProgramIn(path) }
        return program
    }

    private companion object {
        /**
         * Six Cyrillic letters over five distinct glyphs — `и` occurs twice, so a
         * subset that counted characters rather than glyphs would come out one
         * glyph too large.
         */
        const val HELLO: String = "Привет"

        /** The entries the object graph has to contain for the chain to exist. */
        val CHAIN: List<String> = listOf(
            "\"/Subtype\": \"/Type0\"",
            "\"/Encoding\": \"/Identity-H\"",
            "\"/Subtype\": \"/CIDFontType2\"",
            "\"/Type\": \"/FontDescriptor\"",
            "\"/CIDToGIDMap\"",
            "\"/FontFile2\"",
            "\"/ToUnicode\"",
            "\"/Length1\"",
        )

        /** Pinned, so the file this asserts about is the same file every run (§14.4). */
        val FILE_IDENTIFIER: ByteArray = ByteArray(16) { (0x20 + it).toByte() }
    }
}
