import Foundation
import Pdfkt

/// An unordered list.
public struct BulletList {
    internal let items: [Item]
    internal var markerStyle: ListMarker = .bullet

    public init(@ListBuilder content: () -> [Item]) {
        self.items = content()
    }

    public func marker(_ style: ListMarker) -> BulletList {
        var copy = self
        copy.markerStyle = style
        return copy
    }

    internal func applyToList(_ scope: KotlinListScope) {
        for item in items { item.apply(to: scope) }
    }
}

/// An ordered list.
public struct NumberedList {
    internal let items: [Item]
    internal var markerStyle: ListMarker = .decimal
    internal var startNumber: Int = 1

    public init(@ListBuilder content: () -> [Item]) {
        self.items = content()
    }

    public func marker(_ style: ListMarker) -> NumberedList {
        var copy = self
        copy.markerStyle = style
        return copy
    }

    public func start(_ number: Int) -> NumberedList {
        var copy = self
        copy.startNumber = number
        return copy
    }

    internal func applyToList(_ scope: KotlinListScope) {
        for item in items { item.apply(to: scope) }
    }
}

/// One list item: a plain line, or paragraphs of its own.
public struct Item {
    private let text: String?
    private let paragraphs: [Paragraph]?

    public init(_ text: String) {
        self.text = text
        self.paragraphs = nil
    }

    public init(@BlockContainerBuilder content: () -> [Paragraph]) {
        self.text = nil
        self.paragraphs = content()
    }

    internal func apply(to scope: KotlinListScope) {
        if let text {
            scope.item(text: text)
        } else {
            scope.item { itemScope in
                for paragraph in self.paragraphs ?? [] {
                    paragraph.apply(to: itemScope)
                }
            }
        }
    }
}

@resultBuilder
public enum ListBuilder {
    // Partial blocks, not a variadic buildBlock: `if` and `for` hand back
    // [Item], and a block may mix those with single expressions.
    public static func buildBlock() -> [Item] { [] }
    public static func buildPartialBlock(first: Item) -> [Item] { [first] }
    public static func buildPartialBlock(first: [Item]) -> [Item] { first }
    public static func buildPartialBlock(accumulated: [Item], next: Item) -> [Item] { accumulated + [next] }
    public static func buildPartialBlock(accumulated: [Item], next: [Item]) -> [Item] { accumulated + next }
    public static func buildOptional(_ component: [Item]?) -> [Item] { component ?? [] }
    public static func buildEither(first component: [Item]) -> [Item] { component }
    public static func buildEither(second component: [Item]) -> [Item] { component }
    public static func buildArray(_ components: [[Item]]) -> [Item] { components.flatMap { $0 } }
}
