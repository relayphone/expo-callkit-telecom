import assert from "node:assert/strict";
import { mkdtempSync, readFileSync, readdirSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { test } from "node:test";
import { captureMediaCommand, collectNativeMediaEvidence, type CapturedOutput, type MediaEvidenceName } from "./native-media-evidence.ts";

const hook = { captureRate: 48000, profile: "tones-v2", passed: true, step: "end", pipeline: "observed", failure: [], gateTrace: {} };
const capture = { clock: "android.elapsedRealtimeNanos", overflow: false, columns: [], callbacks: [], apmStates: [], rtc: [], postApm: {} };
const ready = (name: MediaEvidenceName): CapturedOutput => ({ stdout: JSON.stringify(name === "media-hook-results" ? hook : capture), stderr: "", exitCode: 0 });

test("the real tool adapter captures stdout, stderr and exit status without printing tool output", () => {
  const result = captureMediaCommand(process.execPath, ["-e",
    'process.stdout.write("{}");process.stderr.write("private stderr");process.exitCode=7'], 1000);
  assert.deepEqual(result, { stdout: "{}", stderr: "private stderr", exitCode: 7 });
  const timeout = captureMediaCommand(process.execPath, ["-e", 'setTimeout(()=>{},10000)'], 100);
  assert.equal(timeout.exitCode, null);
});
function fixture() {
  const output = mkdtempSync(join(tmpdir(), "media-evidence-"));
  let now = 0;
  const timing = { now: () => now, pause: (ms: number) => { assert.ok(ms > 0 && ms <= 200); now += ms; } };
  return { output, timing, advance: (ms: number) => { now += ms; },
    journal: () => JSON.parse(readFileSync(join(output, "media-evidence-reads.json"), "utf8")),
    close: () => rmSync(output, { recursive: true, force: true }) };
}

for (const source of ["media-hook-results", "capture-trace"] as const)
  test(`${source} recovers from missing, empty, truncated and incomplete output within the shared bound`, () => {
    const f = fixture();
    let count = 0;
    const responses: CapturedOutput[] = [
      { stdout: "", stderr: "cat: private path: No such file or directory", exitCode: 1 },
      { stdout: " \n", stderr: "", exitCode: 0 },
      { stdout: '{"private":"å"', stderr: "sensitive stderr", exitCode: 0 },
      { stdout: "{}", stderr: "", exitCode: 0 }, ready(source),
    ];
    try {
      const result = collectNativeMediaEvidence(f.output, (name, timeout) => {
        assert.ok(timeout > 0 && timeout <= 1000);
        return name === source ? responses[count++]! : ready(name);
      }, f.timing);
      assert.deepEqual(result.failures, []);
      assert.equal(count, 5);
      assert.equal(f.timing.now(), 800);
      const journal = f.journal();
      const attempts = journal.attempts.filter((item: { source: string }) => item.source === source);
      assert.deepEqual(attempts.map((item: { state: string }) => item.state), ["read-error", "empty", "invalid", "fields", "ready"]);
      assert.deepEqual(attempts.map((item: { exitCode: number }) => item.exitCode), [1, 0, 0, 0, 0]);
      assert.equal(attempts[2].bytes, 15);
      assert.equal(attempts[0].stderr, "file missing");
      assert.equal(attempts[2].stderr, "other stderr redacted");
      assert.doesNotMatch(JSON.stringify(journal), /private|sensitive/);
      assert.equal(journal.attempts.filter((item: { source: string }) => item.source !== source).length, 1);
      assert.deepEqual(readdirSync(f.output).sort(), ["capture-trace.json", "media-evidence-reads.json", "media-hook-results.json"]);
    } finally { f.close(); }
  });

test("both readers share 10 seconds and no per-read timeout exceeds its remaining budget", () => {
  const f = fixture();
  try {
    const result = collectNativeMediaEvidence(f.output, (_name, timeout) => {
      assert.ok(timeout <= 10_000 - f.timing.now());
      f.advance(750);
      return { stdout: "", stderr: "device offline: private details", exitCode: 1 };
    }, f.timing);
    assert.ok(f.timing.now() <= 10_000);
    assert.equal(result.failures.length, 2);
    assert.ok(result.failures.every(message => message.startsWith("Retain EmulatorTest evidence: could not read JSON from adb files/")));
    const journal = f.journal();
    assert.equal(journal.budgetMs, 10_000);
    assert.ok(journal.attempts.length > 2 && journal.attempts.length <= 100);
    assert.ok(journal.attempts.every((item: { stderr: string }) => item.stderr === "device offline"));
    assert.doesNotMatch(JSON.stringify(journal), /private/);
  } finally { f.close(); }
});

test("valid JSON after the bound or from a failed command is not accepted", () => {
  for (const late of [false, true]) {
    const f = fixture();
    try {
      const result = collectNativeMediaEvidence(f.output, name => {
        if (late) f.advance(10_001);
        return { ...ready(name), exitCode: late ? 0 : 1 };
      }, f.timing);
      assert.deepEqual(result.results, {});
      assert.equal(result.failures.length, 2);
      assert.match(result.failures[0]!, late ? /exceeded 10000 ms/ : /could not read JSON/);
    } finally { f.close(); }
  }
});

test("failed test results and overflow diagnostics are complete evidence, without changing their verdict", () => {
  const f = fixture();
  try {
    const result = collectNativeMediaEvidence(f.output, name => ({ stdout: JSON.stringify(name === "media-hook-results"
      ? { ...hook, passed: false, failure: [{ type: "Error", message: "Details retained in test XML" }] }
      : { ...capture, overflow: true }), stderr: "", exitCode: 0 }), f.timing);
    assert.deepEqual(result.failures, []);
    assert.equal((result.results["media-hook-results"] as typeof hook).passed, false);
    assert.equal((result.results["capture-trace"] as typeof capture).overflow, true);
    assert.equal(f.timing.now(), 0);
  } finally { f.close(); }
});

test("missing or wrongly typed top-level fields never count as publication readiness", () => {
  for (const source of ["media-hook-results", "capture-trace"] as const) {
    const valid = source === "media-hook-results" ? hook : capture;
    for (const field of Object.keys(valid)) for (const missing of [false, true]) {
      const f = fixture();
      try {
        const partial = { ...valid } as Record<string, unknown>;
        if (missing) delete partial[field]; else partial[field] = null;
        const result = collectNativeMediaEvidence(f.output, name => name === source
          ? { stdout: JSON.stringify(partial), stderr: "", exitCode: 0 } : ready(name), f.timing);
        assert.equal(result.failures.length, 1, field);
        assert.match(result.failures[0]!, /incomplete JSON fields/);
      } finally { f.close(); }
    }
  }
});

test("adb tool errors on stdout retain only their known reason and remain fatal", () => {
  for (const mode of ["zero", "nonzero", "late"] as const) {
  const f = fixture();
  try {
    const result = collectNativeMediaEvidence(f.output, () => {
      if (mode === "late") f.advance(10001);
      return { stdout: "cat: private path: No such file or directory\n", stderr: "", exitCode: mode === "nonzero" ? 1 : 0 };
    }, f.timing);
    assert.equal(result.failures.length, 2);
    assert.ok(f.journal().attempts.every((attempt: { stdoutDiagnostic: string }) => attempt.stdoutDiagnostic === "file missing"));
    assert.doesNotMatch(JSON.stringify(f.journal()), /private/);
  } finally { f.close(); }
  }
});
