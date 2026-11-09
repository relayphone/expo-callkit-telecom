# Android bridge journeys

The `v2 example` workflow packs the Android fork, installs it with the pinned SDK archive,
and builds a bundled, debuggable release plus an example-targeted test APK. Checkout
autolinking and TypeScript aliases are disabled for this consumer. No Metro server is used.

`node tools/android-journey.ts APK_DIRECTORY OUTPUT_DIRECTORY` runs the test on an API35
emulator. Both APKs must be present. The host uses bounded ADB commands and `run-as`, clears
stale results, validates both evidence files and stops the example even on failure.

The test APK launches `conversations-example://android-journey?runID=...`, injects FCM-shaped
data through production `NativeCallPush` in the example process, and acknowledges each JS
checkpoint only after capturing native snapshots, correlated Telecom state and CallStyle
notification identity. Test-only reflection reads the engine's existing Core-Telecom control
to correlate its platform UUID with the app ID; it never changes engine state or its emitter.
Only sanitized identities, states and counts are retained. This tests native intake and the
installed Expo bridge; network FCM delivery is outside this fixture.

Incoming answer/hold/end and outgoing placement/connect/end use the public JS API.
Answered elsewhere proves an ended snapshot, one correlated event and removal. DND filtering
proves the filtered outcome, teardown and zero media/JS warmup. Android may briefly admit and
then suppress a filtered incoming call. Its terminal snapshot must carry `reason: "failed"`
and `failure: { kind: "filtered", cause: "doNotDisturb" }`, with no performed end action; no call or
notification remains. **Preview join is unsupported**, recorded separately and never counted
as a passed journey. The example disables native media; existing native suites retain real
media hold controls, cold/system actions, duplicate/end races and the 1000 ms removal gate.

`android-journey-results.test.ts` exercises the result oracle with intentional identity,
ordering, handler, state, report, notification and warmup failures. The older example smoke
also remains in CI to cover actual bridge rejection and placement-deadline behavior.

Node tests live under `tools/`. `tsconfig.journeys-app.json` also checks journey sources
with the example's compiler settings before the native build.

The test APK also checks decoded notification URI identity with Android's actual URI
construction, including unrelated callers, packages, fragments and double encoding.
The host requires both instrumentation tests to pass. A failed checkpoint retains its
latest sanitized native, Telecom and notification observation.
