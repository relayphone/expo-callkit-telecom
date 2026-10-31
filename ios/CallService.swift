import Foundation
import IosVoipSDK
import Observation
import Synchronization
import os

/// Runs the SDK's `VoIPClient` for the app, from launch, and translates between it and the
/// TypeScript API.
///
/// The app delegate subscriber starts the client before JavaScript runs, so a VoIP push that
/// launches the app is reported and the user can answer before JavaScript loads. The SDK keeps
/// every call's state; this service shows it as conversations, maps commands and reports to the
/// SDK's requests and server events, and passes the SDK's events to JavaScript.
@MainActor
final class CallService {
  static let shared = CallService()

  /// The latest `ConversationsSnapshot`, which JavaScript reads synchronously from its own
  /// thread.
  nonisolated static let snapshot = Mutex(
    JSON.object(["revision": .number(0), "conversations": .array([])]))

  private static let log = Logger(subsystem: "ExpoCallKitTelecom", category: "calls")

  private let settings = Settings.main
  private let intake = PushIntake()
  private var client: VoIPClient?
  private var startError: CommandError?
  private var send: (@MainActor (String, JSON) -> Void)?
  private var emitterOwner: ObjectIdentifier?
  private lazy var handlers = HandlerActions(
    send: { [unowned self] in emit($0, $1) },
    conversation: { [unowned self] id in
      client?.calls.first { $0.id == id }.map { conversation($0) }
    },
    complete: { [unowned self] id, placed in complete(id, placed: placed) },
    expired: { [unowned self] in publish() })
  private lazy var routes = AudioRoutes { [unowned self] in emit("audioRoutesChanged", $0) }

  // Conversations
  private var revision = 0
  private var conversations = JSON.array([])
  /// Calls that ended, shown once with their end reason before they leave the snapshot.
  private var endedCalls: [(call: Call, at: Date)] = []
  /// Ended calls that the client may not have dropped yet.
  private var tombstones: Set<CallID> = []
  /// Calls that a snapshot has shown, so that their end is shown too.
  private var shown: Set<CallID> = []
  /// The participants JavaScript gave for outgoing calls and member updates.
  private var participants: [CallID: [JSON]] = [:]
  /// Actions the app requested that the SDK hasn't performed yet, to tell the handler their
  /// origin.
  private var requested: [Action] = []
  /// Requests that wait for the SDK to carry them out.
  private var waiters: [ActionWaiter] = []

  // Push token
  private var tokenRevision = 0
  private var token: JSON = .null

  // Dial requests
  private var observesConversationEvents = false
  private var dialRequest: JSON?

  private init() {}

  // MARK: Lifecycle

  /// Starts the client, once. The app delegate subscriber calls it at launch.
  func start() {
    guard client == nil, startError == nil else { return }
    #if EXPO_CALLKIT_TELECOM_SIMULATOR && DEBUG
      VoIPSimulator.activate()
    #endif
    #if EXPO_CALLKIT_TELECOM_CALLKIT
      let framework = CallKitAdapter()
    #else
      let framework = LiveCommunicationKitAdapter()
    #endif
    #if EXPO_CALLKIT_TELECOM_WEBRTC
      let media = RTCAudioSessionMedia()
    #else
      let media = AppManagedMedia()
    #endif
    let intake = intake
    let push = PushKitSource { intake.decode($0) }
    let client: VoIPClient
    do {
      client = try VoIPClient.start(
        framework: framework, media: media, push: push, configuration: settings.configuration)
    } catch {
      Self.log.error("Another VoIPClient runs in this process, so calls are unavailable")
      startError = CommandError(
        code: .duplicateSDKLinkage, message: "Another VoIPClient runs in this process")
      return
    }
    self.client = client
    handlers.acceptsUnhandled = settings.acceptsUnhandledActions
    let events = client.events()
    Task { [weak self] in
      for await event in events { self?.handle(event) }
    }
    Task { [weak self] in
      for await _ in Observations({ client.calls }) { self?.publish() }
    }
    Task { [weak self] in
      for await token in push.tokens() { self?.tokenChanged(token) }
    }
    _ = routes
  }

  /// The module for a new JavaScript runtime starts sending events.
  func attach(owner: ObjectIdentifier, send: @escaping @MainActor (String, JSON) -> Void) {
    start()
    emitterOwner = owner
    self.send = send
    handlers.reset()
    observesConversationEvents = false
  }

  /// The module stops sending events, unless a newer one took over.
  func detach(owner: ObjectIdentifier) {
    guard emitterOwner == owner else { return }
    emitterOwner = nil
    send = nil
    handlers.reset()
    observesConversationEvents = false
  }

  // MARK: State

  func snapshotNow() -> JSON {
    publish()
    return Self.snapshot.withLock { $0 }
  }

  var audioRoutes: JSON { routes.snapshot }

  var pushToken: JSON {
    .object(["revision": .number(Double(tokenRevision)), "token": token])
  }

  // MARK: Commands

  func startConversation(_ options: JSON) async throws(CommandError) -> String {
    guard let to = options["to"] else { throw .invalidArguments }
    let party = try Party(participant: to)
    let id = CallID(rawValue: UUID().uuidString.lowercased())
    var metadata: [String: String] = [:]
    if case .object(let values)? = options["metadata"] {
      metadata = values.compactMapValues(\.string)
    }
    participants[id] = [to]
    let call = OutgoingCall(
      id: id, parties: [party], hasVideo: options["video"]?.bool ?? false, metadata: metadata)
    do {
      try await request(.start(call))
    } catch {
      participants[id] = nil
      throw error
    }
    return id.rawValue
  }

  /// Answers a ringing call, or joins a call from its call preview.
  func joinConversation(_ id: String) async throws(CommandError) {
    let callID = CallID(rawValue: id)
    if case .previewing = try call(callID).status {
      try await request(.join(callID))
    } else {
      try await request(.answer(callID))
    }
  }

  func endConversation(_ id: String) async throws(CommandError) {
    try await request(.end(CallID(rawValue: id)))
  }

  func setHeld(_ id: String, _ held: Bool) async throws(CommandError) {
    let callID = CallID(rawValue: id)
    guard try call(callID).isOnHold != held else { return }
    try await request(.hold(callID, onHold: held))
  }

  func setMuted(_ id: String, _ muted: Bool) async throws(CommandError) {
    let callID = CallID(rawValue: id)
    guard try call(callID).isMuted != muted else { return }
    try await request(.mute(callID, muted: muted))
  }

  func playTones(_ id: String, _ digits: String) async throws(CommandError) {
    guard !digits.isEmpty, digits.allSatisfy({ "0123456789*#ABCDabcd".contains($0) }) else {
      throw .invalidArguments
    }
    try await request(.playTones(CallID(rawValue: id), digits: digits))
  }

  func mergeConversations(_ first: String, _ second: String) async throws(CommandError) {
    try await request(.merge(CallID(rawValue: first), with: CallID(rawValue: second)))
  }

  func selectAudioRoute(_ id: String) throws(CommandError) {
    try routes.select(id)
  }

  // MARK: Reports

  /// Applies a transport fact about a call that JavaScript knows.
  func reportConversation(_ id: String, _ report: JSON) async throws(CommandError) {
    let callID = CallID(rawValue: id)
    _ = try call(callID)
    switch report["type"]?.string {
    case "connecting":
      await receive(.connecting, for: callID)
    case "connected":
      await receive(.connected, for: callID)
    case "updated":
      // The SDK keeps whether a call has video from its start; only members change.
      guard let members = report["members"]?.array else { return }
      guard !members.isEmpty else { throw .invalidArguments }
      var parties: [Party] = []
      for member in members { parties.append(try Party(participant: member)) }
      participants[callID] = members
      await receive(.partiesChanged(parties), for: callID)
    case "ended":
      switch report["reason"]?.string {
      case "local": try await request(.end(callID))
      case "remoteEnded": await receive(.endedRemotely, for: callID)
      case "failed": await receive(.failed, for: callID)
      case "unanswered": await receive(.unanswered, for: callID)
      case "answeredElsewhere": await receive(.answeredElsewhere, for: callID)
      case "declinedElsewhere": await receive(.declinedElsewhere, for: callID)
      default: throw .invalidArguments
      }
    default:
      throw .invalidArguments
    }
  }

  /// Presents an incoming call that JavaScript learned about, and returns how it appears.
  func reportIncomingConversation(_ payload: JSON) async throws(CommandError) -> JSON {
    let client = try running()
    guard
      let data = try? JSON.object(["incomingCall": payload]).data(),
      case .incoming(let incoming, _) = VoIPPush(defaultFormat: data)
    else {
      return .object(["status": .string("failed"), "error": CommandError.invalidArguments.json])
    }
    intake.record(payload, for: incoming.id, viaPush: false)
    let outcome = await client.report(incoming).json(id: incoming.id)
    publish()
    emitIncomingOutcome(payload: payload, outcome: outcome)
    return outcome
  }

  /// Applies a server's end signal for an incoming call, and returns whether this device knows
  /// the call. A signal for a call that hasn't arrived yet is kept until it does.
  func receiveCallEnded(_ payload: JSON) async throws(CommandError) -> Bool {
    let client = try running()
    guard
      let body = try? payload.data(), let text = String(data: body, encoding: .utf8),
      let data = try? JSON.object([
        "messageType": .string("callEnded"), "callEnded": .string(text),
      ]).data(),
      case .serverEvent(let event, let id, let eventID) = VoIPPush(defaultFormat: data)
    else { return false }
    if event == .answeredElsewhere { intake.recordAnsweredElsewhere(eventID, for: id) }
    let isKnown = client.calls.contains { $0.id == id }
    await client.receive(event, for: id, eventID: eventID)
    publish()
    return isKnown
  }

  // MARK: Handler and listeners

  func setHandlerAttached(_ attached: Bool, generation: Int) {
    handlers.attach(attached, generation: generation)
    publish()
  }

  func settleAction(_ actionID: String, generation: Int, success: Bool) {
    handlers.settle(actionID, generation: generation, success: success)
    publish()
  }

  /// JavaScript started or stopped listening for conversation events, which carry dial requests.
  func observeConversationEvents(_ observes: Bool) {
    observesConversationEvents = observes
    guard observes, let dialRequest else { return }
    self.dialRequest = nil
    emit("conversationEvent", .object(["type": .string("dialRequest"), "payload": dialRequest]))
  }

  /// The user asked to call a handle from Recents, Siri or Contacts. Without a listener, the
  /// latest request waits for one.
  func dialRequested(handle: String, video: Bool) {
    let payload = JSON.object(["handle": .string(handle), "video": .bool(video)])
    guard observesConversationEvents, send != nil else {
      dialRequest = payload
      return
    }
    emit("conversationEvent", .object(["type": .string("dialRequest"), "payload": payload]))
  }

  // MARK: SDK events

  private func handle(_ event: CallEvent) {
    defer {
      for waiter in waiters where waiter.isCarriedOut(by: event) { waiter.finish() }
    }
    switch event {
    case .presented(let call):
      emitPushOutcome(for: call.id, PresentationOutcome.presented.json(id: call.id))
    case .previewStarted(let call):
      // A join that failed returns to the preview.
      handlers.cancel(call.id, reason: "cancelled")
      guard case .previewing(let reason) = call.status else { break }
      publish()
      emitPushOutcome(for: call.id, PresentationOutcome.previewed(reason).json(id: call.id))
      if reason == .answeredElsewhere { emitAnsweredElsewhere(call.id) }
    case .startRequested(let call):
      let origin = takeRequest {
        if case .start(let start) = $0 { start.id == call.id } else { false }
      }
      gate(.start, call, origin: origin)
    case .joinRequested(let call):
      gate(.join, call, origin: takeRequest { $0 == .join(call.id) })
    case .performed(let action, let call):
      let origin = takeRequest { $0 == action }
      publish()
      let inform = { (kind: HandlerActions.Kind, arguments: [String: JSON]) in
        self.handlers.inform(
          kind, arguments: arguments, conversation: self.conversation(call), origin: origin)
      }
      switch action {
      case .end: inform(.end, [:])
      case .hold(_, let onHold): inform(.hold, ["held": .bool(onHold)])
      case .mute(_, let muted): inform(.mute, ["muted": .bool(muted)])
      case .playTones(_, let digits): inform(.tones, ["digits": .string(digits)])
      default: break
      }
    case .audioActivated:
      routes.refresh()
      emit(
        "conversationEvent", .object(["type": .string("audioActivated"), "payload": .object([:])]))
    case .audioDeactivated, .audioInterrupted, .mediaServicesReset:
      // The module passes no audio session to the SDK, so only the calling framework's
      // deactivation arrives; an interruption or a reset would also stop the media.
      routes.refresh()
      emit(
        "conversationEvent",
        .object(["type": .string("audioDeactivated"), "payload": .object([:])]))
    case .ended(let call):
      ended(call)
    }
    publish()
  }

  private func ended(_ call: Call) {
    handlers.cancel(call.id, reason: "cancelled")
    requested.removeAll { $0.callID == call.id }
    guard case .ended(let reason) = call.status else { return }
    switch reason {
    case .filtered(let cause):
      emitPushOutcome(for: call.id, PresentationOutcome.filtered(cause).json(id: call.id))
    case .failed(let failure):
      emitPushOutcome(for: call.id, PresentationOutcome.refused(failure).json(id: call.id))
    default:
      break
    }
    tombstones.insert(call.id)
    if shown.remove(call.id) != nil {
      endedCalls.append((call, Date.now))
      publish()
      endedCalls.removeAll { $0.call.id == call.id }
    }
    let conversation = JSON.object(["conversationId": .string(call.id.rawValue)])
    switch reason {
    case .answeredElsewhere:
      emitAnsweredElsewhere(call.id)
    case .failed(.audioActivationTimedOut):
      emit(
        "conversationEvent",
        .object(["type": .string("audioActivationMissing"), "payload": conversation]))
    case .failed(.frameworkReset):
      emit(
        "conversationEvent", .object(["type": .string("providerReset"), "payload": .object([:])]))
    default:
      break
    }
    participants[call.id] = nil
    intake.forget(call.id)
  }

  // MARK: Snapshots

  /// Sends a new snapshot when the conversations changed.
  private func publish() {
    guard let client else { return }
    let live = client.calls
    tombstones.formIntersection(live.map(\.id))
    var list: [JSON] = []
    for call in live where !tombstones.contains(call.id) {
      list.append(conversation(call))
      shown.insert(call.id)
    }
    for ended in endedCalls { list.append(conversation(ended.call, endedAt: ended.at)) }
    let conversations = JSON.array(list)
    guard conversations != self.conversations else { return }
    self.conversations = conversations
    revision += 1
    let snapshot = JSON.object([
      "revision": .number(Double(revision)), "conversations": conversations,
    ])
    Self.snapshot.withLock { $0 = snapshot }
    emit("conversationsChanged", snapshot)
  }

  /// The call as the TypeScript API's `Conversation`.
  private func conversation(_ call: Call, endedAt: Date = .now) -> JSON {
    let payload = intake.payload(for: call.id)
    var fields = call.status.fields(isOnHold: call.isOnHold, endedAt: endedAt)
    fields["id"] = .string(call.id.rawValue)
    fields["direction"] = .string(call.isIncoming ? "incoming" : "outgoing")
    fields["members"] = .array(members(of: call, payload: payload))
    fields["capabilities"] = settings.capabilities
    fields["isMuted"] = .bool(call.isMuted)
    fields["isVideo"] = .bool(call.hasVideo)
    if let connectedAt = call.connectedAt { fields["connectedAt"] = .date(connectedAt) }
    if let payload { fields["payload"] = payload }
    if let pending = handlers.pending(for: call.id) { fields["pending"] = pending }
    return .object(fields)
  }

  /// The participants JavaScript gave, or else the call's parties. An incoming caller's host
  /// contact ID identifies it, as on Android.
  private func members(of call: Call, payload: JSON?) -> [JSON] {
    if let members = participants[call.id] { return members }
    var members = call.parties.map(\.participant)
    if let contact = payload?["caller"]?["hostContactID"],
      case .object(var first)? = members.first
    {
      first["id"] = contact
      members[0] = .object(first)
    }
    return members
  }

  // MARK: Helpers

  private func running() throws(CommandError) -> VoIPClient {
    start()
    guard let client else {
      throw startError ?? CommandError(code: .conversation, message: "Calls are unavailable")
    }
    return client
  }

  private func call(_ id: CallID) throws(CommandError) -> Call {
    guard let call = try running().calls.first(where: { $0.id == id }) else {
      throw .unknownConversation
    }
    return call
  }

  /// Requests an action, and returns once the SDK carried it out, so that the conversations
  /// show it: a start or join once the app is asked to place the call, and any other action once
  /// the SDK performed it, or once the call ended or became a call preview.
  private func request(_ action: Action) async throws(CommandError) {
    let client = try running()
    requested.append(action)
    // The SDK's event can arrive before the request returns.
    let waiter = ActionWaiter(action)
    waiters.append(waiter)
    do {
      try await client.request(action)
    } catch {
      if let index = requested.firstIndex(of: action) { requested.remove(at: index) }
      waiters.removeAll { $0 === waiter }
      publish()
      throw CommandError(error)
    }
    await waiter.wait()
    waiters.removeAll { $0 === waiter }
    publish()
  }

  private func receive(_ event: ServerEvent, for id: CallID) async {
    guard let client else { return }
    await client.receive(event, for: id, eventID: EventID(rawValue: UUID().uuidString))
    publish()
  }

  /// Waits for the handler to place a call that the SDK asked the app to start or join.
  private func gate(_ kind: HandlerActions.Kind, _ call: Call, origin: HandlerActions.Origin) {
    handlers.gate(
      kind, call: call.id, conversation: conversation(call), origin: origin,
      placement: settings.configuration.deadlines.placement)
  }

  /// Places a call that the handler started or joined, or ends it when the handler failed.
  private func complete(_ id: CallID, placed: Bool) {
    guard let client else { return }
    Task {
      if placed {
        await client.confirmPlaced(id)
        publish()
      } else {
        try? await request(.end(id))
      }
    }
  }

  /// Removes the first requested action that matches, and returns whether the app requested it.
  private func takeRequest(where matches: (Action) -> Bool) -> HandlerActions.Origin {
    guard let index = requested.firstIndex(where: matches) else { return .system }
    requested.remove(at: index)
    return .app
  }

  private func tokenChanged(_ data: Data?) {
    let value = data.map { bytes in bytes.map { String(format: "%02x", $0) }.joined() }
    let token: JSON =
      value.map { .object(["type": .string("apns-voip"), "value": .string($0)]) } ?? .null
    guard token != self.token else { return }
    self.token = token
    tokenRevision += 1
    emit("pushTokenUpdated", pushToken)
  }

  private func emitIncomingOutcome(payload: JSON, outcome: JSON) {
    emit(
      "conversationEvent",
      .object([
        "type": .string("incomingConversationOutcome"),
        "payload": .object([
          "eventId": payload["eventId"] ?? .null, "serverCallId": payload["serverCallId"] ?? .null,
          "outcome": outcome,
        ]),
      ]))
  }

  /// Tells JavaScript how a call that a VoIP push brought appeared, once.
  private func emitPushOutcome(for id: CallID, _ outcome: JSON) {
    guard let payload = intake.takePushOutcome(for: id) else { return }
    emitIncomingOutcome(payload: payload, outcome: outcome)
  }

  private func emitAnsweredElsewhere(_ id: CallID) {
    guard let eventID = intake.answeredElsewhereEvent(for: id) else { return }
    emit(
      "conversationEvent",
      .object([
        "type": .string("answeredElsewhere"),
        "payload": .object([
          "conversationId": .string(id.rawValue), "serverCallId": .string(id.rawValue),
          "eventId": .string(eventID.rawValue),
        ]),
      ]))
  }

  private func emit(_ name: String, _ body: JSON) {
    send?(name, body)
  }
}

/// A request that waits for the SDK's event that carries it out.
@MainActor
private final class ActionWaiter {
  /// How long a request waits at most. The SDK's own deadlines end a call that stalls.
  private static let limit = Duration.seconds(10)

  let action: Action
  private var isFinished = false
  private var continuation: CheckedContinuation<Void, Never>?

  init(_ action: Action) {
    self.action = action
  }

  /// Whether the event carries out the request.
  func isCarriedOut(by event: CallEvent) -> Bool {
    switch event {
    case .performed(let performed, _): performed == action
    case .startRequested(let call):
      if case .start(let outgoing) = action { outgoing.id == call.id } else { false }
    case .joinRequested(let call): action == .join(call.id)
    case .ended(let call), .previewStarted(let call): call.id == action.callID
    case .presented, .audioActivated, .audioDeactivated, .audioInterrupted, .mediaServicesReset:
      false
    }
  }

  func finish() {
    isFinished = true
    continuation?.resume()
    continuation = nil
  }

  func wait() async {
    guard !isFinished else { return }
    await withCheckedContinuation { continuation in
      self.continuation = continuation
      Task { [weak self] in
        try? await Task.sleep(for: Self.limit)
        self?.finish()
      }
    }
  }
}
