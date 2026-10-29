# pdfkt

PDF generation for Kotlin Multiplatform — JVM, Android, iOS, macOS, JS,
WasmJS.

Written against the published specifications (ISO 32000-2 for PDF,
OpenType and Adobe AFM for fonts) rather than by porting another
library's code. Licensed under Apache 2.0.

## Installing

One coordinate. The engine and the DSL are one artifact.

```kotlin
repositories {
    maven("https://jitpack.io")
}
dependencies {
    implementation("com.github.vlaushkin.pdfkt:pdfkt-core:0.0.7")
}
```

Until the artifact reaches Maven Central, JitPack builds it from the
release tags under its `com.github.<user>.<repo>` group. On Central it
will be `io.github.vlaushkin:pdfkt-core` — the group the build itself
publishes, which is also what `./gradlew publishToMavenLocal` puts in
`~/.m2` for local work.

The JVM and Android artifacts come from JitPack's Linux builders; the
Apple targets are consumed through the Swift package below instead.

## Hello world

One entry point, `document { }`, deliberately paired with the author's
sibling library **docxkt**: the same skeleton and the same verbs write a
DOCX, so a developer who knows one writes the other by changing the import
and the file extension.

```kotlin
import io.pdfkt.api.*
import kotlinx.io.asSink
import kotlinx.io.buffered
import java.io.File

val logo: ByteArray = File("logo.png").readBytes()

val doc = document {
    clock = Clock { PdfDate.ofEpochSeconds(System.currentTimeMillis() / 1000) }

    heading(1, "Q4 2026 summary")
    paragraph {
        text("Revenue: ") { bold = true }
        text("up 12% on the quarter")
    }
    table {
        header {
            cell("Metric")
            cell("Value")
        }
        row {
            cell("Revenue")
            cell("1 310")
        }
    }
    image(logo) // the bytes decide the format; fit and rotation are optional
}

File("report.pdf").outputStream().asSink().buffered().use { doc.writeTo(it) }
```

`Document` is opaque: `document { }` is the only way to get one, and once
the builder returns nothing can be added to it. `toByteArray()` is there
for a caller who wants the file in memory instead.

What pdfkt adds beyond the pairing: typed `Pt` and `Int.pt`,
`heading(level)`, `bulletList` / `numberedList`, `codeBlock` / `blockquote`
/ `hr`, image `fit` and `rotationDegrees`, headers and footers as block
scopes with typed page numbering, an injectable `Clock`, a typed `Theme`,
an emoji policy — and no silent loss: a glyph the font cannot draw is an
error naming the character, never a blank page.

`PageSize.FitContent(width)` (Swift `.fitContent(width:)`) is paper of that
width and as tall as the section's content plus its margins — a photo and a
caption in whatever font the caption needs, on one page, without measuring
the text in the application. Each `pageBreak()` in such a section starts a
page measured on its own.

### Text in any script, from the fonts of the system

A supplied font may be TrueType or OpenType with CFF or CFF2 outlines, a
variable font (drawn at its default instance), or one font of a collection:
`FontRef.Supplied(bytes, id, index)`. Whatever its outlines, only the glyphs
the document draws are embedded — a page of Chinese set in Noto Sans CJK
carries a few kilobytes of it, not 20 MB.

Text is shaped with the font's `GSUB` and `GPOS`: kerning and ligatures,
marks placed on their letters, Arabic joining (Arabic, Persian, Urdu),
Hebrew points, Thai and Lao, and the Indic syllable model for Devanagari,
Bengali, Gurmukhi, Gujarati, Tamil, Telugu, Kannada and Malayalam.
Paragraphs follow the Unicode Bidirectional Algorithm: Hebrew and Arabic
run right to left among Latin and digits, a paragraph takes its direction
from its first letter (`paragraph(text, direction = TextDirection.RightToLeft)`
says it outright), and `TextAlign.Start` / `End` — the default is `Start`
— are the sides a line begins and ends at. Where glyphs do not read back as
their text (a conjunct, a reordered matra) they carry `/ActualText`, so the
page copies and searches as the words that were written.

Not yet: Khmer, Myanmar and the other scripts of the Universal Shaping
Engine; line breaking inside Thai or CJK text without spaces; vertical
text. Fonts that have only Apple's `morx` and no `GSUB` are drawn without
their substitutions.

An album, a report, an invoice are *application* vocabularies. They lower
onto this one — `section()`, `landscape()`, `Margins`, `image(bytes, fit,
rotationDegrees)`, `pageBreak()`, colophons — and live in the application.

### Pages of their own size

`section(pageSize, margins, verticalAlign) { }` sets the pages it writes on
paper of their own; the blocks after it are back on the document's paper. A
photo album is one section per page — a square cover centred top to bottom,
then each photograph on a sheet exactly its size, with a line under it:

```kotlin
val nunito = FontFamily.of(FontRefSupplied(nunitoBytes, "Nunito-Light"))
val none = Margins.Fixed(0.pt, 0.pt, 0.pt, 0.pt)

val album = document {
    clock = Clock { PdfDate.ofEpochSeconds(System.currentTimeMillis() / 1000) }
    theme = Theme(bodyFamily = nunito, lineHeight = 1.5f)

    val side = PageSize.A4.toSize().width
    section(PageSize.Custom(side, side), margins = none, verticalAlign = VerticalAlign.Middle) {
        paragraph(align = TextAlign.Center, spacingAfter = 10.pt) { text("Лето в Карелии") { fontSize = side / 20.0f } }
        paragraph(align = TextAlign.Center) { text("Июль 2026") { fontSize = side / 35.0f } }
    }
    section(PageSize.Custom(640.pt, 520.pt), margins = Margins.Fixed(0.pt, 0.pt, 10.pt, 0.pt)) {
        image(photoBytes) // fills the 640 pt column: 480 pt tall
        paragraph(align = TextAlign.Right, indentRight = 5.pt, spacingBefore = 10.pt) {
            text("Фото: Анна") { fontSize = 8.pt; color = Rgb.of(0x616161) }
        }
    }
}
```

For an album of many large photographs, add them by path —
`image(Path(file.path))`, `Image(url:)` in Swift — and write the document to
a file with `writeTo(Path(...))` or `write(to:)`. A JPEG added that way is
held only as its frame header: the file is copied into the PDF when the
document is written, so thirty 20 MB photographs need the memory of none
of them. The files must not change until then.

A `pageBreak()` next to a section adds no blank page; the document's header
and footer are drawn on a section's pages too, in strips cut from that page.

## Swift (iOS / macOS)

The same pairing, spoken SwiftUI-style: the `PdfktDSL` package wraps the
Kotlin core's XCFramework in `@resultBuilder` scopes, and a fidelity test
battery asserts the facade and the raw bridged API produce byte-identical
PDFs.

```swift
import PdfktDSL

let doc = Document {
    Header { Paragraph { Text("Q1 Report").bold() } }
    Heading(1, "Q4 2026 summary")
    Paragraph {
        Text("Revenue: ").bold()
        Text("up 12% on the quarter")
    }
    Table {
        Row(header: true) {
            Cell("Metric")
            Cell("Value")
        }
        Row {
            Cell("Revenue")
            Cell("1 310").alignment(.right)
        }
    }
    Footer(pages: .exceptFirst) { PageOfPages() }
}

try doc.write(to: outputURL)
```

Sections, themes and fonts handed over as bytes read the same way:

```swift
let nunito = Font(data: nunitoData, id: "Nunito-Light")

let album = Document(theme: Theme(font: nunito, lineHeight: 1.5)) {
    Section(pageSize: .custom(width: 595.276, height: 595.276), margins: .all(0), verticalAlignment: .middle) {
        Paragraph { Text("Лето в Карелии").fontSize(29.76) }.alignment(.center).spacing(after: 10)
        Paragraph { Text("Июль 2026").fontSize(17.01) }.alignment(.center)
    }
    Section(pageSize: .custom(width: 640, height: 520), margins: Margins(top: 0, right: 0, bottom: 10, left: 0)) {
        Image(data: photo)
        Paragraph { Text("Фото: Анна").fontSize(8.42).color(0x616161) }
            .alignment(.right).indent(right: 5).spacing(before: 10)
    }
}
```

Emoji and characters the face has no glyph for are the theme's too:
`Theme(font:, emojiPolicy: .rasterized(id: "SystemEmoji") { sequence, emPixels in ... }, fallbacks: [cjk])`
draws each emoji from a picture your closure renders (Apple Color Emoji
through CoreText, say), and anything `font` lacks from the first fallback
that has it. In Kotlin the same is `Theme(emojiPolicy = EmojiPolicyRasterized(...))`
and `FontFamily.of(face).withFallbacks(listOf(...))`. A font of a collection
is `Font(data:id:index:)`; `Paragraph { ... }.direction(.rightToLeft)` and
`.alignment(.start)` / `.end` are the bidi side of a paragraph.

Errors are real Swift errors: the engine's no-silent-loss refusals (an
unencodable character, an emoji without a picture) arrive as catchable
`throws`, not a crash.

Local development: build the XCFramework first
(`./gradlew :core:assemblePdfktReleaseXCFramework`), then add the package
by path — the repository root is the package. `swift test` runs the smoke
and fidelity suites.

## JavaScript (Node / browser)

The same pairing again, spoken the way JavaScript document builders read:
chainable calls, styles applying to the last-written run. The Kotlin DSL
itself is not reachable from plain JavaScript — un-exported names are
mangled and value classes do not cross — so `jsMain` carries an exported
facade, and a fidelity suite asserts it produces byte-identical PDFs to
the Kotlin DSL.

```js
const { PdfDocument } = pdfkt.io.pdfkt.api;

const doc = new PdfDocument();
doc.heading(1, "Q4 2026 summary");
doc.paragraph(p => p.text("Revenue: ").bold().text("up 12% on the quarter"));
doc.table(t => {
  t.headerRow(r => r.cell("Metric").cell("Value"));
  t.row(r => r.cell("Revenue").cell("1 310", "right"));
});
const bytes = doc.toBytes(); // Int8Array of the finished PDF
```

`./gradlew :core:jsNodeProductionLibraryDistribution` builds the
consumable package (with TypeScript declarations) under
`build/js/packages/pdfkt-core`. Running the JS test suite needs `node`
on PATH.

## Architecture

| Layer | Contents | Source |
|---|---|---|
| L5 | `document { }` DSL, `io.pdfkt.api` | own design |
| L4 | layout engine | own design |
| L3 | fonts, encodings, resources | OpenType, AFM, ISO 32000 clause 9 |
| L2 | content stream builder | ISO 32000 clauses 8–9 |
| L1 | file syntax: objects, xref, trailer | ISO 32000 clause 7 |

All five are in `:core`, published as `pdfkt-core`. `io.pdfkt.api` is what a
consumer writes against; `io.pdfkt.core.*` is the model tier underneath it.

Everything is common Kotlin — compression, PNG decoding and font parsing
included — so every target renders byte-identical output. The metrics of the
14 standard fonts are embedded from Adobe's AFM files (see
`third_party/adobe-core14-afm/`); no other binary is committed.

## Running the tests

```bash
./gradlew check
```

Some JVM tests validate output against real font programs and external PDF
tools rather than committed binaries:

- **Fonts** — install `fonts-dejavu-core` and `fonts-noto-color-emoji`
  (Debian names), or point `PDFKT_TEST_FONT_DIR` at a directory containing
  `DejaVuSans.ttf`, `DejaVuSansMono.ttf` and `NotoColorEmoji.ttf`. A missing
  font fails the test that needs it rather than skipping.
- **System fonts** — the format, shaping and bidi suites read fonts of macOS
  and Android from `macos/` and `android/` under `PDFKT_TEST_FONT_DIR`, and
  the Unicode Character Database files (`BidiCharacterTest.txt` among them)
  from `ucd/` there. `core/scripts/generate_unicode_tables.py` regenerates
  the Unicode tables from that directory.
- **PDF tools** — `qpdf` and `poppler-utils` (`pdftotext`, `pdftoppm`) enable
  the render-verification tests; without them those tests skip and say so.
- **Reference readers** — `hb-shape` (HarfBuzz) is what the shaper is compared
  with, and fontTools (`PDFKT_FONTTOOLS_PYTHON`) what the CFF subsets are read
  back with; without them those comparisons skip and say so.

## Licence

Apache License 2.0 — see [LICENSE](LICENSE).
