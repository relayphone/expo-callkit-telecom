import { execFileSync } from "node:child_process";
import { mkdirSync, renameSync, rmSync, writeFileSync } from "node:fs";
import { join } from "node:path";

export interface Command {
  program: "gradle" | "adb" | "python3";
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
  ["journey-results", "check-android-journey.py"],
  ["answered-elsewhere-results", "check-answered-elsewhere.py"],
  ["native-media-results", "check-native-media.py"],
  ["native-webrtc-results", "check-native-media.py"],
] as const;

/** Runs the functional suite once and retains available media evidence on failure. */
export function runNativeMediaJourney(
  output = "android/instrumentation/build",
  run: Execute = execute
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
    for (const [name, checker] of checks) {
      const destination = pull(name);
      run({
        program: "python3",
        args: [join("tools", checker), destination],
        timeout: 15_000,
      });
    }
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

if (import.meta.main) runNativeMediaJourney();
