# Push isolation — native/React Native checkpoint

Acceptance: malformed synthetic PushKit delivery B must preserve the identity and
connected state of an existing call A, and A must still accept a native mute
transaction and end normally.

**Measured pass:** library `eae5e37`, simulator fixture `b7a33ab`, Xcode/SDK 27.1,
iOS 27.0 simulator, iPhone 17 Pro. The full Release Expo example and UI test bundle
built; XCTest passed in 22.063 seconds and the runner exited zero. Its run-owned
simulator and temporary fixture libraries were deleted. Existing simulators,
accessibility/display settings and physical devices were untouched.

The test uses actual Expo/native/JS code, the bundled WebRTC framework and genuine
CallKit objects with the separate simulator discovery runtime. It explicitly
triggers the malformed fixture after connection, requires a completion receipt
from the **metadata** API, compares A's UUID before/after, tests mute, and requires
final UUID removal plus a reenabled start button. No APNs credentials were used.

![Connected call A after malformed push B](connected-call-after-malformed-push.png)

The original screenshot was inspected at normal phone resolution: the same call
remains Connected and Muted with the audio session active. No token appears in
this screenshot. The compact [result](result.json) excludes raw application logs;
the example currently logs its VoIP token, so raw `.xcresult` stays private.

## Implemented

- Carried upstream [PR #29](https://github.com/mfairley/expo-callkit-telecom/pull/29),
  preserving Tor Håkon Ingerson Moen / t0rs's commits and attribution. Invalid
  payloads report/end their own UUID without creating an application session,
  preparing audio or emitting ghost events. Placeholder Answer/End guards are
  retained. Failed reports now release their marker.
- A small typed Objective-C delegate adapter uses the exact metadata selector and
  a header feature guard. Swift never references the newer SDK type. Legacy
  delivery remains required-report behavior. Both compiler branches pass Clang
  checks with the local SDK; actual older-SDK qualification belongs to hosted CI.
- Typed plugin option `optionalVoIPPushPolicy` defaults to `reportAll`.
  `ignoreInvalidPayloads` skips only malformed optional pushes. Valid invitations
  still report; required pushes always take the reporting path.
- A native XCTest regression target is added only to generated Xcode files.
  Example buttons now expose correct button/disabled accessibility semantics,
  and the call UUID is available for identity assertions.
- Failure screenshots/hierarchy are captured before termination. The fixture uses
  unique cross-process trigger/receipt names. Receipts are not exactly-once proof.

## Review and other checks

Independent reviews checked native SDK compatibility, delegate retention, policy,
CI, identity assertions, scheduling and cleanup. Fixed review findings included
ambiguous “Inactive” matching, missing identity proof, timer-based injection,
staged-source provenance and missing packaging inputs in the source/CI gates.

Root TypeScript/plugin typecheck and build pass. The four existing plugin tests
pass. Lint exits zero with pre-existing warnings in unchanged `src/` files. The
full generated Expo app compiles and links the adapter. No unit suite was added.

## Limits

This is one end-to-end regression, not complete calling-library qualification.
The runtime is still the discovery prototype; independent two-way media, actual
APNs reporting/watchdog/cold launches, device behavior and the new Swift Package
are not established by this result. System call-screen UI remains separate work.

Successful placeholder UUID markers remain retained for the process lifetime,
as inherited from #29. A valid report can also race asynchronous session
publication/reset. Those lifecycle issues need explicit ownership/generation
handling; an arbitrary eviction timer would weaken delayed-action protection.
Duplicate/cancellation payload semantics are not newly invented in this patch.

See [the regression runner](../../../example/qa/README.md) for reproduction.
