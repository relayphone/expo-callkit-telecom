# Android instrumentation

This small application compiles the production conversations sources directly,
without requiring a local Expo prebuild or an Android Studio installation. It is
not an alternate implementation. `NativeIdentityReservationsTest` exercises the
runtime's exact application IDs, including Unicode and surrounding whitespace,
shared incoming ownership, direction collisions and safe reuse after release.
The installed package consumer separately checks raw IDs through the shared SDK
API. Public call IDs are never derived UUIDs.

CI runs `gradle -p android/instrumentation connectedDebugAndroidTest` on an API 35
Google APIs x86_64 emulator with KVM, and retains machine-checked JUnit results.
The Expo bridge/example integration is a separate gate from this native seam.

Media qualification failures append an ASCII numeric trace to the original
assertion type and message in JUnit XML. The copy is at most 8,192 bytes, ending
with `[TRUNCATED media qualification trace]` when clipped. It preserves the drain
guard's actual counts and limit, receiver publication/subscription boundaries,
and consumer/fixture stage timings in their separately labelled monotonic clocks.
Slowest and latest stages precede history so large histories retain those clues.
Only fixed stage labels, numeric values, booleans and publication states are copied;
PCM, credentials, notification content and arbitrary diagnostic messages are excluded.

Device JSON collection still runs with its existing shared 10-second bound.
The XML records device-file publication success or a classified publication error;
host collection has not started when the assertion is raised. The later host
attempts and classified collection errors remain in `media-evidence-reads.json`
and the CI summary. Known `adb` tool errors on stdout are classified there too,
including a missing file reported with exit status zero.

`MediaQualificationTraceTest` exercises actual guard snapshots and truncation.
To verify transport through Gradle's JUnit XML writer, set
`MEDIA_XML_FORCE_FAILURE=1` and select `*forcedFailure*`, `*oversizedFailure*` and
`*forcedClaim*` with
`testEmulatorTestUnitTest -PmediaTestBuildType=emulatorTest`. These intentional
controls fail; the XML must retain the original failure first and the bounded
trace. Run the normal suite without that variable afterward.

Claim failures also retain the last prejoin client count and its device-clock
observation time, the failure's native signal timeline, and any signaling snapshot
already fetched by the test. Publication counts and audio-bearing offer counts
remain separate in the fixture's `python.time.monotonic_ns` clock. A zero-client
prejoin failure does not imply that Answer or publication occurred. The fallback
adds no network request or wait, and preserves the existing count assertions.

The muted-source journey first requires a valid 480-frame recordingless buffer
within 1500 ms of microphone publication, then runs its existing 100 ms progress
and pacing assertions. Evidence records publication, pump-start and first-buffer
times and startup latency. A readiness failure reports `source-start-timeout`.
Output qualification drains while at most one HTTP acknowledgement is pending,
batching contiguous PCM and every RTC counter sample for the next request.
Both the tap backlog and pending batch retain the 24,000-frame / 500 ms limit;
an overflow, stopped source, missing output or failed fixture still fails.

The recordingless journey flushes its original assertion, test/phase, process ID,
receiver clock/counts and the last eight source samples before optional failure
enrichment. Samples retain monotonic elapsed nanoseconds and injected-frame
counts on both sides of the unchanged 100 ms check. Cleanup adds room-release
and capture-stop evidence; the original exception is rethrown unchanged.

The media job then selects that same journey with
`mediaForceRecordinglessFailure=true` on its existing API 35 emulator. Its single
intentional assertion must fail within the 180-second command timeout and
240-second control budget. The checker requires the persisted original, bounded
JUnit trace, matching process exit/liveness evidence and completed cleanup.
Normal suite XML is retained separately before the control replaces its report.
The normal suite remains fatal even when the control passes.

ART pending-exception aborts retain only exception/cast class names and the first
32 pending frames with source locations. Free-form abort messages are discarded.
The classification marks production frames in LiveKit, WebRTC or this module;
test frames alone do not make a production-crash candidate.

Query the API 35 emulator from the host with
`node tools/check-android-journey-state.ts emulator-5554`.
An optional second argument selects the application package; the default is
`expo.modules.callkittelecom.instrumentation`. The JSON contains current Telecom
calls and posted notification presence for that package. Each `callId` is a
Telecom platform identity such as `TC@3`; `state` uses the platform state names,
and `endReason` is the native disconnect reason when available, otherwise `null`.
Archived calls and notifications are excluded. Sequential dumps carry a host
monotonic observation interval. Raw dumps, handles and notification content stay
out of the output. The functional runner checks the parser controls and executes
the query on its existing emulator; malformed or unsupported dumps fail the run.

## Push faults on API 35

Install the Debug instrumentation host, grant microphone/notification permissions,
and use an explicit emulator serial. These controls inject at the app-owned
`CallPushIntake` seam; they do not send FCM, bypass a force-stop, or require Firebase
credentials. The exported activity requires the shell's `DUMP` permission and also
rejects non-debug builds and non-emulators.

```sh
node tools/android-push-faults.ts emulator-5554 '{"kind":"reset"}'
node tools/android-push-faults.ts emulator-5554 '{"kind":"deliver","callId":"call-a","deliveryId":"delayed-1","eventId":"invite-a","delayMillis":1000}'
node tools/android-push-faults.ts emulator-5554 '{"kind":"duplicate","callId":"call-a","deliveryId":"duplicate","eventId":"invite-a","delayMillis":0}'
node tools/android-push-faults.ts emulator-5554 '{"kind":"drop","callId":"call-b","deliveryId":"dropped-1","eventId":"invite-b","delayMillis":0}'
node tools/android-push-faults.ts emulator-5554 '{"kind":"rebind-empty-token","deliveryId":"rebind-1"}'
node tools/android-push-faults.ts emulator-5554 events
```

Commands enqueue process-owned work. Inspect the journal again after a delay;
`recognized` means the intake decoder accepted the message, not that asynchronous
Telecom admission has finished. The Android journey checks ringing and Telecom
admission separately and proves duplicate messages create one call. Duplicate
commands generate `.1` and `.2` delivery IDs with the same call/event identity.
Drops are journaled without calling intake. Delays are limited to 30 seconds and
each reset permits 64 unique delivery IDs; reset refuses outstanding work.

The rebind check covers both an absent token and an empty value supplied through
`CallPushIntake.newToken`. It replaces bridge ownership, compares snapshots and
restores the original bridge/token. Only presence bits, revisions and equality
are recorded, never token values. Run it between calls because rebinding resets
bridge action ownership.

`files/push-fault-events.json` is an atomic, bounded journal with process identity
and decimal-string device monotonic nanoseconds. The live reader rejects a log
from another process. Functional CI retains this file even on failure; parse a
saved artifact with `node tools/android-push-faults.ts --file <path>`. The log
contains delivery and token observations, with no media credentials or payloads.

The exploratory DND study and its measurements are preserved on
[`archive/android-telecom-research`](https://github.com/relayphone/expo-callkit-telecom/tree/archive/android-telecom-research).
The active suite keeps `DndMatrixTest` for functional filtering and
`DndWarmCleanupTest` for teardown of prewarmed media after a policy change.
