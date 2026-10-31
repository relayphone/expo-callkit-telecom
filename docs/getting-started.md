---
description: Install expo-callkit-telecom v2 in an Expo or React Native app — config plugin setup, the conversations API, VoIP push and push tokens.
---

# Getting started

`expo-callkit-telecom` is opinionated about *system integration* and unopinionated about *media*. It presents calls with [LiveCommunicationKit](https://developer.apple.com/documentation/livecommunicationkit) on iOS, through [ios-voip-sdk](https://github.com/relayphone/ios-voip-sdk), and with Jetpack Core-Telecom on Android. You connect your media library ([LiveKit](https://livekit.io/), plain [WebRTC](https://webrtc.org/), etc.) from the conversation handler, or use Android's native media.

::: info v2 isn't published
This guide covers the fork's `v2` branch. Build the package from a reviewed checkout of that branch, as the [Android quick-start](./android-quick-start) describes, and rebuild the native app. Coming from v1? Start with [Migrating from v1](./migrating-v1-to-v2).
:::

## Install

### Peer dependencies

Your app also needs `expo-notifications` and, on iOS unless `mediaAdapter` is `"none"`, a `react-native-webrtc` build:

```sh
bun add @livekit/react-native-webrtc expo-notifications
```

- A `react-native-webrtc` build. [`@livekit/react-native-webrtc`](https://github.com/livekit/react-native-webrtc) `>= 144` is the one the maintainers test against. The module doesn't depend on a specific WebRTC pod: on iOS it links against whichever WebRTC framework your app ships, so upstream [`react-native-webrtc`](https://github.com/react-native-webrtc/react-native-webrtc) `>= 124.0.5` and other forks also work (community-verified). Install only one of them.
- [`expo-notifications`](https://docs.expo.dev/versions/latest/sdk/notifications/) — required on Android, where the module's FCM service builds on it.

Add the config plugin to `app.json` / `app.config.ts`. Minimal form:

```jsonc
{
  "expo": {
    "plugins": ["expo-callkit-telecom"]
  }
}
```

With custom sounds and options:

```jsonc
{
  "expo": {
    "plugins": [
      [
        "expo-callkit-telecom",
        {
          "sounds": [
            "./assets/sounds/ringtone.wav",
            "./assets/sounds/dialtone.wav"
          ],
          "defaultRingtoneIos": "ringtone.wav",
          "defaultRingtoneAndroid": "ringtone.wav",
          "defaultDialtone": "dialtone.wav",
          "iconTemplateIos": "./assets/call-icon.png",
          "incomingCallTimeout": 45,
          "outgoingCallTimeout": 60,
          "fulfillAnswerCallTimeout": 30,
          "includesCallsInRecents": true,
          "microphonePermission": "$(PRODUCT_NAME) needs the microphone to make calls."
        }
      ]
    ]
  }
}
```

Files in `sounds` are copied into the iOS bundle and Android raw resources at prebuild time. The full prop type is `ExpoCallKitTelecomPluginProps` in `plugin/src/`.

On iOS, three options set LiveCommunicationKit's [`ConversationManager.Configuration`](https://developer.apple.com/documentation/livecommunicationkit/conversationmanager/configuration-swift.struct):

- `defaultRingtoneIos` sets [`ringtoneName`](https://developer.apple.com/documentation/livecommunicationkit/conversationmanager/configuration-swift.struct/ringtonename), the sound resource in the app bundle that rings for incoming calls. `"default"`, the default, uses the system ringtone.
- `iconTemplateIos` sets [`iconTemplateImageData`](https://developer.apple.com/documentation/livecommunicationkit/conversationmanager/configuration-swift.struct/icontemplateimagedata), which Apple documents only as the PNG data for the app's icon image. The property is named for a template image, so provide a square PNG, about 40×40 points, whose alpha channel describes the glyph. Android's call UI uses the app's launcher icon, so this option is iOS-only.
- `includesCallsInRecents` sets [`includesConversationInRecents`](https://developer.apple.com/documentation/livecommunicationkit/conversationmanager/configuration-swift.struct/includesconversationinrecents): whether the system's Recents list includes a call after it ends. It defaults to `true`.

On iOS, `incomingCallTimeout` also bounds how long an outgoing call dials, and `fulfillAnswerCallTimeout` bounds how long a call stays connecting before it ends as failed. See [iOS](./migrating-v1-to-v2#ios) for `mediaAdapter` and the iOS-only `simulator` option, and the [Android quick-start](./android-quick-start) for `joinPolicy`.

## Concepts

- **State.** `getConversations()`, `useConversations()` and `useConversation(id)` read immutable snapshots of every call. Several calls can coexist.
- **Handler.** Register one `setConversationHandler({ start, end, join?, hold?, mute?, tones? })` at module scope, before React mounts. Resolving `start` (or `join`) confirms that your app placed the call. [Migrating from v1](./migrating-v1-to-v2) describes how each platform treats the other methods.
- **Commands** ask the system to act: `startConversation`, `joinConversation`, `endConversation`, `setMuted`, `setHeld`, `playTones` and `selectAudioRoute`.
- **Reports** tell the module transport facts: `reportConversation(id, { type: "connected" })`, `reportIncomingConversation(payload)` and `receiveCallEnded(payload)`. A handler resolving doesn't mean media is connected; report it.
- **Events** carry what a snapshot can't show, through `useConversationEvent(...)`, such as `dialRequest` and `answeredElsewhere`.

The [API reference](./api/) documents every function and type.

## Push tokens

Each platform registers for its pushes when the app launches; there's nothing to call. Read the token with `usePushToken()`:

```ts
import { usePushToken } from "expo-callkit-telecom";
import { useEffect } from "react";

function PushRegistration() {
  const token = usePushToken();
  useEffect(() => {
    // { type: "apns-voip", value } on iOS, { type: "fcm", value } on Android, or null.
    if (token) sendToBackend(token.type, token.value);
  }, [token]);
  return null;
}
```

On iOS the value is the hex-encoded PushKit VoIP token. The token is `null` until the system issues one, and again if it's invalidated.

## Example app

The repo's `example/` contains a runnable Expo app (`example/client/`) and a push-sender script (`example/server/`). See their READMEs for setup.

## Next steps

- [VoIP push payload shape](./voip-push)
- [Platform notes](./platform-notes)
- [API reference](./api/)
- [Comparison with `react-native-callkeep`](./vs-callkeep)
