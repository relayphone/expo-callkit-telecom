import { execFileSync } from "node:child_process";
import { appendFileSync, mkdirSync, renameSync, rmSync, writeFileSync } from "node:fs";
import { join } from "node:path";

export interface Command {
  program: "gradle" | "adb" | "node";
  args: readonly string[];
  timeout: number;
  capture?: boolean;
}

export type Execute = (command: Command) => string;
const execute: Execute = ({ program, args, timeout, capture }) =>
  execFileSync(program, [...args], {
    encoding: "utf8",
    timeout,
    killSignal: "SIGKILL",
    stdio: ["ignore", capture ? "pipe" : "inherit", "inherit"],
    maxBuffer: 2 * 1024 * 1024,
  }) ?? "";

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
  return `### Android media failure\n\nStep: ${safe(result.step)}\n\n${causes.slice(0, 8).join("\n")}\n`;
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
      const contents = run({
        program: "adb",
        args: ["exec-out", "run-as", app, "cat", `files/${name}.json`],
        timeout: 15_000,
        capture: true,
      });
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
    run({ program: "node", args: ["--test", "tools/check-android-journey-state.test.ts"], timeout: 30_000 });
    run({ program: "node", args: ["tools/check-android-journey-state.ts"], timeout: 60_000 });
    if (qualifyHooks) runNativeMediaFeasibility(output, run);
  } finally {
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
  controls: "run" | "prechecked" = "run"): void {
  mkdirSync(output, { recursive: true });
  for (const name of ["media-hook-results", "capture-trace"]) rmSync(join(output, `${name}.json`), {force: true});
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
      if (build === "EmulatorTest") for (const name of ["media-hook-results", "capture-trace"]) {
        try {
          const contents = run({ program: "adb", args: ["exec-out", "run-as", app, "cat", `files/${name}.json`],
            timeout: 15_000, capture: true });
          JSON.parse(contents);
          writeFileSync(join(output, `${name}.json`), contents);
          if (name === "media-hook-results") reportMediaFailure(JSON.parse(contents));
        } catch (error) { if (failure === undefined) throw error; }
      }
    }
  }
}

if (import.meta.main) {
  if (process.argv.includes("--host-controls-only")) runNativeMediaControls();
  else if (process.argv.includes("--build-feasibility-only")) buildNativeMediaApks();
  else if (process.argv.includes("--feasibility-only")) runNativeMediaFeasibility(undefined, undefined,
    process.argv.includes("--controls-prechecked") ? "prechecked" : "run");
  else runNativeMediaJourney(undefined, undefined, !process.argv.includes("--functional-only"));
}
