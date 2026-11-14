import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { readFileSync, writeFileSync } from "node:fs";

interface Capture {
  markerId: string;
  sampleRate: number;
  channels: number;
  firstSourceFrame: number;
  frames: number;
  error: string | null;
  sourceEncoding: string;
  postEncoding: string;
  sourcePcm: string;
  postPcm: string;
  blocks: number[][];
}
interface Slot {
  index: number;
  confidence: number;
  startSamples: number;
  driftMs: number;
}
interface Detection {
  offsetSamples: number;
  endSamples: number;
  slots: Slot[];
}
interface Analysis {
  markers: number;
  detections: Detection[];
  cycles: Detection[];
}
interface Pair {
  source: Analysis;
  post: Analysis;
}
export type Analyze = (
  markerId: string,
  source: number[],
  post: number[]
) => Pair;

// Invoke the exact detector used by both live directions; no PCM repair or quantization.
const sharedAnalyze: Analyze = (markerId, source, post) =>
  JSON.parse(
    execFileSync(
      process.env.NATIVE_MEDIA_PYTHON ?? "python3",
      [
        "-c",
        `
import importlib.util, json, sys
spec = importlib.util.spec_from_file_location("fixture", "tools/native-media-fixture.py")
fixture = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fixture)
data = json.load(sys.stdin)
frequencies = fixture.TonesV2Signal(data["markerId"], 48000).frequencies
print(json.dumps({name: fixture.MarkerObservation.analyze(data[name], frequencies)
                  for name in ["source", "post"]}))
`,
      ],
      {
        input: JSON.stringify({ markerId, source, post }),
        encoding: "utf8",
        timeout: 30_000,
        killSignal: "SIGKILL",
        maxBuffer: 2 * 1024 * 1024,
      }
    )
  ) as Pair;

/** Compare the same recorded processing blocks; their timestamps do not measure acoustic latency. */
export function compareMediaApm(
  trace: string,
  analyze: Analyze = sharedAnalyze
) {
  const capture = (JSON.parse(trace) as { postApm: Capture }).postApm;
  assert.ok(capture, "Missing post-APM observation");
  assert.equal(capture.error, null, "Post-APM frame pairing failed");
  assert.equal(capture.sampleRate, 48000);
  assert.equal(capture.channels, 1);
  assert.equal(capture.sourceEncoding, "pcm16le");
  assert.equal(capture.postEncoding, "float32le-s16-scale");
  assert.ok(
    typeof capture.markerId === "string" && capture.markerId.length > 0
  );
  assert.ok(
    Number.isSafeInteger(capture.firstSourceFrame) &&
      capture.firstSourceFrame >= 0
  );
  assert.ok(
    Number.isSafeInteger(capture.frames) &&
      capture.frames >= 34560 &&
      capture.frames <= 144000,
    "Need a complete marker window of paired PCM"
  );
  const before = Buffer.from(capture.sourcePcm, "base64"),
    after = Buffer.from(capture.postPcm, "base64");
  assert.equal(before.length, capture.frames * 2);
  assert.equal(after.length, capture.frames * 4);
  assert.equal(capture.blocks.length * 480, capture.frames);
  for (const [index, block] of capture.blocks.entries()) {
    assert.equal(block.length, 5);
    assert.ok(block.every(Number.isSafeInteger));
    assert.equal(block[0], capture.firstSourceFrame + index * 480);
    assert.equal(block[1], index * 480);
    assert.ok(block[2]! <= block[3]!);
    assert.ok(block[4]! > 0);
  }
  const source = Array.from({ length: capture.frames }, (_, i) =>
    before.readInt16LE(i * 2)
  );
  const post = Array.from({ length: capture.frames }, (_, i) =>
    after.readFloatLE(i * 4)
  );
  assert.ok(post.every(Number.isFinite), "Post-APM PCM must be finite");
  const result = analyze(capture.markerId, source, post);
  assert.ok(
    result.source.markers > 0,
    "Source observation must contain a complete canonical marker"
  );
  const energy = (pcm: number[], start: number) => {
    let sum = 0;
    for (let i = start; i < start + 960; i++) sum += (pcm[i]! / 32768) ** 2;
    return sum / 960;
  };
  return {
    markerId: capture.markerId,
    sampleRate: capture.sampleRate,
    pairedFrames: capture.frames,
    firstSourceFrame: capture.firstSourceFrame,
    sourceAnalysis: result.source,
    postAnalysis: result.post,
    // Identical processing-block offsets, including DSP delay; no alignment is applied to these energies.
    sourceCycles: result.source.detections.map((detection) => ({
      sourceFrame: capture.firstSourceFrame + detection.offsetSamples,
      slots: detection.slots.map((slot) => {
        const start = slot.startSamples + 960;
        assert.ok(start >= 0 && start + 960 <= capture.frames);
        const sourceEnergy = energy(source, start),
          postEnergy = energy(post, start);
        return {
          index: slot.index,
          sourceFrame: capture.firstSourceFrame + start,
          sourceEnergy,
          postEnergy,
          energyRatio: sourceEnergy === 0 ? null : postEnergy / sourceEnergy,
        };
      }),
    })),
  };
}

if (import.meta.main) {
  assert.equal(
    process.argv.length,
    4,
    "Usage: node tools/compare-media-apm.ts <capture-trace.json> <comparison.json>"
  );
  const comparison = compareMediaApm(readFileSync(process.argv[2]!, "utf8"));
  writeFileSync(process.argv[3]!, JSON.stringify(comparison, null, 2) + "\n");
  console.log(
    JSON.stringify({
      pairedFrames: comparison.pairedFrames,
      sourceMarkers: comparison.sourceAnalysis.markers,
      postApmMarkers: comparison.postAnalysis.markers,
    })
  );
}
