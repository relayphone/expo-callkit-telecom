import Foundation

/// A synchronous reservation bridges CallKit's callbacks and the actor store.
/// Terminal state remains until both reporting and store cleanup finish.
final class IncomingCallReport: @unchecked Sendable {
  let id: UUID
  private let lock = NSLock()
  private var session: CallSession?
  private var reportResolved = false
  private var storeRemoved = false

  init(_ session: CallSession) {
    id = session.id
    self.session = session
  }

  func withSession<T>(_ body: (inout CallSession?) -> T) -> T {
    lock.lock()
    defer { lock.unlock() }
    return body(&session)
  }

  func answered() {
    withSession { session in
      if session?.status == .ringing { session?.status = .connecting }
    }
  }

  @discardableResult
  func ended() -> CallSession? {
    withSession { session in
      var ended = session
      ended?.status = .ended
      session = nil
      return ended
    }
  }

  func resolved() {
    lock.lock()
    reportResolved = true
    lock.unlock()
  }

  var canDiscard: Bool {
    lock.lock()
    defer { lock.unlock() }
    return session == nil && reportResolved && storeRemoved
  }

  func removedFromStore() {
    lock.lock()
    storeRemoved = true
    lock.unlock()
  }

  func whileRinging(_ body: () -> Void) {
    withSession { if $0?.status == .ringing { body() } }
  }
}

final class IncomingCallReports: @unchecked Sendable {
  private let lock = NSLock()
  private var reports: [UUID: IncomingCallReport] = [:]
  private var order: [UUID] = []

  func reserve(_ session: CallSession) -> IncomingCallReport {
    lock.lock()
    defer { lock.unlock() }
    let report = IncomingCallReport(session)
    reports[session.id] = report
    order.append(session.id)
    return report
  }

  func report(for id: UUID) -> IncomingCallReport? {
    lock.lock()
    defer { lock.unlock() }
    return reports[id]
  }

  var all: [IncomingCallReport] {
    lock.lock()
    defer { lock.unlock() }
    return order.compactMap { reports[$0] }
  }

  func finish(_ report: IncomingCallReport) {
    report.resolved()
    discardEnded(report)
  }

  func discardEnded(_ report: IncomingCallReport) {
    lock.lock()
    defer { lock.unlock() }
    if reports[report.id] === report && report.canDiscard {
      reports[report.id] = nil
      order.removeAll { $0 == report.id }
    }
  }

  func endAll() {
    for report in all {
      report.ended()
      discardEnded(report)
    }
  }
}
