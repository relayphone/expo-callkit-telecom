---
description: Platform requirements for expo-callkit-telecom v2 — iOS 26+ (LiveCommunicationKit + PushKit through ios-voip-sdk), Android API 26+ (Jetpack androidx.core-telecom). Keep-alive notes for background WebSocket signalling.
---

# Platform notes

## iOS

- Minimum iOS version: **26.0**.
- Presents calls with [LiveCommunicationKit](https://developer.apple.com/documentation/livecommunicationkit) and receives VoIP pushes with [PushKit](https://developer.apple.com/documentation/pushkit), through [ios-voip-sdk](https://github.com/relayphone/ios-voip-sdk)'s `VoIPClient`. Prebuild adds the SDK's pod to the Podfile at a pinned revision. There's no CallKit option.
- The module starts the client when the app launches, before JavaScript loads, so a VoIP push that launches the app is reported at once and the user can answer before JavaScript runs.
- The plugin adds the `voip` and `audio` background modes and the `aps-environment` entitlement. Sending VoIP pushes needs an APNs authentication key or a VoIP services certificate.
- With `mediaAdapter` `"webrtc"` (the default) or `"livekit"`, the module puts [WebRTC](https://webrtc.org/)'s `RTCAudioSession` in manual audio mode: WebRTC's audio starts when the system activates the call's audio session and stops when it deactivates it. With `"none"`, the app manages the audio session.
- A `callEnded` VoIP push, in the same format as [Android's data message](#ending-a-call-from-the-server), or `receiveCallEnded`, ends a known incoming call. Before iOS 26.4 every VoIP push must report a call, so for such a push the SDK reports the call again when the system still presents it, or else reports a placeholder call and ends it at once.
- A call that the system filters, for example under a Focus, or that is answered elsewhere, becomes a call preview instead of ringing. See [iOS](./migrating-v1-to-v2#ios) for this and the other differences from Android.

## Expo

- Supports Expo SDK **55** and later.

## Android

- Requires [`MANAGE_OWN_CALLS`](https://developer.android.com/reference/android/Manifest.permission#MANAGE_OWN_CALLS) permission.
- Minimum SDK: **26** (Android 8.0).
- Uses [`androidx.core:core-telecom`](https://developer.android.com/develop/connectivity/telecom/voip-app/telecom).
- Incoming calls come via [FCM](https://firebase.google.com/docs/cloud-messaging) data messages — the config plugin registers `ExpoCallKitTelecomMessagingService` automatically.
- Muting a call (from `setMuted` or the system call UI) also mutes the device microphone via [`AudioManager.setMicrophoneMute`](https://developer.android.com/reference/android/media/AudioManager#setMicrophoneMute(boolean)). The mute is device-wide, so the module applies it only while call audio is active and clears it when the last call ends. This adds the [`MODIFY_AUDIO_SETTINGS`](https://developer.android.com/reference/android/Manifest.permission#MODIFY_AUDIO_SETTINGS) permission to your app's manifest; it's granted at install, with no runtime prompt.

### Incoming call UI

The module has no call screen of its own. An incoming call is a Core-Telecom call with the system's call-style notification, which rings with the system ringtone, or `defaultRingtoneAndroid`, and offers answer and decline. On a locked device, its full-screen intent opens the app's launch activity, so show your own call UI there from the conversation snapshots.

### Declined calls without JavaScript

When native FCM intake reports an incoming call, it also starts the JavaScript runtime in the background, unless a native `joinPolicy` sets `prewarm: false`. So the conversation handler that the app registers at module scope receives a decline as `end`, with `origin: "system"`, even if the app wasn't running: notify your backend there. Like every handler action, it waits for the handler only until its deadline. Native code can follow incoming calls without JavaScript too, through `NativeIncomingOutcomes` and `NativeMediaSignals`.

### Ending a call from the server

If the caller hangs up or another endpoint answers, the backend can stop a known incoming conversation without waiting for JavaScript. Send a `callEnded` FCM data message, or call `receiveCallEnded({ eventId, serverCallId, reason })` from the host.

Send a second FCM data message and the module ends the call natively:

```json
{
  "messageType": "callEnded",
  "callEnded": "{\"eventId\":\"7c1e...\",\"serverCallId\":\"call-123\",\"reason\":\"answeredElsewhere\"}"
}
```

`eventId` and `serverCallId` are required. End event IDs are deduplicated for two minutes before lookup, including unknown calls. Unknown signals create no future-call tombstone. The wire reason defaults to `remoteEnded` when missing, local, or unrecognized; recognized remote reasons are `remoteEnded`, `answeredElsewhere`, `declinedElsewhere`, `unanswered`, and `failed`. The typed host API requires a non-local `EndedReason`; local hangups use `endConversation`.

The signal targets retained incoming ownership for the exact `serverCallId`, including an admission still in flight. It bypasses the End handler and does not require ringing status, so the server must send it only when this device should stop. For `answeredElsewhere`, an ended SDK snapshot precedes the correlated `answeredElsewhere` event carrying `conversationId`, `serverCallId`, and `eventId`. The host promise returns acceptance, not completed platform or UI dismissal.

These messages are consumed by the module and are never passed on to `expo-notifications`. Native cleanup works without a JS listener; registered JS listeners can use `useConversationEvent("answeredElsewhere", listener)`. The [Android answered-elsewhere contract](contracts/android-answered-elsewhere.md) documents retention, the API34+ native disconnect-code limit, and visible SystemUI dismissal checks.

The example server sends it with `bun send-test-push.ts --end --serverCallId call-123 [--reason answeredElsewhere]`.

## Push token types

`usePushToken()` reports `{ type: "apns-voip", value }` on iOS, where `value` is the hex-encoded PushKit VoIP token, and `{ type: "fcm", value }` on Android. Send the type with the token to your backend so it knows which transport to use.

## Keeping connections alive in the background

This module hands the system a LiveCommunicationKit or Core-Telecom call, which keeps the *process* alive during a call — but JS timers (`setInterval`, `setTimeout`) and JS-side network heartbeats are still subject to background throttling once the screen locks. If your media stack needs an app-level heartbeat (e.g. a WebSocket signalling channel) to survive the background, pair this module with [`react-native-nitro-keepalive-timer`](https://www.npmjs.com/package/react-native-nitro-keepalive-timer) to get native timers that fire reliably while a call is active.
