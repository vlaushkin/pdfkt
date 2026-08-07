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
            url: "https://github.com/vlaushkin/pdfkt/releases/download/v0.0.1/Pdfkt.xcframework.zip",
            checksum: "681e070f4b518e3ba9addac9792f9719952f7af9a9d3fd6d66f411afd2f1bff6"
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
