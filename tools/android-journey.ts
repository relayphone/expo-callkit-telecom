import { execFileSync } from "node:child_process";
import { randomUUID } from "node:crypto";
import { mkdirSync, writeFileSync } from "node:fs";
import { join } from "node:path";

import { assertAndroidJourney } from "./android-journey-results.ts";

const packageName = "com.example.expocallkittelecom";
export type Adb = (args: readonly string[], timeout: number) => string;
const adb: Adb = (args, timeout) =>
  execFileSync("adb", [...args], {
    timeout,
    killSignal: "SIGKILL",
    encoding: "utf8",
    maxBuffer: 2 * 1024 * 1024,
  });

/** Installs the bundled debuggable example and collects only sanitized app/test evidence. */
export function runAndroidJourney(
  apks: string,
  output: string,
  command: Adb = adb,
  runID: string = randomUUID()
): void {
  if (!/^[a-z0-9-]{1,80}$/.test(runID)) throw new Error("Invalid runID");
  mkdirSync(output, { recursive: true });
  command(["install", "-r", join(apks, "app-release.apk")], 60_000);
  command(["install", "-r", join(apks, "app-release-androidTest.apk")], 60_000);
  for (const permission of ["RECORD_AUDIO", "POST_NOTIFICATIONS"])
    command(
      ["shell", "pm", "grant", packageName, `android.permission.${permission}`],
      10_000
    );
  command(["shell", "am", "force-stop", packageName], 10_000);
  command(
    [
      "shell",
      "run-as",
      packageName,
      "rm",
      "-f",
      "files/android-journey.json",
      "files/android-journey-ack.json",
      "files/android-native-journey.json",
    ],
    10_000
  );
  let instrumentation = "";
  const values: unknown[] = [];
  try {
    try {
      instrumentation = command(
        [
          "shell",
          "am",
          "instrument",
          "-w",
          "-r",
          "-e",
          "runID",
          runID,
          "-e",
          "class",
          "expo.modules.callkittelecom.conversations.AndroidBridgeJourneyTest",
          `${packageName}.test/androidx.test.runner.AndroidJUnitRunner`,
        ],
        330_000
      );
    } finally {
      for (const name of [
        "android-journey.json",
        "android-native-journey.json",
      ]) {
        try {
          const text = command(
            ["exec-out", "run-as", packageName, "cat", `files/${name}`],
            10_000
          );
          writeFileSync(join(output, name), text);
          values.push(JSON.parse(text));
        } catch {
          values.push(null);
        }
      }
    }
    if (
      !/OK \(1 test\)/.test(instrumentation) ||
      /FAILURES|INSTRUMENTATION_FAILED|Process crashed/.test(instrumentation)
    )
      throw new Error("Example instrumentation did not pass");
    assertAndroidJourney(values[0], values[1], runID);
  } finally {
    writeFileSync(join(output, "instrumentation.txt"), instrumentation);
    command(["shell", "am", "force-stop", packageName], 10_000);
  }
}

if (import.meta.main) {
  const [apks, output] = process.argv.slice(2);
  if (!apks || !output)
    throw new Error("Expected APK_DIRECTORY OUTPUT_DIRECTORY");
  runAndroidJourney(apks, output);
}
