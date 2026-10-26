import Foundation
import VoIPSDK

/// Receives the SDK Expo module's existing client. Never creates another native owner.
/// The Expo Modules 2 bridge will expose this boundary; it owns no JS callbacks.
@MainActor
public final class SDKConversationAdapter {
  private let client: ConversationClient

  public init(client: ConversationClient) { self.client = client }

  /// Synchronous value snapshot; revisions and state remain owned by the SDK.
  public var snapshot: ConversationStateUpdate { client.state }

  public func observe(_ observer: @escaping @MainActor (ConversationStateUpdate) -> Void) -> UUID {
    client.observeState(observer)
  }

  public func removeObserver(_ token: UUID) { client.removeStateObserver(token) }

  public func setHandler(_ handler: ConversationHandler?) { client.setConversationHandler(handler) }

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

  public func reportIncoming(_ payload: IncomingCallPayload) async throws -> UUID {
    try await client.reportIncomingConversation(payload)
  }

  public func report(_ id: UUID, _ report: ConversationReport) async throws {
    try await client.reportConversation(id, report: report)
  }
}
