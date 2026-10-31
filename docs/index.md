---
layout: home
description: LiveCommunicationKit and Core-Telecom for React Native and Expo — VoIP push, incoming call UI, LiveKit-friendly audio. A modern react-native-callkeep alternative.

# Preload the LCP candidate (hero video poster) so Lighthouse sees it early.
head:
  - - link
    - rel: preload
      as: image
      href: /outgoing-call-ios-poster.webp
      fetchpriority: high

hero:
  name: expo-callkit-telecom
  text: LiveCommunicationKit & Core-Telecom for React Native + Expo
  tagline: "Fork v2 has a conversations API on Android and iOS. The recordings below show upstream v1."
  actions:
    - theme: brand
      text: Android v2 quick-start
      link: /android-quick-start
    - theme: alt
      text: Getting started
      link: /getting-started
    - theme: alt
      text: View on GitHub
      link: https://github.com/relayphone/expo-callkit-telecom/tree/v2
    - theme: alt
      text: vs react-native-callkeep
      link: /vs-callkeep

features:
  - icon: 📱
    title: Native call UI
    details: '<a href="https://developer.apple.com/documentation/livecommunicationkit">LiveCommunicationKit</a> on iOS, through <a href="https://github.com/relayphone/ios-voip-sdk">ios-voip-sdk</a>; <a href="https://developer.android.com/reference/androidx/core/telecom/package-summary">Jetpack Core-Telecom</a> with a call notification and full-screen intent on Android. The module presents calls in the system call UI, coordinates call audio and receives VoIP pushes — your app owns the media.'
  - icon: 🔔
    title: VoIP push, parsed natively
    details: '<a href="https://developer.apple.com/documentation/usernotifications/sending-voip-pushes">APNs VoIP</a> (<a href="https://developer.apple.com/documentation/pushkit">PushKit</a>) on iOS, <a href="https://firebase.google.com/docs/cloud-messaging">FCM</a> data messages on Android. Calls report to the system before JS is running, so the cold-start case works without app-side glue.'
  - icon: 🎧
    title: LiveKit-friendly audio
    details: 'On iOS, puts <a href="https://webrtc.org/">WebRTC</a>''s <code>RTCAudioSession</code> in manual-audio mode, so <a href="https://livekit.io/">LiveKit</a> and plain WebRTC stacks work without app-side <a href="https://developer.apple.com/documentation/avfaudio/avaudiosession">AVAudioSession</a> coordination. With <code>mediaAdapter: "livekit"</code>, Android can also join LiveKit or WebRTC media natively.'
  - icon: 🧩
    title: Typed, unified API
    details: 'Immutable conversation snapshots, one handler, commands and reports that work the same on both platforms.'
  - icon: 🛠️
    title: Expo config plugin
    details: '<a href="https://docs.expo.dev/config-plugins/introduction/">Expo config plugin</a> handles entitlements, background modes, microphone permission, ringtone bundling, and FCM service registration at prebuild.'
  - icon: 🆕
    title: Current platform APIs
    details: '<a href="https://www.swift.org/">Swift</a> + LiveCommunicationKit on iOS (26.0+), <a href="https://kotlinlang.org/">Kotlin</a> + <a href="https://developer.android.com/jetpack/androidx/releases/core-telecom">androidx.core-telecom</a> on Android (minSdk 26). The path Google and Apple are pushing forward.'
---

## Fork v2 rollout

The recordings on this page show upstream v1's system call UI. The fork's `v2`
branch uses a breaking conversations API on both platforms: start with the
[v1 → v2 migration](./migrating-v1-to-v2.md) and
[Android quick-start](./android-quick-start.md).
On iOS, v2 runs over ios-voip-sdk with LiveCommunicationKit; see [iOS](./migrating-v1-to-v2.md#ios).

## See it in action on iOS and Android

The runnable `example/` app drives the system call UI on both platforms — outgoing calls, banner-style incoming calls (while the device is in use), and full-screen incoming calls (when locked).

<DemoGrid />

## Frequently asked questions

### What is `expo-callkit-telecom`?

An Expo module that presents calls with LiveCommunicationKit on iOS and Jetpack Core-Telecom on Android, behind one typed TypeScript API. The module presents calls in the system call UI, coordinates call audio and decodes VoIP pushes; your app owns the media.

### Does it work with bare React Native, or only Expo?

It's distributed as an Expo module with a config plugin, so the smoothest path is an Expo project (managed or prebuild). Bare React Native apps that have adopted the Expo Modules API can also use it.

### How is it different from `react-native-callkeep`?

`react-native-callkeep` is built on CallKit and Android's older `ConnectionService` API, Objective-C + Java, and leaves VoIP push parsing and `RTCAudioSession` coordination to the app. This module uses LiveCommunicationKit and Jetpack `androidx.core-telecom` (Google's current recommendation), Swift + Kotlin, an Expo config plugin, and parses APNs VoIP and FCM data payloads natively. See [vs react-native-callkeep](./vs-callkeep) for the full side-by-side.

### Does it work with LiveKit?

Yes. With `mediaAdapter` `"webrtc"` (the default) or `"livekit"`, the module coordinates the iOS audio session: it puts WebRTC's `RTCAudioSession` into manual-audio mode and gives it WebRTC's call configuration before each call; when LiveCommunicationKit activates the session it enables WebRTC audio, and when it deactivates the session it disables WebRTC audio and restores the pre-call configuration. Because of that, your app should **not** call LiveKit's `AudioSession.startAudioSession()` / `stopAudioSession()` (or otherwise activate `AVAudioSession` itself) — let the module handle it, and connect LiveKit from your conversation handler. On Android, with `mediaAdapter: "livekit"`, the native join policy can also join a LiveKit room natively; see the [Android quick-start](./android-quick-start.md).

### What are the minimum platform versions?

v2 needs iOS 26.0 and Android API 26 (Android 8.0), on Expo SDK 55 or later. Upstream v1 supported iOS 16.0; see [Verified against](./verified-against) for what has been tested.

### Does it handle incoming calls when the app is terminated?

Yes. On iOS the module starts ios-voip-sdk's client at launch, so a VoIP push is decoded and its call reported to LiveCommunicationKit before PushKit's callback returns, even from a cold start. On Android, native FCM intake reports the call to Core-Telecom before JS runs. The user can answer before JavaScript loads.


