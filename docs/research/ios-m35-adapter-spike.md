# iOS M3.5 adapter spike

**Draft; merge is blocked until SDK M3.5 lands on main and a replacement pin is
qualified.** This spike targets
[`78bd2f1595b4cc2b9773ba6e254a6f2be5a80b7b`](https://github.com/ziahamza/ios-voip-sdk/tree/78bd2f1595b4cc2b9773ba6e254a6f2be5a80b7b)
on `codex/sdk-m35-bridge-contract`. It validates the native adapter and proposed
bridge contract early; it does not replace the shipping iOS module registration.

## What is wired

The [Swift adapter](../../apple/Sources/ExpoConversationsBoundary/SDKConversationAdapter.swift)
passes the native Join and incoming prewarmer through the application's existing
SDK startup operation. An Expo host supplies `ExpoMediaSupport.startFromBundle`
to preserve its compiled audio adapter. State, events, commands, handler
registration and server-end intake remain on the SDK-owned client. This package
owns no additional provider, PushKit registry, JavaScript host or audio adapter.

The [TypeScript contract fixture](../../apple/BridgeContract/contract.ts) imports
the actual pinned SDK source. It checks matching Caller and attach/settle shapes,
readonly input acceptance, and native-versus-handler Join requirements. Explicit
negative checks retain the known mismatches: native backend configuration,
incoming media declarations, wire UUID/status invariants, route names, outcome
error codes and server-end delivery. A separate module-scope relay test uses the
existing SDK declaration, before React, and verifies once-only settlement. These
are declaration/host controls; they do not execute Hermes or the Expo macro module.

## Native simulator journeys

The two [XCTest journeys](../../apple/Tests/ExpoConversationsBoundaryTests/AdapterJourneyTests.swift)
use the pinned SDK's simulator runtime and real `ConversationClient` in separate
processes, preserving immutable launch selection:

| Journey | Assertions |
| --- | --- |
| Native Join and push report gate | Two native Joins, including no JS handler and an attached handler whose Join is bypassed; connecting is distinct from a connected report; native server-end intake; terminal invitation retention. SDK simulator push injection feeds its registered receiver; successful reporting precedes media/JS warm requests; terminal intake closes the session and retained delivery does not restart it. |
| Module-scope handler | Register before UI, incoming Join, effective native deadline, explicit connected report, hold/resume, End, outgoing Start/connected/End. |

The push fixture uses the SDK's `CKSimInjectPush`, **not APNs delivery**. Media and
JS-host implementations record boundary calls; no Room connection, JS execution,
microphone, backend claim, permission promotion or answer-to-audio timing is
measured. No Focus or physical-device result is inferred from these tests.
Foreground `reportIncomingConversation` currently requests no prewarm; that gap
is preserved as an explicit negative control rather than patched around locally.

Local qualification on October 27, 2026 used Xcode27.1 and iPhone17 Pro / iOS27.0
arm64: the generic simulator build passed, and each named journey passed once
with zero failures/skips. The same two journeys also passed through the CI runner
locally. Test execution durations are not media latency measurements.
The [draft PR checks](https://github.com/ziahamza/expo-callkit-telecom/pull/33/checks)
and [lane receipt](https://github.com/ziahamza/expo-callkit-telecom/issues/11#issuecomment-6417788391)
track completed CI runs and source-hashed artifacts. The first CI attempt
compiled the adapter but failed before testing: its selected iPhone17 Pro was
incompatible with the runner's iOS27.1 runtime. That attempt is not a passing
journey. The runner must select an iPhone from the runtime's advertised supported
device types rather than assuming a locally available model is compatible.

The [CI runner](../../tools/run-ios-m35-journeys.py) creates and deletes its own
iOS27 simulator, gives each local subprocess a hard timeout, and checks exact
test identities, one pass per process and zero failures/skips. Artifacts contain
an allowlisted JSON summary, SDK/source revisions and device version. Failed
attempts retain a sanitized phase/count receipt and remain failures. Raw logs,
credentials and `.xcresult` bundles are not uploaded.

## Integration blockers

The [Apple boundary gap table](../../apple/README.md#contract-gaps-that-remain-sdk-owned)
links each blocker to pinned SDK source. The modern module does not yet implement
the entire proposed v2 port: typed incoming outcomes, routes and dial-listener
attachment are unfinished; native server-end intake and its event still need
bridge exposure. Named backend/fetcher resolution and automatic app-host
integration remain app/SDK work. SDK prejoin requires hidden subscribe-only
credentials; Android's existing publish-capable credential policy is unchanged.

The next integration pass must use the landed SDK revision, compile both native
and Expo surfaces, project wire values into v2's strict state model, and qualify
the actual Expo/Hermes runtime. Until then this remains a draft native/contract
spike. Android implementation, registration and CI assertions remain unchanged.
