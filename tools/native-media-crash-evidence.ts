import { spawnSync } from "node:child_process";
import { appendFileSync, mkdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { basename, join } from "node:path";
import { sanitizeFixtureLog } from "./native-media-fixture-log.ts";

export const crashLimits = { fileBytes: 256 * 1024, totalBytes: 2 * 1024 * 1024,
  summaryBytes: 2048, rawBytes: 1024 * 1024, lines: 2000, traceFiles: 2, commandMs: 2000, totalMs: 30_000 } as const;
const packages = { app: "expo.modules.callkittelecom.instrumentation",
  tests: "expo.modules.callkittelecom.instrumentation.test" } as const;
const reasons = ["unknown", "exit-self", "signaled", "low-memory", "java-crash", "native-crash", "anr",
  "initialization-failure", "permission-change", "excessive-resource-usage", "user-requested", "user-stopped",
  "dependency-died", "other", "freezer", "package-state-change", "package-updated"] as const;
interface Exit { timestamp: string; pid: number; reason: typeof reasons[number]; status: number }
export interface CrashRead { stdout: string; stderr: string; exitCode: number | null; limited?: boolean }
export type ReadCrashCommand = (args: readonly string[], timeout: number) => CrashRead;
type Source = "crash" | "main" | "app" | "tests" | "anr-list" | "tombstone-list" | `anr-${number}` | `tombstone-${number}`;
interface Attempt { source: Source; state: "read" | "partial" | "permission-denied" | "unavailable" | "budget";
  bytes: number; truncated: boolean }

const moduleProductionFiles = new Set(("NativeRoomConnection NativeTransportReadiness NativeDecodedAudioReadiness NativeAudioHooks " +
  "LiveKitNativeSession LiveKitNativeConfiguration NativeOutputMute NativeMicrophonePublication BuiltInMedia NativeWarmAudio " +
  "NativeTransportProbe NativePcmProbe ConversationMedia NativeActionReceipt ConversationActions NativeCaller ConversationPlatformCalls " +
  "NativeDtmf NativeCallEnded IncomingPolicy NativeCallPush NativeMediaSignals NativeConnectionStatus CallPushIntake NativeMediaOwner " +
  "NativeAudioAuthority EndedFailure CallerAddress NativeAudioLifecycle NativeMediaUrl ConversationEnds ConversationPresentation " +
  "ConversationService ConversationCapabilities IncomingRingDeadline ConversationProximity MediaPermissions ConversationMediaRouter " +
  "NativeIdentityReservations ConversationEngine ConversationCommandOutcome StartPlacementCoordinator CommandRejection " +
  "SystemCommandOutcome ConversationPlatform NativeMedia ConversationFailure CoreCallLifecycle NativeControlObservation NativeMuteTrace")
  .split(" ").map(name => `${name}.kt`));
const className = String.raw`(?:[A-Za-z_$][\w$]*\.)+[A-Za-z_$][\w$]*(?:\[\]){0,4}`;
const exceptionName = String.raw`(${className}(?:Exception|Error))`;
const abortPattern = new RegExp(`^Throwing new exception '(.*)' with unexpected pending exception: ${exceptionName}(?::|$)`);
const castPattern = new RegExp(`^(?:java\\.lang\\.ClassCastException: )?(${className}) cannot be cast to (${className})$`);
const exceptionPattern = new RegExp(`^${exceptionName}(?::|$)`);
function crashLine(line: string): { body: string; metadata: string } {
  const prefix = /^(\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3})\s+(\d+)\s+(\d+)\s+[VDIWEF]\s+[^:]*:\s?/.exec(line);
  return { body: (prefix ? line.slice(prefix[0].length) : line).trim()
    .replace(/^(?:runtime|check_jni|thread)\.cc:\d+\]\s*/, "").replace(/^Abort message: '/, ""),
    metadata: prefix ? `${prefix[1]} pid=${prefix[2]} tid=${prefix[3]} ` : "" };
}
function pendingFrame(body: string): { text: string; production: boolean } | undefined {
  const normalized = body.replace(/^at [A-Za-z0-9_.$\[\]]+ /, "at ").replace(/\([A-Za-z0-9_.$,\[\] ]*\) \(/, "(");
  const match = /^at ([A-Za-z0-9_.$<>]{1,240})\(([A-Za-z0-9_$]{1,100}\.(?:java|kt)):(\d{1,10})\)$/.exec(normalized);
  if (!match) return;
  const test = /(?:^|\.)(?:test|tests)(?:\.|$)|Tests?(?:\$|\.)/.test(match[1]!) || /Tests?\.(?:java|kt)$/.test(match[2]!);
  return { text: `${match[1]}(${match[2]}:${match[3]})`, production: !test && (
    /^(?:io\.livekit\.|(?:livekit\.)?org\.webrtc\.)/.test(match[1]!) ||
    match[1]!.startsWith("expo.modules.callkittelecom.conversations.") && moduleProductionFiles.has(match[2]!)) };
}

const read: ReadCrashCommand = (args, timeout) => {
  const result = spawnSync("adb", ["-s", "emulator-5554", ...args], { encoding: "utf8", timeout, killSignal: "SIGKILL",
    stdio: ["ignore", "pipe", "pipe"], maxBuffer: crashLimits.rawBytes });
  return { stdout: result.stdout ?? "", stderr: result.stderr ?? "", exitCode: result.status,
    limited: result.error !== undefined };
};

/** Keep only numeric exit fields; descriptions and process payloads never enter artifacts. */
export function parseMediaExits(text: string): Exit[] {
  return text.split(/ApplicationExitInfo[^\r\n]*:/).slice(1, 9).flatMap(block => {
    const stamp = /\btimestamp=(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}(?:\.\d{1,3})?)\b/.exec(block)?.[1];
    const pid = /\bpid=(\d{1,10})\b/.exec(block)?.[1];
    const reason = /\breason=(\d{1,2})\b/.exec(block)?.[1];
    const status = /\bstatus=(-?\d{1,10})\b/.exec(block)?.[1];
    if (!stamp || !pid || reason === undefined || status === undefined) return [];
    return [{ timestamp: stamp, pid: Number(pid), reason: reasons[Number(reason)] ?? "unknown", status: Number(status) }];
  }).sort((a, b) => b.timestamp.localeCompare(a.timestamp));
}

/** Arbitrary messages, exception details, thread names, notification text and memory dumps are removed. */
export function sanitizeMediaCrash(text: string, secrets: readonly string[] = [], edge: "head" | "tail" = "tail"): string {
  const lines = sanitizeFixtureLog(text, secrets).split(/\r?\n/);
  const selected = edge === "head" ? lines.slice(0, crashLimits.lines) : lines.slice(-crashLimits.lines);
  let pendingFrames: number | undefined;
  return selected.map((line, index) => {
    // logcat threadtime metadata is numeric; its free-form tag and message are not trusted.
    const { body, metadata } = crashLine(line);
    const abort = abortPattern.exec(body);
    if (abort) {
      pendingFrames = 0;
      const cast = castPattern.exec(abort[1]!);
      const next = cast ? "java.lang.ClassCastException" : exceptionPattern.exec(abort[1]!)?.[1];
      const frames: { text: string; production: boolean }[] = [];
      let observed = 0;
      for (const following of selected.slice(index + 1)) {
        const nextBody = crashLine(following).body;
        if (!nextBody) continue;
        if (!nextBody.startsWith("at ") || observed++ === 32) break;
        const frame = pendingFrame(nextBody);
        if (frame) frames.push(frame);
      }
      return metadata + `ART_ABORT new=${next?.slice(0, 240) ?? "unclassified"}` +
        (cast ? ` source=${cast[1]!.slice(0, 240)} target=${cast[2]!.slice(0, 240)}` : "") +
        ` pending=${abort[2]!.slice(0, 240)} production-crash-candidate=${frames.some(frame => frame.production)}`;
    }
    if (pendingFrames !== undefined) {
      const frame = pendingFrame(body);
      if (body.startsWith("at ")) return metadata + (pendingFrames++ < 32 && frame ? `at ${frame.text}` : "[REDACTED]");
      if (/^(?:#\d+\s+pc|(?:Fatal )?signal |pid: |-----|\*\*\*)/.test(body)) pendingFrames = undefined;
      else return metadata + "[REDACTED]";
    }
    let safe = "[REDACTED]";
    const signal = /^(?:Fatal )?signal (\d{1,3})\b.*?\bcode (-?\d{1,4})\b/i.exec(body);
    const process = /^pid: (\d+), tid: (\d+),/.exec(body);
    const java = /^(?:Caused by: )?((?:java|javax|android|androidx|kotlin|kotlinx|org\.webrtc|io\.livekit|expo\.modules\.callkittelecom)\.[A-Za-z0-9_.$]*(?:Exception|Error))\b/.exec(body);
    // ART adds a return type and argument types; retain only the method and source location.
    const frameBody = body.replace(/^at [A-Za-z0-9_.$\[\]]+ /, "at ")
      .replace(/\([A-Za-z0-9_.$,\[\] ]*\) \(/, "(");
    const frame = /^at ((?:java|javax|android|androidx|kotlin|kotlinx|(?:livekit\.)?org\.webrtc|io\.livekit|expo\.modules\.callkittelecom)\.[A-Za-z0-9_.$<>]+)\(([A-Za-z0-9_$]+\.(?:java|kt):\d+|Native Method|Unknown Source)\)$/.exec(frameBody);
    const native = /^#(\d+)\s+pc\s+([a-f0-9]+)\s+(\S+)/i.exec(body);
    const library = native && /^(lib[A-Za-z0-9_.+-]+\.so|[A-Za-z0-9_.+-]+\.(?:oat|odex))$/.exec(basename(native[3]!).split("!").at(-1)!);
    const symbol = native && /\s+\(((?:art|webrtc|rtc)::[A-Za-z0-9_:~<>]{1,160})(?=[(+])/.exec(body);
    const jni = body.startsWith("JNI DETECTED ERROR IN APPLICATION:");
    const call = jni && /\bJNI (Call(?:Static|Nonvirtual)?(?:Void|Object|Boolean|Byte|Char|Short|Int|Long|Float|Double)Method[AV]?)\b/.exec(body);
    const pending = jni && /\bpending exception (java\.lang\.[A-Za-z]{1,96}(?:Exception|Error))\b/.exec(body);
    const anr = /^----- pid (\d+) at (\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2})/.exec(body);
    if (jni) safe = "JNI DETECTED ERROR IN APPLICATION" + (call ? ` call=${call[1]}` : "") + (pending ? ` pending=${pending[1]}` : "");
    else if (signal) safe = `signal=${signal[1]} code=${signal[2]}`;
    else if (process) safe = `pid=${process[1]} tid=${process[2]}`;
    else if (java) safe = java[1]!;
    else if (frame) safe = `at ${frame[1]}(${frame[2]})`;
    else if (native && library) safe = `#${native[1]} pc ${native[2]} ${library[1]}` + (symbol ? ` ${symbol[1]}` : "");
    else if (anr) safe = `anr pid=${anr[1]} at ${anr[2]}`;
    else if (body.startsWith("FATAL EXCEPTION:")) safe = "FATAL EXCEPTION [thread redacted]";
    else if (body.startsWith("ANR in ")) safe = "ANR [process details redacted]";
    return metadata + safe;
  }).join("\n") + "\n";
}

function fixtureSecrets(): string[] {
  try {
    const settings: unknown = JSON.parse(readFileSync(join(process.env.RUNNER_TEMP ?? "/tmp", "native-media-fixture", "settings.json"), "utf8"));
    return settings && typeof settings === "object" ? Object.values(settings).filter((v): v is string => typeof v === "string") : [];
  } catch { return []; } // Structural allowlisting still removes all free-form content.
}

/** Failure diagnostics only: bounded commands and artifacts, with no privilege changes or raw files. */
export function collectMediaCrashEvidence(output: string, command: ReadCrashCommand = read,
  now: () => number = () => performance.now(), secrets: readonly string[] = fixtureSecrets()): string {
  rmSync(output, { recursive: true, force: true });
  mkdirSync(output, { recursive: true });
  const deadline = now() + crashLimits.totalMs;
  const metadataReserve = 16 * 1024;
  let remaining = crashLimits.totalBytes - metadataReserve;
  const attempts: Attempt[] = [];
  function capture(source: Source, args: readonly string[]): string {
    const timeout = Math.min(crashLimits.commandMs, Math.floor(deadline - now()));
    if (timeout <= 0) { attempts.push({ source, state: "budget", bytes: 0, truncated: false }); return ""; }
    let value: CrashRead;
    try { value = command(args, timeout); }
    catch { value = { stdout: "", stderr: "", exitCode: null }; }
    const bytes = Buffer.byteLength(value.stdout);
    const text = Buffer.from(value.stdout).subarray(0, crashLimits.rawBytes).toString("utf8");
    const denied = /permission denied|operation not permitted/i.test(value.stderr + text);
    attempts.push({ source, state: denied ? "permission-denied" : value.exitCode === 0 ? "read" : text ? "partial" : "unavailable",
      bytes, truncated: !!value.limited || bytes >= crashLimits.rawBytes });
    return denied ? "" : text;
  }
  function save(source: Source, contents: string): void {
    const max = Math.min(crashLimits.fileBytes, remaining);
    if (max === 0) return;
    const bytes = Buffer.from(contents);
    const truncated = bytes.length > max;
    const marker = "\n[TRUNCATED]\n";
    const saved = truncated ? (max < marker.length ? marker.slice(0, max)
      : bytes.subarray(0, max - marker.length).toString("utf8") + marker) : contents;
    writeFileSync(join(output, `${source}.txt`), saved);
    remaining -= Buffer.byteLength(saved);
    if (truncated) attempts.findLast(a => a.source === source)!.truncated = true;
  }
  const logSignals = new Set<string>();
  const logExceptions = new Set<string>();
  const artAborts = new Set<string>();
  const observeAborts = (value: string) => {
    for (const line of value.split("\n")) {
      const start = line.indexOf("ART_ABORT ");
      if (start >= 0) artAborts.add(line.slice(start));
    }
  };
  for (const buffer of ["crash", "main"] as const) {
    const sanitized = sanitizeMediaCrash(capture(buffer, ["logcat", "-d", "-b", buffer, "-v", "threadtime", "-t", String(crashLimits.lines)]), secrets);
    for (const signal of sanitized.matchAll(/\bsignal=(\d{1,3})\b/g)) logSignals.add(signal[1]!);
    for (const exception of sanitized.matchAll(/\b(java\.lang\.(?:OutOfMemoryError|[A-Za-z]{1,96}Exception))\b/g)) logExceptions.add(exception[1]!);
    observeAborts(sanitized);
    save(buffer, sanitized);
  }
  const exits = { app: [] as Exit[], tests: [] as Exit[] };
  for (const name of ["app", "tests"] as const) {
    exits[name] = parseMediaExits(sanitizeFixtureLog(capture(name, ["shell", "dumpsys", "activity", "exit-info", packages[name]]), secrets));
    save(name, JSON.stringify(exits[name], null, 2) + "\n");
  }
  for (const kind of ["anr", "tombstone"] as const) {
    const directory = kind === "anr" ? "/data/anr" : "/data/tombstones";
    const listing = capture(`${kind}-list`, ["shell", "ls", "-1t", directory]);
    const names = listing.split(/\r?\n/).filter(name => /^(?:anr_[A-Za-z0-9_.:-]+|traces\.txt|tombstone_\d+)$/.test(name))
      .slice(0, crashLimits.traceFiles);
    for (const [index, name] of names.entries()) {
      const source = `${kind}-${index + 1}` as const;
      const raw = capture(source, ["shell", "head", "-c", String(crashLimits.rawBytes), `${directory}/${name}`]);
      if (raw.split(/\r?\n/).length > crashLimits.lines) attempts.findLast(a => a.source === source)!.truncated = true;
      const sanitized = sanitizeMediaCrash(raw, secrets, "head");
      observeAborts(sanitized);
      save(source, sanitized);
    }
  }
  const exit = (name: "app" | "tests") => {
    const latest = exits[name][0];
    return latest ? `${name} latest=${latest.reason} status=${latest.status} pid=${latest.pid} at=${latest.timestamp}` : `${name} exit=unobserved`;
  };
  const missing = attempts.filter(a => a.state !== "read").map(a => `${a.source}:${a.state}`).join(",") || "none";
  // Every interpolated field is ASCII; reserve one byte for the artifact's final newline.
  const classification = [...artAborts].find(line => line.includes("production-crash-candidate=true")) ?? [...artAborts][0];
  const summary = `Android media crash evidence: ${classification ?? "ART_ABORT unobserved"}; ${exit("app")}; ${exit("tests")}; log signals(unscoped)=${[...logSignals].slice(-3).join(",") || "unobserved"}; log exceptions(unscoped)=${[...logExceptions].slice(-3).join(",") || "unobserved"}; unavailable=${missing}; truncated=${attempts.filter(a => a.truncated).length}. Latest exit history is not proof of this test's cause; signaled status=9 alone does not establish low memory.`.slice(0, crashLimits.summaryBytes - 1);
  remaining += metadataReserve;
  function metadata(name: "collection.json" | "summary.txt", contents: string): void {
    const bytes = Buffer.byteLength(contents);
    if (bytes > crashLimits.fileBytes || bytes > remaining) throw new Error("Crash metadata exceeds artifact budget");
    writeFileSync(join(output, name), contents);
    remaining -= bytes;
  }
  metadata("collection.json", JSON.stringify({ schemaVersion: 1, limits: crashLimits, attempts }, null, 2) + "\n");
  metadata("summary.txt", summary + "\n");
  const path = process.env.GITHUB_STEP_SUMMARY;
  if (path) appendFileSync(path, `\n${summary}\n`);
  return summary;
}

/** Always preserve the original failure, including when diagnostic collection fails. */
export function withMediaCrashEvidence(operation: () => void, output: string,
  collect: (output: string) => unknown = collectMediaCrashEvidence): void {
  try { operation(); }
  catch (error) {
    try { collect(output); }
    catch {
      const message = "Android media crash evidence: collection unavailable; original failure preserved.\n";
      process.stderr.write(message);
      try { if (process.env.GITHUB_STEP_SUMMARY) appendFileSync(process.env.GITHUB_STEP_SUMMARY, message); } catch { /* No diagnostic may replace the test failure. */ }
    }
    throw error;
  }
}
