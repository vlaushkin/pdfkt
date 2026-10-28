import Foundation
import Pdfkt

/// Pages on paper of their own: the content starts a new page of `pageSize`,
/// continues onto as many more of the same as it needs, and whatever follows
/// the section is back on the document's paper.
///
/// ```swift
/// Section(pageSize: .custom(width: 595.276, height: 595.276), verticalAlignment: .middle) {
///     Paragraph { Text("Лето").fontSize(29.8) }.alignment(.center)
/// }
/// Section(pageSize: .custom(width: 640, height: 480), margins: .all(0)) {
///     Image(data: photo)
/// }
/// ```
///
/// A section always starts a page and the content after it starts another, so
/// a `PageBreak` next to one adds no blank page. The document's header and
/// footer are drawn on its pages too.
public struct Section {
    internal let pageSize: PageSize?
    internal let margins: Margins?
    internal let verticalAlignment: VerticalAlignment
    internal let blocks: [SectionBlock]

    /// - Parameters:
    ///   - pageSize: the paper, or `nil` for the document's.
    ///   - margins: what is kept clear around the content, or `nil` for the
    ///     document's. `.all(0)` lets the content reach the edges.
    ///   - verticalAlignment: where a page's content sits when it does not
    ///     fill the page; `.middle` centres a title page.
    public init(
        pageSize: PageSize? = nil,
        margins: Margins? = nil,
        verticalAlignment: VerticalAlignment = .top,
        @SectionBuilder content: () -> [SectionBlock]
    ) {
        self.pageSize = pageSize
        self.margins = margins
        self.verticalAlignment = verticalAlignment
        self.blocks = content()
    }

    internal func apply(to scope: KotlinDocumentScope) {
        scope.section(
            pageSize: pageSize?.kotlin ?? scope.pageSize,
            margins: margins?.kotlin ?? scope.margins,
            verticalAlign: verticalAlignment.kotlin
        ) { sectionScope in
            for block in blocks { block.apply(sectionScope) }
        }
    }
}

/// One block of a `Section { }` — type-erased onto the section scope.
public struct SectionBlock {
    internal let apply: (KotlinSectionScope) -> Void
    internal init(_ apply: @escaping (KotlinSectionScope) -> Void) { self.apply = apply }
}

@resultBuilder
public enum SectionBuilder {
    public static func buildBlock(_ components: SectionBlock...) -> [SectionBlock] { components }
    public static func buildOptional(_ component: [SectionBlock]?) -> [SectionBlock] { component ?? [] }
    public static func buildEither(first component: [SectionBlock]) -> [SectionBlock] { component }
    public static func buildEither(second component: [SectionBlock]) -> [SectionBlock] { component }
    public static func buildArray(_ components: [[SectionBlock]]) -> [SectionBlock] { components.flatMap { $0 } }

    public static func buildExpression(_ paragraph: Paragraph) -> SectionBlock {
        SectionBlock { scope in paragraph.apply(to: scope) }
    }

    public static func buildExpression(_ heading: Heading) -> SectionBlock {
        SectionBlock { scope in
            if let text = heading.text {
                scope.heading(level: Int32(heading.level), text: text)
            } else {
                scope.heading(level: Int32(heading.level), block: heading.applyToInline)
            }
        }
    }

    public static func buildExpression(_ table: Table) -> SectionBlock {
        SectionBlock { scope in scope.table(block: table.applyToTable) }
    }

    public static func buildExpression(_ list: BulletList) -> SectionBlock {
        SectionBlock { scope in
            scope.bulletList(
                markerStyle: list.markerStyle.kotlin,
                indentPerLevel: BlockSinkKt.DEFAULT_LIST_INDENT,
                block: list.applyToList
            )
        }
    }

    public static func buildExpression(_ list: NumberedList) -> SectionBlock {
        SectionBlock { scope in
            scope.numberedList(
                startNumber: Int32(list.startNumber),
                markerStyle: list.markerStyle.kotlin,
                indentPerLevel: BlockSinkKt.DEFAULT_LIST_INDENT,
                block: list.applyToList
            )
        }
    }

    public static func buildExpression(_ code: CodeBlock) -> SectionBlock {
        SectionBlock { scope in
            scope.codeBlock(text: code.text, backgroundColor: nil, fontSize: nil, padding: code.padding)
        }
    }

    public static func buildExpression(_ quote: Blockquote) -> SectionBlock {
        SectionBlock { scope in
            scope.blockquote(
                barColor: nil,
                barWidth: BlockSinkKt.DEFAULT_QUOTE_BAR_WIDTH,
                indentLeft: BlockSinkKt.DEFAULT_QUOTE_INDENT,
                block: quote.applyToQuote
            )
        }
    }

    public static func buildExpression(_ rule: HorizontalRule) -> SectionBlock {
        SectionBlock { scope in scope.hr(thickness: nil, color: nil, verticalSpacing: nil) }
    }

    public static func buildExpression(_ image: Image) -> SectionBlock {
        SectionBlock { scope in
            scope.image(
                bytes: image.data.toKotlinByteArray(),
                fit: image.fit.kotlin,
                rotationDegrees: Int32(image.rotationDegrees)
            )
        }
    }

    public static func buildExpression(_ pageBreak: PageBreak) -> SectionBlock {
        SectionBlock { scope in scope.pageBreak() }
    }
}
