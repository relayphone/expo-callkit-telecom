import assert from "node:assert/strict";
import { test } from "node:test";
import { assertEmulatorBudget, collectEmulatorBudget } from "./android-emulator-budget.ts";

test("accepts the observed bounded guest and the inclusive 2 GiB limit", () => {
  assert.deepEqual(assertEmulatorBudget("MemTotal:        2022316 kB\nMemFree: 1 kB\n", "0-1\n"),
    { memoryKiB: 2022316, cpus: 2 });
  assert.equal(assertEmulatorBudget("MemTotal: 2097152 kB\r\n", "0-1\r\n").memoryKiB, 2097152);
});

test("rejects the emulator's automatic 2560 MB increase and any excess", () => {
  for (const value of [2532240, 2097153]) {
    assert.throws(() => assertEmulatorBudget(`MemTotal: ${value} kB\n`, "0-1"), /exceeds 2 GiB/);
  }
});

test("missing, zero, malformed and duplicate memory observations fail", () => {
  for (const memory of ["", "MemFree: 100 kB", "MemTotal: 0 kB", "MemTotal: -1 kB",
    "MemTotal: 1 MB", "MemTotal: 1 kB\nMemTotal: 2 kB\n"]) {
    assert.throws(() => assertEmulatorBudget(memory, "0-1"));
  }
});

test("wrong or unavailable CPU topology fails", () => {
  for (const cpu of ["", "0", "0-2", "0-3", "0-7"]) {
    assert.throws(() => assertEmulatorBudget("MemTotal: 2022316 kB\n", cpu), /two CPUs/);
  }
});

test("collector targets the owned emulator and propagates observation failure", () => {
  const actual: string[][] = [];
  const result = collectEmulatorBudget((args) => {
    actual.push([...args]);
    return args.at(-1) === "/proc/meminfo" ? "MemTotal: 2022316 kB\n" : "0-1\n";
  });
  assert.deepEqual(actual, [
    ["-s", "emulator-5554", "shell", "cat", "/proc/meminfo"],
    ["-s", "emulator-5554", "shell", "cat", "/sys/devices/system/cpu/present"],
  ]);
  assert.equal(result.cpus, 2);
  const failure = new Error("emulator unavailable");
  assert.throws(() => collectEmulatorBudget(() => { throw failure; }), (error) => error === failure);
});
