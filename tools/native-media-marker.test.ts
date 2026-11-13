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
import array, asyncio, hashlib, importlib.util, json, sys, types
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
    receiver.pcm += [0] * 7200
    receiver.frames = len(receiver.pcm)
    post_roll = dict(await receiver.measure())
    wrong = fixture.MarkerObservation("different-identity")
    wrong_id = wrong.analyze(pcm, wrong.frequencies)["markers"]
    damaged = [0 if 11520 <= i < 14400 else value for i, value in enumerate(pcm)]
    missing = receiver.analyze(damaged, receiver.frequencies)["markers"]
    central = [value if 960 <= i % 2880 < 1920 else 0 for i, value in enumerate(pcm)]
    central_only = receiver.analyze(central, receiver.frequencies)["markers"]
    zero = receiver.analyze([0] * len(pcm), receiver.frequencies)["markers"]
    receiver.reset()
    reset = (await receiver.measure())["markers"]
    late = fixture.MarkerObservation("parity-v2")
    data = pcm + [0] * (192000 - len(pcm)) + pcm + [0] * 7200
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
    return dict(hashes=hashes, partial=partial, full=full, tail=tail, postRoll=post_roll, first=first, centralOnly=central_only,
                wrongId=wrong_id, missing=missing, zero=zero, reset=reset,
                lateMarkers=late_markers, retained=len(late.pcm), remembered=remembered)
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
