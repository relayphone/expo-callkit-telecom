import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";

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
  assert.ok(starts.length <= 1, "Ambiguous Android dump section");
  const start = starts[0];
  if (start === undefined) return undefined;
  const indent = lines[start]!.search(/\S/);
  const following = lines.slice(start + 1);
  const end = following.findIndex(line => line.trim() && line.search(/\S/) <= indent);
  assert.ok(end >= 0, "Truncated Android dump section");
  return following.slice(0, end).filter(line => line.trim());
}

/** Read only CallsManager's current calls; history cannot create a live call. */
export function parseTelecomCalls(dump: string): TelecomCall[] {
  const lines = section(dump, "mCalls:");
  assert.ok(lines, "Missing CallsManager evidence");
  const calls = lines.map(line => {
    const match = /^\s*\[Call id=(TC@[^,\]\s]+), state=([A-Z_]+),/.exec(line);
    assert.ok(match, "Unsupported CallsManager call record");
    const state = states.find(value => value === match[2]);
    assert.ok(state, "Unsupported Telecom state");
    const callId = match[1]!;
    let endReason: string | null = null;
    if (["DISCONNECTED", "DISCONNECTING", "ABORTED"].includes(state)) {
      for (const event of dump.split(/\r?\n/)) {
        const fields = /^\s*\d{2}:\d{2}:\d{2}\.\d{3},([^,]+),SET_DISCONNECTED,(.*)$/.exec(event);
        if (fields?.[1] !== callId) continue;
        const reason = /\bReason: \((.*)\) Tone: \(/.exec(fields[2]!);
        assert.ok(reason, "Missing Telecom disconnect reason");
        endReason = reason[1] || null;
      }
    }
    return { callId, state, endReason };
  });
  assert.equal(new Set(calls.map(call => call.callId)).size, calls.length, "Duplicate Telecom identity");
  return calls;
}

/** Exclude archived, snoozed and enqueued notifications, as well as other apps. */
export function parseNotificationPresence(dump: string, packageName: string): NotificationPresence {
  assert.match(packageName, /^(?:[a-zA-Z_]\w*\.)+[a-zA-Z_]\w*$/, "Invalid application package");
  assert.ok(/^Current Notification Manager state:\s*$/m.test(dump), "Missing notification evidence");
  assert.ok(/^\s*mMaxPackageEnqueueRate=/m.test(dump), "Incomplete notification evidence");
  const records = section(dump, "Notification List:") ?? [];
  if (!records.length) assert.ok(!dump.split("mMaxPackageEnqueueRate=", 1)[0]!.includes("NotificationRecord("),
    "Missing active notification section");
  const indent = records.reduce((minimum, line) => Math.min(minimum, line.search(/\S/)), Infinity);
  const notifications: NotificationPresence["notifications"] = [];
  for (const line of records.filter(line => line.search(/\S/) === indent)) {
    const record = /^\s*NotificationRecord\(0x[\da-f]+: pkg=(\S+) user=\S+ id=(-?\d+) tag=.*? key=.*?: Notification\(.*\)\)\s*$/i.exec(line);
    assert.ok(record, "Unsupported notification record");
    if (record[1] !== packageName) continue;
    const id = Number(record[2]);
    assert.ok(Number.isInteger(id) && id >= -2147483648 && id <= 2147483647, "Invalid notification identity");
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
): AndroidEmulatorState {
  assert.match(serial, /^emulator-\d+$/, "An emulator serial is required");
  const command = (args: readonly string[]) => run(["-s", serial, "shell", ...args]);
  assert.equal(command(["getprop", "ro.kernel.qemu"]).trim(), "1", "Selected target is not an emulator");
  assert.ok(["34", "35", "36"].includes(command(["getprop", "ro.build.version.sdk"]).trim()),
    "API 34, 35 or 36 is required");
  const startedNanos = now().toString();
  const calls = parseTelecomCalls(command(["dumpsys", "-t", "5", "telecom"]));
  const notifications = parseNotificationPresence(command(["dumpsys", "-t", "5", "notification"]), packageName);
  return { calls, notifications,
    observation: { clock: "host.hrtime", startedNanos, finishedNanos: now().toString() } };
}

if (import.meta.main) {
  try {
    console.log(JSON.stringify(collectAndroidEmulatorState(process.argv[2], process.argv[3])));
  } catch {
    console.error("Android emulator state query failed.");
    process.exitCode = 1;
  }
}
