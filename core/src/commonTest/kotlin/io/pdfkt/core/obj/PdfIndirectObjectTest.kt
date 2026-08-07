/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.obj

import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// ISO 32000-2:2020 §7.3.10 "Indirect objects"

/**
 * What the clause requires of the definition of an indirect object:
 *
 * > Any object in a PDF file may be labelled as an indirect object. This gives
 * > the object a unique object identifier by which other objects can refer to
 * > it…
 *
 * > The definition of an indirect object in a PDF file shall consist of its
 * > object number and generation number (separated by white-space), followed by
 * > the value of the object bracketed between the keywords obj and endobj.
 *
 * > EXAMPLE 1  Indirect object definition
 * >
 * >     12 0 obj
 * >     (Brillig)
 * >     endobj
 * >
 * > Defines an indirect string object with an object number of 12, a generation
 * > number of 0, and the string value Brillig.
 *
 * The identifier itself, and the reference that carries it, are asserted in
 * `PdfRefTest`.
 */
class PdfIndirectObjectTest {
    /** Example 1, byte for byte. */
    @Test
    fun `a definition is the identifier then the value between the keywords`() {
        assertContentEquals(
            "12 0 obj\n(Brillig)\nendobj".encodeToByteArray(),
            serializeIndirect(PdfRef(12, 0), PdfString("Brillig".encodeToByteArray())),
        )
    }

    /**
     * The same example as the clause prints it. Its layout across three lines
     * is "purely for clarity of exposition and need not be included in
     * practical use" (§7.2.3, NOTE 2), so the two are compared up to the
     * white-space that only lays them out — see [withoutLayoutWhiteSpace].
     */
    @Test
    fun `the definition of example 1 is the example`() {
        assertEquals(
            withoutLayoutWhiteSpace("12 0 obj\n(Brillig)\nendobj"),
            withoutLayoutWhiteSpace(serializeIndirectToText(PdfRef(12, 0), PdfString("Brillig".encodeToByteArray()))),
        )
    }

    /**
     * Example 3's second object — the length of the stream that precedes it:
     *
     * >     8 0 obj
     * >        77                 %The length of the preceding stream
     * >     endobj
     *
     * A value made of regular characters is why the white-space around the
     * keywords is not optional: `8 0 obj77` and `77endobj` would each be one
     * token, and the object would be neither the integer 77 nor a definition.
     */
    @Test
    fun `a value of regular characters is separated from both keywords`() {
        assertEquals("8 0 obj\n77\nendobj", serializeIndirectToText(PdfRef(8, 0), PdfInt(77)))
    }

    /**
     * > …its object number and generation number (separated by white-space)…
     *
     * The head of a definition carries the identifier in the same digits an
     * indirect reference to it carries (§7.3.10, `PdfRefTest`), so what the
     * cross-reference table will point at and what the referring object says
     * cannot drift apart.
     */
    @Test
    fun `the head of a definition is the identifier of every reference to it`() {
        for (id in listOf(PdfRef(1), PdfRef(12, 0), PdfRef(17, 5), PdfRef(1_000_000, 65535))) {
            val head = serializeIndirectToText(id, PdfInt(0)).substringBefore('\n')
            val reference = serializeToText(id)
            assertEquals(
                reference.removeSuffix("R") + "obj",
                head,
                "the definition of $id does not open with the identifier its references carry",
            )
        }
    }

    /**
     * "Any object in a PDF file may be labelled as an indirect object": every
     * variant of the hierarchy is framed the same way, and the value inside the
     * framing is the value as [writePdfObject] writes it anywhere else.
     */
    @Test
    fun `any object may be the value and is written by the same entry point`() {
        for (value in PdfObjectSamples.all) {
            val bytes = serializeIndirect(PdfRef(3, 0), value)
            val text = bytes.decodeToString()
            assertTrue(
                text.startsWith("3 0 obj\n"),
                "${value::class.simpleName} was not framed by its identifier: $text",
            )
            assertTrue(
                text.endsWith("\nendobj"),
                "${value::class.simpleName} was not closed by endobj: $text",
            )
            assertContentEquals(
                serialize(value),
                bytes.copyOfRange("3 0 obj\n".length, bytes.size - "\nendobj".length),
                "${value::class.simpleName} was written differently inside the framing",
            )
        }
    }

    /**
     * > NOTE (2020) Any object outside of an object stream … can consist solely
     * > of an object reference. PDF syntax thus permits chains of such objects.
     *
     * So a reference is a value here like anywhere else, and a definition whose
     * body is one is a link in such a chain rather than a mistake.
     */
    @Test
    fun `a definition may consist solely of a reference`() {
        assertEquals("6 0 obj\n4 0 R\nendobj", serializeIndirectToText(PdfRef(6, 0), PdfRef(4, 0)))
    }

    /**
     * "All streams shall be indirect objects" (§7.3.8.1), so this framing is
     * the only way a stream reaches a file — and §7.3.8 constrains what may sit
     * between the two: "There shall not be any extra bytes, other than
     * white-space, between endstream and endobj." What is written there is one
     * LINE FEED.
     */
    @Test
    fun `between endstream and endobj there is nothing but white-space`() {
        val stream = PdfStream(PdfDict(emptyMap()), "BT /F1 12 Tf".encodeToByteArray())
        assertEquals(
            "4 0 obj\n<</Length 12>>\nstream\nBT /F1 12 Tf\nendstream\nendobj",
            serializeIndirectToText(PdfRef(4, 0), stream),
        )
    }

    /**
     * The value may be the null object. A definition of null and no definition
     * at all are the same thing to a reader — "An indirect reference to an
     * undefined object … shall be treated as a reference to the null object" —
     * and writing one is how a writer keeps an object number it has already
     * handed out but has nothing to put under.
     */
    @Test
    fun `a definition of the null object is a definition`() {
        assertEquals("9 0 obj\nnull\nendobj", serializeIndirectToText(PdfRef(9, 0), PdfNull))
    }

    /**
     * Nothing follows `endobj`. Where one definition ends and the next begins
     * is a decision about the file body, taken together with the byte offsets
     * the cross-reference table records (§7.5.4), and both belong to the
     * writer — so the framing leaves the sink exactly at the last byte of the
     * keyword, and two definitions written in a row are adjacent.
     */
    @Test
    fun `nothing is written after the keyword endobj`() {
        val buffer = Buffer()
        buffer.writeIndirectObject(PdfRef(1, 0), PdfInt(1))
        buffer.writeIndirectObject(PdfRef(2, 0), PdfInt(2))

        assertEquals("1 0 obj\n1\nendobj2 0 obj\n2\nendobj", buffer.readByteArray().decodeToString())
    }

    /** The framing depends on nothing but its two arguments. */
    @Test
    fun `framing is a pure function of the identifier and the value`() {
        for (value in PdfObjectSamples.all) {
            assertEquals(
                serializeIndirectToText(PdfRef(5, 2), value),
                serializeIndirectToText(PdfRef(5, 2), value),
                "${value::class.simpleName} was not framed identically twice",
            )
        }
    }
}
