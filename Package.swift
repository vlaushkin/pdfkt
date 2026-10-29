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
            url: "https://github.com/vlaushkin/pdfkt/releases/download/v0.0.7/Pdfkt.xcframework.zip",
            checksum: "470b5a5f64d08fc870b43d47dfbb0887dd6ab02444d0fb7dc17cc8f618c998bd"
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
