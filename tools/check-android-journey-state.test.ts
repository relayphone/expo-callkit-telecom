import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { chmodSync, mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { test } from "node:test";
import { fileURLToPath } from "node:url";
import { collectAndroidEmulatorState, parseNotificationPresence, parseTelecomCalls } from "./check-android-journey-state.ts";

const app = "expo.modules.callkittelecom.instrumentation";
function telecom(rows: string[] = [], events: string[] = []) {
  return ["CallsManager:", "  mCalls:", ...rows.map(row => `    ${row}`),
    "  mCallAudioManager:", "    mode: NORMAL", "Historical Calls:", ...events].join("\n");
}
function call(id = "TC@3", state = "RINGING") {
  return `[Call id=${id}, state=${state}, tpac=ComponentInfo{test}, cmgr=null, handle=***, vidst=A, childs(0), has_parent(false), cap=[], prop=[]], voip=true`;
}
function ended(id: string, reason: string) {
  return `12:34:56.789,${id},SET_DISCONNECTED,DisconnectCause [ Code: (REMOTE) Label: () Description: () Reason: (${reason}) Tone: (-1) TelephonyCause: -1/-1 ImsReasonInfo: null]`;
}
function notification(id: number, category = "call", packageName = app) {
  return `NotificationRecord(0x1234abcd: pkg=${packageName} user=UserHandle{0} id=${id} tag=null importance=4 key=0|${packageName}|${id}|null|1234: Notification(channel=calls shortcut=null contentView=null flags=ONGOING_EVENT category=${category} vis=PUBLIC))`;
}
function notifications(rows: string[] = [], archive: string[] = []) {
  return ["Current Notification Manager state:",
    ...(rows.length ? ["  Notification List:", ...rows.flatMap(row => [`    ${row}`, "      extras={private-content}"])] : []),
    "  mMaxPackageEnqueueRate=5.0", "  mArchive=Archive (1 notification)",
    ...archive.map(row => `    ${row}`), "  Enqueued Notification List:", "    queued-other-record"].join("\n");
}

test("current Telecom calls use platform IDs and preserve ringing, held and terminal states", () => {
  assert.deepEqual(parseTelecomCalls(telecom([
    call(), call("TC@4", "ON_HOLD"), call("TC@5", "DISCONNECTED"),
  ], [ended("TC@5", "report (busy), later")])), [
    { callId: "TC@3", state: "RINGING", endReason: null },
    { callId: "TC@4", state: "ON_HOLD", endReason: null },
    { callId: "TC@5", state: "DISCONNECTED", endReason: "report (busy), later" },
  ]);
});

test("history cannot create calls or overwrite a live state, and disconnect reasons match exact IDs", () => {
  assert.deepEqual(parseTelecomCalls(telecom([], [call(), ended("TC@3", "remoteEnded")])), []);
  assert.deepEqual(parseTelecomCalls(telecom([call("TC@3", "ACTIVE")], [ended("TC@3", "old")])), [
    { callId: "TC@3", state: "ACTIVE", endReason: null },
  ]);
  for (const reason of ["", "null", "local"]) assert.deepEqual(parseTelecomCalls(telecom([
    call("TC@3", "DISCONNECTING"),
  ], [ended("TC@3", "earlier"), ended("TC@30", "wrong identity"), ended("TC@3", reason)])), [
    { callId: "TC@3", state: "DISCONNECTING", endReason: reason || null },
  ]);
});

test("missing, truncated, ambiguous or unsupported Telecom evidence cannot report an empty device", () => {
  for (const dump of ["", "Permission Denial", "  mCalls:\n", "  mCalls:\n    " + call(),
    telecom([call(), call()]), telecom([call("TC@3", "FUTURE_STATE")]), telecom(["unsupported call"]),
    telecom() + "\n  mCalls:\n  next:",
    telecom([call("TC@3", "DISCONNECTED")], [ended("TC@3", "local").replace("Reason:", "Unknown:")]),
  ]) assert.throws(() => parseTelecomCalls(dump));
});

test("notification presence excludes archives, enqueued records and package prefix matches", () => {
  assert.deepEqual(parseNotificationPresence(notifications([
    notification(-7), notification(8, "msg"), notification(9, "call", `${app}.other`),
  ], [notification(99)]), app), {
    packageName: app, present: true, callPresent: true,
    notifications: [{ id: -7, isCall: true }, { id: 8, isCall: false }],
  });
  assert.deepEqual(parseNotificationPresence(notifications([], [notification(99)]), app), {
    packageName: app, present: false, callPresent: false, notifications: [],
  });
  assert.equal(parseNotificationPresence(notifications([notification(1, "msg")]), app).callPresent, false);
});

test("a call-category public version does not turn an ordinary notification into a call", () => {
  const row = notification(1, "msg").replace("vis=PUBLIC", "vis=PUBLIC publicVersion=Notification(category=call)");
  assert.equal(parseNotificationPresence(notifications([row]), app).callPresent, false);
});

test("malformed notification evidence fails rather than manufacturing absence", () => {
  for (const dump of ["", "Permission Denial", "Current Notification Manager state:",
    notifications([notification(1)]).replace("Notification List:", "Unknown List:"),
    notifications([notification(1)]).replace("NotificationRecord(0x", "UnsupportedRecord(0x"),
    notifications([notification(1)]).replace("id=1 ", "id=999999999999999999999 "),
    notifications([notification(2147483648)]), notifications([notification(1).slice(0, -2)]),
    notifications([notification(1)]) + "\n  Notification List:\n  next:",
  ]) assert.throws(() => parseNotificationPresence(dump, app));
  assert.throws(() => parseNotificationPresence(notifications(), "invalid package"));
  assert.throws(() => parseNotificationPresence("private-content", app), error => !String(error).includes("private-content"));
});

test("the collector pins every read to one API35 emulator and exports only structured fields", () => {
  const commands: string[][] = [];
  let clock = 9007199254740993n;
  const state = collectAndroidEmulatorState("emulator-5570", app, args => {
    commands.push([...args]);
    if (args.at(-1) === "ro.kernel.qemu") return "1\n";
    if (args.at(-1) === "ro.build.version.sdk") return "35\n";
    return args.at(-1) === "telecom" ? telecom([call()]) : notifications([notification(1)]);
  }, () => clock++);
  assert.deepEqual(commands, [
    ["-s", "emulator-5570", "shell", "getprop", "ro.kernel.qemu"],
    ["-s", "emulator-5570", "shell", "getprop", "ro.build.version.sdk"],
    ["-s", "emulator-5570", "shell", "dumpsys", "-t", "5", "telecom"],
    ["-s", "emulator-5570", "shell", "dumpsys", "-t", "5", "notification"],
  ]);
  assert.deepEqual(state.observation, { clock: "host.hrtime", startedNanos: "9007199254740993", finishedNanos: "9007199254740994" });
  assert.equal(state.calls[0]?.state, "RINGING");
  assert.equal(state.notifications.callPresent, true);
  assert.ok(!JSON.stringify(state).includes("private-content"));
});

test("physical serials, non-emulator targets, unsupported APIs and failed commands stay fatal", () => {
  for (const serial of ["device-id", "", "emulator-5554; other"]) assert.throws(() =>
    collectAndroidEmulatorState(serial, app, () => { assert.fail("must not contact target"); }));
  let commands = 0;
  assert.throws(() => collectAndroidEmulatorState("emulator-5554", app, () => { commands++; return "0"; }));
  assert.equal(commands, 1);
  commands = 0;
  assert.throws(() => collectAndroidEmulatorState("emulator-5554", app, () => ++commands === 1 ? "1" : "34"));
  assert.equal(commands, 2);
  const failure = new Error("adb failed with private-content");
  assert.throws(() => collectAndroidEmulatorState("emulator-5554", app, () => { throw failure; }), error => error === failure);
});

test("CLI returns JSON on success and keeps raw dumps and command stderr out of failures", t => {
  const directory = mkdtempSync(join(tmpdir(), "android-state-"));
  t.after(() => rmSync(directory, { recursive: true, force: true }));
  const bin = join(directory, "bin"); mkdirSync(bin);
  const adb = join(bin, "adb");
  writeFileSync(adb, `#!${process.execPath}\nif (process.env.STATE_QUERY_FAIL) { console.error('private-content'); process.exit(3); }\nconst arg = process.argv.at(-1);\nprocess.stdout.write(arg === 'ro.kernel.qemu' ? '1' : arg === 'ro.build.version.sdk' ? '35' : arg === 'telecom' ? ${JSON.stringify(telecom([call()]))} : ${JSON.stringify(notifications([notification(1)]))});\n`);
  chmodSync(adb, 0o755);
  const script = fileURLToPath(new URL("./check-android-journey-state.ts", import.meta.url));
  const run = (failure: string) => spawnSync(process.execPath, [script], {
    encoding: "utf8", timeout: 10_000,
    env: { ...process.env, PATH: `${bin}:${process.env.PATH}`, STATE_QUERY_FAIL: failure },
  });
  const success = run("");
  assert.ifError(success.error); assert.equal(success.status, 0, success.stderr);
  assert.equal(JSON.parse(success.stdout).calls[0].state, "RINGING");
  assert.equal(success.stderr, ""); assert.ok(!success.stdout.includes("private-content"));
  const failure = run("1");
  assert.ifError(failure.error); assert.equal(failure.status, 1);
  assert.equal(failure.stdout, ""); assert.equal(failure.stderr, "Android emulator state query failed.\n");
});
