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
  @MainActor
  func testMalformedPushPreservesConnectedCall() throws {
    continueAfterFailure = false
    let environment = ProcessInfo.processInfo.environment
    let libraries = try XCTUnwrap(environment["CKSIM_QA_RUNTIME_LIBRARIES"])
    let api = try XCTUnwrap(environment["CKSIM_QA_PUSH_API"])
    XCTAssertTrue(["metadata", "legacy"].contains(api))
    let receipt = "dev.callkitsim.push.\(UUID().uuidString)"
    let delivered = expectation(description: "malformed PushKit callback completed")
    delivered.assertForOverFulfill = true
    var notification: Int32 = 0
    var connectedBeforePush = false
    XCTAssertEqual(
      notify_register_dispatch(receipt + "." + api, &notification, .main) { _ in
        XCTAssertTrue(connectedBeforePush, "fixture arrived before call A was connected")
        delivered.fulfill()
      }, UInt32(NOTIFY_STATUS_OK))
    defer { notify_cancel(notification) }

    let app = XCUIApplication(bundleIdentifier: "com.example.expocallkittelecom")
    application = app
    app.launchEnvironment = [
      "DYLD_INSERT_LIBRARIES": libraries,
      "CKSIM_ENABLED": "1",
      "CKSIM_PUSH_TRIGGER_NOTIFICATION": receipt + ".trigger",
      "CKSIM_PUSH_COMPLETION_NOTIFICATION": receipt,
    ]
    app.launch()
    let start = app.buttons["Start outgoing call"]
    XCTAssertTrue(start.waitForExistence(timeout: 15))
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
    connectedBeforePush = true
    XCTAssertEqual(notify_post(receipt + ".trigger"), UInt32(NOTIFY_STATUS_OK))
    wait(for: [delivered], timeout: 40)

    // Exercise a genuine SDK round trip after malformed push B has completed.
    // This catches ending A, replacing its session, and losing delegate delivery.
    XCTAssertTrue(connected.exists)
    XCTAssertEqual(app.staticTexts["call-session-id"].label, callID)
    app.buttons["Mute"].tap()
    XCTAssertTrue(app.buttons["Unmute"].waitForExistence(timeout: 5))
    let lostCall = expectation(
      for: NSPredicate(format: "exists == false"), evaluatedWith: connected)
    lostCall.isInverted = true
    wait(for: [lostCall], timeout: 2)
    XCTAssertFalse(app.staticTexts["Invalid Call"].exists)
    let screenshot = XCTAttachment(screenshot: app.screenshot())
    screenshot.name = "Connected call survives malformed push"
    screenshot.lifetime = .keepAlways
    add(screenshot)
    app.swipeUp()
    app.buttons["End Call"].tap()
    app.swipeDown()
    let cleared = expectation(
      for: NSPredicate(format: "label == %@", "Call ID: none"),
      evaluatedWith: app.staticTexts["call-session-id"])
    wait(for: [cleared], timeout: 5)
    XCTAssertFalse(connected.exists)
    XCTAssertTrue(app.buttons["Start outgoing call"].isEnabled)
  }
}
