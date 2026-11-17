import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { readFileSync, renameSync, writeFileSync } from "node:fs";
import { setTimeout } from "node:timers/promises";
import { collectAndroidEmulatorState } from "./check-android-journey-state.ts";

const app = "expo.modules.callkittelecom.instrumentation";
const activity = `${app}/expo.modules.callkittelecom.conversations.PushFaultActivity`;
type Execute = (args: readonly string[], timeoutMillis?: number) => string;
const execute: Execute = (args, timeoutMillis = 15_000) => execFileSync("adb", [...args], {
  encoding: "utf8", timeout: timeoutMillis, killSignal: "SIGKILL",
  maxBuffer: args.includes("dumpsys") ? 8 * 1024 * 1024 : 512 * 1024,
  stdio: ["ignore", "pipe", "pipe"],
});

type Delivery = { callId: string; deliveryId: string; eventId: string; delayMillis: number };
export type PushFaultCommand = (Delivery & { kind: "deliver" | "drop" | "duplicate" })
  | (Delivery & { kind: "cold-start" })
  | { kind: "rebind-empty-token"; deliveryId: string } | { kind: "reset" };
type Phase = "scheduled" | "dropped" | "recognized" | "unrecognized" | "failed" | "cancelled";
const coldStages = ["processStarted", "intake", "ringing", "presented", "answered", "cleaned"] as const;
type ColdStage = (typeof coldStages)[number] | "failed";
export type PushFaultEvent = (Delivery & { kind: "delivery"; phase: Phase; elapsedRealtimeNanos: string })
  | (Delivery & { kind: "coldStart"; stage: ColdStage; elapsedRealtimeNanos: string })
  | { kind: "tokenRebind"; deliveryId: string; tokenState: "absent" | "empty";
    tokenPresent: boolean; tokenEmpty: boolean; revisionBefore: number; revisionAfter: number;
    sameSnapshot: boolean; elapsedRealtimeNanos: string };
type NativePushFaultLog = { schemaVersion: 1; processId: number; events: PushFaultEvent[] };
export type ColdPushReceipt = { previousProcessId: number | null; journal: NativePushFaultLog };
export type PushFaultLog = NativePushFaultLog & { coldStart?: ColdPushReceipt };

function record(value: unknown): Record<string, unknown> {
  assert.ok(value !== null && typeof value === "object" && !Array.isArray(value), "Expected a record");
  return value as Record<string, unknown>;
}
function text(value: unknown, maximum: number): string {
  assert.ok(typeof value === "string" && value.trim() && value.length <= maximum && !value.includes("\0"), "Invalid identity");
  return value;
}
function integer(value: unknown, maximum = Number.MAX_SAFE_INTEGER): number {
  assert.ok(typeof value === "number" && Number.isSafeInteger(value) && value >= 0 && value <= maximum, "Invalid integer");
  return value;
}
function boolean(value: unknown): boolean { assert.equal(typeof value, "boolean"); return value as boolean; }
function delivery(value: Record<string, unknown>): Delivery {
  return { callId: text(value.callId, 256), deliveryId: text(value.deliveryId, 128),
    eventId: text(value.eventId, 512), delayMillis: integer(value.delayMillis, 30_000) };
}

export function parsePushFaultCommand(value: unknown): PushFaultCommand {
  const command = record(value);
  switch (command.kind) {
    case "reset": return { kind: "reset" };
    case "rebind-empty-token": return { kind: command.kind, deliveryId: text(command.deliveryId, 128) };
    case "deliver": case "drop": case "duplicate": case "cold-start": {
      const request = delivery(command);
      if (command.kind === "duplicate") text(request.deliveryId, 126);
      if (command.kind === "cold-start") assert.equal(request.delayMillis, 0);
      return { kind: command.kind, ...request };
    }
    default: throw new Error("Unknown push fixture command");
  }
}

/** Times remain decimal strings so device nanoseconds never lose precision in JSON consumers. */
function parseNativePushFaultLog(value: unknown): NativePushFaultLog {
  const log = record(value);
  assert.equal(log.schemaVersion, 1);
  const processId = integer(log.processId);
  assert.ok(processId > 0);
  assert.ok(Array.isArray(log.events) && log.events.length <= 256);
  const events = log.events.map((value: unknown): PushFaultEvent => {
    const event = record(value);
    const elapsedRealtimeNanos = text(event.elapsedRealtimeNanos, 19);
    assert.match(elapsedRealtimeNanos, /^\d+$/);
    assert.ok(BigInt(elapsedRealtimeNanos) <= 9_223_372_036_854_775_807n);
    if (event.kind === "coldStart") {
      const stage = event.stage;
      assert.ok(stage === "failed" || coldStages.some(value => value === stage), "Unknown cold-start stage");
      return { kind: "coldStart", ...delivery(event), stage: stage as ColdStage, elapsedRealtimeNanos };
    }
    if (event.kind === "delivery") {
      const phase = event.phase;
      assert.ok(phase === "scheduled" || phase === "dropped" || phase === "recognized" ||
        phase === "unrecognized" || phase === "failed" || phase === "cancelled", "Unknown delivery phase");
      return { kind: "delivery", ...delivery(event), phase, elapsedRealtimeNanos };
    }
    assert.equal(event.kind, "tokenRebind");
    assert.ok(event.tokenState === "absent" || event.tokenState === "empty");
    return { kind: "tokenRebind", deliveryId: text(event.deliveryId, 128), tokenState: event.tokenState,
      tokenPresent: boolean(event.tokenPresent), tokenEmpty: boolean(event.tokenEmpty),
      revisionBefore: integer(event.revisionBefore), revisionAfter: integer(event.revisionAfter),
      sameSnapshot: boolean(event.sameSnapshot), elapsedRealtimeNanos };
  });
  return { schemaVersion: 1, processId, events };
}

/** A host receipt keeps the earlier fault journal intact alongside the new process's journal. */
export function parsePushFaultLog(value: unknown): PushFaultLog {
  const native = parseNativePushFaultLog(value);
  const cold = record(value).coldStart;
  if (cold === undefined) return native;
  const proof = record(cold);
  const previousProcessId = proof.previousProcessId === null ? null : integer(proof.previousProcessId);
  if (previousProcessId !== null) assert.ok(previousProcessId > 0);
  const journal = parseNativePushFaultLog(proof.journal);
  assert.notEqual(journal.processId, previousProcessId, "Cold start reused the previous process");
  return { ...native, coldStart: { previousProcessId, journal } };
}

/** Derive durations exclusively from the new process's device monotonic timestamps. */
export function coldPushTimings(receipt: ColdPushReceipt, request: Delivery) {
  const log = receipt.journal;
  assert.notEqual(log.processId, receipt.previousProcessId);
  assert.deepEqual(log.events.map(event => event.kind === "coldStart" ? event.stage : event.kind), coldStages,
    "Cold push did not ring, present, answer and clean up in order");
  for (const event of log.events) assert.deepEqual(delivery(record(event)), request, "Cold push identity changed");
  const times = log.events.map(event => BigInt(event.elapsedRealtimeNanos));
  assert.ok(times.every((time, index) => index === 0 || time >= times[index - 1]!), "Cold push time moved backwards");
  return { clock: "android.elapsedRealtimeNanos" as const,
    processToIntakeNanos: (times[1]! - times[0]!).toString(),
    intakeToPresentationNanos: (times[3]! - times[1]!).toString(),
    processToPresentationNanos: (times[3]! - times[0]!).toString(),
    processToAnswerNanos: (times[4]! - times[0]!).toString() };
}

function emulator(serial: string, run: Execute): void {
  assert.match(serial, /^emulator-\d+$/, "An emulator serial is required");
  assert.equal(run(["-s", serial, "shell", "getprop", "ro.kernel.qemu"]).trim(), "1");
  assert.ok(["34", "35", "36"].includes(run(["-s", serial, "shell", "getprop", "ro.build.version.sdk"]).trim()),
    "API 34, 35 or 36 is required");
}
// adb shell joins arguments before its remote shell; quote every value at that boundary.
function quote(value: string): string { return `'${value.replaceAll("'", "'\\''")}'`; }

/** Inject at the app-owned intake seam; this does not send FCM or bypass a force-stop. */
export function triggerPushFault(serial: string, input: PushFaultCommand, run: Execute = execute): void {
  const command = parsePushFaultCommand(input);
  emulator(serial, run);
  const requests = command.kind === "duplicate"
    ? [1, 2].map(copy => ({ ...command, kind: "deliver" as const, deliveryId: `${command.deliveryId}.${copy}` }))
    : [command];
  for (const request of requests) {
    const args = ["am", "start", "-W", "-n", activity, "--es", "command", request.kind];
    if (request.kind !== "reset") args.push("--es", "deliveryId", request.deliveryId);
    if (request.kind === "deliver" || request.kind === "drop" || request.kind === "cold-start") args.push("--es", "callId", request.callId,
      "--es", "eventId", request.eventId, "--el", "delayMillis", String(request.delayMillis));
    const result = run(["-s", serial, "shell", args.map(quote).join(" ")]);
    assert.match(result, /^Status: ok\r?$/m, "Push fixture activity did not start");
    assert.ok(!/Error:|Exception/.test(result), "Push fixture activity failed");
  }
}

/** Reject a stale log from a previous process, including before the new process flushes its file. */
export function readPushFaultLog(serial: string, run: Execute = execute): PushFaultLog {
  emulator(serial, run);
  const pid = run(["-s", serial, "shell", "pidof", app]).trim();
  assert.match(pid, /^[1-9]\d*$/);
  const log = parsePushFaultLog(JSON.parse(run(["-s", serial, "exec-out", "run-as", app,
    "cat", "files/push-fault-events.json"])));
  assert.equal(log.processId, Number(pid), "Stale push fixture log");
  return log;
}

/** Force-stop first, then explicitly cold-launch the shell fixture carrying the push into the real intake. */
export async function runColdPushJourney(serial: string, input: Delivery, run: Execute = execute,
  timing = { now: () => performance.now(), pause: (ms: number) => setTimeout(ms) },
  retain: (receipt: ColdPushReceipt) => void = () => {}): Promise<ColdPushReceipt> {
  const request = delivery(record(input));
  assert.equal(request.delayMillis, 0);
  let commandDeadline = timing.now() + 40_000;
  const bounded: Execute = args => {
    const remaining = Math.ceil(commandDeadline - timing.now());
    assert.ok(remaining > 0, "Cold push command budget expired");
    const result = run(args, Math.min(15_000, remaining));
    assert.ok(timing.now() < commandDeadline, "Cold push command budget expired");
    return result;
  };
  emulator(serial, bounded);
  const pid = () => {
    const value = bounded(["-s", serial, "shell", `pidof ${app} || true`]).trim();
    assert.match(value, /^(?:[1-9]\d*)?$/);
    return value ? integer(Number(value), 2_147_483_647) : null;
  };
  const previousProcessId = pid();
  for (const permission of ["RECORD_AUDIO", "POST_NOTIFICATIONS"])
    bounded(["-s", serial, "shell", "pm", "grant", app, `android.permission.${permission}`]);
  const stopArgs = ["-s", serial, "shell", "am", "force-stop", app];
  bounded(stopArgs);
  assert.equal(pid(), null, "Force-stop left the process alive");
  let passed = false;
  let stages: string[] = [];
  try {
    triggerPushFault(serial, { ...request, kind: "cold-start" }, bounded);
    commandDeadline = Math.min(commandDeadline, timing.now() + 20_000);
    while (timing.now() < commandDeadline) {
      let journal: NativePushFaultLog | undefined;
      try { journal = parseNativePushFaultLog(readPushFaultLog(serial, bounded)); }
      catch { /* A cold launch can precede the first atomic journal flush. Never accept a stale process. */ }
      if (journal) {
        const receipt = { previousProcessId, journal };
        retain(receipt);
        stages = journal.events.map(event => event.kind === "coldStart" ? event.stage : event.kind);
        if (stages.includes("cleaned")) {
          coldPushTimings(receipt, request);
          commandDeadline = Math.min(commandDeadline, timing.now() + 5000);
          let state = collectAndroidEmulatorState(serial, app, bounded);
          while (state.calls.length !== 0 || state.notifications.callPresent) {
            await timing.pause(100);
            if (timing.now() >= commandDeadline) break;
            state = collectAndroidEmulatorState(serial, app, bounded);
          }
          assert.equal(state.calls.length, 0, "Cold push left a Telecom call");
          assert.equal(state.notifications.callPresent, false, "Cold push left a call notification");
          passed = true;
          return receipt;
        }
      }
      await timing.pause(100);
    }
    throw new Error(`Cold push did not complete; last stages: ${stages.join(",") || "unavailable"}`);
  } finally { if (!passed) run(stopArgs, 5000); }
}

if (import.meta.main) {
  try {
    const serial = process.argv[2] ?? "emulator-5554";
    if (serial === "--file") {
      assert.ok(process.argv[3]);
      console.log(JSON.stringify(parsePushFaultLog(JSON.parse(readFileSync(process.argv[3], "utf8")))));
    } else {
      const command = process.argv[3] ?? "events";
      if (command === "cold-start") {
        const output = process.argv[4];
        const previous = output ? parsePushFaultLog(JSON.parse(readFileSync(output, "utf8"))) : undefined;
        const id = `cold-${process.hrtime.bigint()}`;
        const request = { callId: id, deliveryId: `${id}-delivery`, eventId: `${id}-invite`, delayMillis: 0 };
        const receipt = await runColdPushJourney(serial, request, execute, undefined, coldStart => {
          if (output && previous) {
            writeFileSync(`${output}.tmp`, JSON.stringify({ ...previous, coldStart }));
            renameSync(`${output}.tmp`, output);
          }
        });
        console.log(JSON.stringify({ ...receipt, timings: coldPushTimings(receipt, request) }));
      } else {
        if (command !== "events") triggerPushFault(serial, parsePushFaultCommand(JSON.parse(command)));
        console.log(JSON.stringify(readPushFaultLog(serial)));
      }
    }
  } catch { console.error("Android push fixture failed."); process.exitCode = 1; }
}
