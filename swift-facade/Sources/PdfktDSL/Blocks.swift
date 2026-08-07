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
            scope.paragraph(align: paragraph.align.kotlin, block: paragraph.applyToInline)
        }
    }
}

/// The homogeneous builder for levels that hold paragraphs — quotations,
/// list items, table cells.
@resultBuilder
public enum BlockContainerBuilder {
    public static func buildBlock(_ components: Paragraph...) -> [Paragraph] { components }
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
    internal let data: Data
    internal var fit: ImageFitMode = .contain
    internal var rotationDegrees: Int = 0

    public init(data: Data) {
        self.data = data
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
