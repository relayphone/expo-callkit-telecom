# Android native WebRTC

The built-in `media: "webrtc"` session supports audio with a call-scoped HTTPS
SDP exchange. It reuses the exact pinned LiveKit2.29.2 WebRTC runtime and its
initialization/resource owner; it does not join a LiveKit room. So it is part of
the LiveKit media engine, which the plugin's `mediaAdapter: "livekit"` adds to
the build; set it along with `media: "webrtc"`. This avoids a
second WebRTC binary and reinitialization while another factory is alive.
The pinned SDK component boundary is compile-qualified in CI on every change.

```ts
joinPolicy: {
  type: "native", media: "webrtc",
  credentials: {
    type: "http", baseUrl: "https://api.example.com",
    authentication: "device-auth",
  },
}
```

The native credentials endpoint returns `WebRTCMediaCredentials`: provider
`webrtc`, an HTTPS `url`, a short-lived call-scoped `token`, Unix-seconds
`expiresAt`, and optional `iceServers` with urls/username/credential. The shared
APNs/FCM `incomingCall.media` object remains the existing LiveKit-only contract.
Apps can also use a named native credentials fetcher. Neither fetcher waits for JS.

The SDP endpoint accepts Bearer authentication and JSON:

- Offer: `{ type: "offer", sdp, serverCallId, eventId, negotiationId }`.
  Returns `{ type: "answer", sdp }`. Both descriptions include gathered ICE
  candidates; trickle ICE and subsequent renegotiation are outside this contract.
- State: `{ type: "state", state, serverCallId, eventId, negotiationId }`.
  Returns `{}`. States are `prejoined`, `answered`, `declined`, `ended`, or
  `filtered`. Prejoined must never count as answered.

Each preparation attempt has a distinct negotiationId. The server must correlate
cleanup with that id, honor cleanup arriving before a late offer completes, and
expire unused negotiations. A retry closes the previous attempt first. Cleanup
is best effort over the network and always disposes local peer/audio resources.
Local disposal happens first. A separate IO job owns one terminal POST for the
immutable negotiation, bounded to three seconds independently of the cancelled
session/controller and its closed executor. The native `terminalSignalCompleted`
observation reports `delivered: 1` only after successful HTTP completion, plus
numeric `httpErrorStatus` and `deadlineExpired` diagnostics; it may arrive after
CLOSED. Delivery failure does not retain local media resources.
The CI signaling fixture implements this contract with an independent aiortc peer.
Transport disposal and application receipt are observed independently: the decline
fixture gates terminal processing until after a zero-client observation. The
journey then waits for both zero clients and the actual declined receipt, retaining
sanitized request/release/recording timestamps. Local CLOSED does not prove server
receipt.

Before creating peer audio threads, the adapter disables physical recording in
the pinned ADM and mutes native speaker output. Its local track stays disabled,
while PeerConnection recordingless buffers and remote playout/decoding run during
pre-warm. Native audio authority enables physical recording and unmutes output;
the SDP and subscription stay intact. Hold stops physical capture and mutes output
while keeping remote decoding warm; mute stops physical recording only.
The adapter requests no Android audio focus or audio mode. The PCM timing hook
has the same threshold/definition as the LiveKit adapter. Its remoteSubscribed
signal means the raw remote audio track is enabled with a sink; it is not an SFU
subscription acknowledgment. CI artifacts and telemetry contain no SDP, ICE
credentials, JWT or raw PCM. SDP and the scoped Bearer travel only to the configured
HTTPS signaling endpoint.

The HTTPS exchange is this library's explicit reference signaling contract.
WebRTC itself does not specify application signaling; existing servers must
implement this endpoint or provide compatible native negotiation through the
existing native session registry.

API references: [PeerConnection audio recording/playout controls](https://webrtc.googlesource.com/src/+/refs/heads/main/sdk/android/api/org/webrtc/PeerConnection.java),
[independent aiortc peer API](https://aiortc.readthedocs.io/en/latest/api.html).

The [shared latency qualification](./android-latency.md) uses the same decoded-PCM
method and fresh-process sampling regime for this adapter and LiveKit.
