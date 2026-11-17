import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { mkdirSync, writeFileSync } from "node:fs";
import { dirname } from "node:path";
import { parseArgs } from "node:util";

const profiles = {
  "2g-2cpu": { memoryKiB: 2 * 1024 * 1024, cpus: 2 },
  "4g-4cpu": { memoryKiB: 4 * 1024 * 1024, cpus: 4 },
} as const;
export type EmulatorBudgetProfile = keyof typeof profiles;

export interface EmulatorBudget {
  memoryKiB: number;
  cpus: 2 | 4;
}

/** Validate actual guest resources; requested AVD memory may be increased during boot. */
export function assertEmulatorBudget(memoryInfo: string, cpuRange: string,
  profile: EmulatorBudgetProfile = "2g-2cpu"): EmulatorBudget {
  const limits = profiles[profile];
  const totals = [...memoryInfo.matchAll(/^MemTotal:[ \t]+([0-9]+)[ \t]+kB[ \t]*\r?$/gm)];
  assert.equal(totals.length, 1, "Missing or ambiguous guest MemTotal");
  const memoryKiB = Number(totals[0]?.[1]);
  assert.ok(Number.isSafeInteger(memoryKiB) && memoryKiB > 0, "Invalid guest memory size");
  assert.ok(memoryKiB <= limits.memoryKiB, `Guest memory exceeds ${limits.memoryKiB / 1024 / 1024} GiB: ${memoryKiB} KiB`);
  assert.equal(cpuRange.trim(), `0-${limits.cpus - 1}`, `Guest must expose exactly ${limits.cpus === 2 ? "two" : "four"} CPUs`);
  return { memoryKiB, cpus: limits.cpus };
}

type Execute = (args: readonly string[]) => string;

const execute: Execute = (args) =>
  execFileSync("adb", [...args], { encoding: "utf8", timeout: 15_000 });

/** Read only the emulator owned by the workflow, never an attached physical device. */
export function collectEmulatorBudget(run: Execute = execute, profile: EmulatorBudgetProfile = "2g-2cpu"): EmulatorBudget {
  const read = (path: string) => run(["-s", "emulator-5554", "shell", "cat", path]);
  return assertEmulatorBudget(read("/proc/meminfo"), read("/sys/devices/system/cpu/present"), profile);
}

/** Keep the default strict; larger CI guests must explicitly select their measured profile. */
export function runEmulatorBudgetCLI(args: string[], run: Execute = execute): EmulatorBudget {
  const { values } = parseArgs({ args, options: {
    profile: { type: "string", default: "2g-2cpu" }, output: { type: "string" },
  } });
  assert.ok(Object.hasOwn(profiles, values.profile), "Unknown emulator budget profile");
  const profile = values.profile as EmulatorBudgetProfile;
  const observed = collectEmulatorBudget(run, profile);
  if (values.output) {
    mkdirSync(dirname(values.output), { recursive: true });
    writeFileSync(values.output, JSON.stringify({ profile, limits: profiles[profile], observed }) + "\n");
  }
  return observed;
}

if (import.meta.main) {
  try {
    console.log(JSON.stringify(runEmulatorBudgetCLI(process.argv.slice(2))));
  } catch (error) {
    console.error(`Android emulator budget failed: ${error instanceof Error ? error.message : String(error)}`);
    process.exitCode = 1;
  }
}
