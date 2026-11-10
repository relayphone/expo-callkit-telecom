import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { test } from "node:test";
import { fileURLToPath } from "node:url";

import { assertAndroidDnd } from "./check-android-dnd.ts";

const policies: readonly [string, boolean, number][] = [
  ["off-any", true, 1],
  ["on-any", false, 3],
  ["priority-any", true, 2],
  ["priority-none", false, 2],
  ["priority-contacts-unknown", false, 2],
  ["priority-contacts-contact", true, 2],
  ["priority-contacts-opaque", false, 2],
  ["priority-starred-contact", false, 2],
  ["priority-starred-starred", true, 2],
  ["priority-repeat-first", false, 2],
  ["priority-repeat-second", true, 2],
  ["off-restored", true, 1],
];

function fixture() {
  return {
    results: Object.fromEntries(
      policies.map(([name, permitted, filter]) => [
        name,
        {
          passed: true,
          case: name,
          coreAccepted: true,
          coreRingingObserved: true,
          coreCallsAfterCleanup: 0,
          callFilterAllowed: permitted,
          notificationAllowed: permitted,
          ringtonePlaybackObserved: permitted,
          ringVolume: permitted ? 3 : 0,
          interruptionFilter: filter,
          ringerMode: filter === 1 ? 2 : 0,
          notificationCategory: "call",
          soundConfigured: true,
          nativeHookCount: 1,
          nativeAllowedAfterRaw: permitted,
          outcome: {
            status: permitted ? "reported" : "filtered",
            ...(permitted
              ? { id: `native-${name}` }
              : { reason: "doNotDisturb" }),
          },
        },
      ])
    ),
    warm: {
      passed: true,
      prejoinedBeforeFilter: true,
      filteredReason: "doNotDisturb",
      serverClients: 0,
      captureStarted: false,
      nativeClosed: true,
      coreCallsAfterCleanup: 0,
      delayedRejectPreventsAnswerAndNotification: true,
      appReasons: ["blockList", "other"],
    },
  };
}

function check(
  value: { results: Readonly<Record<string, unknown>>; warm: unknown },
  prepare?: (directory: string) => void
) {
  const directory = mkdtempSync(join(tmpdir(), "android-dnd-check-"));
  try {
    for (const [name, result] of Object.entries(value.results))
      writeFileSync(join(directory, `${name}.json`), JSON.stringify(result));
    writeFileSync(
      join(directory, "warm-cleanup.json"),
      JSON.stringify(value.warm)
    );
    prepare?.(directory);
    const run = spawnSync(
      process.execPath,
      [
        fileURLToPath(new URL("./check-android-dnd.ts", import.meta.url)),
        directory,
      ],
      { encoding: "utf8", timeout: 15_000 }
    );
    assert.ifError(run.error);
    return run;
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
}

test("CLI identifies the case and failed rule with assertion details and exits 1", () => {
  const result = fixture();
  result.results["off-any"]!.coreAccepted = false;
  const run = check(result);
  assert.equal(run.status, 1);
  assert.equal(run.stdout, "");
  assert.match(run.stderr, /off-any.coreAccepted must be true/);
  assert.match(run.stderr, /AssertionError/);
  assert.match(run.stderr, /actual:/);
  assert.match(run.stderr, /expected:/);
});

test("accepts all 12 policies and warm cleanup with the existing ordered report", () => {
  const run = check(fixture());
  assert.equal(run.status, 0, run.stderr);
  assert.equal(run.stderr, "");
  assert.equal(
    run.stdout,
    [
      "off-any: Core accepted; notification eligible; native reported",
      "on-any: Core accepted; notification suppressed; native filtered",
      "priority-any: Core accepted; notification eligible; native reported",
      "priority-none: Core accepted; notification suppressed; native filtered",
      "priority-contacts-unknown: Core accepted; notification suppressed; native filtered",
      "priority-contacts-contact: Core accepted; notification eligible; native reported",
      "priority-contacts-opaque: Core accepted; notification suppressed; native filtered",
      "priority-starred-contact: Core accepted; notification suppressed; native filtered",
      "priority-starred-starred: Core accepted; notification eligible; native reported",
      "priority-repeat-first: Core accepted; notification suppressed; native filtered",
      "priority-repeat-second: Core accepted; notification eligible; native reported",
      "off-restored: Core accepted; notification eligible; native reported",
      "Prewarmed DND cleanup and native blockList/other outcomes passed\n",
    ].join("\n")
  );
});

test("all policy cases retain admission, notification, ringtone and filtering controls", () => {
  for (const [name, permitted] of policies) {
    for (const patch of [
      { passed: false },
      { case: "other" },
      { coreAccepted: false },
      { coreRingingObserved: false },
      { coreCallsAfterCleanup: 1 },
      { callFilterAllowed: !permitted },
      { notificationAllowed: !permitted },
      { ringtonePlaybackObserved: !permitted },
      { notificationCategory: "message" },
      { soundConfigured: false },
      { nativeHookCount: 0 },
      { nativeAllowedAfterRaw: !permitted },
      { outcome: { status: "other" } },
    ]) {
      const value = fixture();
      assert.throws(
        () =>
          assertAndroidDnd(
            {
              ...value.results,
              [name]: { ...value.results[name], ...patch },
            },
            value.warm
          ),
        new RegExp(name)
      );
    }
  }
});

test("ring volume and normal ringer mode remain conditional on notification permission", () => {
  for (const [name, permitted, filter] of policies) {
    const value = fixture();
    if (permitted) {
      for (const ringVolume of [0, -1, null, undefined])
        assert.throws(
          () =>
            assertAndroidDnd(
              {
                ...value.results,
                [name]: { ...value.results[name], ringVolume },
              },
              value.warm
            ),
          /ringVolume/
        );
      if (filter === 1)
        assert.throws(
          () =>
            assertAndroidDnd(
              {
                ...value.results,
                [name]: { ...value.results[name], ringerMode: 0 },
              },
              value.warm
            ),
          /ringerMode must equal 2 when interruptionFilter is 1/
        );
      value.results[name]!.ringVolume = 0.5;
    } else {
      const suppressed = Object.fromEntries(
        Object.entries(value.results[name]!).filter(
          ([field]) =>
            !["ringVolume", "interruptionFilter", "ringerMode"].includes(field)
        )
      );
      assertAndroidDnd({ ...value.results, [name]: suppressed }, value.warm);
    }
    assertAndroidDnd(value.results, value.warm);
  }
});

test("native raw admission stays independent of policy permission and reported IDs stay optional", () => {
  for (const [name] of policies) {
    const value = fixture();
    for (const nativeAllowedAfterRaw of [false, true]) {
      const outcome = nativeAllowedAfterRaw
        ? { status: "reported" }
        : { status: "filtered", reason: "doNotDisturb" };
      assertAndroidDnd(
        {
          ...value.results,
          [name]: { ...value.results[name], nativeAllowedAfterRaw, outcome },
        },
        value.warm
      );
    }
    assertAndroidDnd(
      {
        ...value.results,
        [name]: {
          ...value.results[name],
          nativeAllowedAfterRaw: true,
          outcome: {
            status: "reported",
            id: " raw application ID ",
            reason: "extra metadata",
          },
        },
      },
      value.warm
    );
  }
});

test("filtered outcomes retain the typed reason and omit every form of id property", () => {
  for (const [name, permitted] of policies) {
    if (permitted) continue;
    const value = fixture();
    for (const outcome of [
      { status: "filtered" },
      { status: "filtered", reason: "other" },
      { status: "filtered", reason: "doNotDisturb", id: "" },
      { status: "filtered", reason: "doNotDisturb", id: null },
      { status: "filtered", reason: "doNotDisturb", id: undefined },
    ])
      assert.throws(
        () =>
          assertAndroidDnd(
            {
              ...value.results,
              [name]: { ...value.results[name], outcome },
            },
            value.warm
          ),
        /outcome/
      );
  }
});

test("warm cleanup preserves prejoin, closure, no capture, no clients and typed app reasons", () => {
  for (const patch of [
    { passed: false },
    { prejoinedBeforeFilter: false },
    { filteredReason: "other" },
    { serverClients: 1 },
    { captureStarted: true },
    { nativeClosed: false },
    { coreCallsAfterCleanup: 1 },
    { delayedRejectPreventsAnswerAndNotification: false },
    { appReasons: [] },
    { appReasons: ["other", "blockList"] },
    { appReasons: ["blockList", "other", "doNotDisturb"] },
  ]) {
    const value = fixture();
    assert.throws(
      () => assertAndroidDnd(value.results, { ...value.warm, ...patch }),
      /warm-cleanup/
    );
  }
});

test("missing policy files, required fields and malformed evidence remain fatal", () => {
  const value = fixture();
  for (const [name, permitted, filter] of policies) {
    const results: Record<string, unknown> = { ...value.results };
    delete results[name];
    assert.throws(
      () => assertAndroidDnd(results, value.warm),
      new RegExp(name)
    );
    for (const field of [
      "passed",
      "case",
      "coreAccepted",
      "coreRingingObserved",
      "coreCallsAfterCleanup",
      "callFilterAllowed",
      "notificationAllowed",
      "ringtonePlaybackObserved",
      "notificationCategory",
      "soundConfigured",
      "nativeHookCount",
      "nativeAllowedAfterRaw",
      "outcome",
      ...(permitted ? ["ringVolume", "interruptionFilter"] : []),
      ...(permitted && filter === 1 ? ["ringerMode"] : []),
    ]) {
      const row: Record<string, unknown> = { ...value.results[name] };
      delete row[field];
      assert.throws(
        () => assertAndroidDnd({ ...value.results, [name]: row }, value.warm),
        new RegExp(field)
      );
    }
  }
  for (const field of Object.keys(value.warm)) {
    const warm: Record<string, unknown> = { ...value.warm };
    delete warm[field];
    assert.throws(
      () => assertAndroidDnd(value.results, warm),
      new RegExp(field)
    );
  }
  for (const root of [null, [], "invalid", 1]) {
    assert.throws(
      () => assertAndroidDnd(root, value.warm),
      /results must be an object/
    );
    assert.throws(
      () => assertAndroidDnd(value.results, root),
      /warm-cleanup must be an object/
    );
    assert.throws(
      () => assertAndroidDnd({ ...value.results, "off-any": root }, value.warm),
      /off-any must be an object/
    );
  }
});

test("CLI shows conditional, filtered and warm-cleanup failures with their assertion values", () => {
  const volume = fixture();
  volume.results["off-any"]!.ringVolume = 0;
  const reason = fixture();
  reason.results["on-any"]!.outcome = { status: "filtered", reason: "other" };
  const warm = fixture();
  warm.warm.captureStarted = true;
  for (const [value, rule] of [
    [volume, /off-any.ringVolume must be positive/],
    [reason, /on-any.outcome.reason must equal doNotDisturb/],
    [warm, /warm-cleanup.captureStarted must be false/],
  ] as const) {
    const run = check(value);
    assert.equal(run.status, 1);
    assert.equal(run.stdout, "");
    assert.match(run.stderr, rule);
    assert.match(run.stderr, /AssertionError/);
    assert.match(run.stderr, /actual:/);
    assert.match(run.stderr, /expected:/);
  }
});

test("CLI keeps invalid JSON brief and reports missing files and directory argument", () => {
  const malformed = check(fixture(), (directory) => {
    writeFileSync(join(directory, "on-any.json"), "invalid JSON");
  });
  assert.equal(malformed.status, 1);
  assert.equal(malformed.stdout, "");
  assert.equal(malformed.stderr, "Android DnD evidence is not valid JSON.\n");
  const missing = check(fixture(), (directory) => {
    rmSync(join(directory, "warm-cleanup.json"));
  });
  assert.equal(missing.status, 1);
  assert.match(missing.stderr, /ENOENT.*warm-cleanup.json/);
  const run = spawnSync(
    process.execPath,
    [fileURLToPath(new URL("./check-android-dnd.ts", import.meta.url))],
    {
      encoding: "utf8",
      timeout: 15_000,
    }
  );
  assert.ifError(run.error);
  assert.equal(run.status, 1);
  assert.match(run.stderr, /Missing Android DnD evidence directory/);
});
