# Android emulator media qualification

The instrumentation app has a dedicated `emulatorTest` build with synthetic
capture and real receive observation. This is an internal software-audio harness.
It verifies that audio flows each way, that mute, hold and reconnect gate it, and
software timing. It does not grade the noise suppressor. NS fidelity belongs to
device and speech QA. It does not measure physical microphones, speakers,
Bluetooth or acoustic latency.

The concrete implementation lives in `src/emulatorTest`; normal device and
Release artifacts exclude it. Activation also requires the emulator-test build
flag, a debuggable application and emulator hardware. The Release isolation test
requires activation to refuse at runtime.

Capture uses `LocalAudioTrack.setAudioBufferCallback`, composed with the existing
ADM customizer and `NativeWarmAudio` pacing. The emulator-test ADM captures at
48 kHz. Synthetic capture disables NS through the SDK's track capture options;
AEC, AGC, high-pass filtering and Opus remain enabled. Callback buffer
CRCs, callback timing, publication mute state, audio mode and processing options
are retained. The callback CRC observes bytes at callback return. A read-only
`AudioOptions.audioProcessorOptions.capturePostProcessor` copies the first three
seconds of answered capture after APM. It pairs source and post-APM processing
blocks by thread, source frame and format; unsupported or missing pairs are
reported explicitly. It observes full-band mono Float32 PCM at 48 kHz with S16
scaling, without modifying native buffers or their cursors. This is not an
encoder-input tap.

The source and post-APM PCM are retained inside `capture-trace.json`. Compare
them offline using the same detector as both live directions:

```sh
node tools/compare-media-apm.ts capture-trace.json comparison.json
```

The comparison retains each stream's complete detector result and per-slot
energy at identical source-frame offsets. These energy windows include DSP
delay; they do not apply a corrective shift or measure acoustic latency.
Set `NATIVE_MEDIA_PYTHON` to the interpreter with the pinned fixture requirements
when it is not `python3`. The initial observation with NS, AEC and AGC enabled
found intact source markers and no complete post-APM markers. The NS-off probe
changes only synthetic capture in the emulator-test configuration; device and
Release media settings retain their defaults. The read-only tap stays enabled
to distinguish processing failures from later codec or receive failures.

Decoded audio is observed through remote sinks. Post-mute output uses the actual
playback tap, with the pinned direct buffer's array access verified before use.
An absent, disabled, stale or unsupported tap fails. Held output must deliver
fresh zero PCM while decoded audio continues. Source ZERO/WRONG controls and
muted publication reject every raw detected marker, including impaired markers.

## Marker and transport rules

Both directions use the Python peer's shared tones-v2 detector. The Android
output tap sends contiguous PCM and subscriber counter snapshots to that
detector over the authenticated local fixture endpoint. Its phase resets are
independent of the far-end capture observer.

Sources retain the canonical 720 ms marker, 150 ms 400 Hz pre-roll and post-roll,
and all four source parity hashes. Detection scores each slot's central 20 ms.
Later slot starts search within ±15 ms of the prior chosen start plus 60 ms;
final cumulative drift is capped at ±60 ms. All twelve slots must score at least
0.8. The sync anchor is fixed before later slots are evaluated, so a failed cap
cannot slide the anchor to manufacture a pass.

An audio phase passes on its first complete **clean** cycle, with post-roll
observed, within the existing five-second deadline. A cycle whose counter
bracket shows loss or concealment is impaired and observation continues with
the next cycle. Missing or negative counter deltas are unknown and cannot pass.
A sampled loss spike remains impaired after late packet recovery reduces the
net loss counter. Insertion/removal alone is recorded and handled by alignment.

Every candidate retains its per-slot scores, margins and drifts, sync/cap result,
and bracketing loss, concealment and inserted/removed sample counters. Candidate
windows with incomplete sync are labeled explicitly. Counter brackets include
polling uncertainty; they conservatively cover the aligned marker span. They
are not exact packet-to-sample or cross-clock latency measurements.

The six SDK amendment 2a input hashes and drift lists are host-tested at 8 kHz
and 48 kHz. They insert or delete 10 ms at slot +45 ms, with 2.5 ms in-place
crossfades. Four edits in slots 2/5/8/10 give ±40 ms and must pass. Eight
insertions give +80 ms and must fail specifically on the drift cap.

## CI and evidence

The `media-feasibility` job uses standard Ubuntu with KVM, a two-core/2 GB
emulator and the merged guest-budget guard. It runs separately from the ordinary
functional journeys, which retain their existing checks. Local emulator results
are debugging data on a shared host; qualification comes from CI.

```sh
node tools/run-native-media-journey.ts --feasibility-only
```

The `media-feasibility-results` artifact contains native test XML,
`media-hook-results.json`, `capture-trace.json`, the sanitized fixture log and
frame-keyed PCM/analysis files for both directions. Each PCM file is the exact
snapshot analyzed, with its phase-frame bounds; a later rolling buffer cannot
replace it while the worker runs. The phase summary retains impaired cycles
even when a later clean cycle succeeds.

## Known emulator-default limitation

The emulator defaults to 8 kHz. The test build selects 48 kHz without changing
the signal. Historical local debugging produced these margins over 0.8 for its
best full 8 kHz candidate; host contention prevents using this table as a CI
qualification result. The weakest slot scored 0.765364.

| Slot | Confidence | Margin |
| --- | ---: | ---: |
| 0 | 0.860708 | +0.060708 |
| 1 | 0.765364 | -0.034636 |
| 2 | 0.801910 | +0.001910 |
| 3 | 0.945526 | +0.145526 |
| 4 | 0.991899 | +0.191899 |
| 5 | 0.803162 | +0.003162 |
| 6 | 0.999978 | +0.199978 |
| 7 | 0.999986 | +0.199986 |
| 8 | 0.997552 | +0.197552 |
| 9 | 0.990580 | +0.190580 |
| 10 | 0.801134 | +0.001134 |
| 11 | 0.971150 | +0.171150 |
