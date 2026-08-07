/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.core.doc

import io.pdfkt.core.file.PdfFileWriter
import io.pdfkt.core.obj.PdfArray
import io.pdfkt.core.obj.PdfDict
import io.pdfkt.core.obj.PdfInt
import io.pdfkt.core.obj.PdfName
import io.pdfkt.core.obj.PdfObject
import io.pdfkt.core.obj.PdfObjectNumbering
import io.pdfkt.core.obj.PdfReal
import io.pdfkt.core.obj.PdfRef
import io.pdfkt.core.obj.PdfStream
import kotlinx.io.Sink

// ISO 32000-2:2020 §7.7.2 "Document catalog dictionary",
// §7.7.3.2 "Page tree nodes", §7.7.3.3 "Page objects",
// §7.8.2 "Content streams", §7.8.3 "Resource dictionaries", §7.9.5 "Rectangles"

/**
 * Writes the document structure of one file: the catalog, the page tree and the
 * pages hanging off it.
 *
 * §7.5 gives a file four parts and [PdfFileWriter] writes them; what it does not
 * say is what the objects in the body are. Clause 7.7 does: "The root of a
 * document's object hierarchy is the catalog dictionary, located by means of the
 * Root entry in the trailer" (§7.7.2), and "The pages of a document are accessed
 * through a structure known as the page tree" (§7.7.3). This class is those two
 * clauses and nothing else — it holds the tree together so that a caller who has
 * a content stream and a resource dictionary can put them on a page without
 * assembling three dictionaries and keeping their cross-references straight.
 *
 * It is one layer above [PdfFileWriter] and delegates everything about the file
 * to it: numbering, offsets, the cross-reference table and the trailer. It adds
 * no knowledge of where bytes land.
 *
 * ## Using it
 *
 * ```
 * val document = PdfDocumentWriter(sink)
 * val fonts = FontCatalog()
 * val helvetica = fonts.resolve(FontRef.Standard14(Standard14Name.HELVETICA))
 * builder.textFont(helvetica.resourceName, 24.0)
 * builder.showText(helvetica.encode("Hello, World!"))
 * document.addPage(
 *     mediaBox = pdfRectangle(0.0, 0.0, 612.0, 792.0),
 *     resources = PdfDict(mapOf(PdfName("Font") to fonts.fontResources(document::define))),
 *     content = builder.build(),
 * )
 * document.finish(fileIdentifier = …)
 * ```
 *
 * ## The shape of the tree
 *
 * One root node with every page as its immediate child. §7.7.3.1 describes the
 * tree as one a processor may balance — "the tree structure allows PDF
 * processors, using only limited memory, to quickly open a document containing
 * thousands of pages" — but requires no particular shape of a writer, and NOTE 1
 * of §7.7.3.2 removes the other reason to build one: "The structure of the page
 * tree is not necessarily related to the logical structure of the document."
 * A flat tree is therefore correct, and a deeper one is an optimisation for
 * documents this library cannot yet produce.
 *
 * Nothing is inherited (§7.7.3.4). Every page carries its own `/Resources` and
 * `/MediaBox`, which is what §7.8.3 asks for in the case of the first — "PDF
 * writers should not use this inheritance feature of PDF as its use can cause
 * undue complexity for a PDF reader. A PDF writer should only include resource
 * definitions for resources that are actually referenced by the content streams
 * of the associated page" — and consistency for the case of the second.
 *
 * One instance writes the structure of one file, is not thread-safe, and is
 * finished exactly once.
 */
public class PdfDocumentWriter(sink: Sink) : PdfObjectNumbering {
    private val file = PdfFileWriter(sink)

    /**
     * The root page tree node, reserved before any page exists because each page
     * names it as its `/Parent` (Table 31) while it names each page in its
     * `/Kids` (Table 30) — the graph [PdfFileWriter.reserve] exists for.
     */
    private val pageTreeRoot: PdfRef = file.reserve()

    /** The pages defined so far, in the order [addPage] was called. */
    private val kids: MutableList<PdfRef> = mutableListOf()

    private var finished: Boolean = false

    /** Reserves an identifier of this file, per [PdfObjectNumbering]. */
    override fun reserve(): PdfRef = file.reserve()

    /**
     * Defines an object of this file's body and returns the identifier it is
     * referred to by — a font dictionary, an image, anything a page's resources
     * name. The document structure is what this class writes; what the pages
     * *contain* is the caller's, and this is the door it goes through.
     */
    public fun define(value: PdfObject): PdfRef = file.define(value)

    /** Defines the object [id] names, for an identifier taken from [reserve]. */
    public fun define(id: PdfRef, value: PdfObject) {
        file.define(id, value)
    }

    /**
     * Appends one page to the tree: a content stream, and the page object of
     * Table 31 that points at it.
     *
     * Every entry Table 31 marks Required is written and no entry that is not:
     *
     * - `/Type /Page`;
     * - `/Parent`, "shall be an indirect reference", the root node;
     * - `/Resources`, "A dictionary containing any resources required by the
     *   page contents (see 7.8.3)". "If the page requires no resources, the
     *   value of this entry shall be an empty dictionary", which is what an
     *   empty [resources] writes;
     * - `/MediaBox`, "A rectangle (see 7.9.5) … that shall define the boundaries
     *   of the physical medium on which the page shall be displayed or printed".
     *
     * `/Contents` is Optional — "If this entry is absent, the page shall be
     * empty" — and is written because a page with content is what this method
     * is for. It is "either a single stream or an array of streams" and a single
     * stream is written; the array exists so that a writer may interleave other
     * objects with the content, which this one has no reason to do.
     *
     * The content becomes an indirect object of its own, as it must: §7.3.8
     * requires that "all streams shall be indirect objects".
     *
     * @param mediaBox the page boundary, four numbers per §7.9.5 — build one
     *   with [pdfRectangle].
     * @param resources the resource dictionary (§7.8.3) the content stream's
     *   named resources are looked up in: "The scope of a resource name shall be
     *   local to a particular content stream." A name a content stream uses and
     *   this dictionary does not define is a name that resolves to nothing, and
     *   this class cannot tell — it does not read content streams.
     * @param content the bytes of the content stream, as
     *   `io.pdfkt.core.content.ContentStreamBuilder` produces them.
     * @param annotations what is attached to this page rather than painted on
     *   it — links, and every other annotation of §12.5. Table 31 makes
     *   `/Annots` Optional and this writes none when the list is empty. Each
     *   dictionary becomes an indirect object here, which the entry requires:
     *   "An array of annotation dictionaries that shall contain indirect
     *   references to all annotations associated with the page" (Table 31). It
     *   is also what makes §12.5.2's rule hold by construction — "A given
     *   annotation dictionary shall be referenced from the Annots array of only
     *   one page" — since the object is created by, and for, this one page.
     * @return the identifier of the page object, which is what a destination or
     *   an annotation elsewhere in the document refers to.
     * @throws IllegalArgumentException if [mediaBox] is not a rectangle.
     * @throws IllegalStateException if the document is already finished.
     */
    public fun addPage(
        mediaBox: PdfArray,
        resources: PdfDict,
        content: ByteArray,
        annotations: List<PdfDict> = emptyList(),
    ): PdfRef {
        check(!finished) { FINISHED }
        requireRectangle(mediaBox)
        val contents = file.define(PdfStream(PdfDict(emptyMap()), content))
        val entries =
            mutableMapOf<PdfName, PdfObject>(
                PdfName("Type") to PdfName("Page"),
                PdfName("Parent") to pageTreeRoot,
                PdfName("Resources") to resources,
                PdfName("MediaBox") to mediaBox,
                PdfName("Contents") to contents,
            )
        if (annotations.isNotEmpty()) {
            entries[PdfName("Annots")] = PdfArray(annotations.map { annotation -> file.define(annotation) })
        }
        val page = file.define(PdfDict(entries))
        kids += page
        return page
    }

    /**
     * Ends the document: the root page tree node, the catalog, and the file
     * around them.
     *
     * The root node is Table 30 in full. `/Type /Pages`; `/Kids`, "An array of
     * indirect references to the immediate children of this node"; `/Count`,
     * "The number of leaf nodes (page objects) that are descendants of this node
     * within the page tree" — which in a flat tree is the size of `/Kids`, and
     * the table demands they agree: "A PDF writer shall ensure that the value of
     * the Count key is consistent with the number of entries in the Kids array
     * and its descendants." `/Parent` is absent because it is "not permitted in
     * the root node".
     *
     * The catalog is Table 29's two Required entries: `/Type /Catalog` and
     * `/Pages`, "shall be an indirect reference", "The page tree node that shall
     * be the root of the document's page tree". `/Version` is absent because the
     * entry is for a document conforming to a version "later than the version
     * specified in the file's header", and the header this library writes is the
     * version it writes.
     *
     * Both are written here rather than as they become known, because neither is
     * complete until the last page is: the root node's `/Kids` is every page and
     * the catalog names the root node.
     *
     * @param fileIdentifier the `/ID` of the trailer, per [PdfFileWriter.finish].
     * @param info the `/Info` of the trailer (§14.3.3), or null — an object of
     *   this document, as Table 15 requires an indirect reference. Build one
     *   with [documentInformation]; what a document says about itself is the
     *   caller's, exactly as its pages are.
     * @throws IllegalStateException if no page was added. There is no clause
     *   against a page tree with no leaves, and this is a decision of this
     *   writer rather than a requirement: a document is being written in order
     *   to be displayed, and a file whose `/Count` is 0 has nothing to display.
     *   The alternative is a file that every reader accepts and no reader shows.
     */
    public fun finish(fileIdentifier: ByteArray, info: PdfRef? = null) {
        check(!finished) { FINISHED }
        check(kids.isNotEmpty()) {
            "a document with no pages: nothing was added to the page tree, and its root node would " +
                "have an empty /Kids and a /Count of 0 (ISO 32000-2:2020 §7.7.3.2, Table 30)"
        }
        file.define(
            pageTreeRoot,
            PdfDict(
                mapOf(
                    PdfName("Type") to PdfName("Pages"),
                    PdfName("Kids") to PdfArray(kids),
                    PdfName("Count") to PdfInt(kids.size.toLong()),
                ),
            ),
        )
        val catalog =
            file.define(
                PdfDict(
                    mapOf(
                        PdfName("Type") to PdfName("Catalog"),
                        PdfName("Pages") to pageTreeRoot,
                    ),
                ),
            )
        finished = true
        file.finish(root = catalog, fileIdentifier = fileIdentifier, info = info)
    }

    private companion object {
        const val FINISHED: String =
            "this document is finished; a writer writes one document (§7.7.2)"
    }
}

/**
 * A rectangle (§7.9.5), which is what `/MediaBox` and every other box of a page
 * object is:
 *
 * > A rectangle shall be written as an array of four numbers giving the
 * > coordinates of a pair of diagonally opposite corners.
 * >
 * > Typically, the array takes the form
 * >
 * > [llx lly urx ury]
 *
 * The four parameters are named for those four coordinates and are written in
 * that order. Neither the ordering of the corners nor the sizes are constrained:
 * the clause calls its own form typical rather than required, since "The other
 * two corners of the rectangle are then assumed to have coordinates (llx, ury)
 * and (urx, lly)" holds for any pair of opposite corners, and it admits the
 * degenerate cases outright — "NOTE Rectangles can have a width of zero or
 * height of zero."
 *
 * Coordinates are "expressed in default user space units" (Table 31) where a
 * unit is 1/72 inch (§8.3.2.3), so a page of US Letter size is
 * `pdfRectangle(0.0, 0.0, 612.0, 792.0)` and one of A4 is
 * `pdfRectangle(0.0, 0.0, 595.276, 841.89)`.
 */
public fun pdfRectangle(lowerLeftX: Double, lowerLeftY: Double, upperRightX: Double, upperRightY: Double): PdfArray =
    PdfArray(
        listOf(
            PdfReal(lowerLeftX),
            PdfReal(lowerLeftY),
            PdfReal(upperRightX),
            PdfReal(upperRightY),
        ),
    )

/**
 * Checks that [value] is what §7.9.5 calls a rectangle: four numbers, of either
 * numeric type — "Wherever a real number is expected, an integer may be used
 * instead" (§7.3.3), so a coordinate may be written as either.
 */
private fun requireRectangle(value: PdfArray) {
    require(
        value.items.size == RECTANGLE_COORDINATES &&
            value.items.all { it is PdfInt || it is PdfReal },
    ) {
        "a rectangle shall be written as an array of four numbers giving the coordinates of a pair " +
            "of diagonally opposite corners (ISO 32000-2:2020 §7.9.5)"
    }
}

/** `[llx lly urx ury]` — the four coordinates §7.9.5 gives a rectangle. */
private const val RECTANGLE_COORDINATES: Int = 4
