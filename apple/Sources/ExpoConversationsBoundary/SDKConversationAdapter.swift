import Foundation
import VoIPSDK

/// Receives the SDK Expo module's existing client. Never creates another native owner.
/// The private M3.5 bridge exposes the same launch-owned hooks. This adapter never
/// creates a manager, provider, RN host or audio adapter.
@MainActor
public final class SDKConversationAdapter {
  private let client: ConversationClient

  public init(client: ConversationClient) { self.client = client }

  /// Invoke before the SDK app-delegate/module starts its application. The Expo
  /// host supplies ExpoMediaSupport.startFromBundle here, retaining its compiled
  /// audio adapter. Native Swift hosts can supply VoIPApplication.start. Repeated
  /// starts preserve SDK hook identity; incompatible selection throws.
  public static func launch(
    nativeJoinHandler: NativeConversationJoinHandler? = nil,
    incomingMediaPrewarmer: NativeIncomingMediaPrewarmer? = nil,
    start: @MainActor (NativeConversationJoinHandler?, NativeIncomingMediaPrewarmer?) throws -> VoIPApplication
  ) throws -> SDKConversationAdapter {
    let application = try start(nativeJoinHandler, incomingMediaPrewarmer)
    return SDKConversationAdapter(client: application.client)
  }

  /// Synchronous value snapshot; revisions and state remain owned by the SDK.
  public var snapshot: ConversationStateUpdate { client.state }

  public func observe(_ observer: @escaping @MainActor (ConversationStateUpdate) -> Void) -> UUID {
    client.observeState(observer)
  }

  public func removeObserver(_ token: UUID) { client.removeStateObserver(token) }

  /// Register at module scope, before React mounting. The launch-selected native
  /// Join implementation remains independent of handler replacement/removal.
  public func setHandler(_ handler: ConversationHandler?) { client.setConversationHandler(handler) }

  public func observeEvents(_ observer: @escaping @MainActor (ConversationEventContext) -> Void) -> UUID {
    client.observeEvents(observer)
  }

  public func removeEventObserver(_ token: UUID) { client.removeEventObserver(token) }

  public func start(to participant: Participant, video: Bool,
                    metadata: [String: JSONValue]? = nil) async throws -> UUID {
    try await client.startConversation(to: participant, video: video, metadata: metadata)
  }

  public func join(_ id: UUID) async throws { try await client.joinConversation(id) }
  public func end(_ id: UUID) async throws { try await client.endConversation(id) }
  public func setHeld(_ id: UUID, _ held: Bool) async throws { try await client.setHeld(id, held) }
  public func setMuted(_ id: UUID, _ muted: Bool) async throws { try await client.setMuted(id, muted) }
  public func playTones(_ id: UUID, _ digits: String) async throws { try await client.playTones(id, digits) }
  public func merge(_ first: UUID, _ second: UUID) async throws { try await client.mergeConversations(first, second) }

  /// The pinned core returns a UUID or throws; typed bridge outcomes remain an
  /// SDK M3.5 gap. Foreground reporting currently does not trigger SDK prewarm.
  public func reportIncoming(_ payload: IncomingCallPayload) async throws -> UUID {
    try await client.reportIncomingConversation(payload)
  }

  public func receiveCallEnded(_ payload: CallEndedPayload) -> Bool {
    client.receiveCallEnded(payload)
  }

  public func report(_ id: UUID, _ report: ConversationReport) async throws {
    try await client.reportConversation(id, report: report)
  }
}
