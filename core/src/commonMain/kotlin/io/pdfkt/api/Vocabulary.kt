/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.api

// No clause applies to the list itself: which types the API surface and the
// engine share is this library's design. The clauses cited below are the ones the individual types
// carry, in `io.pdfkt.core.*`, where they are defined.

/*
 * The shared vocabulary, under the API surface's own name.
 *
 * The model tier owns these types: this package holds no `Block`, `InlineRun`,
 * `Theme`, `Pt` or colour copies of its own — only builders,
 * `PageSize` and `Margins`.
 *
 * Owning them is one thing and *naming* them is another. Keeping the types in
 * the model tier's packages alone would leave a caller importing
 * `io.pdfkt.api` for `document { }` and then `io.pdfkt.core.model` for
 * the `Rgb` a heading is coloured with, `io.pdfkt.core.block` for the
 * `TextAlign` a paragraph is set with, `io.pdfkt.core.doc` for the clock the
 * document dates itself from — four packages to fill in one document. This
 * package is the API surface; a type that surface names should be reachable
 * from the package that names it.
 *
 * So: every model-tier type this package names in a public signature is aliased
 * here, together with the types a caller must name to *build* one — a
 * [Theme] is set in a [FontFamily], a [FontFamily] made of a [FontRef], a
 * [FontRefStandard14] of a [Standard14Name], an [EmojiPolicyRasterized] of an
 * [EmojiRasterizer] handing back [RasterGlyph]s, a [Clock] of a [PdfDate].
 *
 * A typealias and not a wrapper, deliberately. There is exactly one [Pt] in a
 * document's life: the one the DSL took, the one the fragmenter measured with,
 * the one the emitter wrote. A wrapper would put a conversion between them, and
 * a conversion between two identical types is a twin type by another name.
 * Nothing here is convertible *to* the
 * model tier's — it *is* the model tier's.
 *
 * Where the line falls: this file names the types a caller writes down —
 * including the block types themselves, because `add(Block)` is
 * the entry point of a *converter* — a CommonMark or HTML tree walked into
 * blocks — and a converter writes down the block it builds, not just the
 * interface it satisfies. So every variant of [Block] and of [InlineRun] is
 * named here too, [ParagraphBlock] and [TextRun] through [PageBreakBlock], with
 * [ImageRef] and [imageFromBytes] for the one variant that cannot be built out
 * of the other aliases alone.
 *
 * It still does not follow them inward: a caller who takes a [DocumentSpec]
 * apart is working below the API surface and imports `io.pdfkt.core.*`
 * directly, which — the DSL and the engine being one artifact — is already on
 * their compile classpath.
 *
 * **One thing a typealias cannot carry: a nested classifier.** `TableColumns` is
 * aliased below, but `TableColumns.Equal` does not resolve through the alias —
 * Kotlin looks a nested class up on the aliased *name*, finds nothing, and
 * stops. (What does travel: constructors, companion members such as
 * `Theme.Default` and `Rgb.BLACK`, and enum entries such as `TextAlign.Center`.)
 * The variants of the sealed types a caller has to name are therefore aliased
 * one by one, under the qualified name written without its dot —
 * `TableColumns.Equal` is [TableColumnsEqual]. The rule is mechanical on
 * purpose: there is one way to guess the name and it is right, and a reader who
 * prefers the nested spelling gets it by importing `io.pdfkt.core.block` as
 * before.
 */

// -------------------------------------------------------------- geometry

/**
 * A length on a page, in the 1 ⁄ 72 inch units of ISO 32000-2:2020 §8.3.2.3
 * "User space" — what a margin, an indent and a font size are measured in.
 */
public typealias Pt = io.pdfkt.core.model.Pt

/** A whole number of points, so that a margin reads `36.pt`. */
public val Int.pt: Pt get() = Pt(this.toFloat())

/**
 * The same from a `Float`, which is what a caller who computed a length out of a
 * screen dimension already holds.
 */
public val Float.pt: Pt get() = Pt(this)

/** The same for a length written as a fraction — `10.5.pt`. */
public val Double.pt: Pt get() = Pt(this.toFloat())

/** How wide and how tall a page is — what [PageSize.toSize] hands the engine (§7.7.3.3, Table 31). */
public typealias Size = io.pdfkt.core.model.Size

/** What is kept clear on the four sides of a page — what [Margins.toInsets] hands the engine. */
public typealias Insets = io.pdfkt.core.model.Insets

/** A colour in DeviceRGB (§8.6.4.3), which is what `rg` and `RG` take. */
public typealias Rgb = io.pdfkt.core.model.Rgb

// ------------------------------------------------------- the block tree

/**
 * One thing in the flow of a document: a paragraph, a heading, a list, a table,
 * a rule.
 *
 * The type [BlockSink.add] takes, which is the entry point for a converter — a
 * CommonMark AST walked into blocks — rather than for the DSL, and the reason
 * this alias exists: without it, the one function a converter calls names a type
 * from a package the caller had no other reason to import.
 */
public typealias Block = io.pdfkt.core.block.Block

/** A run of inline content set as one paragraph, aligned and indented as it says. */
public typealias ParagraphBlock = io.pdfkt.core.block.ParagraphBlock

/** A heading of a level 1..[HeadingBlock.MAX_LEVEL], set in the theme's family for that level. */
public typealias HeadingBlock = io.pdfkt.core.block.HeadingBlock

/** A list, bulleted or numbered, of [ListItem]s that may hold lists of their own. */
public typealias ListBlock = io.pdfkt.core.block.ListBlock

/** A table: its [TableColumns], its rows of [TableCell]s, and the header repeated across pages. */
public typealias TableBlock = io.pdfkt.core.block.TableBlock

/** Pre-formatted text, set in the theme's code family, its line breaks kept as written. */
public typealias CodeBlock = io.pdfkt.core.block.CodeBlock

/** Blocks indented behind a vertical bar — what a converter makes of a block quotation. */
public typealias BlockquoteBlock = io.pdfkt.core.block.BlockquoteBlock

/** A rule across the column, of a thickness and colour, or the theme's if it states none. */
public typealias HorizontalRuleBlock = io.pdfkt.core.block.HorizontalRuleBlock

/**
 * A picture in the flow: an [ImageRef], how it fills the space it is given, and
 * the angle it is turned by — what [DocumentScope.image] adds, and what a
 * converter adds for a turn that is not a quarter of a circle.
 */
public typealias ImageBlock = io.pdfkt.core.block.ImageBlock

/** The end of a page, wherever the flow has got to — what [DocumentScope.pageBreak] adds. */
public typealias PageBreakBlock = io.pdfkt.core.block.PageBreakBlock

/** One stretch of inline content inside a paragraph or a heading — what [InlineRunSink.add] takes. */
public typealias InlineRun = io.pdfkt.core.block.InlineRun

/** Characters, and nothing else said about them — the leaf every other run is built over. */
public typealias TextRun = io.pdfkt.core.block.TextRun

/** Runs set apart: bold, italic, code, underlined, struck, coloured, or of a size of their own. */
public typealias StyledRun = io.pdfkt.core.block.StyledRun

/** Runs that are a link — the URI action of §12.6.4.8 the emitter writes an annotation for. */
public typealias LinkRun = io.pdfkt.core.block.LinkRun

/** A break inside a paragraph: the line ends here, the paragraph does not. */
public typealias BreakRun = io.pdfkt.core.block.BreakRun

/** Where a line sits in its column: `Left`, `Center`, `Right` or `Justify`. */
public typealias TextAlign = io.pdfkt.core.block.TextAlign

/** Where a table cell's content sits in a row taller than itself. */
public typealias VerticalAlign = io.pdfkt.core.block.VerticalAlign

/** One entry of a list — the blocks it holds, which may be another list. */
public typealias ListItem = io.pdfkt.core.block.ListItem

/** What a list writes in front of each item: a bullet, a number, a letter, a numeral. */
public typealias ListMarkerStyle = io.pdfkt.core.block.ListMarkerStyle

/** One cell of a table — what [RowScope.add] takes. */
public typealias TableCell = io.pdfkt.core.block.TableCell

/** How a table's columns are measured: equal, fractional, or fixed widths. */
public typealias TableColumns = io.pdfkt.core.block.TableColumns

/** As many columns of one width as the table has — `TableColumns.Equal`. */
public typealias TableColumnsEqual = io.pdfkt.core.block.TableColumns.Equal

/** Columns sharing the width in the given proportions — `TableColumns.Fractional`. */
public typealias TableColumnsFractional = io.pdfkt.core.block.TableColumns.Fractional

/** Columns of stated widths — `TableColumns.Fixed`. */
public typealias TableColumnsFixed = io.pdfkt.core.block.TableColumns.Fixed

/** Which rules a table draws, how thick and in what colour. */
public typealias TableBorderStyle = io.pdfkt.core.block.TableBorderStyle

/**
 * How an image fills the space it is given: `Contain` keeps its aspect ratio,
 * `Stretch` does not — what [DocumentScope.image] takes.
 */
public typealias ImageFit = io.pdfkt.core.block.ImageFit

/**
 * One picture within one document: its samples, its size, its colour space —
 * the image XObject of §8.9.5.1, Table 87, before anything is placed. What an
 * [ImageBlock] holds, and the only ingredient of a block a caller cannot write
 * down out of the aliases above; [imageFromBytes] is how one is made.
 */
public typealias ImageRef = io.pdfkt.core.image.ImageRef

/**
 * The reference to a picture supplied as the bytes of a file — a JPEG embedded
 * as it arrived (§7.4.8 "DCTDecode filter") or a PNG decoded and re-encoded
 * (§7.4.4 "FlateDecode filter"), chosen by what the first bytes say.
 *
 * A function and not a typealias: Kotlin aliases types, not functions, and a
 * converter that builds an [ImageBlock] has to name this to have anything to
 * put in it. It forwards, and does nothing else — the same call
 * [DocumentScope.image] makes for the bytes it is handed.
 *
 * @param id what the picture is called within one document ([ImageRef.id]); one
 *   id is one image XObject, however many blocks refer to it.
 * @param bytes the file, whole and from its first byte.
 * @throws IllegalArgumentException if [bytes] begin as neither format does.
 */
public fun imageFromBytes(id: String, bytes: ByteArray): ImageRef = io.pdfkt.core.image.imageFromBytes(id, bytes)

// ----------------------------------------------------------------- fonts

/**
 * A font program the document may draw with: one of the standard 14 of §9.6.2.2,
 * or bytes the caller supplies.
 */
public typealias FontRef = io.pdfkt.core.font.FontRef

/** One of the standard 14 fonts, embedded in nothing — `FontRef.Standard14`. */
public typealias FontRefStandard14 = io.pdfkt.core.font.FontRef.Standard14

/**
 * A font program the caller supplies as bytes, called [FontRefSupplied.id]
 * within the document — `FontRef.Supplied`, and the way a document is set in an
 * alphabet the standard 14 do not cover.
 */
public typealias FontRefSupplied = io.pdfkt.core.font.FontRef.Supplied

/** Which of the standard 14 fonts §9.6.2.2 lists a [FontRefStandard14] means. */
public typealias Standard14Name = io.pdfkt.core.font.Standard14Name

/** The four faces a document is set in — regular, bold, italic, bold italic. */
public typealias FontFamily = io.pdfkt.core.style.FontFamily

// ----------------------------------------------------------------- emoji

/** What a document does with an emoji — [Theme.emojiPolicy]. */
public typealias EmojiPolicy = io.pdfkt.core.style.EmojiPolicy

/** Leave the emoji in the text and draw it with the run's own font — `EmojiPolicy.None`. */
public typealias EmojiPolicyNone = io.pdfkt.core.style.EmojiPolicy.None

/** Remove the emoji from the text, whole — `EmojiPolicy.Strip`. */
public typealias EmojiPolicyStrip = io.pdfkt.core.style.EmojiPolicy.Strip

/** Write the emoji out as its code points, `[U+1F44B]` — `EmojiPolicy.Placeholder`. */
public typealias EmojiPolicyPlaceholder = io.pdfkt.core.style.EmojiPolicy.Placeholder

/** Draw the emoji in colour out of a supplied colour-bitmap font — `EmojiPolicy.EmbeddedColorFont`. */
public typealias EmojiPolicyEmbeddedColorFont = io.pdfkt.core.style.EmojiPolicy.EmbeddedColorFont

/** Draw the emoji in colour out of the caller's own rasterizer — `EmojiPolicy.Rasterized`. */
public typealias EmojiPolicyRasterized = io.pdfkt.core.style.EmojiPolicy.Rasterized

/**
 * What draws one emoji for [EmojiPolicyRasterized]: the caller's own rasterizer,
 * asked once per distinct sequence per document.
 */
public typealias EmojiRasterizer = io.pdfkt.core.font.EmojiRasterizer

/** One emoji as a rasterizer drew it — the PNG and its metrics, in pixels. */
public typealias RasterGlyph = io.pdfkt.core.font.RasterGlyph

// -------------------------------------------------------------- the seam

/**
 * How a document is set — fonts, sizes, spacing and colours every block falls
 * back to. [DocumentScope.theme].
 */
public typealias Theme = io.pdfkt.core.style.Theme

/** What a document says about itself: the entries of §14.3.3, Table 349, a caller supplies. */
public typealias Metadata = io.pdfkt.core.doc.Metadata

/**
 * Which pages a running head or foot is drawn on — `All`, `FirstOnly` for a
 * cover, `ExceptFirst` for everything after one. What [DocumentScope.header]
 * and [DocumentScope.footer] take as their `pages`.
 *
 * A band that says one thing on the first page and another after it is not a
 * fourth case here but the overload taking two lines: the rule stays `All` and
 * the choice is made per page, which is what a content function is for
 * (`BandPages` in `:core`).
 */
public typealias BandPages = io.pdfkt.core.doc.BandPages

/**
 * Which page of how many a band is being drawn on — what the [DocumentScope.header]
 * and [DocumentScope.footer] overloads taking a function are handed, and what
 * makes "Page 3 of 12" a line the DSL can write.
 */
public typealias PageNumbering = io.pdfkt.core.doc.PageNumbering

/**
 * Where `/CreationDate` and `/ModDate` come from (§14.3.3), passed in by the
 * caller because reading the time is a platform capability and a defaulted clock
 * is how a library ships one date to everybody.
 */
public typealias Clock = io.pdfkt.core.doc.Clock

/** A moment, as §7.9.4 "Dates" writes one — what a [Clock] hands back. */
public typealias PdfDate = io.pdfkt.core.obj.PdfDate

/** The one thing the engine renders, which [Document.toDocumentSpec] builds. */
public typealias DocumentSpec = io.pdfkt.core.doc.DocumentSpec
