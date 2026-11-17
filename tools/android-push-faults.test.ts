import assert from "node:assert/strict";
import test from "node:test";
import { coldPushTimings, parsePushFaultCommand, parsePushFaultLog, readPushFaultLog, runColdPushJourney,
  triggerPushFault, type ColdPushReceipt } from "./android-push-faults.ts";

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

const coldRequest = { callId: "cold-call", deliveryId: "cold-delivery", eventId: "cold-invite", delayMillis: 0 };
function coldJournal() {
  return { schemaVersion: 1 as const, processId: 456, events:
    (["processStarted", "intake", "ringing", "presented", "answered", "cleaned"] as const).map((stage, index) =>
      ({ kind: "coldStart" as const, ...coldRequest, stage, elapsedRealtimeNanos: String(9_007_199_254_740_993n + BigInt(index) * 100n) })) };
}
function coldDevice(journal = coldJournal(), api = "35") {
  const commands: (readonly string[])[] = [];
  let pid = "123";
  let now = 0;
  const run = (args: readonly string[]) => {
    commands.push(args);
    if (args.at(-1) === "ro.kernel.qemu") return "1";
    if (args.at(-1) === "ro.build.version.sdk") return api;
    if (args.some(value => value.startsWith("pidof "))) return pid;
    if (args.includes("force-stop")) { pid = ""; return ""; }
    if (args[3]?.includes("'am' 'start'")) { assert.equal(pid, ""); pid = "456"; return "Status: ok\n"; }
    if (args.includes("pidof")) return pid;
    if (args.includes("exec-out")) return JSON.stringify(journal);
    if (args.at(-1) === "telecom") return "mCalls:\nnext:\n";
    if (args.at(-1) === "notification") return "Current Notification Manager state:\n mMaxPackageEnqueueRate=5\n";
    return "";
  };
  return { run, commands, timing: { now: () => now, pause: async (ms: number) => { now += ms; } } };
}

for (const api of ["34", "35", "36"]) test(`API ${api} cold push force-stops before launch, retains the new process and measures only device times`, async () => {
  const f = coldDevice(coldJournal(), api);
  const retained: ColdPushReceipt[] = [];
  const receipt = await runColdPushJourney("emulator-5554", coldRequest, f.run, f.timing, value => retained.push(value));
  assert.equal(receipt.previousProcessId, 123);
  assert.equal(receipt.journal.processId, 456);
  const stop = f.commands.findIndex(args => args.includes("force-stop"));
  const launch = f.commands.findIndex(args => args[3]?.includes("'am' 'start'"));
  assert.ok(stop >= 0 && launch > stop);
  assert.match(f.commands[launch]![3]!, /'command' 'cold-start'/);
  assert.match(f.commands[launch]![3]!, /'deliveryId' 'cold-delivery'/);
  assert.deepEqual(retained, [receipt]);
  assert.deepEqual(coldPushTimings(receipt, coldRequest), { clock: "android.elapsedRealtimeNanos",
    processToIntakeNanos: "100", intakeToPresentationNanos: "200", processToPresentationNanos: "300", processToAnswerNanos: "400" });
  assert.deepEqual(parsePushFaultLog({ ...log, coldStart: receipt }).coldStart, receipt);
});

test("unsupported runtimes fail before force-stop or delivery", async () => {
  for (const api of ["33", "37", "", "unknown"]) {
    const f = coldDevice(coldJournal(), api);
    await assert.rejects(runColdPushJourney("emulator-5554", coldRequest, f.run, f.timing));
    assert.ok(!f.commands.some(args => args.includes("force-stop") || args[3]?.includes("'am' 'start'")));
  }
});

test("missing presentation, wrong delivery, failed controls and nonmonotonic times cannot pass", async () => {
  for (const fault of ["presentation", "identity", "failure", "time"] as const) {
    const journal = coldJournal();
    if (fault === "presentation") journal.events.splice(3, 1);
    if (fault === "identity") journal.events[3]!.deliveryId = "other-delivery";
    if (fault === "time") journal.events[3]!.elapsedRealtimeNanos = "1";
    const f = coldDevice(journal);
    const run = fault !== "failure" ? f.run : (args: readonly string[]) => {
      const result = f.run(args);
      return args.includes("exec-out") ? JSON.stringify({ ...journal,
        events: [...journal.events.slice(0, 4), { ...journal.events[4], stage: "failed" }, journal.events[5]] }) : result;
    };
    await assert.rejects(runColdPushJourney("emulator-5554", coldRequest, run, f.timing));
    assert.equal(f.commands.filter(args => args.includes("force-stop")).length, 2);
  }
});

test("stale journals and an incomplete cold start time out and remain fatal", async () => {
  for (const stale of [false, true]) {
    const journal = coldJournal();
    if (stale) journal.processId = 123;
    else journal.events.splice(3);
    const f = coldDevice(journal);
    await assert.rejects(runColdPushJourney("emulator-5554", coldRequest, f.run, f.timing), /Cold push did not complete/);
    assert.equal(f.commands.filter(args => args.includes("force-stop")).length, 2);
  }
});

test("a process surviving force-stop and a leftover Telecom call cannot qualify", async () => {
  for (const fault of ["process", "telecom"] as const) {
    const f = coldDevice();
    await assert.rejects(runColdPushJourney("emulator-5554", coldRequest, args => {
      if (fault === "process" && args.includes("force-stop")) return "";
      if (fault === "telecom" && args.at(-1) === "telecom") return "mCalls:\n  [Call id=TC@1, state=ACTIVE, other]\nnext:\n";
      return f.run(args);
    }, f.timing), fault === "process" ? /Force-stop left the process alive/ : /Cold push left a Telecom call/);
  }
});

test("a disconnected Telecom record must disappear before cleanup can qualify", async () => {
  const f = coldDevice();
  let reads = 0;
  await runColdPushJourney("emulator-5554", coldRequest, args => {
    const value = f.run(args);
    if (args.at(-1) === "telecom" && ++reads <= 2)
      return "mCalls:\n  [Call id=TC@1, state=DISCONNECTED, other]\nnext:\n";
    return value;
  }, f.timing);
  assert.equal(reads, 3);
  assert.equal(f.commands.filter(args => args.includes("force-stop")).length, 1);
});

test("permanent disconnected calls and late empty evidence stay fatal", async () => {
  for (const fault of ["disconnected", "late"] as const) {
    const f = coldDevice();
    await assert.rejects(runColdPushJourney("emulator-5554", coldRequest, args => {
      const value = f.run(args);
      if (args.at(-1) === "telecom") {
        if (fault === "disconnected") return "mCalls:\n  [Call id=TC@1, state=DISCONNECTED, other]\nnext:\n";
        void f.timing.pause(5001);
      }
      return value;
    }, f.timing), fault === "disconnected" ? /Cold push left a Telecom call/ : /Cold push command budget expired/);
    assert.equal(f.commands.filter(args => args.includes("force-stop")).length, 2);
  }
});

test("stalled adb reads share a deadline and reserve bounded force-stop cleanup", async () => {
  const f = coldDevice();
  const timeouts: number[] = [];
  let now = 0;
  let launched = false;
  const run = (args: readonly string[], timeout?: number) => {
    timeouts.push(timeout!);
    const value = f.run(args);
    if (launched && !args.includes("force-stop")) now += 6000;
    if (args[3]?.includes("'am' 'start'")) launched = true;
    return value;
  };
  await assert.rejects(runColdPushJourney("emulator-5554", coldRequest, run,
    { now: () => now, pause: async ms => { now += ms; } }), /Cold push did not complete/);
  assert.ok(timeouts.includes(2000));
  assert.equal(timeouts.at(-1), 5000);
  assert.equal(f.commands.filter(args => args.includes("force-stop")).length, 2);
});
