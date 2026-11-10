import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { join } from "node:path";

const policies = [
  ["off-any", true],
  ["on-any", false],
  ["priority-any", true],
  ["priority-none", false],
  ["priority-contacts-unknown", false],
  ["priority-contacts-contact", true],
  ["priority-contacts-opaque", false],
  ["priority-starred-contact", false],
  ["priority-starred-starred", true],
  ["priority-repeat-first", false],
  ["priority-repeat-second", true],
  ["off-restored", true],
] as const;

function object(value: unknown, field: string): Record<string, unknown> {
  assert.ok(
    value !== null && typeof value === "object" && !Array.isArray(value),
    `${field} must be an object`
  );
  return value as Record<string, unknown>;
}

function number(value: unknown, field: string): number {
  assert.ok(
    typeof value === "number" && Number.isFinite(value),
    `${field} must be a finite number`
  );
  return value;
}

/** Validate API35 Core admission, DnD policy, typed filtering and warm cleanup. */
export function assertAndroidDnd(results: unknown, cleanup: unknown): string[] {
  const values = object(results, "Android DnD results");
  const report: string[] = [];
  for (const [name, permitted] of policies) {
    const value = object(values[name], name);
    assert.equal(value.passed, true, `${name}.passed must be true`);
    assert.equal(
      value.case,
      name,
      `${name}.case must match the expected policy case`
    );
    for (const field of [
      "coreAccepted",
      "coreRingingObserved",
      "soundConfigured",
    ])
      assert.equal(value[field], true, `${name}.${field} must be true`);
    assert.equal(
      value.coreCallsAfterCleanup,
      0,
      `${name}.coreCallsAfterCleanup must equal 0`
    );
    for (const field of [
      "callFilterAllowed",
      "notificationAllowed",
      "ringtonePlaybackObserved",
    ])
      assert.equal(
        value[field],
        permitted,
        `${name}.${field} must be ${permitted}`
      );
    if (permitted) {
      assert.ok(
        number(value.ringVolume, `${name}.ringVolume`) > 0,
        `${name}.ringVolume must be positive`
      );
      if (number(value.interruptionFilter, `${name}.interruptionFilter`) === 1)
        assert.equal(
          value.ringerMode,
          2,
          `${name}.ringerMode must equal 2 when interruptionFilter is 1`
        );
    }
    assert.equal(
      value.notificationCategory,
      "call",
      `${name}.notificationCategory must equal call`
    );
    assert.equal(
      value.nativeHookCount,
      1,
      `${name}.nativeHookCount must equal 1`
    );
    assert.equal(
      typeof value.nativeAllowedAfterRaw,
      "boolean",
      `${name}.nativeAllowedAfterRaw must be a boolean`
    );
    const outcome = object(value.outcome, `${name}.outcome`);
    assert.equal(
      outcome.status,
      value.nativeAllowedAfterRaw ? "reported" : "filtered",
      `${name}.outcome.status must match nativeAllowedAfterRaw`
    );
    if (outcome.status === "filtered") {
      assert.equal(
        outcome.reason,
        "doNotDisturb",
        `${name}.outcome.reason must equal doNotDisturb`
      );
      assert.ok(
        !Object.hasOwn(outcome, "id"),
        `${name}.outcome must not contain an id when filtered`
      );
    }
    report.push(
      `${name}: Core accepted; notification ${
        permitted ? "eligible" : "suppressed"
      }; native ${outcome.status}`
    );
  }
  const warm = object(cleanup, "warm-cleanup");
  for (const [field, expected] of [
    ["passed", true],
    ["prejoinedBeforeFilter", true],
    ["captureStarted", false],
    ["nativeClosed", true],
    ["delayedRejectPreventsAnswerAndNotification", true],
  ] as const)
    assert.equal(
      warm[field],
      expected,
      `warm-cleanup.${field} must be ${expected}`
    );
  assert.equal(
    warm.filteredReason,
    "doNotDisturb",
    "warm-cleanup.filteredReason must equal doNotDisturb"
  );
  for (const field of ["serverClients", "coreCallsAfterCleanup"])
    assert.equal(warm[field], 0, `warm-cleanup.${field} must equal 0`);
  assert.deepEqual(
    warm.appReasons,
    ["blockList", "other"],
    "warm-cleanup.appReasons must equal blockList, other in order"
  );
  report.push(
    "Prewarmed DND cleanup and native blockList/other outcomes passed"
  );
  return report;
}

if (import.meta.main) {
  try {
    const directory = process.argv[2];
    assert.ok(directory, "Missing Android DnD evidence directory");
    const read = (name: string): unknown =>
      JSON.parse(readFileSync(join(directory, `${name}.json`), "utf8"));
    const results = Object.fromEntries(
      policies.map(([name]) => [name, read(name)])
    );
    console.log(assertAndroidDnd(results, read("warm-cleanup")).join("\n"));
  } catch (error) {
    if (error instanceof SyntaxError)
      console.error("Android DnD evidence is not valid JSON.");
    else console.error(error);
    process.exitCode = 1;
  }
}
