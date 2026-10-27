// swift-tools-version: 6.1
import PackageDescription

let package = Package(
  name: "ExpoConversationsBoundary",
  platforms: [.iOS("17.4")],
  products: [.library(name: "ExpoConversationsBoundary", targets: ["ExpoConversationsBoundary"])],
  dependencies: [
    // The SDK manifest reads VERSION beside itself. Check out sdk-revision before
    // resolving so SwiftPM evaluates that manifest from its complete source tree.
    .package(path: ".sdk/ios-voip-sdk")
  ],
  targets: [
    .target(name: "ExpoConversationsBoundary", dependencies: [
      .product(name: "VoIPSDK", package: "ios-voip-sdk")
    ]),
    .testTarget(name: "ExpoConversationsBoundaryTests", dependencies: [
      "ExpoConversationsBoundary",
      .product(name: "VoIPSDK", package: "ios-voip-sdk")
    ])
  ],
  swiftLanguageModes: [.v6]
)
