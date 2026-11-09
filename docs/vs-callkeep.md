# Migration from react-native-callkeep

The fork's `v2` branch composes `@relayphone/ios-voip-sdk` for the shared API
and Apple native provider, with Core-Telecom on Android. See the
[SDK guide](https://github.com/relayphone/ios-voip-sdk/blob/main/docs/react-native.md)
for its iOS requirements and the [platform notes](./platform-notes.md) for Android
behavior. The [react-native-callkeep project](https://github.com/react-native-webrtc/react-native-callkeep)
documents its own API and supported platforms.

Start with [package evaluation](./sdk-package-evaluation.md) and
[Getting started](./getting-started.md). This fork's inherited package version
is not a published v2 release. Regenerate and rebuild the native application
after replacing the old integration.

| Existing integration | Composed conversations API |
| --- | --- |
| `RNCallKeep.setup({ ios, android })` | Configure one `expo-callkit-telecom` plugin entry; it composes both platform plugins |
| `RNCallKeep.startCall(uuid, handle)` | `startConversation({ id: serverCallId, to, video })`; the `start` handler places the call |
| `RNCallKeep.displayIncomingCall(...)` | `reportIncomingConversation(payload)` for invitations learned by app signaling; native push intake already reports its invitations |
| Answer/end/hold/mute action listeners | Snapshots and `actionPerformed` events; those actions run natively without consulting a handler |

Use the application's exact server call ID for incoming `serverCallId` and
outgoing `id`; platform UUIDs stay internal. A command's completion does not
prove transport connection. Report `connected` when the transport actually
connects. Keep `eventId` distinct for each server event.

Import functions and hooks from `expo-callkit-telecom`, and types from
`expo-callkit-telecom/types`. Register the required `start` handler at module
scope before React mounts. The optional `join` handler is for iOS preview
placement. Native answer, end, hold and mute do not use handler callbacks.

Android tones and preview joining are unsupported. `androidNativeMedia`
selects Android native media independently of SDK `mediaAdapter` audio
integration. See the [migration guide](./migrating-v1-to-v2.md) for payloads,
plugin changes and regeneration, and [verification scope](./verified-against.md)
for what the automated checks establish.
