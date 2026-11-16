import assert from "node:assert/strict";
import { mkdtempSync, mkdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { test } from "node:test";
import { mediaSuiteThenFailureControl, runNativeMediaFailureControl, verifyMediaFailureXml } from "./run-native-media-failure-control.ts";
import type { Command, Execute } from "./run-native-media-journey.ts";

const method = "realRecordinglessCapturePublicationGateDecodedAndPostMuteOutput";
const xml = `<testsuite tests="1" failures="1" errors="0" skipped="0"><testcase name="${method}"><failure>java.lang.AssertionError: Recordingless source stopped
[mediaQualificationTrace v1]
device.clock=android.elapsedRealtimeNanos
device.guard={&quot;pendingFrames&quot;:24001}
\tat org.junit.Assert.fail(Assert.java:1)
Caused by: java.lang.AssertionError: Recordingless source stopped</failure></testcase></testsuite>`;

test("actual Gradle-shaped XML must contain one original assertion and its bounded trace", () => {
  verifyMediaFailureXml(xml);
  verifyMediaFailureXml(xml.replace("java.lang.AssertionError: Recordingless", "java.lang.AssertionError: java.lang.AssertionError: Recordingless"));
  for (const invalid of [xml.replace('tests="1"', 'tests="2"'), xml.replace('failures="1"', 'failures="0"'),
    xml.replace("Recordingless source stopped", "different assertion"), xml.replace("[mediaQualificationTrace v1]", "missing"),
    xml.replace("device.clock=", "x".repeat(8193)), xml.replace(/<failure>[\s\S]*?<\/failure>/, "<failure></failure>")])
    assert.throws(() => verifyMediaFailureXml(invalid));
});

test("a failing suite still runs the control and keeps its same original failure", () => {
  const original = new Error("suite failed");
  let calls = 0;
  assert.throws(() => mediaSuiteThenFailureControl(() => { throw original; }, () => { calls++; throw Error("control failed"); }),
    error => error === original);
  assert.equal(calls, 1);
  assert.throws(() => mediaSuiteThenFailureControl(() => {}, () => { throw original; }), error => error === original);
});

test("forced journey requires the failed process, original persisted assertion, XML and finally cleanup", t => {
  const output = mkdtempSync(join(tmpdir(), "media-unwind-"));
  t.after(() => rmSync(output, { recursive: true, force: true }));
  const reports = join(output, "outputs/androidTest-results/connected/emulatorTest");
  mkdirSync(reports, { recursive: true });
  const calls: Command[] = [];
  let exitCode: number | null = 1, crashed = false, cleanup = true, original = "Recordingless source stopped";
  let historyPid = 123, live = true;
  const run: Execute = command => {
    calls.push(command);
    assert(command.timeout > 0 && command.timeout <= 180_000);
    if (command.program === "gradle") {
      assert(command.args.some(arg => arg.endsWith(`#${method}`)));
      assert(command.args.includes("-Pandroid.testInstrumentationRunnerArguments.mediaForceRecordinglessFailure=true"));
      writeFileSync(join(reports, "test.xml"), xml);
      return { stdout: "private Gradle output", stderr: "", exitCode };
    }
    if (command.args.includes("exit-info")) return `ApplicationExitInfo #0:\n timestamp=2026-11-16 00:00:01 pid=${historyPid} reason=${crashed ? 5 : 2} status=9\n`;
    if (command.args.includes("pidof")) return live ? "123\n" : "456\n";
    if (command.args.at(-1) === "files/media-hook-results.json" && !command.args.includes("rm")) return JSON.stringify({
      captureRate: 48000, profile: "tones-v2", passed: false, step: "muted publication", pipeline: "unobserved", failure: [], gateTrace: {},
      pid: 123, test: method, forcedFailure: true, originalFailure: { message: original }, cleanup: { roomLeft: cleanup, captureStopped: cleanup },
    });
    if (command.args.at(-1) === "files/capture-trace.json" && !command.args.includes("rm")) return JSON.stringify({
      clock: "android.elapsedRealtimeNanos", overflow: false, columns: [], callbacks: [], apmStates: [], rtc: [], postApm: {},
    });
    return "";
  };
  let now = 0;
  const timing = { now: () => now, pause: (ms: number) => { now += ms; } };
  writeFileSync(join(reports, "test.xml"), "normal suite XML");
  runNativeMediaFailureControl(output, run, timing);
  assert.equal(readFileSync(join(output, "media-failure-control/suite-xml/0.xml"), "utf8"), "normal suite XML");
  assert.equal(readFileSync(join(output, "media-failure-control/control.xml"), "utf8"), xml);
  assert.equal(calls.filter(call => call.program === "gradle").length, 1);
  for (const mode of ["succeeded", "timeout", "native", "cleanup", "assertion", "missing-pid", "live-pid"] as const) {
    exitCode = mode === "succeeded" ? 0 : mode === "timeout" ? null : 1;
    crashed = mode === "native"; cleanup = mode !== "cleanup"; original = mode === "assertion" ? "different" : "Recordingless source stopped";
    historyPid = mode.endsWith("pid") ? 456 : 123; live = mode !== "missing-pid";
    if (mode === "live-pid") runNativeMediaFailureControl(output, run, timing);
    else assert.throws(() => runNativeMediaFailureControl(output, run, timing));
  }
});
