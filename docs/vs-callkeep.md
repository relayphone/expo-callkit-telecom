---
description: expo-callkit-telecom v2 vs react-native-callkeep — architectural differences (LiveCommunicationKit vs CallKit, Jetpack core-telecom vs ConnectionService, Swift/Kotlin vs Obj-C/Java, built-in vs app-side VoIP push), when to pick which, and migration sketch.
---

# `expo-callkit-telecom` vs `react-native-callkeep`

[`react-native-callkeep`](https://github.com/react-native-webrtc/react-native-callkeep) is the long-standing React Native library for putting a native call UI on top of WebRTC. `expo-callkit-telecom` solves the same problem but is built on the *current* generation of platform APIs that Apple, Google, and Expo are pushing forward.

## What this module is built on

- **[LiveCommunicationKit](https://developer.apple.com/documentation/livecommunicationkit)** on iOS, through [ios-voip-sdk](https://github.com/relayphone/ios-voip-sdk) — Apple's framework for presenting VoIP calls in the system's call UI, in Recents and on connected devices.
- **[Jetpack `androidx.core:core-telecom`](https://developer.android.com/jetpack/androidx/releases/core-telecom)** on Android — Google's recommended path for VoIP apps, introduced in 2023. It owns the foreground service, the incoming-call notification, and the full-screen intent on your behalf.
- **[Swift](https://www.swift.org/) on iOS, [Kotlin](https://kotlinlang.org/) on Android** — the current first-party languages for each platform.
- **The [Expo Modules API](https://docs.expo.dev/modules/overview/)**, distributed with an [Expo config plugin](https://docs.expo.dev/config-plugins/introduction/) — handles entitlements, background modes, microphone permission, ringtone bundling, and FCM service registration at prebuild time.
- **[WebRTC](https://webrtc.org/)'s `RTCAudioSession` in manual-audio mode** — the coordination model that [LiveKit](https://livekit.io/)'s [RN SDK](https://github.com/livekit/client-sdk-react-native) (and most modern WebRTC stacks) expect.
- **Native VoIP push parsing**, on both [APNs VoIP](https://developer.apple.com/documentation/usernotifications/sending-voip-pushes) ([PushKit](https://developer.apple.com/documentation/pushkit)) and [FCM](https://firebase.google.com/docs/cloud-messaging) data messages — incoming calls are reported to the OS before JS is running.

`react-native-callkeep` came out of an earlier era of the platform: [CallKit](https://developer.apple.com/documentation/callkit) on iOS, Android's [`android.telecom.ConnectionService`](https://developer.android.com/reference/android/telecom/ConnectionService) (the API that `core-telecom` wraps and supersedes), Objective-C + Java, and a design that leaves push parsing and `RTCAudioSession` coordination to the app.

See [Verified against](./verified-against) for the platform versions this library has been tested against.

## Side-by-side

| | `expo-callkit-telecom` v2 | `react-native-callkeep` |
| --- | --- | --- |
| iOS backend | LiveCommunicationKit, through ios-voip-sdk | CallKit |
| Android backend | Jetpack `androidx.core:core-telecom` | `android.telecom.ConnectionService` |
| Minimum Android SDK | 26 (Android 8.0) | 23 (Android 6.0) |
| Native languages | Swift + Kotlin | Objective-C + Java |
| Expo config plugin | Built in | Community plugins |
| VoIP push parsing | Native, on both transports | App-side (bring your own PushKit delegate / FCM service) |
| iOS audio session | Coordinates with `RTCAudioSession` (manual-audio mode) | Manipulates `AVAudioSession` directly |
| Android incoming-call UI | Module owns the foreground service, notification, and full-screen intent | App-owned |
| API shape | Typed conversation snapshots, one handler, commands and reports, cross-platform | Split into `{ ios, android }`, several platform-only methods |

## When to pick which

Pick **`expo-callkit-telecom`** if:

- You're on Expo and want the config-plugin experience for entitlements, background modes, permissions, ringtone bundling, and FCM service registration.
- You're on a manual-audio WebRTC stack — LiveKit, plain WebRTC, or anything else that wants to own its `RTCAudioSession`.
- You want incoming calls to work from a terminated state without writing your own PushKit / FCM glue.
- iOS 26+ and Android API 26+ cover your install base.

Pick **`react-native-callkeep`** if:

- You need to support iOS before 26, or Android API 23–25.
- You're on bare React Native and your existing callkeep wiring works for your use case.

## On the iOS audio session specifically

The iOS audio session is the part of a system-call integration that breaks most often, because two parties ([`AVAudioSession`](https://developer.apple.com/documentation/avfaudio/avaudiosession) and WebRTC's `RTCAudioSession`) both want to own the route, and the system activates and deactivates the session for each call in ways that can surprise both of them. `expo-callkit-telecom` puts `RTCAudioSession` in manual-audio mode and starts WebRTC's audio only when LiveCommunicationKit activates the call's audio session, which is what LiveKit (and any "I want to control the mic/speaker myself" WebRTC stack) expects.

## On VoIP push specifically

Apple requires an app to report the call of a VoIP push before PushKit's callback returns, including from a terminated state, and Android expects a high-priority call push to show the call promptly. `expo-callkit-telecom` parses both APNs VoIP and FCM data payloads natively and reports the call before JS is running, so the cold-start case works as long as your payload matches the documented shape — no app-side delegate or service code required.

## Migration sketch

If you're moving from callkeep to this module:

1. Replace `RNCallKeep.setup({ ios, android })` with the `expo-callkit-telecom` config plugin in `app.json`.
2. Replace `RNCallKeep.startCall(uuid, handle)` with `startConversation({ to, video })`, and place the call in your conversation handler's `start`.
3. Replace `RNCallKeep.displayIncomingCall(...)` with `reportIncomingConversation(payload)` — though if your push parsing has moved into the module, you usually don't need to call this from JS at all.
4. Replace the various `RNCallKeep.addEventListener('answerCall', ...)` calls with `useConversations()` snapshots, the handler's methods and `useConversationEvent(...)`.
5. Remove your PushKit / FCM glue.
