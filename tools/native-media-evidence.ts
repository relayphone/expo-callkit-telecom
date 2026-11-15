import { renameSync, rmSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { spawnSync } from "node:child_process";

export const mediaEvidenceNames = ["media-hook-results", "capture-trace"] as const;
export type MediaEvidenceName = typeof mediaEvidenceNames[number];
export interface CapturedOutput { stdout: string; stderr: string; exitCode: number | null }
export function captureMediaCommand(program: string, args: readonly string[], timeout: number): CapturedOutput {
  const result = spawnSync(program, [...args], { encoding: "utf8", timeout, killSignal: "SIGKILL",
    stdio: ["ignore", "pipe", "pipe"], maxBuffer: 2 * 1024 * 1024 });
  return { stdout: result.stdout ?? "", stderr: result.stderr ?? "", exitCode: result.status };
}
export interface EvidenceClock { now: () => number; pause: (milliseconds: number) => void }
const pauseWord = new Int32Array(new SharedArrayBuffer(4));
// This CLI runs commands synchronously; each short pause is included in the collection bound.
const clock: EvidenceClock = { now: () => performance.now(), pause: ms => { Atomics.wait(pauseWord, 0, 0, ms); } };
type Read = (name: MediaEvidenceName, timeout: number) => CapturedOutput;
type State = "unobserved" | "read-error" | "empty" | "invalid" | "fields" | "late" | "ready";
interface Attempt {
  source: MediaEvidenceName; attempt: number; elapsedMs: number; bytes: number;
  exitCode: number | null; stderr: string; state: State;
}

function record(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

/** Check publication readiness, including failed test results; this does not change any test oracle. */
function complete(name: MediaEvidenceName, value: unknown): boolean {
  if (!record(value)) return false;
  if (name === "media-hook-results") return typeof value.passed === "boolean" && typeof value.step === "string"
    && Number.isSafeInteger(value.captureRate) && (value.captureRate as number) > 0 && value.profile === "tones-v2"
    && typeof value.pipeline === "string" && Array.isArray(value.failure) && record(value.gateTrace);
  return value.clock === "android.elapsedRealtimeNanos" && typeof value.overflow === "boolean"
    && [value.columns, value.callbacks, value.apmStates, value.rtc].every(Array.isArray) && record(value.postApm);
}

/** Stderr is classified, never copied: adb/run-as can include arbitrary package or payload text. */
function safeStderr(stderr: string): string {
  if (!stderr.trim()) return "";
  const known: readonly [RegExp, string][] = [
    [/No such file or directory/i, "file missing"], [/device offline/i, "device offline"],
    [/device .*not found|no devices\/emulators found/i, "device unavailable"],
    [/daemon not running|daemon started successfully/i, "adb daemon starting"],
    [/cannot connect|failed to connect|cannot bind/i, "adb connection failed"],
    [/not debuggable|permission denied/i, "permission denied"], [/run-as:/i, "run-as failed"],
  ];
  return known.filter(([pattern]) => pattern.test(stderr)).map(([, name]) => name).join("; ") || "other stderr redacted";
}

function atomicWrite(path: string, contents: string): void {
  try { writeFileSync(`${path}.tmp`, contents); renameSync(`${path}.tmp`, path); }
  finally { rmSync(`${path}.tmp`, { force: true }); }
}

/** Observe both files for at most 10 s total, retaining every attempt without tool output or payloads. */
export function collectNativeMediaEvidence(output: string, read: Read, timing: EvidenceClock = clock): {
  results: Partial<Record<MediaEvidenceName, unknown>>;
  failures: string[];
} {
  const started = timing.now();
  const deadline = started + 10_000;
  const pending = new Set<MediaEvidenceName>(mediaEvidenceNames);
  const attempts: Attempt[] = [];
  const counts = { "media-hook-results": 0, "capture-trace": 0 };
  const last: Record<MediaEvidenceName, State> = { "media-hook-results": "unobserved", "capture-trace": "unobserved" };
  const results: Partial<Record<MediaEvidenceName, unknown>> = {};
  while (pending.size && timing.now() < deadline) {
    for (const name of pending) {
      const remaining = Math.floor(deadline - timing.now());
      if (remaining <= 0) break;
      let captured: CapturedOutput;
      try { captured = read(name, Math.min(1_000, remaining)); }
      catch { captured = { stdout: "", stderr: "read failed", exitCode: null }; }
      let value: unknown;
      let state: State = "read-error";
      if (captured.exitCode === 0) {
        state = "empty";
        if (captured.stdout.trim()) {
          try { value = JSON.parse(captured.stdout); state = complete(name, value) ? "ready" : "fields"; }
          catch { state = "invalid"; }
        }
      }
      if (timing.now() > deadline) state = "late";
      last[name] = state;
      attempts.push({ source: name, attempt: ++counts[name], elapsedMs: timing.now() - started,
        bytes: Buffer.byteLength(captured.stdout, "utf8"), exitCode: captured.exitCode,
        stderr: safeStderr(captured.stderr), state });
      atomicWrite(join(output, "media-evidence-reads.json"), JSON.stringify({ schemaVersion: 1,
        clock: "host.performance.now", budgetMs: 10_000, attempts }));
      if (state === "ready") {
        atomicWrite(join(output, `${name}.json`), captured.stdout);
        results[name] = value;
        pending.delete(name);
      }
    }
    if (pending.size) {
      const remaining = deadline - timing.now();
      if (remaining > 0) timing.pause(Math.min(200, remaining));
    }
  }
  const failures = [...pending].map(name => {
    const reason = { "read-error": "could not read JSON", empty: "empty JSON", invalid: "invalid JSON",
      fields: "incomplete JSON fields", late: "JSON read exceeded 10000 ms", ready: "unavailable JSON",
      unobserved: "JSON unavailable within 10000 ms" }[last[name]];
    return `Retain EmulatorTest evidence: ${reason} from adb files/${name}.json`;
  });
  return { results, failures };
}
