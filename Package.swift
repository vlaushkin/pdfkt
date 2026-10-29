// swift-tools-version:5.9
import PackageDescription

// The Swift face of pdfkt: the PdfktDSL facade over the Kotlin core,
// paired with docxkt's DocxktDSL the way the Kotlin DSLs are paired.
//
// The binary target is the XCFramework attached to the GitHub release the
// tag names. To develop against a locally assembled binary instead, build
// it (./gradlew :core:assemblePdfktReleaseXCFramework) and point the
// target at path: "core/build/XCFrameworks/release/Pdfkt.xcframework".
// On re-release: re-zip, `swift package compute-checksum`, update both
// url: and checksum:.
let package = Package(
    name: "pdfkt",
    platforms: [
        .iOS(.v13),
        .macOS(.v11),
    ],
    products: [
        .library(name: "PdfktDSL", targets: ["PdfktDSL"]),
    ],
    targets: [
        .binaryTarget(
            name: "Pdfkt",
            url: "https://github.com/vlaushkin/pdfkt/releases/download/v0.0.5/Pdfkt.xcframework.zip",
            checksum: "74ad02f187b746b52b671e4d195c518f341ff06a51f391694d40db9f203e1634"
        ),
        .target(
            name: "PdfktDSL",
            dependencies: ["Pdfkt"],
            path: "swift-facade/Sources/PdfktDSL"
        ),
        .testTarget(
            name: "PdfktDSLTests",
            dependencies: ["PdfktDSL"],
            path: "swift-facade/Tests/PdfktDSLTests"
        ),
    ]
)
