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
