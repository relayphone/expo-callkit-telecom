import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { chmodSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

import { VerifierFailure, type VerifierDiagnostic } from "./android-push-faults-diagnostics.ts";
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

const parserFailures: [import('./android-push-faults-diagnostics.ts').VerifierRule, () => unknown][] = [
  ["record", () => parsePushFaultLog(null)],
  ["identity", () => parsePushFaultCommand({ ...request, callId: "private-content\0" })],
  ["integer", () => parsePushFaultCommand({ ...request, delayMillis: -1 })],
  ["boolean", () => parsePushFaultLog({ ...log, events: [{ ...log.events[1], tokenPresent: "private-content" }] })],
  ["command.kind", () => parsePushFaultCommand({ kind: "private-content" })],
  ["command.delay", () => parsePushFaultCommand({ ...request, kind: "cold-start" })],
  ["journal.schema", () => parsePushFaultLog({ ...log, schemaVersion: "private-content" })],
  ["journal.process", () => parsePushFaultLog({ ...log, processId: 0 })],
  ["journal.events", () => parsePushFaultLog({ ...log, events: {} })],
  ["journal.timestamp", () => parsePushFaultLog({ ...log, events: [{ ...log.events[0], elapsedRealtimeNanos: "private-content" }] })],
  ["journal.stage", () => parsePushFaultLog({ ...log, events: [{ ...coldJournal().events[0], stage: "private-content" }] })],
  ["journal.phase", () => parsePushFaultLog({ ...log, events: [{ ...log.events[0], phase: "private-content" }] })],
  ["journal.kind", () => parsePushFaultLog({ ...log, events: [{ ...log.events[1], kind: "private-content" }] })],
  ["journal.token-state", () => parsePushFaultLog({ ...log, events: [{ ...log.events[1], tokenState: "private-content" }] })],
  ["process.new", () => parsePushFaultLog({ ...log, coldStart: { previousProcessId: 456, journal: coldJournal() } })],
  ["process.pid", () => readPushFaultLog("emulator-5554", args => args.includes("pidof") ? "private-content" : device()(args))],
  ["process.journal", () => readPushFaultLog("emulator-5554", args => args.includes("pidof") ? "999" : device()(args))],
  ["journal.json", () => readPushFaultLog("emulator-5554", args => args.includes("exec-out") ? "private-content" : device()(args))],
  ["target.serial", () => triggerPushFault("private-content", request)],
  ["target.emulator", () => triggerPushFault("emulator-5554", request, () => "private-content")],
  ["target.api", () => triggerPushFault("emulator-5554", request, args => args.at(-1) === "ro.kernel.qemu" ? "1" : "private-content")],
  ["activity.started", () => triggerPushFault("emulator-5554", request, device([], "private-content"))],
  ["activity.result", () => triggerPushFault("emulator-5554", request, device([], "Status: ok\nException: private-content"))],
];
test("the cold-push zero-delay rule retains strict negative-zero rejection", () => {
  assert.throws(() => parsePushFaultCommand({ ...coldRequest, kind: "cold-start", delayMillis: -0 }), /command.delay/);
});
for (const [rule, run] of parserFailures) test(`${rule} reports sanitized expected and observed fields`, () => {
  assert.throws(run, error => {
    assert.ok(error instanceof VerifierFailure);
    assert.equal(error.failure.rule, rule);
    assert.ok(Object.keys(error.failure.expected).length && Object.keys(error.failure.observed).length);
    assert.ok(!JSON.stringify(error.failure).includes("private-content"));
    return true;
  });
});

for (const fault of ["stages", "identity", "monotonic"] as const)
  test(`cold.${fault} keeps its original rejection and names the failing rule`, () => {
    const journal = coldJournal();
    if (fault === "stages") journal.events.splice(3, 1);
    if (fault === "identity") journal.events[3]!.callId = "private-content";
    if (fault === "monotonic") journal.events[3]!.elapsedRealtimeNanos = "1";
    assert.throws(() => coldPushTimings({ previousProcessId: 123, journal }, coldRequest), error => {
      assert.ok(error instanceof VerifierFailure);
      assert.equal(error.failure.rule, `cold.${fault}`);
      assert.ok(!JSON.stringify(error.failure).includes("private-content"));
      return true;
    });
  });

for (const [field, rule] of [["ro.kernel.qemu", "adb.emulator"], ["ro.build.version.sdk", "adb.api"],
  ["pidof", "adb.pid"], ["exec-out", "adb.journal"], ["launch", "adb.launch"]] as const)
  test(`${rule} reports an unavailable adb operation without its stdout or stderr`, () => {
    const run = (args: readonly string[]) => {
      if (args.includes(field) || field === "launch" && args.length === 4) throw new Error("private-content");
      return device()(args);
    };
    assert.throws(() => field === "launch" ? triggerPushFault("emulator-5554", request, run) :
      readPushFaultLog("emulator-5554", run), error => error instanceof VerifierFailure && error.failure.rule === rule);
  });

for (const fault of ["process.stopped", "cold.budget", "cold.completed", "cold.telecom-empty", "cold.notifications-empty",
  "adb.permissions", "adb.stop", "evidence.retain", "adb.telecom", "adb.notifications"] as const)
  test(`${fault} retains last stage, platform process time and sanitized residual probes`, async () => {
    const f = coldDevice();
    const failures: VerifierDiagnostic[] = [];
    const journal = coldJournal();
    const run = (args: readonly string[]) => {
      if (fault === "process.stopped" && args.includes("force-stop")) return "";
      if (fault === "adb.permissions" && args.includes("grant") || fault === "adb.stop" && args.includes("force-stop") ||
        fault === "adb.telecom" && args.at(-1) === "telecom" || fault === "adb.notifications" && args.at(-1) === "notification")
        throw new Error("private-content");
      const result = f.run(args);
      if (fault === "cold.completed" && args.includes("exec-out")) return JSON.stringify({ ...journal, events: journal.events.slice(0, 3) });
      if (fault === "cold.telecom-empty" && args.at(-1) === "telecom")
        return "mCalls:\n  [Call id=TC@private-content, state=ACTIVE, other]\nnext:\n";
      if (fault === "cold.notifications-empty" && args.at(-1) === "notification")
        return "Current Notification Manager state:\n Notification List:\n  NotificationRecord(0x123: pkg=expo.modules.callkittelecom.instrumentation user=0 id=123 tag=private-content key=private-content: Notification(category=call))\n mMaxPackageEnqueueRate=5\n";
      return result;
    };
    let time = 0;
    const timing = fault === "cold.budget" ? { now: () => time++ * 40_001, pause: async () => {} } : f.timing;
    await assert.rejects(runColdPushJourney("emulator-5554", coldRequest, run, timing,
      () => { if (fault === "evidence.retain") throw new Error("private-content"); }, failure => failures.push(failure)),
    error => error instanceof VerifierFailure && error.failure.rule === fault);
    assert.equal(failures.length, 1);
    const failure = failures[0]!;
    assert.equal(failure.rule, fault);
    assert.ok(!JSON.stringify(failure).includes("private-content"));
    if (["cold.telecom-empty", "cold.notifications-empty", "adb.telecom", "adb.notifications", "evidence.retain"].includes(fault)) {
      assert.deepEqual(failure.lastColdStage, { stage: "cleaned", timestamp: journal.events[5]!.elapsedRealtimeNanos });
      assert.equal(failure.platform.processStart?.timestamp, journal.events[0]!.elapsedRealtimeNanos);
    }
    if (fault === "cold.telecom-empty") assert.deepEqual(failure.platform.calls,
      [{ identity: "call-1", state: "ACTIVE", hasEndReason: false }]);
    if (fault === "cold.notifications-empty") assert.deepEqual(failure.platform.callNotifications, [{ identity: "notification-1" }]);
    if (fault === "adb.notifications") assert.deepEqual(failure.platform.calls, []);
  });

test("a cleanup command failure retains the verifier rule that preceded it", async () => {
  const f = coldDevice();
  let stopped = false;
  let failure: VerifierDiagnostic | undefined;
  await assert.rejects(runColdPushJourney("emulator-5554", coldRequest, args => {
    if (args.includes("force-stop")) {
      if (stopped) throw new Error("private-content");
      stopped = true;
    }
    if (args.at(-1) === "telecom") return "private-content";
    return f.run(args);
  }, f.timing, undefined, value => { failure = value; }), /adb.stop/);
  assert.equal(failure?.rule, "adb.stop");
  assert.equal(failure?.priorFailure?.rule, "telecom.section");
  assert.equal(failure?.lastColdStage?.stage, "cleaned");
  assert.ok(!JSON.stringify(failure).includes("private-content"));
});

test("transient journal rejection is named at the unchanged completion deadline", async () => {
  const f = coldDevice();
  let failure: VerifierDiagnostic | undefined;
  await assert.rejects(runColdPushJourney("emulator-5554", coldRequest,
    args => args.includes("exec-out") ? "private-content" : f.run(args), f.timing, undefined,
    value => { failure = value; }), /cold.completed/);
  assert.equal(failure?.lastJournalFailure?.rule, "journal.json");
  assert.equal(failure?.lastColdStage, null);
});

test("cold-push CLI prints and atomically retains the same named failure and residual probes", t => {
  const directory = mkdtempSync(join(tmpdir(), "cold-push-diagnostics-"));
  t.after(() => rmSync(directory, { recursive: true, force: true }));
  const bin = join(directory, "bin"); mkdirSync(bin);
  const state = join(directory, "device.json");
  writeFileSync(state, JSON.stringify({ pid: "123" }));
  const adb = join(bin, "adb");
  writeFileSync(adb, `#!${process.execPath}
const fs = require('fs');
const path = ${JSON.stringify(state)};
const state = JSON.parse(fs.readFileSync(path, 'utf8'));
const args = process.argv.slice(2), last = args.at(-1);
let output = '';
if (last === 'ro.kernel.qemu') output = '1';
else if (last === 'ro.build.version.sdk') output = '35';
else if (args.includes('force-stop')) { state.pid = ''; fs.writeFileSync(path, JSON.stringify(state)); }
else if (args.some(arg => arg.startsWith('pidof'))) output = state.pid;
else if (args[3]?.includes("'am' 'start'")) {
  state.pid = '456';
  state.request = Object.fromEntries(['callId', 'deliveryId', 'eventId'].map(key => [key, args[3].match(new RegExp("'"+key+"' '([^']+)'"))[1]]));
  fs.writeFileSync(path, JSON.stringify(state)); output = 'Status: ok\\n';
} else if (args.includes('exec-out')) output = JSON.stringify({schemaVersion: 1, processId: 456, events:
  ['processStarted','intake','ringing','presented','answered','cleaned'].map((stage,index) => ({kind:'coldStart', ...state.request,
    delayMillis:0, stage, elapsedRealtimeNanos:String(1000+index)}))});
else if (last === 'telecom') output = 'mCalls:\\n  [Call id=TC@private-content, state=ACTIVE, other]\\nnext:\\n';
else if (last === 'notification') output = 'private-content';
process.stdout.write(output);
`);
  chmodSync(adb, 0o755);
  const output = join(directory, "events.json"); writeFileSync(output, JSON.stringify(log));
  const script = fileURLToPath(new URL("./android-push-faults.ts", import.meta.url));
  const run = () => spawnSync(process.execPath, [script, "emulator-5554", "cold-start", output],
    { encoding: "utf8", timeout: 10_000, env: { ...process.env, PATH: `${bin}:${process.env.PATH}` } });
  const result = run();
  assert.ifError(result.error); assert.equal(result.status, 1); assert.equal(result.stdout, "");
  const failure = JSON.parse(result.stderr) as VerifierDiagnostic;
  assert.equal(failure.rule, "notification.header");
  assert.equal(failure.lastColdStage?.stage, "cleaned");
  assert.equal(failure.lastColdStage?.timestamp, "1005");
  assert.equal(failure.platform.processStart?.timestamp, "1000");
  assert.deepEqual(failure.platform.calls, [{ identity: "call-1", state: "ACTIVE", hasEndReason: false }]);
  assert.equal(failure.platform.callNotifications, null);
  const retained = JSON.parse(readFileSync(output, "utf8"));
  assert.deepEqual(retained.verificationFailure, failure);
  assert.equal(retained.coldStart.journal.events.at(-1).stage, "cleaned");
  assert.ok(!result.stderr.includes("private-content"));
  writeFileSync(output, 'private-content');
  const unreadable = run();
  assert.equal(unreadable.status, 1);
  const inputFailure = JSON.parse(unreadable.stderr) as VerifierDiagnostic;
  assert.equal(inputFailure.rule, "evidence.read");
  assert.deepEqual(JSON.parse(readFileSync(output, "utf8")).verificationFailure, inputFailure);
});
