---
description: Compatibility matrix for expo-callkit-telecom — the iOS, Android, Expo SDK, React Native, and media-transport versions this library has been tested against on real devices.
---

# Verified against

`expo-callkit-telecom` has been tested end-to-end on real devices via the runnable `example/` app against the platform versions listed below.

| Library | [iOS](https://developer.apple.com/ios/) | [Android](https://developer.android.com/about/versions) | [Expo SDK](https://expo.dev/) | [React Native](https://reactnative.dev/) | [New Architecture](https://reactnative.dev/architecture/landing-page) | Media transport |
| --- | --- | --- | --- | --- | --- | --- |
| 0.3.x | 26 (min 16.0) | 15 (min API 26) | 55 | 0.83 | Yes | [LiveKit RN SDK](https://github.com/livekit/client-sdk-react-native) |

The fork's `v2` branch isn't released and hasn't been through this real-device matrix. It needs iOS 26.0 or later, where it presents calls with LiveCommunicationKit instead of CallKit, and Android API 26 or later. Its CI builds the example app for the iOS Simulator and runs Android journeys on an API 35 emulator.
