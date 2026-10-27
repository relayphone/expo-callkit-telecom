import Foundation
import XCTest
import VoIPSDK
#if targetEnvironment(simulator)
import CKSimRuntimeC
#endif
import ExpoConversationsBoundary

/// Run each class in a separate xcodebuild test invocation. The SDK deliberately
/// keeps one launch selection for the lifetime of the native process.
final class NativeJoinJourneyTests: XCTestCase {
  @MainActor
  func testNativeJoinAndReportGate() async throws {
    var nativeJoins = 0
    var javaScriptJoins = 0
    let nativeJoin = NativeConversationJoinHandler { conversation, context in
      XCTAssertEqual(conversation.direction, .incoming)
      XCTAssertEqual(context.origin, .app)
      XCTAssertFalse(context.signal.isCancelled)
      nativeJoins += 1
    }
    let sessions = SessionRecorder()
    let host = HostRecorder()
    let prewarmer = NativeIncomingMediaPrewarmer(javaScriptHost: host) { _ in
      let session = PendingSession()
      sessions.values.append(session)
      return session
    }
    let adapter = try SDKConversationAdapter.launch(nativeJoinHandler: nativeJoin, incomingMediaPrewarmer: prewarmer) {
      join, media in
      try VoIPApplication.start(mediaAdapter: NoMediaAdapter(), nativeJoinHandler: join,
                                incomingMediaPrewarmer: media)
    }
    XCTAssertTrue(SimulatorSupport.activateIfNeeded(), "The actual SDK simulator runtime must activate")
    XCTAssertEqual(adapter.snapshot.conversations, [])
    let before = adapter.snapshot.revision
    let first = try await adapter.reportIncoming(invitation("native-cold"))
    try await waitUntil { adapter.snapshot.conversations.contains { $0.id == first && $0.status == .ringing } }
    XCTAssertGreaterThan(adapter.snapshot.revision, before)
    // Public foreground reporting does not yet invoke the SDK prewarm hook.
    // Preserve that gap as a negative control rather than silently implementing it here.
    XCTAssertTrue(sessions.values.isEmpty)
    XCTAssertEqual(host.requests, 0)
    try await adapter.join(first)
    try await waitUntil { nativeJoins == 1 && adapter.snapshot.conversations.first { $0.id == first }?.status == .connecting }
    XCTAssertNil(adapter.snapshot.conversations.first { $0.id == first }?.connectedAt,
                 "Native Join fulfillment alone cannot claim media connection")
    try await adapter.report(first, .connected)
    try await waitUntil { adapter.snapshot.conversations.first { $0.id == first }?.status == .connected }
    try await adapter.report(first, .ended(.remoteEnded))
    try await waitUntil { !adapter.snapshot.conversations.contains { $0.id == first && $0.status != .ended } }

    adapter.setHandler(.init(join: { _, _ in javaScriptJoins += 1 }, start: { _, _ in }, end: { _, _ in }))
    let second = try await adapter.reportIncoming(invitation("native-attached"))
    try await adapter.join(second)
    try await waitUntil { nativeJoins == 2 }
    XCTAssertEqual(javaScriptJoins, 0, "JS attachment cannot steal launch-owned native Join")
    adapter.setHandler(nil)
    try await adapter.report(second, .ended(.remoteEnded))
    try await waitUntil { !adapter.snapshot.conversations.contains { $0.id == second && $0.status != .ended } }

    let endedPayload = try invitation("native-ended-signal")
    let third = try await adapter.reportIncoming(endedPayload)
    XCTAssertTrue(adapter.receiveCallEnded(.init(eventId: "server-end", serverCallId: endedPayload.serverCallId,
                                                 reason: .answeredElsewhere)))
    try await waitUntil { !adapter.snapshot.conversations.contains { $0.id == third && $0.status != .ended } }

    // Simulator-only SDK injection feeds its actual registered PushKit receiver;
    // the SDK owns the canonical report gate and generation. This is not APNs.
    let pushPayload = try invitation("native-push-prewarm")
    var preparing = 0
    var ready = 0
    prewarmer.onEvent = { event in
      if event.phase == .preparing {
        preparing += 1
        XCTAssertEqual(adapter.snapshot.conversations.first { $0.id == event.invitation.conversationID }?.status, .ringing,
                       "Native media preparation must follow the actual successful platform report")
      }
      if event.phase == .ready { ready += 1 }
    }
    XCTAssertTrue(sessions.values.isEmpty)
    XCTAssertEqual(host.requests, 0)
    let body: [String: Any] = [
      "incomingCall": [
        "eventId": pushPayload.eventId, "serverCallId": pushPayload.serverCallId,
        "caller": ["handleType": "phone", "value": "+15555550142", "displayName": "Fixture caller"],
        "hasVideo": false,
        "media": ["provider": "livekit", "url": "wss://media.example.test", "room": "fixture-room",
                  "identity": "fixture-device", "token": "inert-fixture-credential",
                  "expiresAt": Date().addingTimeInterval(60).timeIntervalSince1970]
      ]
    ]
    #if targetEnvironment(simulator)
    CKSimInjectPush(body, true, UUID().uuidString)
    #else
    XCTFail("This journey requires the actual SDK simulator runtime")
    #endif
    try await waitUntil { preparing == 1 && ready == 1 && sessions.values.first?.prepared == true && host.requests == 1 }
    let pushID = try XCTUnwrap(adapter.snapshot.conversations.first { $0.payload?.eventId == pushPayload.eventId }?.id)
    XCTAssertEqual(adapter.snapshot.conversations.first { $0.id == pushID }?.status, .ringing)
    XCTAssertNil(adapter.snapshot.conversations.first { $0.id == pushID }?.connectedAt)
    XCTAssertTrue(adapter.receiveCallEnded(.init(eventId: "push-end", serverCallId: pushPayload.serverCallId,
                                                 reason: .remoteEnded)))
    try await waitUntil { sessions.values.first?.closed == true }
    let retainedID = try await adapter.reportIncoming(pushPayload)
    XCTAssertEqual(retainedID, pushID)
    await Task.yield()
    XCTAssertEqual(sessions.values.count, 1, "A retained terminal invitation cannot create another media session")
    XCTAssertEqual(sessions.values.first?.preparations, 1)
    prewarmer.retireAll()
    try await waitUntil { adapter.snapshot.conversations.allSatisfy { $0.status == .ended } }
    print("V2_M35_NATIVE_JOURNEY_PASS nativeJoin=2 jsJoin=0 sdkPushReportGate=1 retired=1")
  }
}

final class HandlerJourneyTests: XCTestCase {
  @MainActor
  func testModuleScopeHandler() async throws {
    var joins = 0
    var starts = 0
    var ends = 0
    var effectiveDeadline: Date?
    let adapter = try SDKConversationAdapter.launch { join, prewarmer in
      XCTAssertNil(join)
      XCTAssertNil(prewarmer)
      return try VoIPApplication.start(mediaAdapter: NoMediaAdapter())
    }
    XCTAssertTrue(SimulatorSupport.activateIfNeeded())
    // This is application/module registration before any view or React tree.
    adapter.setHandler(.init(join: { _, context in
      joins += 1
      effectiveDeadline = context.deadline
      XCTAssertFalse(context.signal.isCancelled)
    }, start: { _, _ in starts += 1 }, end: { _, _ in ends += 1 }))
    let id = try await adapter.reportIncoming(invitation("handler-module-scope"))
    try await adapter.join(id)
    try await waitUntil { joins == 1 && adapter.snapshot.conversations.first { $0.id == id }?.status == .connecting }
    XCTAssertNotNil(effectiveDeadline)
    XCTAssertNil(adapter.snapshot.conversations.first { $0.id == id }?.connectedAt)
    try await adapter.report(id, .connected)
    try await waitUntil { adapter.snapshot.conversations.first { $0.id == id }?.status == .connected }
    try await adapter.setHeld(id, true)
    try await waitUntil { adapter.snapshot.conversations.first { $0.id == id }?.status == .held }
    try await adapter.setHeld(id, false)
    try await waitUntil { adapter.snapshot.conversations.first { $0.id == id }?.status == .connected }
    try await adapter.end(id)
    try await waitUntil { ends == 1 && !adapter.snapshot.conversations.contains { $0.id == id && $0.status != .ended } }
    let outgoing = try await adapter.start(to: .init(id: "callee", displayName: "Callee"), video: false)
    try await waitUntil { starts == 1 }
    try await adapter.report(outgoing, .connected)
    try await waitUntil { adapter.snapshot.conversations.first { $0.id == outgoing }?.status == .connected }
    try await adapter.end(outgoing)
    try await waitUntil { ends == 2 && !adapter.snapshot.conversations.contains { $0.id == outgoing && $0.status != .ended } }
    adapter.setHandler(nil)
    print("V2_M35_HANDLER_JOURNEY_PASS joins=1 starts=1 ends=2 holdResume=1")
  }
}

@MainActor private final class PendingSession: NativeIncomingMediaSession {
  var preparations = 0
  var prepared: Bool { preparations > 0 }
  var closed = false
  func prewarm(_ credentials: NativeMediaCredentials) async throws { preparations += 1 }
  func close() async { closed = true }
}
@MainActor private final class SessionRecorder { var values: [PendingSession] = [] }
@MainActor private final class HostRecorder: NativeJavaScriptPrewarming {
  var requests = 0
  func prewarm() async throws { requests += 1 }
}
@MainActor private func invitation(_ label: String) throws -> IncomingCallPayload {
  .init(eventId: label, serverCallId: label,
        caller: try Caller(handleType: .phone, value: "+15555550142", displayName: "Fixture caller"))
}
@MainActor private func waitUntil(_ predicate: @escaping @MainActor () -> Bool) async throws {
  let deadline = ContinuousClock.now + .seconds(8)
  while !predicate() {
    guard ContinuousClock.now < deadline else {
      XCTFail("Bounded native state observation expired")
      throw JourneyError.observationExpired
    }
    try await Task.sleep(for: .milliseconds(10))
  }
}
private enum JourneyError: Error { case observationExpired }
