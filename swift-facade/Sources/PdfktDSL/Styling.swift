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
    public var emojiPolicy: EmojiPolicy
    public var fallbacks: [Font]

    /// - Parameters:
    ///   - font: the face, or `nil` for Helvetica.
    ///   - fontSize: the body size in points.
    ///   - lineHeight: the distance between baselines as a multiple of the size.
    ///   - textColor: `0xRRGGBB`.
    ///   - emojiPolicy: what happens to emoji in the text; `.none` draws them
    ///     with the text's own font, which refuses those it has no glyph for.
    ///   - fallbacks: the fonts a character is drawn from when `font` has no
    ///     glyph for it — the first of them that has one.
    public init(
        font: Font? = nil,
        fontSize: Double = 11,
        lineHeight: Double = 1.2,
        textColor: Int = 0x000000,
        emojiPolicy: EmojiPolicy = .none,
        fallbacks: [Font] = []
    ) {
        self.font = font
        self.fontSize = fontSize
        self.lineHeight = lineHeight
        self.textColor = textColor
        self.emojiPolicy = emojiPolicy
        self.fallbacks = fallbacks
    }

    internal var kotlin: Pdfkt.Theme {
        var family = font.map { FontFamily.companion.of(font: $0.kotlin) } ?? FontFamily.companion.HELVETICA
        if !fallbacks.isEmpty { family = family.withFallbacks(fallbacks: fallbacks.map { $0.kotlin }) }
        return ObjCInteropKt.themeOf(
            family: family,
            fontSize: fontSize,
            lineHeight: lineHeight,
            textColor: Int32(textColor)
        ).withEmojiPolicy(policy: emojiPolicy.kotlin)
    }
}

/// What happens to an emoji in the text.
public enum EmojiPolicy {
    /// Drawn with the text's own font, like any other character — refused,
    /// naming it, when the font has no glyph for it.
    case none
    /// Removed from the text, whole.
    case strip
    /// Written out as its code points, `[U+1F44B]`.
    case placeholder
    /// Drawn in colour from a colour-bitmap font (CBDT/CBLC) handed over as bytes.
    case colorFont(data: Data, id: String)
    /// Drawn in colour from pictures your own code renders: `rasterizer` is
    /// asked for each emoji sequence at `emPixels` to the em, and returns its
    /// picture, or `nil` for one it cannot draw — which is refused, naming it.
    /// It is called on the thread that writes the document.
    case rasterized(id: String, emPixels: Int = 128, rasterizer: (String, Int) -> RasterGlyph?)

    internal var kotlin: Pdfkt.EmojiPolicy {
        switch self {
        case .none: return EmojiPolicyNone.shared
        case .strip: return EmojiPolicyStrip.shared
        case .placeholder: return EmojiPolicyPlaceholder.shared
        case let .colorFont(data, id):
            return EmojiPolicyEmbeddedColorFont(fontBytes: data.toKotlinByteArray(), id: id)
        case let .rasterized(id, emPixels, rasterizer):
            return EmojiPolicyRasterized(
                rasterizer: RasterizerBridge(rasterizer),
                id: id,
                emPixels: Int32(emPixels)
            )
        }
    }
}

/// One emoji as a picture: a PNG and where it sits on the baseline, in pixels
/// at the `emPixels` the rasterizer was asked for.
public struct RasterGlyph {
    public var png: Data
    public var width: Int
    public var height: Int
    /// From the pen position to the picture's left edge.
    public var bearingX: Int
    /// From the baseline up to the picture's top edge.
    public var bearingY: Int
    /// How far the pen moves on.
    public var advance: Int

    public init(png: Data, width: Int, height: Int, bearingX: Int, bearingY: Int, advance: Int) {
        self.png = png
        self.width = width
        self.height = height
        self.bearingX = bearingX
        self.bearingY = bearingY
        self.advance = advance
    }

    internal var kotlin: Pdfkt.RasterGlyph {
        Pdfkt.RasterGlyph(
            png: png.toKotlinByteArray(),
            width: Int32(width),
            height: Int32(height),
            bearingX: Int32(bearingX),
            bearingY: Int32(bearingY),
            advance: Int32(advance)
        )
    }
}

/// A Swift closure as the core's rasterizer. One per policy conversion, so a
/// document's emoji font sees one rasterizer throughout.
internal final class RasterizerBridge: NSObject, EmojiRasterizer {
    private let body: (String, Int) -> RasterGlyph?

    init(_ body: @escaping (String, Int) -> RasterGlyph?) {
        self.body = body
    }

    func rasterize(sequence: String, emPixels: Int32) -> Pdfkt.RasterGlyph? {
        body(sequence, Int(emPixels))?.kotlin
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
