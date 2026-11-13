import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";

export interface EmulatorBudget {
  memoryKiB: number;
  cpus: 2;
}

/** Validate actual guest resources; requested AVD memory may be increased during boot. */
export function assertEmulatorBudget(memoryInfo: string, cpuRange: string): EmulatorBudget {
  const totals = [...memoryInfo.matchAll(/^MemTotal:[ \t]+([0-9]+)[ \t]+kB[ \t]*\r?$/gm)];
  assert.equal(totals.length, 1, "Missing or ambiguous guest MemTotal");
  const memoryKiB = Number(totals[0]?.[1]);
  assert.ok(Number.isSafeInteger(memoryKiB) && memoryKiB > 0, "Invalid guest memory size");
  assert.ok(memoryKiB <= 2 * 1024 * 1024, `Guest memory exceeds 2 GiB: ${memoryKiB} KiB`);
  assert.equal(cpuRange.trim(), "0-1", "Guest must expose exactly two CPUs");
  return { memoryKiB, cpus: 2 };
}

type Execute = (args: readonly string[]) => string;

/** Read only the emulator owned by the workflow, never an attached physical device. */
export function collectEmulatorBudget(run: Execute = (args) =>
  execFileSync("adb", [...args], { encoding: "utf8", timeout: 15_000 })
): EmulatorBudget {
  const read = (path: string) => run(["-s", "emulator-5554", "shell", "cat", path]);
  return assertEmulatorBudget(read("/proc/meminfo"), read("/sys/devices/system/cpu/present"));
}

if (import.meta.main) {
  try {
    console.log(JSON.stringify(collectEmulatorBudget()));
  } catch (error) {
    console.error(`Android emulator budget failed: ${error instanceof Error ? error.message : String(error)}`);
    process.exitCode = 1;
  }
}
