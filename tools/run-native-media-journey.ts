import { execFileSync } from "node:child_process";
import { mkdirSync, renameSync, rmSync, writeFileSync } from "node:fs";
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
export function runNativeMediaFeasibility(output = "android/instrumentation/build", run: Execute = execute): void {
  mkdirSync(output, { recursive: true });
  for (const name of ["media-hook-results", "capture-trace"]) rmSync(join(output, `${name}.json`), {force: true});
  run({ program: "node", args: ["--test", "tools/native-media-marker.test.ts", "tools/compare-media-apm.test.ts"], timeout: 30_000 });
  for (const [build, classes] of [
    ["EmulatorTest", "MediaHookFeasibilityTest,MediaTestIsolationTest"],
    ["Release", "MediaTestIsolationTest"],
  ] as const) {
    let failure: unknown;
    try {
      run({ program: "gradle", args: [
        "-p", "android/instrumentation", `:connected${build}AndroidTest`,
        `-PmediaTestBuildType=${build.charAt(0).toLowerCase()}${build.slice(1)}`,
        "-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true",
        `-Pandroid.testInstrumentationRunnerArguments.class=${classes.split(",")
          .map((name) => `expo.modules.callkittelecom.conversations.${name}`).join(",")}`,
        "--no-daemon", "--max-workers=2", "--stacktrace",
      ], timeout: 5 * 60_000 });
    } catch (error) { failure = error; throw error; }
    finally {
      if (build === "EmulatorTest") for (const name of ["media-hook-results", "capture-trace"]) {
        try {
          const contents = run({ program: "adb", args: ["exec-out", "run-as", app, "cat", `files/${name}.json`],
            timeout: 15_000, capture: true });
          JSON.parse(contents);
          writeFileSync(join(output, `${name}.json`), contents);
        } catch (error) { if (failure === undefined) throw error; }
      }
    }
  }
}

if (import.meta.main) {
  if (process.argv.includes("--feasibility-only")) runNativeMediaFeasibility();
  else runNativeMediaJourney(undefined, undefined, !process.argv.includes("--functional-only"));
}
