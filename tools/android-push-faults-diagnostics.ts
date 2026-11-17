import assert from "node:assert/strict";

import type { AndroidEmulatorState } from "./check-android-journey-state.ts";
import type { PushFaultEvent } from "./android-push-faults.ts";

export const verifierRules = {
  record: "Expected a record", identity: "Invalid identity", integer: "Invalid integer", boolean: "Invalid boolean",
  "command.kind": "Unknown push fixture command", "command.delay": "Cold push delay must be zero",
  "journal.schema": "Unsupported journal schema", "journal.process": "Invalid journal process",
  "journal.events": "Invalid journal events", "journal.timestamp": "Invalid journal timestamp",
  "journal.stage": "Unknown cold-start stage", "journal.phase": "Unknown delivery phase",
  "journal.kind": "Unknown journal event", "journal.token-state": "Invalid token state",
  "process.new": "Cold start reused the previous process", "process.pid": "Invalid process observation",
  "process.journal": "Stale push fixture log", "process.stopped": "Force-stop left the process alive",
  "cold.stages": "Cold push did not ring, present, answer and clean up in order",
  "cold.identity": "Cold push identity changed", "cold.monotonic": "Cold push time moved backwards",
  "cold.budget": "Cold push command budget expired", "cold.completed": "Cold push did not complete",
  "cold.telecom-empty": "Cold push left a Telecom call", "cold.notifications-empty": "Cold push left a call notification",
  "target.serial": "An emulator serial is required", "target.emulator": "Selected target is not an emulator",
  "target.api": "API 34, 35 or 36 is required", "activity.started": "Push fixture activity did not start",
  "activity.result": "Push fixture activity failed", "journal.json": "Invalid journal JSON",
  "dump.unique": "Ambiguous Android dump section", "dump.complete": "Truncated Android dump section",
  "telecom.section": "Missing CallsManager evidence", "telecom.record": "Unsupported CallsManager call record",
  "telecom.state": "Unsupported Telecom state", "telecom.reason": "Missing Telecom disconnect reason",
  "telecom.unique": "Duplicate Telecom identity", "notification.package": "Invalid application package",
  "notification.header": "Missing notification evidence", "notification.complete": "Incomplete notification evidence",
  "notification.section": "Missing active notification section", "notification.record": "Unsupported notification record",
  "notification.identity": "Invalid notification identity",
  "adb.emulator": "Emulator property query failed", "adb.api": "API property query failed",
  "adb.pid": "Process query failed", "adb.permissions": "Permission grant failed",
  "adb.stop": "Force-stop command failed", "adb.launch": "Activity launch command failed",
  "adb.journal": "Journal read failed", "adb.telecom": "Telecom query failed",
  "adb.notifications": "Notification query failed", "evidence.read": "Evidence read failed",
  "evidence.retain": "Evidence retention failed",
} as const;
export type VerifierRule = keyof typeof verifierRules;
export type Fields = Readonly<Record<string, string | number | boolean | null | readonly string[]>>;
export type RuleFailure = { rule: VerifierRule; expected: Fields; observed: Fields };
export class VerifierFailure extends Error {
  readonly failure: RuleFailure;
  constructor(failure: RuleFailure) { super(`${failure.rule}: ${verifierRules[failure.rule]}`); this.failure = failure; }
}
export function verify<T>(rule: VerifierRule, expected: Fields, observed: Fields, body: () => T): T {
  try { return body(); } catch (error) {
    if (error instanceof VerifierFailure) throw error;
    throw new VerifierFailure({ rule, expected, observed });
  }
}
export function requireRule(rule: VerifierRule, condition: unknown, expected: Fields, observed: Fields): asserts condition {
  verify(rule, expected, observed, () => assert.ok(condition));
}
export function kind(value: unknown): string { return value === null ? "null" : Array.isArray(value) ? "array" : typeof value; }
export function commandProbe<T>(rule: VerifierRule, body: () => T): T {
  try { return body(); } catch (error) {
    if (error instanceof VerifierFailure) throw error;
    const fields = error !== null && typeof error === "object" ? error as Record<string, unknown> : {};
    throw new VerifierFailure({ rule, expected: { completed: true }, observed: {
      completed: false, exitStatus: typeof fields.status === "number" && Number.isSafeInteger(fields.status) ? fields.status : null,
      timedOut: fields.code === "ETIMEDOUT", killed: fields.signal === "SIGKILL" || fields.signal === "SIGTERM",
    } });
  }
}
export type PlatformProbes = {
  /** Null means the existing verifier did not reach or parse this probe; it never means empty. */
  calls: { identity: string; state: AndroidEmulatorState["calls"][number]["state"]; hasEndReason: boolean }[] | null;
  callNotifications: { identity: string }[] | null;
  processStart: { clock: "android.elapsedRealtimeNanos"; timestamp: string } | null;
};
export type VerifierContext = {
  lastColdStage: { stage: Extract<PushFaultEvent, { kind: "coldStart" }>["stage"]; timestamp: string } | null;
  platform: PlatformProbes;
  lastJournalFailure: RuleFailure | null;
  priorFailure: RuleFailure | null;
};
export type VerifierDiagnostic = VerifierContext & RuleFailure & { schemaVersion: 1 };
export function verifierContext(): VerifierContext {
  return { lastColdStage: null, platform: { calls: null, callNotifications: null, processStart: null },
    lastJournalFailure: null, priorFailure: null };
}
export function observePlatform(context: VerifierContext, state: Partial<AndroidEmulatorState>): void {
  if (state.calls) context.platform.calls = state.calls.map((call, index) =>
    ({ identity: `call-${index + 1}`, state: call.state, hasEndReason: call.endReason !== null }));
  if (state.notifications) context.platform.callNotifications = state.notifications.notifications
    .filter(value => value.isCall).map((_, index) => ({ identity: `notification-${index + 1}` }));
}
export function diagnostic(error: unknown, context = verifierContext()): VerifierDiagnostic {
  const failure = error instanceof VerifierFailure ? error.failure :
    { rule: "evidence.read" as const, expected: { completed: true }, observed: { completed: false } };
  return structuredClone({ schemaVersion: 1 as const, ...failure, ...context });
}
