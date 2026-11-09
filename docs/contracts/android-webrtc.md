# Android WebRTC instrumentation research

Raw WebRTC lives in the standalone Android instrumentation project and is
registered explicitly by its media journey. It is excluded from the published
native module; `androidNativeMedia: "livekit"` includes only the LiveKit session.
The shared SDK's `MediaAdapter` type remains `"webrtc" | "none"`.

The fixture's adapter reuses the pinned LiveKit WebRTC runtime and a bounded
HTTPS signaling helper; the credentials lookup is registered by the test APK as
a named fetcher. The library no longer ships the HTTP credentials-fetch policy
or native authentication registry. The shared incoming push media contract is
LiveKit-only.

The complete original setup and measurements are preserved on
[archive/android-telecom-research](https://github.com/relayphone/expo-callkit-telecom/tree/archive/android-telecom-research).
The independent fixture supplies an HTTPS signaling URL, short-lived token,
expiry and ICE server configuration for each test attempt.

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

The HTTPS exchange is the retained research fixture's reference signaling contract.
WebRTC itself does not specify application signaling; existing servers must
implement this endpoint or provide compatible native negotiation through the
existing native session registry.

API references: [PeerConnection audio recording/playout controls](https://webrtc.googlesource.com/src/+/refs/heads/main/sdk/android/api/org/webrtc/PeerConnection.java),
[independent aiortc peer API](https://aiortc.readthedocs.io/en/latest/api.html).

The [native audio readiness contract](./android-latency.md) describes the retained
functional checks and the archived latency benchmark.
