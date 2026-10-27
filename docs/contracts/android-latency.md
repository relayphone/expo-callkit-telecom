# Android native answer latency

The benchmark measures native answer request to the first eligible decoded remote
PCM callback, with PCM16 peak at least 128. It uses the same real LiveKit publisher
and independent aiortc peer as the native media journeys. It measures decoded
media on an API 35 x86_64 emulator, not acoustic output or FCM cloud delivery.

Each profile contains five measured calls for each provider/credential path:
LiveKit push and fetcher; raw WebRTC fetcher and fallback despite fresh LiveKit
push credentials. Each measurement starts a fresh application process and creates
a fresh room/peer. All twenty attempts are required; failures are not retried,
skipped or filtered. The app is already running when its native incoming-push
entry receives the fixture invitation, so this does not measure OS process launch
or FCM delivery. Answer follows PREJOINED plus 150 ms. The same process regime
applies to baseline and optimized profiles.

The full native media journeys still run separately, including hold/resume,
decline, timeout and terminal-credential cleanup. Before PUSH_RECEIVED and after
cleanup, a bounded platform CallsManager dump must show zero calls; audio mode
must return to MODE_NORMAL before the invitation. Core may use MODE_RINGTONE
while ringing. These barriers are outside timed phases. Each dedicated sample
also verifies native media closure, zero server clients, recording-session cleanup
and, for raw WebRTC, an acknowledged terminal state before its process exits.

Earlier profiling attempts kept one process alive across ten calls per provider.
Those runs exposed missing Core-Telecom current-endpoint callbacks after several
raw calls, including when the platform reported zero prior calls. They failed the
unchanged audio-authority deadline and remain failed diagnostic evidence. Process
isolation controls this history variable; it does not claim that the repeated-call
platform issue is fixed. Neither the 1.5 s audio-authority deadline nor the 3 s
answer-to-PCM failure bound is relaxed.

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
Physical recording checks compare session identity sets with the idle snapshot,
monitoring changes from before invitation through cleanup. Android's public list
is device-wide, so global zero is not an app-specific assertion. Session IDs stay
only in memory; artifacts contain counts, source/silenced enums and observation
times. This is delta evidence in an isolated emulator, not UID attribution.
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

The optimized profile keeps the remote subscription and decoder enabled and the
native playback track running behind ADM speaker mute. LiveKit prepublishes a
muted microphone using recordingless buffers; raw WebRTC negotiates the sender
once during pre-warm. Fake capture buffers are paced at their audio duration and
do not open the microphone. A cached public Core starting-endpoint preference
preselects the route without delaying initial call reporting; the observed current
endpoint and communication mode still authorize audio.

After that authority, Answer releases native output mute and arms the same decoded
PCM gate. It enables physical recording and unmutes the existing sender without
another SDP exchange or subscription. Remote PCM timing does not wait for the
microphone's first physical read. On hold, output is muted while receive decoding
stays hot; physical capture stops. Resume again waits for native audio authority.
Non-speaking callers and absent remote publications remain answerable: optional
remote decoder/output warmup is bounded to two seconds rather than a requirement
that the caller produce nonzero audio. The CI tone fixture separately requires
both transports, nonzero decoder PCM, running playback and muted output before
PREJOINED.

Optimized privacy evidence includes positive bytes read by this ADM after answer,
zero new recording sessions before answer, silent post-mute AudioTrack buffers
before answer, and nonzero output after authority. The playback observation is
installed only when the pinned direct-buffer backing-array API is supported; the
API35 qualification requires it. The baseline capture timestamp is an SDK start
callback; the optimized timestamp comes from a positive physical read. Decoded
remote PCM remains the shared before/after metric. No raw audio is retained.
