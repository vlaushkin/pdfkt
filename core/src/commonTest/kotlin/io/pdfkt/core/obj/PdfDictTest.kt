/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.obj

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// ISO 32000-2:2020 §7.3.7 "Dictionary objects"

/**
 * What the clause requires of a dictionary object:
 *
 * > The first element of each entry is the key and the second element is the
 * > value. The key shall be a name … All keys shall be direct objects. The
 * > value may be any kind of object, including another dictionary. An indirect
 * > reference … shall be used as the value to refer to a stream object …
 * > A dictionary entry whose value is null … shall be treated the same as if
 * > the entry does not exist. … A dictionary may have zero entries.
 *
 * > The entries in a dictionary represent an associative table and as such
 * > shall be unordered even though an arbitrary order may be imposed upon them
 * > when written in a file. That ordering shall be ignored.
 *
 * > Multiple entries in the same dictionary shall not have the same key.
 *
 * > A dictionary shall be written as a sequence of key-value pairs enclosed in
 * > double angle brackets (<<…>>) (using LESS-THAN SIGNs (3Ch) and
 * > GREATER-THAN SIGNs (3Eh)).
 */
class PdfDictTest {
    /**
     * The clause's own example, entry for entry — six entries whose values
     * span a name, a real, an integer, a string and a nested dictionary.
     *
     * It is written here on one line, which is the same dictionary: the
     * example's indentation "is purely for clarity of exposition and need not
     * be included in practical use" (§7.2.3, NOTE 2). The test below compares
     * this rendering against the example as the clause prints it.
     */
    @Test
    fun `the example of the clause is written entry for entry`() {
        assertEquals(
            "<</Type /Example /Subtype /DictionaryExample /Version 0.01 /IntegerItem 12 " +
                "/StringItem (a string) /Subdictionary <</Item1 0.4 /Item2 true " +
                "/LastItem (not !) /VeryLastItem (OK)>>>>",
            serializeToText(EXAMPLE),
        )
    }

    /** The same claim against the example exactly as the clause prints it. */
    @Test
    fun `the example of the clause differs from what is written only in layout`() {
        assertEquals(
            withoutLayoutWhiteSpace(EXAMPLE_AS_PRINTED),
            withoutLayoutWhiteSpace(serializeToText(EXAMPLE)),
        )
    }

    /** "A dictionary may have zero entries." */
    @Test
    fun `a dictionary with no entries is the two pairs of angle brackets`() {
        assertEquals("<<>>", serializeToText(PdfDict(emptyMap())))
    }

    /**
     * A key is written by the rules of §7.3.5 like any other name — the reason
     * [writePdfDict] hands it to the same entry point as the value. A key
     * whose name holds a space is written with the escape that clause
     * requires, not with a space that would end the token.
     */
    @Test
    fun `a key is written as a name with the escaping its own clause requires`() {
        assertEquals("<</Lime#20Green 1>>", serializeToText(PdfDict(mapOf(PdfName("Lime Green") to PdfInt(1)))))
        assertEquals(
            "<</The_Key_of_F#23_Minor 1>>",
            serializeToText(PdfDict(mapOf(PdfName("The_Key_of_F#_Minor") to PdfInt(1)))),
        )
        assertEquals("<</ 1>>", serializeToText(PdfDict(mapOf(PdfName("") to PdfInt(1)))))
    }

    /**
     * The key and the value are separate objects and stay separate tokens.
     * `/Version` followed immediately by `0.01` would be read as the single
     * name `/Version0.01` — a solidus ends a name only at a delimiter or at
     * white-space (§7.2.3) — so a key and a number are always separated.
     */
    @Test
    fun `a key and its value are separated so that the two do not merge`() {
        assertEquals("<</Version 0.01>>", serializeToText(PdfDict(mapOf(PdfName("Version") to PdfReal(0.01)))))
        assertEquals("<</IntegerItem 12>>", serializeToText(PdfDict(mapOf(PdfName("IntegerItem") to PdfInt(12)))))
        assertEquals("<</Item2 true>>", serializeToText(PdfDict(mapOf(PdfName("Item2") to PdfBool(true)))))
    }

    /**
     * > A dictionary entry whose value is null (see 7.3.9, "Null object")
     * > shall be treated the same as if the entry does not exist.
     *
     * "Treated the same" is stronger than "written the same", so the entry is
     * gone from the object as well: what a caller reads back from [PdfDict]
     * is what a reader of the file would find.
     */
    @Test
    fun `an entry whose value is null is the same as no entry at all`() {
        val withNull =
            PdfDict(
                mapOf(
                    PdfName("Type") to PdfName("Page"),
                    PdfName("Missing") to PdfNull,
                ),
            )
        val without = PdfDict(mapOf(PdfName("Type") to PdfName("Page")))

        assertFalse(PdfName("Missing") in withNull.entries, "a null-valued entry survived in the object")
        assertEquals(without.entries, withNull.entries)
        assertEquals(serializeToText(without), serializeToText(withNull))
        assertEquals("<</Type /Page>>", serializeToText(withNull))
    }

    @Test
    fun `a dictionary of nothing but null-valued entries has zero entries`() {
        val allNull = PdfDict(mapOf(PdfName("A") to PdfNull, PdfName("B") to PdfNull))
        assertEquals(emptyMap<PdfName, PdfObject>(), allNull.entries)
        assertEquals("<<>>", serializeToText(allNull))
    }

    /**
     * The null object stays written where the clause does not remove it: an
     * array element inside a dictionary is not a dictionary entry.
     */
    @Test
    fun `a null inside a value is untouched by the rule about entries`() {
        val dict = PdfDict(mapOf(PdfName("A") to PdfArray(listOf(PdfNull))))
        assertEquals("<</A [null]>>", serializeToText(dict))
    }

    /** "The value may be any kind of object, including another dictionary." */
    @Test
    fun `values nest to any depth`() {
        val depth = 32
        var nested: PdfObject = PdfInt(1)
        repeat(depth) { nested = PdfDict(mapOf(PdfName("D") to nested)) }
        assertEquals("<</D ".repeat(depth) + "1" + ">>".repeat(depth), serializeToText(nested))
    }

    @Test
    fun `a dictionary holds arrays and dictionaries as values`() {
        val page =
            PdfDict(
                mapOf(
                    PdfName("Type") to PdfName("Page"),
                    PdfName("MediaBox") to PdfArray(listOf(PdfInt(0), PdfInt(0), PdfReal(595.276), PdfReal(841.89))),
                    PdfName("Resources") to
                        PdfDict(mapOf(PdfName("Font") to PdfDict(mapOf(PdfName("F1") to PdfRef(5, 0))))),
                ),
            )
        assertEquals(
            "<</Type /Page /MediaBox [0 0 595.276 841.89] /Resources <</Font <</F1 5 0 R>>>>>>",
            serializeToText(page),
        )
    }

    /**
     * > Multiple entries in the same dictionary shall not have the same key.
     *
     * Two names are the same name when the sequences of characters they denote
     * match (§7.3.5), so a key given twice is one key, and the dictionary that
     * results has one entry — the last value given, since that is what the
     * caller's own map building says.
     */
    @Test
    fun `a key given twice yields one entry`() {
        val dict = PdfDict(mapOf(PdfName("Type") to PdfName("First"), PdfName("Type") to PdfName("Second")))
        assertEquals(1, dict.entries.size)
        assertEquals("<</Type /Second>>", serializeToText(dict))
    }

    /**
     * > The entries in a dictionary … shall be unordered even though an
     * > arbitrary order may be imposed upon them when written in a file. That
     * > ordering shall be ignored.
     *
     * Two dictionaries built with the entries in different orders are the same
     * table, and each writes all of its entries; only the order in which they
     * appear differs, and that order carries nothing.
     */
    @Test
    fun `the order entries are written in is arbitrary and carries no meaning`() {
        val forwards = PdfDict(mapOf(PdfName("A") to PdfInt(1), PdfName("B") to PdfInt(2)))
        val backwards = PdfDict(mapOf(PdfName("B") to PdfInt(2), PdfName("A") to PdfInt(1)))

        assertEquals(forwards.entries, backwards.entries)
        assertEquals("<</A 1 /B 2>>", serializeToText(forwards))
        assertEquals("<</B 2 /A 1>>", serializeToText(backwards))
    }

    /**
     * > An indirect reference … shall be used as the value to refer to a
     * > stream object
     *
     * and "All streams shall be indirect objects" (§7.3.8.1). The value that
     * stands for a stream is therefore the reference to it.
     */
    @Test
    fun `a stream is referred to by an indirect reference and cannot be a value`() {
        assertEquals("<</Contents 4 0 R>>", serializeToText(PdfDict(mapOf(PdfName("Contents") to PdfRef(4, 0)))))
        val rejected =
            assertFailsWith<IllegalArgumentException> {
                PdfDict(mapOf(PdfName("Contents") to PdfObjectSamples.stream))
            }
        assertTrue(
            rejected.message?.contains("/Contents") == true,
            "the refusal does not say which entry it is about: ${rejected.message}",
        )
    }

    /**
     * A dictionary is the table it was built from, not whatever map the caller
     * goes on to edit — otherwise what a file says would depend on when it was
     * written rather than on which object was written.
     */
    @Test
    fun `a dictionary keeps the entries it was built from`() {
        val source = mutableMapOf<PdfName, PdfObject>(PdfName("A") to PdfInt(1))
        val dict = PdfDict(source)
        source[PdfName("B")] = PdfInt(2)
        assertEquals(1, dict.entries.size)
        assertEquals("<</A 1>>", serializeToText(dict))
    }

    private companion object {
        /** The dictionary of the clause's EXAMPLE, entry by entry. */
        val EXAMPLE =
            PdfDict(
                mapOf(
                    PdfName("Type") to PdfName("Example"),
                    PdfName("Subtype") to PdfName("DictionaryExample"),
                    PdfName("Version") to PdfReal(0.01),
                    PdfName("IntegerItem") to PdfInt(12),
                    PdfName("StringItem") to PdfString("a string".encodeToByteArray()),
                    PdfName("Subdictionary") to
                        PdfDict(
                            mapOf(
                                PdfName("Item1") to PdfReal(0.4),
                                PdfName("Item2") to PdfBool(true),
                                PdfName("LastItem") to PdfString("not !".encodeToByteArray()),
                                PdfName("VeryLastItem") to PdfString("OK".encodeToByteArray()),
                            ),
                        ),
                ),
            )

        /** The same example as the clause prints it, indentation included. */
        val EXAMPLE_AS_PRINTED =
            """
            <</Type /Example
                     /Subtype /DictionaryExample
                     /Version 0.01
                     /IntegerItem 12
                     /StringItem (a string)
                     /Subdictionary <<
                           /Item1 0.4
                           /Item2 true
                           /LastItem (not !)
                           /VeryLastItem (OK)
                           >>
            >>
            """.trimIndent()
    }
}
