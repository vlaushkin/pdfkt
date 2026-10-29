import Foundation
import Pdfkt

/// A paragraph of inline runs, paired with docxkt's `Paragraph`.
public struct Paragraph {
    internal let runs: [Run]
    internal var align: Alignment = .start
    internal var direction: TextDirection = .auto
    internal var indentLeft: Double = 0
    internal var indentRight: Double = 0
    internal var spacingBefore: Double?
    internal var spacingAfter: Double?

    public init(@ParagraphBuilder content: () -> [Run]) {
        self.runs = content()
    }

    public func alignment(_ value: Alignment) -> Paragraph {
        var copy = self
        copy.align = value
        return copy
    }

    /// Which way it runs; `.auto` unless the text says otherwise.
    public func direction(_ value: TextDirection) -> Paragraph {
        var copy = self
        copy.direction = value
        return copy
    }

    /// How far in from the left and the right of the column its lines run —
    /// a right-aligned line that stops short of the edge says `right:`.
    public func indent(left: Double = 0, right: Double = 0) -> Paragraph {
        var copy = self
        copy.indentLeft = left
        copy.indentRight = right
        return copy
    }

    /// The gaps above and below it, in points. One left out is the theme's.
    public func spacing(before: Double? = nil, after: Double? = nil) -> Paragraph {
        var copy = self
        if let before { copy.spacingBefore = before }
        if let after { copy.spacingAfter = after }
        return copy
    }

    internal func applyToInline(_ scope: KotlinInlineScope) {
        for run in runs { run.apply(scope) }
    }

    internal func apply(to sink: any BlockSink) {
        ObjCInteropKt.spacedParagraph(
            sink,
            align: align.kotlin,
            indentLeft: indentLeft,
            indentRight: indentRight,
            spacingBefore: spacingBefore.map { KotlinDouble(value: $0) },
            spacingAfter: spacingAfter.map { KotlinDouble(value: $0) },
            direction: direction.kotlin,
            block: applyToInline
        )
    }
}

/// One inline thing inside a `Paragraph { }` — type-erased onto the inline scope.
public struct Run {
    internal let apply: (KotlinInlineScope) -> Void
    internal init(_ apply: @escaping (KotlinInlineScope) -> Void) { self.apply = apply }
}

@resultBuilder
public enum ParagraphBuilder {
    // Partial blocks, not a variadic buildBlock: `if` and `for` hand back
    // [Run], and a block may mix those with single expressions.
    public static func buildBlock() -> [Run] { [] }
    public static func buildPartialBlock(first: Run) -> [Run] { [first] }
    public static func buildPartialBlock(first: [Run]) -> [Run] { first }
    public static func buildPartialBlock(accumulated: [Run], next: Run) -> [Run] { accumulated + [next] }
    public static func buildPartialBlock(accumulated: [Run], next: [Run]) -> [Run] { accumulated + next }
    public static func buildOptional(_ component: [Run]?) -> [Run] { component ?? [] }
    public static func buildEither(first component: [Run]) -> [Run] { component }
    public static func buildEither(second component: [Run]) -> [Run] { component }
    public static func buildArray(_ components: [[Run]]) -> [Run] { components.flatMap { $0 } }

    public static func buildExpression(_ text: Text) -> Run {
        Run { scope in text.applyToInline(scope) }
    }

    public static func buildExpression(_ link: Link) -> Run {
        Run { scope in
            scope.link(href: link.href) { linkScope in
                for text in link.texts { text.applyToInline(linkScope) }
            }
        }
    }

    public static func buildExpression(_ br: Br) -> Run {
        Run { scope in scope.br(soft: br.soft) }
    }
}

/// A run of text with its style, modifier-chained the SwiftUI way.
public struct Text {
    internal let value: String
    private var isBold = false
    private var isItalic = false
    private var isCode = false
    private var isStrike = false
    private var isUnderline = false
    private var colorValue: Int?
    private var fontSizePoints: Double?

    public init(_ value: String) {
        self.value = value
    }

    public func bold() -> Text { var copy = self; copy.isBold = true; return copy }
    public func italic() -> Text { var copy = self; copy.isItalic = true; return copy }
    public func code() -> Text { var copy = self; copy.isCode = true; return copy }
    public func strike() -> Text { var copy = self; copy.isStrike = true; return copy }
    public func underline() -> Text { var copy = self; copy.isUnderline = true; return copy }

    /// `0xCC0000`-style RGB.
    public func color(_ rgb: Int) -> Text { var copy = self; copy.colorValue = rgb; return copy }

    /// Font size in points.
    public func fontSize(_ points: Double) -> Text { var copy = self; copy.fontSizePoints = points; return copy }

    private var hasOverrides: Bool {
        isBold || isItalic || isCode || isStrike || isUnderline || colorValue != nil || fontSizePoints != nil
    }

    internal func applyToInline(_ scope: KotlinInlineScope) {
        guard hasOverrides else {
            scope.text(value: value)
            return
        }
        scope.text(value: value) { [self] style in
            if isBold { style.bold = true }
            if isItalic { style.italic = true }
            if isCode { style.code = true }
            if isStrike { style.strike = true }
            if isUnderline { style.underline = true }
            if let colorValue { style.color(rgb: Int32(colorValue)) }
            if let fontSizePoints { style.fontSize(points: fontSizePoints) }
        }
    }
}

/// A hyperlink around its texts.
public struct Link {
    internal let href: String
    internal let texts: [Text]

    public init(_ href: String, @LinkBuilder content: () -> [Text]) {
        self.href = href
        self.texts = content()
    }
}

@resultBuilder
public enum LinkBuilder {
    // Partial blocks, not a variadic buildBlock: `if` and `for` hand back
    // [Text], and a block may mix those with single expressions.
    public static func buildBlock() -> [Text] { [] }
    public static func buildPartialBlock(first: Text) -> [Text] { [first] }
    public static func buildPartialBlock(first: [Text]) -> [Text] { first }
    public static func buildPartialBlock(accumulated: [Text], next: Text) -> [Text] { accumulated + [next] }
    public static func buildPartialBlock(accumulated: [Text], next: [Text]) -> [Text] { accumulated + next }
    public static func buildOptional(_ component: [Text]?) -> [Text] { component ?? [] }
    public static func buildEither(first component: [Text]) -> [Text] { component }
    public static func buildEither(second component: [Text]) -> [Text] { component }
    public static func buildArray(_ components: [[Text]]) -> [Text] { components.flatMap { $0 } }
    public static func buildExpression(_ text: Text) -> Text { text }
}

/// A line break; `soft: true` breaks the line without ending the paragraph's
/// justification unit.
public struct Br {
    internal let soft: Bool
    public init(soft: Bool = false) { self.soft = soft }
}
