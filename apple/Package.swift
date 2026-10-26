// swift-tools-version: 6.1
import PackageDescription

let package = Package(
  name: "ExpoConversationsBoundary",
  platforms: [.iOS("17.4")],
  products: [.library(name: "ExpoConversationsBoundary", targets: ["ExpoConversationsBoundary"])],
  dependencies: [
    .package(url: "https://github.com/ziahamza/ios-voip-sdk.git",
             revision: "52c2eaba23f61e67e9086a7b2328cf9e293da4ea")
  ],
  targets: [
    .target(name: "ExpoConversationsBoundary", dependencies: [
      .product(name: "VoIPSDK", package: "ios-voip-sdk")
    ])
  ],
  swiftLanguageModes: [.v6]
)
