# Native Android answer-latency comparison

API 35 x86_64 google_apis emulator, pixel_6 profile, Ubuntu GitHub Actions with KVM. Values below describe this controlled emulator experiment.

- Baseline: [682471b](https://github.com/relayphone/expo-callkit-telecom/commit/682471b36c49b213f5ed9abd2c012058a2c76533), [Android run](https://github.com/relayphone/expo-callkit-telecom/actions/runs/40445076518); 20 checked attempts, five per path.
- Optimized: [bb6d636](https://github.com/relayphone/expo-callkit-telecom/commit/bb6d6362a4e04b7703d64540667bc68db44ea388), [Android run](https://github.com/relayphone/expo-callkit-telecom/actions/runs/40447020455); 20 checked attempts, five per path.

Each measurement starts a fresh app process and fresh room/peer. The app is already running when the native fixture invitation arrives. Answer is requested after PREJOINED plus a 150 ms delay. No measured attempt is retried, skipped or filtered. The [checked JSON](./android-latency-results.json) retains exact artifact SHA256 hashes and the five answer values in sampleIndex order. Method and native lifecycle details are in [Android latency](./android-latency.md). `webrtc / livekitPush` means WebRTC fetcher fallback despite fresh LiveKit push credentials.

## Answer request → first eligible remote PCM

Milliseconds: minimum / median / maximum. All paths have n=5 per profile. The target is 300 ms; the failure bound remains 3,000 ms.

| Provider / credentials | Baseline min / median / max | Optimized min / median / max | n before / after |
| --- | --- | --- | --- |
| livekit / fetcher | 954.03 / 965.17 / 1123.17 | 160.39 / 180.84 / 186.27 | 5 / 5 |
| livekit / push | 919.32 / 1039.07 / 1057.95 | 159.65 / 164.25 / 183.64 | 5 / 5 |
| webrtc / fetcher | 162.60 / 175.45 / 257.91 | 160.39 / 180.55 / 184.53 | 5 / 5 |
| webrtc / livekitPush | 161.99 / 172.89 / 210.13 | 169.10 / 178.78 / 189.06 | 5 / 5 |

All 20 optimized samples meet 300 ms. LiveKit median reductions are 84.2% for push and 81.3% for fetcher. Raw WebRTC already meets 300 ms in the controlled baseline; its medians move from 175.45 to 180.55 ms and 172.89 to 178.78 ms. These samples show no raw median speedup.

The minimum observed optimized latency in this primary comparison is 159.65 ms. Across all 20 samples, Answer to Core/mode authorization takes 117.61–160.55 ms; authorization to eligible PCM takes another 17.42–63.23 ms. Mode is polled every 10 ms and PCM callbacks carry 10 ms of audio. Scheduling and frame/callback timing remain in the release interval. This is an observed emulator range, not a fundamental lower bound or a production percentile. The 300 ms target is reachable here.

The 300 ms result applies to answers after PREJOINED. Earlier answers can still wait for native preparation. LiveKit push→PREJOINED medians move from 949.71 to 2061.45 ms for push and 1560.93 to 2789.14 ms for fetcher. Under this answer-after-PREJOINED schedule, push→PCM medians move from 2095.86 to 2390.50 ms and 2683.70 to 3130.32 ms. Work moved before Answer; these runs do not demonstrate an invitation-to-audio improvement.

## Preparation and audio authority

Median milliseconds; missing observations are —. Counts and ranges are retained in JSON.

| Profile / path | Push → prewarm | Push → PREJOINED | Answer → accepted | Accepted → authorized | Audio wait → mode | Authorized → PCM |
| --- | --- | --- | --- | --- | --- | --- |
| baseline / livekit / fetcher | 96.67 | 1560.93 | 12.56 | 134.66 | 123.87 | 812.41 |
| baseline / livekit / push | 117.30 | 949.71 | 10.06 | 127.77 | 119.14 | 901.86 |
| baseline / webrtc / fetcher | 106.26 | 1220.76 | 10.97 | 131.41 | 117.90 | 30.01 |
| baseline / webrtc / livekitPush | 87.20 | 1064.06 | 11.21 | 129.52 | 121.45 | 37.80 |
| optimized / livekit / fetcher | 101.57 | 2789.14 | 12.40 | 129.46 | 120.47 | 41.77 |
| optimized / livekit / push | 81.45 | 2061.45 | 10.19 | 133.12 | 122.68 | 20.91 |
| optimized / webrtc / fetcher | 98.43 | 1129.68 | 9.88 | 127.48 | 116.37 | 42.68 |
| optimized / webrtc / livekitPush | 113.70 | 1156.32 | 13.28 | 126.62 | 115.49 | 39.67 |

## Subscription and decoder

Median milliseconds; missing observations are —. Counts and ranges are retained in JSON.

| Profile / path | Subscription → track | Track → decoder | Microphone publication | Capture after authority | Playback from Answer | Decoder from Answer |
| --- | --- | --- | --- | --- | --- | --- |
| baseline / livekit / fetcher | 27.37 | 785.53 | 13.03 | 67.42 | 186.46 | 965.08 |
| baseline / livekit / push | 23.51 | 878.56 | 9.59 | 64.91 | 173.26 | 1038.99 |
| baseline / webrtc / fetcher | 16.60 | 30.58 | — | 9.22 | 173.61 | -146.76 |
| baseline / webrtc / livekitPush | 16.01 | 31.64 | — | 7.77 | 160.81 | -143.37 |
| optimized / livekit / fetcher | 730.08 | 1158.70 | 95.04 | 52.74 | -1296.15 | -158.51 |
| optimized / livekit / push | 807.12 | 1005.47 | 114.53 | 57.09 | -1127.48 | -176.74 |
| optimized / webrtc / fetcher | 39.29 | 55.30 | — | 54.51 | -229.40 | -165.06 |
| optimized / webrtc / livekitPush | 29.51 | 51.86 | — | 58.90 | -224.85 | -168.99 |

LiveKit's optimized subscription request begins with `connect(autoSubscribe=true)`; the baseline requests subscription after Answer. The optimized request→track interval therefore also includes room setup. It is not a pure SFU subscription duration. Decoder readiness means the first nonzero decoded PCM observation; codec initialization is not exposed separately.

## First observed transport readiness

Transport timestamps are first-observed states, not exact handshake completion. Stats polling is 25 ms and the stats cache may be 50 ms; retained per-observation poll/cache and request-to-callback statistics describe additional uncertainty. Callbacks and stats observations are counted separately. Intervals overlap and must not be summed. Missing observations remain null with n=0.

Offsets are median milliseconds. Negative offsets precede Answer. Request→callback includes stats work and scheduling; it is not handshake time.

| Profile / path | Role / state | From push | From Answer | Stats / callback n | Poll / cache | Request → callback |
| --- | --- | --- | --- | --- | --- | --- |
| baseline / livekit / fetcher | publisher / iceConnected | 1658.06 | -70.42 | 5 / 0 | 25.00 / 50.00 | 0.88 |
| baseline / livekit / fetcher | publisher / dtlsConnected | 1703.99 | -17.61 | 5 / 0 | 25.00 / 50.00 | 0.74 |
| baseline / livekit / fetcher | subscriber / iceConnected | 1647.73 | -71.29 | 5 / 0 | 25.00 / 50.00 | 17.80 |
| baseline / livekit / fetcher | subscriber / dtlsConnected | 2649.03 | 930.70 | 5 / 0 | 25.00 / 50.00 | 24.44 |
| baseline / livekit / push | publisher / iceConnected | 1056.83 | -73.24 | 5 / 0 | 25.00 / 50.00 | 1.13 |
| baseline / livekit / push | publisher / dtlsConnected | 1056.83 | -54.91 | 5 / 0 | 25.00 / 50.00 | 0.90 |
| baseline / livekit / push | subscriber / iceConnected | 1038.86 | -97.19 | 5 / 0 | 25.00 / 50.00 | 10.40 |
| baseline / livekit / push | subscriber / dtlsConnected | 2117.49 | 984.02 | 5 / 0 | 25.00 / 50.00 | 2.17 |
| baseline / webrtc / fetcher | raw / iceConnected | 1204.48 | -172.45 | 4 / 1 | 25.00 / 50.00 | 0.90 |
| baseline / webrtc / fetcher | raw / dtlsConnected | 1259.54 | -118.73 | 5 / 0 | 25.00 / 50.00 | 1.26 |
| baseline / webrtc / livekitPush | raw / iceConnected | 1051.76 | -172.80 | 4 / 1 | 25.00 / 50.00 | 0.85 |
| baseline / webrtc / livekitPush | raw / dtlsConnected | 1104.75 | -118.48 | 5 / 0 | 25.00 / 50.00 | 1.03 |
| optimized / livekit / fetcher | publisher / iceConnected | 1709.58 | -1293.63 | 5 / 0 | 25.00 / 50.00 | 4.85 |
| optimized / livekit / fetcher | publisher / dtlsConnected | 1742.21 | -1275.39 | 5 / 0 | 25.00 / 50.00 | 4.52 |
| optimized / livekit / fetcher | subscriber / iceConnected | 1676.41 | -1294.62 | 5 / 0 | 25.00 / 50.00 | 16.71 |
| optimized / livekit / fetcher | subscriber / dtlsConnected | 2734.90 | -178.57 | 5 / 0 | 25.00 / 50.00 | 1.39 |
| optimized / livekit / push | publisher / iceConnected | 1128.56 | -1125.51 | 5 / 0 | 25.00 / 50.00 | 8.21 |
| optimized / livekit / push | publisher / dtlsConnected | 1128.56 | -1125.51 | 5 / 0 | 25.00 / 50.00 | 8.21 |
| optimized / livekit / push | subscriber / iceConnected | 1044.37 | -1201.81 | 5 / 0 | 25.00 / 50.00 | 60.95 |
| optimized / livekit / push | subscriber / dtlsConnected | 2032.69 | -161.47 | 5 / 0 | 25.00 / 50.00 | 1.77 |
| optimized / webrtc / fetcher | raw / iceConnected | 1069.89 | -215.70 | 3 / 2 | 25.00 / 50.00 | 11.06 |
| optimized / webrtc / fetcher | raw / dtlsConnected | 1122.87 | -167.04 | 5 / 0 | 25.00 / 50.00 | 0.89 |
| optimized / webrtc / livekitPush | raw / iceConnected | 1099.32 | -215.68 | 4 / 1 | 25.00 / 50.00 | 2.15 |
| optimized / webrtc / livekitPush | raw / dtlsConnected | 1146.07 | -162.90 | 5 / 0 | 25.00 / 50.00 | 0.97 |

## Authority and interpretation limits

Endpoint-absent counts at authorization (out of five):

- baseline: livekit/fetcher 0; livekit/push 0; webrtc/fetcher 1; webrtc/livekitPush 0.
- optimized: livekit/fetcher 0; livekit/push 0; webrtc/fetcher 0; webrtc/livekitPush 0.

All retained samples require one independently observed ACTIVE platform call, the same live Core-active call, and communication audio mode. An endpoint callback is descriptive rather than an additional activation acknowledgment. First eligible PCM means decoded PCM16 peak ≥128 after the current answer gate; it does not prove acoustic output. The baseline capture timestamp is an SDK thread-start callback; the optimized capture timestamp is a positive read by the session's ADM. Playback timestamps are thread callbacks. These observations do not mark exact recording/playback API completion. The earliest endpoint callback may precede Answer; its offset is not the authority wait.

These five-sample path summaries do not establish production percentiles or normalize host performance. Historical Stop2 values (including 617–648 ms) and failed repeated-process bursts use different regimes; they cannot establish a causal before/after improvement against this baseline. OS process launch, FCM delivery and acoustic output remain outside this measurement. Overlapping phase intervals must not be added.

Both profiles pass their full native/hold/cleanup and all 12 DND journeys. The optimized suite records 31 native passes, three expected test selections skipped and zero failures; none of the 20 timing attempts is skipped. Separate [baseline release Expo](https://github.com/relayphone/expo-callkit-telecom/actions/runs/40445076117) and [optimized release Expo](https://github.com/relayphone/expo-callkit-telecom/actions/runs/40447020485) journeys pass. Timing comes from the instrumentation harness using the production adapters; the release journey separately verifies the Expo bridge.

## Separate merged-source verification

The [merged `v2` Android run](https://github.com/relayphone/expo-callkit-telecom/actions/runs/40448095070) on [8c6a794](https://github.com/relayphone/expo-callkit-telecom/commit/8c6a79471dd339e58760704586cbd32247cfb4d6) repeats all 20 optimized attempts with runtime inputs identical to the primary optimized commit. All remain below 300 ms: LiveKit 150.30–195.06 ms; WebRTC 157.24–251.31 ms. The native suite again records 31 passes, three expected selections skipped and zero failures, with all DND/cleanup checks passing. This validation run is separate from the five-per-path comparison above; its values are not pooled into that comparison or the JSON. Across both optimized runs, all 40 measured answers meet the target. The lower observed minimum and wider range reinforce that these are run observations rather than a fixed emulator floor.
