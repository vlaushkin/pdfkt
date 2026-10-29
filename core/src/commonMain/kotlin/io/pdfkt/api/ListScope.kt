/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.api

import io.pdfkt.core.block.ListBlock
import io.pdfkt.core.block.ListItem
import io.pdfkt.core.block.ListMarkerStyle
import io.pdfkt.core.block.ParagraphBlock
import io.pdfkt.core.block.TextAlign
import io.pdfkt.core.block.TextRun
import io.pdfkt.core.model.Pt

// No clause applies: the DSL is this library's own design. What a marker reads and where an item sits are the engine's
// (io.pdfkt.core.block.ListBlock, and marker text in io.pdfkt.core.resolve) —
// this file only collects what the caller wrote.

/**
 * The items of a `bulletList { }` or `numberedList { }`.
 *
 * ```kotlin
 * bulletList {
 *     item("Revenue: +12%")
 *     item {
 *         +"Departments"
 *         bulletList { item("Sales — 30%") }
 *     }
 * }
 * ```
 */
@PdfktDsl
public class ListScope internal constructor() {
    private val items = mutableListOf<ListItem>()

    /** Append an item of one plain-text paragraph. */
    public fun item(text: String) {
        items.add(ListItem(listOf(ParagraphBlock(content = listOf(TextRun(text))))))
    }

    /**
     * Append an item built from inline content and blocks.
     *
     * An item holds blocks, so it may carry several paragraphs, a nested list or
     * a table; inline content written straight into it becomes its leading
     * paragraph.
     */
    public fun item(block: ListItemScope.() -> Unit) {
        val scope = ListItemScope()
        scope.block()
        items.add(ListItem(scope.build()))
    }

    /** Append an already-built item — the entry point a converter uses. */
    public fun add(item: ListItem) {
        items.add(item)
    }

    internal fun build(): List<ListItem> = items.toList()
}

/**
 * One item's content. Every block builder of [BlockSink] applies, which is what
 * makes a list of tables or a list of lists ordinary rather than a special case.
 */
public class ListItemScope internal constructor() : BlockContainerScope(TextAlign.Start)

/**
 * The unordered list a `bulletList { }` describes.
 *
 * The marker style has to be one of the unordered ones. An ordered style here
 * would be a list that counts without having been asked to count from anywhere,
 * and silently drawing bullets instead would be the library deciding what the
 * caller meant; [numberedList] is where a counted list is written.
 */
internal fun buildBulletList(
    markerStyle: ListMarkerStyle,
    indentPerLevel: Pt,
    block: ListScope.() -> Unit,
): ListBlock {
    require(!markerStyle.isOrdered) {
        "$markerStyle counts its items, which bulletList does not do: write numberedList(markerStyle = " +
            "$markerStyle) for a counted list"
    }
    return ListBlock(
        items = collectItems(block),
        ordered = false,
        markerStyle = markerStyle,
        indentPerLevel = indentPerLevel,
    )
}

/**
 * The ordered list a `numberedList { }` describes.
 *
 * Counted from [startNumber], which is a reader's number and so starts at 1: a
 * list numbered from zero would draw a marker for an item before its first one.
 */
internal fun buildNumberedList(
    startNumber: Int,
    markerStyle: ListMarkerStyle,
    indentPerLevel: Pt,
    block: ListScope.() -> Unit,
): ListBlock {
    require(markerStyle.isOrdered) {
        "$markerStyle marks its items without counting them, which numberedList does not do: write " +
            "bulletList(markerStyle = $markerStyle) for an unordered list"
    }
    require(startNumber >= FIRST_ITEM) {
        "a list is numbered from $FIRST_ITEM as a reader counts; got $startNumber"
    }
    return ListBlock(
        items = collectItems(block),
        ordered = true,
        startNumber = startNumber,
        markerStyle = markerStyle,
        indentPerLevel = indentPerLevel,
    )
}

/**
 * Whether this style counts the items it marks.
 *
 * The division is the one the two builders exist for: `1.` `a.` `i.` say which
 * item this is, and a bullet says only that there is one.
 */
public val ListMarkerStyle.isOrdered: Boolean get() = when (this) {
    ListMarkerStyle.Bullet, ListMarkerStyle.Dash, ListMarkerStyle.Circle, ListMarkerStyle.Square -> false
    ListMarkerStyle.Decimal,
    ListMarkerStyle.LowerAlpha,
    ListMarkerStyle.UpperAlpha,
    ListMarkerStyle.LowerRoman,
    ListMarkerStyle.UpperRoman,
    -> true
}

private fun collectItems(block: ListScope.() -> Unit): List<ListItem> {
    val scope = ListScope()
    scope.block()
    return scope.build()
}

private const val FIRST_ITEM: Int = 1
