#if EXPO_CALLKIT_TELECOM_FOCUS
  import Foundation
  import IosVoipSDK

  /// The Focus filter state, with the `focusFilter` plugin option: what the app's Focus filter
  /// last asked for, which the filter records in the App Group wherever the system runs it.
  ///
  /// The module doesn't know the app's settings type, so it reads the settings as JSON. JavaScript
  /// receives the state exactly as ios-voip-sdk encodes it for the app's servers, as a revisioned
  /// snapshot.
  @MainActor
  final class FocusFilter {
    private let store: FocusFilterStore<JSONValue>
    private let changed: @MainActor (JSONValue) -> Void
    private var revision = 0
    private var state: JSONValue
    private var task: Task<Void, Never>?

    /// Creates the Focus filter state of an App Group.
    ///
    /// - Parameters:
    ///   - appGroup: The App Group in which the app's Focus filter records its state.
    ///   - changed: Sends a new snapshot to JavaScript.
    init(appGroup: String, changed: @escaping @MainActor (JSONValue) -> Void) {
      store = FocusFilterStore(appGroup: appGroup)
      self.changed = changed
      state = Self.json(store.state)
    }

    /// The TypeScript API's `FocusFilterStateSnapshot`.
    var snapshot: JSONValue {
      .object(["revision": .number(Double(revision)), "state": state])
    }

    /// Follows the state's changes, which any process that shares the App Group records.
    func start() {
      guard task == nil else { return }
      let states = store.states()
      task = Task { [weak self] in
        for await state in states { self?.update(state) }
      }
    }

    private func update(_ state: FocusFilterState<JSONValue>) {
      let json = Self.json(state)
      guard json != self.state else { return }
      self.state = json
      revision += 1
      changed(snapshot)
    }

    /// The state's JSON object, as the SDK encodes it for the app's servers.
    private static func json(_ state: FocusFilterState<JSONValue>) -> JSONValue {
      guard let data = try? JSONEncoder().encode(state),
        let json = try? JSONDecoder().decode(JSONValue.self, from: data)
      else { return .object(["state": .string("unknown")]) }
      return json
    }
  }
#endif
