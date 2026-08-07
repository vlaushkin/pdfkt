/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
@file:OptIn(ExperimentalJsExport::class)

package io.pdfkt.api

import io.pdfkt.core.block.ImageFit
import io.pdfkt.core.block.ListMarkerStyle
import io.pdfkt.core.block.TextAlign
import io.pdfkt.core.doc.BandPages
import io.pdfkt.core.doc.Clock
import io.pdfkt.core.model.Rgb
import io.pdfkt.core.obj.PdfDate

// No clause applies: the JavaScript face of the DSL is this library's own
// design. It exists because plain JavaScript cannot reach the Kotlin DSL —
// un-exported names are mangled, value classes and Long do not cross — so
// every parameter here is a String, a Double, an Int, a Boolean or a
// ByteArray, and styles chain onto the last-written run, the way
// JavaScript document builders usually read:
//
//     const doc = new PdfDocument();
//     doc.heading(1, "Q4 2026 summary");
//     doc.paragraph(p => p.text("Revenue: ").bold().text("up 12%"));
//     const bytes = doc.toBytes();

private fun alignOf(name: String?): TextAlign = when (name) {
    null, "left" -> TextAlign.Left
    "center" -> TextAlign.Center
    "right" -> TextAlign.Right
    "justify" -> TextAlign.Justify
    else -> throw IllegalArgumentException(
        "alignment is one of left, center, right, justify; got \"$name\"",
    )
}

private fun bandPagesOf(name: String?): BandPages = when (name) {
    null, "all" -> BandPages.All
    "firstOnly" -> BandPages.FirstOnly
    "exceptFirst" -> BandPages.ExceptFirst
    else -> throw IllegalArgumentException(
        "pages is one of all, firstOnly, exceptFirst; got \"$name\"",
    )
}

private fun markerOf(name: String?, fallback: ListMarkerStyle): ListMarkerStyle = when (name) {
    null -> fallback
    "bullet" -> ListMarkerStyle.Bullet
    "circle" -> ListMarkerStyle.Circle
    "square" -> ListMarkerStyle.Square
    "dash" -> ListMarkerStyle.Dash
    "decimal" -> ListMarkerStyle.Decimal
    "lowerAlpha" -> ListMarkerStyle.LowerAlpha
    "upperAlpha" -> ListMarkerStyle.UpperAlpha
    "lowerRoman" -> ListMarkerStyle.LowerRoman
    "upperRoman" -> ListMarkerStyle.UpperRoman
    else -> throw IllegalArgumentException("unknown list marker \"$name\"")
}

private fun fitOf(name: String?): ImageFit = when (name) {
    null, "contain" -> ImageFit.Contain
    "stretch" -> ImageFit.Stretch
    else -> throw IllegalArgumentException("fit is one of contain, stretch; got \"$name\"")
}

/** One `text()` run and the styles chained after it. */
private class RunSpec(val value: String) {
    var bold = false
    var italic = false
    var code = false
    var strike = false
    var underline = false
    var color: Int? = null
    var fontSizePoints: Double? = null

    val styled: Boolean
        get() = bold ||
            italic ||
            code ||
            strike ||
            underline ||
            color != null ||
            fontSizePoints != null

    fun applyTo(scope: InlineScope) {
        if (!styled) {
            scope.text(value)
            return
        }
        scope.text(value) {
            if (this@RunSpec.bold) bold = true
            if (this@RunSpec.italic) italic = true
            if (this@RunSpec.code) code = true
            if (this@RunSpec.strike) strike = true
            if (this@RunSpec.underline) underline = true
            this@RunSpec.color?.let { color = Rgb.of(it) }
            this@RunSpec.fontSizePoints?.let { fontSize = it.pt }
        }
    }
}

/**
 * A paragraph under construction: `text()` writes a run, and the style
 * calls that follow restyle that run —
 * `p.text("warning").bold().color(0xCC0000)`.
 */
@JsExport
public class PdfParagraph internal constructor() {
    private val ops = mutableListOf<(InlineScope) -> Unit>()
    private var lastRun: RunSpec? = null

    /** One run of text; the calls chained after it style this run. */
    public fun text(value: String): PdfParagraph {
        val run = RunSpec(value)
        lastRun = run
        ops += { scope -> run.applyTo(scope) }
        return this
    }

    /** A hyperlink around [text]. */
    public fun link(href: String, text: String): PdfParagraph {
        lastRun = null
        ops += { scope -> scope.link(href, text) }
        return this
    }

    /** A line break; a soft one keeps the justification unit. */
    public fun br(soft: Boolean = false): PdfParagraph {
        lastRun = null
        ops += { scope -> scope.br(soft) }
        return this
    }

    public fun bold(): PdfParagraph = restyle { bold = true }
    public fun italic(): PdfParagraph = restyle { italic = true }
    public fun code(): PdfParagraph = restyle { code = true }
    public fun strike(): PdfParagraph = restyle { strike = true }
    public fun underline(): PdfParagraph = restyle { underline = true }
    public fun color(rgb: Int): PdfParagraph = restyle { color = rgb }
    public fun fontSize(points: Double): PdfParagraph = restyle { fontSizePoints = points }

    private fun restyle(apply: RunSpec.() -> Unit): PdfParagraph {
        val run = lastRun ?: throw IllegalStateException(
            "a style call must follow the text() run it styles",
        )
        run.apply()
        return this
    }

    internal fun applyTo(scope: InlineScope) {
        ops.forEach { it(scope) }
    }
}

/** A list under construction: plain items, or paragraph items. */
@JsExport
public class PdfList internal constructor() {
    private val items = mutableListOf<(ListScope) -> Unit>()

    internal fun applyTo(scope: ListScope) {
        items.forEach { it(scope) }
    }

    public fun item(text: String): PdfList {
        items += { scope -> scope.item(text) }
        return this
    }

    public fun richItem(build: (PdfParagraph) -> Unit): PdfList {
        val p = PdfParagraph().also(build)
        items += { scope -> scope.item { paragraph { p.applyTo(this) } } }
        return this
    }
}

/** A table row under construction. */
@JsExport
public class PdfRow internal constructor() {
    private val cells = mutableListOf<(RowScope) -> Unit>()

    internal fun applyTo(scope: RowScope) {
        cells.forEach { it(scope) }
    }

    public fun cell(text: String, alignment: String = "left"): PdfRow {
        val align = alignOf(alignment)
        cells += { scope -> scope.cell(text, align = align) }
        return this
    }

    public fun richCell(build: (PdfParagraph) -> Unit): PdfRow {
        val p = PdfParagraph().also(build)
        cells += { scope -> scope.cell { paragraph { p.applyTo(this) } } }
        return this
    }
}

/** A table under construction: one optional header row, then rows. */
@JsExport
public class PdfTable internal constructor() {
    private val rows = mutableListOf<Pair<Boolean, PdfRow>>()

    internal fun applyTo(scope: TableScope) {
        rows.forEach { (isHeader, r) ->
            if (isHeader) {
                scope.header { r.applyTo(this) }
            } else {
                scope.row { r.applyTo(this) }
            }
        }
    }

    public fun headerRow(build: (PdfRow) -> Unit): PdfTable {
        rows += true to PdfRow().also(build)
        return this
    }

    public fun row(build: (PdfRow) -> Unit): PdfTable {
        rows += false to PdfRow().also(build)
        return this
    }
}

/**
 * A PDF document for a JavaScript caller, paired with the Kotlin
 * `document { }` and the Swift `Document { }`.
 *
 * The clock defaults to construction time; pass [pinnedAtEpochSeconds]
 * (and [timeZoneOffsetMinutes]) for bytes reproducible across runs.
 */
@JsExport
public class PdfDocument(pinnedAtEpochSeconds: Double = Double.NaN, timeZoneOffsetMinutes: Int = 0) {
    private val builders = mutableListOf<DocumentScope.() -> Unit>()
    private val clockSeconds: Double =
        if (pinnedAtEpochSeconds.isNaN()) currentEpochSeconds() else pinnedAtEpochSeconds
    private val clockOffset: Int = timeZoneOffsetMinutes

    public fun pageSizeA4(): PdfDocument = configure { pageSize = PageSize.A4 }
    public fun pageSizeA5(): PdfDocument = configure { pageSize = PageSize.A5 }
    public fun pageSizeLetter(): PdfDocument = configure { pageSize = PageSize.Letter }
    public fun pageSizeLegal(): PdfDocument = configure { pageSize = PageSize.Legal }

    public fun landscape(): PdfDocument = configure { landscape() }

    public fun margins(top: Double, right: Double, bottom: Double, left: Double): PdfDocument =
        configure { margins = Margins.Fixed(top.pt, right.pt, bottom.pt, left.pt) }

    public fun metadata(title: String? = null, author: String? = null): PdfDocument =
        configure { metadata = metadata.copy(title = title, author = author) }

    public fun paragraph(build: (PdfParagraph) -> Unit): PdfDocument {
        val p = PdfParagraph().also(build)
        return configure { paragraph { p.applyTo(this) } }
    }

    public fun simpleParagraph(text: String, alignment: String = "left"): PdfDocument {
        val align = alignOf(alignment)
        return configure { paragraph(text, align) }
    }

    public fun heading(level: Int, text: String): PdfDocument = configure { heading(level, text) }

    public fun richHeading(level: Int, build: (PdfParagraph) -> Unit): PdfDocument {
        val p = PdfParagraph().also(build)
        return configure { heading(level) { p.applyTo(this) } }
    }

    public fun bulletList(marker: String? = null, build: (PdfList) -> Unit): PdfDocument {
        val l = PdfList().also(build)
        val style = markerOf(marker, ListMarkerStyle.Bullet)
        return configure {
            bulletList(markerStyle = style) { l.applyTo(this) }
        }
    }

    public fun numberedList(startNumber: Int = 1, marker: String? = null, build: (PdfList) -> Unit): PdfDocument {
        val l = PdfList().also(build)
        val style = markerOf(marker, ListMarkerStyle.Decimal)
        return configure {
            numberedList(startNumber = startNumber, markerStyle = style) { l.applyTo(this) }
        }
    }

    public fun table(build: (PdfTable) -> Unit): PdfDocument {
        val t = PdfTable().also(build)
        return configure { table { t.applyTo(this) } }
    }

    public fun codeBlock(text: String): PdfDocument = configure { codeBlock(text) }

    public fun blockquote(build: (PdfParagraph) -> Unit): PdfDocument {
        val p = PdfParagraph().also(build)
        return configure { blockquote { paragraph { p.applyTo(this) } } }
    }

    public fun horizontalRule(): PdfDocument = configure { hr() }

    /** A picture in the flow; the bytes decide the format (JPEG or PNG). */
    public fun image(bytes: ByteArray, fit: String = "contain", rotationDegrees: Int = 0): PdfDocument {
        val mode = fitOf(fit)
        return configure { image(bytes, fit = mode, rotationDegrees = rotationDegrees) }
    }

    public fun pageBreak(): PdfDocument = configure { pageBreak() }

    public fun header(pages: String = "all", build: (PdfParagraph) -> Unit): PdfDocument {
        val p = PdfParagraph().also(build)
        val selection = bandPagesOf(pages)
        return configure {
            header(pages = selection) { paragraph { p.applyTo(this) } }
        }
    }

    public fun footer(pages: String = "all", build: (PdfParagraph) -> Unit): PdfDocument {
        val p = PdfParagraph().also(build)
        val selection = bandPagesOf(pages)
        return configure {
            footer(pages = selection) { paragraph { p.applyTo(this) } }
        }
    }

    /** "3 / 12" in the running foot — the page and the count, no words — on the pages [pages] names. */
    public fun footerPageOfPages(
        pages: String = "all",
        alignment: String = "right",
        separator: String = " / ",
    ): PdfDocument {
        val selection = bandPagesOf(pages)
        val align = alignOf(alignment)
        return configure {
            footer(pages = selection) { paragraph(pageOfPages(separator), align = align) }
        }
    }

    private fun configure(op: DocumentScope.() -> Unit): PdfDocument {
        builders += op
        return this
    }

    /** Render the PDF. The engine's no-silent-loss refusals throw. */
    public fun toBytes(): ByteArray {
        val seconds = clockSeconds
        val offset = clockOffset
        val doc = document {
            clock = Clock {
                PdfDate.ofEpochSeconds(seconds.toLong(), offsetMinutes = offset)
            }
            builders.forEach { it(this) }
        }
        return doc.toByteArray()
    }
}

private fun currentEpochSeconds(): Double = js("Date.now() / 1000").unsafeCast<Double>()
