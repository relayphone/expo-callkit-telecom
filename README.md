# 📞 expo-callkit-telecom — LiveCommunicationKit & Core-Telecom for React Native + Expo (with VoIP push)

## Fork v2 rollout

This fork's **`v2` branch** replaces the v1 session/listener API with typed
conversations. Use the fork source and its lockfiles for evaluation; the inherited
package version doesn't identify a published v2 release.

| Platform | v2 status |
| --- | --- |
| Android | Implemented on this branch: Core-Telecom, native FCM intake, native answer/pre-warm, typed incoming outcomes and server-end signals. Emulator and release Expo journeys are checked in CI. |
| iOS | Implemented on this branch over [ios-voip-sdk](https://github.com/relayphone/ios-voip-sdk)'s `VoIPClient`: LiveCommunicationKit, PushKit intake from launch, native answer, call previews and WebRTC `RTCAudioSession` audio. iOS 26 or later; the example is built for the Simulator in CI. See [iOS](docs/migrating-v1-to-v2.md#ios). |

Start with the [v1 → v2 migration guide](docs/migrating-v1-to-v2.md) and
[Android quick-start](docs/android-quick-start.md). They cover:

- `Conversation` snapshots and hooks, a module-scope handler, and separate
  commands versus transport reports. Handler completion does not report media
  connected.
- Required typed `Caller { handleType, value, displayName, hostContactID? }`,
  stable server-call identity and `receiveCallEnded` for answered-elsewhere
  signals.
- Native report-first pre-warm and answer, with physical audio gated by Android
  authority; the example's handler policy is a separate media-free flow.
- Typed DND/filter outcomes and the measured Android/iOS LCK allowance contrast.
  `reported` alone does not prove ringing.

The [checked latency comparison](docs/contracts/android-latency-results.md)
records 40 optimized emulator samples across two runs, all below 300 ms after
completed pre-warm. This measures decoded remote PCM, excludes cloud push/process
launch/acoustic latency, and does not establish production percentiles.

## What the module does

> An Expo module, written in Swift and Kotlin, that presents your app's calls in the system's call UI: **LiveCommunicationKit** on iOS, through [ios-voip-sdk](https://github.com/relayphone/ios-voip-sdk), and **Jetpack Core-Telecom** on Android, behind one typed TypeScript API. It receives VoIP pushes natively and coordinates call audio; your app owns the media (LiveKit, plain WebRTC, etc.).

📖 **Documentation:** the published site, [expo-callkit-telecom.mfairley.com](https://expo-callkit-telecom.mfairley.com/), describes upstream v1. For v2, use the guides linked above and this branch's [docs](docs/).

<h2 align="center">📱 See it in action</h2>

These recordings show upstream v1's system call UI.

<table align="center">
  <tr>
    <th></th>
    <th align="center">Outgoing call</th>
    <th align="center">Incoming (banner)</th>
    <th align="center">Incoming (full screen)</th>
  </tr>
  <tr>
    <td align="center"><strong>iOS</strong></td>
    <td align="center">
      <a href="https://github.com/mfairley/expo-callkit-telecom/raw/main/docs/public/outgoing-call-ios.mp4" title="Click to watch — outgoing call on iOS">
        <img src="docs/public/outgoing-call-ios-poster.webp" alt="Outgoing call on iOS (click to watch)" width="200">
      </a>
    </td>
    <td align="center"><img src="docs/public/incoming-call-banner-ios.webp" alt="Incoming call banner on iOS" width="200"></td>
    <td align="center"><img src="docs/public/incoming-call-fullscreen-ios.webp" alt="Incoming call full screen on iOS" width="200"></td>
  </tr>
  <tr>
    <td align="center"><strong>Android</strong></td>
    <td align="center">
      <a href="https://github.com/mfairley/expo-callkit-telecom/raw/main/docs/public/outgoing-call-android.mp4" title="Click to watch — outgoing call on Android">
        <img src="docs/public/outgoing-call-android-poster.webp" alt="Outgoing call on Android (click to watch)" width="200">
      </a>
    </td>
    <td align="center"><img src="docs/public/incoming-call-banner-android.webp" alt="Incoming call banner on Android" width="200"></td>
    <td align="center"><img src="docs/public/incoming-call-fullscreen-android.webp" alt="Incoming call full screen on Android" width="200"></td>
  </tr>
</table>

## ✨ Features

- 📱 **System call UI** — LiveCommunicationKit on iOS; on Android, Core-Telecom with a call-style notification and full-screen intent
- 🔔 **VoIP push** — APNs VoIP pushes through PushKit on iOS, FCM data messages on Android, decoded natively from launch so a call is reported before JavaScript runs
- 🎵 **Ringtones** — the system ringtone, or a bundled sound, for incoming calls
- ☎️ **Dialtone** — looped dialtone with fade-in for outgoing calls, configurable
- 🖼️ **App icon on the iOS call UI** — a template image via `iconTemplateIos`
- 🎧 **Audio routes** — the current route and the available ones, with `useAudioRoutes()` and `selectAudioRoute()`
- 🎚️ **Mute, hold and tones** — commands from your app, and mute and hold from the system's call UI
- 🗣️ **Dial requests on iOS** — calls started from Recents, Siri or Contacts arrive as `dialRequest` events
- 🧩 **Typed conversations API** — immutable `Conversation` snapshots, hooks, one module-scope handler, commands and transport reports

## 📦 Install

v2 isn't published to npm. Build the package from a reviewed checkout of this fork's `v2` branch, as the [Android quick-start](docs/android-quick-start.md) describes, and rebuild the native app.

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

On iOS, the plugin passes these options to LiveCommunicationKit's `ConversationManager.Configuration`:

- `defaultRingtoneIos` becomes `ringtoneName`, the sound resource in the app bundle that rings for incoming calls; `"default"` uses the system ringtone.
- `iconTemplateIos` becomes `iconTemplateImageData`, which Apple documents only as the PNG data for the app's icon image. The property is named for a template image, so provide a square PNG, about 40×40 points, whose alpha channel describes the glyph. Android's call UI uses the app's launcher icon, so this option is iOS-only.
- `includesCallsInRecents` becomes `includesConversationInRecents`, whether the system's Recents list includes a call after it ends.

On iOS, `incomingCallTimeout` also bounds how long an outgoing call dials, and `fulfillAnswerCallTimeout` bounds how long a call stays connecting before it ends as failed. The [migration guide](docs/migrating-v1-to-v2.md#ios) describes `mediaAdapter` and the iOS-only `simulator` option.

## 🧠 API

The [migration guide](docs/migrating-v1-to-v2.md) maps every v1 function to its v2 replacement, and the [v2 types](src/conversations/types.ts) document the full model. In short:

- **State** — `getConversations()`, `useConversations()` and `useConversation(id)` read immutable snapshots of every call.
- **Handler** — one `setConversationHandler({ start, end, join?, hold?, mute?, tones? })`, registered at module scope, places calls and hears about actions.
- **Commands** — `startConversation`, `joinConversation`, `endConversation`, `setMuted`, `setHeld`, `playTones`, `selectAudioRoute`.
- **Reports** — `reportConversation(id, { type: "connected" })` and the other transport facts, `reportIncomingConversation(payload)` and `receiveCallEnded(payload)`.
- **Events** — `useConversationEvent(...)` for signals a snapshot can't show, such as `dialRequest` and `answeredElsewhere`.

## 🚀 VoIP push payload

When a VoIP push arrives (PushKit on iOS, an FCM data message on Android), the module decodes it natively, before JavaScript runs, and reports the call to the system. The invitation is an `IncomingCallPayload` with a typed `Caller`:

```jsonc
{
  "eventId": "550e8400-e29b-41d4-a716-446655440000",   // required: identifies this delivery, for deduplication
  "serverCallId": "call-456",                          // required: your backend's call ID
  "caller": {
    "handleType": "phone",                             // "phone", "email" or "generic"
    "value": "+14155551234",                           // phone numbers must be E.164
    "displayName": "Jane Smith",
    "hostContactID": "host-contact-42"                 // optional, opaque
  },
  "hasVideo": false,
  "metadata": { "chatId": "abc-123" }                  // optional, opaque JSON passed through to `payload.metadata`
}
```

### 🍎 iOS — APNs VoIP push

Send a VoIP push (`apns-push-type: voip`) whose payload nests the invitation under `incomingCall`:

```jsonc
{
  "incomingCall": { /* IncomingCallPayload — see above */ }
}
```

### 🤖 Android — FCM data message

FCM data values must be strings, so JSON-encode the invitation and put it under `incomingCall`:

```jsonc
{
  "data": {
    "messageType": "incomingCall",
    "incomingCall": "{\"eventId\":\"...\",\"serverCallId\":\"...\", ... }"
  }
}
```

On both platforms a `callEnded` message ends a known incoming call from the server (see [platform notes](docs/platform-notes.md#ending-a-call-from-the-server)). On Android, other data messages are forwarded to `expo-notifications`'s service.

## 🔑 Push token

Each platform registers for its pushes at launch. Read the token with `usePushToken()`, which returns `{ type: "apns-voip", value }` on iOS (the hex-encoded PushKit VoIP token) or `{ type: "fcm", value }` on Android, and `null` until a token exists or after it's invalidated. Send it to your backend with its type so it knows which transport to use.

## 🧪 Example

`example/` contains a runnable Expo app (`example/client/`) and a push-sender script (`example/server/`). See their READMEs for setup.

## 📝 Platform notes

- 🍎 **iOS** — iOS 26 or later. Calls are presented with LiveCommunicationKit through ios-voip-sdk, which the plugin pins in the Podfile. The plugin adds the `voip` and `audio` background modes and the push entitlement; sending VoIP pushes needs an APNs key or VoIP services certificate. With `mediaAdapter` `"webrtc"` or `"livekit"`, the module puts WebRTC's `RTCAudioSession` in manual audio mode, so WebRTC's audio starts when the system activates the call's audio session.
- 🤖 **Android** — requires `MANAGE_OWN_CALLS` permission, min SDK 26. Uses `androidx.core:core-telecom`. Incoming calls come via FCM data messages — the config plugin registers `ExpoCallKitTelecomMessagingService` automatically.

## ⏰ Keeping connections alive in the background

This module hands the system a LiveCommunicationKit or Core-Telecom call, which keeps the *process* alive during a call — but JS timers (`setInterval`, `setTimeout`) and JS-side network heartbeats are still subject to background throttling once the screen locks. If your media stack needs an app-level heartbeat (e.g. a WebSocket signalling channel) to survive the background, pair this module with [`react-native-nitro-keepalive-timer`](https://www.npmjs.com/package/react-native-nitro-keepalive-timer) to get native timers that fire reliably while a call is active.

## 🆚 Comparison with `react-native-callkeep`

[`react-native-callkeep`](https://github.com/react-native-webrtc/react-native-callkeep) is the long-standing React Native library in this space, built on CallKit on iOS and `ConnectionService` on Android. `expo-callkit-telecom` v2 uses LiveCommunicationKit on iOS and Jetpack `androidx.core:core-telecom` on Android, Swift + Kotlin, the Expo Modules API with a config plugin, and `RTCAudioSession` coordination for manual-audio WebRTC stacks like LiveKit. It also decodes APNs VoIP and FCM data payloads natively, so the cold-start incoming-call case works without app-side glue.

More detail: **[docs/vs-callkeep.md](docs/vs-callkeep.md)**.
