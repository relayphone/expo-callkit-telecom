import Foundation
import IosVoipSDK
import Synchronization

/// Decodes the app's VoIP pushes, and keeps what the TypeScript API shows about incoming calls
/// that the SDK's values don't carry: the original payload, and the event that answered a call
/// elsewhere.
///
/// PushKit decodes on `VoIPActor` and the module reads on the main actor, so the records are
/// behind a lock.
final class PushIntake: Sendable {
  private struct Record {
    /// The incoming call's payload, without its media credentials.
    var payload: JSONValue
    /// Whether a VoIP push brought the call, and the module hasn't told JavaScript how it
    /// appeared yet.
    var awaitsOutcome: Bool
  }

  private struct State {
    var records: [CallID: Record] = [:]
    var order: [CallID] = []
    var answeredElsewhere: [CallID: EventID] = [:]
  }

  /// How many calls the intake remembers. Ended calls are forgotten at once; this bounds calls
  /// that never appear.
  private static let limit = 32

  private let state = Mutex(State())

  /// Decodes a VoIP push in the SDK's default format, from PushKit's callback on `VoIPActor`.
  func decode(_ data: Data) -> VoIPPush {
    let push = VoIPPush(defaultFormat: data)
    switch push {
    case .incoming(let call, _):
      let object = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
      if let body = object?["incomingCall"] ?? object?["incoming_call"],
        let payload = JSONValue(body), case .object = payload
      {
        record(payload, for: call.id, viaPush: true)
      }
    case .serverEvent(.answeredElsewhere, let id, let eventID):
      recordAnsweredElsewhere(eventID, for: id)
    default:
      break
    }
    return push
  }

  /// Remembers an incoming call's payload.
  func record(_ payload: JSONValue, for id: CallID, viaPush: Bool) {
    state.withLock { state in
      if state.records[id] == nil { state.order.append(id) }
      state.records[id] = Record(payload: payload.removing("media"), awaitsOutcome: viaPush)
      while state.order.count > Self.limit {
        let oldest = state.order.removeFirst()
        state.records[oldest] = nil
        state.answeredElsewhere[oldest] = nil
      }
    }
  }

  /// Remembers the event that answered a call elsewhere.
  func recordAnsweredElsewhere(_ eventID: EventID, for id: CallID) {
    state.withLock { $0.answeredElsewhere[id] = eventID }
  }

  /// The incoming call's payload.
  func payload(for id: CallID) -> JSONValue? {
    state.withLock { $0.records[id]?.payload }
  }

  /// The payload of a call that a VoIP push brought, the first time the module tells JavaScript
  /// how it appeared.
  func takePushOutcome(for id: CallID) -> JSONValue? {
    state.withLock { state in
      guard state.records[id]?.awaitsOutcome == true else { return nil }
      state.records[id]?.awaitsOutcome = false
      return state.records[id]?.payload
    }
  }

  /// The event that answered the call elsewhere.
  func answeredElsewhereEvent(for id: CallID) -> EventID? {
    state.withLock { $0.answeredElsewhere[id] }
  }

  /// Forgets an ended call.
  func forget(_ id: CallID) {
    state.withLock { state in
      state.records[id] = nil
      state.order.removeAll { $0 == id }
      state.answeredElsewhere[id] = nil
    }
  }
}
