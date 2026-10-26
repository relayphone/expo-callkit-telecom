# Android native media

`joinPolicy: { type: "native", media: "livekit", credentials: "fromPush" }`
uses the built-in LiveKit session. Reporting to Core-Telecom precedes pre-warm.
The room connects with automatic subscription, microphone and camera disabled.
The SDK uses `NoAudioHandler`; Core-Telecom owns focus and communication mode.
Capture and remote subscription begin only after native answer/audio authority.
Hold suspends capture and remote playback. Decline, timeout and termination close
the native session through the controller's cancellation lifetime.

For missing/expired push credentials, configure a built-in HTTPS fallback:

```ts
joinPolicy: {
  type: "native", media: "livekit", credentials: "fromPush",
  fallback: {
    type: "http", baseUrl: "https://api.example.com",
    authentication: "device-auth",
  },
}
```

Register `NativeMediaAuthenticationProviders.providers["device-auth"]` during
`Application.onCreate`. The suspending native provider returns
`NativeMediaAuthentication(bearer, deviceId)` from the app's existing native
authentication store. It must not wait for JavaScript. No token belongs in the
plugin configuration. App-registered named fetchers remain supported.

The client POSTs `/v1/calls/{percent-encoded-serverCallId}/media-credentials` with
`{ eventId, deviceId }` and Bearer authentication. TLS validation is enabled,
redirects are refused and request/body sizes are bounded. HTTP404/410 abandons
pre-warm; the controller permits one answer-time preparation retry. Credentials
are never logged or placed in conversation snapshots.

`NativeMediaSignals.observers` is a native main-thread lifecycle hook. Signals
carry a serverCallId, a monotonic timestamp and numeric measurements. Audio
callbacks enqueue observations without running app code on the audio thread.
`firstAudio` means the first decoded remote PCM16 frame with peak at least128
(-48dBFS), after native activation; it is not a hardware/acoustic measurement.
No raw audio or credentials are retained.

The Ubuntu/API35 journey uses the production FCM data entry with a synthetic
high-priority envelope (it does not prove cloud FCM delivery), a real LiveKit
server and a remote PCM publisher. Both pushed credentials and the HTTPS fetcher
must prejoin without capture, playback or remote subscription. Native answer runs
without a JS Join handler. Checked `native-media-results.json` records the report,
prejoin, JS warm request, answer, audio authority and first PCM timestamps. Its
3s emulator budget is separate from the 300ms product target; the artifact reports
actual timing. The native harness records the JS warm request; the compiled Expo
service invokes `ReactHost.start()`.

The journey also requires capture/playback to stop on hold and restart after resume, and
room departure on decline/timeout. Missing credentials (404/410) spend one retry
on answer and terminate without capture. Generated fixture credentials and the
test CA stay in the CI workspace; only sanitized result JSON and JUnit are uploaded.
The instrumentation application's test CA is never trusted by production code.

The LiveKit transport sends no application-specific room data messages. Native
lifecycle hooks provide the app's backend integration boundary. Required decline
cleanup is Core call removal, native closure, no capture, and verified server
participant departure.
