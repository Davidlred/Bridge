// swift-tools-version:5.9
// Olm for encrypted DMs: the bridge_crypto Rust library (vodozemac) and its generated Swift bindings.
// Build the binary first with crypto/build-ios.sh (CI does this before xcodegen). The bindings are generated code,
// so they compile in Swift 5 language mode here rather than under the app's Swift 6 strict concurrency.
import PackageDescription

let package = Package(
    name: "BridgeCrypto",
    platforms: [.iOS(.v17)],
    products: [.library(name: "BridgeCrypto", targets: ["BridgeCrypto"])],
    targets: [
        .binaryTarget(name: "bridge_cryptoFFI", path: "bridge_cryptoFFI.xcframework"),
        .target(name: "BridgeCrypto", dependencies: ["bridge_cryptoFFI"], path: "Sources/BridgeCrypto"),
    ],
    swiftLanguageVersions: [.v5]
)
