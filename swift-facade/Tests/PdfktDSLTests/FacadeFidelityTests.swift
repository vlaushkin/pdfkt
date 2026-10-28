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
        // The bulk path the facade takes must agree with the byte-at-a-time one.
        XCTAssertEqual(try raw.toNSData(), rawData, file: file, line: line)
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
                scope.paragraph(text: "Hello, world!", align: .left, indentLeft: 0, indentRight: 0, spacingBefore: nil, spacingAfter: nil)
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
                scope.paragraph(align: .left, indentLeft: 0, indentRight: 0, spacingBefore: nil, spacingAfter: nil) { inline in
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
                scope.paragraph(align: .left, indentLeft: 0, indentRight: 0, spacingBefore: nil, spacingAfter: nil) { inline in
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
                scope.paragraph(text: "centred", align: .center, indentLeft: 0, indentRight: 0, spacingBefore: nil, spacingAfter: nil)
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
                scope.paragraph(align: .left, indentLeft: 0, indentRight: 0, spacingBefore: nil, spacingAfter: nil) { inline in
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
                    quote.paragraph(align: .left, indentLeft: 0, indentRight: 0, spacingBefore: nil, spacingAfter: nil) { inline in inline.text(value: "quoted") }
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
                scope.paragraph(text: "one", align: .left, indentLeft: 0, indentRight: 0, spacingBefore: nil, spacingAfter: nil)
                scope.pageBreak()
                scope.paragraph(text: "two", align: .left, indentLeft: 0, indentRight: 0, spacingBefore: nil, spacingAfter: nil)
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
                scope.paragraph(text: "wide", align: .left, indentLeft: 0, indentRight: 0, spacingBefore: nil, spacingAfter: nil)
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
                    colophon.paragraph(align: .left, indentLeft: 0, indentRight: 0, spacingBefore: nil, spacingAfter: nil) { inline in
                        inline.text(value: "Q1 Report") { style in style.bold = true }
                    }
                }
                scope.footer(height: DocumentScopeKt.DEFAULT_BAND_HEIGHT, pages: .exceptfirst) { colophon in
                    colophon.paragraph(text: colophon.pageOfPages(separator: " / "), align: .right, indentLeft: 0, indentRight: 0, spacingBefore: nil, spacingAfter: nil)
                }
                scope.paragraph(text: "body", align: .left, indentLeft: 0, indentRight: 0, spacingBefore: nil, spacingAfter: nil)
                scope.pageBreak()
                scope.paragraph(text: "more", align: .left, indentLeft: 0, indentRight: 0, spacingBefore: nil, spacingAfter: nil)
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
                scope.paragraph(text: "dated", align: .left, indentLeft: 0, indentRight: 0, spacingBefore: nil, spacingAfter: nil)
            }
        )
    }

    // MARK: - Sections, themes, supplied fonts


    /// A picture by URL writes the PDF its bytes write, and `write(to:)` —
    /// which renders straight into the file — writes what `toData()` returns.
    func testImageFromFileAndWriteToFile() throws {
        let photo = headerOnlyJpeg(width: 64, height: 48)
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }
        let photoURL = dir.appendingPathComponent("photo.jpg")
        try photo.write(to: photoURL)

        func album(_ image: PdfktDSL.Image) -> PdfktDSL.Document {
            PdfktDSL.Document(
                date: Date(timeIntervalSince1970: TimeInterval(pinnedEpochSeconds)),
                timeZone: TimeZone(secondsFromGMT: 0)!
            ) {
                Section(pageSize: .custom(width: 64, height: 48), margins: .all(0)) { image }
            }
        }
        let byData = try album(Image(data: photo)).toData()
        XCTAssertEqual(try album(Image(url: photoURL)).toData(), byData)
        XCTAssertEqual(try album(Image(path: photoURL.path)).toData(), byData)

        let out = dir.appendingPathComponent("album.pdf")
        try album(Image(url: photoURL)).write(to: out)
        XCTAssertEqual(try Data(contentsOf: out), byData)
    }

    func testAlbumSectionsWithSuppliedFont() throws {
        let fontData = try Data(contentsOf: testFontURL)
        let photo = headerOnlyJpeg(width: 64, height: 48)
        let side = 595.276
        let facade = PdfktDSL.Document(
            theme: PdfktDSL.Theme(font: Font(data: fontData, id: "sans"), lineHeight: 1.5),
            date: Date(timeIntervalSince1970: TimeInterval(pinnedEpochSeconds)),
            timeZone: TimeZone(secondsFromGMT: Int(pinnedOffsetMinutes) * 60)!
        ) {
            Section(pageSize: .custom(width: side, height: side), margins: .all(0), verticalAlignment: .middle) {
                Paragraph { Text("Лето").bold().fontSize(side / 20) }.alignment(.center).spacing(after: 10)
                Paragraph { Text("Июль").fontSize(side / 35) }.alignment(.center)
            }
            Section(
                pageSize: .custom(width: 64, height: 48 + 20 + 12.6),
                margins: PdfktDSL.Margins(top: 0, right: 0, bottom: 10, left: 0)
            ) {
                Image(data: photo)
                Paragraph { Text("Фото").fontSize(8.4).color(0x616161) }
                    .alignment(.right).indent(right: 5).spacing(before: 10)
            }
        }
        let raw = rawDocument { scope in
            let family = FontFamily.companion.of(font: FontRefSupplied(bytes: fontData.toKotlinByteArray(), id: "sans"))
            scope.theme = ObjCInteropKt.themeOf(family: family, fontSize: 11, lineHeight: 1.5, textColor: 0)
            scope.section(
                pageSize: PageSizeCustom(width: Float(side), height: Float(side)),
                margins: MarginsFixed(top: 0, right: 0, bottom: 0, left: 0),
                verticalAlign: .middle
            ) { section in
                ObjCInteropKt.spacedParagraph(
                    section, align: .center, indentLeft: 0, indentRight: 0,
                    spacingBefore: nil, spacingAfter: KotlinDouble(value: 10)
                ) { inline in
                    inline.text(value: "Лето") { style in
                        style.bold = true
                        style.fontSize(points: side / 20)
                    }
                }
                ObjCInteropKt.spacedParagraph(
                    section, align: .center, indentLeft: 0, indentRight: 0, spacingBefore: nil, spacingAfter: nil
                ) { inline in
                    inline.text(value: "Июль") { style in style.fontSize(points: side / 35) }
                }
            }
            scope.section(
                pageSize: PageSizeCustom(width: 64, height: Float(48 + 20 + 12.6)),
                margins: MarginsFixed(top: 0, right: 0, bottom: 10, left: 0),
                verticalAlign: .top
            ) { section in
                section.image(bytes: photo.toKotlinByteArray(), fit: .contain, rotationDegrees: 0)
                ObjCInteropKt.spacedParagraph(
                    section, align: .right, indentLeft: 0, indentRight: 5,
                    spacingBefore: KotlinDouble(value: 10), spacingAfter: nil
                ) { inline in
                    inline.text(value: "Фото") { style in
                        style.fontSize(points: 8.4)
                        style.color(rgb: 0x616161)
                    }
                }
            }
        }
        try assertSameBytes(facade: facade, raw: raw)
    }

    /// A JPEG of the given size that is only its headers — SOI, a baseline
    /// SOF0 frame header, a scan header, EOI (ITU-T T.81 §B.2). The engine
    /// embeds a JPEG as it arrived and reads nothing past the frame header, so
    /// this is all a layout needs; no picture is committed to the repository.
    private func headerOnlyJpeg(width: Int, height: Int) -> Data {
        Data([
            0xFF, 0xD8,
            0xFF, 0xC0, 0x00, 0x11, 0x08,
            UInt8(height >> 8), UInt8(height & 0xFF), UInt8(width >> 8), UInt8(width & 0xFF),
            0x03, 0x01, 0x11, 0x00, 0x02, 0x11, 0x01, 0x03, 0x11, 0x01,
            0xFF, 0xDA, 0x00, 0x08, 0x01, 0x01, 0x00, 0x00, 0x3F, 0x00,
            0xFF, 0xD9,
        ])
    }

    /// DejaVuSans.ttf from `PDFKT_TEST_FONT_DIR`, as the Kotlin suites find
    /// it, or else from `test-fonts/` at the repository root.
    private var testFontURL: URL {
        if let dir = ProcessInfo.processInfo.environment["PDFKT_TEST_FONT_DIR"] {
            return URL(fileURLWithPath: dir).appendingPathComponent("DejaVuSans.ttf")
        }
        return URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            .deletingLastPathComponent().appendingPathComponent("test-fonts/DejaVuSans.ttf")
    }
}

/// The raw side's pin — the same instant the facade side is handed.
private final class PinnedKotlinClock: Clock {
    func now() -> PdfDate {
        PdfDate.companion.ofEpochSeconds(epochSeconds: pinnedEpochSeconds, offsetMinutes: pinnedOffsetMinutes)
    }
}
