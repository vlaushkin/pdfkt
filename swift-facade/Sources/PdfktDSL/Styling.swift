import Foundation
import Pdfkt

/// The paper. Mirrors `io.pdfkt.api.PageSize`.
public enum PageSize {
    case a4
    case a5
    case letter
    case legal
    case custom(width: Double, height: Double) // points

    internal var kotlin: Pdfkt.PageSize {
        switch self {
        case .a4: return PageSizeA4.shared
        case .a5: return PageSizeA5.shared
        case .letter: return PageSizeLetter.shared
        case .legal: return PageSizeLegal.shared
        case .custom(let width, let height):
            return PageSizeCustom(width: Float(width), height: Float(height))
        }
    }
}

/// What is kept clear around the text, in points.
public struct Margins {
    public let top: Double
    public let right: Double
    public let bottom: Double
    public let left: Double

    public init(top: Double, right: Double, bottom: Double, left: Double) {
        self.top = top
        self.right = right
        self.bottom = bottom
        self.left = left
    }

    /// The same inset on every side.
    public static func all(_ value: Double) -> Margins {
        Margins(top: value, right: value, bottom: value, left: value)
    }

    internal var kotlin: Pdfkt.Margins {
        MarginsFixed(top: Float(top), right: Float(right), bottom: Float(bottom), left: Float(left))
    }
}

/// What the document says about itself (`/Info`).
public struct Metadata {
    public var title: String?
    public var author: String?
    public var subject: String?
    public var keywords: [String]

    public init(title: String? = nil, author: String? = nil, subject: String? = nil, keywords: [String] = []) {
        self.title = title
        self.author = author
        self.subject = subject
        self.keywords = keywords
    }

    internal func apply(to scope: KotlinDocumentScope) {
        scope.metadata = Pdfkt.Metadata(
            title: title,
            author: author,
            subject: subject,
            keywords: keywords,
            creator: scope.metadata.creator,
            producer: scope.metadata.producer
        )
    }
}

/// How a paragraph sits in its column.
public enum Alignment {
    case left, center, right, justify

    internal var kotlin: TextAlign {
        switch self {
        case .left: return .left
        case .center: return .center
        case .right: return .right
        case .justify: return .justify
        }
    }
}

/// How a picture fills the space it is given.
public enum ImageFitMode {
    case contain, stretch

    internal var kotlin: ImageFit {
        switch self {
        case .contain: return .contain
        case .stretch: return .stretch
        }
    }
}

/// Which pages a running head or foot stands on.
public enum BandPageSelection {
    case all, firstOnly, exceptFirst

    internal var kotlin: BandPages {
        switch self {
        case .all: return .all
        case .firstOnly: return .firstonly
        case .exceptFirst: return .exceptfirst
        }
    }
}

/// A list's marker.
public enum ListMarker {
    case bullet, circle, square, dash
    case decimal, lowerAlpha, upperAlpha, lowerRoman, upperRoman

    internal var kotlin: ListMarkerStyle {
        switch self {
        case .bullet: return .bullet
        case .circle: return .circle
        case .square: return .square
        case .dash: return .dash
        case .decimal: return .decimal
        case .lowerAlpha: return .loweralpha
        case .upperAlpha: return .upperalpha
        case .lowerRoman: return .lowerroman
        case .upperRoman: return .upperroman
        }
    }
}
