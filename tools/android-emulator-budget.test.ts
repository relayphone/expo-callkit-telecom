import assert from "node:assert/strict";
import { test } from "node:test";
import { mkdtempSync, readFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { assertEmulatorBudget, collectEmulatorBudget, runEmulatorBudgetCLI } from "./android-emulator-budget.ts";

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

test("the explicit four-core profile accepts observed memory through its inclusive 4 GiB limit", () => {
  for (const memoryKiB of [4000000, 4194304])
    assert.deepEqual(assertEmulatorBudget(`MemTotal: ${memoryKiB} kB\n`, "0-3\n", "4g-4cpu"), { memoryKiB, cpus: 4 });
  assert.throws(() => assertEmulatorBudget("MemTotal: 4194305 kB\n", "0-3", "4g-4cpu"), /exceeds 4 GiB/);
  for (const cpus of ["", "0-1", "0-2", "0-7"])
    assert.throws(() => assertEmulatorBudget("MemTotal: 4000000 kB\n", cpus, "4g-4cpu"), /four CPUs/);
});

test("CLI requires an explicit larger profile and retains observed resources beside requested limits", () => {
  const directory = mkdtempSync(join(tmpdir(), "emulator-budget-"));
  const run = (args: readonly string[]) => args.at(-1) === "/proc/meminfo" ? "MemTotal: 4000000 kB\n" : "0-3\n";
  try {
    assert.throws(() => runEmulatorBudgetCLI([], run), /exceeds 2 GiB/);
    const output = join(directory, "report", "budget.json");
    assert.deepEqual(runEmulatorBudgetCLI(["--profile", "4g-4cpu", "--output", output], run), { memoryKiB: 4000000, cpus: 4 });
    assert.deepEqual(JSON.parse(readFileSync(output, "utf8")), {
      profile: "4g-4cpu", limits: { memoryKiB: 4194304, cpus: 4 }, observed: { memoryKiB: 4000000, cpus: 4 },
    });
    assert.throws(() => runEmulatorBudgetCLI(["--profile", "__proto__"], run), /Unknown emulator budget profile/);
    assert.throws(() => runEmulatorBudgetCLI(["--unknown"], run), /Unknown option/);
  } finally { rmSync(directory, { recursive: true, force: true }); }
});
