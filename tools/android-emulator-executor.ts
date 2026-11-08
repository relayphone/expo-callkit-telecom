import { spawnSync } from "node:child_process";
import {
  accessSync,
  appendFileSync,
  constants,
  mkdirSync,
  statSync,
  writeFileSync,
} from "node:fs";
import { arch, cpus, platform } from "node:os";
import { dirname, join } from "node:path";
export interface KvmHost {
  platform: string;
  architecture: string;
  cpuCount: number;
  kvmCharacterDevice: boolean;
  kvmAccessible: boolean;
}

/** Requires hardware acceleration on a bounded Linux executor. */
export function assertKvmHost(host: KvmHost): void {
  if (host.platform !== "linux" || host.architecture !== "x64")
    throw new Error("Android emulator requires a Linux x64 host");
  if (
    !Number.isSafeInteger(host.cpuCount) ||
    host.cpuCount < 1 ||
    host.cpuCount > 4
  )
    throw new Error("Android executor must have between 1 and 4 CPUs");
  if (!host.kvmCharacterDevice || !host.kvmAccessible)
    throw new Error("Android executor requires readable and writable /dev/kvm");
}

export interface EmulatorBoot {
  acceleration: string;
  bootCompleted: string;
  api: string;
  abi: string;
  emulated: string;
}

/** Rejects software acceleration and an unbooted or mismatched Android image. */
export function assertAcceleratedBoot(boot: EmulatorBoot): void {
  if (
    !/^KVM \(version [^)]+\) is installed and usable\.?\r?$/m.test(
      boot.acceleration
    )
  )
    throw new Error("Android emulator did not confirm usable KVM acceleration");
  if (boot.bootCompleted.trim() !== "1" || boot.emulated.trim() !== "1")
    throw new Error("Android emulator has not completed boot");
  if (boot.api.trim() !== "35" || boot.abi.trim() !== "x86_64")
    throw new Error("Android journey requires the API 35 x86_64 image");
}

type Execute = (program: string, args: readonly string[]) => string;

const execute: Execute = (program, args) => {
  const result = spawnSync(program, args, {
    encoding: "utf8",
    timeout: 15_000,
    maxBuffer: 1024 * 1024,
  });
  if (result.error) throw result.error;
  if (result.status !== 0)
    throw new Error(`${program} failed: ${result.stderr}`);
  return `${result.stdout}${result.stderr}`;
};

/** Reads the single emulator owned by this workflow; never targets an attached device. */
export function collectBoot(
  emulator: string,
  run: Execute = execute
): EmulatorBoot {
  const get = (property: string) =>
    run("adb", ["-s", "emulator-5554", "shell", "getprop", property]);
  return {
    acceleration: run(emulator, ["-accel-check"]),
    bootCompleted: get("sys.boot_completed"),
    api: get("ro.build.version.sdk"),
    abi: get("ro.product.cpu.abi"),
    emulated: get("ro.kernel.qemu"),
  };
}

function host(): KvmHost {
  let kvmCharacterDevice = false;
  let kvmAccessible = false;
  try {
    kvmCharacterDevice = statSync("/dev/kvm").isCharacterDevice();
    accessSync("/dev/kvm", constants.R_OK | constants.W_OK);
    kvmAccessible = true;
  } catch {
    /* The preflight reports missing or inaccessible KVM below. */
  }
  return {
    platform: platform(),
    architecture: arch(),
    cpuCount: cpus().length,
    kvmCharacterDevice,
    kvmAccessible,
  };
}

function main(): void {
  const [mode, output] = process.argv.slice(2);
  if ((mode !== "preflight" && mode !== "boot") || !output)
    throw new Error(
      "Usage: android-emulator-executor.ts <preflight|boot> <result.json>"
    );
  const machine = host();
  const runner = process.env.ANDROID_EXECUTOR_LABEL ?? "unspecified";
  const reason = process.env.ANDROID_EXECUTOR_REASON ?? "Preferred executor";
  console.log(JSON.stringify({ runner, reason, ...machine }));
  assertKvmHost(machine);
  let boot: EmulatorBoot | undefined;
  if (mode === "boot") {
    const sdk = process.env.ANDROID_HOME ?? process.env.ANDROID_SDK_ROOT;
    if (!sdk) throw new Error("Missing Android SDK path");
    boot = collectBoot(join(sdk, "emulator", "emulator"));
    assertAcceleratedBoot(boot);
  }
  mkdirSync(dirname(output), { recursive: true });
  writeFileSync(
    output,
    JSON.stringify(
      {
        schemaVersion: 1,
        runner,
        reason,
        host: machine,
        ...(boot ? { boot } : {}),
      },
      null,
      2
    )
  );
  const message = `Android executor ${mode}: ${runner}; ${
    machine.cpuCount
  } CPUs; KVM accessible${
    boot ? "; API 35 x86_64 booted with KVM" : ""
  }. ${reason}\n`;
  console.log(message);
  if (process.env.GITHUB_STEP_SUMMARY)
    appendFileSync(process.env.GITHUB_STEP_SUMMARY, message);
}

if (import.meta.main) {
  try {
    main();
  } catch (error) {
    const message = `infra:android-executor failed: ${
      error instanceof Error ? error.message : String(error)
    }\n`;
    console.error(message);
    if (process.env.GITHUB_STEP_SUMMARY)
      appendFileSync(process.env.GITHUB_STEP_SUMMARY, message);
    process.exitCode = 1;
  }
}
