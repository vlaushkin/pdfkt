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
    implementation("com.github.vlaushkin.pdfkt:pdfkt-core:0.0.1")
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

An album, a report, an invoice are *application* vocabularies. They lower
onto this one — `landscape()`, `Margins`, `image(bytes, fit,
rotationDegrees)`, `pageBreak()`, colophons — and live in the application.

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
- **PDF tools** — `qpdf` and `poppler-utils` (`pdftotext`, `pdftoppm`) enable
  the render-verification tests; without them those tests skip and say so.

## Licence

Apache License 2.0 — see [LICENSE](LICENSE).
