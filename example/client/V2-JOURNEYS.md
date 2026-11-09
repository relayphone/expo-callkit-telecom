# Conversations example journeys

The example uses the composed conversations API, immutable snapshots, commands,
transport reports and audio routes. Its module-scope handler accepts outgoing
start placement locally. Incoming answer, end, hold and mute run natively.
`androidNativeMedia: "none"` leaves media to the app; this example connects no
room and produces no remote call audio.

Follow [package evaluation](../../docs/sdk-package-evaluation.md) to prepare the
pinned SDK and fork in both the root and example. Build the package at the root,
then run `bun run android` here. Grant microphone and notification permission.
Use the screen's buttons for incoming/outgoing calls, answer, hold/resume, mute,
route selection and end.

## Screen journey

The journey button checks incoming → answer → connected → hold → resume → end
and outgoing → connected → end through the production Expo bridge. It writes
`conversation-journey.json` with ordered steps and pass/fail status.
`conversations-example://journey` runs the same code.

## Installed Android journeys

The [example workflow](../../.github/workflows/v2-example.yml) installs the packed
packages in the example, builds its bundled APK and a test-only instrumentation
APK, then runs both the screen journey and the installed JavaScript journeys on
an accelerated API 35 emulator. Foreground reports need no Firebase credentials;
real push delivery requires the application's Firebase configuration.

The [host driver](../../tools/android-journey.ts) coordinates a unique run ID,
native injection and stage acknowledgments. Incoming, outgoing, answered-elsewhere
and filtered scenarios retain independent native, Telecom and CallStyle evidence.
Preview joining is reported as unsupported on Android. Result validation rejects
missing or mismatched evidence. These media-free checks do not qualify audio or
cloud FCM delivery; [native media checks](../../docs/contracts/android-latency.md)
cover readiness, privacy, hold/resume and cleanup separately.

On iOS, the example enables the SDK's Debug simulator runtime. The
[iOS workflow](../../.github/workflows/v2-ios.yml) builds the example for the
iOS Simulator. [Package evaluation](../../.github/workflows/v2-sdk-evaluation.yml)
separately builds a thin installed consumer from both tarballs. Follow the
[SDK guide](https://github.com/relayphone/ios-voip-sdk/blob/main/docs/react-native.md)
for the shared API and SDK-owned iOS journey coverage.
