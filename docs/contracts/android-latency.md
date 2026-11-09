# Android native audio readiness

Native media prewarms before Answer. LiveKit keeps the receive subscription and
decoder warm and prepublishes a muted microphone using recordingless buffers.
Physical capture and audible output require a live active Core-Telecom call and
observed communication audio mode within the existing 1500 ms authority bound.
Hold stops physical capture and mutes output; resume requires authority again.

The functional media journeys continue to check report-before-prewarm ordering,
transport and decoded-PCM readiness, no capture or audible output before authority,
hold/resume, decline, timeout, credential failures and server cleanup. Their
answer-to-eligible-decoded-PCM failure bound remains 3000 ms. Decoded PCM is not
acoustic speaker output; these checks do not establish a production percentile.

The repeated fresh-process latency benchmark, its aggregation tools and historical
comparison are preserved on
[archive/android-telecom-research](https://github.com/relayphone/expo-callkit-telecom/tree/archive/android-telecom-research).
The current Android workflow runs the functional suite once, without the separate
20-sample benchmark. It uses synthetic native FCM envelopes, so it does not measure
cloud delivery or OS process launch.
