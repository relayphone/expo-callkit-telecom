import Foundation
import IosVoipSDK

/// The module's settings, which the config plugin writes to the app's Info.plist.
struct Settings: Sendable {
  /// How the SDK presents and controls calls.
  let configuration: VoIPConfiguration

  /// The `mediaAdapter` plugin option: `webrtc`, `livekit` or `none`.
  let mediaAdapter: String

  /// Whether a start or join that no handler received is placed anyway.
  let acceptsUnhandledActions: Bool

  /// The settings in the main bundle's Info.plist.
  static let main = Settings(Bundle.main.infoDictionary ?? [:])

  init(_ info: [String: Any]) {
    func seconds(_ key: String) -> Duration? {
      (info[key] as? Int).map { .seconds(max(1, $0)) }
    }
    let defaults = Deadlines()
    let ringtone = info["ExpoCallKitTelecomDefaultRingtone"] as? String
    let icon = (info["ExpoCallKitTelecomIconTemplate"] as? String).flatMap {
      Bundle.main.url(forResource: $0, withExtension: nil)
    }
    configuration = VoIPConfiguration(
      supportsVideo: true,
      includesCallsInRecents: info["ExpoCallKitTelecomIncludesCallsInRecents"] as? Bool ?? true,
      ringtoneSound: ringtone == "default" ? nil : ringtone,
      iconTemplateImageData: icon.flatMap { try? Data(contentsOf: $0) },
      deadlines: Deadlines(
        ring: seconds("ExpoCallKitTelecomIncomingCallTimeout") ?? defaults.ring,
        connect: seconds("ExpoCallKitTelecomFulfillAnswerCallTimeout") ?? defaults.connect))
    mediaAdapter = info["ExpoCallKitTelecomMediaAdapter"] as? String ?? "webrtc"
    acceptsUnhandledActions = info["ExpoCallKitTelecomUnhandledAction"] as? String == "accept"
  }

  /// The TypeScript API's `ConversationCapabilities`, the same for every call.
  var capabilities: JSONValue {
    .object([
      "hold": .bool(configuration.capabilities.contains(.holding)),
      "merge": .bool(configuration.capabilities.contains(.merging)),
      "tones": .bool(configuration.capabilities.contains(.playingTones)),
      "video": .bool(configuration.supportsVideo),
    ])
  }
}
