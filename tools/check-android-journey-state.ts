import { execFileSync } from "node:child_process";
import { writeFileSync } from "node:fs";

import { commandProbe, diagnostic, observePlatform, requireRule, verifierContext } from "./android-push-faults-diagnostics.ts";

const states = [
  "NEW", "CONNECTING", "SELECT_PHONE_ACCOUNT", "DIALING", "RINGING", "ACTIVE",
  "ON_HOLD", "DISCONNECTED", "ABORTED", "DISCONNECTING", "PULLING", "ANSWERED",
  "AUDIO_PROCESSING", "SIMULATED_RINGING", "UNKNOWN",
] as const;
export interface TelecomCall {
  /** Telecom's TC@ identity, not an application conversation ID. */
  callId: string;
  state: (typeof states)[number];
  endReason: string | null;
}
export interface NotificationPresence {
  packageName: string;
  present: boolean;
  callPresent: boolean;
  notifications: { id: number; isCall: boolean }[];
}
export interface AndroidEmulatorState {
  calls: TelecomCall[];
  notifications: NotificationPresence;
  /** Sequential dumps are observations within this host monotonic interval. */
  observation: { clock: "host.hrtime"; startedNanos: string; finishedNanos: string };
}

function section(dump: string, heading: string): string[] | undefined {
  const lines = dump.split(/\r?\n/);
  const starts = lines.flatMap((line, index) => line.trim() === heading ? [index] : []);
  requireRule("dump.unique", starts.length <= 1, { maximumSections: 1 }, { sections: starts.length });
  const start = starts[0];
  if (start === undefined) return undefined;
  const indent = lines[start]!.search(/\S/);
  const following = lines.slice(start + 1);
  const end = following.findIndex(line => line.trim() && line.search(/\S/) <= indent);
  requireRule("dump.complete", end >= 0, { terminated: true }, { terminated: end >= 0 });
  return following.slice(0, end).filter(line => line.trim());
}

/** Read only CallsManager's current calls; history cannot create a live call. */
export function parseTelecomCalls(dump: string): TelecomCall[] {
  const lines = section(dump, "mCalls:");
  requireRule("telecom.section", lines, { present: true }, { present: !!lines });
  const calls = lines.map(line => {
    const match = /^\s*\[Call id=(TC@[^,\]\s]+), state=([A-Z_]+),/.exec(line);
    const stateToken = /\bstate=([A-Z_]+)/.exec(line)?.[1];
    requireRule("telecom.record", match,
      { callPrefix: true, platformIdentity: true, identityComma: true, stateComma: true },
      { callPrefix: line.trimStart().startsWith("[Call id="), platformIdentity: /\bid=TC@/.test(line),
        identityComma: /\bid=TC@[^,\]\s]+,/.test(line), stateComma: /\bstate=[A-Z_]+,/.test(line),
        state: states.find(value => value === stateToken) ?? "unsupported-or-missing",
        indentation: line.search(/\S/), length: line.length });
    const state = states.find(value => value === match[2]);
    requireRule("telecom.state", state, { supported: true }, { supported: !!state });
    const callId = match[1]!;
    let endReason: string | null = null;
    if (["DISCONNECTED", "DISCONNECTING", "ABORTED"].includes(state)) {
      for (const event of dump.split(/\r?\n/)) {
        const fields = /^\s*\d{2}:\d{2}:\d{2}\.\d{3},([^,]+),SET_DISCONNECTED,(.*)$/.exec(event);
        if (fields?.[1] !== callId) continue;
        const reason = /\bReason: \((.*)\) Tone: \(/.exec(fields[2]!);
        requireRule("telecom.reason", reason, { present: true }, { present: !!reason });
        endReason = reason[1] || null;
      }
    }
    return { callId, state, endReason };
  });
  requireRule("telecom.unique", new Set(calls.map(call => call.callId)).size === calls.length,
    { uniqueCalls: calls.length }, { uniqueCalls: new Set(calls.map(call => call.callId)).size });
  return calls;
}

/** Exclude archived, snoozed and enqueued notifications, as well as other apps. */
export function parseNotificationPresence(dump: string, packageName: string): NotificationPresence {
  requireRule("notification.package", typeof packageName === "string" && /^(?:[a-zA-Z_]\w*\.)+[a-zA-Z_]\w*$/.test(packageName),
    { valid: true }, { valid: false });
  requireRule("notification.header", /^Current Notification Manager state:\s*$/m.test(dump), { present: true }, { present: false });
  requireRule("notification.complete", /^\s*mMaxPackageEnqueueRate=/m.test(dump), { complete: true }, { complete: false });
  const records = section(dump, "Notification List:") ?? [];
  if (!records.length) requireRule("notification.section", !dump.split("mMaxPackageEnqueueRate=", 1)[0]!.includes("NotificationRecord("),
    { activeSection: true }, { activeSection: false });
  const indent = records.reduce((minimum, line) => Math.min(minimum, line.search(/\S/)), Infinity);
  const notifications: NotificationPresence["notifications"] = [];
  for (const line of records.filter(line => line.search(/\S/) === indent)) {
    const record = /^\s*NotificationRecord\(0x[\da-f]+: pkg=(\S+) user=\S+ id=(-?\d+) tag=.*? key=.*?: Notification\(.*\)\)\s*$/i.exec(line);
    requireRule("notification.record", record,
      { recordPrefix: true, packageField: true, integerIdentity: true, notificationPayload: true, closingRecord: true },
      { recordPrefix: /^\s*NotificationRecord\(0x[\da-f]+:/i.test(line), packageField: /\bpkg=\S+/.test(line),
        integerIdentity: /\bid=-?\d+\s/.test(line), notificationPayload: /: Notification\(/.test(line),
        closingRecord: /\)\)\s*$/.test(line), indentation: line.search(/\S/), length: line.length });
    if (record[1] !== packageName) continue;
    const id = Number(record[2]);
    requireRule("notification.identity", Number.isInteger(id) && id >= -2147483648 && id <= 2147483647,
      { signed32Bit: true }, { signed32Bit: false });
    notifications.push({ id, isCall: /\bcategory=call(?:\s|\))/.test(line.split(" publicVersion=", 1)[0]!) });
  }
  return { packageName, present: notifications.length > 0,
    callPresent: notifications.some(notification => notification.isCall), notifications };
}

type Execute = (args: readonly string[]) => string;
const execute: Execute = args => execFileSync("adb", [...args], {
  encoding: "utf8", timeout: 15_000, maxBuffer: 8 * 1024 * 1024,
  stdio: ["ignore", "pipe", "pipe"],
});

/** Query an explicitly selected emulator. Raw system dumps never leave this function. */
export function collectAndroidEmulatorState(
  serial = "emulator-5554",
  packageName = "expo.modules.callkittelecom.instrumentation",
  run: Execute = execute,
  now: () => bigint = process.hrtime.bigint,
  observe: (state: Partial<AndroidEmulatorState>) => void = () => {},
): AndroidEmulatorState {
  requireRule("target.serial", typeof serial === "string" && /^emulator-\d+$/.test(serial), { emulatorSerial: true }, { emulatorSerial: false });
  const command = (rule: "adb.emulator" | "adb.api" | "adb.telecom" | "adb.notifications", args: readonly string[]) =>
    commandProbe(rule, () => run(["-s", serial, "shell", ...args]));
  requireRule("target.emulator", command("adb.emulator", ["getprop", "ro.kernel.qemu"]).trim() === "1",
    { emulator: true }, { emulator: false });
  requireRule("target.api", ["34", "35", "36"].includes(command("adb.api", ["getprop", "ro.build.version.sdk"]).trim()),
    { supported: true }, { supported: false });
  const startedNanos = now().toString();
  const calls = parseTelecomCalls(command("adb.telecom", ["dumpsys", "-t", "5", "telecom"]));
  observe({ calls });
  const notifications = parseNotificationPresence(command("adb.notifications", ["dumpsys", "-t", "5", "notification"]), packageName);
  observe({ notifications });
  return { calls, notifications,
    observation: { clock: "host.hrtime", startedNanos, finishedNanos: now().toString() } };
}

if (import.meta.main) {
  const context = verifierContext();
  try {
    console.log(JSON.stringify(collectAndroidEmulatorState(process.argv[2], process.argv[3], undefined, undefined, state => observePlatform(context, state))));
  } catch (error) {
    const failure = diagnostic(error, context);
    console.error(JSON.stringify(failure));
    if (process.argv[4]) {
      try { writeFileSync(process.argv[4], JSON.stringify(failure)); } catch { /* Keep the original failure verdict. */ }
    }
    process.exitCode = 1;
  }
}
