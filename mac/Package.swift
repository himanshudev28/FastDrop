// swift-tools-version: 6.0
// FastDrop for Mac. Built with SwiftPM and the Command Line Tools only (no Xcode):
//   scripts/build-app.sh            -> build/FastDrop.app
//   swift run fastdrop-cli ...      -> protocol test client (no UI)
import PackageDescription

let swift5: [SwiftSetting] = [.swiftLanguageMode(.v5)]

let package = Package(
    name: "FastDrop",
    platforms: [.macOS(.v14)],
    products: [
        .executable(name: "FastDrop", targets: ["FastDrop"]),
        .executable(name: "fastdrop-cli", targets: ["FastDropCLI"]),
    ],
    targets: [
        // Wire protocol, discovery, receiver and sender. Speaks the same protocol as the Android app.
        .target(name: "FastDropCore", swiftSettings: swift5),

        // The SwiftUI app.
        .executableTarget(
            name: "FastDrop",
            dependencies: ["FastDropCore"],
            swiftSettings: swift5,
            linkerSettings: [
                .linkedFramework("AppKit"),
                .linkedFramework("CoreImage"),
                .linkedFramework("UserNotifications"),
                .linkedFramework("UniformTypeIdentifiers"),
                .linkedFramework("AVFoundation"),
                .linkedFramework("Vision"),
            ]
        ),

        // Headless send / receive / hello, used to test the core against tools/fdtest.py and real phones.
        .executableTarget(name: "FastDropCLI", dependencies: ["FastDropCore"], swiftSettings: swift5),
    ]
)
