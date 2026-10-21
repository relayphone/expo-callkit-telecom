import Collections
import Foundation

/// Thread-safe store for active call sessions.
actor CallStore {
  private var sessions: OrderedDictionary<UUID, CallSession> = [:]
  nonisolated let incomingReports = IncomingCallReports()

  private struct SessionObserver: Sendable {
    let continuation: AsyncStream<CallSession>.Continuation
    let filter: @Sendable (CallSession) -> Bool
  }
  private var sessionObservers: [UUID: [UUID: SessionObserver]] = [:]

  /// The first session, if any.
  var firstSession: CallSession? {
    allSessions.first
  }

  /// All sessions in insertion order.
  var allSessions: [CallSession] {
    var result = Array(sessions.values)
    for report in incomingReports.all {
      let value = report.withSession { $0 }
      if let index = result.firstIndex(where: { $0.id == report.id }) {
        if let value { result[index] = value } else { result.remove(at: index) }
      } else if let value { result.append(value) }
    }
    return result
  }

  /// Get a session by its system call ID.
  func session(for id: UUID) -> CallSession? {
    if let report = incomingReports.report(for: id) { return report.withSession { $0 } }
    return sessions[id]
  }

  /// Commit under the same lock used by synchronous answer/end transitions.
  @discardableResult
  func publishIncoming(_ report: IncomingCallReport) -> Bool {
    report.withSession { session in
      guard let session else { return false }
      add(session)
      return true
    }
  }

  /// Add a new session. No-op if session with same ID already exists.
  func add(_ session: CallSession) {
    guard sessions[session.id] == nil else { return }
    sessions[session.id] = session
    Task { @MainActor in
      CallEventEmitter.shared.send(CallSessionAddedEvent(session: session))
    }
  }

  /// Remove a session by its system call ID. No-op if session doesn't exist.
  func remove(for id: UUID) {
    let report = incomingReports.report(for: id)
    report?.ended()
    defer {
      if let report {
        report.removedFromStore()
        incomingReports.discardEnded(report)
      }
    }
    guard sessions.removeValue(forKey: id) != nil else { return }
    Task { @MainActor in
      CallEventEmitter.shared.send(CallSessionRemovedEvent(id: id))
    }
  }

  /// Remove all sessions.
  func removeAll() {
    incomingReports.endAll()
    let ids = Array(sessions.keys)
    sessions.removeAll()
    for report in incomingReports.all {
      report.removedFromStore()
      incomingReports.discardEnded(report)
    }
    Task { @MainActor in
      for id in ids {
        CallEventEmitter.shared.send(CallSessionRemovedEvent(id: id))
      }
    }
  }

  // MARK: - Updates

  /// Update a session using a closure.
  /// Only sends update events if the session actually changed.
  func update(for id: UUID, _ transform: (inout CallSession) -> Void) {
    if let report = incomingReports.report(for: id) {
      report.withSession { value in
        guard let session = value else { return }
        if sessions[id] == nil { add(session) }
        value = applyUpdate(session, transform)
      }
    } else if let session = sessions[id] {
      _ = applyUpdate(session, transform)
    }
  }

  private func applyUpdate(_ current: CallSession, _ transform: (inout CallSession) -> Void) -> CallSession {
    var session = current
    let id = session.id
    let previousSession = sessions[id] ?? current
    transform(&session)

    // Skip if nothing changed
    guard session != previousSession else { return session }

    sessions[id] = session

    // Notify observers whose filter matches
    sessionObservers[id]?.values.forEach { observer in
      if observer.filter(session) {
        observer.continuation.yield(session)
      }
    }

    Task { @MainActor in
      CallEventEmitter.shared.send(CallSessionUpdatedEvent(session: session))
    }
    return session
  }

  /// Update the status of a session.
  func updateStatus(for id: UUID, status: CallSession.Status) {
    update(for: id) { session in
      session.status = status
    }
  }

  /// Update the connectedAt timestamp of a session.
  func updateConnectedAt(for id: UUID, connectedAt: Date?) {
    update(for: id) { session in
      session.connectedAt = connectedAt
    }
  }

  /// Update the muted state of a session.
  func updateMuted(for id: UUID, isMuted: Bool) {
    update(for: id) { session in
      session.isMuted = isMuted
    }
  }

  /// Update the held state of a session.
  func updateHeld(for id: UUID, isOnHold: Bool) {
    update(for: id) { session in
      session.isOnHold = isOnHold
    }
  }

  // MARK: - Session Observation

  /// Returns an AsyncStream that emits session updates matching the filter.
  /// Immediately emits the current session if it exists and matches the filter.
  func sessionUpdates(
    for callId: UUID,
    where filter: @escaping @Sendable (CallSession) -> Bool = { _ in true }
  ) -> AsyncStream<CallSession> {
    AsyncStream { continuation in
      let observerId = UUID()

      // Emit current session if it matches
      if let session = session(for: callId), filter(session) {
        continuation.yield(session)
      }

      // Register observer
      if sessionObservers[callId] == nil {
        sessionObservers[callId] = [:]
      }
      sessionObservers[callId]?[observerId] = SessionObserver(
        continuation: continuation,
        filter: filter
      )

      continuation.onTermination = { @Sendable [weak self] _ in
        guard let self else { return }
        Task { await self.removeSessionObserver(callId: callId, observerId: observerId) }
      }
    }
  }

  private func removeSessionObserver(callId: UUID, observerId: UUID) {
    sessionObservers[callId]?[observerId] = nil
    if sessionObservers[callId]?.isEmpty == true {
      sessionObservers[callId] = nil
    }
  }
}
