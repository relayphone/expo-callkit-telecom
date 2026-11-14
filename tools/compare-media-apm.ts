import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { readFileSync, readdirSync, writeFileSync } from "node:fs";
import { basename, join, relative } from "node:path";
import { crc32 } from "node:zlib";

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
  receiveStats?: {
    beforeFrame: number;
    afterFrame: number;
    delta: Record<string, number | null>;
    status: "clean" | "impaired" | "unknown";
  };
}

interface PhaseReceipt extends Analysis {
  markerId: string;
  cleanCyclesWithPostRoll: number;
  firstCleanCycle: Detection | null;
}
type Callback = [
  phase: string,
  enteredNs: number,
  returnedNs: number,
  firstFrame: number,
  frames: number,
  rate: number,
  bytesRead: number,
  publicationMuted: boolean,
  pcmCrc32: number,
  thread: number
];
const snapshotNames = [
  "mutedPublication",
  "fromDevice",
  "toDevice",
  "ZERO",
  "WRONG",
] as const;
type SnapshotName = (typeof snapshotNames)[number];
interface CaptureTrace {
  postApm: Capture;
  overflow: boolean;
  callbacks: Callback[];
  apmStates: {
    atNs: number;
    mode: number;
    aec: boolean;
    agc: boolean;
    ns: boolean;
    trackEnabled: boolean;
    publicationMuted: boolean;
  }[];
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

/** Summarize downloaded evidence without reclassifying gates or retaining PCM in the report. */
export function summarizeMediaArtifacts(
  directory: string,
  analyze: Analyze = sharedAnalyze
) {
  const walk = (path: string): string[] =>
    readdirSync(path, { withFileTypes: true }).flatMap((entry) => {
      const child = join(path, entry.name);
      return entry.isDirectory() ? walk(child) : entry.isFile() ? [child] : [];
    });
  const files = walk(directory).sort();
  const unique = (name: string) => {
    const matches = files.filter((path) => basename(path) === name);
    assert.equal(matches.length, 1, `Need exactly one ${name}`);
    return readFileSync(matches[0]!, "utf8");
  };
  const traceText = unique("capture-trace.json");
  const trace = JSON.parse(traceText) as CaptureTrace;
  const journey = JSON.parse(unique("media-hook-results.json")) as {
    passed: boolean;
    step: string;
    pipeline: string;
  } & Partial<Record<SnapshotName, PhaseReceipt>>;
  assert.equal(typeof journey.passed, "boolean");
  assert.equal(typeof journey.step, "string");
  assert.ok(Array.isArray(trace.callbacks) && Array.isArray(trace.apmStates));
  const summarize = (analysis: Analysis) => ({
    markers: analysis.markers,
    detections: analysis.detections,
    cycles: analysis.cycles.map((cycle) => ({
      ...cycle,
      minimumConfidence: Math.min(
        ...cycle.slots.map((slot) => slot.confidence)
      ),
      finalDriftMs: cycle.slots.at(-1)?.driftMs ?? null,
    })),
  });
  const processing = (() => {
    try {
      const comparison = compareMediaApm(traceText, analyze);
      return {
        error: null,
        source: summarize(comparison.sourceAnalysis),
        post: summarize(comparison.postAnalysis),
        sourceCycles: comparison.sourceCycles,
      };
    } catch (error) {
      return { error: error instanceof Error ? error.message : String(error) };
    }
  })();
  const paired = trace.postApm;
  const source = Buffer.from(paired.sourcePcm, "base64");
  const callbacks = trace.callbacks.filter(
    (callback) => callback[0] === "answered"
  );
  const byFrame = new Map(callbacks.map((callback) => [callback[3], callback]));
  const pairedSourceCrcMismatches = paired.blocks.filter(
    (block, i) =>
      crc32(source.subarray(i * 960, (i + 1) * 960)) !==
      byFrame.get(block[0]!)?.[8]
  ).length;
  const phases = files
    .filter((path) => basename(path) === "cycles.json")
    .map((path) => {
      const phase = JSON.parse(readFileSync(path, "utf8")) as PhaseReceipt;
      return {
        file: relative(directory, path),
        markerId: phase.markerId,
        ...summarize(phase),
        cleanCyclesWithPostRoll: phase.cleanCyclesWithPostRoll,
        firstCleanCycle: phase.firstCleanCycle,
      };
    });
  const snapshots: Partial<
    Record<
      SnapshotName,
      ReturnType<typeof summarize> & { cleanCyclesWithPostRoll: number }
    >
  > = {};
  for (const name of snapshotNames) {
    const phase = journey[name];
    if (phase)
      snapshots[name] = {
        ...summarize(phase),
        cleanCyclesWithPostRoll: phase.cleanCyclesWithPostRoll,
      };
  }
  return {
    journey: {
      passed: journey.passed,
      stoppedAt: journey.step,
      pipeline: journey.pipeline,
    },
    // These are the recorded snapshots, not inferred pass/fail results. Failure cleanup can overwrite fromDevice.
    snapshots,
    capture: {
      overflow: trace.overflow,
      requestedStates: trace.apmStates,
      answeredCallbacks: callbacks.length,
      pairedFrames: paired.frames,
      firstSourceFrame: paired.firstSourceFrame,
      pairingError: paired.error,
      pairedSourceCrcMismatches,
      maxAnsweredCallbackGapMs:
        callbacks.length < 2
          ? null
          : Math.max(
              ...callbacks
                .slice(1)
                .map((callback, i) => (callback[1] - callbacks[i]![1]) / 1e6)
            ),
    },
    processing,
    phases,
  };
}

if (import.meta.main) {
  if (process.argv[2] === "--artifact-summary") {
    assert.equal(
      process.argv.length,
      5,
      "Usage: node tools/compare-media-apm.ts --artifact-summary <artifact-directory> <summary.json>"
    );
    const summary = summarizeMediaArtifacts(process.argv[3]!);
    writeFileSync(process.argv[4]!, JSON.stringify(summary, null, 2) + "\n");
    console.log(JSON.stringify(summary.journey));
  } else {
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
}
