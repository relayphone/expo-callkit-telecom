import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { readFileSync } from "node:fs";

const app = "expo.modules.callkittelecom.instrumentation";
const activity = `${app}/expo.modules.callkittelecom.conversations.PushFaultActivity`;
type Execute = (args: readonly string[]) => string;
const execute: Execute = args => execFileSync("adb", [...args], {
  encoding: "utf8", timeout: 15_000, maxBuffer: 512 * 1024,
  stdio: ["ignore", "pipe", "pipe"],
});

type Delivery = { callId: string; deliveryId: string; eventId: string; delayMillis: number };
export type PushFaultCommand = (Delivery & { kind: "deliver" | "drop" | "duplicate" })
  | { kind: "rebind-empty-token"; deliveryId: string } | { kind: "reset" };
type Phase = "scheduled" | "dropped" | "recognized" | "unrecognized" | "failed" | "cancelled";
export type PushFaultEvent = (Delivery & { kind: "delivery"; phase: Phase; elapsedRealtimeNanos: string })
  | { kind: "tokenRebind"; deliveryId: string; tokenState: "absent" | "empty";
    tokenPresent: boolean; tokenEmpty: boolean; revisionBefore: number; revisionAfter: number;
    sameSnapshot: boolean; elapsedRealtimeNanos: string };
export type PushFaultLog = { schemaVersion: 1; processId: number; events: PushFaultEvent[] };

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
    case "deliver": case "drop": case "duplicate": {
      const request = delivery(command);
      if (command.kind === "duplicate") text(request.deliveryId, 126);
      return { kind: command.kind, ...request };
    }
    default: throw new Error("Unknown push fixture command");
  }
}

/** Times remain decimal strings so device nanoseconds never lose precision in JSON consumers. */
export function parsePushFaultLog(value: unknown): PushFaultLog {
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

function emulator(serial: string, run: Execute): void {
  assert.match(serial, /^emulator-\d+$/, "An emulator serial is required");
  assert.equal(run(["-s", serial, "shell", "getprop", "ro.kernel.qemu"]).trim(), "1");
  assert.equal(run(["-s", serial, "shell", "getprop", "ro.build.version.sdk"]).trim(), "35");
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
    if (request.kind === "deliver" || request.kind === "drop") args.push("--es", "callId", request.callId,
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

if (import.meta.main) {
  try {
    const serial = process.argv[2] ?? "emulator-5554";
    if (serial === "--file") {
      assert.ok(process.argv[3]);
      console.log(JSON.stringify(parsePushFaultLog(JSON.parse(readFileSync(process.argv[3], "utf8")))));
    } else {
      const command = process.argv[3] ?? "events";
      if (command !== "events") triggerPushFault(serial, parsePushFaultCommand(JSON.parse(command)));
      console.log(JSON.stringify(readPushFaultLog(serial)));
    }
  } catch { console.error("Android push fixture failed."); process.exitCode = 1; }
}
