/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.font

import io.pdfkt.core.obj.PdfDict
import io.pdfkt.core.obj.PdfName
import io.pdfkt.core.obj.PdfObject
import io.pdfkt.core.obj.PdfRef
import io.pdfkt.core.obj.serializeToText
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

// ISO 32000-2:2020 §7.8.3 "Resource dictionaries" (Table 34), §9.6.2.1 "Type 1
// fonts — General" (Table 109), §9.6.2.2 "Standard Type 1 fonts (standard 14
// fonts) (PDF 1.0-1.7)", §9.6.5 "Character encoding", §9.7.1 "Composite fonts —
// General"

/**
 * The catalog's scope rule, as L4 sees it:
 *
 * > Resolution (`FontRef` → parsed program or AFM table) happens once per
 * > document in a **document-scoped catalog**. No global caches.
 *
 * Two claims, and both are observable without reaching into the catalog: within
 * one catalog a reference resolves to one entry however often it is asked for,
 * and between two catalogs nothing at all is shared — not the entries, not the
 * numbering of the resource names, not the memory a parsed font program sits in.
 *
 * The second is what "no global state" means in a test rather than in prose. A
 * global cache is exactly what would make two catalogs agree on identity, so a
 * pair of catalogs that disagree is the evidence.
 */
class FontCatalogTest {
    private val helvetica = FontRef.Standard14(Standard14Name.HELVETICA)

    /**
     * Two bytes that are not a font program; nothing here parses them.
     *
     * Resolution is a name being looked up, not a font being read: a catalog
     * hands out an entry for a reference without touching the bytes behind it,
     * and a document that names a font it never draws with never pays for
     * parsing it. Every test below that only resolves uses these two bytes, and
     * the ones that draw use [suppliedFontProgram].
     */
    private fun suppliedProgram(id: String, marker: Byte): FontRef.Supplied =
        FontRef.Supplied(byteArrayOf(marker, 0), id)

    /** A font program of six glyphs, `A` to `E` — see `SuppliedFontBytes`. */
    private val suppliedFontProgram: ByteArray = suppliedFontBytes()

    @Test
    fun `a reference resolves once within one catalog`() {
        val catalog = FontCatalog()

        assertSame(catalog.resolve(helvetica), catalog.resolve(helvetica))
    }

    /**
     * The key is what the reference *names*, not the instance it arrives in: a
     * layout resolves a font per run and has no reason to keep one object.
     * `Standard14` is named by the enum member, `Supplied` by its id.
     */
    @Test
    fun `an equal reference in a fresh instance resolves to the same entry`() {
        val catalog = FontCatalog()

        assertSame(
            catalog.resolve(FontRef.Standard14(Standard14Name.HELVETICA)),
            catalog.resolve(FontRef.Standard14(Standard14Name.HELVETICA)),
        )
        assertSame(
            catalog.resolve(suppliedProgram("body", 1)),
            catalog.resolve(suppliedProgram("body", 1)),
        )
    }

    @Test
    fun `different references resolve to different entries`() {
        val catalog = FontCatalog()

        assertNotSame(
            catalog.resolve(helvetica),
            catalog.resolve(FontRef.Standard14(Standard14Name.COURIER)),
        )
        assertNotSame(
            catalog.resolve(suppliedProgram("body", 1)),
            catalog.resolve(suppliedProgram("heading", 1)),
        )
    }

    /**
     * The point of the whole design. Two catalogs are two documents; a reference
     * resolved in both yields two entries, because a catalog "lives and dies
     * with one render" and holds its own parse of the font.
     */
    @Test
    fun `two catalogs resolve the same reference independently`() {
        val first = FontCatalog()
        val second = FontCatalog()

        assertNotSame(first.resolve(helvetica), second.resolve(helvetica))
        assertNotSame(
            first.resolve(suppliedProgram("body", 1)),
            second.resolve(suppliedProgram("body", 1)),
        )
    }

    /**
     * And nothing counts across documents either. `/F1` is the first font of
     * *this* file (§7.8.3: "The scope of a resource name shall be local to a
     * particular content stream"), so two catalogs both start at `/F1` — the
     * shared counter a global cache would need does not exist.
     */
    @Test
    fun `resource names are numbered per catalog and never shared`() {
        val first = FontCatalog()
        val second = FontCatalog()

        assertEquals(PdfName("F1"), first.resolve(helvetica).resourceName)
        assertEquals(PdfName("F2"), first.resolve(suppliedProgram("body", 1)).resourceName)
        assertEquals(PdfName("F1"), second.resolve(helvetica).resourceName)

        // Asking again does not consume a name: resolution happened already.
        assertEquals(PdfName("F1"), first.resolve(helvetica).resourceName)
    }

    /**
     * The catalog is also the list of what the document uses, in the order it
     * was first used — which is what an emitter walks to write the font
     * dictionaries and the `/Font` subdictionary of §7.8.3.
     */
    @Test
    fun `the catalog reports its fonts in first-use order`() {
        val catalog = FontCatalog()
        val courier = FontRef.Standard14(Standard14Name.COURIER)
        val body = suppliedProgram("body", 1)

        catalog.resolve(courier)
        catalog.resolve(body)
        catalog.resolve(courier)

        assertEquals(listOf(courier, body), catalog.fonts().map { it.ref })
        assertTrue(FontCatalog().fonts().isEmpty())
    }

    /**
     * The simple path, end to end through the catalog: a `Standard14` reference
     * becomes the Type 1 dictionary of Table 109, naming the font and the
     * encoding of Annex D that its codes are produced by.
     */
    @Test
    fun `a standard 14 reference resolves to a Type1 dictionary with WinAnsiEncoding`() {
        val catalog = FontCatalog()

        assertEquals(
            "<</Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding>>",
            serializeToText(catalog.resolve(helvetica).fontDictionary { PdfRef(1) }),
        )
    }

    /**
     * And the string a page shows is encoded by the same entry: one byte per
     * character (§9.6.1), from Table D.2 — never the UTF-8 a Kotlin `String`
     * would hand over on its own, which for `ä` would be two bytes and two
     * wrong glyphs.
     */
    @Test
    fun `a resolved standard font encodes text as the codes its dictionary declares`() {
        val catalog = FontCatalog()
        val font = catalog.resolve(helvetica)

        assertContentEquals(byteArrayOf(72, 105), font.encode("Hi"))
        assertContentEquals(byteArrayOf(228.toByte()), font.encode("ä"))
        assertFailsWith<UnencodableCharacterException> { font.encode("Привет") }
    }

    /**
     * The `/Font` subdictionary of §7.8.3, Table 34: "A dictionary that maps
     * resource names to font dictionaries", in the form EXAMPLE 2 of the clause
     * shows — the names the catalog handed out, against indirect references to
     * the dictionaries it defined through the caller.
     */
    @Test
    fun `the catalog writes the Font subdictionary of a resource dictionary`() {
        val catalog = FontCatalog()
        catalog.resolve(helvetica)
        catalog.resolve(FontRef.Standard14(Standard14Name.COURIER))

        val defined = mutableListOf<PdfObject>()
        val resources =
            catalog.fontResources { value ->
                defined += value
                PdfRef(defined.size + 4)
            }

        assertEquals("<</F1 5 0 R /F2 6 0 R>>", serializeToText(resources))
        assertEquals(
            listOf("Helvetica", "Courier"),
            defined.map { (it as PdfDict).entries[PdfName("BaseFont")].let { name -> (name as PdfName).name } },
        )
    }

    /** A document that used no font writes no font resources. */
    @Test
    fun `an empty catalog writes an empty Font subdictionary`() {
        assertEquals("<<>>", serializeToText(FontCatalog().fontResources { PdfRef(1) }))
    }

    /**
     * The embedded path, reached through the catalog: a `Supplied` reference is
     * written as the Type 0 font of Table 119 over a `CIDFontType2` descendant,
     * and the codes it encodes text as are the two-byte glyph indices
     * `Identity-H` shows (§9.7.5.2).
     *
     * What each of those objects contains is `EmbeddedFontTest`'s business. What
     * is asserted here is that the catalog leads to them at all — that the second
     * of the two font paths is wired to the same three calls the
     * first one is.
     */
    @Test
    fun `a supplied reference resolves to a Type0 font over its embedded program`() {
        val catalog = FontCatalog()
        val font = catalog.resolve(FontRef.Supplied(suppliedFontProgram, "body"))

        assertContentEquals(byteArrayOf(0, 2), font.encode("B"))
        val dictionary = serializeToText(font.fontDictionary { PdfRef(1) })
        assertTrue("/Subtype /Type0" in dictionary, dictionary)
        assertTrue("/Encoding /Identity-H" in dictionary, dictionary)
        assertTrue("+body" in dictionary, dictionary)
    }

    /**
     * And the same font through the resource dictionary, which is how a page
     * reaches it. The supplied font's chain is defined through the caller's
     * `define`, so the `/Font` subdictionary names one object per font and the
     * objects under it are indirect as §7.3.8 and Table 115 require.
     */
    @Test
    fun `the Font subdictionary carries supplied fonts beside standard ones`() {
        val catalog = FontCatalog()
        catalog.resolve(helvetica)
        catalog.resolve(FontRef.Supplied(suppliedFontProgram, "body")).encode("B")

        val defined = mutableListOf<PdfObject>()
        val resources = catalog.fontResources { value ->
            defined += value
            PdfRef(defined.size)
        }

        // The Type 1 dictionary of the standard font, then the five objects the
        // embedded path defines — the program, the descriptor, the CIDToGIDMap,
        // the CIDFont and the ToUnicode CMap — then the Type 0 font over them.
        assertEquals("<</F1 1 0 R /F2 7 0 R>>", serializeToText(resources))
        assertEquals(7, defined.size)
    }

    /**
     * No silent loss, applied to identity. An id names one
     * font program within one document; a second, different program under the
     * same id would otherwise be drawn with the first one's glyphs — every page
     * rendering, and every page wrong.
     */
    @Test
    fun `the same id with different bytes is an error`() {
        val catalog = FontCatalog()
        catalog.resolve(suppliedProgram("body", 1))

        val failure =
            assertFailsWith<IllegalArgumentException> {
                catalog.resolve(suppliedProgram("body", 2))
            }
        assertTrue(failure.message!!.contains("body"), failure.message)
    }
}
