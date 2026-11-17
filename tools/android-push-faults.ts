import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { readFileSync, renameSync, writeFileSync } from "node:fs";
import { setTimeout } from "node:timers/promises";

import { commandProbe, diagnostic, kind, observePlatform, requireRule, VerifierFailure, verifierContext, verify,
  type VerifierContext, type VerifierDiagnostic } from "./android-push-faults-diagnostics.ts";
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
  requireRule("record", value !== null && typeof value === "object" && !Array.isArray(value), { kind: "object" }, { kind: kind(value) });
  return value as Record<string, unknown>;
}
function text(value: unknown, maximum: number, field = "identity"): string {
  requireRule("identity", typeof value === "string" && value.trim() && value.length <= maximum && !value.includes("\0"),
    { field, nonempty: true, maximumLength: maximum, containsNul: false },
    { kind: kind(value), length: typeof value === "string" ? value.length : null,
      nonempty: typeof value === "string" && !!value.trim(), containsNul: typeof value === "string" && value.includes("\0") });
  return value;
}
function integer(value: unknown, maximum = Number.MAX_SAFE_INTEGER, field = "integer"): number {
  requireRule("integer", typeof value === "number" && Number.isSafeInteger(value) && value >= 0 && value <= maximum,
    { field, safeInteger: true, minimum: 0, maximum }, { kind: kind(value), safeInteger: Number.isSafeInteger(value),
      withinRange: typeof value === "number" && value >= 0 && value <= maximum });
  return value;
}
function boolean(value: unknown): boolean {
  requireRule("boolean", typeof value === "boolean", { kind: "boolean" }, { kind: kind(value) }); return value;
}
function delivery(value: Record<string, unknown>): Delivery {
  return { callId: text(value.callId, 256, "callId"), deliveryId: text(value.deliveryId, 128, "deliveryId"),
    eventId: text(value.eventId, 512, "eventId"), delayMillis: integer(value.delayMillis, 30_000, "delayMillis") };
}

export function parsePushFaultCommand(value: unknown): PushFaultCommand {
  const command = record(value);
  switch (command.kind) {
    case "reset": return { kind: "reset" };
    case "rebind-empty-token": return { kind: command.kind, deliveryId: text(command.deliveryId, 128) };
    case "deliver": case "drop": case "duplicate": case "cold-start": {
      const request = delivery(command);
      if (command.kind === "duplicate") text(request.deliveryId, 126);
      if (command.kind === "cold-start") requireRule("command.delay", Object.is(request.delayMillis, 0), { delayMillis: 0 }, { delayMillis: request.delayMillis });
      return { kind: command.kind, ...request };
    }
    default: throw new VerifierFailure({ rule: "command.kind", expected: { supported: true }, observed: { supported: false } });
  }
}

/** Times remain decimal strings so device nanoseconds never lose precision in JSON consumers. */
function parseNativePushFaultLog(value: unknown): NativePushFaultLog {
  const log = record(value);
  requireRule("journal.schema", log.schemaVersion === 1, { schemaVersion: 1 }, { matches: log.schemaVersion === 1 });
  const processId = integer(log.processId);
  requireRule("journal.process", processId > 0, { positive: true }, { positive: processId > 0 });
  requireRule("journal.events", Array.isArray(log.events) && log.events.length <= 256,
    { array: true, maximumLength: 256 }, { kind: kind(log.events), length: Array.isArray(log.events) ? log.events.length : null });
  const events = log.events.map((value: unknown): PushFaultEvent => {
    const event = record(value);
    const elapsedRealtimeNanos = text(event.elapsedRealtimeNanos, 19, "elapsedRealtimeNanos");
    requireRule("journal.timestamp", /^\d+$/.test(elapsedRealtimeNanos), { decimal: true }, { decimal: false });
    requireRule("journal.timestamp", BigInt(elapsedRealtimeNanos) <= 9_223_372_036_854_775_807n,
      { signed64Bit: true }, { signed64Bit: false });
    if (event.kind === "coldStart") {
      const stage = event.stage;
      requireRule("journal.stage", stage === "failed" || coldStages.some(value => value === stage), { supported: true }, { supported: false });
      return { kind: "coldStart", ...delivery(event), stage: stage as ColdStage, elapsedRealtimeNanos };
    }
    if (event.kind === "delivery") {
      const phase = event.phase;
      requireRule("journal.phase", phase === "scheduled" || phase === "dropped" || phase === "recognized" ||
        phase === "unrecognized" || phase === "failed" || phase === "cancelled", { supported: true }, { supported: false });
      return { kind: "delivery", ...delivery(event), phase, elapsedRealtimeNanos };
    }
    requireRule("journal.kind", event.kind === "tokenRebind", { supported: true }, { supported: false });
    requireRule("journal.token-state", event.tokenState === "absent" || event.tokenState === "empty", { supported: true }, { supported: false });
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
  if (previousProcessId !== null) requireRule("journal.process", previousProcessId > 0, { positive: true }, { positive: false });
  const journal = parseNativePushFaultLog(proof.journal);
  requireRule("process.new", !Object.is(journal.processId, previousProcessId), { newProcess: true }, { newProcess: false });
  return { ...native, coldStart: { previousProcessId, journal } };
}

/** Derive durations exclusively from the new process's device monotonic timestamps. */
export function coldPushTimings(receipt: ColdPushReceipt, request: Delivery) {
  const log = receipt.journal;
  requireRule("process.new", !Object.is(log.processId, receipt.previousProcessId), { newProcess: true }, { newProcess: false });
  const stages = log.events.map(event => event.kind === "coldStart" ? event.stage : event.kind);
  verify("cold.stages", { stages: coldStages }, { stages }, () => assert.deepEqual(stages, coldStages));
  for (const event of log.events) verify("cold.identity",
    { callMatches: true, deliveryMatches: true, eventMatches: true, delayMillis: request.delayMillis },
    { callMatches: "callId" in event && event.callId === request.callId, deliveryMatches: event.deliveryId === request.deliveryId,
      eventMatches: "eventId" in event && event.eventId === request.eventId,
      delayMillis: "delayMillis" in event ? event.delayMillis : null },
    () => assert.deepEqual(delivery(record(event)), request));
  const times = log.events.map(event => BigInt(event.elapsedRealtimeNanos));
  requireRule("cold.monotonic", times.every((time, index) => index === 0 || time >= times[index - 1]!),
    { monotonic: true }, { monotonic: false });
  return { clock: "android.elapsedRealtimeNanos" as const,
    processToIntakeNanos: (times[1]! - times[0]!).toString(),
    intakeToPresentationNanos: (times[3]! - times[1]!).toString(),
    processToPresentationNanos: (times[3]! - times[0]!).toString(),
    processToAnswerNanos: (times[4]! - times[0]!).toString() };
}

function emulator(serial: string, run: Execute): void {
  requireRule("target.serial", typeof serial === "string" && /^emulator-\d+$/.test(serial), { emulatorSerial: true }, { emulatorSerial: false });
  requireRule("target.emulator", commandProbe("adb.emulator", () => run(["-s", serial, "shell", "getprop", "ro.kernel.qemu"])).trim() === "1",
    { emulator: true }, { emulator: false });
  requireRule("target.api", ["34", "35", "36"].includes(commandProbe("adb.api", () => run(["-s", serial, "shell", "getprop", "ro.build.version.sdk"])).trim()),
    { supported: true }, { supported: false });
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
    const result = commandProbe("adb.launch", () => run(["-s", serial, "shell", args.map(quote).join(" ")]));
    requireRule("activity.started", /^Status: ok\r?$/m.test(result), { statusOk: true }, { statusOk: false });
    requireRule("activity.result", !/Error:|Exception/.test(result), { errorMarker: false }, { errorMarker: true });
  }
}

/** Reject a stale log from a previous process, including before the new process flushes its file. */
export function readPushFaultLog(serial: string, run: Execute = execute): PushFaultLog {
  emulator(serial, run);
  const pid = commandProbe("adb.pid", () => run(["-s", serial, "shell", "pidof", app])).trim();
  requireRule("process.pid", /^[1-9]\d*$/.test(pid), { positivePid: true }, { positivePid: false });
  const raw = commandProbe("adb.journal", () => run(["-s", serial, "exec-out", "run-as", app,
    "cat", "files/push-fault-events.json"]));
  const log = parsePushFaultLog(verify("journal.json", { validJson: true }, { validJson: false }, () => JSON.parse(raw)));
  requireRule("process.journal", log.processId === Number(pid), { matchesRunningProcess: true }, { matchesRunningProcess: false });
  return log;
}

/** Force-stop first, then explicitly cold-launch the shell fixture carrying the push into the real intake. */
export async function runColdPushJourney(serial: string, input: Delivery, run: Execute = execute,
  timing = { now: () => performance.now(), pause: (ms: number) => setTimeout(ms) },
  retain: (receipt: ColdPushReceipt) => void = () => {},
  retainFailure: (failure: VerifierDiagnostic) => void = () => {}): Promise<ColdPushReceipt> {
  const context = verifierContext();
  try { return await coldPushJourney(serial, input, run, timing, retain, context); }
  catch (error) {
    try { retainFailure(diagnostic(error, context)); } catch { /* Preserve the verifier's failure. */ }
    throw error;
  }
}

async function coldPushJourney(serial: string, input: Delivery, run: Execute,
  timing: { now: () => number; pause: (ms: number) => Promise<unknown> },
  retain: (receipt: ColdPushReceipt) => void, context: VerifierContext): Promise<ColdPushReceipt> {
  const request = delivery(record(input));
  requireRule("command.delay", Object.is(request.delayMillis, 0), { delayMillis: 0 }, { delayMillis: request.delayMillis });
  let commandDeadline = timing.now() + 40_000;
  const bounded: Execute = args => {
    const remaining = Math.ceil(commandDeadline - timing.now());
    requireRule("cold.budget", remaining > 0, { remainingPositive: true }, { remainingMillis: remaining });
    const result = run(args, Math.min(15_000, remaining));
    requireRule("cold.budget", timing.now() < commandDeadline, { remainingPositive: true }, { remainingMillis: commandDeadline - timing.now() });
    return result;
  };
  emulator(serial, bounded);
  const pid = () => {
    const value = commandProbe("adb.pid", () => bounded(["-s", serial, "shell", `pidof ${app} || true`])).trim();
    requireRule("process.pid", /^(?:[1-9]\d*)?$/.test(value), { optionalPid: true }, { optionalPid: false });
    return value ? integer(Number(value), 2_147_483_647) : null;
  };
  const previousProcessId = pid();
  for (const permission of ["RECORD_AUDIO", "POST_NOTIFICATIONS"])
    commandProbe("adb.permissions", () => bounded(["-s", serial, "shell", "pm", "grant", app, `android.permission.${permission}`]));
  const stopArgs = ["-s", serial, "shell", "am", "force-stop", app];
  commandProbe("adb.stop", () => bounded(stopArgs));
  const stoppedPid = pid();
  requireRule("process.stopped", stoppedPid === null, { processPresent: false }, { processPresent: stoppedPid !== null });
  let passed = false;
  let stages: string[] = [];
  try {
    triggerPushFault(serial, { ...request, kind: "cold-start" }, bounded);
    commandDeadline = Math.min(commandDeadline, timing.now() + 20_000);
    while (timing.now() < commandDeadline) {
      let journal: NativePushFaultLog | undefined;
      try { journal = parseNativePushFaultLog(readPushFaultLog(serial, bounded)); }
      catch (error) { if (error instanceof VerifierFailure) context.lastJournalFailure = error.failure; }
      if (journal) {
        const receipt = { previousProcessId, journal };
        const last = journal.events.findLast(event => event.kind === "coldStart");
        if (last?.kind === "coldStart") context.lastColdStage = { stage: last.stage, timestamp: last.elapsedRealtimeNanos };
        const start = journal.events.find(event => event.kind === "coldStart" && event.stage === "processStarted");
        if (start) context.platform.processStart = { clock: "android.elapsedRealtimeNanos", timestamp: start.elapsedRealtimeNanos };
        commandProbe("evidence.retain", () => retain(receipt));
        stages = journal.events.map(event => event.kind === "coldStart" ? event.stage : event.kind);
        if (stages.includes("cleaned")) {
          coldPushTimings(receipt, request);
          commandDeadline = Math.min(commandDeadline, timing.now() + 5000);
          let state = collectAndroidEmulatorState(serial, app, bounded, undefined, state => observePlatform(context, state));
          while (state.calls.length !== 0 || state.notifications.callPresent) {
            await timing.pause(100);
            if (timing.now() >= commandDeadline) break;
            state = collectAndroidEmulatorState(serial, app, bounded, undefined, state => observePlatform(context, state));
          }
          requireRule("cold.telecom-empty", state.calls.length === 0, { remainingCalls: 0 }, { remainingCalls: state.calls.length });
          requireRule("cold.notifications-empty", state.notifications.callPresent === false, { callPresent: false },
            { callPresent: state.notifications.callPresent, remainingCallNotifications: state.notifications.notifications.filter(value => value.isCall).length });
          passed = true;
          return receipt;
        }
      }
      await timing.pause(100);
    }
    throw new VerifierFailure({ rule: "cold.completed", expected: { cleaned: true }, observed: { stages } });
  } catch (error) {
    if (error instanceof VerifierFailure) context.priorFailure = error.failure;
    throw error;
  } finally { if (!passed) commandProbe("adb.stop", () => run(stopArgs, 5000)); }
}

if (import.meta.main) {
  let output: string | undefined;
  let previous: PushFaultLog | undefined;
  let failure: VerifierDiagnostic | undefined;
  try {
    const serial = process.argv[2] ?? "emulator-5554";
    if (serial === "--file") {
      assert.ok(process.argv[3]);
      console.log(JSON.stringify(parsePushFaultLog(JSON.parse(readFileSync(process.argv[3], "utf8")))));
    } else {
      const command = process.argv[3] ?? "events";
      if (command === "cold-start") {
        output = process.argv[4];
        previous = output ? commandProbe("evidence.read", () => parsePushFaultLog(JSON.parse(readFileSync(output!, "utf8")))) : undefined;
        const id = `cold-${process.hrtime.bigint()}`;
        const request = { callId: id, deliveryId: `${id}-delivery`, eventId: `${id}-invite`, delayMillis: 0 };
        const receipt = await runColdPushJourney(serial, request, execute, undefined, coldStart => {
          if (output && previous) {
            writeFileSync(`${output}.tmp`, JSON.stringify({ ...previous, coldStart }));
            renameSync(`${output}.tmp`, output);
          }
        }, value => { failure = value; });
        console.log(JSON.stringify({ ...receipt, timings: coldPushTimings(receipt, request) }));
      } else {
        if (command !== "events") triggerPushFault(serial, parsePushFaultCommand(JSON.parse(command)));
        console.log(JSON.stringify(readPushFaultLog(serial)));
      }
    }
  } catch (error) {
    failure ??= diagnostic(error);
    console.error(JSON.stringify(failure));
    if (output) {
      let retained = previous;
      try { retained = parsePushFaultLog(JSON.parse(readFileSync(output, "utf8"))); } catch { /* Retain diagnostics even for missing or malformed input. */ }
      try {
        writeFileSync(`${output}.tmp`, JSON.stringify({ ...retained, verificationFailure: failure }));
        renameSync(`${output}.tmp`, output);
      } catch { /* Printed diagnostics preserve the original failure if persistence is unavailable. */ }
    }
    process.exitCode = 1;
  }
}
