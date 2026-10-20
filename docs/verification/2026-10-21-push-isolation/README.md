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

## Hosted result

[GitHub run 39511821416](https://github.com/ziahamza/expo-callkit-telecom/actions/runs/39511821416)
also passes this real Expo/native/JS regression with **Xcode 26.6 / SDK 26.5 /
iOS 26.5** on macOS 26.6.2. The synthetic merge checkout is
`56cbe6f25ce61a6a36ae6a48f7bef238da80ce37`; the pinned runtime is `b7a33ab`.
The public [summary and environment](hosted-39511821416/) contain no raw app logs.

This proves application/native connected state and genuine CallKit transaction
delivery under the simulator runtime. It does not exercise a network peer,
provider signaling or two-party media. Xcode Cloud and third-party simulator
provider bootstrap environments remain unqualified.

The Xcode 26.3 / SDK 26.2 lane in this run exits 65 during native build before
XCTest; no compiler error was retained. It remains unclassified. The next harness
revision records its failure phase and bounded pre-launch compiler diagnostics.

The follow-up [run 39513498744](https://github.com/ziahamza/expo-callkit-telecom/actions/runs/39513498744)
classified that native-build failure: Xcode 26.3 rejects `SWIFT_RETURNS_RETAINED` /
`SWIFT_RETURNS_UNRETAINED` annotations in Expo's `RuntimeScheduler.h`. The
[bounded diagnostics and stage receipt](hosted-39513498744-legacy-build/) retain
the actual error. This is a dependency compilation failure before XCTest, not a
CallKit or PushKit runtime result. We did not patch third-party headers to make
the lane green.

The workflow now separates actual SDK 26.2 / 26.5 adapter compilation from full
Expo metadata and forced-legacy callback tests on Xcode 26.6. A forced-legacy run
must disable the metadata path in **both** fixture and library, and require a
legacy-specific completion receipt. It qualifies fallback behavior with that
compiler/runtime; it does not qualify the entire Expo stack on Xcode 26.3.

## Metadata and legacy gate

[Run 39514972142](https://github.com/ziahamza/expo-callkit-telecom/actions/runs/39514972142)
passes **all four jobs**: actual SDK 26.2 and 26.5 native adapter checks, plus
full Release Expo metadata and forced-legacy XCTest runs on Xcode 26.6 / iOS
26.5. Each UI lane passes one test with zero failures. Its actual synthetic
merge source is `1adbab17c00ec2e3457d68bb865a2d88cae828ce`.
The [public evidence](hosted-39514972142/) records the selected/expected API and
forced-legacy setting, so a lane cannot silently qualify the wrong callback.

The initial [integration run 39515108673](https://github.com/ziahamza/expo-callkit-telecom/actions/runs/39515108673)
passes both SDK checks and legacy XCTest, but metadata times out in the
installation/permission stage **before XCTest**. That source tree is identical
to the passing feature-branch tree. The [retained receipts](integration-39515108673/)
keep the failed integration qualification explicit. The old stage covered both
commands, so this evidence cannot identify which one stalled. The runner now
records separate stage durations and allows 180 seconds for installation and
60 seconds for permission setup, without automatic retries.

Final standards review also caught a receipt written before cleanup. It now
records the original scenario status separately and the final process status
**after** cleanup. [Controlled CLI evidence](harness-controls/) executes the
actual shell helpers: deletion failure turns a pass into exit 1, an existing
exit 65 survives deletion failure, successful cleanup stays zero, and external
command failure/deadline statuses remain 17/142. These are harness checks, not
additional calling-library unit tests. Subsequent native runs carry their own
results; the earlier pass does not prequalify a changed runner.

## Negative control catches the original failure

The same XCTest was run against an isolated checkout that deliberately restores
ordinary invalid-call session creation followed by `store.firstSession` teardown.
A connected successfully, the explicitly triggered metadata push B completed,
then `XCTAssertTrue(connected.exists)` at line 70 **failed**: A was gone. The
runner exited **65**, with exactly one failed test. This is an application
assertion failure, not a failed build, absent fixture, or startup timeout.

The original [failure screenshot](negative-control/call-a-lost.png) was inspected:
Call Session is Inactive, Call ID is none, and call controls are disabled. The
[structured result](negative-control/result.json), environment, stage receipt
and [reproduction patch](negative-control/restore-wrong-first-session.patch) are
retained. Apply the patch to `35dc7ca` in a disposable checkout and commit it
before running the same QA script; tracked QA/native sources otherwise match.
The intentionally broken commit was local only, not a feature-branch change.

The first failed-control attempt also exposed Xcode's automatic failure
sysdiagnose exceeding the runner deadline. The final run disables that verbose
collection, preserves the test's explicit screenshot/hierarchy attachments and
finishes normally with the expected assertion failure. Earlier dependency/startup
failures and the incomplete result are not counted as negative-control proof.

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
