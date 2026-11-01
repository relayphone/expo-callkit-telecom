---
description: Join an iOS call's media natively, before JavaScript loads, with expo-callkit-telecom v2 — a Swift CallMedia registered from an app delegate subscriber, with a LiveKit example.
---

# iOS: native media

By default, the module only coordinates the audio session on iOS, and your JavaScript connects
each conversation's media, such as its LiveKit room. A call that the user answers on a locked phone
after a cold launch then has no audio until JavaScript has loaded and connected the room, which can
take seconds.

Native media closes that gap. Register a Swift implementation of ios-voip-sdk's `CallMedia`, and
the SDK prepares each call's media natively, before it completes the answer, start or join, then
activates and deactivates it with the audio session:

| `CallMedia` | When the SDK calls it | What the media does |
| --- | --- | --- |
| `prepare(_:)` | Before it completes the answer, start or join. A throw, or a preparation that outlasts its deadline, fails the call, or returns a join to its call preview. | Connects the call's room, without using the microphone or speaker yet. |
| `activate()` | When the system activates the audio session. | Starts the microphone and speaker. |
| `deactivate()` | When the system deactivates or interrupts the audio session. | Stops using them. |

Without a registration, nothing changes: JavaScript connects the media, as before.

## Register the media

The module starts calls as the app launches, in its app delegate subscriber's
`application(_:didFinishLaunchingWithOptions:)`. Register before that, from an app delegate
subscriber of your own, in `application(_:willFinishLaunchingWithOptions:)`, which iOS calls
first. Registering later is a programming error, which stops Debug builds.

`NativeCallMedia.register(_:observers:)` takes the media and, optionally, `CallObserver`s, which
the module passes to `VoIPClient.start` after its own, so they follow calls from launch. Use one
to leave a call's media when the call ends, and to report its transport with
`VoIPClient.receive(_:for:eventID:)`, such as `.connected` once the room connected. A `.connected`
report while the SDK prepares the call takes effect as soon as the answer completes.

`NativeCallMedia.mediaCredentials(for:)` returns the `media` object of the VoIP push that brought
an incoming call, the same `LiveKitMediaCredentials` that Android's native answer uses, or of the
`reportIncomingConversation` payload. The module never shows them to JavaScript, and forgets them
when the call ends.

1. Create a local Expo module, which survives `prebuild --clean`:

   ```sh
   npx create-expo-module@latest --local call-media
   ```

2. Declare its app delegate subscriber in `modules/call-media/expo-module.config.json`:

   ```json
   {
     "platforms": ["apple"],
     "apple": { "appDelegateSubscribers": ["CallMediaAppDelegateSubscriber"] }
   }
   ```

3. In its podspec, depend on the module, on ios-voip-sdk's `Core` subspec, whose source the
   plugin adds to the Podfile, and on your media's SDK. LiveKit's Swift SDK is the `LiveKitClient`
   pod; add its podspec source to the Podfile as
   [LiveKit's CocoaPods guide](https://github.com/livekit/client-sdk-swift/blob/main/Docs/cocoapods.md)
   describes.

   ```ruby
   s.platforms = { :ios => '26.0' }
   s.swift_version = '6.0'
   s.dependency 'ExpoModulesCore'
   s.dependency 'ExpoCallKitTelecom'
   s.dependency 'IosVoipSDK/Core'
   s.dependency 'LiveKitClient'
   ```

4. Implement the media and register it. This example joins the LiveKit room with the VoIP push's
   credentials, reports the call connected once the room is, and leaves the room when the call
   ends. It assumes LiveKit's Swift SDK 2, whose
   [CallKit integration](https://github.com/livekit/client-sdk-swift#integration-with-callkit)
   leaves the audio session to the system; adapt it to your version and media.

   ```swift
   import AVFAudio
   import ExpoCallKitTelecom
   import ExpoModulesCore
   import Foundation
   import IosVoipSDK
   import LiveKit
   import UIKit

   /// Joins each call's LiveKit room natively, so that a call answered on a locked phone has audio
   /// before JavaScript loads.
   @VoIPActor
   final class LiveKitCallMedia: CallMedia {
     private var rooms: [CallID: Room] = [:]
     private let connected: @MainActor @Sendable (CallID) async -> Void

     /// - Parameter connected: Reports that a call's room connected.
     nonisolated init(connected: @escaping @MainActor @Sendable (CallID) async -> Void) {
       self.connected = connected
       // The system activates the audio session for calls, so LiveKit's audio engine waits for it.
       AudioManager.shared.audioSession.isAutomaticConfigurationEnabled = false
       try? AudioManager.shared.setEngineAvailability(.none)
     }

     func prepare(_ call: Call) async throws {
       // The VoIP push's `media` object. A call without one, such as an outgoing call, gets its
       // credentials from your servers instead.
       guard let credentials = NativeCallMedia.mediaCredentials(for: call.id),
         case .string(let url)? = credentials["url"],
         case .string(let token)? = credentials["token"]
       else { throw LiveKitCallMediaError.noCredentials }
       try AVAudioSession.sharedInstance().setCategory(
         .playAndRecord, mode: .voiceChat, options: [.allowBluetoothHFP])
       let room = Room()
       try await room.connect(url: url, token: token)
       try await room.localParticipant.setMicrophone(enabled: true)
       rooms[call.id] = room
       await connected(call.id)
     }

     func activate() {
       try? AudioManager.shared.setEngineAvailability(.default)
     }

     func deactivate() {
       try? AudioManager.shared.setEngineAvailability(.none)
     }

     func leave(_ id: CallID) async {
       await rooms.removeValue(forKey: id)?.disconnect()
     }
   }

   enum LiveKitCallMediaError: Error {
     case noCredentials
   }

   /// Follows calls from launch: reports that a call connected once its room did, and leaves a
   /// call's room when the call ends.
   @MainActor
   final class LiveKitCalls: CallObserver {
     private(set) lazy var media = LiveKitCallMedia { [weak self] id in
       await self?.client?.receive(.connected, for: id, eventID: EventID(rawValue: UUID().uuidString))
     }
     private weak var client: VoIPClient?
     private var task: Task<Void, Never>?

     func attach(to client: VoIPClient) {
       self.client = client
       let events = client.events()  // Subscribes before the VoIP push of a cold launch arrives.
       task = Task { [media] in
         for await case .ended(let call) in events { await media.leave(call.id) }
       }
     }
   }

   /// Registers the media before expo-callkit-telecom starts calls.
   public final class CallMediaAppDelegateSubscriber: ExpoAppDelegateSubscriber {
     private let calls = LiveKitCalls()

     public func application(
       _ application: UIApplication,
       willFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
     ) -> Bool {
       NativeCallMedia.register(calls.media, observers: [calls])
       return true
     }
   }
   ```

## In JavaScript

JavaScript learns of the media afterwards. Each conversation whose media the registered media
prepared has `media: "native"`, and its status follows your observer's reports: `connected` once
it reported the room connected. Don't connect its media from JavaScript too:

```ts
for (const conversation of useConversations()) {
  if (conversation.status === "connecting" && conversation.media !== "native") {
    void connectRoom(conversation); // The JavaScript path, for conversations without native media.
  }
}
```

The handler still places outgoing calls and joins from call previews; the SDK prepares the media
once the handler resolved. `mediaCredentials(for:)` is `nil` for an outgoing call, so get its
credentials from your servers in `prepare(_:)`, and report it connected when the other party joins.

## Audio

The registered media replaces the module's, so `mediaAdapter` no longer applies on iOS: the module
doesn't put react-native-webrtc's `RTCAudioSession` in manual audio mode. Your media configures the
audio session in `prepare(_:)` and uses it only between `activate()` and `deactivate()`; the
system activates it for calls. LiveKit's Swift SDK brings its own WebRTC build: if your JavaScript
no longer uses react-native-webrtc, remove it and set `mediaAdapter: "none"`.
