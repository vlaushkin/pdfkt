/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.image

import io.pdfkt.core.obj.PdfDict
import io.pdfkt.core.obj.PdfInt
import io.pdfkt.core.obj.PdfName
import io.pdfkt.core.obj.PdfObject
import io.pdfkt.core.obj.PdfRef
import io.pdfkt.core.obj.PdfStream
import io.pdfkt.core.obj.StreamBody

// ISO 32000-2:2020 §7.8.3 "Resource dictionaries" (Table 34),
// §8.8.1 "External objects — General" (Table 86),
// §8.9.5.1 "Image dictionaries — General" (Table 87)

/**
 * The images of one document: which [ImageRef] the document draws, and what each
 * of them is called in a resource dictionary.
 *
 * Symmetric to `FontCatalog` in every way that matters: one instance per document, created by
 * whoever starts the render and
 * discarded with it, no global state, not thread-safe within one render. Two
 * documents drawing the same image are two catalogs that share nothing.
 *
 * What it buys is the thing a page cannot express by itself: an image drawn on
 * four pages is *one* image XObject in the file, named `/Im1` on each of them,
 * because all four pages resolved the same reference through the same catalog.
 */
public class ImageCatalog {
    /** The images resolved so far, keyed by id and in first-use order. */
    private val entries: MutableMap<String, ResolvedImage> = mutableMapOf()

    /**
     * The document's entry for [ref], naming it on the first call and returning
     * that same entry on every later one.
     *
     * @throws IllegalArgumentException if this document already resolved a
     *   *different* image under the same [ImageRef.id]. The alternative to
     *   refusing is a document that quietly draws the first image where the
     *   second was asked for — wrong on the page and reported as a success.
     */
    public fun resolve(ref: ImageRef): ResolvedImage {
        val existing = entries[ref.id]
        if (existing != null) {
            requireSameImage(existing.ref, ref)
            return existing
        }
        val resolved = ResolvedImage(ref, PdfName("$RESOURCE_NAME_PREFIX${entries.size + 1}"))
        entries[ref.id] = resolved
        return resolved
    }

    /** Every image this document has resolved, in the order it was first drawn. */
    public fun images(): List<ResolvedImage> = entries.values.toList()

    /**
     * The `/XObject` subdictionary of a resource dictionary — Table 34: "A
     * dictionary that maps resource names to external objects" — holding every
     * image this document has resolved.
     *
     * The names are the ones the catalog handed out, so the name a content
     * stream's `Do` operator paints is the name the plan drew under: "The
     * operand name shall appear as a key in the XObject subdictionary of the
     * current resource dictionary" (Table 86).
     *
     * Each image becomes an indirect object through [define], which is what lets
     * several pages share one — and what makes `Do` legal at all, since "all
     * streams shall be indirect objects" (§7.3.8). Object numbers belong to the
     * file, so defining is the caller's: `PdfDocumentWriter::define` is what a
     * document passes.
     */
    public fun imageResources(define: (PdfObject) -> PdfRef): PdfDict =
        PdfDict(entries.values.associate { image -> image.resourceName to define(image.imageXObject(define)) })

    private companion object {
        /**
         * `/Im1`, `/Im2`, … — as with fonts, §7.8.3 fixes neither the spelling
         * nor a numbering ("The scope of a resource name shall be local to a
         * particular content stream"), and numbering per document makes a name
         * mean the same image throughout a file.
         */
        const val RESOURCE_NAME_PREFIX: String = "Im"

        /**
         * Two references carrying one id shall describe one image. Compared by
         * everything that reaches the file, with the samples by identity first —
         * the usual case is one array handed round — and by contents otherwise,
         * which is the only answer that is actually true.
         */
        fun requireSameImage(resolved: ImageRef, incoming: ImageRef) {
            require(sameImage(resolved, incoming)) {
                "two different images supplied under the id \"${incoming.id}\" ($resolved, then " +
                    "$incoming): an id names one image within one document, so this document would " +
                    "draw one of the two in the other's place. Give the second image an id of its own"
            }
        }

        /**
         * Whether two references describe one image, down to the soft mask —
         * the alpha is as much of what a page shows as the colour is, so two
         * images alike in every sample and different in their transparency are
         * two images.
         */
        fun sameImage(resolved: ImageRef, incoming: ImageRef): Boolean = resolved.width == incoming.width &&
            resolved.height == incoming.height &&
            resolved.colorSpace == incoming.colorSpace &&
            resolved.bitsPerComponent == incoming.bitsPerComponent &&
            resolved.filter == incoming.filter &&
            sameSamples(resolved.samples, incoming.samples) &&
            sameSoftMask(resolved.softMask, incoming.softMask)

        /**
         * Samples in memory are compared by identity and then by contents; samples
         * read from a file when they are written are the same samples when they
         * are the same file of the same length — their bytes are not at hand.
         */
        fun sameSamples(resolved: StreamBody, incoming: StreamBody): Boolean = when {
            resolved is StreamBody.Bytes && incoming is StreamBody.Bytes ->
                resolved.array === incoming.array || resolved.array.contentEquals(incoming.array)
            resolved is StreamBody.Opened && incoming is StreamBody.Opened ->
                resolved.key == incoming.key && resolved.length == incoming.length
            else -> false
        }

        fun sameSoftMask(resolved: ImageRef?, incoming: ImageRef?): Boolean = when {
            resolved == null || incoming == null -> resolved == null && incoming == null
            else -> sameImage(resolved, incoming)
        }
    }
}

/**
 * One image of one document: what it was asked for by, and what it is called in
 * a resource dictionary.
 *
 * @property ref the reference this was resolved from.
 * @property resourceName the name this image is known by in the `/XObject`
 *   subdictionary of a page's resource dictionary (§7.8.3) — the name a content
 *   stream's `Do` operator paints it by.
 */
public class ResolvedImage internal constructor(public val ref: ImageRef, public val resourceName: PdfName) {
    /**
     * The image XObject this image is written into the file as: "a graphics
     * object whose contents are defined by a self-contained stream" (§8.8.1),
     * whose dictionary is Table 87.
     *
     * Every entry Table 87 marks Required is written, plus the two names that
     * make the object self-describing:
     *
     * - `/Type /XObject` — Optional, "if present, shall be XObject for an image
     *   XObject";
     * - `/Subtype /Image` — Required here, since this is not a thumbnail: "The
     *   effect of Do depends on the value of the XObject's Subtype entry"
     *   (Table 86);
     * - `/Width` and `/Height`, "in samples";
     * - `/ColorSpace`, "Required for images … The colour space in which image
     *   samples shall be specified";
     * - `/BitsPerComponent`, "Required except for image masks";
     * - `/Filter`, only when there is one — §7.4 makes it Optional, and an
     *   absent one means the stream data are the samples themselves.
     *
     * plus `/SMask` where the image carries alpha.
     *
     * Nothing else. `/Decode` is absent because the default of Table 88 is what
     * these samples want, `/ImageMask` because false is its default, and
     * `/Interpolate` because "false" is its default and interpolation is a
     * reader's decision this library has no reason to force.
     *
     * [define] is what turns an object into an indirect reference: the soft mask
     * is a `/SMask` entry whose type Table 87 gives as "stream", and "all streams
     * shall be indirect objects" (§7.3.8), so writing one needs an object number
     * exactly as the image itself does.
     */
    public fun imageXObject(define: (PdfObject) -> PdfRef): PdfStream = imageStream(ref, define)

    override fun toString(): String = "ResolvedImage(/${resourceName.name} -> $ref)"
}

/**
 * The image XObject stream for [ref], and for its soft mask through the same
 * function.
 *
 * A soft-mask image "shall have the same format and meaning as in that of an
 * ordinary image XObject" (§11.6.5.2), so it is built here too rather than by a
 * path of its own — the restrictions of Table 143 are on what [ImageRef] admits
 * as a mask, not on how one is written. It gets no resource name because Table
 * 143 says of `/Name` that it is "Ignored": nothing paints a mask with `Do`, the
 * parent image's `/SMask` is the only thing that reaches it.
 */
private fun imageStream(ref: ImageRef, define: (PdfObject) -> PdfRef): PdfStream {
    val dictionary =
        mutableMapOf<PdfName, PdfObject>(
            PdfName("Type") to PdfName("XObject"),
            PdfName("Subtype") to PdfName("Image"),
            PdfName("Width") to PdfInt(ref.width.toLong()),
            PdfName("Height") to PdfInt(ref.height.toLong()),
            PdfName("ColorSpace") to ref.colorSpace.pdfObject,
            PdfName("BitsPerComponent") to PdfInt(ref.bitsPerComponent.toLong()),
        )
    ref.filter?.let { dictionary[PdfName("Filter")] = it.pdfName }
    ref.softMask?.let { mask -> dictionary[PdfName("SMask")] = define(imageStream(mask, define)) }
    return PdfStream(PdfDict(dictionary), ref.samples)
}
