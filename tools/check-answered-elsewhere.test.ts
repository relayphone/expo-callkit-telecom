import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { test } from "node:test";
import { fileURLToPath } from "node:url";

import { assertAnsweredElsewhereJourney } from "./check-answered-elsewhere.ts";

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

function checkAnsweredElsewhere(result: unknown) {
  return runChecker(JSON.stringify(result));
}

function runChecker(contents: string) {
  const directory = mkdtempSync(join(tmpdir(), "answered-elsewhere-check-"));
  try {
    const path = join(directory, "result.json");
    writeFileSync(path, contents);
    const run = spawnSync(
      process.execPath,
      [
        fileURLToPath(
          new URL("./check-answered-elsewhere.ts", import.meta.url)
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

test("accepts inclusive bounds, unordered samples and unmodified raw identities", () => {
  const result = answeredElsewhereFixture();
  const sample = result.samples[0]!;
  for (const field of [
    "terminalSnapshotNanos",
    "eventNanos",
    "emptyHookNanos",
    "notificationGoneNanos",
  ] as const)
    sample[field] = sample.signalNanos + 1_000_000_000;
  sample.platformGoneNanos = sample.signalNanos + 3_000_000_000;
  sample.platformPolls.at(-1)!.returnedNanos = sample.platformGoneNanos;
  sample.uiGoneNanos = sample.signalNanos + 3_000_000_000;
  sample.eventId = "";
  sample.serverCallId = " raw application ID ";
  result.samples.reverse();
  const report = assertAnsweredElsewhereJourney(result);
  assert.equal(report.length, 7);
  assert.equal(
    report.at(-1),
    "Six samples passed: app maximum 1000.0 ms and recorded disconnect maximum 1000 ms (1000 ms budget); tracked removal observed maximum 3000.0 ms; UI maximum 3000.0 ms (3000 ms observation budget, configured heads-up minimum 2000 ms)"
  );

  result.headsUpMinimumMs = 4000;
  result.uiBudgetMs = 5000;
  sample.platformGoneNanos = sample.signalNanos + 5_000_000_000;
  sample.platformPolls.at(-1)!.returnedNanos = sample.platformGoneNanos;
  sample.uiGoneNanos = sample.signalNanos + 5_000_000_000;
  assertAnsweredElsewhereJourney(result);
});

test("only the final two UI scans establish absence, and a single terminal poll may establish removal", () => {
  const result = answeredElsewhereFixture();
  const sample = result.samples[0]!;
  sample.uiScanTimings.unshift({
    requestedNanos: sample.signalNanos,
    returnedNanos: sample.signalNanos,
    sameWindow: false,
    complete: false,
    callerPresent: true,
  });
  const finalPoll = sample.platformPolls.at(-1)!;
  sample.platformPolls = [finalPoll];
  sample.platformDisconnectObservedNanos = finalPoll.returnedNanos;
  assertAnsweredElsewhereJourney(result);
});

type Evidence = ReturnType<typeof answeredElsewhereFixture>;
type Sample = Evidence["samples"][number];

test("rejects changed metadata, missing identities and duplicate host/push coverage", () => {
  for (const patch of [
    { passed: false },
    { passed: 1 },
    { nativeBudgetMs: 1001 },
    { timelineTimestampFormat: "other" },
    { timelineTimeZone: "" },
    { platformTimeZone: "" },
    { headsUpMinimumMs: -1 },
    { headsUpMinimumMs: 2000.5 },
    { uiObserverAllowanceMs: 1001 },
    { uiBudgetMs: 3001 },
    { samples: [] },
    { samples: null },
  ])
    assert.throws(() =>
      assertAnsweredElsewhereJourney({
        ...answeredElsewhereFixture(),
        ...patch,
      })
    );
  const changes: ((value: Evidence) => void)[] = [
    (r) => {
      r.samples.pop();
    },
    (r) => {
      r.samples[0]!.source = "other";
    },
    (r) => {
      r.samples[0]!.sampleIndex = 4;
    },
    (r) => {
      r.samples[0]!.sampleIndex = 2;
    },
    (r) => {
      r.samples[0]!.eventId = r.samples[1]!.eventId;
    },
    (r) => {
      r.samples[0]!.serverCallId = r.samples[1]!.serverCallId;
    },
  ];
  for (const change of changes) {
    const result = answeredElsewhereFixture();
    change(result);
    assert.throws(() => assertAnsweredElsewhereJourney(result));
  }
});

test("every sample retains SystemUI identity, call cause and native ordering controls", () => {
  const changes: ((sample: Sample) => void)[] = [
    (s) => {
      s.uiBefore.packageName = "other";
    },
    (s) => {
      s.uiBefore.windowId = -1;
    },
    (s) => {
      s.uiBefore.windowId = 1.5;
    },
    (s) => {
      s.uiBefore.callerVisible = false;
    },
    (s) => {
      s.uiBefore.answerVisible = false;
    },
    (s) => {
      s.uiBefore.declineVisible = false;
    },
    (s) => {
      s.uiAbsenceObservations = 1;
    },
    (s) => {
      s.uiAbsentWindowId = 8;
    },
    (s) => {
      s.nativeEvidence = "tracked-call-only";
    },
    (s) => {
      s.nativeCode = 11;
    },
    (s) => {
      s.nativeReason = "remoteEnded";
    },
    (s) => {
      s.correlatedEvents = 2;
    },
    (s) => {
      s.endHandlerCalls = 1;
    },
    (s) => {
      s.incomingNanos = s.reportedNanos + 1;
    },
    (s) => {
      s.reportedNanos = s.visibleBeforeNanos + 1;
    },
    (s) => {
      s.visibleBeforeNanos = s.signalNanos + 1;
    },
    (s) => {
      s.terminalSnapshotNanos = s.signalNanos - 1;
    },
    (s) => {
      s.eventNanos = s.terminalSnapshotNanos - 1;
    },
    (s) => {
      s.emptyHookNanos = s.eventNanos - 1;
    },
    (s) => {
      s.acceptedNanos = s.signalNanos - 1;
    },
    (s) => {
      s.notificationGoneNanos = s.signalNanos - 1;
    },
    (s) => {
      s.signalWallMillis += 0.5;
    },
    (s) => {
      s.platformDisconnectWallMillis += 0.5;
    },
    (s) => {
      s.platformDisconnectObservedNanos = s.signalNanos - 1;
    },
    (s) => {
      s.platformDisconnectObservedNanos = s.platformGoneNanos + 1;
    },
  ];
  for (let index = 0; index < 6; index++)
    for (const change of changes) {
      const result = answeredElsewhereFixture();
      change(result.samples[index]!);
      assert.throws(
        () => assertAnsweredElsewhereJourney(result),
        change.toString()
      );
    }
});

test("all Telecom polls retain ordering, typed flags and independently correlated disconnect receipt", () => {
  const changes: ((sample: Sample) => void)[] = [
    (s) => {
      s.platformPolls = [];
    },
    (s) => {
      s.platformPolls[0]!.requestedNanos = s.signalNanos - 1;
    },
    (s) => {
      s.platformPolls[1]!.requestedNanos =
        s.platformPolls[0]!.returnedNanos - 1;
    },
    (s) => {
      s.platformPolls[0]!.returnedNanos =
        s.platformPolls[0]!.requestedNanos - 1;
    },
    (s) => {
      s.platformPolls.at(-1)!.returnedNanos = s.platformGoneNanos + 1;
    },
    (s) => {
      s.platformPolls.at(-1)!.trackedCall = true;
    },
    (s) => {
      s.platformPolls.at(-1)!.disconnectEventFound = false;
    },
    (s) => {
      s.platformPolls.at(-1)!.returnedNanos--;
    },
    (s) => {
      s.platformDisconnectObservedNanos++;
    },
    (s) => {
      s.platformPolls[1]!.remoteCodeFound = false;
    },
    (s) => {
      s.platformPolls[1]!.answeredReasonFound = false;
    },
    (s) => {
      s.platformPolls[1]!.recordedDisconnectWallMillis!--;
    },
    (s) => {
      s.platformPolls[1]!.recordedDisconnectElapsedMs!--;
    },
    (s) => {
      s.platformPolls.at(-1)!.remoteCodeFound = false;
    },
    (s) => {
      s.platformPolls.at(-1)!.answeredReasonFound = false;
    },
    (s) => {
      s.platformPolls.at(-1)!.recordedDisconnectWallMillis!--;
    },
    (s) => {
      s.platformPolls.at(-1)!.recordedDisconnectElapsedMs!--;
    },
  ];
  for (const change of changes) {
    const result = answeredElsewhereFixture();
    change(result.samples[0]!);
    assert.throws(
      () => assertAnsweredElsewhereJourney(result),
      change.toString()
    );
  }
  for (const patch of [
    { trackedCall: 0 },
    { disconnectEventFound: 0 },
    { remoteCodeFound: 1 },
    { answeredReasonFound: 1 },
  ]) {
    const result = answeredElsewhereFixture();
    const sample = result.samples[0]!;
    assert.throws(() =>
      assertAnsweredElsewhereJourney({
        ...result,
        samples: [
          {
            ...sample,
            platformPolls: [
              sample.platformPolls[0],
              { ...sample.platformPolls[1], ...patch },
              sample.platformPolls[2],
            ],
          },
          ...result.samples.slice(1),
        ],
      })
    );
  }
});

test("UI scan duration, coverage and final absence controls remain fatal", () => {
  const changes: ((sample: Sample) => void)[] = [
    (s) => {
      s.firstUiCallerAbsentNanos = s.signalNanos - 1;
    },
    (s) => {
      s.firstUiCallerAbsentNanos = s.uiGoneNanos + 1;
    },
    (s) => {
      s.uiScans = 1;
    },
    (s) => {
      s.uiCompleteScans = 1;
    },
    (s) => {
      s.maximumUiScanNanos = -1;
    },
    (s) => {
      s.uiScanTimings[0]!.requestedNanos = s.signalNanos - 1;
    },
    (s) => {
      s.uiScanTimings[0]!.returnedNanos =
        s.uiScanTimings[0]!.requestedNanos - 1;
    },
    (s) => {
      s.uiScanTimings[1]!.returnedNanos = s.uiGoneNanos + 1;
    },
    (s) => {
      s.uiScanTimings[0]!.requestedNanos--;
    },
    (s) => {
      s.uiScanTimings.pop();
    },
    (s) => {
      s.uiScanTimings[0]!.sameWindow = false;
    },
    (s) => {
      s.uiScanTimings[0]!.complete = false;
    },
    (s) => {
      s.uiScanTimings[0]!.callerPresent = true;
    },
    (s) => {
      s.uiScanTimings[1]!.sameWindow = false;
    },
    (s) => {
      s.uiScanTimings[1]!.complete = false;
    },
    (s) => {
      s.uiScanTimings[1]!.callerPresent = true;
    },
  ];
  for (const change of changes) {
    const result = answeredElsewhereFixture();
    change(result.samples[0]!);
    assert.throws(
      () => assertAnsweredElsewhereJourney(result),
      change.toString()
    );
  }
});

test("malformed evidence reports the field it cannot validate", () => {
  for (const value of [null, [], 1, "passed"])
    assert.throws(
      () => assertAnsweredElsewhereJourney(value),
      /evidence must be an object/
    );
  const result = answeredElsewhereFixture();
  for (const [field, value] of [
    ["signalNanos", "invalid"],
    ["signalNanos", NaN],
    ["signalNanos", Infinity],
    ["uiBefore", null],
    ["platformPolls", null],
    ["uiScanTimings", null],
    ["eventId", undefined],
    ["serverCallId", undefined],
  ] as const)
    assert.throws(
      () =>
        assertAnsweredElsewhereJourney({
          ...result,
          samples: [
            { ...result.samples[0], [field]: value },
            ...result.samples.slice(1),
          ],
        }),
      new RegExp(field)
    );
});

test("CLI prints metadata, timing, poll and UI assertion details and exits exactly 1", () => {
  const timing = answeredElsewhereFixture();
  timing.samples[0]!.notificationGoneNanos =
    timing.samples[0]!.signalNanos + 1_001_000_000;
  const poll = answeredElsewhereFixture();
  poll.samples[0]!.platformPolls[1]!.recordedDisconnectElapsedMs = 999;
  const ui = answeredElsewhereFixture();
  ui.samples[0]!.uiScanTimings[1]!.callerPresent = true;
  const malformed = answeredElsewhereFixture();
  const cases: readonly [unknown, RegExp][] = [
    [
      { ...answeredElsewhereFixture(), nativeBudgetMs: 1001 },
      /nativeBudgetMs must equal 1000/,
    ],
    [
      timing,
      /host\/1.notificationGoneNanos: 1001 ms must be within 0\.\.1000 ms/,
    ],
    [poll, /host\/1.platformPolls\[1\].recordedDisconnectElapsedMs must match/],
    [ui, /host\/1.finalUiScans\[1\].callerPresent must be false/],
    [
      {
        ...malformed,
        samples: [
          { ...malformed.samples[0], signalNanos: "invalid" },
          ...malformed.samples.slice(1),
        ],
      },
      /host\/1.signalNanos must be an exact integer/,
    ],
  ];
  for (const [value, rule] of cases) {
    const run = checkAnsweredElsewhere(value);
    assert.equal(run.status, 1);
    assert.equal(run.stdout, "");
    assert.match(run.stderr, rule);
    assert.match(run.stderr, /AssertionError/);
    assert.match(run.stderr, /actual:/);
    assert.match(run.stderr, /expected:/);
  }
});

test("CLI keeps invalid JSON brief and reports a missing evidence path", (t) => {
  const directory = mkdtempSync(join(tmpdir(), "answered-elsewhere-check-"));
  t.after(() => rmSync(directory, { recursive: true, force: true }));
  const file = join(directory, "invalid.json");
  writeFileSync(file, "invalid JSON");
  const checker = fileURLToPath(
    new URL("./check-answered-elsewhere.ts", import.meta.url)
  );
  const malformed = spawnSync(process.execPath, [checker, file], {
    encoding: "utf8",
    timeout: 15_000,
  });
  assert.ifError(malformed.error);
  assert.equal(malformed.status, 1);
  assert.equal(malformed.stdout, "");
  assert.equal(
    malformed.stderr,
    "Answered-elsewhere evidence is not valid JSON.\n"
  );
  const missing = spawnSync(process.execPath, [checker], {
    encoding: "utf8",
    timeout: 15_000,
  });
  assert.ifError(missing.error);
  assert.equal(missing.status, 1);
  assert.match(missing.stderr, /Missing answered-elsewhere evidence path/);
});

test("CLI preserves nanosecond deadline and ordering controls beyond Number precision", () => {
  const offset = 9_007_199_254_740_992n;
  function shift(value: unknown): unknown {
    if (Array.isArray(value)) return value.map(shift);
    if (value === null || typeof value !== "object") return value;
    return Object.fromEntries(
      Object.entries(value).map(([key, entry]: [string, unknown]) => [
        key,
        key.endsWith("Nanos") &&
        key !== "maximumUiScanNanos" &&
        typeof entry === "number"
          ? BigInt(entry) + offset
          : shift(entry),
      ])
    );
  }
  const cases: readonly [(sample: Sample) => void, RegExp | undefined][] = [
    [
      (s) => {
        s.notificationGoneNanos = s.signalNanos + 1_000_000_000;
      },
      undefined,
    ],
    [
      (s) => {
        s.notificationGoneNanos = s.signalNanos + 1_000_000_001;
      },
      /1000\.000001 ms must be within 0\.\.1000 ms/,
    ],
    [
      (s) => {
        s.incomingNanos = s.reportedNanos + 1;
      },
      /incomingNanos <= reportedNanos/,
    ],
    [
      (s) => {
        s.platformPolls.at(-1)!.returnedNanos--;
      },
      /final Telecom poll must return at platformGoneNanos/,
    ],
    [
      (s) => {
        s.uiScanTimings[0]!.requestedNanos--;
      },
      /scan duration must not exceed maximumUiScanNanos/,
    ],
  ];
  for (const [change, rule] of cases) {
    const result = answeredElsewhereFixture();
    change(result.samples[0]!);
    const contents = JSON.stringify(shift(result), (_key, value: unknown) =>
      typeof value === "bigint" ? `${value}n` : value
    ).replace(/"(-?\d+)n"/g, "$1");
    const run = runChecker(contents);
    assert.equal(run.status, rule ? 1 : 0, run.stderr);
    if (rule) assert.match(run.stderr, rule);
  }
});

test("timing reports retain ties-to-even rounding without changing nearby fractions", () => {
  const result = answeredElsewhereFixture();
  const sample = result.samples[0]!;
  sample.platformDisconnectObservedNanos = sample.signalNanos + 1_200_250_000;
  sample.platformPolls[1]!.returnedNanos =
    sample.platformDisconnectObservedNanos;
  sample.uiGoneNanos = sample.signalNanos + 2_000_250_000;
  sample.notificationGoneNanos = sample.signalNanos + 40_250_000;
  const report = assertAnsweredElsewhereJourney(result);
  assert.match(
    report[0]!,
    /event observed 1200\.2 ms;.*visible ring removed 2000\.2 ms/
  );
  assert.match(report.at(-1)!, /app maximum 40\.2 ms/);
  sample.notificationGoneNanos = sample.signalNanos + 40_150_000;
  assert.match(
    assertAnsweredElsewhereJourney(result).at(-1)!,
    /app maximum 40\.1 ms/
  );
});
