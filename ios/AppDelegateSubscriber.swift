public import ExpoModulesCore
import Intents
public import UIKit

/// Starts the SDK's client when the app launches, before JavaScript runs, and passes the system's
/// requests to call someone on to JavaScript.
///
/// Starting at launch registers for VoIP pushes, so a push that launched the app is reported, and
/// the SDK answers natively while JavaScript loads.
public final class ExpoCallKitTelecomAppDelegateSubscriber: ExpoAppDelegateSubscriber {
  public func application(
    _ application: UIApplication,
    didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
  ) -> Bool {
    CallService.shared.start()
    return true
  }

  /// Delivers a call intent from Recents, Siri or Contacts as a `dialRequest` conversation event.
  public func application(
    _ application: UIApplication,
    continue userActivity: NSUserActivity,
    restorationHandler: @escaping ([any UIUserActivityRestoring]?) -> Void
  ) -> Bool {
    defer { restorationHandler(nil) }
    guard
      let intent = userActivity.interaction?.intent as? INStartCallIntent,
      let handle = intent.contacts?.first?.personHandle?.value
    else { return false }
    CallService.shared.dialRequested(handle: handle, video: intent.callCapability == .videoCall)
    return true
  }
}
