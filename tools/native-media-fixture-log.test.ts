import assert from "node:assert/strict";
import { spawn, spawnSync } from "node:child_process";
import { once } from "node:events";
import { setTimeout as pause } from "node:timers/promises";
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
import { sampleHostResources, startHostResourceMonitor } from "./native-media-receiver-diagnostics.ts";

function fixture(t: TestContext) {
  const directory = mkdtempSync(join(tmpdir(), "media-fixture-log-"));
  t.after(() => rmSync(directory, { recursive: true, force: true }));
  const output = join(directory, "artifacts", "server.log");
  return { directory, output };
}

test("independent host samples distinguish peer CPU from idle host and retain main-thread runqueue wait", () => {
  let ticks = "cpu 100 0 20 380 0 0 0 0 90 0\ncpu0 0\ncpu1 0\ncpu2 0\ncpu3 0\n";
  let peer = "S " + Array.from({ length: 22 }, (_, i) => i === 10 ? "20" : i === 11 ? "10" : "0").join(" ");
  const read = (path: string): string => {
    if (path === "/proc/stat") return ticks;
    if (path === "/proc/meminfo") return "MemTotal: 1000 kB\nMemAvailable: 100 kB\nSwapTotal: 20 kB\nSwapFree: 10 kB\n";
    if (path.endsWith("/stat")) return `42 (never-export-this) ${peer}`;
    if (path.endsWith("/schedstat")) return "1000000000 9000000000 10";
    if (path.endsWith("/status")) return "VmRSS: 25 kB\n";
    throw Error("private error");
  };
  const first = sampleHostResources(42, undefined, read, 1n, 10);
  assert.deepEqual(first.cpu, { totalTicks: 500, idleTicks: 380, cores: 4 });
  ticks = "cpu 180 0 20 400 0 0 0 0 90 0\ncpu0 0\ncpu1 0\ncpu2 0\ncpu3 0\n";
  peer = "S " + Array.from({ length: 22 }, (_, i) => i === 10 ? "40" : i === 11 ? "10" : "0").join(" ");
  const next = sampleHostResources(42, first, read, 2n, 11);
  assert.equal(next.hostBusyPercent, 80);
  assert.equal(next.peerCpuPercentOneCore, 80);
  assert.equal(next.peer?.mainThreadWaitNs, "9000000000");
  assert.equal(next.peer?.rssKiB, 25);
  assert.equal(next.memory?.availableKiB, 100);
  assert.equal(next.clock, "host.hrtime.bigint");
  assert.equal(next.atNs, "2");
  assert.equal(next.unixMs, 11);
  assert.ok(!JSON.stringify(next).includes("never-export"));
});

test("missing, malformed or reset resource counters remain unavailable without exporting raw errors", () => {
  for (const read of [() => { throw Error("fixture-secret"); }, () => "fixture-secret"]) {
    const sample = sampleHostResources(42, undefined, read, 1n, 1);
    assert.equal(sample.cpu, null);
    assert.equal(sample.peer, null);
    assert.equal(sample.memory, null);
    assert.equal(sample.hostBusyPercent, null);
    assert.ok(!JSON.stringify(sample).includes("fixture-secret"));
  }
  const read = (path: string) => path === "/proc/stat" ? "cpu 0 0 0 100 0 0 0 0\ncpu0 0\n" : "";
  const first = sampleHostResources(undefined, undefined, read);
  assert.equal(sampleHostResources(undefined, first, read).hostBusyPercent, null);
  assert.equal(sampleHostResources(-1, first, read).peer, null);
});

test("resource sampling continues during a blocked host command and exits with its fixture", { timeout: 8_000 }, async (t) => {
  const { directory } = fixture(t);
  const owner = spawn(process.execPath, ["-e", "setTimeout(() => {}, 6000)"], { stdio: "ignore" });
  assert.ok(owner.pid);
  const monitor = startHostResourceMonitor(directory, owner.pid);
  const closed = once(monitor, "close");
  const path = join(directory, "media-evidence", "receiver-host.jsonl");
  try {
    const deadline = performance.now() + 2_000;
    while (!existsSync(path) && performance.now() < deadline) await pause(20);
    const before = readFileSync(path, "utf8").trim().split("\n").length;
    Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, 1_200);
    const samples = readFileSync(path, "utf8").trim().split("\n").map(line => JSON.parse(line));
    assert.ok(samples.length > before, "sampler must progress independently of the blocked command");
    assert.ok(samples.every(s => s.clock === "host.hrtime.bigint" && /^\d+$/.test(s.atNs)));
    owner.kill();
    await Promise.race([closed, pause(2_500).then(() => { throw Error("monitor outlived fixture"); })]);
  } finally {
    owner.kill(); monitor.kill();
    await closed;
  }
});

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
