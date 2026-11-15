import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { mkdtempSync, readFileSync, readdirSync, rmSync, statSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { delimiter, join } from "node:path";
import { test, type TestContext } from "node:test";
import { collectMediaCrashEvidence, crashLimits, parseMediaExits, sanitizeMediaCrash,
  withMediaCrashEvidence, type CrashRead, type ReadCrashCommand } from "./native-media-crash-evidence.ts";

function fixture(t: TestContext) {
  const directory = mkdtempSync(join(tmpdir(), "media-crash-"));
  t.after(() => rmSync(directory, { recursive: true, force: true }));
  return { directory, output: join(directory, "evidence") };
}
const success = (stdout: string): CrashRead => ({ stdout, stderr: "", exitCode: 0 });
// Android 15 ApplicationExitInfo.dump layout; free-form descriptions must never survive.
const exit = (reason = 5, status = 11, stamp = "2026-11-15 18:47:57.123") => `
ApplicationExitInfo #0:
 timestamp=${stamp} pid=123 realUid=10001 packageUid=10001 definingUid=10001 user=0
 process=expo.modules.callkittelecom.instrumentation reason=${reason} (TEXT) subreason=0 (UNKNOWN) status=${status}
 importance=100 pss=12MB rss=30MB description=notification-text Authorization: Bearer private-auth state=empty trace=/data/private
`;

test("parses Android exit records without descriptions and keeps signal kills distinct from low memory", () => {
  assert.deepEqual(parseMediaExits(exit()), [{ timestamp: "2026-11-15 18:47:57.123", pid: 123, reason: "native-crash", status: 11 }]);
  for (const [code, reason] of [[3, "low-memory"], [4, "java-crash"], [6, "anr"], [2, "signaled"]] as const)
    assert.equal(parseMediaExits(exit(code, 9))[0]?.reason, reason);
  assert.equal(parseMediaExits(exit(2, 9))[0]?.status, 9);
  assert.equal(parseMediaExits(exit(99))[0]?.reason, "unknown");
  assert.deepEqual(parseMediaExits("permission denied private-auth"), []);
  const records = parseMediaExits(exit(3, 0, "2026-11-14 18:47:57.123") + exit());
  assert.equal(records[0]?.reason, "native-crash");
  assert.equal(parseMediaExits(Array.from({ length: 20 }, () => exit()).join("")).length, 8);
});

test("sanitizes logcat, native and ANR stacks without credentials, notification text or abort messages", () => {
  const raw = `11-15 18:47:57.123 123 456 E AndroidRuntime: FATAL EXCEPTION: notification-text
11-15 18:47:57.123 123 456 E AndroidRuntime: java.lang.OutOfMemoryError: notification-text private-secret
11-15 18:47:57.123 123 456 E AndroidRuntime: at org.webrtc.AudioTrack.run(AudioTrack.java:55)
11-15 18:47:57.123 123 456 F DEBUG: signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x0
11-15 18:47:57.123 123 456 F DEBUG: #00 pc 000abc /apex/private/lib64/libc.so (abort+12)
pid: 123, tid: 456, name: notification-text >>> private-package <<<
----- pid 123 at 2026-11-15 18:47:57 -----
ANR in private-package
Abort message: 'private-secret'
Notification: title=notification-text password=unknown-password
Authorization: Bearer private-auth
eyJhbGciOiJIUzI1NiJ9.payload.signature
at java.lang.Private.run(notification-text)
at java.lang.private_secret.run(File.java:1)
`;
  const sanitized = sanitizeMediaCrash(raw, ["private-secret", "private_secret"]);
  assert.match(sanitized, /java.lang.OutOfMemoryError\n/);
  assert.match(sanitized, /at org.webrtc.AudioTrack.run\(AudioTrack.java:55\)/);
  assert.match(sanitized, /signal=11 code=1/);
  assert.match(sanitized, /#00 pc 000abc libc.so/);
  assert.match(sanitized, /anr pid=123 at 2026-11-15 18:47:57/);
  assert.doesNotMatch(sanitized, /notification-text|private|unknown-password|eyJ|Bearer|abort|apex/);
  assert.equal(sanitizeMediaCrash("line\n".repeat(3000)).trim().split("\n").length, crashLimits.lines);
});

test("collects both package exits and readable traces, rejects unsafe names and records permission failures", t => {
  const f = fixture(t);
  const observed: string[][] = [];
  const run: ReadCrashCommand = (args, timeout) => {
    assert(timeout > 0 && timeout <= crashLimits.commandMs);
    observed.push([...args]);
    if (args[0] === "logcat") return success("signal 11 (SIGSEGV), code 1 (SEGV_MAPERR)\nprivate-secret");
    if (args.includes("exit-info")) return success(exit(args.at(-1)!.endsWith(".test") ? 2 : 5, 9));
    if (args.includes("ls")) return args.at(-1) === "/data/anr"
      ? success("../../private\nanr_2026-11-15_18-47-57\ntraces.txt\ntombstone_00.pb\nanr_extra\n")
      : { stdout: "ls: permission denied private-secret", stderr: "", exitCode: 1 };
    return success("----- pid 123 at 2026-11-15 18:47:57 -----\nprivate-secret");
  };
  const summary = collectMediaCrashEvidence(f.output, run, () => 0, ["private-secret"]);
  assert.match(summary, /app latest=native-crash status=9 pid=123/);
  assert.match(summary, /tests latest=signaled status=9/);
  assert.match(summary, /log signals\(unscoped\)=11/);
  assert.match(summary, /tombstone-list:permission-denied/);
  assert.match(summary, /status=9 alone does not establish low memory/);
  assert.deepEqual(observed.filter(a => a.includes("exit-info")).map(a => a.at(-1)),
    ["expo.modules.callkittelecom.instrumentation", "expo.modules.callkittelecom.instrumentation.test"]);
  assert.deepEqual(observed.filter(a => a.includes("head")).map(a => a.at(-1)),
    ["/data/anr/anr_2026-11-15_18-47-57", "/data/anr/traces.txt"]);
  assert(observed.filter(a => a[0] === "logcat").every(a => a.includes("2000") && a.includes("-d")));
  const artifacts = readdirSync(f.output).map(name => readFileSync(join(f.output, name), "utf8")).join("");
  assert.doesNotMatch(artifacts, /private-secret|private-auth|notification-text|\.\.\//);
});

test("caps raw reads, every file and total artifacts while marking truncation", t => {
  const f = fixture(t);
  const huge = (`at java.${"A".repeat(1800)}.run(File.java:1)\n`).repeat(2000);
  collectMediaCrashEvidence(f.output, args => args.includes("exit-info") ? success(exit())
    : args.includes("ls") ? success("traces.txt\nanr_2\n") : { ...success(huge), limited: true }, () => 0, []);
  const files = readdirSync(f.output);
  assert(files.every(name => statSync(join(f.output, name)).size <= crashLimits.fileBytes));
  assert(files.reduce((n, name) => n + statSync(join(f.output, name)).size, 0) <= crashLimits.totalBytes);
  assert.match(readFileSync(join(f.output, "crash.txt"), "utf8"), /\[TRUNCATED\]/);
  const report = JSON.parse(readFileSync(join(f.output, "collection.json"), "utf8"));
  assert(report.attempts.some((a: { truncated: boolean }) => a.truncated));
});

test("oversized exception names cannot bypass summary, per-file or total artifact bounds", t => {
  const f = fixture(t);
  const huge = ["A", "B", "C"].map(letter => `java.lang.${letter.repeat(300_000)}Exception: private-message`).join("\n");
  const summary = collectMediaCrashEvidence(f.output, args => args[0] === "logcat" ? success(huge) : success(""), () => 0, []);
  assert(Buffer.byteLength(summary) <= 2048);
  const files = readdirSync(f.output);
  assert(files.every(name => statSync(join(f.output, name)).size <= crashLimits.fileBytes));
  assert(files.reduce((n, name) => n + statSync(join(f.output, name)).size, 0) <= crashLimits.totalBytes);
});

test("a shared collection deadline bounds offline adb and keeps availability explicit", t => {
  const f = fixture(t);
  let time = 0, calls = 0;
  const summary = collectMediaCrashEvidence(f.output, () => {
    calls++; time += crashLimits.totalMs;
    throw new Error("private transport credential");
  }, () => time, []);
  assert.equal(calls, 1);
  assert.match(summary, /app exit=unobserved/);
  assert.match(summary, /crash:unavailable/);
  assert.match(summary, /main:budget/);
  assert.doesNotMatch(summary, /private transport/);
});

test("partial output remains sanitized and stdout permission errors are recognized even with exit zero", t => {
  const f = fixture(t);
  collectMediaCrashEvidence(f.output, args => args.includes("ls") ? success("Permission denied: private-payload")
    : { stdout: "signal 6 (SIGABRT), code -1 (SI_QUEUE)\nprivate-payload", stderr: "private-auth", exitCode: null, limited: true }, () => 0, []);
  const artifacts = readdirSync(f.output).map(name => readFileSync(join(f.output, name), "utf8")).join("");
  assert.match(artifacts, /signal=6 code=-1/);
  assert.match(artifacts, /permission-denied/);
  assert.match(artifacts, /partial/);
  assert.doesNotMatch(artifacts, /private/);
});

test("runs no diagnostics on success and preserves the original failure even if collection fails", t => {
  const f = fixture(t);
  let calls = 0;
  withMediaCrashEvidence(() => {}, f.output, () => { calls++; });
  assert.equal(calls, 0);
  const failure = new Error("original qualification failure");
  assert.throws(() => withMediaCrashEvidence(() => { throw failure; }, f.output, () => { calls++; }), error => error === failure);
  assert.equal(calls, 1);
  const summary = join(f.directory, "summary.md");
  const previous = process.env.GITHUB_STEP_SUMMARY;
  process.env.GITHUB_STEP_SUMMARY = summary;
  try {
    assert.throws(() => withMediaCrashEvidence(() => { throw failure; }, f.output,
      () => { throw new Error("private collection error"); }), error => error === failure);
    assert.match(readFileSync(summary, "utf8"), /collection unavailable; original failure preserved/);
    collectMediaCrashEvidence(f.output, () => success(exit(3, 0)), () => 0, []);
    assert.match(readFileSync(summary, "utf8"), /app latest=low-memory status=0/);
    assert.doesNotMatch(readFileSync(summary, "utf8"), /private|notification-text/);
  } finally {
    if (previous === undefined) delete process.env.GITHUB_STEP_SUMMARY;
    else process.env.GITHUB_STEP_SUMMARY = previous;
  }
});

test("the actual feasibility CLI captures a fatal resource guard before exit using only its workflow emulator", t => {
  const f = fixture(t);
  writeFileSync(join(f.directory, "adb"), `#!${process.execPath}
const args = process.argv.slice(2);
if (args[0] !== '-s' || args[1] !== 'emulator-5554') process.exit(19);
if (args.includes('/proc/meminfo')) console.log('MemTotal: 2097153 kB');
else if (args.includes('/sys/devices/system/cpu/present')) console.log('0-1');
else if (args.includes('exit-info')) console.log(${JSON.stringify(exit(3, 0))});
else if (args.includes('logcat')) console.log('java.lang.OutOfMemoryError: notification-text private-auth');
else { console.error('Permission denied private-auth'); process.exit(1); }
`, { mode: 0o755 });
  const summary = join(f.directory, "summary.md");
  const child = spawnSync(process.execPath, [new URL("./run-native-media-journey.ts", import.meta.url).pathname,
    "--feasibility-only", "--controls-prechecked", "--crash-evidence"], {
    cwd: f.directory, env: { ...process.env, PATH: f.directory + delimiter + process.env.PATH,
      GITHUB_STEP_SUMMARY: summary }, encoding: "utf8", timeout: 15_000, killSignal: "SIGKILL",
  });
  assert.equal(child.status, 1);
  assert.match(child.stderr, /Guest memory exceeds 2 GiB/);
  const evidence = join(f.directory, "android/instrumentation/build/media-crash-evidence");
  assert.match(readFileSync(join(evidence, "summary.txt"), "utf8"), /app latest=low-memory status=0/);
  assert.match(readFileSync(summary, "utf8"), /java.lang.OutOfMemoryError/);
  const artifacts = readdirSync(evidence).map(name => readFileSync(join(evidence, name), "utf8")).join("");
  assert.doesNotMatch(artifacts, /private-auth|notification-text/);
});

test("the actual failing instrumentation command stays fatal and retains crash evidence before any Release install", t => {
  const f = fixture(t);
  const result = { passed: false, step: "synthetic crash", captureRate: 48000, profile: "tones-v2", pipeline: "",
    failure: [], gateTrace: {} };
  const trace = { clock: "android.elapsedRealtimeNanos", overflow: false, columns: [], callbacks: [], apmStates: [], rtc: [], postApm: {} };
  writeFileSync(join(f.directory, "adb"), `#!${process.execPath}
const args = process.argv.slice(2);
if (args.includes('/proc/meminfo')) console.log('MemTotal: 2097152 kB');
else if (args.includes('/sys/devices/system/cpu/present')) console.log('0-1');
else if (args.includes('files/media-hook-results.json')) console.log(${JSON.stringify(JSON.stringify(result))});
else if (args.includes('files/capture-trace.json')) console.log(${JSON.stringify(JSON.stringify(trace))});
else if (args.includes('exit-info')) console.log(${JSON.stringify(exit(5, 11))});
else if (args.includes('logcat')) console.log('signal 11 (SIGSEGV), code 1 (SEGV_MAPERR)');
else { console.error('Permission denied private-auth'); process.exit(1); }
`, { mode: 0o755 });
  writeFileSync(join(f.directory, "gradle"), `#!${process.execPath}
import { appendFileSync } from 'node:fs';
appendFileSync('gradle-calls.txt', process.argv.slice(2).join(' ') + '\\n');
console.error('synthetic instrumentation process crashed');
process.exit(23);
`, { mode: 0o755 });
  const child = spawnSync(process.execPath, [new URL("./run-native-media-journey.ts", import.meta.url).pathname,
    "--feasibility-only", "--controls-prechecked", "--crash-evidence"], {
    cwd: f.directory, env: { ...process.env, PATH: f.directory + delimiter + process.env.PATH },
    encoding: "utf8", timeout: 15_000, killSignal: "SIGKILL",
  });
  assert.equal(child.status, 1);
  assert.match(child.stderr, /synthetic instrumentation process crashed/);
  assert.match(child.stderr, /status: 23/);
  const calls = readFileSync(join(f.directory, "gradle-calls.txt"), "utf8");
  assert.match(calls, /:connectedEmulatorTestAndroidTest/);
  assert.doesNotMatch(calls, /Release/);
  const evidence = join(f.directory, "android/instrumentation/build/media-crash-evidence");
  assert.match(readFileSync(join(evidence, "summary.txt"), "utf8"), /app latest=native-crash status=11/);
});
