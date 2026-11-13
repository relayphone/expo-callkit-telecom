import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { test } from "node:test";

// SDK PR293 createMarker("marker-parity:fromDevice"); keep its complete tones-v1 identity.
const frequencies = [650, 950, 1350, 1850, 950, 1350, 1850, 1850, 650, 950,
  950, 950, 1850, 650, 950, 650, 1350, 1850, 650, 650];
const pcm = Array.from({ length: 19_200 }, (_, position) => Math.round(32767 * Math.fround(
  0.2 * Math.sin(2 * Math.PI * frequencies[Math.floor(position / 960)]! * position / 48_000))));
const receiver = `
import asyncio, importlib.util, json, sys
spec = importlib.util.spec_from_file_location("fixture", "tools/native-media-fixture.py")
fixture = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fixture)
async def check():
    pcm = json.load(sys.stdin)
    receiver = fixture.MarkerObservation("marker-parity:fromDevice")
    receiver.pcm = pcm[:19199]
    partial = (await receiver.measure())["markers"]
    receiver.pcm = pcm[:]
    full = dict(await receiver.measure())
    receiver.pcm += [0] * 480 + pcm
    tail = dict(await receiver.measure())
    wrong = fixture.MarkerObservation("different-identity")
    wrong_id = wrong.analyze(pcm, wrong.frequencies)["markers"]
    damaged = [0 if 3840 <= i < 4800 else value for i, value in enumerate(pcm)]
    missing = receiver.analyze(damaged, receiver.frequencies)["markers"]
    zero = receiver.analyze([0] * len(pcm), receiver.frequencies)["markers"]
    receiver.reset()
    reset = (await receiver.measure())["markers"]
    return dict(partial=partial, full=full, tail=tail, wrongId=wrong_id, missing=missing, zero=zero, reset=reset)
print(json.dumps(asyncio.run(check())))
`;

test("the real fixture requires complete tones-v1 slots, detects short leaks and measures the final tail", () => {
  const result = JSON.parse(execFileSync(process.env.NATIVE_MEDIA_PYTHON ?? "python3", ["-c", receiver], {
    input: JSON.stringify(pcm), encoding: "utf8", timeout: 30_000,
  }));
  assert.equal(result.full.markers, 1);
  assert.equal(result.full.slots.length, 20);
  assert.ok(result.full.minimumConfidence > 0.9999);
  assert.ok(result.full.slots.every((slot: { margin: number }) => slot.margin > 0.1999));
  assert.equal(result.tail.markers, 2);
  for (const control of ["partial", "wrongId", "missing", "zero", "reset"])
    assert.equal(result[control], 0, control);
});
