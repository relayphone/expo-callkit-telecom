import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import {
  mkdtempSync,
  mkdirSync,
  readFileSync,
  rmSync,
  writeFileSync,
} from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { test } from "node:test";
import { crc32 } from "node:zlib";
import { emissionVector, markerFrequencies, repeatedVector } from "./fixtures/media-marker-vectors.ts";

import {
  compareMediaApm,
  summarizeMediaArtifacts,
} from "./compare-media-apm.ts";

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

test("the Android comparator uses the complete-path detector and rejects repeated-run controls", () => {
  for (const variant of ["deleted", "wrong", "silent", "alias"] as const) {
    const {id, pcm} = repeatedVector(48000, variant);
    const original = emissionVector(48000, markerFrequencies(id));
    const before = Buffer.alloc(pcm.length * 2), after = Buffer.alloc(pcm.length * 4);
    pcm.forEach((value, index) => {
      before.writeInt16LE(Math.round(original[index]! * 32767), index * 2);
      after.writeFloatLE(Math.round(value * 32767), index * 4);
    });
    const value = {...capture(), markerId: id, frames: pcm.length, sourcePcm: before.toString("base64"),
      postPcm: after.toString("base64"), blocks: Array.from({length: pcm.length / 480}, (_, i) =>
        [i * 480, i * 480, i * 10_000_000, i * 10_000_000 + 1, 7])};
    const result = compareMediaApm(trace(value));
    assert.equal(result.sourceAnalysis.markers, 1);
    assert.equal(result.postAnalysis.markers, variant === "deleted" ? 1 : 0);
    if (variant === "deleted") {
      const cycle = result.postAnalysis.detections[0]!;
      assert.deepEqual(cycle.slots.map(slot => slot.driftMs), Array(12).fill(0));
    }
  }
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

function artifactFixture(
  t: { after: (cleanup: () => void) => void },
  removed: number[] = []
) {
  const directory = mkdtempSync(join(tmpdir(), "media-artifact-"));
  t.after(() => rmSync(directory, { recursive: true, force: true }));
  const postApm = capture(removed);
  const write = (name: string, value: unknown) =>
    writeFileSync(join(directory, name), JSON.stringify(value));
  const traceValue = {
    postApm,
    overflow: false,
    callbacks: postApm.blocks.map((block, i) => [
      "answered",
      block[2],
      block[3],
      block[0],
      480,
      48000,
      960,
      false,
      crc32(source.subarray(i * 960, (i + 1) * 960)),
      7,
    ]),
    apmStates: [
      {
        atNs: 0,
        mode: 3,
        aec: false,
        agc: true,
        ns: false,
        trackEnabled: true,
        publicationMuted: false,
      },
    ],
  };
  const phase = {
    markerId: "parity-v2",
    markers: 0,
    detections: [],
    cleanCyclesWithPostRoll: 0,
    firstCleanCycle: null,
    cycles: [
      {
        offsetSamples: 7200,
        endSamples: 41760,
        slots: [
          {
            index: 0,
            confidence: 0,
            margin: -0.8,
            startSamples: 7200,
            driftMs: 0,
          },
        ],
        receiveStats: {
          beforeFrame: 7000,
          afterFrame: 42000,
          status: "impaired",
          delta: {
            packets_lost: 0,
            concealed_samples: 480,
            inserted_samples_for_deceleration: 0,
            removed_samples_for_acceleration: 0,
          },
          positiveIncreases: { concealed_samples: 480, packets_lost: 1 },
        },
      },
    ],
  };
  write("capture-trace.json", traceValue);
  write("media-hook-results.json", {
    passed: false,
    step: "answer",
    pipeline: "AEC=false",
    fromDevice: phase,
  });
  mkdirSync(join(directory, "epoch-3"));
  write("epoch-3/cycles.json", phase);
  return { directory, traceValue, phase, write };
}

test("artifact summary preserves failed gates, impaired candidates and actual shared-detector results", (t) => {
  const { directory, phase } = artifactFixture(t, [1, 5, 6]);
  const result = summarizeMediaArtifacts(directory);
  assert.deepEqual(result.journey, {
    passed: false,
    stoppedAt: "answer",
    pipeline: "AEC=false",
  });
  assert.equal(result.capture.pairedSourceCrcMismatches, 0);
  assert.equal(result.capture.answeredCallbacks, 300);
  assert.equal(result.capture.maxAnsweredCallbackGapMs, 10);
  assert.equal(result.processing.error, null);
  assert.ok(result.processing.source!.markers >= 2);
  assert.equal(result.processing.post!.markers, 0);
  assert.equal(result.phases[0]!.firstCleanCycle, null);
  assert.deepEqual(
    result.phases[0]!.cycles[0]!.receiveStats,
    phase.cycles[0]!.receiveStats
  );
  assert.equal(result.phases[0]!.cycles[0]!.minimumConfidence, 0);
  assert.equal(result.phases[0]!.cycles[0]!.finalDriftMs, 0);
  assert.equal(result.snapshots.fromDevice!.markers, 0);
  assert.equal(JSON.stringify(result).includes("sourcePcm"), false);
  assert.equal(JSON.stringify(result).includes("postPcm"), false);
});

test("artifact summary retains separate epochs when failure cleanup replaces the source snapshot", (t) => {
  const { directory, write, phase } = artifactFixture(t);
  write("media-hook-results.json", {
    passed: false,
    step: "held output",
    pipeline: "AEC=false",
    fromDevice: { ...phase, markers: 1 },
  });
  const result = summarizeMediaArtifacts(directory);
  assert.equal(result.snapshots.fromDevice!.markers, 1);
  assert.equal(result.phases[0]!.markers, 0);
  assert.equal(result.phases[0]!.file, join("epoch-3", "cycles.json"));
});

test("artifact summary reports pairing errors and mismatched source CRCs without marking a gate passed", (t) => {
  const { directory, traceValue, write } = artifactFixture(t);
  traceValue.postApm.error = "THREAD_MISMATCH";
  traceValue.callbacks[0]![8] = -1;
  write("capture-trace.json", traceValue);
  const result = summarizeMediaArtifacts(directory);
  assert.match(result.processing.error!, /Post-APM frame pairing failed/);
  assert.equal(result.capture.pairedSourceCrcMismatches, 1);
  assert.equal(result.journey.passed, false);
});

test("artifact-summary CLI writes its report and refuses ambiguous captures", (t) => {
  const { directory, write } = artifactFixture(t);
  const report = join(directory, "summary.json");
  const output = execFileSync(
    process.execPath,
    ["tools/compare-media-apm.ts", "--artifact-summary", directory, report],
    { encoding: "utf8", timeout: 15_000, killSignal: "SIGKILL" }
  );
  assert.equal(JSON.parse(output).passed, false);
  assert.equal(
    JSON.parse(readFileSync(report, "utf8")).processing.post.markers >= 2,
    true
  );
  write("epoch-3/capture-trace.json", {});
  assert.throws(
    () => summarizeMediaArtifacts(directory),
    /Need exactly one capture-trace.json/
  );
});
