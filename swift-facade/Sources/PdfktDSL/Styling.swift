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

/// A font program handed over as the bytes of a TrueType file — what a
/// document needs to be set in anything beyond the Latin of the standard 14
/// fonts: Cyrillic, Greek, accented Latin past WinAnsi.
///
/// The `id` names the program within one document; two `Font`s with the same
/// id are the same font. Only the glyphs a document draws are embedded.
public struct Font {
    internal let kotlin: FontRef

    public init(data: Data, id: String) {
        self.kotlin = FontRefSupplied(bytes: data.toKotlinByteArray(), id: id)
    }
}

/// How the document is set: the face every paragraph and heading is drawn in,
/// the body size, how far apart the lines are, and the colour of the text.
///
/// A `Text` that states its own size or colour overrides the theme for itself.
/// `bold()` and `italic()` draw the same face when the theme has one font, as
/// a single TrueType file has no other weight in it.
public struct Theme {
    public var font: Font?
    public var fontSize: Double
    public var lineHeight: Double
    public var textColor: Int

    /// - Parameters:
    ///   - font: the face, or `nil` for Helvetica.
    ///   - fontSize: the body size in points.
    ///   - lineHeight: the distance between baselines as a multiple of the size.
    ///   - textColor: `0xRRGGBB`.
    public init(font: Font? = nil, fontSize: Double = 11, lineHeight: Double = 1.2, textColor: Int = 0x000000) {
        self.font = font
        self.fontSize = fontSize
        self.lineHeight = lineHeight
        self.textColor = textColor
    }

    internal var kotlin: Pdfkt.Theme {
        let family = font.map { FontFamily.companion.of(font: $0.kotlin) } ?? FontFamily.companion.HELVETICA
        return ObjCInteropKt.themeOf(
            family: family,
            fontSize: fontSize,
            lineHeight: lineHeight,
            textColor: Int32(textColor)
        )
    }
}

/// Where a page's content sits top to bottom when it does not fill the page.
public enum VerticalAlignment {
    case top, middle, bottom

    internal var kotlin: VerticalAlign {
        switch self {
        case .top: return .top
        case .middle: return .middle
        case .bottom: return .bottom
        }
    }
}
