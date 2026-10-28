import Foundation
import Pdfkt

/// The running head: blocks drawn in the strip above the content on the
/// pages its selection names.
public struct Header {
    internal let blocks: [ColophonBlock]
    internal let height: Float
    internal let pages: BandPageSelection

    public init(
        height: Double? = nil,
        pages: BandPageSelection = .all,
        @ColophonBuilder content: () -> [ColophonBlock]
    ) {
        self.blocks = content()
        self.height = height.map { Float($0) } ?? DocumentScopeKt.DEFAULT_BAND_HEIGHT
        self.pages = pages
    }

    internal func applyToColophon(_ scope: KotlinColophonScope) {
        for block in blocks { block.apply(scope) }
    }
}

/// The running foot — the same strip, below the content.
public struct Footer {
    internal let blocks: [ColophonBlock]
    internal let height: Float
    internal let pages: BandPageSelection

    public init(
        height: Double? = nil,
        pages: BandPageSelection = .all,
        @ColophonBuilder content: () -> [ColophonBlock]
    ) {
        self.blocks = content()
        self.height = height.map { Float($0) } ?? DocumentScopeKt.DEFAULT_BAND_HEIGHT
        self.pages = pages
    }

    internal func applyToColophon(_ scope: KotlinColophonScope) {
        for block in blocks { block.apply(scope) }
    }
}

/// One thing inside a colophon — erased onto the colophon scope, which also
/// knows which page it is on.
public struct ColophonBlock {
    internal let apply: (KotlinColophonScope) -> Void
    internal init(_ apply: @escaping (KotlinColophonScope) -> Void) { self.apply = apply }
}

@resultBuilder
public enum ColophonBuilder {
    public static func buildBlock(_ components: ColophonBlock...) -> [ColophonBlock] { components }
    public static func buildOptional(_ component: [ColophonBlock]?) -> [ColophonBlock] { component ?? [] }
    public static func buildEither(first component: [ColophonBlock]) -> [ColophonBlock] { component }
    public static func buildEither(second component: [ColophonBlock]) -> [ColophonBlock] { component }
    public static func buildArray(_ components: [[ColophonBlock]]) -> [ColophonBlock] { components.flatMap { $0 } }

    public static func buildExpression(_ paragraph: Paragraph) -> ColophonBlock {
        ColophonBlock { scope in
            paragraph.apply(to: scope)
        }
    }

    public static func buildExpression(_ pageOfPages: PageOfPages) -> ColophonBlock {
        ColophonBlock { scope in
            scope.paragraph(
                text: scope.pageOfPages(separator: pageOfPages.separator),
                align: pageOfPages.align.kotlin,
                indentLeft: 0,
                indentRight: 0,
                spacingBefore: nil,
                spacingAfter: nil
            )
        }
    }
}

/// "3 / 12" — the page and the count, said by the page it stands on.
/// There are no words in it, so no language is imposed on the document.
public struct PageOfPages {
    internal var align: Alignment
    internal var separator: String

    public init(alignment: Alignment = .right, separator: String = " / ") {
        self.align = alignment
        self.separator = separator
    }
}
