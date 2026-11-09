# Android native media

`androidNativeMedia: "livekit"` includes the built-in LiveKit session.
Production native media uses fresh LiveKit credentials from the incoming push. Reporting to Core-Telecom precedes pre-warm.
The room connects with automatic remote subscription and the camera disabled.
A prepublished, muted microphone uses paced recordingless buffers. Remote audio
tracks and decoding run during pre-warm with native speaker output muted.
The SDK uses `NoAudioHandler`; Core-Telecom owns focus and communication mode.
Physical capture and audible output begin only after native answer/audio authority.
Answer unmutes the existing sender and output without another negotiation.
Hold stops physical capture and mutes output while receive decoding stays warm. Decline, timeout and termination close
the native session through the controller's cancellation lifetime.

The production package has no built-in HTTP credentials fetcher or native
authentication-provider registry. Missing or expired push credentials fail
preparation; an answer may spend the controller's existing single preparation
retry. The independent server's named credentials fetcher lives entirely in the
instrumentation APK and retains HTTPS, identity, cancellation and HTTP404/410
failure controls. It is not shipped in either library media variant.

`NativeMediaSignals.observers` is a native main-thread lifecycle hook. Signals
carry a serverCallId, a monotonic timestamp and numeric measurements. Audio
callbacks enqueue observations without running app code on the audio thread.
`firstAudio` means the first decoded remote PCM16 frame with peak at least128
(-48dBFS), after native activation; it is not a hardware/acoustic measurement.
No raw audio or credentials are retained.

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

[Android latency qualification](./android-latency.md) records five fresh-process
samples per provider/credential path, transport/decoder/route phases and the
before/after timing comparison. The original full journeys still run separately.
