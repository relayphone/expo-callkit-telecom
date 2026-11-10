import assert from "node:assert/strict";
import { execFileSync, spawn } from "node:child_process";
import { randomBytes } from "node:crypto";
import {
  chmodSync,
  closeSync,
  copyFileSync,
  mkdirSync,
  openSync,
  readdirSync,
  writeFileSync,
} from "node:fs";
import { join } from "node:path";
import { setTimeout as sleep } from "node:timers/promises";

type Program = "openssl" | "ip" | "docker" | "curl";
export type Execute = (program: Program, args: readonly string[]) => string;
export type Start = (directory: string) => number;

const execute: Execute = (program, args) =>
  execFileSync(program, [...args], {
    encoding: "utf8",
    stdio: [
      "ignore",
      program === "ip" ? "pipe" : program === "curl" ? "ignore" : "inherit",
      program === "openssl" ? "ignore" : "inherit",
    ],
  }) ?? "";

const startServer: Start = (directory) => {
  const log = openSync(join(directory, "server.log"), "w");
  const child = spawn(
    "timeout",
    ["1200s", "python3", "tools/native-media-fixture.py", directory],
    { stdio: ["ignore", log, log] }
  );
  closeSync(log);
  child.once("error", () => {});
  child.unref();
  assert.ok(child.pid, "Could not start native media fixture");
  return child.pid;
};

/** Prepares the local TLS/media fixture and waits for both existing health endpoints. */
export async function prepareNativeMediaFixture(
  runnerTemp: string,
  run: Execute = execute,
  start: Start = startServer,
  pause: (milliseconds: number) => Promise<void> = sleep
): Promise<void> {
  const directory = join(runnerTemp, "native-media-fixture");
  const raw = "android/instrumentation/src/main/res/raw";
  const assets = "android/instrumentation/src/androidTest/assets";
  for (const path of [directory, raw, assets])
    mkdirSync(path, { recursive: true });
  const file = (name: string) => join(directory, name);
  run("openssl", [
    "req",
    "-x509",
    "-newkey",
    "rsa:2048",
    "-nodes",
    "-days",
    "1",
    "-subj",
    "/CN=NativeMediaFixtureCA",
    "-keyout",
    file("ca.key"),
    "-out",
    file("ca.crt"),
  ]);
  run("openssl", [
    "req",
    "-newkey",
    "rsa:2048",
    "-nodes",
    "-subj",
    "/CN=localhost",
    "-keyout",
    file("server.key"),
    "-out",
    file("server.csr"),
  ]);
  writeFileSync(
    file("extensions"),
    "subjectAltName=DNS:localhost,IP:127.0.0.1,IP:10.0.2.2\n"
  );
  run("openssl", [
    "x509",
    "-req",
    "-in",
    file("server.csr"),
    "-CA",
    file("ca.crt"),
    "-CAkey",
    file("ca.key"),
    "-CAcreateserial",
    "-days",
    "1",
    "-extfile",
    file("extensions"),
    "-out",
    file("server.crt"),
  ]);
  copyFileSync(file("ca.crt"), join(raw, "media_ci_ca.pem"));
  const settings = {
    key: "ci-key",
    secret: randomBytes(32).toString("hex"),
    deviceAuth: randomBytes(24).toString("hex"),
  };
  writeFileSync(file("settings.json"), JSON.stringify(settings));
  let routes: unknown;
  try {
    routes = JSON.parse(run("ip", ["-j", "route", "get", "1.1.1.1"]));
  } catch {
    throw new Error("Could not read host route");
  }
  assert.ok(Array.isArray(routes), "Invalid host route");
  const route: unknown = routes[0];
  assert.ok(
    route !== null &&
      typeof route === "object" &&
      "prefsrc" in route &&
      typeof route.prefsrc === "string" &&
      "dev" in route &&
      typeof route.dev === "string",
    "Invalid host route"
  );
  // Advertise one host interface so the emulator's loopback mapping stays unambiguous.
  writeFileSync(
    file("livekit.yaml"),
    `port: 7880
rtc:
  node_ip: ${route.prefsrc}
  use_external_ip: false
  interfaces:
    includes: [${route.dev}]
  tcp_port: 7881
  udp_port: 7882
keys:
  ci-key: ${settings.secret}
logging:
  level: error
`
  );
  writeFileSync(
    join(assets, "media-ci.json"),
    JSON.stringify({
      baseUrl: "https://10.0.2.2:9443",
      authentication: settings.deviceAuth,
    })
  );
  for (const name of [
    ...readdirSync(directory).filter((name) => name.endsWith(".key")),
    "settings.json",
    "livekit.yaml",
  ])
    chmodSync(file(name), 0o600);
  run("docker", [
    "run",
    "--detach",
    "--rm",
    "--name",
    "native-media-fixture",
    "--network",
    "host",
    "--user",
    `${process.getuid!()}:${process.getgid!()}`,
    "-v",
    `${file("livekit.yaml")}:/etc/livekit.yaml:ro`,
    "livekit/livekit-server:v1.13.11",
    "--config",
    "/etc/livekit.yaml",
  ]);
  writeFileSync(file("pid"), `${start(directory)}\n`);
  for (let attempt = 0; attempt < 40; attempt++) {
    try {
      run("curl", ["--silent", "--fail", "http://127.0.0.1:7880"]);
      run("curl", [
        "--silent",
        "--fail",
        "--cacert",
        file("ca.crt"),
        "https://localhost:9443/health",
      ]);
      return;
    } catch {
      await pause(1000);
    }
  }
  throw new Error("Native media fixture did not become healthy");
}

if (import.meta.main) {
  try {
    assert.ok(process.env.RUNNER_TEMP, "RUNNER_TEMP is required");
    await prepareNativeMediaFixture(process.env.RUNNER_TEMP);
  } catch (error) {
    console.error(
      error instanceof Error
        ? error.message
        : "Native media fixture preparation failed"
    );
    process.exitCode =
      error !== null &&
      typeof error === "object" &&
      "status" in error &&
      typeof error.status === "number" &&
      error.status > 0
        ? error.status
        : 1;
  }
}
