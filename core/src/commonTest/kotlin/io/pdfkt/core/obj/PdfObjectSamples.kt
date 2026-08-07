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

// ISO 32000-2:2020 §7.3.1 "Objects — General"

/**
 * One instance of every variant of [PdfObject].
 *
 * `PdfObjectHierarchyTest` checks this list against the hierarchy itself, so
 * a variant added later cannot quietly go untested — it fails there until it
 * is listed here.
 */
internal object PdfObjectSamples {
    val bool: PdfBool = PdfBool(true)
    val int: PdfInt = PdfInt(42L)
    val real: PdfReal = PdfReal(0.5)
    val name: PdfName = PdfName("Type")
    val string: PdfString = PdfString("Sample".encodeToByteArray())
    val array: PdfArray = PdfArray(listOf(int, real, name))
    val dict: PdfDict = PdfDict(mapOf(name to PdfName("Catalog")))
    val stream: PdfStream = PdfStream(dict, "Sample".encodeToByteArray())
    val ref: PdfRef = PdfRef(3, 0)
    val nullObject: PdfNull = PdfNull

    /** Every variant, in the order the clause introduces them. */
    val all: List<PdfObject> =
        listOf(bool, int, real, name, string, array, dict, stream, ref, nullObject)
}

/** Serializes [obj] through the single entry point and returns the bytes. */
internal fun serialize(obj: PdfObject): ByteArray {
    val buffer = Buffer()
    buffer.writePdfObject(obj)
    return buffer.readByteArray()
}

/** The same bytes, decoded for readable assertions on the ASCII-only samples. */
internal fun serializeToText(obj: PdfObject): String = serialize(obj).decodeToString()

/**
 * Frames [value] as the indirect object [id] and returns the bytes — the
 * definition of §7.3.10, which is not a variant of [PdfObject] and so does not
 * go through [serialize].
 */
internal fun serializeIndirect(id: PdfRef, value: PdfObject): ByteArray {
    val buffer = Buffer()
    buffer.writeIndirectObject(id, value)
    return buffer.readByteArray()
}

/** The same bytes, decoded for readable assertions on the ASCII-only samples. */
internal fun serializeIndirectToText(id: PdfRef, value: PdfObject): String =
    serializeIndirect(id, value).decodeToString()
