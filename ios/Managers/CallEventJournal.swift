import Foundation

/// Process-local acknowledged replay, with bounded retention and explicit gaps.
/// Bridge teardown never touches calls or native deadlines.
@MainActor
final class CallEventJournal {
  struct Entry {
    let revision: Int
    let name: String
    let body: [String: Any]
    let timestamp: Date

    func dictionary(epoch: String, replayed: Bool) -> [String: Any] {
      var payload = body
      payload["meta"] = ["flushed": replayed, "timestamp": ISO8601DateFormatter().string(from: timestamp),
                         "epoch": epoch, "revision": revision]
      let deadline = body["deadlineUnixMS"] as? Double
      return ["epoch": epoch, "revision": revision, "name": name, "body": payload,
              "timestamp": ISO8601DateFormatter().string(from: timestamp),
              "expired": deadline.map { $0 <= Date().timeIntervalSince1970 * 1000 } ?? false]
    }
  }
  let epoch = UUID().uuidString
  private(set) var revision = 0
  private(set) var entries: [Entry] = []
  private var sessions: [String: [String: Any]] = [:]
  private var terminalCalls = Set<String>()
  private var retainedBytes = 0
  private var sizes: [Int: Int] = [:]
  private var droppedThroughRevision = 0
  private var acknowledgedRevision = 0

  func append(name: String, body: [String: Any], timestamp: Date) -> Entry {
    revision += 1
    let entry = Entry(revision: revision, name: name, body: body, timestamp: timestamp)
    entries.append(entry)
    let size = (try? JSONSerialization.data(withJSONObject: body).count) ?? 0
    sizes[revision] = size; retainedBytes += size
    if (name == "onCallSessionAdded" || name == "onCallSessionUpdated"),
      let session = body["session"] as? [String: Any], let id = session["id"] as? String {
      if !terminalCalls.contains(id) { sessions[id] = session }
    }
    if name == "onCallSessionRemoved", let id = body["id"] as? String { sessions.removeValue(forKey: id) }
    if (name == "onCallEnded" || name == "onCallReportedEnded" || name == "onCallSessionRemoved"), let id = body["id"] as? String {
      terminalCalls.insert(id)
      sessions.removeValue(forKey: id)
    }
    // Existing applications using only legacy listeners must not retain an
    // unbounded second event history. Overflow is explicit in the first attach.
    while entries.count > 1024 || retainedBytes > 4 * 1024 * 1024 {
      let discarded = entries.removeFirst()
      retainedBytes -= sizes.removeValue(forKey: discarded.revision) ?? 0
      droppedThroughRevision = discarded.revision
    }
    terminalCalls.formIntersection(Set(entries.compactMap { $0.body["id"] as? String }))
    return entry
  }
  func dictionary(_ entry: Entry, replayed: Bool) -> [String: Any] {
    var result = entry.dictionary(epoch: epoch, replayed: replayed)
    if entry.name == "onCallAnswered", let id = entry.body["id"] as? String, terminalCalls.contains(id) { result["expired"] = true }
    return result
  }
  func snapshot(consumerID: String) -> [String: Any] {
    return ["consumerID": consumerID, "epoch": epoch, "revision": revision,
     "replayComplete": droppedThroughRevision <= acknowledgedRevision, "droppedThroughRevision": droppedThroughRevision,
     "events": entries.map { dictionary($0, replayed: true) },
     "sessions": sessions.keys.sorted().compactMap { sessions[$0] }]
  }
  func acknowledge(through revision: Int) {
    acknowledgedRevision = max(acknowledgedRevision, revision)
    for entry in entries where entry.revision <= revision { retainedBytes -= sizes.removeValue(forKey: entry.revision) ?? 0 }
    entries.removeAll { $0.revision <= revision }
    let retainedCalls = Set(entries.compactMap { $0.body["id"] as? String })
    terminalCalls.formIntersection(retainedCalls)
  }
}
