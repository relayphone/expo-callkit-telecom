# Android instrumentation

Notification UI waits retain hierarchy XML and screenshots under the test
application's `additionalTestOutputDir/systemui-failures` before cleanup. AGP
collects this standard output under `build/outputs/connected_android_test_additional_output/`
before uninstalling the test APKs. Manual invocations without that argument
fall back to external app files. Capture is deduplicated
per test. These fixtures use synthetic call identities on en-US emulator images.
The helper keeps the original v2 navigation and five-second waits. The additional
navigation retries are removed after the measured comparison; the Home fallback
is omitted because an isolated benefit has not been established.

The notification UI workflow runs both complete Answer and Activity-cleanup
classes 20 times per warm-up condition on API 35, in four batches of five.
Each repetition uses Gradle's `connectedDebugAndroidTest` with both classes in
one instrumentation process, the ordinary AGP install/permission handling and
the same disabled animations as the functional suite. The comparison uses the
default two-core/2 GiB guest. One condition adds bounded launcher idle and one
throwaway shade open/close before the first connected run; the other adds none.
The warm-up condition retains launcher/open/closed hierarchies and completion
evidence. No notification clearing is added. Each run retains Gradle stdout and stderr,
JUnit reports, AGP additional output, suite wall time and per-class test time.
Incomplete, skipped and failed tests remain fatal, including a nonzero Gradle
exit after passing reports. `compare BATCH` completes all five planned rounds
and reports any failures after the batch, giving equal comparison samples.
`run BATCH` is final qualification and stops at its first failed connected run.
The missing-card artifact control runs after all five measured repetitions,
so its deliberate timeout cannot affect their starting UI state. Both conditions
run identical test code and retain the budget guard's requested limits and
observed memory/CPU report beside the run artifacts. The prior equal-sample
resource comparison had identical class/case pass counts for both allocations;
larger resources are not enabled in the functional job.

Hold revokes the current capture generation and disables ADM recording before
publication mute, unpublish, prewarm stop or disposal. A restarted pump reapplies
that generation's recording state before its read loop. Capture diagnostics
check the current epoch, including after hold and close.

`NativeCaptureRestartPathTest` injects a pump restart from unpublish, track stop,
dispose, `setMicrophone` and the ADM itself, each with a stale enabled flag.
`NativeCaptureAuthorizationTest` checks per-generation diagnostics and stale grants.
`NativeWarmAudioTest` repeats every restart source through the production pump
callback and bound recording device, plus per-epoch diagnostics and closed restarts;
`NativeCaptureRestartTest` checks real ADM hold and cancelled playback-boundary
retirement. The full `realNativeLiveKitAudioAndCleanup` journey keeps its original
hold/resume timestamp assertions.

Journey evidence retains `holdRequestedNanos` and `resumeRequestedNanos`, plus
the latest 128 relevant hold/resume signals on the device monotonic clock.
`captureRetirement` signals bracket each call with `entered: 1` and `entered: 0`:
step 1 is publication mute, 2 unpublish, 3 prewarm stop and 4 disposal. These
timings allow an ADM restart to be correlated with its retirement operation.

This small application compiles the production conversations sources directly,
without requiring a local Expo prebuild or an Android Studio installation. It is
not an alternate implementation. `NativeIdentityReservationsTest` exercises the
runtime's exact application IDs, including Unicode and surrounding whitespace,
shared incoming ownership, direction collisions and safe reuse after release.
The installed package consumer separately checks raw IDs through the shared SDK
API. Public call IDs are never derived UUIDs.

Decoded-push expiry assertion messages retain the actual expiry/end wall times,
their signed difference and paired wall/elapsed samples at intake and end.
Answer traces retain the first 32 available/current endpoint emissions from
admission through the first timeout, with elapsed times and up to eight numeric
endpoint types per emission. Truncation counts are explicit; endpoint names and
identifiers are omitted. Existing assertions and waits are unchanged.

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

Receiver waits keep the latest 32 poll start/end times, the reached HTTP phase,
and the last typed running state, generation, error class and counters. The
shared 10-second budget and receiver assertions are unchanged. Requests, response
payloads and exception messages are excluded. Claim failures also retain their
own capture counters in JUnit and callback traces in `capture-trace.json`'s
`claimFailures`, labelled with the fixture claim ID and configured claim delay.
Each of the latest four failed claims retains its first 16 and last 240 callbacks,
with a dropped count; later journey writes preserve those entries. Claim capture
does not perform the separate post-APM qualification.

The muted-source journey first requires a valid 480-frame recordingless buffer
within 3300 ms of microphone publication, then runs its existing 100 ms progress
and pacing assertions. Evidence records publication, pump-start and first-buffer
times, publication-to-pump and pump-to-first-buffer intervals, and startup latency
on success and failure in the result and capture traces. The three ordinary emulator
startup samples (208, 694 and 1628.8 ms) give 3300 ms using
`round_up_100ms(max(2 × P95, 1.25 × max))`. A readiness failure reports `source-start-timeout`.
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

## Push faults on API 34, 35 and 36

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

## Cold-process push journey

`node tools/android-push-faults.ts emulator-5554 cold-start` force-stops the host,
confirms no process remains, then explicitly launches the shell fixture carrying
an invitation into `CallPushIntake`. A new PID must journal process start, intake,
ringing, posted Answer/Decline actions, a performed notification Answer and cleanup
under the same call, event and delivery IDs. The host also checks Telecom and
notification cleanup. Each device wait keeps a five-second bound.

Process start uses `Process.getStartElapsedRealtime()` (millisecond precision,
serialized as nanoseconds); subsequent observations use `elapsedRealtimeNanos`.
The CLI derives durations only within that device clock. Functional CI runs this
after the standard journeys and retains the new journal under `coldStart` in
`push-fault-events.json`, preserving the earlier process's fault events.

CI runs the cold journey on API 35. Local qualification covers API 34, 35 and 36.
After assembling the Debug host, run it on a disposable emulator with
`bash tools/run-android-cold-push.sh emulator-5554`. It installs and precompiles
the APK, then retains the journal, final state and logcat under
`android/instrumentation/build/cold-push-results`.

After the native cleanup stage, the host polls until Telecom has removed the
call and the call notification is gone. This check has a five-second cap within
the existing command deadline. Disconnected calls still count until removed.

This is a cold-start intake/bootstrap qualification with media disabled and a
native handler that proceeds. After the posted Answer settles as `Performed`,
the host requires `connecting`, supplies the application's `connected` report,
and requires the connected snapshot before cleanup. With native media disabled,
Answer alone does not report a media connection.

Cold intake starts after the shell Activity resumes. The sender stays open
until cleanup, allowing it to send the posted Activity PendingIntent as a user
interaction. The launch Activity
consumes the native answer delivery before JavaScript. Answer and Decline must
both be posted; Answer must target an Activity and Decline a broadcast.

It does not deliver FCM: Android does not deliver
ordinary FCM messages to force-stopped apps until an explicit launch clears the
stopped state. The activity remains debug/emulator-only and shell protected.

The exploratory DND study and its measurements are preserved on
[`archive/android-telecom-research`](https://github.com/relayphone/expo-callkit-telecom/tree/archive/android-telecom-research).
The active suite keeps `DndMatrixTest` for functional filtering and
`DndWarmCleanupTest` for teardown of prewarmed media after a policy change.

### Receiver readiness diagnostics

The media qualification keeps the existing shared 10-second prejoin/subscription
budget. `media-hook-results.json.receiverWait` records the device's prejoin,
publication and polling boundaries in `android.elapsedRealtimeNanos`, including
whether the first receiver read was ever reached. The failure summary names
unobserved boundaries rather than treating every timeout as a peer failure.

The existing media-evidence artifact also contains:

| File | Observations |
| --- | --- |
| `receiver-hooks-*.json` | Automatic subscription requested at `room.connect`, connection completion, remote publication/subscription callbacks, authenticated server publication event and 250 ms event-loop lag samples |
| `receiver-host.jsonl` | Independent one-second host CPU, memory/swap, peer process CPU/RSS and main-thread scheduler runtime/runqueue-wait counters |

Peer callback times use `host.monotonic_ns`. Host resource samples use
`host.hrtime.bigint`. Both include labelled host Unix milliseconds for wall-time
correlation; never subtract device and host monotonic timestamps. Server webhook
`created_at` is labelled Unix seconds with whole-second precision, including
server telemetry scheduling before notification; its peer delivery time is
recorded separately. Missing webhook evidence alone cannot establish late app
publication.

The peer retains automatic subscription, requested before the invitation is
returned to the app. This path has no later per-track subscribe request. The
loopback-only webhook receiver authenticates LiveKit signatures and retains only
synthetic test identities, track IDs and timestamps. The independent resource
sampler continues when the peer event loop or host Gradle command is blocked,
and exits when the fixture wrapper exits or its existing 20-minute limit ends.
No scheduling priorities, media gates or timing budgets change.
