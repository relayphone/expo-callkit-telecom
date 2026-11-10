import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import {
  existsSync,
  mkdirSync,
  mkdtempSync,
  readFileSync,
  rmSync,
  writeFileSync,
} from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { test, type TestContext } from "node:test";

import {
  retainFixtureLog,
  sanitizeFixtureLog,
} from "./native-media-fixture-log.ts";

function fixture(t: TestContext) {
  const directory = mkdtempSync(join(tmpdir(), "media-fixture-log-"));
  t.after(() => rmSync(directory, { recursive: true, force: true }));
  const output = join(directory, "artifacts", "server.log");
  return { directory, output };
}

test("redacts fixture credentials, authorization values and signed tokens while retaining diagnostics", () => {
  const token = "eyJhbGciOiJIUzI1NiJ9.eyJyb29tIjoiZml4dHVyZSJ9.fake-signature";
  const diagnostic =
    "OSError: [Errno 98] address already in use\n  File /tools/native-media-fixture.py, line 255\n";
  const raw = `${diagnostic}secret=long-secret; auth=device-secret; key=ci-key\n${token}\nAuthorization: Bearer opaque-value\nAuthorization: Basic dXNlcjpwYXNz\n`;
  assert.equal(
    sanitizeFixtureLog(raw, ["ci-key", "long-secret", "device-secret"]),
    `${diagnostic}secret=<REDACTED>; auth=<REDACTED>; key=<REDACTED>\n<REDACTED>\nAuthorization: Bearer <REDACTED>\nAuthorization: Basic <REDACTED>\n`
  );
});

test("handles overlapping secrets and empty settings values", () => {
  assert.equal(
    sanitizeFixtureLog("abcdef abc", ["abc", "", "abcdef"]),
    "<REDACTED> <REDACTED>"
  );
  assert.equal(
    sanitizeFixtureLog(
      "eyJhbGciOiJIUzI1NiJ9.payload.ci-key-suffix Bearer prefix-ci-key-suffix",
      ["ci-key"]
    ),
    "<REDACTED> Bearer <REDACTED>"
  );
});

test("retains only a sanitized copy and leaves the original log private", (t) => {
  const { directory, output } = fixture(t);
  writeFileSync(
    join(directory, "settings.json"),
    JSON.stringify({ secret: "fixture-secret", deviceAuth: "fixture-auth" })
  );
  writeFileSync(
    join(directory, "server.log"),
    "startup failed: fixture-secret fixture-auth\n"
  );
  assert.equal(retainFixtureLog(directory, output), true);
  assert.equal(
    readFileSync(output, "utf8"),
    "startup failed: <REDACTED> <REDACTED>\n"
  );
  assert.equal(
    readFileSync(join(directory, "server.log"), "utf8"),
    "startup failed: fixture-secret fixture-auth\n"
  );
});

test("does not invent a log when fixture startup never created one", (t) => {
  const { directory, output } = fixture(t);
  assert.equal(retainFixtureLog(directory, output), false);
  assert.equal(existsSync(output), false);
});

test("fails closed if settings cannot be read or validated", (t) => {
  const { directory, output } = fixture(t);
  writeFileSync(join(directory, "server.log"), "fixture-secret\n");
  mkdirSync(join(directory, "artifacts"));
  for (const settings of [
    undefined,
    "invalid fixture-secret",
    "null",
    "[]",
    '{"secret":1}',
  ]) {
    writeFileSync(output, "stale artifact");
    if (settings !== undefined)
      writeFileSync(join(directory, "settings.json"), settings);
    assert.throws(() => retainFixtureLog(directory, output));
    assert.equal(existsSync(output), false);
  }
});

test("CLI errors reveal neither malformed settings nor the private log", (t) => {
  const { directory, output } = fixture(t);
  writeFileSync(join(directory, "settings.json"), "malformed fixture-secret");
  writeFileSync(join(directory, "server.log"), "private-token\n");
  const result = spawnSync(
    process.execPath,
    [
      new URL("./native-media-fixture-log.ts", import.meta.url).pathname,
      directory,
      output,
    ],
    { encoding: "utf8" }
  );
  assert.equal(result.status, 1);
  assert.equal(result.stdout, "");
  assert.equal(
    result.stderr,
    "Could not retain sanitized native media fixture log.\n"
  );
  assert.equal(existsSync(output), false);
});
