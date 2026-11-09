# Platform notes

## iOS

The SDK supplies LiveCommunicationKit and PushKit through its sole Apple native
provider. Follow its [React Native guide](https://github.com/relayphone/ios-voip-sdk/blob/main/docs/react-native.md)
for current iOS/Expo requirements, entitlements, notifications, previews and Focus
configuration. Its package includes the native sources; do not add a separate SDK
pod or Swift package. The composed fork plugin invokes the SDK plugin.

`mediaAdapter: "webrtc" | "none"` selects SDK audio integration. LiveKit React
Native uses `"webrtc"`. For media prepared before JavaScript starts, follow
[iOS native media](./ios-native-media.md). Call previews and `providerReset` are
iOS-only.

## Android

Android requires API 26 or later and uses Core-Telecom. The plugin supplies the
native FCM service and call-related manifest configuration. The SDK guide owns
the shared Expo requirements; use a rebuilt development client containing both
packages, rather than Expo Go. Follow [Getting started](./getting-started.md).

Answer, end, hold and mute run natively without an action handler. Observe
snapshots and `actionPerformed`; the handler places outgoing starts only.
Android advertises `capabilities.tones = false`; tones and merging reject with
`unsupported`. Filtered and answered-elsewhere calls terminate and remove their
notification. Preview joining is unsupported.

`androidNativeMedia: "livekit"` selects native media and prewarm; `"none"` is the
default and leaves media to the app. Native output and capture wait for audio
authority. `audioActivated` follows successful native audio authority, or Core
activation with app-managed media. Hold and disconnect deactivate audio; native
authority's 1500 ms timeout emits `audioActivationMissing`.

### Incoming call UI

The module posts a CallStyle notification for the Core-Telecom call, with answer
and decline actions. Its full-screen intent opens the app's launch activity;
show the app's call UI from conversation snapshots. Android controls DND,
notification presentation and visible dismissal timing. Admission alone does
not prove audible ringing. See [incoming policy](./contracts/android-incoming-policy.md).

### Declined calls without JavaScript

Native decline closes the call and native media without waiting for JavaScript.
There is no `handler.end` callback. Use snapshots and `actionPerformed` while the
bridge is alive; apps needing native integration can observe `NativeMediaSignals`
and incoming outcomes through `NativeIncomingOutcomes`.

### Ending a call from the server

Send a high-priority FCM data message to stop a known incoming call natively:

```json
{
  "messageType": "callEnded",
  "callEnded": "{\"eventId\":\"end-789\",\"serverCallId\":\"call-123\",\"reason\":\"answeredElsewhere\"}"
}
```

The host equivalent is `receiveCallEnded({ eventId, serverCallId, reason })`.
The signal targets retained incoming ownership, including admission in flight;
unknown signals do not create future-call tombstones. Local hangups use
`endConversation`. Server reports create no performed end action. For
`answeredElsewhere`, terminal state precedes the correlated event carrying
`conversationId`, `serverCallId` and `eventId`. Acceptance acknowledges the signal,
not completed platform or UI dismissal. See the
[answered-elsewhere contract](./contracts/android-answered-elsewhere.md) and
[public push sender](../example/server/README.md).

## Push tokens and background media

`usePushToken()` returns `{ type: "apns-voip", value }` on iOS and
`{ type: "fcm", value }` on Android, or `null` when unavailable. Send token changes
and their type to the backend. Follow the [push guide](./voip-push.md) for payloads.
Native call lifecycle does not guarantee JavaScript timer or network heartbeat
delivery in the background. Use the media stack's supported native background
integration when the application requires it.
