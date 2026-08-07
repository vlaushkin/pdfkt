import Foundation
import Pdfkt

/// A PDF document, built SwiftUI-style and paired with docxkt's `Document`.
///
/// ```swift
/// let doc = Document {
///     Heading(1, "Q4 2026 summary")
///     Paragraph {
///         Text("Revenue: ").bold()
///         Text("up 12% on the quarter")
///     }
/// }
/// try doc.write(to: url)
/// ```
///
/// The Kotlin core wants an explicit clock; the Swift facade defaults it to
/// the moment the document is built, which is what a Swift caller means when
/// they say nothing. Pass `date:` (and `timeZone:`) to pin it.
public struct Document {
    internal let kotlin: KotlinDocument

    public init(
        pageSize: PageSize = .a4,
        landscape: Bool = false,
        margins: Margins? = nil,
        metadata: Metadata? = nil,
        date: Date = Date(),
        timeZone: TimeZone = .current,
        @DocumentBuilder content: () -> [DocumentBlock]
    ) {
        let blocks = content()
        self.kotlin = DocumentKt.document { scope in
            scope.clock = FixedClock(date: date, timeZone: timeZone)
            scope.pageSize = pageSize.kotlin
            if landscape { scope.landscape() }
            if let margins { scope.margins = margins.kotlin }
            if let metadata { metadata.apply(to: scope) }
            for block in blocks { block.apply(scope) }
        }
    }

    /// The rendered PDF. Throws when the engine refuses to lose something
    /// quietly — an unencodable character, an emoji without a picture, a
    /// band that overflows its strip — with the refusal's message intact.
    public func toData() throws -> Data {
        let kotlinBytes = try kotlin.toByteArray()
        let count = Int(kotlinBytes.size)
        var data = Data(count: count)
        data.withUnsafeMutableBytes { dst in
            guard let base = dst.bindMemory(to: Int8.self).baseAddress else { return }
            for i in 0..<count { base[i] = kotlinBytes.get(index: Int32(i)) }
        }
        return data
    }

    /// Render and write to `url`, overwriting any existing file.
    public func write(to url: URL) throws {
        try toData().write(to: url)
    }
}

/// The clock the facade hands the core: one pinned instant, so a document's
/// `/CreationDate` and `/ModDate` say when it was built.
internal final class FixedClock: Clock {
    private let seconds: Int64
    private let offsetMinutes: Int32

    init(date: Date, timeZone: TimeZone) {
        self.seconds = Int64(date.timeIntervalSince1970)
        self.offsetMinutes = Int32(timeZone.secondsFromGMT(for: date) / 60)
    }

    func now() -> PdfDate {
        PdfDate.companion.ofEpochSeconds(epochSeconds: seconds, offsetMinutes: offsetMinutes)
    }
}

/// One thing written into a `Document { }` — type-erased onto the Kotlin
/// scope it will be applied to.
public struct DocumentBlock {
    internal let apply: (KotlinDocumentScope) -> Void
    internal init(_ apply: @escaping (KotlinDocumentScope) -> Void) { self.apply = apply }
}

@resultBuilder
public enum DocumentBuilder {
    public static func buildBlock(_ components: DocumentBlock...) -> [DocumentBlock] { components }
    public static func buildOptional(_ component: [DocumentBlock]?) -> [DocumentBlock] { component ?? [] }
    public static func buildEither(first component: [DocumentBlock]) -> [DocumentBlock] { component }
    public static func buildEither(second component: [DocumentBlock]) -> [DocumentBlock] { component }
    public static func buildArray(_ components: [[DocumentBlock]]) -> [DocumentBlock] { components.flatMap { $0 } }

    public static func buildExpression(_ paragraph: Paragraph) -> DocumentBlock {
        DocumentBlock { scope in scope.paragraph(align: paragraph.align.kotlin, block: paragraph.applyToInline) }
    }

    public static func buildExpression(_ heading: Heading) -> DocumentBlock {
        DocumentBlock { scope in
            if let text = heading.text {
                scope.heading(level: Int32(heading.level), text: text)
            } else {
                scope.heading(level: Int32(heading.level), block: heading.applyToInline)
            }
        }
    }

    public static func buildExpression(_ table: Table) -> DocumentBlock {
        DocumentBlock { scope in scope.table(block: table.applyToTable) }
    }

    public static func buildExpression(_ list: BulletList) -> DocumentBlock {
        DocumentBlock { scope in
            scope.bulletList(
                markerStyle: list.markerStyle.kotlin,
                indentPerLevel: BlockSinkKt.DEFAULT_LIST_INDENT,
                block: list.applyToList
            )
        }
    }

    public static func buildExpression(_ list: NumberedList) -> DocumentBlock {
        DocumentBlock { scope in
            scope.numberedList(
                startNumber: Int32(list.startNumber),
                markerStyle: list.markerStyle.kotlin,
                indentPerLevel: BlockSinkKt.DEFAULT_LIST_INDENT,
                block: list.applyToList
            )
        }
    }

    public static func buildExpression(_ code: CodeBlock) -> DocumentBlock {
        DocumentBlock { scope in
            scope.codeBlock(text: code.text, backgroundColor: nil, fontSize: nil, padding: code.padding)
        }
    }

    public static func buildExpression(_ quote: Blockquote) -> DocumentBlock {
        DocumentBlock { scope in
            scope.blockquote(
                barColor: nil,
                barWidth: BlockSinkKt.DEFAULT_QUOTE_BAR_WIDTH,
                indentLeft: BlockSinkKt.DEFAULT_QUOTE_INDENT,
                block: quote.applyToQuote
            )
        }
    }

    public static func buildExpression(_ rule: HorizontalRule) -> DocumentBlock {
        DocumentBlock { scope in scope.hr(thickness: nil, color: nil, verticalSpacing: nil) }
    }

    public static func buildExpression(_ image: Image) -> DocumentBlock {
        DocumentBlock { scope in
            scope.image(
                bytes: image.data.toKotlinByteArray(),
                fit: image.fit.kotlin,
                rotationDegrees: Int32(image.rotationDegrees)
            )
        }
    }

    public static func buildExpression(_ pageBreak: PageBreak) -> DocumentBlock {
        DocumentBlock { scope in scope.pageBreak() }
    }

    public static func buildExpression(_ header: Header) -> DocumentBlock {
        DocumentBlock { scope in
            scope.header(height: header.height, pages: header.pages.kotlin, block: header.applyToColophon)
        }
    }

    public static func buildExpression(_ footer: Footer) -> DocumentBlock {
        DocumentBlock { scope in
            scope.footer(height: footer.height, pages: footer.pages.kotlin, block: footer.applyToColophon)
        }
    }
}

internal extension Data {
    func toKotlinByteArray() -> KotlinByteArray {
        let result = KotlinByteArray(size: Int32(count))
        withUnsafeBytes { src in
            guard let base = src.bindMemory(to: Int8.self).baseAddress else { return }
            for i in 0..<count { result.set(index: Int32(i), value: base[i]) }
        }
        return result
    }
}
