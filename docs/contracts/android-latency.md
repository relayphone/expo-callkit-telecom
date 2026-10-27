# Android native answer latency

The benchmark measures native answer request to the first eligible decoded remote
PCM callback, with PCM16 peak at least 128. It uses the same real LiveKit publisher
and independent aiortc peer as the native media journeys. It measures decoded
media on an API 35 x86_64 emulator, not acoustic output or FCM cloud delivery.

Each profile contains five fresh calls for each provider/credential path: LiveKit
push and fetcher; raw WebRTC fetcher and fallback despite fresh LiveKit push
credentials. Sources are deterministically interleaved. Each call gets a fresh
room/peer; the application process remains alive. Process age is retained, so
these samples must not be presented as five process-cold launches. Answer follows
PREJOINED plus 150 ms, with prior call cleanup and MODE_NORMAL required.

The baseline profile preserves the existing media behavior. Production signaling,
subscription, capture, playout and route choices are unchanged. The receive-only
probe observes decoder PCM independently of the answer gate. Callback-entry
elapsedRealtimeNanos is captured before checking eligibility or scanning PCM.
A fresh gate per answer/resume prevents pre-answer or previous-generation frames
from becoming the first post-answer result. WebRTC capturedAt and RTCStats report
timestamps are not assumed to share Android's monotonic epoch.

The checked results include Core admission, credential resolution, media join,
answer acceptance, audio mode/endpoint readiness, subscription, decoder readiness,
microphone publication, capture/playback startup and first post-answer PCM.
Transport roles distinguish raw WebRTC, LiveKit publisher and LiveKit subscriber.
Raw ICE callbacks provide callback observations; public RTCStats provide selected
candidate-pair/ICE and DTLS readiness observations. Stats are polled every 25 ms and
may be cached for 50 ms; request and callback times expose additional scheduling
uncertainty. These are first-observed states, not exact handshake durations.
No candidate addresses, certificate data, credentials or raw PCM are retained.
Overlapping intervals must not be added as if they were sequential phases.

`tools/check-native-media.py` checks ordering, audio authority, PCM content, sample
counts and cleanup. `tools/summarize-native-latency.py` groups individual samples
by profile/provider/credential path and reports count, minimum, median and maximum.
Missing phase observations stay null with an observed count. The full DND and
pre-warmed cleanup journeys remain part of the same CI qualification.

The baseline and optimized before/after measurements will be recorded here from
completed CI artifacts, including commit/run references and any measured emulator
floor. The 300 ms goal remains separate from the 3 s failure bound until the measured
results establish which part of the path meets it.
