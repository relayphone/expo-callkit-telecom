import AVFAudio
import Foundation
import IosVoipSDK

/// The audio routes that the TypeScript API shows, from the audio session.
///
/// The routes are the session's available inputs and the speaker; choosing an input also routes
/// output to it, as the system call screen does, and choosing the iPhone's microphone routes
/// output to its receiver. The current route is the session's actual output, which needn't be
/// one of the routes.
@MainActor
final class AudioRoutes {
  private static let speakerID = "speaker"
  private static let inputPrefix = "input:"

  private(set) var revision = 0
  private(set) var routes: [JSONValue] = []
  private(set) var current: JSONValue = .null
  private let changed: @MainActor (JSONValue) -> Void
  private var observer: (any NSObjectProtocol)?

  /// Starts following the audio session's routes.
  ///
  /// - Parameter changed: Receives the new ``snapshot`` when the routes or the current route
  ///   changed.
  init(changed: @escaping @MainActor (JSONValue) -> Void) {
    self.changed = changed
    observer = NotificationCenter.default.addObserver(
      forName: AVAudioSession.routeChangeNotification, object: nil, queue: .main
    ) { [weak self] _ in
      MainActor.assumeIsolated { self?.refresh() }
    }
    refresh()
  }

  /// The routes as the TypeScript API's `AudioRoutesSnapshot`.
  var snapshot: JSONValue {
    .object(["revision": .number(Double(revision)), "routes": .array(routes), "current": current])
  }

  /// Reads the audio session's routes again.
  func refresh() {
    let session = AVAudioSession.sharedInstance()
    var routes = (session.availableInputs ?? []).map { input in
      Self.route(id: Self.inputPrefix + input.uid, name: input.portName, type: Self.type(of: input))
    }
    routes.append(Self.route(id: Self.speakerID, name: "Speaker", type: "builtInSpeaker"))
    let current = session.currentRoute.outputs.first.map { output in
      Self.route(id: "output:" + output.uid, name: output.portName, type: Self.type(of: output))
    }
    guard routes != self.routes || (current ?? .null) != self.current else { return }
    self.routes = routes
    self.current = current ?? .null
    revision += 1
    changed(snapshot)
  }

  /// Routes the call's audio to a route from ``routes``.
  func select(_ id: String) throws(CommandError) {
    let session = AVAudioSession.sharedInstance()
    let input = session.availableInputs?.first { Self.inputPrefix + $0.uid == id }
    guard id == Self.speakerID || input != nil else { throw .invalidArguments }
    do {
      if let input {
        try session.overrideOutputAudioPort(.none)
        try session.setPreferredInput(input)
      } else {
        try session.overrideOutputAudioPort(.speaker)
      }
    } catch {
      throw CommandError(code: .conversation, message: "The audio session refused the route")
    }
  }

  private static func route(id: String, name: String, type: String) -> JSONValue {
    .object(["id": .string(id), "name": .string(name), "type": .string(type)])
  }

  /// The TypeScript API's `AudioRouteType` for a port. The iPhone's microphone stands for its
  /// receiver.
  private static func type(of port: AVAudioSessionPortDescription) -> String {
    switch port.portType {
    case .builtInMic, .builtInReceiver: "builtInReceiver"
    case .builtInSpeaker: "builtInSpeaker"
    case .headsetMic, .headphones: "headphones"
    case .bluetoothA2DP: "bluetoothA2DP"
    case .bluetoothLE: "bluetoothLE"
    case .bluetoothHFP: "bluetoothHFP"
    case .airPlay: "airPlay"
    case .HDMI: "hdmi"
    case .carAudio: "carAudio"
    case .usbAudio: "usbAudio"
    case .lineOut: "lineOut"
    default: "unknown"
    }
  }
}
