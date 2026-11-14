import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { test } from "node:test";

import { compareMediaApm } from "./compare-media-apm.ts";

// Generate the real peer's canonical source rather than an approximation of its envelope.
const source = execFileSync(
  process.env.NATIVE_MEDIA_PYTHON ?? "python3",
  [
    "-c",
    `
import importlib.util, sys
spec = importlib.util.spec_from_file_location("fixture", "tools/native-media-fixture.py")
fixture = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fixture)
sys.stdout.buffer.write(fixture.TonesV2Signal("parity-v2", 48000).pcm(144000))
`,
  ],
  { timeout: 15_000, killSignal: "SIGKILL", maxBuffer: 1024 * 1024 }
);

function capture(removed: number[] = []) {
  const post = Buffer.alloc(144000 * 4);
  for (let i = 0; i < 144000; i++) {
    const position = (i % 48960) - 7200;
    const muted =
      position >= 0 &&
      position < 34560 &&
      removed.includes(Math.floor(position / 2880));
    post.writeFloatLE(muted ? 0 : source.readInt16LE(i * 2), i * 4);
  }
  return {
    markerId: "parity-v2",
    sampleRate: 48000,
    channels: 1,
    firstSourceFrame: 0,
    frames: 144000,
    error: null as string | null,
    sourceEncoding: "pcm16le",
    postEncoding: "float32le-s16-scale",
    sourcePcm: source.toString("base64"),
    postPcm: post.toString("base64"),
    blocks: Array.from({ length: 300 }, (_, i) => [
      i * 480,
      i * 480,
      i * 10_000_000,
      i * 10_000_000 + 1,
      7,
    ]),
  };
}
const trace = (postApm: ReturnType<typeof capture>) =>
  JSON.stringify({ postApm });

test("the shared detector preserves complete post-APM markers and same-frame slot energy", () => {
  const result = compareMediaApm(trace(capture()));
  assert.ok(result.sourceAnalysis.markers >= 2);
  assert.equal(result.postAnalysis.markers, result.sourceAnalysis.markers);
  assert.ok(
    result.sourceCycles.every(
      (cycle) =>
        cycle.slots.length === 12 &&
        cycle.slots.every(
          (slot) => slot.sourceEnergy > 0.01 && slot.energyRatio === 1
        )
    )
  );
});

test("removed slot energy fails the actual detector without changing source observations", () => {
  const result = compareMediaApm(trace(capture([1, 5, 6])));
  assert.ok(result.sourceAnalysis.markers >= 2);
  assert.equal(result.postAnalysis.markers, 0);
  assert.ok(
    result.sourceCycles.every((cycle) =>
      cycle.slots
        .filter((slot) => [1, 5, 6].includes(slot.index))
        .every(
          (slot) =>
            slot.sourceEnergy > 0.01 &&
            slot.postEnergy === 0 &&
            slot.energyRatio === 0
        )
    )
  );
});

test("unpaired, truncated, nonfinite and noncontiguous observations fail closed", () => {
  for (const kind of ["unpaired", "truncated", "nonfinite", "gap", "format"]) {
    const value = capture();
    if (kind === "unpaired") value.error = "THREAD_MISMATCH";
    if (kind === "truncated") value.postPcm = "";
    if (kind === "gap") value.blocks[10]![0] = 0;
    if (kind === "format") value.postEncoding = "pcm16le";
    if (kind === "nonfinite") {
      const post = Buffer.from(value.postPcm, "base64");
      post.writeFloatLE(NaN, 0);
      value.postPcm = post.toString("base64");
    }
    assert.throws(() => compareMediaApm(trace(value)), kind);
  }
});
