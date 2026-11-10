import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { test } from "node:test";
import { fileURLToPath } from "node:url";

import { assertAndroidDndCase } from "./check-android-dnd-case.ts";

test("accepts literal success without requiring the final policy fields", () => {
  assertAndroidDndCase({ passed: true });
  assertAndroidDndCase({
    passed: true,
    case: "priority-repeat-first",
    notificationAllowed: false,
    nativeAllowedAfterRaw: true,
  });
});

test("rejects missing, unsuccessful and nonboolean passed flags", () => {
  for (const passed of [
    undefined,
    null,
    false,
    0,
    1,
    -1,
    "",
    "true",
    [],
    [true],
    {},
    { passed: true },
  ])
    assert.throws(
      () => assertAndroidDndCase({ passed }),
      /Android DnD case passed flag must be true/
    );
});

test("rejects malformed evidence roots", () => {
  for (const value of [null, undefined, true, false, 1, "passed", [], [{}]])
    assert.throws(
      () => assertAndroidDndCase(value),
      /Android DnD case evidence must be an object/
    );
});

test("CLI identifies the evidence file and failed rule with assertion details", (t) => {
  const directory = mkdtempSync(join(tmpdir(), "android-dnd-case-check-"));
  t.after(() => rmSync(directory, { recursive: true, force: true }));
  const file = join(directory, "priority-contacts-opaque.json");
  const checker = fileURLToPath(
    new URL("./check-android-dnd-case.ts", import.meta.url)
  );
  const cases: readonly [unknown, RegExp | undefined][] = [
    [{ passed: true }, undefined],
    [{ passed: false }, /Android DnD case passed flag must be true/],
    [{ passed: 1 }, /Android DnD case passed flag must be true/],
    [{}, /Android DnD case passed flag must be true/],
    [null, /Android DnD case evidence must be an object/],
  ];
  for (const [value, rule] of cases) {
    writeFileSync(file, JSON.stringify(value));
    const run = spawnSync(process.execPath, [checker, file], {
      encoding: "utf8",
      timeout: 15_000,
    });
    assert.ifError(run.error);
    assert.equal(run.status, rule ? 1 : 0, run.stderr);
    assert.equal(run.stdout, "");
    if (rule) {
      assert.match(run.stderr, rule);
      assert.ok(run.stderr.includes(file));
      assert.match(run.stderr, /AssertionError/);
      assert.match(run.stderr, /actual:/);
      assert.match(run.stderr, /expected:/);
    } else assert.equal(run.stderr, "");
  }
});

test("CLI reports invalid JSON, missing evidence and a missing path", (t) => {
  const directory = mkdtempSync(join(tmpdir(), "android-dnd-case-check-"));
  t.after(() => rmSync(directory, { recursive: true, force: true }));
  const file = join(directory, "on-any.json");
  const checker = fileURLToPath(
    new URL("./check-android-dnd-case.ts", import.meta.url)
  );
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
    `Android DnD case evidence: ${file}\nAndroid DnD case evidence is not valid JSON.\n`
  );
  const absentPath = join(directory, "missing.json");
  const absent = spawnSync(process.execPath, [checker, absentPath], {
    encoding: "utf8",
    timeout: 15_000,
  });
  assert.ifError(absent.error);
  assert.equal(absent.status, 1);
  assert.equal(absent.stdout, "");
  assert.ok(absent.stderr.includes(absentPath));
  assert.match(absent.stderr, /ENOENT/);
  const missing = spawnSync(process.execPath, [checker], {
    encoding: "utf8",
    timeout: 15_000,
  });
  assert.ifError(missing.error);
  assert.equal(missing.status, 1);
  assert.equal(missing.stdout, "");
  assert.match(missing.stderr, /Missing Android DnD case evidence path/);
});
