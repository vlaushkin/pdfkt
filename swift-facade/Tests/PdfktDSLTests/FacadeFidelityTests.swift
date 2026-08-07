import XCTest
import Pdfkt
@testable import PdfktDSL

/// Cross-translation fidelity: each test builds the same document twice —
/// once through the SwiftUI-style facade, once through the raw bridged
/// Kotlin API — and asserts the PDF bytes are identical.
///
/// pdfkt is deterministic once the clock is pinned, so unlike a ZIP-based
/// format there is nothing to exclude: the whole file is compared.
private let pinnedEpochSeconds: Int64 = 1_770_000_000
private let pinnedOffsetMinutes: Int32 = 60

final class FacadeFidelityTests: XCTestCase {

    private func rawDocument(_ block: @escaping (KotlinDocumentScope) -> Void) -> KotlinDocument {
        DocumentKt.document { scope in
            scope.clock = PinnedKotlinClock()
            block(scope)
        }
    }

    private func facadeDocument(
        pageSize: PdfktDSL.PageSize = .a4,
        landscape: Bool = false,
        margins: PdfktDSL.Margins? = nil,
        metadata: PdfktDSL.Metadata? = nil,
        @DocumentBuilder content: () -> [DocumentBlock]
    ) -> PdfktDSL.Document {
        PdfktDSL.Document(
            pageSize: pageSize,
            landscape: landscape,
            margins: margins,
            metadata: metadata,
            date: Date(timeIntervalSince1970: TimeInterval(pinnedEpochSeconds)),
            timeZone: TimeZone(secondsFromGMT: Int(pinnedOffsetMinutes) * 60)!,
            content: content
        )
    }

    private func assertSameBytes(
        facade: PdfktDSL.Document,
        raw: KotlinDocument,
        file: StaticString = #file,
        line: UInt = #line
    ) throws {
        let facadeData = try facade.toData()
        let rawBytes = try raw.toByteArray()
        var rawData = Data(count: Int(rawBytes.size))
        rawData.withUnsafeMutableBytes { dst in
            guard let base = dst.bindMemory(to: Int8.self).baseAddress else { return }
            for i in 0..<Int(rawBytes.size) { base[i] = rawBytes.get(index: Int32(i)) }
        }
        XCTAssertEqual(
            facadeData, rawData,
            "facade and raw bytes differ (\(facadeData.count) vs \(rawData.count))",
            file: file, line: line
        )
    }

    // MARK: - Paragraph and inline styles

    func testPlainParagraph() throws {
        try assertSameBytes(
            facade: facadeDocument { Paragraph { Text("Hello, world!") } },
            raw: rawDocument { scope in
                scope.paragraph(text: "Hello, world!", align: .left)
            }
        )
    }

    func testStyledRuns() throws {
        try assertSameBytes(
            facade: facadeDocument {
                Paragraph {
                    Text("Revenue: ").bold()
                    Text("up 12%")
                }
            },
            raw: rawDocument { scope in
                scope.paragraph(align: .left) { inline in
                    inline.text(value: "Revenue: ") { style in style.bold = true }
                    inline.text(value: "up 12%")
                }
            }
        )
    }

    func testColorAndFontSize() throws {
        try assertSameBytes(
            facade: facadeDocument {
                Paragraph {
                    Text("warning").color(0xCC0000).fontSize(9)
                }
            },
            raw: rawDocument { scope in
                scope.paragraph(align: .left) { inline in
                    inline.text(value: "warning") { style in
                        style.color(rgb: 0xCC0000)
                        style.fontSize(points: 9)
                    }
                }
            }
        )
    }

    func testAlignment() throws {
        try assertSameBytes(
            facade: facadeDocument {
                Paragraph { Text("centred") }.alignment(.center)
            },
            raw: rawDocument { scope in
                scope.paragraph(text: "centred", align: .center)
            }
        )
    }

    func testLinkAndBreak() throws {
        try assertSameBytes(
            facade: facadeDocument {
                Paragraph {
                    Link("https://example.com") { Text("here") }
                    Br()
                    Text("next line")
                }
            },
            raw: rawDocument { scope in
                scope.paragraph(align: .left) { inline in
                    inline.link(href: "https://example.com") { linkScope in
                        linkScope.text(value: "here")
                    }
                    inline.br(soft: false)
                    inline.text(value: "next line")
                }
            }
        )
    }

    // MARK: - Headings, lists, tables

    func testHeadings() throws {
        try assertSameBytes(
            facade: facadeDocument {
                Heading(1, "Title")
                Heading(3) { Text("styled ").italic(); Text("tail") }
            },
            raw: rawDocument { scope in
                scope.heading(level: 1, text: "Title")
                scope.heading(level: 3) { inline in
                    inline.text(value: "styled ") { style in style.italic = true }
                    inline.text(value: "tail")
                }
            }
        )
    }

    func testLists() throws {
        try assertSameBytes(
            facade: facadeDocument {
                BulletList {
                    Item("first")
                    Item("second")
                }.marker(.dash)
                NumberedList {
                    Item("third")
                }.start(7)
            },
            raw: rawDocument { scope in
                scope.bulletList(markerStyle: .dash, indentPerLevel: BlockSinkKt.DEFAULT_LIST_INDENT) { list in
                    list.item(text: "first")
                    list.item(text: "second")
                }
                scope.numberedList(
                    startNumber: 7,
                    markerStyle: .decimal,
                    indentPerLevel: BlockSinkKt.DEFAULT_LIST_INDENT
                ) { list in
                    list.item(text: "third")
                }
            }
        )
    }

    func testTableWithHeaderRow() throws {
        try assertSameBytes(
            facade: facadeDocument {
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
            },
            raw: rawDocument { scope in
                scope.table { table in
                    table.header { row in
                        row.cell(text: "Metric", align: .left, verticalAlign: .top, backgroundColor: nil)
                        row.cell(text: "Value", align: .left, verticalAlign: .top, backgroundColor: nil)
                    }
                    table.row { row in
                        row.cell(text: "Revenue", align: .left, verticalAlign: .top, backgroundColor: nil)
                        row.cell(text: "1 310", align: .right, verticalAlign: .top, backgroundColor: nil)
                    }
                }
            }
        )
    }

    // MARK: - Blocks

    func testCodeQuoteRule() throws {
        try assertSameBytes(
            facade: facadeDocument {
                CodeBlock("val x = 1")
                Blockquote { Paragraph { Text("quoted") } }
                HorizontalRule()
            },
            raw: rawDocument { scope in
                scope.codeBlock(
                    text: "val x = 1",
                    backgroundColor: nil,
                    fontSize: nil,
                    padding: BlockSinkKt.DEFAULT_CODE_PADDING
                )
                scope.blockquote(
                    barColor: nil,
                    barWidth: BlockSinkKt.DEFAULT_QUOTE_BAR_WIDTH,
                    indentLeft: BlockSinkKt.DEFAULT_QUOTE_INDENT
                ) { quote in
                    quote.paragraph(align: .left) { inline in inline.text(value: "quoted") }
                }
                scope.hr(thickness: nil, color: nil, verticalSpacing: nil)
            }
        )
    }

    func testPageBreak() throws {
        try assertSameBytes(
            facade: facadeDocument {
                Paragraph { Text("one") }
                PageBreak()
                Paragraph { Text("two") }
            },
            raw: rawDocument { scope in
                scope.paragraph(text: "one", align: .left)
                scope.pageBreak()
                scope.paragraph(text: "two", align: .left)
            }
        )
    }

    // MARK: - Page setup and colophons

    func testLandscapeAndMargins() throws {
        try assertSameBytes(
            facade: facadeDocument(landscape: true, margins: .all(24)) {
                Paragraph { Text("wide") }
            },
            raw: rawDocument { scope in
                scope.landscape()
                scope.margins = MarginsFixed(top: 24, right: 24, bottom: 24, left: 24)
                scope.paragraph(text: "wide", align: .left)
            }
        )
    }

    func testColophons() throws {
        try assertSameBytes(
            facade: facadeDocument {
                Header { Paragraph { Text("Q1 Report").bold() } }
                Footer(pages: .exceptFirst) { PageOfPages() }
                Paragraph { Text("body") }
                PageBreak()
                Paragraph { Text("more") }
            },
            raw: rawDocument { scope in
                scope.header(height: DocumentScopeKt.DEFAULT_BAND_HEIGHT, pages: .all) { colophon in
                    colophon.paragraph(align: .left) { inline in
                        inline.text(value: "Q1 Report") { style in style.bold = true }
                    }
                }
                scope.footer(height: DocumentScopeKt.DEFAULT_BAND_HEIGHT, pages: .exceptfirst) { colophon in
                    colophon.paragraph(text: colophon.pageOfPages(separator: " / "), align: .right)
                }
                scope.paragraph(text: "body", align: .left)
                scope.pageBreak()
                scope.paragraph(text: "more", align: .left)
            }
        )
    }

    func testMetadata() throws {
        try assertSameBytes(
            facade: facadeDocument(metadata: PdfktDSL.Metadata(title: "Report", author: "pdfkt")) {
                Paragraph { Text("dated") }
            },
            raw: rawDocument { scope in
                scope.metadata = Pdfkt.Metadata(
                    title: "Report",
                    author: "pdfkt",
                    subject: nil,
                    keywords: [],
                    creator: scope.metadata.creator,
                    producer: scope.metadata.producer
                )
                scope.paragraph(text: "dated", align: .left)
            }
        )
    }
}

/// The raw side's pin — the same instant the facade side is handed.
private final class PinnedKotlinClock: Clock {
    func now() -> PdfDate {
        PdfDate.companion.ofEpochSeconds(epochSeconds: pinnedEpochSeconds, offsetMinutes: pinnedOffsetMinutes)
    }
}
