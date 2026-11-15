# Android native media

`androidNativeMedia: "livekit"` includes the built-in LiveKit session.
Production native media uses fresh LiveKit credentials from the incoming push. Reporting to Core-Telecom precedes pre-warm.
The room connects with automatic remote subscription and the camera disabled.
Prejoin is receive-only: it creates no local audio track, publishes nothing, and
does not check or request microphone permission. Remote audio tracks and decoding
run during pre-warm with native speaker output muted. A remote track is optional.
The SDK uses `NoAudioHandler`; Core-Telecom owns focus and communication mode.
Physical capture and audible output begin only after native answer/audio authority.
Answer creates and publishes the microphone once, after the participant has
permission to publish microphone audio. Publication starts muted, preserving a
mute requested before Answer. Permission waiting and publication share the existing
activation deadline; no repeated publish attempts are made. A missing grant emits
the media diagnostic reason `PUBLISH_PERMISSION_NOT_GRANTED` and fails activation.

When `ConversationActionResult.Proceed(media)` returns fresh Answer credentials,
the receive-only prejoin is deliberately closed before the new controller connects
using that grant. The call and audio authority keep their ownership through the
swap. Closing the retired prejoin does not report a remote or failure end. The
replacement receives remote tracks again and publishes one microphone; credentials
must still match the same server call and participant identity.
The answered replacement connects without waiting for the optional prejoin decoder
warm-up; remote reception recovers alongside microphone activation. The ringing
prejoin retains its existing optional subscriber warm-up window.
If retirement fails or exceeds the existing cleanup bounds, the replacement does
not connect or retry preparation; activation fails with `PREJOIN_CLOSE_FAILED`.
Hold stops physical capture and mutes output while receive decoding stays warm. Decline, timeout and termination close
the native session through the controller's cancellation lifetime.

The production package has no built-in HTTP credentials fetcher or native
authentication-provider registry. Missing or expired push credentials fail
preparation; an answer may spend the controller's existing single preparation
retry. The independent server's named credentials fetcher lives entirely in the
instrumentation APK and retains HTTPS, identity, cancellation and HTTP404/410
failure controls. It is not shipped in either library media variant.

`NativeMediaSignals.observers` is a native main-thread lifecycle hook. Signals
carry a serverCallId, a monotonic timestamp and numeric measurements. The
`ROOM_DISCONNECTED` signal carries an empty `serverCallId` by design, with the
reason name only in `reason` and no room or participant identifiers.

An active controller mute emits `MUTE_SERIAL_ENTERED`/`EXITED` for serial-lock
acquisition and `MUTE_SESSION_ENTERED`/`EXITED` around the session call, including
its dispatcher switch. LiveKit adds `MUTE_PUBLICATION_ENTERED`/`EXITED` around the
publication setter and `MUTE_BOUNDARY_ENTERED`/`EXITED` around the capture-pump
stop wait. Each pair shares a numeric `operationId`. Exit measurements contain
`completed=1` on normal return or `0` on failure/cancellation. Timestamps are
captured at each boundary before main-thread delivery. Missing downstream
entries identify work that was not reached; an exit alone does not prove success.
The controller's 1000 ms mute deadline and the pump's 900 ms wait remain in force.

For outgoing, answered and held calls, disconnect reasons have these outcomes:

| Reason | Outcome |
| --- | --- |
| `ROOM_DELETED`, `PARTICIPANT_REMOVED` | Normal `remoteEnded`, without a failure |
| `CLIENT_INITIATED` received through room observation | `failed`, failure kind `media` |
| Other LiveKit reason names | `failed`, failure kind `media` |
| `RECONNECT_TIMEOUT` | The 30-second reconnect deadline expired; `failed`, failure kind `media` |

v2's own release emits `CLIENT_INITIATED` after retiring room observation, so it
does not trigger another logical end. A ringing incoming call only loses its
optional pre-join and keeps ringing, whatever the disconnect reason.

Audio callbacks enqueue observations without running app code on the audio thread.
`firstAudio` means the first decoded remote PCM16 frame with peak at least128
(-48dBFS), after native activation; it is not a hardware/acoustic measurement.
No raw audio or credentials are retained.

Recordingless buffers remain paced by their audio duration. The former silent-buffer
CPU/timing report is preserved in the
[research archive](https://github.com/relayphone/expo-callkit-telecom/blob/archive/android-telecom-research/android/src/livekit/java/expo/modules/callkittelecom/conversations/NativeWarmAudio.kt).

The Ubuntu/API35 journey uses the production FCM data entry with a synthetic
high-priority envelope (it does not prove cloud FCM delivery), a real LiveKit
server and a remote PCM publisher. Both pushed credentials and the instrumentation-only named fetcher
must prejoin without physical capture or audible output. The tone fixture requires
subscription, decoded PCM and muted native playback to be ready before prejoin. Native answer runs
without a JS Join handler. Checked `native-media-results.json` records the report,
prejoin, JS warm request, answer, audio authority and first PCM timestamps. Its
3s emulator budget is separate from the 300ms product target; the artifact reports
actual timing. The native harness records the JS warm request; the compiled Expo
service invokes `ReactHost.start()`.

The journey also requires physical capture to stop and output to become silent on
hold, then both to resume after audio authority, and room departure on decline/timeout. Missing credentials (404/410) spend one retry
on answer and terminate without capture. Generated fixture credentials and the
test CA stay in the CI workspace; only sanitized result JSON and JUnit are uploaded.
The instrumentation application's test CA is never trusted by production code.

The LiveKit transport sends no application-specific room data messages. Native
lifecycle hooks provide the app's backend integration boundary. Required decline
cleanup is Core call removal, native closure, no capture, and verified server
participant departure.

[Native audio readiness](./android-latency.md) describes the retained functional
checks and links to the archived fresh-process latency study.
