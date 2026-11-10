import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import {
  mkdtempSync,
  readFileSync,
  rmSync,
  symlinkSync,
  writeFileSync,
} from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { test, type TestContext } from "node:test";
import { fileURLToPath } from "node:url";

import {
  runExampleJourney,
  type Command,
  type Execute,
} from "./run-example-journey.ts";

function fixture(t: TestContext, readyAt = 1) {
  const directory = mkdtempSync(join(tmpdir(), "example-runner-"));
  t.after(() => rmSync(directory, { recursive: true, force: true }));
  const output = join(directory, "example-journey.json");
  const calls: Command[] = [];
  const delays: number[] = [];
  let polls = 0;
  const run: Execute = async (command) => {
    calls.push(command);
    if (command.args.join(" ") === "shell id -u") return Buffer.from("0\r\n");
    if (command.args[1] === "test" && ++polls < readyAt)
      throw new Error("no result yet");
    if (command.args[0] === "exec-out") return Buffer.from('{"passed":true}');
    return Buffer.alloc(0);
  };
  return {
    directory,
    output,
    calls,
    run,
    delays,
    delay: async (ms: number) => {
      delays.push(ms);
    },
  };
}

test("recovers from adbd restarting and checks evidence before stopping the app", async (t) => {
  const directory = mkdtempSync(join(tmpdir(), "example-runner-"));
  t.after(() => rmSync(directory, { recursive: true, force: true }));
  const output = join(directory, "example-journey.json");
  const calls: Command[] = [];
  const run: Execute = async (command) => {
    calls.push(command);
    if (command.args[0] === "root") throw new Error("adbd restarting");
    if (command.args.join(" ") === "shell id -u") return Buffer.from("0\r\n");
    if (command.args[0] === "exec-out") return Buffer.from('{"passed":true}');
    return Buffer.alloc(0);
  };
  await runExampleJourney(output, run, async () =>
    assert.fail("unexpected poll delay")
  );
  assert.deepEqual(calls.slice(0, 3), [
    { program: "adb", args: ["root"], timeout: 15_000, output: "ignore" },
    { program: "adb", args: ["wait-for-device"], timeout: 30_000 },
    {
      program: "adb",
      args: ["shell", "id", "-u"],
      timeout: 10_000,
      output: "capture",
    },
  ]);
  assert.deepEqual(calls.at(-2), {
    program: "node",
    args: ["tools/check-example-journey.ts", output],
  });
  assert.deepEqual(calls.at(-1), {
    program: "adb",
    args: ["shell", "am", "force-stop", "com.example.expocallkittelecom"],
  });
  assert.equal(readFileSync(output, "utf8"), '{"passed":true}');
  assert.deepEqual(
    calls.slice(3, 8).map((command) => command.args),
    [
      ["install", "-r", "example-apk/app-release.apk"],
      [
        "shell",
        "pm",
        "grant",
        "com.example.expocallkittelecom",
        "android.permission.RECORD_AUDIO",
      ],
      [
        "shell",
        "pm",
        "grant",
        "com.example.expocallkittelecom",
        "android.permission.POST_NOTIFICATIONS",
      ],
      [
        "shell",
        "am",
        "start",
        "-W",
        "-a",
        "android.intent.action.VIEW",
        "-d",
        "conversations-example://journey",
        "com.example.expocallkittelecom",
      ],
      [
        "shell",
        "test",
        "-s",
        "/data/user/0/com.example.expocallkittelecom/files/conversation-journey.json",
      ],
    ]
  );
  assert.deepEqual(calls[8], {
    program: "adb",
    args: [
      "exec-out",
      "cat",
      "/data/user/0/com.example.expocallkittelecom/files/conversation-journey.json",
    ],
    output: "capture",
  });
  assert.ok(calls.slice(3).every((command) => command.timeout === undefined));
});

test("uses captured root identity even if its command exits unsuccessfully", async (t) => {
  const f = fixture(t);
  await runExampleJourney(
    f.output,
    async (command) => {
      if (command.args.join(" ") === "shell id -u")
        throw Object.assign(new Error("transport closed"), {
          stdout: Buffer.from("0\r\n"),
        });
      return f.run(command);
    },
    f.delay
  );
  assert.equal(readFileSync(f.output, "utf8"), '{"passed":true}');
});

test("rejects nonroot, blank and malformed identities before installing the app", async (t) => {
  for (const uid of ["2000\n", "", " 0\n", "0 \n", "0\nextra"]) {
    const f = fixture(t);
    await assert.rejects(
      runExampleJourney(
        f.output,
        async (command) => {
          const result = await f.run(command);
          return command.args.join(" ") === "shell id -u"
            ? Buffer.from(uid)
            : result;
        },
        f.delay
      ),
      /requires the test image to provide root ADB/
    );
    assert.equal(f.calls.length, 3);
  }
  const f = fixture(t);
  await assert.rejects(
    runExampleJourney(
      f.output,
      async (command) => {
        const result = await f.run(command);
        if (command.args.join(" ") === "shell id -u")
          throw new Error("identity timed out");
        return result;
      },
      f.delay
    ),
    /requires the test image to provide root ADB/
  );
  assert.equal(f.calls.length, 3);
});

test("allows the last poll after exactly 59 two-second delays", async (t) => {
  const f = fixture(t, 60);
  await runExampleJourney(f.output, f.run, f.delay);
  assert.equal(
    f.calls.filter((command) => command.args[1] === "test").length,
    60
  );
  assert.deepEqual(
    f.delays,
    Array.from({ length: 59 }, () => 2_000)
  );
  assert.equal(
    f.calls.filter((command) => command.args[0] === "logcat").length,
    0
  );
});

test("exhausts sixty polls and delays, collects logcat and fails", async (t) => {
  const f = fixture(t, 61);
  await assert.rejects(
    runExampleJourney(f.output, f.run, f.delay),
    /did not produce its journey result within 120 seconds/
  );
  assert.equal(
    f.calls.filter((command) => command.args[1] === "test").length,
    60
  );
  assert.deepEqual(
    f.delays,
    Array.from({ length: 60 }, () => 2_000)
  );
  assert.deepEqual(f.calls.at(-1), {
    program: "adb",
    args: ["logcat", "-d", "-s", "AndroidRuntime"],
  });
  assert.equal(
    f.calls.filter((command) => command.program === "node").length,
    0
  );
});

for (const target of [
  "wait-for-device",
  "install",
  "android.permission.RECORD_AUDIO",
  "android.permission.POST_NOTIFICATIONS",
  "start",
  "exec-out",
  "tools/check-example-journey.ts",
  "force-stop",
])
  test(`${target} failure remains fatal and stops subsequent commands`, async (t) => {
    const f = fixture(t);
    const failure = new Error(`${target} failed`);
    await assert.rejects(
      runExampleJourney(
        f.output,
        async (command) => {
          const result = await f.run(command);
          if (command.args.includes(target)) throw failure;
          return result;
        },
        f.delay
      ),
      (error) => error === failure
    );
    assert.ok(f.calls.at(-1)?.args.includes(target));
  });

test("preserves a logcat failure when the result never appears", async (t) => {
  const f = fixture(t, 61);
  const failure = new Error("logcat failed");
  await assert.rejects(
    runExampleJourney(
      f.output,
      async (command) => {
        if (command.args[0] === "logcat") throw failure;
        return f.run(command);
      },
      f.delay
    ),
    (error) => error === failure
  );
  assert.equal(f.delays.length, 60);
});

test("truncates stale evidence and retains partial stdout when collection fails", async (t) => {
  for (const partial of [undefined, '{"passed":']) {
    const f = fixture(t);
    writeFileSync(f.output, "stale result");
    const failure = Object.assign(
      new Error("cat failed"),
      partial === undefined ? {} : { stdout: Buffer.from(partial) }
    );
    await assert.rejects(
      runExampleJourney(
        f.output,
        async (command) => {
          const result = await f.run(command);
          if (command.args[0] === "exec-out") throw failure;
          return result;
        },
        f.delay
      ),
      (error) => error === failure
    );
    assert.equal(readFileSync(f.output, "utf8"), partial ?? "");
    assert.equal(
      f.calls.filter((command) => command.program === "node").length,
      0
    );
  }
});

test("real CLI checks valid evidence and preserves root, command and checker failures", (t) => {
  const f = fixture(t);
  symlinkSync(
    fileURLToPath(new URL(".", import.meta.url)),
    join(f.directory, "tools"),
    "dir"
  );
  writeFileSync(join(f.directory, "package.json"), '{"type":"module"}');
  const adb = join(f.directory, "fake-adb.ts");
  writeFileSync(
    adb,
    `#!${process.execPath}
import {appendFileSync} from "node:fs";
const args = process.argv.slice(2);
const scenario = process.env.EXAMPLE_RUNNER_SCENARIO;
appendFileSync(process.env.EXAMPLE_RUNNER_TRACE!, JSON.stringify(args)+"\\n");
if (args[0] === "root") {console.error("ignored root restart"); process.exit(1);}
if (args[0] === "wait-for-device" && scenario === "wait") {console.error("wait failed"); process.exit(17);}
if (args[0] === "wait-for-device" && scenario === "killed") process.kill(process.pid,"SIGKILL");
if (args.join(" ") === "shell id -u") {process.stdout.write(scenario === "nonroot" ? "2000\\n" : "0\\r\\n"); process.exit(scenario === "identity-partial" ? 1 : 0);}
if (args[0] === "install" && scenario === "install") {console.error("install failed"); process.exit(19);}
if (args[0] === "exec-out") {
  if (scenario === "partial-bytes") {process.stdout.write(Buffer.from([0x7b,0x22,0xc3])); process.exit(23);}
  if (scenario === "partial") {process.stdout.write('{"passed":'); process.exit(23);}
  if (scenario === "json") {process.stdout.write("invalid JSON"); process.exit(0);}
  const value = {passed:true,steps:["ringing","connecting","connected","held","connected","incoming-ended","dialing","connecting","connected","outgoing-ended"],transport:"expo-native-bridge"};
  if (scenario === "passed") value.passed = false;
  if (scenario === "steps") value.steps.reverse();
  if (scenario === "transport") value.transport = "native";
  process.stdout.write(JSON.stringify(value));
}
`,
    { mode: 0o755 }
  );
  symlinkSync(adb, join(f.directory, "adb"));
  const trace = join(f.directory, "trace.jsonl");
  const runner = fileURLToPath(
    new URL("./run-example-journey.ts", import.meta.url)
  );
  const cases: readonly [string, number, RegExp | undefined][] = [
    ["success", 0, undefined],
    ["identity-partial", 0, undefined],
    ["nonroot", 1, /requires the test image to provide root ADB/],
    ["wait", 17, /wait failed/],
    ["killed", 137, undefined],
    ["install", 19, /install failed/],
    ["partial", 23, undefined],
    ["partial-bytes", 23, undefined],
    ["passed", 1, /Example journey did not pass/],
    ["steps", 1, /steps are incomplete or out of order/],
    ["transport", 1, /must use the Expo native bridge/],
    ["json", 1, /evidence is not valid JSON/],
  ];
  for (const [scenario, status, rule] of cases) {
    writeFileSync(trace, "");
    const run = spawnSync(process.execPath, [runner], {
      cwd: f.directory,
      encoding: "utf8",
      timeout: 15_000,
      env: {
        ...process.env,
        PATH: `${f.directory}:${process.env.PATH ?? ""}`,
        EXAMPLE_RUNNER_SCENARIO: scenario,
        EXAMPLE_RUNNER_TRACE: trace,
      },
    });
    assert.ifError(run.error);
    assert.equal(run.status, status, run.stderr);
    assert.doesNotMatch(run.stderr, /ignored root restart/);
    const commands: string[][] = readFileSync(trace, "utf8")
      .trimEnd()
      .split("\n")
      .map((line) => JSON.parse(line));
    const stopped = commands.some((args) => args.includes("force-stop"));
    assert.equal(stopped, status === 0);
    if (status === 0) {
      assert.equal(run.stderr, "");
      assert.equal(
        run.stdout,
        "Expo example incoming, hold/resume and outgoing journeys passed\n"
      );
    } else {
      assert.equal(run.stdout, "");
      if (rule) assert.match(run.stderr, rule);
      if (["passed", "steps", "transport"].includes(scenario)) {
        assert.match(run.stderr, /AssertionError/);
        assert.match(run.stderr, /actual:/);
        assert.match(run.stderr, /expected:/);
      }
    }
    if (scenario === "partial")
      assert.equal(readFileSync(f.output, "utf8"), '{"passed":');
    if (scenario === "partial-bytes")
      assert.deepEqual(readFileSync(f.output), Buffer.from([0x7b, 0x22, 0xc3]));
  }
});
