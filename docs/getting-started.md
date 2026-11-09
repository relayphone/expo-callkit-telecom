# Getting started

`expo-callkit-telecom` composes `@relayphone/ios-voip-sdk`. The SDK owns the shared
conversations API and Apple native integration; this package supplies Android
Core-Telecom and the composed config plugin. The fork's `v2` migration is evaluated
from pinned source and SDK archives. Follow [package evaluation](./sdk-package-evaluation.md)
to build the packages; the inherited package version does not identify a published
v2 release.

## Configure the native application

Use the SDK's [React Native guide](https://github.com/relayphone/ios-voip-sdk/blob/main/docs/react-native.md)
for shared options, Apple setup and media dependencies. Android also needs
`expo-notifications`. Configure one fork plugin entry:

```ts
plugins: [
  ["expo-callkit-telecom", {
    mediaAdapter: "none",
    androidNativeMedia: "none",
  }],
]
```

The fork composes the SDK plugin. `mediaAdapter` accepts `"webrtc" | "none"`;
`androidNativeMedia` independently accepts `"livekit" | "none"` and defaults to
`"none"`. Select `"livekit"` for Android's built-in native media with fresh
credentials from the incoming push. The old `joinPolicy` option is rejected.
See the [Android guide](./android-quick-start.md) for that path.

Commit native customizations before regenerating an existing project with
`npx expo prebuild --clean`, then rebuild the development client. Follow the
[upgrade instructions](./canonical-contract.md#plugin-composition-and-native-project-upgrades)
for retired provider, Podfile and widget integration. Native provider changes
require a rebuilt application.

## Use the conversations API

Import runtime functions and hooks from `expo-callkit-telecom`, and types from
`expo-callkit-telecom/types`. The [SDK guide](https://github.com/relayphone/ios-voip-sdk/blob/main/docs/react-native.md)
is the shared API reference. The [fork contract](./canonical-contract.md) describes
raw call IDs, start placement, native performed actions and Android's limits.
Report connected only when the transport actually connects.

See the public [example client](../example/client/README.md) and
[push sender](../example/server/README.md), [push payloads](./voip-push.md), and
[package evaluation](./sdk-package-evaluation.md). For an existing v1 application,
read the [migration guide](./migrating-v1-to-v2.md).
