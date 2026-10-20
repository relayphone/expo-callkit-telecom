import Foundation
import PushKit

extension VoIPPushManager: CallKitVoIPPushReceiver {
  /// Called when push credentials are updated.
  ///
  /// Converts the token data to a hex string and stores it for JS access.
  nonisolated func handleVoIPCredentials(_ pushCredentials: PKPushCredentials) {
    let tokenData = pushCredentials.token
    let tokenString = tokenData.map { String(format: "%02x", $0) }.joined()

    Task { @MainActor in
      updateToken(tokenString)
    }
  }

  /// Called when push credentials are invalidated.
  ///
  /// Clears the stored token and notifies JS.
  nonisolated func invalidateVoIPCredentials() {
    Log.voipPush.debug("VoIP push token invalidated")

    Task { @MainActor in
      updateToken(nil)
    }
  }

  /// Called when a VoIP push notification is received.
  ///
  /// Required pushes report immediately, before calling completion. On optional
  /// pushes, only malformed payloads may be skipped by the configured policy.
  /// The payload is expected to wrap an `IncomingCallEvent` under the top-level
  /// key `"incomingCall"`, matching `IncomingCallEventParser`.
  ///
  /// - Parameters:
  ///   - payload: The push payload containing the incoming call event.
  ///   - mustReport: Apple's reporting obligation; legacy delivery supplies true.
  ///   - completion: Completion handler that must be called after processing.
  nonisolated func handleVoIPPayload(
    _ payload: PKPushPayload,
    mustReport: Bool,
    completion: @escaping () -> Void
  ) {
    let dictionaryPayload = payload.dictionaryPayload

    Log.voipPush.debug("Received VoIP push - payload keys: \(dictionaryPayload.keys)")

    guard let event = IncomingCallEventParser.parse(from: dictionaryPayload) else {
      if !mustReport && optionalPushPolicy == .ignoreInvalidPayloads {
        Log.voipPush.debug("Ignoring optional VoIP push with invalid payload")
        completion()
        return
      }
      // The parser logs which guard rejected the payload. We must still report
      // a call to CallKit even on parse failure, or the app will be terminated.
      Log.voipPush.error("Failed to parse VoIP push payload as IncomingCallEvent")
      CallManager.shared.reportAndEndInvalidCall(completion: completion)
      return
    }

    Log.voipPush.debug(
      "Parsed incoming call event - serverCallId: \(event.serverCallId)"
    )

    // Report the incoming call to CallKit using callback-based API
    // (async/await may not work reliably when app is launched from terminated state)
    CallManager.shared.reportIncomingCall(event: event) { error in
      if let error = error {
        Log.voipPush.error("Failed to report incoming call: \(error.localizedDescription)")
      } else {
        Log.voipPush.debug("Successfully reported incoming call from VoIP push")
      }
      completion()
    }
  }
}
