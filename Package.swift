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
            url: "https://github.com/vlaushkin/pdfkt/releases/download/v0.0.6/Pdfkt.xcframework.zip",
            checksum: "befd8342ab817d15aabf80b2f098de510c791326e90a5d6eead382f5bd1a109e"
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
