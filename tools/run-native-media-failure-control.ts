import assert from "node:assert/strict";
import { copyFileSync, existsSync, mkdirSync, readFileSync, readdirSync, statSync } from "node:fs";
import { join } from "node:path";
import { captureMediaCommand, collectNativeMediaEvidence, type EvidenceClock } from "./native-media-evidence.ts";
import { parseMediaExits } from "./native-media-crash-evidence.ts";
import type { Execute } from "./run-native-media-journey.ts";

const app = "expo.modules.callkittelecom.instrumentation";
const method = "realRecordinglessCapturePublicationGateDecodedAndPostMuteOutput";
const original = "Recordingless source stopped";
const execute: Execute = ({ program, args, timeout }) => captureMediaCommand(program, args, timeout);
function object(value: unknown): Record<string, unknown> {
  assert(value !== null && typeof value === "object" && !Array.isArray(value), "Expected control evidence object");
  return value as Record<string, unknown>;
}
function xmlFiles(directory: string): string[] {
  if (!existsSync(directory)) return [];
  const files = readdirSync(directory, { recursive: true }).flatMap(name =>
    typeof name === "string" && name.endsWith(".xml") ? [join(directory, name)] : []);
  assert(files.length <= 16, "Too many control XML reports");
  assert(files.every(file => statSync(file).size <= 256 * 1024), "Control XML exceeds artifact bound");
  return files;
}

/** Read the constrained Gradle JUnit report, never runner stdout or arbitrary diagnostic payloads. */
export function verifyMediaFailureXml(xml: string): void {
  assert(!/<!DOCTYPE|<!ENTITY/.test(xml), "Unexpected XML declarations");
  const suite = /<testsuite\b([^>]*)>/.exec(xml)?.[1] ?? "";
  for (const [key, expected] of [["tests", "1"], ["failures", "1"], ["errors", "0"], ["skipped", "0"]])
    assert(new RegExp(`\\b${key}=["']${expected}["']`).test(suite), `Unexpected control ${key}`);
  assert.equal((xml.match(/<testcase\b/g) ?? []).length, 1, "Control must execute exactly one case");
  assert(xml.includes(`name="${method}"`), "Unexpected control selector");
  const failure = /<failure\b[^>]*>([\s\S]*?)<\/failure>/.exec(xml)?.[1];
  assert(failure, "Control failure is empty");
  const decoded = failure.replace(/&#(x[0-9a-f]+|\d+);/gi, (_, number: string) =>
    String.fromCodePoint(number.startsWith("x") ? parseInt(number.slice(1), 16) : Number(number)))
    .replace(/&lt;/g, "<").replace(/&gt;/g, ">").replace(/&quot;/g, '"').replace(/&apos;/g, "'").replace(/&amp;/g, "&");
  const marker = "[mediaQualificationTrace v1]";
  assert(new RegExp(`^(?:java\\.lang\\.AssertionError: ){1,2}${original}\\n\\[mediaQualificationTrace v1\\]`).test(decoded),
    "Original assertion must precede the trace");
  const trace = decoded.slice(decoded.indexOf(marker)).split(/\n\s+at /)[0]!.trimEnd();
  assert(Buffer.byteLength(trace) <= 8192 && /^[\x20-\x7e\n]*$/.test(trace), "Control trace must be bounded ASCII");
}

/** Both phases run; an expected control failure never hides the normal suite's original failure. */
export function mediaSuiteThenFailureControl(suite: () => void, control: () => void): void {
  let failed = false, failure: unknown;
  try { suite(); } catch (error) { failed = true; failure = error; }
  try { control(); } catch (error) { if (!failed) throw error; }
  if (failed) throw failure;
}

/** One real journey, with an intentional assertion, on the emulator already owned by the media job. */
export function runNativeMediaFailureControl(output = "android/instrumentation/build", run: Execute = execute,
  timing?: EvidenceClock): void {
  const deadline = performance.now() + 240_000;
  const destination = join(output, "media-failure-control");
  const baseline = join(destination, "suite-xml");
  mkdirSync(baseline, { recursive: true });
  const reports = join(output, "outputs/androidTest-results/connected/emulatorTest");
  for (const [index, file] of xmlFiles(reports).entries()) copyFileSync(file, join(baseline, `${index}.xml`));
  const command = (program: "adb" | "gradle", args: string[], timeout = 10_000) => {
    const remaining = Math.floor(deadline - performance.now());
    assert(remaining > 0, "Failure control exceeded its 240-second bound");
    const value = run({ program, args, timeout: Math.min(timeout, remaining), capture: true, diagnostics: true });
    return typeof value === "string" ? { stdout: value, stderr: "", exitCode: 0 } : value;
  };
  const result = command("gradle", ["-p", "android/instrumentation", ":connectedEmulatorTestAndroidTest",
    "-PmediaTestBuildType=emulatorTest", "-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true",
    `-Pandroid.testInstrumentationRunnerArguments.class=expo.modules.callkittelecom.conversations.MediaHookFeasibilityTest#${method}`,
    "-Pandroid.testInstrumentationRunnerArguments.mediaForceRecordinglessFailure=true",
    "--no-daemon", "--max-workers=2", "--stacktrace"], 180_000);
  assert(result.exitCode !== null && result.exitCode !== 0, "Forced assertion must fail without timing out");
  const evidence = collectNativeMediaEvidence(destination, (name, timeout) => command("adb",
    ["-s", "emulator-5554", "exec-out", "run-as", app, "cat", `files/${name}.json`], timeout), timing);
  const failure = object(evidence.results["media-hook-results"]);
  const exit = command("adb", ["-s", "emulator-5554", "shell", "dumpsys", "activity", "exit-info", app]);
  assert.equal(exit.exitCode, 0, "Could not inspect control process exit history");
  assert(/ApplicationExitInfo|ACTIVITY MANAGER PROCESS EXIT INFO/.test(exit.stdout), "Missing process exit history response");
  assert(Number.isSafeInteger(failure.pid) && (failure.pid as number) > 0, "Missing control process identity");
  const matching = parseMediaExits(exit.stdout).filter(record => record.pid === failure.pid);
  assert(!matching.some(record => record.reason === "native-crash"), "Control process suffered a native crash");
  if (!matching.length) {
    const live = command("adb", ["-s", "emulator-5554", "shell", "pidof", app]);
    assert(live.exitCode === 0 && live.stdout.trim().split(/\s+/).includes(String(failure.pid)),
      "Control process has neither matching exit history nor a live PID");
  }
  assert.equal(failure.test, method, "Unexpected persisted test");
  assert.equal(failure.forcedFailure, true, "Missing forced-failure identity");
  assert.equal(failure.passed, false, "Control must persist a failure");
  assert.equal(object(failure.originalFailure).message, original, "Original assertion was not persisted");
  assert.equal(object(failure.cleanup).roomLeft, true, "Room cleanup did not complete");
  assert.equal(object(failure.cleanup).captureStopped, true, "Capture cleanup did not complete");
  assert.equal(evidence.failures.length, 0, "Control device evidence was incomplete");
  const files = xmlFiles(reports);
  assert.equal(files.length, 1, "Expected one control XML report");
  copyFileSync(files[0]!, join(destination, "control.xml"));
  verifyMediaFailureXml(readFileSync(files[0]!, "utf8"));
  console.log("Android media failure control: original assertion, bounded XML, process exit and cleanup verified.");
}
