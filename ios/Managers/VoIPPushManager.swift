import Foundation
import PushKit

/// Manages VoIP push notification registration and token handling.
///
/// This singleton handles:
/// - Registering for VoIP push notifications via PushKit
/// - Storing and exposing the current push token
/// - Emitting events when the token updates or is invalidated
///
/// VoIP pushes are used to receive incoming call notifications and must report
/// a call to CallKit immediately upon receipt.
final class VoIPPushManager: NSObject {
  static let shared = VoIPPushManager()

  /// The PushKit registry for VoIP notifications.
  private var pushRegistry: PKPushRegistry?
  // PKPushRegistry's delegate is weak. Keep the SDK compatibility adapter alive.
  private var pushDelegate: CallKitVoIPPushDelegate?

  enum OptionalPushPolicy: String {
    case reportAll
    case ignoreInvalidPayloads
  }

  /// Valid invitations always report. Malformed optional pushes are ignored;
  /// reportAll retains the previous placeholder behavior as an explicit opt-in.
  private(set) var optionalPushPolicy: OptionalPushPolicy = {
    let value =
      Bundle.main.object(forInfoDictionaryKey: "ExpoCallKitTelecomOptionalVoIPPushPolicy")
      as? String
    return value.flatMap(OptionalPushPolicy.init(rawValue:)) ?? .ignoreInvalidPayloads
  }()

  /// The current VoIP push token, if available.
  private(set) var token: String?

  private override init() {
    super.init()
  }

  /// Registers for VoIP push notifications.
  ///
  /// This creates a PKPushRegistry and requests VoIP push credentials.
  /// The token will be available via the `token` property once received,
  /// and a `VoIPPushTokenUpdatedEvent` will be emitted.
  func register() {
    guard pushRegistry == nil else {
      Log.voipPush.debug("VoIP push already registered")
      return
    }

    Log.voipPush.debug("Registering for VoIP push notifications")

    let registry = PKPushRegistry(queue: .main)
    let delegate = CallKitVoIPPushDelegate(receiver: self)
    pushDelegate = delegate
    registry.delegate = delegate
    registry.desiredPushTypes = [.voIP]
    pushRegistry = registry
  }

  /// Updates the stored token and emits an event to JS.
  ///
  /// - Parameter newToken: The new token string, or nil if invalidated.
  @MainActor
  func updateToken(_ newToken: String?) {
    let oldToken = token
    token = newToken

    if oldToken != newToken {
      Log.voipPush.debug("VoIP token updated - hasToken: \(newToken != nil)")
      CallEventEmitter.shared.send(VoIPPushTokenUpdatedEvent(token: newToken))
    }
  }
}
