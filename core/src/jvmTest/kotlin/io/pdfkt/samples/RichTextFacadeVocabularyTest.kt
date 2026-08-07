/*
 * Copyright (c) 2026 pdfkt contributors.
 * Licensed under the Apache License, Version 2.0 — see LICENSE.
 *
 * pdfkt implements PDF generation from the published specifications
 * (ISO 32000-2 for PDF, OpenType and Adobe AFM for fonts). It is not
 * derived from any other PDF library.
 */
package io.pdfkt.samples

import io.pdfkt.api.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlin.io.path.deleteIfExists
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// ISO 32000-2:2020 §7.9.4 "Dates" — the one string this test reads out of the
// file's own bytes. Nothing else here implements a clause; what is under test is
// the facade's API surface.

/**
 * A rich text document written the way a consumer of the published artifact
 * writes one: with `io.pdfkt.api` as the only pdfkt package on the file's
 * import list.
 *
 * **The single import above is the assertion.** This file lives outside
 * `io.pdfkt.api`, so nothing in that package reaches it by being a
 * neighbour; every pdfkt name below — `Block`, `InlineRun`, `TextAlign`,
 * `VerticalAlign`, `ListMarkerStyle`, `ListItem`, `TableCell`, `TableColumns`,
 * `TableColumnsEqual`, `TableBorderStyle`, `Rgb`, `Pt`, `.pt`, `Size`,
 * `Insets`, `Theme`, `Metadata`, `Clock`, `PdfDate`, `DocumentSpec`,
 * `FontFamily`, `FontRef`, `FontRefSupplied`, `Standard14Name`, `EmojiPolicy`,
 * `EmojiPolicyPlaceholder`, and every block
 * and run a converter builds by name: `ParagraphBlock`, `HeadingBlock`,
 * `ListBlock`, `TableBlock`, `CodeBlock`, `BlockquoteBlock`,
 * `HorizontalRuleBlock`, `ImageBlock`, `PageBreakBlock`, `TextRun`,
 * `StyledRun`, `LinkRun`, `BreakRun`, `ImageRef`, `imageFromBytes` — resolves
 * through that one line or does not resolve at all. Without `Vocabulary.kt` this
 * file would need five more imports, of two modules, to say the same things; if a
 * model-tier type ever reappears in this API surface's signatures without a
 * name of its own here, this file stops compiling, which is a louder failure
 * than a review comment.
 *
 * The check is by use, not by reflection: the names are written down in
 * declarations with explicit types, the document is rendered, and the file is
 * read back. Asking a `KClass` whether a member exists would pass on a facade
 * nobody can actually write against.
 *
 * Nothing from this module's *test* sources is used either, although the star
 * import would reach it — the font lookup, the picture and the tool runner
 * below are local for that reason. What a consumer gets is `commonMain`, and
 * that is what is being measured. (The DSL being `commonMain` of `:core`
 * changes nothing here: the names still have to be imported, and the imports
 * are still one line.)
 */
class RichTextFacadeVocabularyTest {

    /** The system's DejaVu Sans: the document has Cyrillic in it, which no standard-14 font draws. */
    private val sans: ByteArray = systemFont("DejaVuSans.ttf")

    @Test
    @DisplayName("A document needs no import but io.pdfkt.api")
    fun aDocumentNeedsNoImportButTheRichTextFacade() {
        // Every one of these is a `:core` type the DSL names, declared here with
        // an explicit type so that the alias — and not inference — is what has to
        // resolve.
        val pinnedClock: Clock = Clock { PdfDate.ofEpochSeconds(PINNED_EPOCH_SECONDS, offsetMinutes = OFFSET_MINUTES) }
        val body: FontRef = FontRefSupplied(sans, id = FONT_ID)
        val mono: FontFamily = FontFamily.COURIER
        val monoFace: Standard14Name = Standard14Name.COURIER
        val policy: EmojiPolicy = EmojiPolicyPlaceholder
        val ink: Rgb = Rgb.gray(0.1f)
        val indent: Pt = 18.pt

        var runsSeen: List<InlineRun> = emptyList()

        // `add(Block)` is the entry point a converter uses — a CommonMark AST
        // walked into blocks — and the reason `Block` carries a name in this
        // package. The blocks come from a document rather than from a
        // constructor, which is exactly what a converter has: values of a type
        // it must be able to say out loud.
        val carried: List<Block> = document { paragraph(CARRIED) }.blocks

        // The two containers that take an already-built value, so that
        // `ListItem` and `TableCell` are named as well.
        val item: ListItem = ListItem(carried)
        val cell: TableCell = TableCell(
            content = carried,
            align = TextAlign.Left,
            verticalAlign = VerticalAlign.Top,
            background = null,
        )

        // And the blocks a converter builds itself, each named. A converter does
        // not get its blocks out of a document — it walks a source tree and
        // *constructs* them, so it writes down the concrete type of every one:
        // a CommonMark paragraph is a `ParagraphBlock` of a `TextRun`, its
        // emphasis a `StyledRun`, its link a `LinkRun`, its hard break a
        // `BreakRun`, its thematic break a `HorizontalRuleBlock`. Each of these
        // names must be sayable without reaching into `io.pdfkt.core.block`;
        // the list below is that claim, checked.
        val logo: ImageRef = imageFromBytes(IMAGE_ID, pngBytes())
        val converted: List<Block> = listOf(
            HeadingBlock(level = CONVERTED_HEADING_LEVEL, content = listOf(TextRun(CONVERTED_HEADING))),
            ParagraphBlock(
                content = listOf(
                    TextRun(CONVERTED_TEXT),
                    StyledRun(children = listOf(TextRun(CONVERTED_EMPHASIS)), italic = true),
                    BreakRun(hard = true),
                    LinkRun(href = HREF, children = listOf(TextRun(CONVERTED_LINK))),
                ),
                align = TextAlign.Left,
            ),
            ListBlock(
                items = listOf(ListItem(listOf(ParagraphBlock(listOf(TextRun(CONVERTED_ITEM)))))),
                ordered = true,
            ),
            BlockquoteBlock(children = listOf(ParagraphBlock(listOf(TextRun(CONVERTED_QUOTE))))),
            // The code family is Courier, which is why this one block is the
            // only Latin line in a Cyrillic document: no standard-14 font draws
            // Cyrillic, and drawing it anyway is the loss the engine refuses.
            CodeBlock(text = CONVERTED_CODE),
            HorizontalRuleBlock(),
            TableBlock(
                columns = TableColumnsEqual(1),
                rows = listOf(listOf(TableCell(listOf(ParagraphBlock(listOf(TextRun(CONVERTED_CELL))))))),
            ),
            ImageBlock(image = logo, fit = ImageFit.Contain, rotation = QUARTER_TURN),
            PageBreakBlock,
        )

        val report = document {
            pageSize = PageSize.A4
            margins = Margins.Fixed(top = 36.pt, right = 36.pt, bottom = 36.pt, left = 36.pt)
            // Every family the document can reach is the supplied one, headings
            // included: the theme answers for all six levels, and a heading left
            // at the standard-14 default would stop the render on its first
            // Cyrillic letter — which is the no-silent-loss policy working, not a
            // failure of this sample.
            theme = Theme(
                bodyFamily = FontFamily.of(body),
                bodyFontSize = 11.pt,
                textColor = ink,
                headingFamilies = (1..HEADING_LEVELS).associateWith { FontFamily.of(body) },
                codeFamily = mono,
                emojiPolicy = policy,
            )
            metadata = Metadata(title = TITLE, author = AUTHOR)
            clock = pinnedClock

            heading(level = 1, HEADING)

            paragraph(align = TextAlign.Justify) {
                +INTRO
                bold(EMPHASIS)
                color(Rgb.BLACK, TAIL)
                link(HREF, LINK_TEXT)
                runsSeen = runs()
            }

            bulletList(markerStyle = ListMarkerStyle.Bullet, indentPerLevel = indent) {
                item(BULLET)
            }

            numberedList(startNumber = 1, markerStyle = ListMarkerStyle.Decimal, indentPerLevel = indent) {
                item { +NUMBERED }
            }

            blockquote(barColor = Rgb.gray(0.5f), barWidth = 3.pt, indentLeft = 12.pt) {
                +QUOTE
            }

            codeBlock(CODE, backgroundColor = Rgb.gray(0.95f), fontSize = 9.pt, padding = 6.pt)

            hr(thickness = 1.pt, color = Rgb.gray(0.7f), verticalSpacing = 8.pt)

            table {
                columns = TableColumnsEqual(TABLE_COLUMNS)
                borderStyle = TableBorderStyle.Default
                cellPadding = 4.pt
                repeatHeader = true
                header {
                    cell(HEAD_LEFT, align = TextAlign.Center, verticalAlign = VerticalAlign.Middle)
                    cell(HEAD_RIGHT, align = TextAlign.Center, backgroundColor = Rgb.gray(0.9f))
                }
                row {
                    cell { +CELL_LEFT }
                    cell(CELL_RIGHT, align = TextAlign.Right, verticalAlign = VerticalAlign.Bottom)
                }
            }

            // The converter's blocks and the two containers that take an
            // already-built value, written where every other block is written:
            // `document { }` hands back a document that is finished, so
            // everything the document carries is said inside the braces
            carried.forEach { block: Block -> add(block) }
            bulletList { add(item) }
            table {
                columns = TableColumnsEqual(1)
                row { add(cell) }
            }
            converted.forEach { block: Block -> add(block) }
        }

        // The seam, named from the same package.
        val spec: DocumentSpec = report.toDocumentSpec()
        val size: Size = report.pageSize.toSize()
        val insets: Insets = report.margins.toInsets()

        assertEquals(size, spec.pageSize, "the document is lowered onto the paper it was given")
        assertEquals(insets, spec.margins, "and inside the margins it was given")
        assertEquals(INLINE_RUNS, runsSeen.size, "the paragraph collected its four inline runs")
        assertEquals(monoFace, Standard14Name.COURIER, "the standard-14 names are reachable by name")

        // The converter's blocks reached the seam as the very objects it built:
        // `add` carries a block through, it does not rebuild one.
        assertTrue(
            spec.content.containsAll(converted),
            "the blocks a converter constructed did not all reach the DocumentSpec",
        )

        val bytes = report.toByteArray()
        assertPdfIsClean(bytes)

        // §7.9.4: the clock built through the alias is the clock the file is
        // dated from, down to the offset from UT.
        assertTrue(
            bytes.toLatin1().contains(PINNED_DATE),
            "the injected clock dates the document: expected $PINNED_DATE in /Info",
        )

        val text = textOf(bytes)
        listOf(HEADING, INTRO, EMPHASIS, BULLET, NUMBERED, QUOTE, HEAD_LEFT, CELL_RIGHT, CARRIED).forEach { written ->
            assertTrue(text.contains(written), "\"$written\" went in through the DSL and did not come back out")
        }
        listOf(
            CONVERTED_HEADING,
            CONVERTED_TEXT,
            CONVERTED_EMPHASIS,
            CONVERTED_LINK,
            CONVERTED_ITEM,
            CONVERTED_QUOTE,
            CONVERTED_CODE,
            CONVERTED_CELL,
        ).forEach { written ->
            assertTrue(text.contains(written), "\"$written\" was added as a block and did not come back out")
        }
        assertTrue(text.contains(WAVING_AS_PLACEHOLDER), "EmojiPolicy.Placeholder wrote the wave out as code points")
    }

    // ----------------------------------------------------------- the tools

    /**
     * The file, checked by a reader that knows nothing about this library —
     * permitted, and only ever as a tool.
     */
    private fun assertPdfIsClean(bytes: ByteArray) = withTempFile(bytes) { path ->
        val check = run("qpdf", "--check", path)
        assertEquals(0, check.first, "qpdf --check reported:\n${check.second}")
    }

    /** What pdftotext reads off the pages, all of them, as one string. */
    private fun textOf(bytes: ByteArray): String = withTempFile(bytes) { path ->
        val extracted = run("pdftotext", "-enc", "UTF-8", path, "-")
        assertEquals(0, extracted.first, "pdftotext reported:\n${extracted.second}")
        extracted.second
    }

    private fun <T> withTempFile(bytes: ByteArray, block: (String) -> T): T {
        val path = Files.createTempFile("pdfkt-richtext-vocabulary", ".pdf")
        return try {
            Files.write(path, bytes)
            block(path.toAbsolutePath().toString())
        } finally {
            path.deleteIfExists()
        }
    }

    /** [command] run to completion: its exit status and its output, errors folded in. */
    private fun run(vararg command: String): Pair<Int, String> {
        val process = ProcessBuilder(command.toList()).redirectErrorStream(true).start()
        val output = process.inputStream.readBytes().decodeToString()
        assertTrue(process.waitFor(TOOL_TIMEOUT_SECONDS, TimeUnit.SECONDS), "${command.first()} did not finish")
        return process.exitValue() to output
    }

    /**
     * A picture, made here rather than taken from this module's test sources —
     * the same reason the font lookup and the tool runner are local. It is a
     * PNG because `imageFromBytes` reads the format off the bytes, and what is
     * under test is that a caller can name the reference it hands back.
     */
    private fun pngBytes(): ByteArray {
        val picture = BufferedImage(IMAGE_WIDTH, IMAGE_HEIGHT, BufferedImage.TYPE_INT_RGB)
        val graphics = picture.createGraphics()
        try {
            graphics.color = Color.DARK_GRAY
            graphics.fillRect(0, 0, IMAGE_WIDTH, IMAGE_HEIGHT)
        } finally {
            graphics.dispose()
        }
        val encoded = ByteArrayOutputStream()
        assertTrue(ImageIO.write(picture, "png", encoded), "the JDK wrote no PNG, so there is no picture to add")
        return encoded.toByteArray()
    }

    /**
     * A font program from the system. A missing one fails rather than skips: a
     * run that quietly dropped the Cyrillic half of this document would report
     * the alphabet as checked when it was checked against nothing.
     */
    private fun systemFont(fileName: String): ByteArray {
        val path = FONT_DIRECTORIES.map { directory -> Path.of(directory, fileName) }
            .firstOrNull { candidate -> Files.isReadable(candidate) }
        requireNotNull(path) {
            "$fileName is not installed, so a document set in a supplied font cannot be written here. " +
                "Install fonts-dejavu-core; the paths tried were ${FONT_DIRECTORIES.joinToString(", ")}"
        }
        return Files.readAllBytes(path)
    }

    /**
     * The file's bytes as characters one for one, which is how a date written in
     * a literal string (§7.3.4) is looked for without decoding the document.
     */
    private fun ByteArray.toLatin1(): String = buildString(size) {
        this@toLatin1.forEach { byte -> append((byte.toInt() and BYTE_MASK).toChar()) }
    }

    private companion object {
        /** Where the font packages put their files; none of them is a fallback to a *different* font. */
        val FONT_DIRECTORIES: List<String> = listOfNotNull(
            System.getenv("PDFKT_TEST_FONT_DIR"),
            "/usr/share/fonts/truetype/dejavu",
            "/usr/share/fonts/dejavu",
            "/usr/share/fonts/TTF",
        )

        const val FONT_ID = "DejaVuSans"

        /** The heading levels a theme answers for, 1..6 (`HeadingBlock.MAX_LEVEL`). */
        const val HEADING_LEVELS = 6

        /** 2026-08-06T09:30:00 three hours east of UT, so a non-zero offset is what the file carries. */
        const val PINNED_EPOCH_SECONDS = 1_785_997_800L
        const val OFFSET_MINUTES = 180
        const val PINNED_DATE = "D:20260806093000+03'00"

        const val TITLE = "A document written from one import"
        const val AUTHOR = "pdfkt"
        const val HEADING = "Отчёт за квартал"
        const val INTRO = "Вводный абзац, "
        const val EMPHASIS = "выделенный полужирным"
        const val TAIL = " и хвост 👋 к нему"
        const val HREF = "https://example.invalid/report"
        const val LINK_TEXT = "ссылка"
        const val INLINE_RUNS = 4

        const val BULLET = "Маркированный пункт"
        const val NUMBERED = "Нумерованный пункт"
        const val QUOTE = "Цитата в рамке"
        const val CODE = "fun main() {\n    println(\"hi\")\n}"
        const val CARRIED = "Блок, добавленный через add"

        // What a converter builds and adds: one line per block type it names.
        const val CONVERTED_HEADING_LEVEL = 2
        const val CONVERTED_HEADING = "Заголовок из конвертера"
        const val CONVERTED_TEXT = "Абзац, собранный из узлов, "
        const val CONVERTED_EMPHASIS = "с выделением"
        const val CONVERTED_LINK = "и со ссылкой"
        const val CONVERTED_ITEM = "Пункт из конвертера"
        const val CONVERTED_QUOTE = "Цитата из конвертера"

        /** Latin, because the code family is Courier and no standard-14 font draws Cyrillic. */
        const val CONVERTED_CODE = "val converted = true"
        const val CONVERTED_CELL = "Ячейка из конвертера"

        /** A quarter turn counter-clockwise, the orientation a camera records. */
        const val QUARTER_TURN = 90.0f

        const val IMAGE_ID = "converted-logo"
        const val IMAGE_WIDTH = 48
        const val IMAGE_HEIGHT = 24

        const val TABLE_COLUMNS = 2
        const val HEAD_LEFT = "Показатель"
        const val HEAD_RIGHT = "Значение"
        const val CELL_LEFT = "Выручка"
        const val CELL_RIGHT = "12 процентов"

        /** What [EmojiPolicyPlaceholder] makes of the wave in [TAIL]. */
        const val WAVING_AS_PLACEHOLDER = "[U+1F44B]"

        const val TOOL_TIMEOUT_SECONDS = 60L
        const val BYTE_MASK = 0xFF
    }
}
