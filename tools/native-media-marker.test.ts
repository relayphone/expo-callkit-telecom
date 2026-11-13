import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { execFileSync } from "node:child_process";
import { test } from "node:test";

// SDK createMarker("parity-v2"); twelve tones-v2 slots, phase reset and raised-cosine edges.
const frequencies = [650, 950, 1350, 1850, 1850, 1850, 950, 1350, 650, 650, 1850, 1350];
function samples(rate: number) {
  const slot = rate / 50 * 3;
  return Array.from({ length: slot * 12 }, (_, position) => {
    const local = position % slot;
    const fade = Math.min(1, Math.min(local, slot - local) / (rate / 200));
    const envelope = 0.5 * (1 - Math.cos(Math.PI * fade));
    return Math.round(32767 * Math.fround(0.2 * envelope *
      Math.sin(2 * Math.PI * frequencies[Math.floor(position / slot)]! * local / rate)));
  });
}
const pcm = samples(48_000);
const receiver = `
import array, asyncio, hashlib, importlib.util, json, math, random, sys, types
spec = importlib.util.spec_from_file_location("fixture", "tools/native-media-fixture.py")
fixture = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fixture)
async def check():
    pcm = json.load(sys.stdin)
    hashes = {str(rate): {
        kind: hashlib.sha256(fixture.TonesV2Signal("parity-v2", rate).pcm(
            rate * (1020 if emission else 720) // 1000, emission=emission)).hexdigest()
        for kind, emission in [("marker", False), ("emission", True)]}
        for rate in [8000, 48000]}
    receiver = fixture.MarkerObservation("parity-v2")
    receiver.pcm = pcm[:34559]
    receiver.frames = len(receiver.pcm)
    partial = (await receiver.measure())["markers"]
    receiver.pcm = pcm[:]
    receiver.frames = len(receiver.pcm)
    full = dict(await receiver.measure())
    receiver.pcm += [0] * 480 + pcm
    receiver.frames = len(receiver.pcm)
    tail = dict(await receiver.measure())
    receiver.pcm += [0] * 9600
    receiver.frames = len(receiver.pcm)
    post_roll = dict(await receiver.measure())
    truncated = fixture.MarkerObservation("parity-v2")
    truncated.pcm = list(array.array("h", fixture.TonesV2Signal("parity-v2", 48000).pcm(48480)))
    truncated.frames = len(truncated.pcm)
    truncated_coverage = (await truncated.measure())["markersWithPostRoll"]
    wrong = fixture.MarkerObservation("different-identity")
    wrong_id = wrong.analyze(pcm, wrong.frequencies)["markers"]
    damaged = [0 if 11520 <= i < 14400 else value for i, value in enumerate(pcm)]
    missing = receiver.analyze(damaged, receiver.frequencies)["markers"]
    central = [value if 960 <= i % 2880 < 1920 else 0 for i, value in enumerate(pcm)]
    central_only = receiver.analyze(central, receiver.frequencies)["markers"]
    zero = receiver.analyze([0] * len(pcm), receiver.frequencies)["markers"]
    # Synthetic receive timing changes preserve pitch; the emitted source stays canonical.
    def timing_capture(factor, central=False):
        length = round(2880 * factor)
        values = [0] * 7200
        for frequency in receiver.frequencies:
            for local in range(length):
                fade = min(1, min(local, length - local) / 240)
                value = .2 * .5 * (1 - math.cos(math.pi * fade)) * math.sin(2 * math.pi * frequency * local / 48000)
                values.append(math.floor(value * 32767 + .5) if not central or 960 <= local < 1920 else 0)
        return values + [0] * 9600
    elastic = {str(factor): receiver.analyze(timing_capture(factor), receiver.frequencies)
               for factor in [.96, 1.04, 1.08, 1.3]}
    cap_rejected = receiver.analyze(timing_capture(1.12, central=True), receiver.frequencies)
    aligned = fixture.MarkerObservation("parity-v2")
    aligned.pcm = timing_capture(1.08)
    aligned.frames = len(aligned.pcm)
    def stats(frame, inserted, removed):
        return {"frame": frame, "inbound": [{"inserted_samples_for_deceleration": inserted,
            "removed_samples_for_acceleration": removed, "concealed_samples": 5, "packets_lost": 0}]}
    aligned.stats = [stats(0, 100, 20), stats(aligned.frames, 157, 29)]
    aligned_receipt = dict(await aligned.measure())
    noise = random.Random(42)
    noise_rejected = receiver.analyze([noise.randrange(-6553, 6554) for _ in pcm], receiver.frequencies)["markers"]
    tone_rejected = receiver.analyze([round(6553 * math.sin(2 * math.pi * 400 * i / 48000)) for i in range(len(pcm))], receiver.frequencies)["markers"]
    receiver.reset()
    reset = (await receiver.measure())["markers"]
    late = fixture.MarkerObservation("parity-v2")
    data = pcm + [0] * (192000 - len(pcm)) + pcm + [0] * 9600
    first = None
    class Stream:
        async def __aiter__(self):
            nonlocal first
            for start in range(0, len(data), 960):
                yield types.SimpleNamespace(frame=types.SimpleNamespace(sample_rate=48000,
                    num_channels=1, data=array.array("h", data[start:start + 960])))
                if start + 960 == 34560:
                    first = (await late.measure())["markers"]
        async def aclose(self):
            pass
    original = fixture.rtc.AudioStream
    try:
        fixture.rtc.AudioStream = lambda *args, **kwargs: Stream()
        await late.receive(None)
    finally:
        fixture.rtc.AudioStream = original
    late_markers = (await late.measure())["markers"]
    late.pcm = [0] * 144000
    late.frames += 144000
    remembered = (await late.measure())["markers"]
    result = dict(hashes=hashes, partial=partial, full=full, tail=tail, postRoll=post_roll,
                truncatedCoverage=truncated_coverage, first=first, centralOnly=central_only,
                wrongId=wrong_id, missing=missing, zero=zero, reset=reset,
                lateMarkers=late_markers, retained=len(late.pcm), remembered=remembered,
                elastic=elastic, capRejected=cap_rejected, alignedReceipt=aligned_receipt,
                noiseRejected=noise_rejected, toneRejected=tone_rejected)
    await fixture.analysis_worker.close()
    now = 0.0
    class Source:
        async def capture_frame(self, frame):
            nonlocal now
            now += .003
    async def pause(seconds):
        nonlocal now
        now += seconds
    await fixture.publish_frames(Source(), fixture.TonesV2Signal("parity-v2", 48000),
                                 clock=lambda: now, pause=pause, limit=100)
    result["publisherElapsed"] = now
    return result
print(json.dumps(asyncio.run(check())))
`;

test("the real fixture requires complete tones-v2 slots, detects short leaks and measures the final tail", () => {
  const result = JSON.parse(execFileSync(process.env.NATIVE_MEDIA_PYTHON ?? "python3", ["-c", receiver], {
    input: JSON.stringify(pcm), encoding: "utf8", timeout: 30_000, killSignal: "SIGKILL",
  }));
  assert.equal(result.full.markers, 1);
  assert.equal(result.full.profile, "tones-v2");
  assert.equal(result.full.slots.length, 12);
  assert.ok(result.full.minimumConfidence > 0.9999);
  assert.ok(result.full.slots.every((slot: { margin: number }) => slot.margin > 0.1999));
  assert.equal(result.tail.markers, 2);
  assert.equal(result.full.markersWithPostRoll, 0);
  assert.equal(result.postRoll.markersWithPostRoll, 2);
  assert.equal(result.truncatedCoverage, 0, "detector start uncertainty cannot hide a truncated post-roll");
  assert.equal(result.first, 1);
  assert.deepEqual(result.hashes, {
    "8000": {marker: "7305be4e1d4ba73d2f2f876869fa5cb27c12bb91450081452139f41262f7c003",
      emission: "e339576ee90e3d72ef65c7a5e44ab4a74345be472fdcd39b8f25072448a5fbab"},
    "48000": {marker: "82f79efb9f1b24a5c839cd500f5c9ad291ef5969348c3d68b775fcef6f8fa3b4",
      emission: "0a79ca1399b4fc018584bcf8e90be7c476618fab47f903b9670ce1282eafbaa1"},
  });
  assert.equal(result.centralOnly, 1);
  assert.equal(result.lateMarkers, 2, "late markers must stay observable during the existing five-second phase");
  assert.equal(result.retained, 144_000, "the capture remains capped at three seconds");
  assert.ok(Math.abs(result.publisherElapsed - 1) < 1e-9, "100 ten-millisecond frames must not accrue callback work time");
  for (const factor of ["0.96", "1.04", "1.08"]) {
    const detection = result.elastic[factor];
    assert.equal(detection.markers, 1, factor);
    assert.equal(detection.detections[0].slots.length, 12);
    assert.ok(detection.slots.every((slot: { confidence: number; driftMs: number }) =>
      slot.confidence >= .8 && Math.abs(slot.driftMs) <= 60));
  }
  assert.ok(result.elastic["1.08"].slots.some((slot: { driftMs: number }) => slot.driftMs !== 0));
  assert.equal(result.elastic["1.3"].markers, 0, "cumulative drift outside the cap must fail");
  assert.equal(result.capRejected.markers, 0);
  assert.ok(result.capRejected.driftCapRejected.minimumConfidence >= .8,
    "the cap control must clear every tone score and fail specifically on cumulative drift");
  assert.ok(Math.abs(result.capRejected.driftCapRejected.slots[11].driftMs) > 60);
  assert.equal(result.alignedReceipt.markersWithPostRoll, 1);
  assert.deepEqual(result.alignedReceipt.phaseDetections[0].receiveStats.delta, {
    inserted_samples_for_deceleration: 57, removed_samples_for_acceleration: 9,
    concealed_samples: 0, packets_lost: 0,
  });
  assert.equal(result.noiseRejected, 0);
  assert.equal(result.toneRejected, 0);
  assert.equal(result.remembered, 2, "a leak cannot disappear when older PCM rolls out");
  for (const control of ["partial", "wrongId", "missing", "zero", "reset"])
    assert.equal(result[control], 0, control);
});

test("both PCM16 little-endian parity vectors match the SDK", () => {
  for (const [rate, expected] of [
    [8000, "7305be4e1d4ba73d2f2f876869fa5cb27c12bb91450081452139f41262f7c003"],
    [48000, "82f79efb9f1b24a5c839cd500f5c9ad291ef5969348c3d68b775fcef6f8fa3b4"],
  ] as const) {
    const pcm = samples(rate);
    const bytes = Buffer.alloc(pcm.length * 2);
    pcm.forEach((value, index) => bytes.writeInt16LE(value, index * 2));
    assert.equal(createHash("sha256").update(bytes).digest("hex"), expected);
  }
});
