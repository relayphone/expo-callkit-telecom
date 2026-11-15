import assert from "node:assert/strict";
import {
  mkdtempSync,
  existsSync,
  readFileSync,
  readdirSync,
  rmSync,
  writeFileSync,
} from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { test } from "node:test";

import {
  runNativeMediaJourney,
  runNativeMediaFeasibility,
  mediaFailureSummary,
  outputConsumerSummary,
  runNativeMediaControls,
  buildNativeMediaApks,
  type Command,
  type Execute,
} from "./run-native-media-journey.ts";

test("output consumer failures name backlog and slow stages within each monotonic clock", () => {
  const value = { passed: false, step: "answered output", failure: [{ type: "IllegalStateException", message: "Output capture consumer fell behind" }],
    outputConsumerTrace: { clock: "android.elapsedRealtimeNanos", observedAtNs: "9007199256000000",
      guard: { capturedFrames: 52321, drainedFrames: 28320, pendingFrames: 24001, limitFrames: 24000, atNs: "9007199255000000" },
      enqueue: { enteredNs: "9007199254000000", exitedNs: "9007199254001000", largestGapNs: "10000000" },
      stages: [{ stage: "RTC_STATS", enteredNs: "9007199253000000", exitedNs: "9007199253050000", completed: true },
        { stage: "HTTP_ACK", enteredNs: "9007199254000000", exitedNs: "9007199254900000", completed: true },
        { stage: "DRAIN", enteredNs: "9007199255000000", exitedNs: "9007199255001000", completed: false }] },
    outputServerTrace: { clock: "host.monotonic_ns", observedAtNs: "810000000",
      stages: [{ stage: "SCORE", queuedNs: "1000000", enteredNs: "10000000", exitedNs: null, completed: false }] } };
  const summary = mediaFailureSummary(value)!;
  assert.match(summary, /captured=52321, drained=28320, pending=24001, limit=24000/);
  assert.match(summary, /Slowest measured device stage: HTTP_ACK, durationMs=0\.900, state=completed/);
  assert.match(summary, /Last device stage: DRAIN.*state=failed/);
  assert.match(summary, /Measured device stage: RTC_STATS, durationMs=0\.050/);
  assert.match(summary, /Active fixture stage: SCORE, durationMs=800\.000.*queueMs=9\.000/);
  assert.match(summary, /ENQUEUE:.*durationMs=0\.001, largestGapMs=10\.000/);
  assert.match(summary, /device clock: android.elapsedRealtimeNanos/);
  assert.match(summary, /fixture clock: host.monotonic_ns/);
});

test("output consumer summary ignores malformed telemetry and arbitrary data", () => {
  const secret = "private URL or credential";
  assert.equal(outputConsumerSummary({ outputConsumerTrace: { clock: secret, stages: [] } }), "");
  const summary = outputConsumerSummary({ outputConsumerTrace: { clock: "android.elapsedRealtimeNanos", observedAtNs: "100",
    guard: { capturedFrames: secret, drainedFrames: 0, pendingFrames: 24001, limitFrames: 24000 },
    stages: [{ stage: secret, enteredNs: "0", exitedNs: "100", completed: true },
      { stage: "HTTP_ACK", enteredNs: "200", exitedNs: "100", completed: true },
      { stage: "RTC_STATS", enteredNs: secret, exitedNs: null, completed: false }] } });
  assert.doesNotMatch(summary, /private|credential|HTTP_ACK|RTC_STATS|pending=/);
});

test("APK preparation and connected tests have independent commands and unchanged test deadlines", () => {
  const f = fixture();
  try {
    buildNativeMediaApks(f.run);
    assert.deepEqual(f.calls, ["EmulatorTest", "Release"].map(build => ({
      program: "gradle", args: ["-p", "android/instrumentation", `:assemble${build}`, `:assemble${build}AndroidTest`,
        `-PmediaTestBuildType=${build.charAt(0).toLowerCase()}${build.slice(1)}`,
        "--no-daemon", "--max-workers=2", "--stacktrace"], timeout: 300_000,
    })));
    runNativeMediaFeasibility(f.output, f.run, "prechecked");
    const tests = f.calls.filter(command => command.args.some(arg => arg.startsWith(":connected")));
    assert.equal(tests.length, 2);
    assert.ok(tests.every(command => command.timeout === 300_000));
  } finally { f.close(); }
});

test("APK build failures are fatal and build/test phases report their own results", () => {
  const f = fixture();
  const previous = process.env.GITHUB_STEP_SUMMARY;
  const path = join(f.output, "summary.md");
  const failure = new Error("cold APK build failed");
  process.env.GITHUB_STEP_SUMMARY = path;
  try {
    assert.throws(() => buildNativeMediaApks(command => { f.run(command); throw failure; }), error => error === failure);
    assert.equal(f.calls.length, 1);
    assert.match(readFileSync(path, "utf8"), /build EmulatorTest APKs[\s\S]*failed/);
    buildNativeMediaApks(f.run);
    runNativeMediaFeasibility(f.output, f.run, "prechecked");
    const summary = readFileSync(path, "utf8");
    assert.match(summary, /build Release APKs[\s\S]*passed/);
    assert.match(summary, /test EmulatorTest[\s\S]*passed/);
    assert.match(summary, /test Release[\s\S]*passed/);
    assert.throws(() => runNativeMediaFeasibility(f.output, command => {
      if (command.program === "gradle") throw failure;
      return f.run(command);
    }, "prechecked"), error => error === failure);
    assert.match(readFileSync(path, "utf8"), /test EmulatorTest\n[\s\S]*\| failed \| [\d.]+ s \|/);
  } finally {
    if (previous === undefined) delete process.env.GITHUB_STEP_SUMMARY;
    else process.env.GITHUB_STEP_SUMMARY = previous;
    f.close();
  }
});

test("host preflight keeps the original controls and deadline and prepared feasibility does not duplicate them", () => {
  const f = fixture();
  try {
    runNativeMediaControls(f.run);
    assert.deepEqual(f.calls[0], { program: "node", args: ["--test",
      "tools/native-media-marker.test.ts", "tools/compare-media-apm.test.ts"], timeout: 30_000 });
    runNativeMediaFeasibility(f.output, f.run, "prechecked");
    assert.equal(f.calls.filter(command => command.program === "node").length, 1);
    assert.equal(f.calls.filter(command => command.program === "gradle").length, 2);
    const failure = new Error("host control failed");
    assert.throws(() => runNativeMediaControls(() => { throw failure; }), error => error === failure);
  } finally { f.close(); }
});

test("media failure summary includes the nested drain cause and actual timeout without changing the verdict", () => {
  const details = { passed: false, step: "answered output", failure: [
    { type: "AssertionError", message: "Details retained in test XML" },
    { type: "IllegalStateException", message: "Output capture consumer fell behind" },
    { type: "TimeoutCancellationException", message: "Timed out waiting for 1000 ms" },
  ] };
  const summary = mediaFailureSummary(details)!;
  assert.match(summary, /Step: answered output/);
  assert.match(summary, /IllegalStateException: Output capture consumer fell behind/);
  assert.match(summary, /Timed out waiting for 1000 ms/);
  assert.equal(mediaFailureSummary({ ...details, passed: true }), undefined);
  assert.equal(mediaFailureSummary({ passed: false, failure: "invalid" }), undefined);
});

test("failed qualification writes nested causes to the CI summary and preserves its original failure", () => {
  const f = fixture();
  const previous = process.env.GITHUB_STEP_SUMMARY;
  const path = join(f.output, "summary.md");
  const failure = new Error("Gradle failed");
  process.env.GITHUB_STEP_SUMMARY = path;
  try {
    assert.throws(() => runNativeMediaFeasibility(f.output, command => {
      const result = f.run(command);
      if (command.program === "gradle") throw failure;
      return command.args.at(-1) === "files/media-hook-results.json" ? JSON.stringify({ ...hookResult(), passed: false, step: "answered output",
        failure: [{ type: "IllegalStateException", message: "Output capture consumer fell behind" }] }) : result;
    }), error => error === failure);
    assert.match(readFileSync(path, "utf8"), /IllegalStateException: Output capture consumer fell behind/);
  } finally {
    if (previous === undefined) delete process.env.GITHUB_STEP_SUMMARY;
    else process.env.GITHUB_STEP_SUMMARY = previous;
    f.close();
  }
});

function hookResult() {
  return { passed: true, step: "end", captureRate: 48000, profile: "tones-v2", pipeline: "observed", failure: [], gateTrace: {} };
}
function captureResult() {
  return { clock: "android.elapsedRealtimeNanos", overflow: false, columns: [], callbacks: [], apmStates: [], rtc: [], postApm: {} };
}

test("mute failure summary distinguishes lock waiting from session work with exact monotonic timestamps", () => {
  const at = 9_007_199_254_740_993n;
  const event = (operationId: string, stage: string, offset: bigint, completed?: number) => ({
    operationId, stage, elapsedRealtimeNanos: (at + offset).toString(), completed,
  });
  const summary = mediaFailureSummary({ passed: false, step: "mute diagnostic",
    failure: [{ type: "TimeoutCancellationException", message: "Timed out waiting for 1000 ms" }], muteTimings: [
      event("1", "muteSerialEntered", 0n), event("1", "muteSerialExited", 1_000_000_000n, 0),
      event("2", "muteSerialEntered", 2_000_000_000n), event("2", "muteSerialExited", 2_001_234_567n, 1),
      event("2", "muteSessionEntered", 2_001_234_568n), event("2", "mutePublicationEntered", 2_002_000_000n),
      event("2", "mutePublicationExited", 2_003_000_000n, 1), event("2", "muteBoundaryEntered", 2_004_000_000n),
    ] })!;
  assert.ok(summary.includes(`| 1 | Serial lock acquire | ${at} | ${at + 1_000_000_000n} | 1000.000 | failed/cancelled |`));
  assert.match(summary, /\| 1 \| Session mute \| unavailable \| unavailable \| unavailable \| not entered \|/);
  assert.match(summary, /\| 2 \| Serial lock acquire .* 1\.234 \| completed \|/);
  assert.match(summary, /\| 2 \| Session mute .* exit not observed \|/);
  assert.match(summary, /\| 2 \| Publication mute .* 1\.000 \| completed \|/);
  assert.match(summary, /\| 2 \| Capture boundary wait .* exit not observed \|/);
  assert.match(summary, /Timed out waiting for 1000 ms/);
});

test("mute summary rejects unsafe fields, bounds evidence and does not invent negative durations", () => {
  const summary = mediaFailureSummary({ passed: false, step: "mute diagnostic", failure: [{ type: "Error", message: "failed" }],
    muteTimings: [null, { operationId: "secret", stage: "muteSerialEntered", elapsedRealtimeNanos: "1" },
      { operationId: "1", stage: "credential", elapsedRealtimeNanos: "1" },
      { operationId: "1", stage: "muteSerialEntered", elapsedRealtimeNanos: 12 },
      { operationId: "1", stage: "muteSerialEntered", elapsedRealtimeNanos: "9223372036854775808" },
      ...Array.from({ length: 100 }, (_, n) => ({ operationId: String(n + 2), stage: "muteSerialEntered", elapsedRealtimeNanos: "2" })),
      { operationId: "101", stage: "muteSerialExited", elapsedRealtimeNanos: "1", completed: 0 },
    ] })!;
  assert.doesNotMatch(summary, /secret|credential|9223372036854775808/);
  assert.equal(summary.split("\n").filter(row => row.startsWith("| ")).length, 34);
  assert.match(summary, /\| 101 \| Serial lock acquire \| 2 \| 1 \| unavailable \| invalid order \|/);
  assert.doesNotMatch(summary, /^\| 2 \|/m);
});

function fixture() {
  const output = mkdtempSync(join(tmpdir(), "native-media-journey-"));
  const calls: Command[] = [];
  const run: Execute = (command) => {
    calls.push(command);
    return command.program === "adb"
      ? JSON.stringify({ name: command.args.at(-1), ...(command.args.at(-1) === "files/media-hook-results.json" ? hookResult()
        : command.args.at(-1) === "files/capture-trace.json" ? captureResult() : {}) })
      : "";
  };
  return {
    output,
    calls,
    run,
    timing: (() => { let now = 0; return { now: () => now, pause: (ms: number) => { now += ms; } }; })(),
    close: () => rmSync(output, { recursive: true, force: true }),
  };
}

test("an unavailable push journal cannot retain a previous run's diagnostic file", () => {
  const f = fixture();
  const failure = new Error("suite failed before push journey");
  const path = join(f.output, "push-fault-events.json");
  try {
    writeFileSync(path, "old push evidence");
    assert.throws(() => runNativeMediaJourney(f.output, command => {
      if (command.program === "gradle") throw failure;
      if (command.args.at(-1) === "files/push-fault-events.json") throw new Error("unavailable");
      return f.run(command);
    }), error => error === failure);
    assert.ok(!existsSync(path));
    assert.ok(!existsSync(`${path}.tmp`));
  } finally { f.close(); }
});

test("runs the complete functional suite once and checks all four results", () => {
  const f = fixture();
  try {
    runNativeMediaJourney(f.output, f.run);
    assert.equal(f.calls.length, 18);
    assert.deepEqual(f.calls[0], {
      program: "gradle",
      args: [
        "-p",
        "android/instrumentation",
        ":connectedDebugAndroidTest",
        "-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true",
        "--stacktrace",
      ],
      timeout: 1_500_000,
    });
    const names = [
      "journey-results",
      "answered-elsewhere-results",
      "native-media-results",
      "native-webrtc-results",
    ];
    for (const [index, name] of names.entries()) {
      assert.deepEqual(f.calls[index * 2 + 1], {
        program: "adb",
        args: [
          "exec-out",
          "run-as",
          "expo.modules.callkittelecom.instrumentation",
          "cat",
          `files/${name}.json`,
        ],
        timeout: 15_000,
        capture: true,
      });
      assert.equal(
        JSON.parse(readFileSync(join(f.output, `${name}.json`), "utf8")).name,
        `files/${name}.json`
      );
      const checker =
        index === 0
          ? "check-android-journey.ts"
          : index === 1
          ? "check-answered-elsewhere.ts"
          : "check-native-media.ts";
      assert.deepEqual(f.calls[index * 2 + 2], {
        program: "node",
        args: [join("tools", checker), join(f.output, `${name}.json`)],
        timeout: 15_000,
      });
    }
    assert.equal(readdirSync(f.output).length, 8);
    assert.deepEqual(f.calls.slice(9), [
      { program: "adb", args: ["exec-out", "run-as", "expo.modules.callkittelecom.instrumentation",
        "cat", "files/push-fault-events.json"], timeout: 15_000, capture: true },
      { program: "node", args: ["tools/android-push-faults.ts", "--file", join(f.output, "push-fault-events.json")], timeout: 15_000 },
      { program: "node", args: ["--test", "tools/check-android-journey-state.test.ts"], timeout: 30_000 },
      { program: "node", args: ["tools/check-android-journey-state.ts"], timeout: 60_000 },
      { program: "node", args: ["--test", "tools/native-media-marker.test.ts", "tools/compare-media-apm.test.ts"], timeout: 30_000 },
      { program: "gradle", args: [
        "-p", "android/instrumentation", ":connectedEmulatorTestAndroidTest", "-PmediaTestBuildType=emulatorTest",
        "-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true",
        "-Pandroid.testInstrumentationRunnerArguments.class=expo.modules.callkittelecom.conversations.MediaHookFeasibilityTest,expo.modules.callkittelecom.conversations.MediaTestIsolationTest",
        "--no-daemon", "--max-workers=2", "--stacktrace",
      ], timeout: 300_000 },
      ...["media-hook-results", "capture-trace"].map((name) => ({
        program: "adb", args: ["exec-out", "run-as", "expo.modules.callkittelecom.instrumentation", "cat", `files/${name}.json`],
        timeout: 1_000, capture: true, diagnostics: true,
      })),
      { program: "gradle", args: [
        "-p", "android/instrumentation", ":connectedReleaseAndroidTest", "-PmediaTestBuildType=release",
        "-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true",
        "-Pandroid.testInstrumentationRunnerArguments.class=expo.modules.callkittelecom.conversations.MediaTestIsolationTest",
        "--no-daemon", "--max-workers=2", "--stacktrace",
      ], timeout: 300_000 },
    ]);
  } finally {
    f.close();
  }
});

for (const build of ["EmulatorTest", "Release"])
  test(`a failed ${build} qualification remains fatal and preserves the ordinary journey evidence`, () => {
    const f = fixture();
    const failure = new Error(`${build} failed`);
    try {
      assert.throws(() => runNativeMediaJourney(f.output, (command) => {
        const result = f.run(command);
        if (command.args.includes(`:connected${build}AndroidTest`)) throw failure;
        return result;
      }), (error) => error === failure);
      assert.equal(readdirSync(f.output).length, 8);
      assert.equal(f.calls.filter((command) => command.program === "gradle").length,
        build === "EmulatorTest" ? 2 : 3);
    } finally { f.close(); }
  });

test("functional-only execution does not start the isolated media qualification", () => {
  const f = fixture();
  try {
    runNativeMediaJourney(f.output, f.run, false);
    assert.equal(f.calls.filter(command => command.program === "gradle").length, 1);
    assert.equal(readdirSync(f.output).length, 5);
  } finally { f.close(); }
});

for (const argument of ["tools/check-android-journey-state.test.ts", "tools/check-android-journey-state.ts"])
  test(`a failed ${argument} remains fatal before media qualification`, () => {
    const f = fixture();
    const failure = new Error("platform state query failed");
    try {
      assert.throws(() => runNativeMediaJourney(f.output, command => {
        const result = f.run(command);
        if (command.args.includes(argument)) throw failure;
        return result;
      }), error => error === failure);
      assert.equal(readdirSync(f.output).length, 5);
      assert.equal(f.calls.filter(command => command.program === "gradle").length, 1);
    } finally { f.close(); }
  });

test("qualification preserves its original failure when capture diagnostics are unavailable", () => {
  const f = fixture();
  const failure = new Error("qualification failed");
  try {
    assert.throws(() => runNativeMediaFeasibility(f.output, command => {
      f.run(command);
      if (command.program === "gradle") throw failure;
      if (command.program === "adb") throw new Error("missing diagnostic");
      return "";
    }, "run", f.timing), error => error === failure);
  } finally { f.close(); }
});

for (const name of ["media-hook-results", "capture-trace"] as const) {
  for (const [label, kind, contents] of [["empty", "empty", ""], ["whitespace-only", "empty", " \r\n\t"],
    ["truncated", "invalid", '{"private":"must-not-appear"']] as const) {
    test(`${name} rejects ${label} JSON with a named sanitized source failure`, () => {
      const f = fixture();
      try {
        assert.throws(() => runNativeMediaFeasibility(f.output, command => {
          const result = f.run(command);
          return command.program === "adb" && command.args.at(-1) === `files/${name}.json` ? contents : result;
        }, "prechecked", f.timing), error => {
          assert.ok(error instanceof Error && !(error instanceof SyntaxError));
          assert.equal(error.message, `Retain EmulatorTest evidence: ${kind} JSON from adb files/${name}.json`);
          assert.doesNotMatch(error.stack ?? "", /must-not-appear/);
          return true;
        });
        assert.ok(!existsSync(join(f.output, `${name}.json`)));
        const other = name === "media-hook-results" ? "capture-trace" : "media-hook-results";
        assert.ok(existsSync(join(f.output, `${other}.json`)), "Keep the other available diagnostic");
        assert.ok(f.calls.filter(command => command.program === "adb").length > 2);
        assert.equal(f.calls.filter(command => command.program === "gradle").length, 1);
      } finally { f.close(); }
    });
  }
}

test("both invalid diagnostic readers report their sources while preserving the original test failure", () => {
  const f = fixture();
  const previous = process.env.GITHUB_STEP_SUMMARY;
  const summary = join(f.output, "summary.md");
  const failure = new Error("instrumentation failed");
  process.env.GITHUB_STEP_SUMMARY = summary;
  try {
    assert.throws(() => runNativeMediaFeasibility(f.output, command => {
      f.run(command);
      if (command.program === "gradle") throw failure;
      return command.args.at(-1) === "files/media-hook-results.json" ? "" : '{"private":"must-not-appear"';
    }, "prechecked", f.timing), error => error === failure);
    const report = readFileSync(summary, "utf8");
    assert.match(report, /Retain EmulatorTest evidence: empty JSON from adb files\/media-hook-results.json/);
    assert.match(report, /Retain EmulatorTest evidence: invalid JSON from adb files\/capture-trace.json/);
    assert.doesNotMatch(report, /SyntaxError|must-not-appear/);
    assert.ok(f.calls.filter(command => command.program === "adb").length > 2);
  } finally {
    if (previous === undefined) delete process.env.GITHUB_STEP_SUMMARY;
    else process.env.GITHUB_STEP_SUMMARY = previous;
    f.close();
  }
});

for (const name of ["media-hook-results", "capture-trace"] as const)
  test(`${name} sanitizes failed adb reads without hiding the other diagnostic`, () => {
    const f = fixture();
    try {
      assert.throws(() => runNativeMediaFeasibility(f.output, command => {
        const result = f.run(command);
        if (command.program === "adb" && command.args.at(-1) === `files/${name}.json`)
          throw new Error("private tool output must-not-appear");
        return result;
      }, "prechecked", f.timing), error => {
        assert.ok(error instanceof Error);
        assert.equal(error.message, `Retain EmulatorTest evidence: could not read JSON from adb files/${name}.json`);
        assert.doesNotMatch(error.stack ?? "", /must-not-appear/);
        return true;
      });
      const other = name === "media-hook-results" ? "capture-trace" : "media-hook-results";
      assert.ok(existsSync(join(f.output, `${other}.json`)));
    } finally { f.close(); }
  });

test("a failed real-fixture marker control remains fatal and retains ordinary results", () => {
  const f = fixture();
  const failure = new Error("marker control failed");
  try {
    assert.throws(() => runNativeMediaJourney(f.output, (command) => {
      const result = f.run(command);
      if (command.args.includes("tools/native-media-marker.test.ts")) throw failure;
      return result;
    }), (error) => error === failure);
    assert.equal(readdirSync(f.output).length, 5);
    assert.equal(f.calls.filter((command) => command.program === "gradle").length, 1);
  } finally { f.close(); }
});

test("retains available media diagnostics after a failed suite and propagates its failure", () => {
  const f = fixture();
  const failure = new Error("suite failed");
  try {
    writeFileSync(join(f.output, "native-webrtc-results.json"), "stale");
    assert.throws(
      () =>
        runNativeMediaJourney(f.output, (command) => {
          if (command.program === "gradle") {
            f.run(command);
            throw failure;
          }
          if (command.args.at(-1) === "files/native-webrtc-results.json")
            throw new Error("unavailable");
          return f.run(command);
        }),
      (error) => error === failure
    );
    assert.deepEqual(readdirSync(f.output).sort(), [
      "answered-elsewhere-results.json",
      "native-media-results.json",
      "push-fault-events.json",
    ]);
    assert.equal(
      f.calls.filter((command) => command.program === "node").length,
      0
    );
  } finally {
    f.close();
  }
});

for (const [index, name] of [
  "journey-results",
  "answered-elsewhere-results",
  "native-media-results",
  "native-webrtc-results",
].entries())
  test(`a failed ${name} check stays fatal while remaining evidence is collected`, () => {
    const f = fixture();
    const failure = new Error("invalid journey evidence");
    try {
      assert.throws(
        () =>
          runNativeMediaJourney(f.output, (command) => {
            const result = f.run(command);
            if (command.args.at(-1) === join(f.output, `${name}.json`))
              throw failure;
            return result;
          }),
        (error) => error === failure
      );
      assert.equal(readdirSync(f.output).length, 5);
      assert.equal(
        f.calls.filter((command) => command.program === "node").length,
        index + 1
      );
      assert.equal(
        f.calls.filter((command) => command.program === "gradle").length,
        1
      );
    } finally {
      f.close();
    }
  });
