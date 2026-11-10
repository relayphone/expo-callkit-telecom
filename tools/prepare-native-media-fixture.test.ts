import assert from "node:assert/strict";
import { execFileSync, spawnSync } from "node:child_process";
import { X509Certificate } from "node:crypto";
import {
  existsSync,
  mkdirSync,
  mkdtempSync,
  readFileSync,
  rmSync,
  statSync,
  symlinkSync,
  writeFileSync,
} from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { test, type TestContext } from "node:test";
import { fileURLToPath } from "node:url";

import { retainFixtureLog } from "./native-media-fixture-log.ts";
import {
  prepareNativeMediaFixture,
  type Execute,
  type Start,
} from "./prepare-native-media-fixture.ts";

const project = process.cwd();

function fixture(t: TestContext) {
  const root = mkdtempSync(join(tmpdir(), "media-fixture-prepare-"));
  process.chdir(root);
  t.after(() => {
    process.chdir(project);
    rmSync(root, { recursive: true, force: true });
  });
  const runnerTemp = join(root, "temp");
  const directory = join(runnerTemp, "native-media-fixture");
  const commands: { program: string; args: readonly string[] }[] = [];
  const run: Execute = (program, args) => {
    commands.push({ program, args });
    if (program === "openssl") {
      for (const option of ["-keyout", "-out"]) {
        const index = args.indexOf(option);
        if (index >= 0)
          writeFileSync(args[index + 1]!, "certificate fixture\n");
      }
    }
    return program === "ip" ? '[{"prefsrc":"192.0.2.42","dev":"eth0"}]' : "";
  };
  const start: Start = (directory) => {
    commands.push({
      program: "timeout",
      args: ["1200s", "python3", "tools/native-media-fixture.py", directory],
    });
    writeFileSync(join(directory, "server.log"), "startup diagnostic\n");
    return 1234;
  };
  const delays: number[] = [];
  const pause = async (ms: number) => {
    delays.push(ms);
  };
  return { root, runnerTemp, directory, commands, run, start, delays, pause };
}

test("preserves TLS commands, credentials, single-interface config, server startup and health order", async (t) => {
  const f = fixture(t);
  await prepareNativeMediaFixture(f.runnerTemp, f.run, f.start, f.pause);
  assert.deepEqual(
    f.commands.map((command) => command.program),
    ["openssl", "openssl", "openssl", "ip", "docker", "timeout", "curl", "curl"]
  );
  assert.deepEqual(f.commands[0]?.args, [
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
    join(f.directory, "ca.key"),
    "-out",
    join(f.directory, "ca.crt"),
  ]);
  assert.deepEqual(f.commands[1]?.args, [
    "req",
    "-newkey",
    "rsa:2048",
    "-nodes",
    "-subj",
    "/CN=localhost",
    "-keyout",
    join(f.directory, "server.key"),
    "-out",
    join(f.directory, "server.csr"),
  ]);
  assert.deepEqual(f.commands[2]?.args, [
    "x509",
    "-req",
    "-in",
    join(f.directory, "server.csr"),
    "-CA",
    join(f.directory, "ca.crt"),
    "-CAkey",
    join(f.directory, "ca.key"),
    "-CAcreateserial",
    "-days",
    "1",
    "-extfile",
    join(f.directory, "extensions"),
    "-out",
    join(f.directory, "server.crt"),
  ]);
  assert.deepEqual(f.commands[3]?.args, ["-j", "route", "get", "1.1.1.1"]);
  assert.deepEqual(f.commands[4]?.args, [
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
    `${f.directory}/livekit.yaml:/etc/livekit.yaml:ro`,
    "livekit/livekit-server:v1.13.11",
    "--config",
    "/etc/livekit.yaml",
  ]);
  assert.deepEqual(f.commands[5]?.args, [
    "1200s",
    "python3",
    "tools/native-media-fixture.py",
    f.directory,
  ]);
  assert.deepEqual(f.commands[6]?.args, [
    "--silent",
    "--fail",
    "http://127.0.0.1:7880",
  ]);
  assert.deepEqual(f.commands[7]?.args, [
    "--silent",
    "--fail",
    "--cacert",
    join(f.directory, "ca.crt"),
    "https://localhost:9443/health",
  ]);
  const settings = JSON.parse(
    readFileSync(join(f.directory, "settings.json"), "utf8")
  );
  assert.deepEqual(Object.keys(settings), ["key", "secret", "deviceAuth"]);
  assert.equal(settings.key, "ci-key");
  assert.ok(/^[0-9a-f]{64}$/.test(settings.secret));
  assert.ok(/^[0-9a-f]{48}$/.test(settings.deviceAuth));
  const config = readFileSync(join(f.directory, "livekit.yaml"), "utf8");
  assert.equal(
    config.replace(settings.secret, "<secret>"),
    "port: 7880\nrtc:\n  node_ip: 192.0.2.42\n  use_external_ip: false\n  interfaces:\n    includes: [eth0]\n  tcp_port: 7881\n  udp_port: 7882\nkeys:\n  ci-key: <secret>\nlogging:\n  level: error\n"
  );
  const assets = JSON.parse(
    readFileSync(
      "android/instrumentation/src/androidTest/assets/media-ci.json",
      "utf8"
    )
  );
  assert.equal(assets.baseUrl, "https://10.0.2.2:9443");
  assert.ok(assets.authentication === settings.deviceAuth);
  for (const name of ["ca.key", "server.key", "settings.json", "livekit.yaml"])
    assert.equal(statSync(join(f.directory, name)).mode & 0o777, 0o600);
  assert.equal(
    readFileSync(
      "android/instrumentation/src/main/res/raw/media_ci_ca.pem",
      "utf8"
    ),
    readFileSync(join(f.directory, "ca.crt"), "utf8")
  );
  assert.equal(readFileSync(join(f.directory, "pid"), "utf8"), "1234\n");
  assert.deepEqual(f.delays, []);
  writeFileSync(
    join(f.directory, "server.log"),
    `startup diagnostic\n${settings.secret}\n${settings.deviceAuth}\n`
  );
  const output = join(f.root, "retained.log");
  assert.equal(retainFixtureLog(f.directory, output), true);
  assert.equal(
    readFileSync(output, "utf8"),
    "startup diagnostic\n<REDACTED>\n<REDACTED>\n"
  );
});

test("real OpenSSL certificates retain the test CA and all three server identities", async (t) => {
  const f = fixture(t);
  await prepareNativeMediaFixture(
    f.runnerTemp,
    (program, args) => {
      if (program === "openssl") {
        execFileSync(program, [...args], { stdio: "ignore" });
        return "";
      }
      return f.run(program, args);
    },
    f.start,
    f.pause
  );
  const ca = new X509Certificate(readFileSync(join(f.directory, "ca.crt")));
  const server = new X509Certificate(
    readFileSync(join(f.directory, "server.crt"))
  );
  assert.ok(ca.ca);
  assert.ok(server.verify(ca.publicKey));
  assert.equal(server.checkHost("localhost"), "localhost");
  assert.equal(server.checkIP("127.0.0.1"), "127.0.0.1");
  assert.equal(server.checkIP("10.0.2.2"), "10.0.2.2");
  assert.equal(
    Date.parse(server.validTo) - Date.parse(server.validFrom),
    86_400_000
  );
});

for (const endpoint of ["livekit", "tls"])
  test(`${endpoint} health failure keeps forty one-second waits and leaves cleanup evidence`, async (t) => {
    const f = fixture(t);
    await assert.rejects(
      prepareNativeMediaFixture(
        f.runnerTemp,
        (program, args) => {
          const output = f.run(program, args);
          if (
            program === "curl" &&
            (endpoint === "livekit" || args.at(-1)?.includes("/health"))
          )
            throw new Error("not healthy");
          return output;
        },
        f.start,
        f.pause
      ),
      /Native media fixture did not become healthy/
    );
    assert.equal(
      f.commands.filter((command) => command.program === "curl").length,
      endpoint === "livekit" ? 40 : 80
    );
    assert.deepEqual(
      f.delays,
      Array.from({ length: 40 }, () => 1000)
    );
    assert.equal(readFileSync(join(f.directory, "pid"), "utf8"), "1234\n");
    assert.equal(
      readFileSync(join(f.directory, "server.log"), "utf8"),
      "startup diagnostic\n"
    );
  });

test("accepts health on the last attempt after exactly thirty-nine waits", async (t) => {
  const f = fixture(t);
  let attempts = 0;
  await prepareNativeMediaFixture(
    f.runnerTemp,
    (program, args) => {
      const output = f.run(program, args);
      if (
        program === "curl" &&
        args.at(-1) === "http://127.0.0.1:7880" &&
        ++attempts < 40
      )
        throw new Error("not yet");
      return output;
    },
    f.start,
    f.pause
  );
  assert.equal(attempts, 40);
  assert.deepEqual(
    f.delays,
    Array.from({ length: 39 }, () => 1000)
  );
});

test("setup failures stop before server launch and malformed routes cannot start Docker", async (t) => {
  for (const failed of ["openssl", "docker"] as const) {
    const f = fixture(t);
    const failure = new Error("setup failed");
    await assert.rejects(
      prepareNativeMediaFixture(
        f.runnerTemp,
        (program, args) => {
          if (program === failed) throw failure;
          return f.run(program, args);
        },
        f.start,
        f.pause
      ),
      (error) => error === failure
    );
    assert.equal(existsSync(join(f.directory, "pid")), false);
  }
  for (const route of [
    "invalid JSON",
    "null",
    "[]",
    '[{"prefsrc":1,"dev":"eth0"}]',
    '[{"prefsrc":"192.0.2.42"}]',
  ]) {
    const f = fixture(t);
    await assert.rejects(
      prepareNativeMediaFixture(
        f.runnerTemp,
        (program, args) => (program === "ip" ? route : f.run(program, args)),
        f.start,
        f.pause
      )
    );
    assert.equal(
      f.commands.some((command) => command.program === "docker"),
      false
    );
  }
});

test("CLI retains external failure status, background output, PID and private-file modes", (t) => {
  const f = fixture(t);
  const binary = join(f.root, "bin");
  mkdirSync(binary);
  const fake = join(binary, "fake.ts");
  writeFileSync(
    fake,
    `#!${process.execPath}
import {existsSync,readFileSync,writeFileSync} from "node:fs";
import {basename,join} from "node:path";
const program=basename(process.argv[1]!);const args=process.argv.slice(2);
if(program===process.env.PREPARE_FAIL) {console.error("fixture setup command failed");process.exit(17);}
if(program==="openssl") for(const flag of ["-keyout","-out"]) {const index=args.indexOf(flag);if(index>=0) writeFileSync(args[index+1]!,"certificate fixture\\n");}
if(program==="ip") console.log('[{"prefsrc":"192.0.2.42","dev":"eth0"}]');
if(program==="docker") console.log("fixture-container");
if(program==="timeout") {console.log("server stdout diagnostic");console.error("server stderr diagnostic");writeFileSync(join(args.at(-1)!,"observed-pid"),String(process.pid));}
if(program==="curl") {const file=join(process.env.RUNNER_TEMP!,"native-media-fixture/server.log");if(!existsSync(file)||!readFileSync(file,"utf8").includes("server stderr diagnostic"))process.exit(22);}
`,
    { mode: 0o755 }
  );
  for (const program of ["openssl", "ip", "docker", "timeout", "curl"])
    symlinkSync(fake, join(binary, program));
  for (const [failed, status] of [
    ["", 0],
    ["openssl", 17],
    ["ip", 1],
    ["docker", 17],
  ] as const) {
    const cwd = join(f.root, failed || "success");
    mkdirSync(cwd);
    const runnerTemp = join(cwd, "temp");
    const result = spawnSync(
      process.execPath,
      [
        fileURLToPath(
          new URL("./prepare-native-media-fixture.ts", import.meta.url)
        ),
      ],
      {
        cwd,
        encoding: "utf8",
        timeout: 15_000,
        env: {
          ...process.env,
          PATH: `${binary}:${process.env.PATH ?? ""}`,
          RUNNER_TEMP: runnerTemp,
          PREPARE_FAIL: failed,
        },
      }
    );
    assert.ifError(result.error);
    assert.equal(result.status, status, result.stderr);
    const directory = join(runnerTemp, "native-media-fixture");
    if (failed) {
      assert.equal(existsSync(join(directory, "pid")), false);
      continue;
    }
    assert.equal(result.stdout, "fixture-container\n");
    assert.equal(result.stderr, "");
    assert.equal(
      readFileSync(join(directory, "server.log"), "utf8"),
      "server stdout diagnostic\nserver stderr diagnostic\n"
    );
    assert.equal(
      readFileSync(join(directory, "pid"), "utf8").trim(),
      readFileSync(join(directory, "observed-pid"), "utf8")
    );
    for (const name of [
      "ca.key",
      "server.key",
      "settings.json",
      "livekit.yaml",
    ])
      assert.equal(statSync(join(directory, name)).mode & 0o777, 0o600);
  }
});
