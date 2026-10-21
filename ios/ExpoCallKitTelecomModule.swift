import CallKit
import ExpoModulesCore

public class ExpoCallKitTelecomModule: Module {
  private let eventBridge = CallEventBridgeIdentity()
  public func definition() -> ModuleDefinition {
    Name("ExpoCallKitTelecom")

    // MARK: - Events

    Events(
      CallSessionAddedEvent.name,
      CallSessionUpdatedEvent.name,
      CallSessionRemovedEvent.name,
      AudioSessionActivatedEvent.name,
      AudioSessionDeactivatedEvent.name,
      AudioRouteChangedEvent.name,
      IncomingCallReportedEvent.name,
      OutgoingCallStartedEvent.name,
      CallAnsweredEvent.name,
      CallEndedEvent.name,
      CallReportedEnded.name,
      SetMutedActionEvent.name,
      VideoChangedEvent.name,
      SetHeldActionEvent.name,
      DTMFEvent.name,
      CallIntentReceivedEvent.name,
      VoIPPushTokenUpdatedEvent.name,
      CallEventEmitter.streamEventName
    )

    // MARK: - Lifecycle

    OnCreate { [weak self] in
      Task { @MainActor in
        guard let self else { return }
        CallEventEmitter.shared.attach(module: self, identity: self.eventBridge)
      }
    }

    OnDestroy { [weak self] in
      guard let self else { return }
      self.eventBridge.close()
      Task { @MainActor in
        CallEventEmitter.shared.detach(identity: self.eventBridge)
      }
    }

    AsyncFunction("attachCallEventStream") { [weak self] (consumerID: String) -> [String: Any] in
      guard let self, !consumerID.isEmpty, consumerID.count <= 128 else {
        throw Exception(name: "InvalidEventConsumer", description: "A live bridge and consumer ID are required")
      }
      return try await MainActor.run {
        guard let snapshot = CallEventEmitter.shared.attachStream(consumerID: consumerID, module: self, identity: self.eventBridge) else {
          throw Exception(name: "EventStreamUnavailable", description: "Bridge replaced or an event consumer is already attached")
        }
        return snapshot
      }
    }
    AsyncFunction("acknowledgeCallEvents") { [weak self] (consumerID: String, epoch: String, revision: Int) -> Bool in
      guard let self else { return false }
      return await MainActor.run {
        CallEventEmitter.shared.acknowledgeStream(consumerID: consumerID, epoch: epoch, revision: revision, identity: self.eventBridge)
      }
    }
    AsyncFunction("detachCallEventStream") { [weak self] (consumerID: String) in
      guard let self else { return }
      await MainActor.run {
        CallEventEmitter.shared.detachStream(consumerID: consumerID, identity: self.eventBridge)
      }
    }

    // Register per-event observers
    OnStartObserving(CallSessionAddedEvent.name) { [weak self] in
      Task { @MainActor in
        guard let self else { return }
        CallEventEmitter.shared.startObserving(
          module: self, identity: self.eventBridge, eventName: CallSessionAddedEvent.name
        )
      }
    }
    OnStopObserving(CallSessionAddedEvent.name) { [weak self] in
      Task { @MainActor in
        guard let self else { return }
        CallEventEmitter.shared.stopObserving(
          identity: self.eventBridge, eventName: CallSessionAddedEvent.name
        )
      }
    }

    OnStartObserving(CallSessionUpdatedEvent.name) { [weak self] in
      Task { @MainActor in
        guard let self else { return }
        CallEventEmitter.shared.startObserving(
          module: self, identity: self.eventBridge, eventName: CallSessionUpdatedEvent.name
        )
      }
    }
    OnStopObserving(CallSessionUpdatedEvent.name) { [weak self] in
      Task { @MainActor in
        guard let self else { return }
        CallEventEmitter.shared.stopObserving(
          identity: self.eventBridge, eventName: CallSessionUpdatedEvent.name
        )
      }
    }

    OnStartObserving(CallSessionRemovedEvent.name) { [weak self] in
      Task { @MainActor in
        guard let self else { return }
        CallEventEmitter.shared.startObserving(
          module: self, identity: self.eventBridge, eventName: CallSessionRemovedEvent.name
        )
      }
    }
    OnStopObserving(CallSessionRemovedEvent.name) { [weak self] in
      Task { @MainActor in
        guard let self else { return }
        CallEventEmitter.shared.stopObserving(
          identity: self.eventBridge, eventName: CallSessionRemovedEvent.name
        )
      }
    }

    OnStartObserving(AudioSessionActivatedEvent.name) { [weak self] in
      Task { @MainActor in
        guard let self else { return }
        CallEventEmitter.shared.startObserving(
          module: self, identity: self.eventBridge, eventName: AudioSessionActivatedEvent.name
        )
      }
    }
    OnStopObserving(AudioSessionActivatedEvent.name) { [weak self] in
      Task { @MainActor in
        guard let self else { return }
        CallEventEmitter.shared.stopObserving(
          identity: self.eventBridge, eventName: AudioSessionActivatedEvent.name
        )
      }
    }

    OnStartObserving(AudioSessionDeactivatedEvent.name) { [weak self] in
      Task { @MainActor in
        guard let self else { return }
        CallEventEmitter.shared.startObserving(
          module: self, identity: self.eventBridge, eventName: AudioSessionDeactivatedEvent.name
        )
      }
    }
    OnStopObserving(AudioSessionDeactivatedEvent.name) { [weak self] in
      Task { @MainActor in
        guard let self else { return }
        CallEventEmitter.shared.stopObserving(
          identity: self.eventBridge, eventName: AudioSessionDeactivatedEvent.name
        )
      }
    }

    OnStartObserving(AudioRouteChangedEvent.name) { [weak self] in
      Task { @MainActor in
        guard let self else { return }
        CallEventEmitter.shared.startObserving(
          module: self, identity: self.eventBridge, eventName: AudioRouteChangedEvent.name
        )
      }
    }
    OnStopObserving(AudioRouteChangedEvent.name) { [weak self] in
      Task { @MainActor in
        guard let self else { return }
        CallEventEmitter.shared.stopObserving(
          identity: self.eventBridge, eventName: AudioRouteChangedEvent.name
        )
      }
    }

    OnStartObserving(IncomingCallReportedEvent.name) { [weak self] in
      Task { @MainActor in
        guard let self else { return }
        CallEventEmitter.shared.startObserving(
          module: self, identity: self.eventBridge, eventName: IncomingCallReportedEvent.name
        )
      }
    }
    OnStopObserving(IncomingCallReportedEvent.name) { [weak self] in
      Task { @MainActor in
        guard let self else { return }
        CallEventEmitter.shared.stopObserving(
          identity: self.eventBridge, eventName: IncomingCallReportedEvent.name
        )
      }
    }

    OnStartObserving(OutgoingCallStartedEvent.name) { [weak self] in
      Task { @MainActor in
        guard let self else { return }
        CallEventEmitter.shared.startObserving(
          module: self, identity: self.eventBridge, eventName: OutgoingCallStartedEvent.name
        )
      }
    }
    OnStopObserving(OutgoingCallStartedEvent.name) { [weak self] in
      Task { @MainActor in
        guard let self else { return }
        CallEventEmitter.shared.stopObserving(
          identity: self.eventBridge, eventName: OutgoingCallStartedEvent.name
        )
      }
    }

    OnStartObserving(CallAnsweredEvent.name) { [weak self] in
      Task { @MainActor in
        guard let self else { return }
        CallEventEmitter.shared.startObserving(
          module: self, identity: self.eventBridge, eventName: CallAnsweredEvent.name
        )
      }
    }
    OnStopObserving(CallAnsweredEvent.name) { [weak self] in
      Task { @MainActor in
        guard let self else { return }
        CallEventEmitter.shared.stopObserving(
          identity: self.eventBridge, eventName: CallAnsweredEvent.name
        )
      }
    }

    OnStartObserving(CallEndedEvent.name) { [weak self] in
      Task { @MainActor in
        guard let self else { return }
        CallEventEmitter.shared.startObserving(
          module: self, identity: self.eventBridge, eventName: CallEndedEvent.name
        )
      }
    }
    OnStopObserving(CallEndedEvent.name) { [weak self] in
      Task { @MainActor in
        guard let self else { return }
        CallEventEmitter.shared.stopObserving(
          identity: self.eventBridge, eventName: CallEndedEvent.name
        )
      }
    }

    OnStartObserving(CallReportedEnded.name) { [weak self] in
      Task { @MainActor in
        guard let self else { return }
        CallEventEmitter.shared.startObserving(
          module: self, identity: self.eventBridge, eventName: CallReportedEnded.name
        )
      }
    }
    OnStopObserving(CallReportedEnded.name) { [weak self] in
      Task { @MainActor in
        guard let self else { return }
        CallEventEmitter.shared.stopObserving(
          identity: self.eventBridge, eventName: CallReportedEnded.name
        )
      }
    }

    OnStartObserving(SetMutedActionEvent.name) { [weak self] in
      Task { @MainActor in
        guard let self else { return }
        CallEventEmitter.shared.startObserving(
          module: self, identity: self.eventBridge, eventName: SetMutedActionEvent.name
        )
      }
    }
    OnStopObserving(SetMutedActionEvent.name) { [weak self] in
      Task { @MainActor in
        guard let self else { return }
        CallEventEmitter.shared.stopObserving(
          identity: self.eventBridge, eventName: SetMutedActionEvent.name
        )
      }
    }

    OnStartObserving(VideoChangedEvent.name) { [weak self] in
      Task { @MainActor in
        guard let self else { return }
        CallEventEmitter.shared.startObserving(
          module: self, identity: self.eventBridge, eventName: VideoChangedEvent.name
        )
      }
    }
    OnStopObserving(VideoChangedEvent.name) { [weak self] in
      Task { @MainActor in
        guard let self else { return }
        CallEventEmitter.shared.stopObserving(
          identity: self.eventBridge, eventName: VideoChangedEvent.name
        )
      }
    }

    OnStartObserving(SetHeldActionEvent.name) { [weak self] in
      Task { @MainActor in
        guard let self else { return }
        CallEventEmitter.shared.startObserving(
          module: self, identity: self.eventBridge, eventName: SetHeldActionEvent.name
        )
      }
    }
    OnStopObserving(SetHeldActionEvent.name) { [weak self] in
      Task { @MainActor in
        guard let self else { return }
        CallEventEmitter.shared.stopObserving(
          identity: self.eventBridge, eventName: SetHeldActionEvent.name
        )
      }
    }

    OnStartObserving(DTMFEvent.name) { [weak self] in
      Task { @MainActor in
        guard let self else { return }
        CallEventEmitter.shared.startObserving(
          module: self, identity: self.eventBridge, eventName: DTMFEvent.name
        )
      }
    }
    OnStopObserving(DTMFEvent.name) { [weak self] in
      Task { @MainActor in
        guard let self else { return }
        CallEventEmitter.shared.stopObserving(identity: self.eventBridge, eventName: DTMFEvent.name)
      }
    }

    OnStartObserving(CallIntentReceivedEvent.name) { [weak self] in
      Task { @MainActor in
        guard let self else { return }
        CallEventEmitter.shared.startObserving(
          module: self, identity: self.eventBridge, eventName: CallIntentReceivedEvent.name
        )
      }
    }
    OnStopObserving(CallIntentReceivedEvent.name) { [weak self] in
      Task { @MainActor in
        guard let self else { return }
        CallEventEmitter.shared.stopObserving(
          identity: self.eventBridge, eventName: CallIntentReceivedEvent.name
        )
      }
    }

    OnStartObserving(VoIPPushTokenUpdatedEvent.name) { [weak self] in
      Task { @MainActor in
        guard let self else { return }
        CallEventEmitter.shared.startObserving(
          module: self, identity: self.eventBridge, eventName: VoIPPushTokenUpdatedEvent.name
        )
      }
    }
    OnStopObserving(VoIPPushTokenUpdatedEvent.name) { [weak self] in
      Task { @MainActor in
        guard let self else { return }
        CallEventEmitter.shared.stopObserving(
          identity: self.eventBridge, eventName: VoIPPushTokenUpdatedEvent.name
        )
      }
    }

    // MARK: - Call Session

    AsyncFunction("getActiveCallSession") { () -> [String: Any]? in
      guard let session = await CallManager.shared.store.firstSession
      else {
        return nil
      }
      return session.toDictionary()
    }

    // MARK: - Audio Session

    Function("getAudioSessionState") { () -> [String: Any] in
      return AudioManager.shared.getAudioSessionState()
    }

    Function("setRTCAudioSessionConfiguration") { (hasVideo: Bool) in
      AudioManager.shared.setRTCAudioSessionConfiguration(hasVideo: hasVideo)
    }

    Function("prepareAudioSessionForCall") { (hasVideo: Bool) in
      AudioManager.shared.prepareAudioSessionForCall(hasVideo: hasVideo)
    }

    Function("restoreAudioSession") {
      AudioManager.shared.restoreAudioSession()
    }

    Function("setAudioSessionPortOverride") { (enabled: Bool) in
      AudioManager.shared.setAudioSessionPortOverride(enabled)
    }

    // MARK: - Capture Session

    Function("getCaptureSessionState") { () -> [String: Any] in
      return CaptureSessionManager.shared.getCaptureSessionState()
    }

    // MARK: - Start Outgoing Call

    AsyncFunction("startOutgoingCall") {
      (recipient: CallParticipantRecord, options: CallOptionsRecord)
        -> String in
      let id = try await CallManager.shared.startOutgoingCall(
        recipient: recipient.toModel(),
        options: options.toModel()
      )
      return id.uuidString
    }

    // MARK: - Report Incoming Call

    AsyncFunction("reportIncomingCall") {
      (event: IncomingCallEventRecord) in
      let incomingCallEvent = try event.toModel()
      try await CallManager.shared.reportIncomingCall(
        event: incomingCallEvent
      )
    }

    // MARK: - Answer Call

    AsyncFunction("answerCall") { (id: String) in
      guard let uuid = UUID(uuidString: id) else {
        throw Exception(
          name: "InvalidUUID",
          description: "Invalid call ID: \(id)"
        )
      }

      try await CallManager.shared.answerCall(for: uuid)
    }

    AsyncFunction("fulfillIncomingCallAnswered") { (requestId: String) in
      guard let requestUUID = UUID(uuidString: requestId) else {
        throw Exception(
          name: "InvalidUUID",
          description: "Invalid request ID: \(requestId)"
        )
      }

      await CallManager.shared.fulfillIncomingCall(requestId: requestUUID)
    }

    AsyncFunction("failIncomingCallConnected") { (requestId: String) in
      guard let requestUUID = UUID(uuidString: requestId) else {
        throw Exception(
          name: "InvalidUUID",
          description: "Invalid request ID: \(requestId)"
        )
      }

      await CallManager.shared.failIncomingCallConnected(requestId: requestUUID)
    }

    AsyncFunction("reportOutgoingCallConnected") { (id: String) in
      guard let uuid = UUID(uuidString: id) else {
        throw Exception(
          name: "InvalidUUID",
          description: "Invalid call ID: \(id)"
        )
      }

      await CallManager.shared.reportOutgoingCallConnected(for: uuid)
    }

    // MARK: - End Call

    AsyncFunction("endCall") { (id: String) in
      guard let uuid = UUID(uuidString: id) else {
        throw Exception(
          name: "InvalidUUID",
          description: "Invalid call ID: \(id)"
        )
      }

      try await CallManager.shared.endCall(for: uuid)
    }

    AsyncFunction("reportCallEnded") { (id: String, reason: String) in
      guard let uuid = UUID(uuidString: id) else {
        throw Exception(
          name: "InvalidUUID",
          description: "Invalid call ID: \(id)"
        )
      }

      let endedReason: CXCallEndedReason
      switch reason {
      case "failed": endedReason = .failed
      case "remoteEnded": endedReason = .remoteEnded
      case "unanswered": endedReason = .unanswered
      case "answeredElsewhere": endedReason = .answeredElsewhere
      case "declinedElsewhere": endedReason = .declinedElsewhere
      default: endedReason = .failed
      }

      await CallManager.shared.reportCallEnded(
        for: uuid,
        reason: endedReason
      )
    }

    // MARK: - Mute Support

    AsyncFunction("setMuted") { (id: String, muted: Bool) in
      guard let uuid = UUID(uuidString: id) else {
        throw Exception(
          name: "InvalidUUID",
          description: "Invalid call ID: \(id)"
        )
      }

      try await CallManager.shared.setMuted(for: uuid, muted: muted)
    }

    // MARK: - Video Support

    AsyncFunction("reportVideo") { (id: String, enabled: Bool) in
      guard let uuid = UUID(uuidString: id) else {
        throw Exception(
          name: "InvalidUUID",
          description: "Invalid call ID: \(id)"
        )
      }

      await CallManager.shared.reportVideo(for: uuid, enabled: enabled)
    }

    // MARK: - Hold Support

    AsyncFunction("setHeld") { (id: String, onHold: Bool) in
      guard let uuid = UUID(uuidString: id) else {
        throw Exception(
          name: "InvalidUUID",
          description: "Invalid call ID: \(id)"
        )
      }

      try await CallManager.shared.setHeld(for: uuid, onHold: onHold)
    }

    // MARK: - DTMF Support

    AsyncFunction("playDTMF") { (id: String, digits: String) in
      guard let uuid = UUID(uuidString: id) else {
        throw Exception(
          name: "InvalidUUID",
          description: "Invalid call ID: \(id)"
        )
      }

      try await CallManager.shared.playDTMF(for: uuid, digits: digits)
    }

    // MARK: - VoIP Push

    Function("registerVoIPPush") {
      Task { @MainActor in
        VoIPPushManager.shared.register()
      }
    }

    Function("getVoIPPushToken") { () -> [String: Any?] in
      return [
        "token": VoIPPushManager.shared.token as Any?,
        "type": "APNS_VOIP",
      ]
    }

  }
}
