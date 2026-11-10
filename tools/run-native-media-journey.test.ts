import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import {
  mkdtempSync,
  readFileSync,
  readdirSync,
  rmSync,
  writeFileSync,
} from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { test } from "node:test";
import { fileURLToPath } from "node:url";

import {
  runNativeMediaJourney,
  type Command,
  type Execute,
} from "./run-native-media-journey.ts";

function fixture() {
  const output = mkdtempSync(join(tmpdir(), "native-media-journey-"));
  const calls: Command[] = [];
  const run: Execute = (command) => {
    calls.push(command);
    return command.program === "adb"
      ? JSON.stringify({ name: command.args.at(-1) })
      : "";
  };
  return {
    output,
    calls,
    run,
    close: () => rmSync(output, { recursive: true, force: true }),
  };
}

test("runs the complete functional suite once and checks all four results", () => {
  const f = fixture();
  try {
    runNativeMediaJourney(f.output, f.run);
    assert.equal(f.calls.length, 9);
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
          ? "check-android-journey.py"
          : index === 1
          ? "check-answered-elsewhere.py"
          : "check-native-media.ts";
      assert.deepEqual(f.calls[index * 2 + 2], {
        program: index < 2 ? "python3" : "node",
        args: [join("tools", checker), join(f.output, `${name}.json`)],
        timeout: 15_000,
      });
    }
    assert.equal(readdirSync(f.output).length, 4);
  } finally {
    f.close();
  }
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
    ]);
    assert.equal(
      f.calls.filter(
        (command) => command.program === "python3" || command.program === "node"
      ).length,
      0
    );
  } finally {
    f.close();
  }
});

for (const checker of ["python3", "node"] as const)
  test(`a ${checker} checker failure stays fatal while remaining evidence is collected`, () => {
    const f = fixture();
    const failure = new Error("invalid journey evidence");
    try {
      assert.throws(
        () =>
          runNativeMediaJourney(f.output, (command) => {
            const result = f.run(command);
            if (command.program === checker) throw failure;
            return result;
          }),
        (error) => error === failure
      );
      assert.equal(readdirSync(f.output).length, 4);
      assert.equal(
        f.calls.filter(
          (command) =>
            command.program === "python3" || command.program === "node"
        ).length,
        checker === "python3" ? 1 : 3
      );
      assert.equal(
        f.calls.filter((command) => command.program === "gradle").length,
        1
      );
    } finally {
      f.close();
    }
  });

interface TelecomPoll {
  requestedNanos: number;
  returnedNanos: number;
  trackedCall: boolean;
  disconnectEventFound: boolean;
  remoteCodeFound?: boolean;
  answeredReasonFound?: boolean;
  recordedDisconnectWallMillis?: number;
  recordedDisconnectElapsedMs?: number;
}

function answeredElsewhereFixture() {
  const samples = ["host", "push"].flatMap((source) =>
    [1, 2, 3].map((sampleIndex) => {
      const start = 10_000_000_000;
      const wall = 1_794_235_200_000;
      const nanos = (ms: number) => start + ms * 1_000_000;
      const event = {
        disconnectEventFound: true,
        remoteCodeFound: true,
        answeredReasonFound: true,
        recordedDisconnectWallMillis: wall + 1000,
        recordedDisconnectElapsedMs: 1000,
      };
      const platformPolls: TelecomPoll[] = [
        {
          requestedNanos: nanos(50),
          returnedNanos: nanos(60),
          trackedCall: true,
          disconnectEventFound: false,
        },
        {
          requestedNanos: nanos(1190),
          returnedNanos: nanos(1200),
          trackedCall: true,
          ...event,
        },
        {
          requestedNanos: nanos(1490),
          returnedNanos: nanos(1500),
          trackedCall: false,
          ...event,
        },
      ];
      return {
        source,
        sampleIndex,
        eventId: `${source}-${sampleIndex}`,
        serverCallId: `${source}-${sampleIndex}`,
        uiBefore: {
          packageName: "com.android.systemui",
          windowId: 7,
          callerVisible: true,
          answerVisible: true,
          declineVisible: true,
        },
        uiAbsenceObservations: 2,
        uiAbsentWindowId: 7,
        nativeEvidence: "telecom-historical-SET_DISCONNECTED",
        nativeCode: 3,
        nativeReason: "answeredElsewhere",
        correlatedEvents: 1,
        endHandlerCalls: 0,
        incomingNanos: start - 1000,
        reportedNanos: start - 500,
        visibleBeforeNanos: start - 100,
        signalNanos: start,
        signalWallMillis: wall,
        acceptedNanos: nanos(1),
        terminalSnapshotNanos: nanos(10),
        eventNanos: nanos(20),
        emptyHookNanos: nanos(30),
        notificationGoneNanos: nanos(40),
        platformDisconnectWallMillis: wall + 1000,
        platformDisconnectObservedNanos: nanos(1200),
        platformGoneNanos: nanos(1500),
        platformPolls,
        uiGoneNanos: nanos(2000),
        firstUiCallerAbsentNanos: nanos(1980),
        uiScans: 2,
        uiCompleteScans: 2,
        maximumUiScanNanos: 10_000_000,
        uiScanTimings: [1970, 1990].map((ms) => ({
          requestedNanos: nanos(ms),
          returnedNanos: nanos(ms + 10),
          sameWindow: true,
          complete: true,
          callerPresent: false,
        })),
      };
    })
  );
  return {
    passed: true,
    nativeBudgetMs: 1000,
    headsUpMinimumMs: 2000,
    uiObserverAllowanceMs: 1000,
    uiBudgetMs: 3000,
    timelineTimestampFormat: "HH:mm:ss.SSS",
    timelineTimeZone: "Etc/UTC",
    platformTimeZone: "Etc/UTC",
    samples,
  };
}

function checkAnsweredElsewhere(
  result: ReturnType<typeof answeredElsewhereFixture>
) {
  const directory = mkdtempSync(join(tmpdir(), "answered-elsewhere-check-"));
  try {
    const path = join(directory, "result.json");
    writeFileSync(path, JSON.stringify(result));
    const run = spawnSync(
      "python3",
      [
        fileURLToPath(
          new URL("./check-answered-elsewhere.py", import.meta.url)
        ),
        path,
      ],
      { encoding: "utf8", timeout: 15_000 }
    );
    if (run.error) throw run.error;
    return run;
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
}

test("accepts a recorded disconnect at 1000 ms with later event and removal observations", () => {
  const run = checkAnsweredElsewhere(answeredElsewhereFixture());
  assert.equal(run.status, 0, run.stderr);
  assert.match(
    run.stdout,
    /recorded 1000 ms; event observed 1200.0 ms; tracked call removal observed 1500.0 ms/
  );
});

test("a recorded disconnect after 1000 ms or before the signal fails", () => {
  for (const delay of [1001, -1]) {
    const result = answeredElsewhereFixture();
    const sample = result.samples[0]!;
    sample.platformDisconnectWallMillis = sample.signalWallMillis + delay;
    for (const poll of sample.platformPolls)
      if (poll.disconnectEventFound) {
        poll.recordedDisconnectWallMillis = sample.platformDisconnectWallMillis;
        poll.recordedDisconnectElapsedMs = delay;
      }
    assert.notEqual(checkAnsweredElsewhere(result).status, 0);
  }
});

test("all four app-side steps retain the 1000 ms limit", () => {
  for (const field of [
    "terminalSnapshotNanos",
    "eventNanos",
    "emptyHookNanos",
    "notificationGoneNanos",
  ] as const) {
    const result = answeredElsewhereFixture();
    const sample = result.samples[0]!;
    sample[field] = sample.signalNanos + 1_001_000_000;
    if (field === "terminalSnapshotNanos")
      sample.eventNanos = sample.signalNanos + 1_002_000_000;
    if (field === "terminalSnapshotNanos" || field === "eventNanos")
      sample.emptyHookNanos = sample.signalNanos + 1_003_000_000;
    assert.notEqual(checkAnsweredElsewhere(result).status, 0, field);
  }
});

test("tracked removal and visible removal retain the existing observation budget", () => {
  for (const field of ["platformGoneNanos", "uiGoneNanos"] as const) {
    const result = answeredElsewhereFixture();
    result.samples[0]![field] = result.samples[0]!.signalNanos + 3_001_000_000;
    assert.notEqual(checkAnsweredElsewhere(result).status, 0, field);
  }
});

test("missing polls, a tracked final call, absent event and invalid cause fail", () => {
  const changes = [
    (s: ReturnType<typeof answeredElsewhereFixture>["samples"][number]) => {
      s.platformPolls = [];
    },
    (s: ReturnType<typeof answeredElsewhereFixture>["samples"][number]) => {
      s.platformPolls.at(-1)!.trackedCall = true;
    },
    (s: ReturnType<typeof answeredElsewhereFixture>["samples"][number]) => {
      s.platformPolls.at(-1)!.disconnectEventFound = false;
    },
    (s: ReturnType<typeof answeredElsewhereFixture>["samples"][number]) => {
      s.nativeCode = 11;
    },
    (s: ReturnType<typeof answeredElsewhereFixture>["samples"][number]) => {
      s.nativeReason = "remoteEnded";
    },
    (s: ReturnType<typeof answeredElsewhereFixture>["samples"][number]) => {
      s.platformPolls.at(-1)!.remoteCodeFound = false;
    },
    (s: ReturnType<typeof answeredElsewhereFixture>["samples"][number]) => {
      s.platformPolls.at(-1)!.recordedDisconnectElapsedMs = 999;
    },
  ];
  for (const change of changes) {
    const result = answeredElsewhereFixture();
    change(result.samples[0]!);
    assert.notEqual(checkAnsweredElsewhere(result).status, 0);
  }
});
