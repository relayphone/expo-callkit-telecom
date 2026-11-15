import assert from "node:assert/strict";
import test from "node:test";
import { parsePushFaultCommand, parsePushFaultLog, readPushFaultLog, triggerPushFault } from "./android-push-faults.ts";

const request = { kind: "deliver" as const, callId: "call", deliveryId: "delivery", eventId: "event", delayMillis: 250 };
const log = { schemaVersion: 1, processId: 123, events: [
  { ...request, kind: "delivery", phase: "recognized", elapsedRealtimeNanos: "9007199254740993" },
  { kind: "tokenRebind", deliveryId: "rebind", tokenState: "absent", tokenPresent: false, tokenEmpty: false,
    revisionBefore: 0, revisionAfter: 0, sameSnapshot: true, elapsedRealtimeNanos: "9007199254740994" },
] };
function device(commands: (readonly string[])[] = [], output = "Status: ok\n") {
  return (args: readonly string[]): string => {
    commands.push(args);
    if (args.at(-1) === "ro.kernel.qemu") return "1";
    if (args.at(-1) === "ro.build.version.sdk") return "35";
    if (args.includes("pidof")) return "123\n";
    if (args.includes("exec-out")) return JSON.stringify(log);
    return output;
  };
}

test("typed delay/drop/duplicate commands reject malformed or unbounded inputs", () => {
  for (const kind of ["deliver", "drop", "duplicate"] as const)
    assert.deepEqual(parsePushFaultCommand({ ...request, kind }), { ...request, kind });
  assert.deepEqual(parsePushFaultCommand({ kind: "reset" }), { kind: "reset" });
  for (const value of [null, {}, { ...request, delayMillis: -1 }, { ...request, delayMillis: 30_001 },
    { ...request, delayMillis: 0.5 }, { ...request, deliveryId: "" }, { ...request, callId: "a\0b" },
    { ...request, kind: "duplicate", deliveryId: "a".repeat(127) }])
    assert.throws(() => parsePushFaultCommand(value));
});

test("duplicate trigger sends two unique deliveries with unchanged call/event identity", () => {
  const commands: (readonly string[])[] = [];
  triggerPushFault("emulator-5554", { ...request, kind: "duplicate" }, device(commands));
  const launches = commands.filter(args => args.length === 4);
  assert.equal(launches.length, 2);
  assert.ok(launches[0]![3]!.includes("'delivery.1'"));
  assert.ok(launches[1]![3]!.includes("'delivery.2'"));
  for (const launch of launches) {
    assert.ok(launch[3]!.includes("'callId' 'call'"));
    assert.ok(launch[3]!.includes("'eventId' 'event'"));
    assert.ok(launch[3]!.includes("'--el' 'delayMillis' '250'"));
  }
});

test("remote shell quoting preserves arbitrary fixture IDs without command execution", () => {
  const commands: (readonly string[])[] = [];
  triggerPushFault("emulator-5554", { ...request, callId: "raw/電話 '; $(touch nope)" }, device(commands));
  assert.ok(commands.at(-1)![3]!.includes("'raw/電話 '\\''; $(touch nope)'"));
  assert.equal(commands.length, 3);
});

test("physical targets, wrong API/emulator properties and failed starts are fatal", () => {
  assert.throws(() => triggerPushFault("physical", request, () => { throw new Error("must not run"); }));
  for (const property of ["ro.kernel.qemu", "ro.build.version.sdk"])
    assert.throws(() => triggerPushFault("emulator-5554", request,
      args => args.at(-1) === property ? "0" : device()(args)));
  for (const output of ["Error: unavailable", "Status: ok\nException: failed"])
    assert.throws(() => triggerPushFault("emulator-5554", request, device([], output)));
});

test("journal parsing preserves exact timestamps and rejects stale or malformed evidence", () => {
  assert.equal(parsePushFaultLog(log).events[0]!.elapsedRealtimeNanos, "9007199254740993");
  assert.deepEqual(readPushFaultLog("emulator-5554", device()), parsePushFaultLog(log));
  assert.throws(() => readPushFaultLog("emulator-5554", args => args.includes("pidof") ? "124" : device()(args)));
  for (const value of [{ ...log, schemaVersion: 2 }, { ...log, events: [{ ...log.events[0], phase: "accepted" }] },
    { ...log, events: [{ ...log.events[0], elapsedRealtimeNanos: 1 }] },
    { ...log, events: [{ ...log.events[0], elapsedRealtimeNanos: "9223372036854775808" }] }])
    assert.throws(() => parsePushFaultLog(value));
});

test("token values and unknown payload fields never leave the typed reader", () => {
  const value = { ...log, token: "private", events: [{ ...log.events[1], token: "private", value: "private" }] };
  assert.ok(!JSON.stringify(parsePushFaultLog(value)).includes("private"));
  const commands: (readonly string[])[] = [];
  triggerPushFault("emulator-5554", { kind: "rebind-empty-token", deliveryId: "rebind" }, device(commands));
  assert.ok(commands.at(-1)![3]!.includes("'rebind-empty-token'"));
});
