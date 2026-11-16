import { execFileSync } from "node:child_process";
import { appendFileSync, mkdirSync, renameSync, rmSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { captureMediaCommand, collectNativeMediaEvidence, mediaEvidenceNames, type CapturedOutput, type EvidenceClock } from "./native-media-evidence.ts";
import { withMediaCrashEvidence } from "./native-media-crash-evidence.ts";
import { collectEmulatorBudget } from "./android-emulator-budget.ts";
import { mediaSuiteThenFailureControl, runNativeMediaFailureControl } from "./run-native-media-failure-control.ts";

export interface Command {
  program: "gradle" | "adb" | "node";
  args: readonly string[];
  timeout: number;
  capture?: boolean;
  diagnostics?: true;
}

export type Execute = (command: Command) => string | CapturedOutput;
const execute: Execute = ({ program, args, timeout, capture, diagnostics }) => {
  if (diagnostics) return captureMediaCommand(program, args, timeout);
  return execFileSync(program, [...args], {
    encoding: "utf8",
    timeout,
    killSignal: "SIGKILL",
    stdio: ["ignore", capture ? "pipe" : "inherit", "inherit"],
    maxBuffer: 2 * 1024 * 1024,
  }) ?? "";
};

const app = "expo.modules.callkittelecom.instrumentation";

function mediaPhase(name: string, operation: () => void): void {
  const started = performance.now();
  let result = "failed";
  try { operation(); result = "passed"; }
  finally {
    const summary = process.env.GITHUB_STEP_SUMMARY;
    if (summary) appendFileSync(summary, `### Android media: ${name}\n\n| Result | Duration |\n| --- | --- |\n| ${result} | ${((performance.now() - started) / 1000).toFixed(3)} s |\n\n`);
  }
}

/** Compile both app/test variants before boot; connected execution keeps its existing deadline. */
export function buildNativeMediaApks(run: Execute = execute): void {
  for (const build of ["EmulatorTest", "Release"] as const) {
    mediaPhase(`build ${build} APKs`, () => run({ program: "gradle", args: [
      "-p", "android/instrumentation", `:assemble${build}`, `:assemble${build}AndroidTest`,
      `-PmediaTestBuildType=${build.charAt(0).toLowerCase()}${build.slice(1)}`,
      "--no-daemon", "--max-workers=2", "--stacktrace",
    ], timeout: 5 * 60_000 }));
  }
}

/** The Kotlin fixture exports only allowlisted failure messages, never request/SDK details. */
export function mediaFailureSummary(value: unknown): string | undefined {
  if (value === null || typeof value !== "object") return;
  const result = value as Record<string, unknown>;
  if (result.passed !== false || typeof result.step !== "string" || !Array.isArray(result.failure)) return;
  const safe = (text: string) => text.replace(/[^a-zA-Z0-9 .:_-]/g, "").slice(0, 200);
  const causes = result.failure.flatMap((entry: unknown) => {
    if (entry === null || typeof entry !== "object") return [];
    const detail = entry as Record<string, unknown>;
    return typeof detail.type === "string" && typeof detail.message === "string"
      ? [`- ${safe(detail.type)}: ${safe(detail.message)}`] : [];
  });
  if (causes.length === 0) return;
  return `### Android media failure\n\nStep: ${safe(result.step)}\n\n${causes.slice(0, 8).join("\n")}\n${muteTimingSummary(result.muteTimings)}${outputConsumerSummary(result)}`;
}

/** Compare durations only within each named monotonic clock; retain no request or PCM fields. */
export function outputConsumerSummary(value: unknown): string {
  const object = (value: unknown): Record<string, unknown> | undefined =>
    value !== null && typeof value === "object" && !Array.isArray(value) ? value as Record<string, unknown> : undefined;
  const nanos = (value: unknown): bigint | undefined =>
    typeof value === "string" && /^\d{1,24}$/.test(value) ? BigInt(value) : undefined;
  const count = (value: unknown): value is number => typeof value === "number" && Number.isSafeInteger(value) && value >= 0;
  const ms = (value: bigint) => `${value / 1_000_000n}.${(value % 1_000_000n / 1_000n).toString().padStart(3, "0")}`;
  const result = object(value);
  if (!result) return "";
  const device = object(result.outputConsumerTrace);
  const server = object(result.outputServerTrace) ?? object(object(result.toDevice)?.outputConsumerTrace);
  const lines: string[] = [];
  for (const [domain, trace, clock] of [["device", device, "android.elapsedRealtimeNanos"],
    ["fixture", server, "host.monotonic_ns"]] as const) {
    if (!trace || trace.clock !== clock) continue;
    lines.push(`${domain} clock: ${clock}`);
    const guard = object(trace.guard);
    if (guard && [guard.capturedFrames, guard.drainedFrames, guard.pendingFrames, guard.limitFrames].every(count)) {
      const at = nanos(guard.atNs);
      lines.push(`Drain guard: captured=${guard.capturedFrames}, drained=${guard.drainedFrames}, pending=${guard.pendingFrames}, limit=${guard.limitFrames}${at === undefined ? "" : `, atNs=${at}`}`);
    }
    const enqueue = object(trace.enqueue);
    const entered = nanos(enqueue?.enteredNs), exited = nanos(enqueue?.exitedNs), gap = nanos(enqueue?.largestGapNs);
    if (entered !== undefined && exited !== undefined && exited >= entered)
      lines.push(`ENQUEUE: enteredNs=${entered}, exitedNs=${exited}, durationMs=${ms(exited - entered)}${gap === undefined ? "" : `, largestGapMs=${ms(gap)}`}`);
    const allowed = new Set(["WAIT", "RTC_STATS", "DRAIN", "HTTP_ACK", "ENQUEUE", "SCORE", "HTTP_RESPONSE"]);
    const observed = nanos(trace.observedAtNs);
    const timings = (Array.isArray(trace.stages) ? trace.stages.slice(-128) : []).flatMap((value: unknown) => {
      const span = object(value);
      if (!span || typeof span.stage !== "string" || !allowed.has(span.stage) || typeof span.completed !== "boolean") return [];
      const entered = nanos(span.enteredNs), exited = span.exitedNs === null ? observed : nanos(span.exitedNs);
      if (entered === undefined || exited === undefined || exited < entered) return [];
      const queued = nanos(span.queuedNs);
      return [{ stage: span.stage, entered, exited, duration: exited - entered,
        state: span.exitedNs === null ? "active" : span.completed ? "completed" : "failed",
        queue: queued !== undefined && queued <= entered ? entered - queued : undefined }];
    });
    const describe = (span: typeof timings[number]) =>
      `${span.stage}, durationMs=${ms(span.duration)}, state=${span.state}, enteredNs=${span.entered}, exitedNs=${span.exited}${span.queue === undefined ? "" : `, queueMs=${ms(span.queue)}`}`;
    const slowest = timings.reduce<typeof timings[number] | undefined>((a, b) => !a || b.duration > a.duration ? b : a, undefined);
    if (slowest) lines.push(`Slowest measured ${domain} stage: ${describe(slowest)}`);
    const last = timings.at(-1);
    if (last) lines.push(`Last ${domain} stage: ${describe(last)}`);
    for (const stage of allowed) {
      const latest = timings.findLast(span => span.stage === stage);
      if (latest && latest !== slowest && latest !== last) lines.push(`Measured ${domain} stage: ${describe(latest)}`);
    }
    for (const active of timings.filter(span => span.state === "active").slice(-4))
      lines.push(`Active ${domain} stage: ${describe(active)}`);
    if (count(trace.droppedSpans) && trace.droppedSpans > 0) lines.push(`${domain} trace dropped spans: ${trace.droppedSpans}`);
  }
  return lines.length ? `\n### Output consumer timing\n\n${lines.join("\n")}\n` : "";
}

function muteTimingSummary(value: unknown): string {
  if (!Array.isArray(value)) return "";
  const steps = [
    ["Serial lock acquire", "muteSerial"], ["Session mute", "muteSession"],
    ["Publication mute", "mutePublication"], ["Capture boundary wait", "muteBoundary"],
  ] as const;
  type Boundary = { time: bigint; completed: boolean };
  const operations = new Map<string, Map<string, Boundary>>();
  const decimal = (text: unknown): text is string => typeof text === "string" && /^[0-9]{1,19}$/.test(text)
    && BigInt(text) <= 9_223_372_036_854_775_807n;
  for (const entry of value.slice(-64)) {
    if (entry === null || typeof entry !== "object") continue;
    const item = entry as Record<string, unknown>;
    if (!decimal(item.operationId) || !decimal(item.elapsedRealtimeNanos) ||
      !steps.some(([, stage]) => item.stage === `${stage}Entered` || item.stage === `${stage}Exited`)) continue;
    if (typeof item.stage !== "string" || (item.stage.endsWith("Exited") && item.completed !== 0 && item.completed !== 1)) continue;
    const boundaries = operations.get(item.operationId) ?? new Map<string, Boundary>();
    if (!boundaries.has(item.stage)) boundaries.set(item.stage, { time: BigInt(item.elapsedRealtimeNanos), completed: item.completed === 1 });
    operations.set(item.operationId, boundaries);
  }
  if (operations.size === 0) return "";
  const rows = [...operations].slice(-8).flatMap(([id, boundaries]) => steps.map(([name, stage]) => {
    const entered = boundaries.get(`${stage}Entered`);
    const exited = boundaries.get(`${stage}Exited`);
    const elapsed = entered && exited ? exited.time - entered.time : undefined;
    const duration = elapsed !== undefined && elapsed >= 0n
      ? `${elapsed / 1_000_000n}.${(elapsed % 1_000_000n / 1_000n).toString().padStart(3, "0")}` : "unavailable";
    const state = elapsed !== undefined && elapsed < 0n ? "invalid order"
      : exited ? exited.completed ? "completed" : "failed/cancelled" : entered ? "exit not observed" : "not entered";
    return `| ${id} | ${name} | ${entered?.time ?? "unavailable"} | ${exited?.time ?? "unavailable"} | ${duration} | ${state} |`;
  }));
  return `\nMute boundaries (monotonic device nanoseconds; duration in ms):\n\n| Operation | Work | Entry ns | Exit ns | Duration ms | Result |\n| --- | --- | --- | --- | --- | --- |\n${rows.join("\n")}\n`;
}

function reportMediaFailure(value: unknown): void {
  const summary = mediaFailureSummary(value);
  if (!summary) return;
  process.stderr.write(summary);
  const path = process.env.GITHUB_STEP_SUMMARY;
  if (path) appendFileSync(path, summary);
}

/** Keep CPU-heavy detector controls outside the running emulator in CI. */
export function runNativeMediaControls(run: Execute = execute): void {
  run({ program: "node", args: ["--test", "tools/native-media-marker.test.ts", "tools/compare-media-apm.test.ts"], timeout: 30_000 });
}
const checks = [
  ["journey-results", "node", "check-android-journey.ts"],
  ["answered-elsewhere-results", "node", "check-answered-elsewhere.ts"],
  ["native-media-results", "node", "check-native-media.ts"],
  ["native-webrtc-results", "node", "check-native-media.ts"],
] as const;

/** Runs the functional suite once and retains available media evidence on failure. */
export function runNativeMediaJourney(
  output = "android/instrumentation/build",
  run: Execute = execute,
  qualifyHooks = true
): void {
  mkdirSync(output, { recursive: true });
  const saved = new Set<string>();
  function pull(name: string): string {
    const destination = join(output, `${name}.json`);
    try {
      const result = run({
        program: "adb",
        args: ["exec-out", "run-as", app, "cat", `files/${name}.json`],
        timeout: 15_000,
        capture: true,
      });
      const contents = typeof result === "string" ? result : result.stdout;
      writeFileSync(`${destination}.tmp`, contents);
      renameSync(`${destination}.tmp`, destination);
      saved.add(name);
      return destination;
    } finally {
      rmSync(`${destination}.tmp`, { force: true });
    }
  }
  for (const [name] of checks)
    rmSync(join(output, `${name}.json`), { force: true });
  rmSync(join(output, "push-fault-events.json"), { force: true });
  try {
    run({
      program: "gradle",
      args: [
        "-p",
        "android/instrumentation",
        ":connectedDebugAndroidTest",
        "-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true",
        "--stacktrace",
      ],
      timeout: 25 * 60_000,
    });
    for (const [name, program, checker] of checks) {
      const destination = pull(name);
      run({
        program,
        args: [join("tools", checker), destination],
        timeout: 15_000,
      });
    }
    const pushLog = pull("push-fault-events");
    run({ program: "node", args: ["tools/android-push-faults.ts", "--file", pushLog], timeout: 15_000 });
    run({ program: "node", args: ["--test", "tools/check-android-journey-state.test.ts"], timeout: 30_000 });
    run({ program: "node", args: ["tools/check-android-journey-state.ts"], timeout: 60_000 });
    if (qualifyHooks) runNativeMediaFeasibility(output, run);
  } finally {
    if (!saved.has("push-fault-events")) {
      try { pull("push-fault-events"); } catch { /* Preserve the original suite failure. */ }
    }
    for (const [name] of checks.slice(1)) {
      if (!saved.has(name)) {
        try {
          pull(name);
        } catch {
          /* Preserve the original suite/checker failure. */
        }
      }
    }
  }
}

/** Qualifies isolated software audio on its own CI emulator and retains failures before Release reinstalls. */
export function runNativeMediaFeasibility(output = "android/instrumentation/build", run: Execute = execute,
  controls: "run" | "prechecked" = "run", timing?: EvidenceClock): void {
  mkdirSync(output, { recursive: true });
  for (const name of mediaEvidenceNames) rmSync(join(output, `${name}.json`), {force: true});
  rmSync(join(output, "media-evidence-reads.json"), { force: true });
  if (controls === "run") runNativeMediaControls(run);
  for (const [build, classes] of [
    ["EmulatorTest", "MediaHookFeasibilityTest,MediaTestIsolationTest"],
    ["Release", "MediaTestIsolationTest"],
  ] as const) {
    let failure: unknown;
    try {
      mediaPhase(`test ${build}`, () => run({ program: "gradle", args: [
        "-p", "android/instrumentation", `:connected${build}AndroidTest`,
        `-PmediaTestBuildType=${build.charAt(0).toLowerCase()}${build.slice(1)}`,
        "-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true",
        `-Pandroid.testInstrumentationRunnerArguments.class=${classes.split(",")
          .map((name) => `expo.modules.callkittelecom.conversations.${name}`).join(",")}`,
        "--no-daemon", "--max-workers=2", "--stacktrace",
      ], timeout: 5 * 60_000 }));
    } catch (error) { failure = error; throw error; }
    finally {
      if (build === "EmulatorTest") {
        try {
          const evidence = collectNativeMediaEvidence(output, (name, timeout) => {
            const result = run({ program: "adb", args: ["exec-out", "run-as", app, "cat", `files/${name}.json`],
              timeout, capture: true, diagnostics: true });
            return typeof result === "string" ? { stdout: result, stderr: "", exitCode: 0 } : result;
          }, timing);
          if (evidence.results["media-hook-results"]) reportMediaFailure(evidence.results["media-hook-results"]);
          for (const message of evidence.failures) {
            process.stderr.write(`${message}\n`);
            const summary = process.env.GITHUB_STEP_SUMMARY;
            if (summary) {
              try { appendFileSync(summary, `\n${message}\n`); }
              catch { process.stderr.write("Retain EmulatorTest evidence: could not write failure summary\n"); }
            }
          }
          if (evidence.failures[0]) throw new Error(evidence.failures[0]);
        } catch (error) { if (failure === undefined) throw error; }
      }
    }
  }
}

if (import.meta.main) {
  if (process.argv.includes("--host-controls-only")) runNativeMediaControls();
  else if (process.argv.includes("--build-feasibility-only")) buildNativeMediaApks();
  else if (process.argv.includes("--feasibility-only")) {
    const operation = () => {
      if (process.argv.includes("--crash-evidence")) console.log(JSON.stringify(collectEmulatorBudget()));
      runNativeMediaFeasibility(undefined, undefined,
        process.argv.includes("--controls-prechecked") ? "prechecked" : "run");
    };
    const suite = () => process.argv.includes("--crash-evidence")
      ? withMediaCrashEvidence(operation, "android/instrumentation/build/media-crash-evidence") : operation();
    if (process.argv.includes("--with-failure-control")) mediaSuiteThenFailureControl(suite, () =>
      mediaPhase("forced assertion unwind", () => withMediaCrashEvidence(() => runNativeMediaFailureControl(),
        "android/instrumentation/build/media-failure-control/crash-evidence")));
    else suite();
  }
  else runNativeMediaJourney(undefined, undefined, !process.argv.includes("--functional-only"));
}
