# Private M3.5 iOS adapter spike

**Draft; do not merge until SDK M3.5 lands on main and its replacement pin is
qualified.** `sdk-revision` pins private branch
[`codex/sdk-m35-bridge-contract` at `78bd2f1`](https://github.com/ziahamza/ios-voip-sdk/tree/78bd2f1595b4cc2b9773ba6e254a6f2be5a80b7b).
This package compiles against the actual SDK, without modifying its sources.
The fork's legacy CocoaPods `ios/` registration remains separate; this spike does
not install a second LCK manager, CallKit provider, PushKit registry or RN host.

`SDKConversationAdapter` receives the SDK's existing `ConversationClient`. It
forwards commands, reports and server-end intake, and exposes SDK state/events.
Its `launch` composition passes the launch-selected `NativeConversationJoinHandler`
and `NativeIncomingMediaPrewarmer` through an app-owned starter. Expo native hosts
supply `ExpoMediaSupport.startFromBundle`, preserving the SDK's compiled audio
adapter. Native Swift hosts can supply `VoIPApplication.start`. Register this
composition during app launch, before SDK module/app-delegate startup, and register
the ordinary handler at module scope before mounting React. The SDK refuses an
incompatible replacement of an already selected native hook. An ordinary JS
handler cannot take over launch-owned native Join.

```swift
let adapter = try SDKConversationAdapter.launch(
  nativeJoinHandler: nativeJoin,
  incomingMediaPrewarmer: prewarmer
) { join, media in
  try ExpoMediaSupport.startFromBundle(
    nativeJoinHandler: join, incomingMediaPrewarmer: media)
}
```

This example belongs in the app's native Expo module context, where
`ExpoMediaSupport` is compiled; the dependency-free Swift package does not import
Expo. The app owns backend claim, room promotion and its existing Expo/RN factory
registration. Prewarm does not answer a call, prove JS readiness, or authorize
microphone publication. See the SDK's
[private bridge contract](https://github.com/ziahamza/ios-voip-sdk/blob/78bd2f1595b4cc2b9773ba6e254a6f2be5a80b7b/Expo/M3.5-BRIDGE.md).

## Compiles and simulator journeys

CI checks out the exact `sdk-revision` in `apple/.sdk/ios-voip-sdk`; the SDK manifest
reads its adjacent `VERSION`, so a complete checkout is required. iOS17.4 is the
minimum deployment target, and compiling requires the iOS27 SDK. Only the standard
`xcode-27` runner is used for the package and its simulator tests. The independent
legacy Expo example compile remains on `macos-26`.

For local checks, create an owned throwaway iOS27 simulator and run from `apple/`:

```sh
xcodebuild -scheme ExpoConversationsBoundary \
  -destination 'generic/platform=iOS Simulator' CODE_SIGNING_ALLOWED=NO build
xcodebuild -scheme ExpoConversationsBoundary -destination "platform=iOS Simulator,id=$OWNED_SIMULATOR" \
  -only-testing:ExpoConversationsBoundaryTests/NativeJoinJourneyTests \
  -parallel-testing-enabled NO CODE_SIGNING_ALLOWED=NO test
xcodebuild -scheme ExpoConversationsBoundary -destination "platform=iOS Simulator,id=$OWNED_SIMULATOR" \
  -only-testing:ExpoConversationsBoundaryTests/HandlerJourneyTests \
  -parallel-testing-enabled NO CODE_SIGNING_ALLOWED=NO test
```

Give every local command a hard timeout; delete the owned simulator and DerivedData
when finished. The two classes require separate test invocations because the SDK
intentionally keeps one launch selection for the native process lifetime.

- `NativeJoinJourneyTests.testNativeJoinAndReportGate` uses the actual SDK simulator
  runtime and LCK client. It checks cold native Join without JS, native Join after
  handler attachment, connecting versus explicit connected reporting, native server
  end, and retained terminal identity. Simulator-only `CKSimInjectPush`, also used
  by the SDK example, feeds the actual registered PushKit receiver: successful
  reporting precedes native media/JS requests, and terminal intake closes the owned
  session. The media session and JS host are inert recording boundaries: no room,
  microphone, decoded audio, backend claim or actual JS execution is measured.
- `HandlerJourneyTests.testModuleScopeHandler` registers the handler before any UI,
  then checks incoming Join, explicit connected reporting, hold/resume, outgoing
  Start and End through the real SDK simulator runtime.

The generic simulator compile and both simulator test invocations passed locally
with Xcode27.1 on an owned iPhone17Pro/iOS27.0 simulator (two tests, zero failures
or skips). Runtime qualification results and CI provenance are recorded in the
[spike report](../docs/research/ios-m35-adapter-spike.md).
These native Swift tests do not qualify Hermes, a cold APNs launch or physical iOS
Focus/audio behavior. The separate TypeScript fixture under `BridgeContract/`
compiles directly against the pinned SDK's proposed bridge declarations; it cannot
turn missing native methods into implementations.

## Contract gaps that remain SDK-owned

| Seam | Pinned implementation and effect |
| --- | --- |
| Typed incoming outcomes | The [modern incoming command](https://github.com/ziahamza/ios-voip-sdk/blob/78bd2f1595b4cc2b9773ba6e254a6f2be5a80b7b/Expo/apple/Modern/ModernCommands.swift) returns a UUID string; proposed `ConversationBridgeV2` requires a typed outcome. Do not invent a reported result for filtered/capacity failures. |
| Audio routes and dial listener | [`ModernIosVoipSDKModule`](https://github.com/ziahamza/ios-voip-sdk/blob/78bd2f1595b4cc2b9773ba6e254a6f2be5a80b7b/Expo/apple/Modern/ModernIosVoipSDKModule.swift) lacks the declared route getters/selector and dial-listener attachment. |
| Server end | [`ConversationClient.receiveCallEnded`](https://github.com/ziahamza/ios-voip-sdk/blob/78bd2f1595b4cc2b9773ba6e254a6f2be5a80b7b/Sources/VoIPSDK/ConversationClient.swift) exists natively; the modern module and proposed bridge omit it, and [`ConversationEvent`](https://github.com/ziahamza/ios-voip-sdk/blob/78bd2f1595b4cc2b9773ba6e254a6f2be5a80b7b/Sources/VoIPSDK/ConversationModels.swift) lacks the answered-elsewhere event. |
| Foreground report prewarm | Public `reportIncomingConversation` reports successfully without requesting prewarm; only the SDK PushService report path invokes its internal report-success prewarm gate. The simulator fixture preserves the foreground gap as a negative control. |
| Native configuration and answer authority | Proposed [`JoinPolicy`](https://github.com/ziahamza/ios-voip-sdk/blob/78bd2f1595b4cc2b9773ba6e254a6f2be5a80b7b/Expo/src/bridge.ts) requires a named native backend in addition to credentials. Plugin resolution, backend claim and retained-room permission promotion are pending; credentials alone are not answer authority. |
| Media contract | SDK [`NativeMediaCredentials`](https://github.com/ziahamza/ios-voip-sdk/blob/78bd2f1595b4cc2b9773ba6e254a6f2be5a80b7b/Sources/VoIPSDK/NativeMediaCredentials.swift) accepts LiveKit only with at most120s remaining; its pending-room integration requires hidden subscribe-only grants. Android currently uses a distinct publish-capable credential contract, which this spike does not change. |

These gaps keep the PR draft. There is no fallback provider, fabricated route,
cast pretending the partial module implements the full port, or Android policy
change in this spike.
