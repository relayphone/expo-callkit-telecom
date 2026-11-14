# expo-callkit-telecom

Expo and React Native conversations with Jetpack Core-Telecom on Android and
[Relay's iOS VoIP SDK](https://github.com/relayphone/ios-voip-sdk) on Apple platforms.
This package composes `@relayphone/ios-voip-sdk`: it re-exports the SDK's shared
TypeScript API, adds the Android `VoIPConversations` provider, and composes both
platforms' config plugins. The SDK supplies the sole Apple native provider.

The fork's `v2` branch is a breaking migration from upstream v1. Install the first
prerelease from its CI-built GitHub release asset in your `package.json`:

```json
"expo-callkit-telecom": "https://github.com/relayphone/expo-callkit-telecom/releases/download/v2.0.0-alpha.6/expo-callkit-telecom-2.0.0-alpha.6.tgz"
```

Prerelease versions can change APIs. See [releases and verification](https://github.com/relayphone/expo-callkit-telecom/blob/v2/docs/releases.md),
or evaluate a pinned checkout with the [package evaluation guide](https://github.com/relayphone/expo-callkit-telecom/blob/v2/docs/sdk-package-evaluation.md).

## Integration

Start with [Getting started](https://github.com/relayphone/expo-callkit-telecom/blob/v2/docs/getting-started.md)
and the [conversations contract](https://github.com/relayphone/expo-callkit-telecom/blob/v2/docs/canonical-contract.md).
The [SDK guide](https://github.com/relayphone/ios-voip-sdk/blob/v0.5.0/docs/react-native.md)
owns the shared API and iOS setup. Import runtime functions and hooks from
`expo-callkit-telecom`; import types from the type-only
`expo-callkit-telecom/types` entry.

Configure `expo-callkit-telecom` once in the application's Expo plugins. Shared
options pass to the SDK plugin. `mediaAdapter: "webrtc" | "none"` configures SDK
audio integration; the independent `androidNativeMedia: "livekit" | "none"`
option selects Android native media and defaults to `"none"`. Regenerate native
projects and rebuild the application after changing native configuration. Existing
projects should follow the [clean regeneration instructions](https://github.com/relayphone/expo-callkit-telecom/blob/v2/docs/canonical-contract.md#plugin-composition-and-native-project-upgrades).

## Android behavior

Conversation IDs are the application's exact server call IDs. The handler decides
outgoing start placement. Answer, end, hold and mute run natively; observe snapshots
and `actionPerformed`. Server reports communicate transport facts and do not
fabricate performed actions.

Native LiveKit media uses short-lived credentials from the incoming push. Physical
capture and audible output wait for Core-Telecom audio authority. With
`androidNativeMedia: "none"`, the application owns media and native code never
attempts a media session. Android tones require native LiveKit. Call previews are
unsupported on Android; `providerReset` is iOS-only.
The [native audio readiness contract](https://github.com/relayphone/expo-callkit-telecom/blob/v2/docs/contracts/android-latency.md)
describes the retained functional media checks.

See the [Android guide](https://github.com/relayphone/expo-callkit-telecom/blob/v2/docs/android-quick-start.md)
for push payloads, incoming outcomes and native media. Historical exploratory
studies are preserved on
[archive/android-telecom-research](https://github.com/relayphone/expo-callkit-telecom/tree/archive/android-telecom-research).

## Examples and validation

The public [example client](https://github.com/relayphone/expo-callkit-telecom/tree/v2/example/client)
and [push sender](https://github.com/relayphone/expo-callkit-telecom/tree/v2/example/server)
use the canonical payloads. CI checks installed package consumers, Android native
and JavaScript journeys, and SDK-owned Apple integration. Examples, instrumentation,
research tools and the documentation site are outside the npm package allowlist.

Read the [migration guide](https://github.com/relayphone/expo-callkit-telecom/blob/v2/docs/migrating-v1-to-v2.md)
when upgrading from v1. Report fork issues in
[relayphone/expo-callkit-telecom](https://github.com/relayphone/expo-callkit-telecom/issues).
