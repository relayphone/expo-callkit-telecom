import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { test } from "node:test";
import { fileURLToPath } from "node:url";

import { assertExampleJourney } from "./check-example-journey.ts";

function fixture() {
  return {
    passed: true,
    steps: [
      "ringing",
      "connecting",
      "connected",
      "held",
      "connected",
      "incoming-ended",
      "dialing",
      "connecting",
      "connected",
      "outgoing-ended",
    ],
    transport: "expo-native-bridge",
  };
}

test("accepts the complete Expo native bridge journey and additional metadata", () => {
  assertExampleJourney(fixture());
  assertExampleJourney({ ...fixture(), rejections: ["invalid_arguments"] });
});

test("rejects unsuccessful results, nonboolean success and other transports", () => {
  for (const passed of [false, 0, 1, "true", null, undefined])
    assert.throws(
      () => assertExampleJourney({ ...fixture(), passed }),
      /Example journey did not pass/
    );
  for (const transport of [
    "",
    "native",
    "expo-native-bridge ",
    null,
    undefined,
  ])
    assert.throws(
      () => assertExampleJourney({ ...fixture(), transport }),
      /Example journey must use the Expo native bridge/
    );
});

test("every missing, altered or reordered step remains fatal", () => {
  for (let index = 0; index < fixture().steps.length; index++) {
    const missing = fixture();
    missing.steps.splice(index, 1);
    const altered = fixture();
    altered.steps[index] = "unexpected";
    const reordered = fixture();
    const other = (index + 1) % reordered.steps.length;
    [reordered.steps[index], reordered.steps[other]] = [
      reordered.steps[other]!,
      reordered.steps[index]!,
    ];
    for (const result of [missing, altered, reordered])
      assert.throws(
        () => assertExampleJourney(result),
        /Example journey steps are incomplete or out of order/
      );
  }
  for (const steps of [
    [],
    null,
    undefined,
    "ringing",
    [...fixture().steps, "outgoing-ended"],
    fixture().steps.toReversed(),
  ])
    assert.throws(
      () => assertExampleJourney({ ...fixture(), steps }),
      /Example journey steps are incomplete or out of order/
    );
});

test("rejects malformed evidence roots", () => {
  for (const value of [null, undefined, true, 1, "passed", [], {}])
    assert.throws(() => assertExampleJourney(value), /Example journey/);
});

test("CLI prints each failed rule with assertion details and exits 1", (t) => {
  const directory = mkdtempSync(join(tmpdir(), "example-journey-check-"));
  t.after(() => rmSync(directory, { recursive: true, force: true }));
  const file = join(directory, "result.json");
  const checker = fileURLToPath(
    new URL("./check-example-journey.ts", import.meta.url)
  );
  const cases: readonly [unknown, RegExp | undefined][] = [
    [fixture(), undefined],
    [{ ...fixture(), passed: false }, /Example journey did not pass/],
    [
      { ...fixture(), steps: fixture().steps.toReversed() },
      /Example journey steps are incomplete or out of order/,
    ],
    [
      { ...fixture(), transport: "native" },
      /Example journey must use the Expo native bridge/,
    ],
    [null, /Example journey evidence must be an object/],
  ];
  for (const [value, rule] of cases) {
    writeFileSync(file, JSON.stringify(value));
    const result = spawnSync(process.execPath, [checker, file], {
      encoding: "utf8",
      timeout: 15_000,
    });
    assert.ifError(result.error);
    assert.equal(result.status, rule ? 1 : 0, result.stderr);
    if (rule) {
      assert.equal(result.stdout, "");
      assert.match(result.stderr, rule);
      assert.match(result.stderr, /AssertionError/);
      assert.match(result.stderr, /actual:/);
      assert.match(result.stderr, /expected:/);
    } else {
      assert.equal(result.stderr, "");
      assert.equal(
        result.stdout,
        "Expo example incoming, hold/resume and outgoing journeys passed\n"
      );
    }
  }
  writeFileSync(file, "invalid JSON");
  const malformed = spawnSync(process.execPath, [checker, file], {
    encoding: "utf8",
    timeout: 15_000,
  });
  assert.ifError(malformed.error);
  assert.equal(malformed.status, 1);
  assert.equal(malformed.stdout, "");
  assert.equal(
    malformed.stderr,
    "Example journey evidence is not valid JSON.\n"
  );
  const missing = spawnSync(process.execPath, [checker], {
    encoding: "utf8",
    timeout: 15_000,
  });
  assert.ifError(missing.error);
  assert.equal(missing.status, 1);
  assert.equal(missing.stdout, "");
  assert.match(missing.stderr, /Missing example journey evidence path/);
  const absent = spawnSync(
    process.execPath,
    [checker, join(directory, "missing.json")],
    {
      encoding: "utf8",
      timeout: 15_000,
    }
  );
  assert.ifError(absent.error);
  assert.equal(absent.status, 1);
  assert.equal(absent.stdout, "");
  assert.match(absent.stderr, /ENOENT/);
});
