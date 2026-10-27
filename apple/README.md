# iOS adapter boundary

The adapter receives the `ConversationClient` already owned by the SDK Expo
module. It forwards commands and reports, exposes the SDK's synchronous revisioned
snapshot and forwards handler registration. It creates no LCK manager, CallKit
provider, PushKit registry or audio session. IDs, status, direction, participants,
deadlines and reports remain SDK values. iOS17.4 is the minimum.

`sdk-revision` pins `relayphone/ios-voip-sdk` at
`52c2eaba23f61e67e9086a7b2328cf9e293da4ea`, the shared identity contract revision.
CI checks out that exact commit in `apple/.sdk/ios-voip-sdk` before resolving the
local package dependency. The SDK manifest reads its adjacent `VERSION` file;
remote manifest preview on Xcode26.6 evaluates it at `/Package.swift`, where
`/VERSION` is absent. Checking out complete sources avoids that preview failure
without modifying SDK sources. Local builds need the same pinned checkout there.
The compile-only CI builds this adapter with the real SDK for a generic iOS
simulator on the standard `xcode-27` runner, because the pinned SDK requires
an iOS27 SDK at build time. It does not boot a simulator. A parallel `macos-26`
job prebuilds and compiles the v2 example for a generic iOS simulator. Native
simulator journeys belong to the SDK repository.

The TS `IOSConversationAdapter` interface is the required Expo Modules 2 port,
using the same `NativeTransport` as Android. Full runtime composition remains
dependent on SDK M3.5: the pinned legacy Expo surface lacks the synchronous JS
snapshot property, typed incoming outcomes, current audio-route port and native
joinPolicy/media credential contract. This boundary does not reinterpret those
gaps as successful operations. The Swift adapter covers the currently available
core commands/state; it preserves SDK errors for the future bridge to encode.

When M3.5 lands, update the pin after review, bind the SDK's existing client,
compose its config plugin, replace the fork's legacy iOS module registration and
select `IosVoipSDK` through the shared TS runtime. Do not register a second iOS
provider. The fork's `ios/` v1 implementation remains until that composition; this
compile-only boundary is outside its CocoaPods source glob.
