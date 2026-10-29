import XCTest
import PDFKit
import ImageIO
import CoreGraphics
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
                scope.paragraph(text: "Hello, world!", align: .left, indentLeft: 0, indentRight: 0, spacingBefore: nil, spacingAfter: nil, direction: .auto_)
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
                scope.paragraph(align: .left, indentLeft: 0, indentRight: 0, spacingBefore: nil, spacingAfter: nil, direction: .auto_) { inline in
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
                scope.paragraph(align: .left, indentLeft: 0, indentRight: 0, spacingBefore: nil, spacingAfter: nil, direction: .auto_) { inline in
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
                scope.paragraph(text: "centred", align: .center, indentLeft: 0, indentRight: 0, spacingBefore: nil, spacingAfter: nil, direction: .auto_)
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
                scope.paragraph(align: .left, indentLeft: 0, indentRight: 0, spacingBefore: nil, spacingAfter: nil, direction: .auto_) { inline in
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
                    quote.paragraph(align: .left, indentLeft: 0, indentRight: 0, spacingBefore: nil, spacingAfter: nil, direction: .auto_) { inline in inline.text(value: "quoted") }
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
                scope.paragraph(text: "one", align: .left, indentLeft: 0, indentRight: 0, spacingBefore: nil, spacingAfter: nil, direction: .auto_)
                scope.pageBreak()
                scope.paragraph(text: "two", align: .left, indentLeft: 0, indentRight: 0, spacingBefore: nil, spacingAfter: nil, direction: .auto_)
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
                scope.paragraph(text: "wide", align: .left, indentLeft: 0, indentRight: 0, spacingBefore: nil, spacingAfter: nil, direction: .auto_)
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
                    colophon.paragraph(align: .left, indentLeft: 0, indentRight: 0, spacingBefore: nil, spacingAfter: nil, direction: .auto_) { inline in
                        inline.text(value: "Q1 Report") { style in style.bold = true }
                    }
                }
                scope.footer(height: DocumentScopeKt.DEFAULT_BAND_HEIGHT, pages: .exceptfirst) { colophon in
                    colophon.paragraph(text: colophon.pageOfPages(separator: " / "), align: .right, indentLeft: 0, indentRight: 0, spacingBefore: nil, spacingAfter: nil, direction: .auto_)
                }
                scope.paragraph(text: "body", align: .left, indentLeft: 0, indentRight: 0, spacingBefore: nil, spacingAfter: nil, direction: .auto_)
                scope.pageBreak()
                scope.paragraph(text: "more", align: .left, indentLeft: 0, indentRight: 0, spacingBefore: nil, spacingAfter: nil, direction: .auto_)
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
                scope.paragraph(text: "dated", align: .left, indentLeft: 0, indentRight: 0, spacingBefore: nil, spacingAfter: nil, direction: .auto_)
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

    /// Emoji in a title, through each policy the facade offers: refused by
    /// the text's font with `.none`, gone with `.strip`, spelled out with
    /// `.placeholder`, and drawn as a Type 3 font of pictures with
    /// `.rasterized` — whose PNGs cross the bridge as one copy each.
    func testEmojiPolicies() throws {
        let png = try solidPng(side: 8)
        var asked: [String] = []
        func album(_ policy: PdfktDSL.EmojiPolicy) -> PdfktDSL.Document {
            PdfktDSL.Document(
                theme: PdfktDSL.Theme(emojiPolicy: policy),
                date: Date(timeIntervalSince1970: TimeInterval(pinnedEpochSeconds)),
                timeZone: TimeZone(secondsFromGMT: 0)!
            ) {
                Paragraph { Text("Hi 😄😁") }
            }
        }

        XCTAssertThrowsError(try album(.none).toData())
        let stripped = try album(.strip).toData()
        XCTAssertFalse(String(decoding: stripped, as: UTF8.self).contains("/Type3"))
        XCTAssertNoThrow(try album(.placeholder).toData())

        let drawn = try album(.rasterized(id: "SystemEmoji", emPixels: 64) { sequence, emPixels in
            asked.append(sequence)
            XCTAssertEqual(emPixels, 64)
            return PdfktDSL.RasterGlyph(png: png, width: 8, height: 8, bearingX: 0, bearingY: 7, advance: 8)
        }).toData()
        XCTAssertTrue(String(decoding: drawn, as: UTF8.self).contains("/Type3"))
        XCTAssertEqual(Set(asked), ["😄", "😁"])

        XCTAssertThrowsError(try album(.rasterized(id: "Nothing") { _, _ in nil }).toData())
    }

    /// Cyrillic in a Helvetica document, drawn from the fallback font.
    func testFallbackFonts() throws {
        let fontData = try Data(contentsOf: testFontURL)
        func doc(_ fallbacks: [PdfktDSL.Font]) -> PdfktDSL.Document {
            PdfktDSL.Document(theme: PdfktDSL.Theme(fallbacks: fallbacks)) {
                Paragraph { Text("Hello, Привет") }
            }
        }
        XCTAssertThrowsError(try doc([]).toData())
        let pdf = try doc([Font(data: fontData, id: "sans")]).toData()
        let text = String(decoding: pdf, as: UTF8.self)
        XCTAssertTrue(text.contains("/Helvetica") && text.contains("/Type0"))
    }

    func testSystemFontsOfEveryFormatAndDirection() throws {
        // A member of a font collection with CFF outlines (Hiragino Sans GB, CID-keyed),
        // and Arabic, which is shaped and runs right to left.
        let hiragino = try Data(contentsOf: fontURL("macos/Hiragino Sans GB.ttc"))
        let naskh = try Data(contentsOf: fontURL("android/NotoNaskhArabic-Regular.ttf"))
        let hebrew = try Data(contentsOf: fontURL("android/NotoSansHebrew-Regular.ttf"))
        let fallbacks = [
            Font(data: hiragino, id: "hiragino", index: 1),
            Font(data: naskh, id: "naskh"),
            Font(data: hebrew, id: "hebrew"),
        ]
        let facade = PdfktDSL.Document(theme: PdfktDSL.Theme(fallbacks: fallbacks)) {
            Paragraph { Text("中文 Hello") }
            Paragraph { Text("مرحبا بالعالم") }.alignment(.start)
            Paragraph { Text("שלום abc") }.direction(.leftToRight)
        }
        let pdf = try facade.toData()
        let text = String(decoding: pdf, as: UTF8.self)
        XCTAssertTrue(text.contains("/CIDFontType0C"), "the CFF font is not embedded as CFF")
        let read = try XCTUnwrap(PDFDocument(data: pdf)?.page(at: 0)?.string)
        XCTAssertTrue(read.contains("中文"), read)
        XCTAssertTrue(read.contains("مرحبا"), read)
    }

    /// A PNG of one colour, `side` pixels square, encoded by ImageIO.
    private func solidPng(side: Int) throws -> Data {
        let context = CGContext(
            data: nil, width: side, height: side, bitsPerComponent: 8, bytesPerRow: 0,
            space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
        )!
        context.setFillColor(red: 1, green: 0.8, blue: 0, alpha: 1)
        context.fill(CGRect(x: 0, y: 0, width: side, height: side))
        let data = NSMutableData()
        let destination = CGImageDestinationCreateWithData(data, "public.png" as CFString, 1, nil)!
        CGImageDestinationAddImage(destination, context.makeImage()!, nil)
        XCTAssertTrue(CGImageDestinationFinalize(destination))
        return data as Data
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
                    spacingBefore: nil, spacingAfter: KotlinDouble(value: 10), direction: .auto_
                ) { inline in
                    inline.text(value: "Лето") { style in
                        style.bold = true
                        style.fontSize(points: side / 20)
                    }
                }
                ObjCInteropKt.spacedParagraph(
                    section, align: .center, indentLeft: 0, indentRight: 0, spacingBefore: nil, spacingAfter: nil, direction: .auto_
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
                    spacingBefore: KotlinDouble(value: 10), spacingAfter: nil, direction: .auto_
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
    private var testFontURL: URL { fontURL("DejaVuSans.ttf") }

    private func fontURL(_ file: String) -> URL {
        if let dir = ProcessInfo.processInfo.environment["PDFKT_TEST_FONT_DIR"] {
            return URL(fileURLWithPath: dir).appendingPathComponent(file)
        }
        return URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            .deletingLastPathComponent().appendingPathComponent("test-fonts").appendingPathComponent(file)
    }
}

/// The raw side's pin — the same instant the facade side is handed.
private final class PinnedKotlinClock: Clock {
    func now() -> PdfDate {
        PdfDate.companion.ofEpochSeconds(epochSeconds: pinnedEpochSeconds, offsetMinutes: pinnedOffsetMinutes)
    }
}
