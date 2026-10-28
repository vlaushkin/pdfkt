import Foundation
import Pdfkt

/// A heading, level 1..6.
public struct Heading {
    internal let level: Int
    internal let text: String?
    internal let runs: [Run]?

    public init(_ level: Int, _ text: String) {
        self.level = level
        self.text = text
        self.runs = nil
    }

    public init(_ level: Int, @ParagraphBuilder content: () -> [Run]) {
        self.level = level
        self.text = nil
        self.runs = content()
    }

    internal func applyToInline(_ scope: KotlinInlineScope) {
        for run in runs ?? [] { run.apply(scope) }
    }
}

/// Preformatted text in the theme's code face, on its own background.
public struct CodeBlock {
    internal let text: String
    internal let padding: Float

    public init(_ text: String) {
        self.text = text
        self.padding = BlockSinkKt.DEFAULT_CODE_PADDING
    }
}

/// A quotation: its paragraphs stand behind the theme's bar.
public struct Blockquote {
    internal let paragraphs: [Paragraph]

    public init(@BlockContainerBuilder content: () -> [Paragraph]) {
        self.paragraphs = content()
    }

    internal func applyToQuote(_ scope: KotlinBlockquoteScope) {
        for paragraph in paragraphs {
            paragraph.apply(to: scope)
        }
    }
}

/// The homogeneous builder for levels that hold paragraphs — quotations,
/// list items, table cells.
@resultBuilder
public enum BlockContainerBuilder {
    // Partial blocks, not a variadic buildBlock: `if` and `for` hand back
    // [Paragraph], and a block may mix those with single expressions.
    public static func buildBlock() -> [Paragraph] { [] }
    public static func buildPartialBlock(first: Paragraph) -> [Paragraph] { [first] }
    public static func buildPartialBlock(first: [Paragraph]) -> [Paragraph] { first }
    public static func buildPartialBlock(accumulated: [Paragraph], next: Paragraph) -> [Paragraph] { accumulated + [next] }
    public static func buildPartialBlock(accumulated: [Paragraph], next: [Paragraph]) -> [Paragraph] { accumulated + next }
    public static func buildOptional(_ component: [Paragraph]?) -> [Paragraph] { component ?? [] }
    public static func buildEither(first component: [Paragraph]) -> [Paragraph] { component }
    public static func buildEither(second component: [Paragraph]) -> [Paragraph] { component }
    public static func buildArray(_ components: [[Paragraph]]) -> [Paragraph] { components.flatMap { $0 } }
}

/// A horizontal rule at the theme's thickness and colour.
public struct HorizontalRule {
    public init() {}
}

/// A picture in the flow; the bytes decide the format (JPEG or PNG).
public struct Image {
    internal enum Source {
        case data(Data)
        case file(String)
    }

    internal let source: Source
    internal var fit: ImageFitMode = .contain
    internal var rotationDegrees: Int = 0

    public init(data: Data) {
        self.source = .data(data)
    }

    /// The picture in the file at `url`. A JPEG is not held: its header is read
    /// when the document is built, and the file is copied into the PDF when it
    /// is written — so the file must stay as it is until then.
    public init(url: URL) {
        self.source = .file(url.path)
    }

    /// The picture in the file at `path`, as `init(url:)`.
    public init(path: String) {
        self.source = .file(path)
    }

    public func fit(_ mode: ImageFitMode) -> Image {
        var copy = self
        copy.fit = mode
        return copy
    }

    /// Counter-clockwise quarter turns only: 0, 90, 180 or 270.
    public func rotation(_ degrees: Int) -> Image {
        var copy = self
        copy.rotationDegrees = degrees
        return copy
    }
}

/// The next block starts a new page.
public struct PageBreak {
    public init() {}
}
