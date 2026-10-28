import Foundation
import Pdfkt

/// A table of rows and cells, paired with docxkt's `Table`.
///
/// The first `Row` marked `header:` repeats when the table breaks across
/// pages — pass it through `Table { Row(header: true) { … } … }` or the
/// plain `Row { … }` for body rows.
public struct Table {
    internal let rows: [Row]

    public init(@TableBuilder content: () -> [Row]) {
        self.rows = content()
    }

    internal func applyToTable(_ scope: KotlinTableScope) {
        for row in rows {
            if row.isHeader {
                scope.header(block: row.applyToRow)
            } else {
                scope.row(block: row.applyToRow)
            }
        }
    }
}

/// One row of cells.
public struct Row {
    internal let cells: [Cell]
    internal let isHeader: Bool

    public init(header: Bool = false, @RowBuilder content: () -> [Cell]) {
        self.isHeader = header
        self.cells = content()
    }

    internal func applyToRow(_ scope: KotlinRowScope) {
        for cell in cells { cell.apply(to: scope) }
    }
}

/// One cell: a plain string, or paragraphs of its own.
public struct Cell {
    private let text: String?
    private let paragraphs: [Paragraph]?
    private var align: Alignment = .left

    public init(_ text: String) {
        self.text = text
        self.paragraphs = nil
    }

    public init(@BlockContainerBuilder content: () -> [Paragraph]) {
        self.text = nil
        self.paragraphs = content()
    }

    public func alignment(_ value: Alignment) -> Cell {
        var copy = self
        copy.align = value
        return copy
    }

    internal func apply(to scope: KotlinRowScope) {
        if let text {
            scope.cell(text: text, align: align.kotlin, verticalAlign: .top, backgroundColor: nil)
        } else {
            scope.cell(align: align.kotlin, verticalAlign: .top, backgroundColor: nil) { cellScope in
                for paragraph in self.paragraphs ?? [] {
                    paragraph.apply(to: cellScope)
                }
            }
        }
    }
}

@resultBuilder
public enum TableBuilder {
    // Partial blocks, not a variadic buildBlock: `if` and `for` hand back
    // [Row], and a block may mix those with single expressions.
    public static func buildBlock() -> [Row] { [] }
    public static func buildPartialBlock(first: Row) -> [Row] { [first] }
    public static func buildPartialBlock(first: [Row]) -> [Row] { first }
    public static func buildPartialBlock(accumulated: [Row], next: Row) -> [Row] { accumulated + [next] }
    public static func buildPartialBlock(accumulated: [Row], next: [Row]) -> [Row] { accumulated + next }
    public static func buildOptional(_ component: [Row]?) -> [Row] { component ?? [] }
    public static func buildEither(first component: [Row]) -> [Row] { component }
    public static func buildEither(second component: [Row]) -> [Row] { component }
    public static func buildArray(_ components: [[Row]]) -> [Row] { components.flatMap { $0 } }
}

@resultBuilder
public enum RowBuilder {
    // Partial blocks, not a variadic buildBlock: `if` and `for` hand back
    // [Cell], and a block may mix those with single expressions.
    public static func buildBlock() -> [Cell] { [] }
    public static func buildPartialBlock(first: Cell) -> [Cell] { [first] }
    public static func buildPartialBlock(first: [Cell]) -> [Cell] { first }
    public static func buildPartialBlock(accumulated: [Cell], next: Cell) -> [Cell] { accumulated + [next] }
    public static func buildPartialBlock(accumulated: [Cell], next: [Cell]) -> [Cell] { accumulated + next }
    public static func buildOptional(_ component: [Cell]?) -> [Cell] { component ?? [] }
    public static func buildEither(first component: [Cell]) -> [Cell] { component }
    public static func buildEither(second component: [Cell]) -> [Cell] { component }
    public static func buildArray(_ components: [[Cell]]) -> [Cell] { components.flatMap { $0 } }
}
