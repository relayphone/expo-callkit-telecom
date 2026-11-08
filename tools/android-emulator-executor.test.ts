import assert from "node:assert/strict";
import { test } from "node:test";

import {
  assertAcceleratedBoot,
  assertKvmHost,
  collectBoot,
} from "./android-emulator-executor.ts";

test("accepts an accessible KVM device on a four-CPU Linux x64 host", () => {
  assert.doesNotThrow(() =>
    assertKvmHost({
      platform: "linux",
      architecture: "x64",
      cpuCount: 4,
      kvmCharacterDevice: true,
      kvmAccessible: true,
    })
  );
});

test("rejects missing KVM, oversized executors and incompatible hosts", () => {
  const valid = {
    platform: "linux",
    architecture: "x64",
    cpuCount: 4,
    kvmCharacterDevice: true,
    kvmAccessible: true,
  };
  for (const change of [
    { kvmCharacterDevice: false },
    { kvmAccessible: false },
    { cpuCount: 8 },
    { cpuCount: 0 },
    { cpuCount: 1.5 },
    { platform: "darwin" },
    { architecture: "arm64" },
  ])
    assert.throws(() => assertKvmHost({ ...valid, ...change }));
});

test("accepts usable KVM and a booted API 35 x86_64 emulator", () => {
  assert.doesNotThrow(() =>
    assertAcceleratedBoot({
      acceleration:
        "accel:\n0\nKVM (version 12) is installed and usable.\naccel\n",
      bootCompleted: "1\r\n",
      api: "35\n",
      abi: "x86_64\n",
      emulated: "1\n",
    })
  );
});

test("rejects software acceleration, incomplete boot and wrong images", () => {
  const valid = {
    acceleration: "KVM (version 12) is installed and usable.",
    bootCompleted: "1",
    api: "35",
    abi: "x86_64",
    emulated: "1",
  };
  for (const change of [
    { acceleration: "TCG is installed and usable." },
    { acceleration: "KVM is not installed." },
    { bootCompleted: "0" },
    { api: "34" },
    { abi: "arm64-v8a" },
    { emulated: "0" },
  ])
    assert.throws(() => assertAcceleratedBoot({ ...valid, ...change }));
});

test("collects proof only from the owned emulator and the SDK accelerator check", () => {
  const properties = new Map([
    ["sys.boot_completed", "1"],
    ["ro.build.version.sdk", "35"],
    ["ro.product.cpu.abi", "x86_64"],
    ["ro.kernel.qemu", "1"],
  ]);
  let acceleratorChecks = 0;
  let propertyChecks = 0;
  const boot = collectBoot("/sdk/emulator/emulator", (program, args) => {
    if (program === "/sdk/emulator/emulator") {
      assert.deepEqual(args, ["-accel-check"]);
      acceleratorChecks++;
      return "KVM (version 12) is installed and usable.";
    }
    assert.equal(program, "adb");
    assert.deepEqual(args.slice(0, 4), [
      "-s",
      "emulator-5554",
      "shell",
      "getprop",
    ]);
    const value = properties.get(args[4]!);
    assert.notEqual(value, undefined);
    propertyChecks++;
    return value!;
  });
  assertAcceleratedBoot(boot);
  assert.equal(acceleratorChecks, 1);
  assert.equal(propertyChecks, 4);
  assert.throws(
    () =>
      collectBoot("/sdk/emulator/emulator", () => {
        throw new Error("Accelerator command failed");
      }),
    /Accelerator command failed/
  );
});
