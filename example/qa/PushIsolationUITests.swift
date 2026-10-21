import Darwin
import XCTest

final class PushIsolationUITests: XCTestCase {
  private var application: XCUIApplication?

  override func tearDownWithError() throws {
    if let application {
      if (testRun?.failureCount ?? 0) > 0 {
        let screenshot = XCTAttachment(screenshot: application.screenshot())
        screenshot.name = "Failure screen"
        screenshot.lifetime = .keepAlways
        add(screenshot)
        let hierarchy = XCTAttachment(string: application.debugDescription)
        hierarchy.name = "Failure accessibility hierarchy"
        hierarchy.lifetime = .keepAlways
        add(hierarchy)
      }
      application.terminate()
    }
    application = nil
    try super.tearDownWithError()
  }
  private var runID = ""
  private var mailbox: URL?

  @MainActor
  private func control(_ operation: String, _ fields: [String: Any] = [:]) throws -> [String: Any] {
    let index = try XCTUnwrap(ProcessInfo.processInfo.environment["CKSIM_QA_CONTAINER_INDEX"])
    let container = try String(contentsOfFile: index, encoding: .utf8).trimmingCharacters(in: .whitespacesAndNewlines)
    let root = URL(fileURLWithPath: container).appendingPathComponent("Documents/.callkit-simulator")
    let ready = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
      guard let data = try? Data(contentsOf: root.appendingPathComponent("session.json")),
            let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
            object["runID"] as? String == self.runID else { return false }
      return true
    }, object: nil)
    let diagnostic = XCTAttachment(string: "[CKSIM-QA-readiness] session=\(root.appendingPathComponent("session.json").path), fileExists=\(FileManager.default.fileExists(atPath: root.appendingPathComponent("session.json").path))")
    diagnostic.name = "Runtime mailbox readiness"
    diagnostic.lifetime = .keepAlways
    add(diagnostic)
    wait(for: [ready], timeout: 10)
    let session = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: root.appendingPathComponent("session.json"))) as? [String: Any])
    XCTAssertEqual(session["runID"] as? String, runID)
    let folder = root.appendingPathComponent(runID)
    mailbox = folder
    let id = UUID().uuidString
    var command = fields
    for key in ["runID", "pid", "bundleID"] { command[key] = session[key] }
    command["id"] = id; command["operation"] = operation
    let data = try JSONSerialization.data(withJSONObject: command)
    try data.write(to: folder.appendingPathComponent("requests/\(id).json"), options: .atomic)
    let notification = try XCTUnwrap(session["notification"] as? String)
    XCTAssertEqual(notify_post(notification), UInt32(NOTIFY_STATUS_OK))
    let responseURL = folder.appendingPathComponent("responses/\(id).json")
    let replied = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
      FileManager.default.fileExists(atPath: responseURL.path)
    }, object: nil)
    wait(for: [replied], timeout: 10)
    let response = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: responseURL)) as? [String: Any])
    XCTAssertEqual(response["id"] as? String, id)
    XCTAssertEqual(response["runID"] as? String, runID)
    XCTAssertEqual(response["pid"] as? Int, session["pid"] as? Int)
    XCTAssertEqual(response["success"] as? Bool, true)
    return try XCTUnwrap(response["result"] as? [String: Any])
  }

  private func events() -> [[String: Any]] {
    guard let mailbox, let data = try? String(contentsOf: mailbox.appendingPathComponent("events.jsonl"), encoding: .utf8) else { return [] }
    return data.split(separator: "\n").compactMap { line in
      guard let bytes = String(line).data(using: .utf8) else { return nil }
      return (try? JSONSerialization.jsonObject(with: bytes)) as? [String: Any]
    }
  }

  @MainActor
  func testOptionalMalformedPushPreservesConnectedCall() throws {
    try malformedPush(mustReport: false, expectedPlaceholder: false, api: "metadata")
  }

  @MainActor
  func testRequiredMalformedPushPreservesConnectedCall() throws {
    try malformedPush(mustReport: true, expectedPlaceholder: true, api: "metadata")
  }

  @MainActor
  func testLegacyMalformedPushPreservesConnectedCall() throws {
    try malformedPush(mustReport: true, expectedPlaceholder: true, api: "legacy")
  }

  @MainActor
  func testEarlyAnswerBeforePushReportCompletion() throws {
    try incomingPublication(viaPush: true, decline: false)
  }

  @MainActor
  func testEarlyDeclineBeforePushReportCompletion() throws {
    try incomingPublication(viaPush: true, decline: true)
  }

  @MainActor
  func testEarlyAnswerBeforeAsyncReportCompletion() throws {
    try incomingPublication(viaPush: false, decline: false)
  }

  @MainActor
  private func incomingPublication(viaPush: Bool, decline: Bool) throws {
    continueAfterFailure = false
    let environment = ProcessInfo.processInfo.environment
    let libraries = try XCTUnwrap(environment["CKSIM_QA_RUNTIME_LIBRARIES"])
    let appInfo = try XCTUnwrap(environment["CKSIM_QA_APP_INFO"])
    let info = try XCTUnwrap(PropertyListSerialization.propertyList(from: Data(contentsOf: URL(fileURLWithPath: appInfo)), format: nil) as? [String: Any])
    XCTAssertEqual(info["ExpoCallKitTelecomIncomingCallTimeout"] as? Int, 3,
      "Regression must outlast the actual binary's configured ringing deadline")
    runID = UUID().uuidString
    let app = XCUIApplication(bundleIdentifier: "com.example.expocallkittelecom")
    application = app
    app.launchEnvironment = ["DYLD_INSERT_LIBRARIES": libraries, "CKSIM_ENABLED": "1", "CKSIM_RUN_ID": runID,
      "CKSIM_PUSH_AFTER": "", "CKSIM_PUSH_TRIGGER_NOTIFICATION": ""]
    app.launch()
    XCTAssertTrue(app.buttons["Start outgoing call"].waitForExistence(timeout: 15))
    _ = try control("faults", ["faults": ["staleAudio": false, "reportCallbackDelayMS": 30000]])
    let barrier = events().compactMap { $0["sequence"] as? Int }.max() ?? 0
    var pushID: String?
    if viaPush {
      let receipt = try control("push", ["mustReport": true, "payload": ["incomingCall": [
        "eventId": UUID().uuidString, "serverCallId": UUID().uuidString, "hasVideo": false,
        "caller": ["id": "publication-qa", "displayName": "Publication QA"]]]])
      pushID = try XCTUnwrap(receipt["pushID"] as? String)
    } else {
      app.buttons["Simulate incoming call"].tap()
      app.alerts.buttons["Audio"].tap()
    }
    let reported = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
      self.events().contains { $0["event"] as? String == "report.incoming" && ($0["sequence"] as? Int ?? 0) > barrier }
    }, object: nil)
    wait(for: [reported], timeout: 10)
    let report = try XCTUnwrap(events().first { $0["event"] as? String == "report.incoming" && ($0["sequence"] as? Int ?? 0) > barrier })
    if let pushID { XCTAssertEqual(report["pushID"] as? String, pushID) }
    let uuid = try XCTUnwrap(report["callUUID"] as? String)
    func callbackCompleted() -> Bool {
      events().contains { $0["event"] as? String == "report.completed" && $0["callUUID"] as? String == uuid }
    }
    XCTAssertFalse(callbackCompleted(), "Action must precede delayed report completion")
    let action = try control(decline ? "decline" : "answer", ["callUUID": uuid])
    let actionID = try XCTUnwrap((action["actionIDs"] as? [String])?.first)
    let settled = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
      self.events().contains { $0["event"] as? String == "action.settled" && $0["actionID"] as? String == actionID && $0["fulfilled"] as? Bool == true }
    }, object: nil)
    let identifier = app.staticTexts["call-session-id"]
    if !decline {
      XCTAssertTrue(app.staticTexts["Connecting"].waitForExistence(timeout: 5))
      XCTAssertEqual(identifier.label, "Call ID: \(uuid)")
      let connect = app.buttons["Connect Call"]
      let enabled = expectation(for: NSPredicate(format: "enabled == true"), evaluatedWith: connect)
      wait(for: [enabled], timeout: 5)
      connect.tap()
      XCTAssertTrue(app.staticTexts["Connected"].waitForExistence(timeout: 5))
      XCTAssertFalse(callbackCompleted(), "Connection must be established before callback delivery")
    }
    wait(for: [settled], timeout: 10)
    let callback = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in callbackCompleted() }, object: nil)
    wait(for: [callback], timeout: 40)
    let completed = try XCTUnwrap(events().first { $0["event"] as? String == "report.completed" && $0["callUUID"] as? String == uuid })
    let fulfilled = try XCTUnwrap(events().first { $0["event"] as? String == "action.settled" && $0["actionID"] as? String == actionID })
    XCTAssertLessThan(try XCTUnwrap(fulfilled["sequence"] as? Int), try XCTUnwrap(completed["sequence"] as? Int),
      "Delegate must actually settle the action before the delayed report callback")
    if let pushID {
      let published = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
        self.events().contains { $0["event"] as? String == "push.completed" && $0["pushID"] as? String == pushID }
      }, object: nil)
      wait(for: [published], timeout: 10)
    } else {
      // IncomingCallReported is emitted by the library's publication task,
      // after CallKit's callback has returned to it.
      let published = app.staticTexts.containing(NSPredicate(format: "label CONTAINS %@", "IncomingCallReported")).firstMatch
      XCTAssertTrue(published.waitForExistence(timeout: 10))
    }
    let expected = decline ? "Call ID: none" : "Call ID: \(uuid)"
    XCTAssertEqual(identifier.label, expected)
    let changed = expectation(for: NSPredicate(format: "label != %@", expected), evaluatedWith: identifier)
    changed.isInverted = true
    wait(for: [changed], timeout: 4)
    if !decline {
      XCTAssertTrue(app.staticTexts["Connected"].exists, "Late completion must not recreate ringing or arm its 3s timeout")
      _ = try control("end", ["callUUID": uuid])
      let cleared = expectation(for: NSPredicate(format: "label == %@", "Call ID: none"), evaluatedWith: identifier)
      wait(for: [cleared], timeout: 5)
    }
    let calls = try XCTUnwrap(control("status")["calls"] as? [[String: Any]])
    XCTAssertTrue(calls.isEmpty)
    XCTAssertTrue(app.buttons["Start outgoing call"].isEnabled)
  }

  @MainActor
  private func malformedPush(mustReport: Bool, expectedPlaceholder: Bool, api: String) throws {
    continueAfterFailure = false
    let environment = ProcessInfo.processInfo.environment
    let libraries = try XCTUnwrap(environment["CKSIM_QA_RUNTIME_LIBRARIES"])
    XCTAssertEqual(environment["CKSIM_QA_PUSH_API"], api)
    runID = UUID().uuidString
    let app = XCUIApplication(bundleIdentifier: "com.example.expocallkittelecom")
    application = app
    app.launchEnvironment = ["DYLD_INSERT_LIBRARIES": libraries, "CKSIM_ENABLED": "1",
      "CKSIM_RUN_ID": runID, "CKSIM_PUSH_AFTER": "", "CKSIM_PUSH_TRIGGER_NOTIFICATION": ""]
    app.launch()
    let start = app.buttons["Start outgoing call"]
    XCTAssertTrue(start.waitForExistence(timeout: 15))
    let adapter = try control("inspect-adapter")
    XCTAssertEqual(adapter["metadataSelector"] as? Bool, api == "metadata",
      "Legacy qualification must inspect the actual library binary")
    start.tap()
    app.alerts.buttons["Audio"].tap()
    let connect = app.buttons["Connect Call"]
    let ready = expectation(for: NSPredicate(format: "enabled == true"), evaluatedWith: connect)
    wait(for: [ready], timeout: 8)
    connect.tap()
    let connected = app.staticTexts["Connected"]
    XCTAssertTrue(connected.waitForExistence(timeout: 8))
    let callID = app.staticTexts["call-session-id"].label
    XCTAssertNotEqual(callID, "Call ID: none")
    let activeUUID = try XCTUnwrap(UUID(uuidString: callID.replacingOccurrences(of: "Call ID: ", with: "")))
    let beforePush = events().compactMap { $0["sequence"] as? Int }.max() ?? 0
    let push = try control("push", ["mustReport": mustReport,
      "payload": ["aps": [:], "from": "+15555550142", "callId": "invalid-payload-qa"]])
    let pushID = try XCTUnwrap(push["pushID"] as? String)
    let completed = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
      self.events().contains { $0["event"] as? String == "push.completed" && $0["pushID"] as? String == pushID }
    }, object: nil)
    wait(for: [completed], timeout: 15)
    let receipts = events().filter { $0["pushID"] as? String == pushID }
    func one(_ event: String) throws -> [String: Any] {
      let rows = receipts.filter { $0["event"] as? String == event }
      XCTAssertEqual(rows.count, 1, "Expected one runtime \(event)")
      return try XCTUnwrap(rows.first)
    }
    let received = try one("push.received")
    XCTAssertEqual(received["api"] as? String, api)
    XCTAssertEqual(received["mustReport"] as? Bool, mustReport)
    let completion = try one("push.completed")
    if expectedPlaceholder {
      let reported = try one("report.incoming")
      let accepted = try one("report.completed")
      let ended = try one("report.ended")
      let placeholder = try XCTUnwrap(reported["callUUID"] as? String)
      XCTAssertNotEqual(UUID(uuidString: placeholder), activeUUID)
      XCTAssertEqual(accepted["callUUID"] as? String, placeholder)
      XCTAssertEqual(accepted["error"] as? Int, 0)
      XCTAssertEqual(ended["callUUID"] as? String, placeholder)
      XCTAssertEqual(ended["reason"] as? Int, 1)
      let order = try [reported, accepted, ended, completion].map { try XCTUnwrap($0["sequence"] as? Int) }
      XCTAssertTrue(zip(order, order.dropFirst()).allSatisfy { $0.0 < $0.1 }, "Report, end and completion order")
    } else {
      XCTAssertFalse(receipts.contains { ($0["event"] as? String)?.hasPrefix("report.") == true },
        "Optional malformed push must not create a placeholder")
    }
    XCTAssertTrue(connected.exists)
    XCTAssertEqual(app.staticTexts["call-session-id"].label, callID)
    app.buttons["Mute"].tap()
    XCTAssertTrue(app.buttons["Unmute"].waitForExistence(timeout: 5))
    let lostCall = expectation(for: NSPredicate(format: "exists == false"), evaluatedWith: connected)
    lostCall.isInverted = true
    wait(for: [lostCall], timeout: 2)
    XCTAssertFalse(app.staticTexts["Invalid Call"].exists)
    let allReports = events().filter {
      ($0["sequence"] as? Int ?? 0) > beforePush && ($0["event"] as? String)?.hasPrefix("report.") == true
    }
    XCTAssertEqual(allReports.count, expectedPlaceholder ? 3 : 0,
      "Check the entire post-push journal, including asynchronous uncorrelated reports")
    let screenshot = XCTAttachment(screenshot: app.screenshot())
    screenshot.name = "Connected call survives malformed push"
    screenshot.lifetime = .keepAlways
    add(screenshot)
    app.swipeUp()
    app.buttons["End Call"].tap()
    app.swipeDown()
    let cleared = expectation(for: NSPredicate(format: "label == %@", "Call ID: none"),
      evaluatedWith: app.staticTexts["call-session-id"])
    wait(for: [cleared], timeout: 5)
    XCTAssertFalse(connected.exists)
    XCTAssertTrue(app.buttons["Start outgoing call"].isEnabled)
  }
}
