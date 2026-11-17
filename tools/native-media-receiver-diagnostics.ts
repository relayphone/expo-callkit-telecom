import { spawn, type ChildProcess } from "node:child_process";
import { appendFileSync, mkdirSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { setTimeout as pause } from "node:timers/promises";

type Read = (path: string) => string;
interface Cpu { totalTicks: number; idleTicks: number; cores: number }
interface Peer { pid: number; cpuTicks: number; rssKiB: number; mainThreadRunNs: string; mainThreadWaitNs: string }
export interface HostResources {
  schemaVersion: 1;
  clock: "host.hrtime.bigint";
  atNs: string;
  unixMs: number;
  cpu: Cpu | null;
  memory: { totalKiB: number; availableKiB: number; swapTotalKiB: number; swapFreeKiB: number } | null;
  peer: Peer | null;
  hostBusyPercent: number | null;
  peerCpuPercentOneCore: number | null;
}

const integer = (value: string | undefined): number => {
  if (value === undefined || !/^\d+$/.test(value) || !Number.isSafeInteger(Number(value))) throw Error("Invalid counter");
  return Number(value);
};
function fields(text: string): Map<string, number> {
  return new Map([...text.matchAll(/^(\w+):\s+(\d+) kB$/gm)].map(([, key, value]) => [key!, integer(value)]));
}
const required = (values: Map<string, number>, key: string): number => {
  const value = values.get(key);
  if (value === undefined) throw Error("Missing counter");
  return value;
};

/** Numeric procfs observations only; command names, environment and exception text never leave the reader. */
export function sampleHostResources(peerPid: number | undefined, previous?: HostResources,
  read: Read = path => readFileSync(path, "utf8"), nowNs = process.hrtime.bigint(), unixMs = Date.now()): HostResources {
  const result: HostResources = { schemaVersion: 1, clock: "host.hrtime.bigint", atNs: nowNs.toString(), unixMs,
    cpu: null, memory: null, peer: null, hostBusyPercent: null, peerCpuPercentOneCore: null };
  try {
    const text = read("/proc/stat");
    const ticks = text.match(/^cpu\s+(.+)$/m)?.[1]?.trim().split(/\s+/).map(integer);
    // Guest ticks are already included in user/nice. I/O wait is idle, not CPU work.
    if (!ticks || ticks.length < 8) throw Error("Missing CPU counters");
    const cores = [...text.matchAll(/^cpu\d+\s/gm)].length;
    if (!cores) throw Error("Missing CPU count");
    result.cpu = { totalTicks: ticks.slice(0, 8).reduce((a, b) => a + b, 0), idleTicks: ticks[3]! + ticks[4]!, cores };
  } catch { /* Missing procfs is explicit, including on non-Linux hosts. */ }
  try {
    const values = fields(read("/proc/meminfo"));
    result.memory = { totalKiB: required(values, "MemTotal"), availableKiB: required(values, "MemAvailable"),
      swapTotalKiB: required(values, "SwapTotal"), swapFreeKiB: required(values, "SwapFree") };
  } catch { /* Preserve the independent CPU and peer observations. */ }
  if (peerPid !== undefined && Number.isSafeInteger(peerPid) && peerPid > 0) try {
    const stat = read(`/proc/${peerPid}/stat`);
    const counters = stat.slice(stat.lastIndexOf(")") + 2).trim().split(/\s+/);
    const scheduler = read(`/proc/${peerPid}/schedstat`).trim().split(/\s+/);
    for (const value of scheduler.slice(0, 2)) if (!/^\d+$/.test(value)) throw Error("Invalid scheduler counter");
    if (scheduler.length < 3) throw Error("Missing scheduler counters");
    result.peer = { pid: peerPid, cpuTicks: integer(counters[11]) + integer(counters[12]),
      rssKiB: required(fields(read(`/proc/${peerPid}/status`)), "VmRSS"),
      mainThreadRunNs: scheduler[0]!, mainThreadWaitNs: scheduler[1]! };
  } catch { /* A peer that has exited is recorded as absent. */ }
  if (result.cpu && previous?.cpu && result.cpu.cores === previous.cpu.cores) {
    const total = result.cpu.totalTicks - previous.cpu.totalTicks;
    const idle = result.cpu.idleTicks - previous.cpu.idleTicks;
    if (total > 0 && idle >= 0 && idle <= total) result.hostBusyPercent = 100 * (total - idle) / total;
    if (total > 0 && result.peer && previous.peer?.pid === result.peer.pid) {
      const used = result.peer.cpuTicks - previous.peer.cpuTicks;
      if (used >= 0) result.peerCpuPercentOneCore = 100 * used * result.cpu.cores / total;
    }
  }
  return result;
}

/** A separate process can sample the peer while its asyncio loop or the host Gradle command is blocked. */
export function startHostResourceMonitor(directory: string, fixturePid: number): ChildProcess {
  const monitor = spawn(process.execPath, [new URL(import.meta.url).pathname, directory, String(fixturePid)],
    { stdio: "ignore" });
  monitor.once("error", () => {});
  monitor.unref();
  return monitor;
}

if (import.meta.main) {
  const [directory, rawPid] = process.argv.slice(2);
  if (!directory || !rawPid || !/^\d+$/.test(rawPid)) throw Error("Missing monitor arguments");
  const fixturePid = integer(rawPid);
  const output = join(directory, "media-evidence");
  mkdirSync(output, { recursive: true });
  const deadline = performance.now() + 1_200_000;
  let previous: HostResources | undefined;
  while (performance.now() < deadline) {
    try { process.kill(fixturePid, 0); } catch { break; }
    let peerPid: number | undefined;
    try { peerPid = integer(readFileSync(join(directory, "receiver-peer-pid"), "utf8").trim()); } catch { /* Not started. */ }
    const sample = sampleHostResources(peerPid, previous);
    appendFileSync(join(output, "receiver-host.jsonl"), `${JSON.stringify(sample)}\n`);
    previous = sample;
    await pause(1_000);
  }
}
