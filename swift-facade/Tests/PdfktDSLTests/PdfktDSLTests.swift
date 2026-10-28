import XCTest
@testable import PdfktDSL

/// Structural smoke tests: every construct renders into something that
/// starts like a PDF, ends like one, and is big enough to be one.
final class PdfktDSLTests: XCTestCase {

    private func assertLooksLikePdf(_ data: Data, file: StaticString = #file, line: UInt = #line) {
        XCTAssertGreaterThan(data.count, 500, "expected > 500 bytes, got \(data.count)", file: file, line: line)
        XCTAssertEqual(Array(data.prefix(5)), Array("%PDF-".utf8), "PDF header missing", file: file, line: line)
        let tail = String(decoding: data.suffix(32), as: UTF8.self)
        XCTAssertTrue(tail.contains("%%EOF"), "PDF trailer missing, tail: \(tail)", file: file, line: line)
    }

    func testHelloWorld() throws {
        let doc = Document {
            Paragraph { Text("Hello, world!") }
        }
        try assertLooksLikePdf(doc.toData())
    }

    func testInlineStyles() throws {
        let doc = Document {
            Paragraph {
                Text("Revenue: ").bold()
                Text("up ")
                Text("12%").italic().color(0xCC0000)
                Text(" — details ").fontSize(9)
                Text("struck").strike()
                Text("under").underline()
                Text("mono").code()
            }
        }
        try assertLooksLikePdf(doc.toData())
    }

    func testHeadingAndLists() throws {
        let doc = Document {
            Heading(1, "Q4 2026 summary")
            Heading(2) { Text("Styled ").bold(); Text("heading") }
            BulletList {
                Item("first")
                Item("second")
            }
            NumberedList {
                Item("one")
                Item { Paragraph { Text("rich item").italic() } }
            }.start(3)
        }
        try assertLooksLikePdf(doc.toData())
    }

    func testTable() throws {
        let doc = Document {
            Table {
                Row(header: true) {
                    Cell("Metric")
                    Cell("Value")
                }
                Row {
                    Cell("Revenue")
                    Cell("1 310").alignment(.right)
                }
                Row {
                    Cell { Paragraph { Text("rich").bold() } }
                    Cell("cell")
                }
            }
        }
        try assertLooksLikePdf(doc.toData())
    }

    func testCodeQuoteRuleBreakLink() throws {
        let doc = Document {
            Paragraph { Text("above") }
            CodeBlock("val x = 1")
            Blockquote { Paragraph { Text("quoted") } }
            HorizontalRule()
            Paragraph {
                Link("https://example.com") { Text("a link") }
                Br()
                Text("after break")
            }
            PageBreak()
            Paragraph { Text("second page") }
        }
        try assertLooksLikePdf(doc.toData())
    }

    func testColophonsAndLandscape() throws {
        let doc = Document(landscape: true, margins: .all(24)) {
            Header { Paragraph { Text("Q1 Report").bold() } }
            Footer(pages: .exceptFirst) { PageOfPages() }
            Paragraph { Text("body") }
            PageBreak()
            Paragraph { Text("page two") }
        }
        try assertLooksLikePdf(doc.toData())
    }

    func testMetadataAndPinnedDate() throws {
        let doc = Document(
            metadata: Metadata(title: "Report", author: "pdfkt"),
            date: Date(timeIntervalSince1970: 1_770_000_000),
            timeZone: TimeZone(secondsFromGMT: 3600)!
        ) {
            Paragraph { Text("dated") }
        }
        let data = try doc.toData()
        try assertLooksLikePdf(data)
        let text = String(decoding: data, as: UTF8.self)
        XCTAssertTrue(text.contains("Report"), "title missing from /Info")
        XCTAssertTrue(text.contains("D:2026"), "the pinned creation date missing from /Info")
    }

    func testEmptyDocumentThrows() {
        let doc = Document {}
        XCTAssertThrowsError(try doc.toData(), "an empty document must be refused, not rendered blank")
    }

    func testWriteToURL() throws {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("pdfkt-dsl-test-\(UUID().uuidString).pdf")
        defer { try? FileManager.default.removeItem(at: url) }

        let doc = Document { Paragraph { Text("to disk") } }
        try doc.write(to: url)

        let read = try Data(contentsOf: url)
        XCTAssertEqual(Array(read.prefix(5)), Array("%PDF-".utf8))
    }

    func testDeterministicWithPinnedDate() throws {
        func build() -> Document {
            Document(date: Date(timeIntervalSince1970: 1_770_000_000), timeZone: TimeZone(secondsFromGMT: 0)!) {
                Heading(1, "Same")
                Paragraph { Text("bytes").bold() }
            }
        }
        XCTAssertEqual(try build().toData(), try build().toData(), "a pinned clock must make the bytes reproducible")
    }

    /// `if`, `if`/`else` and `for` in every builder, mixed with plain
    /// statements: the document must be the one written out by hand.
    func testControlFlowInBuilders() throws {
        let date = Date(timeIntervalSince1970: 1_770_000_000)
        let zone = TimeZone(secondsFromGMT: 0)!
        let title: String? = "Title"
        let missing: String? = nil
        let names = ["One", "Two"]
        let flowing = Document(date: date, timeZone: zone) {
            Header { if let title { Paragraph { Text(title) } } }
            Section(pageSize: .custom(width: 300, height: 300), margins: .all(0), verticalAlignment: .middle) {
                if let title { Paragraph { Text(title) } }
                if let missing { Paragraph { Text(missing) } }
                Paragraph { Text("Fixed") }
            }
            for name in names {
                Paragraph {
                    Text(name)
                    if name == "One" { Text(" first").bold() } else { Text(" later") }
                }
            }
            BulletList { for name in names { Item(name) } }
            Table {
                Row(header: true) { for name in names { Cell(name) } }
                if title != nil { Row { Cell("a"); Cell("b") } }
            }
            Blockquote { if title != nil { Paragraph { Text("Quoted") } } }
        }
        let flat = Document(date: date, timeZone: zone) {
            Header { Paragraph { Text("Title") } }
            Section(pageSize: .custom(width: 300, height: 300), margins: .all(0), verticalAlignment: .middle) {
                Paragraph { Text("Title") }
                Paragraph { Text("Fixed") }
            }
            Paragraph { Text("One"); Text(" first").bold() }
            Paragraph { Text("Two"); Text(" later") }
            BulletList { Item("One"); Item("Two") }
            Table {
                Row(header: true) { Cell("One"); Cell("Two") }
                Row { Cell("a"); Cell("b") }
            }
            Blockquote { Paragraph { Text("Quoted") } }
        }
        XCTAssertEqual(try flowing.toData(), try flat.toData())
    }
}
