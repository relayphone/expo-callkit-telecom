import assert from "node:assert/strict";
import { spawn, spawnSync } from "node:child_process";
import {
  mkdirSync,
  chmodSync,
  existsSync,
  mkdtempSync,
  readFileSync,
  readdirSync,
  rmSync,
  symlinkSync,
  writeFileSync,
} from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { test, type TestContext } from "node:test";
import { setTimeout as sleep } from "node:timers/promises";
import { fileURLToPath } from "node:url";

import {
  runAndroidDnd,
  type Command,
  type Execute,
} from "./run-android-dnd.ts";

const app = "expo.modules.callkittelecom.instrumentation";
const helper = "expo.modules.callkittelecom.dndpolicy";
const namespace = "expo.modules.callkittelecom.conversations";
const runner = `${app}.test/androidx.test.runner.AndroidJUnitRunner`;
const restore: Command = {
  program: "adb",
  args: ["shell", "cmd", "notification", "set_dnd", "off"],
  output: "ignore",
};

const policies = [
  ["off-any", "off", "any", true, false, "unknown", true, 1],
  ["on-any", "on", "any", true, false, "unknown", false, 3],
  ["priority-any", "priority", "any", true, false, "unknown", true, 2],
  ["priority-none", "priority", "any", false, false, "unknown", false, 2],
  [
    "priority-contacts-unknown",
    "priority",
    "contacts",
    true,
    false,
    "unknown",
    false,
    2,
  ],
  [
    "priority-contacts-opaque",
    "priority",
    "contacts",
    true,
    false,
    "opaque",
    false,
    2,
  ],
  [
    "priority-contacts-contact",
    "priority",
    "contacts",
    true,
    false,
    "contact",
    true,
    2,
  ],
  [
    "priority-starred-contact",
    "priority",
    "starred",
    true,
    false,
    "contact",
    false,
    2,
  ],
  [
    "priority-starred-starred",
    "priority",
    "starred",
    true,
    false,
    "starred",
    true,
    2,
  ],
  ["priority-repeat-first", "priority", "any", false, true, "repeat", false, 2],
  ["priority-repeat-second", "priority", "any", false, true, "repeat", true, 2],
  ["off-restored", "off", "any", true, false, "unknown", true, 1],
] as const;

function fixture(t: TestContext) {
  const directory = mkdtempSync(join(tmpdir(), "android-dnd-runner-"));
  t.after(() => rmSync(directory, { recursive: true, force: true }));
  const output = join(directory, "dnd-results");
  const calls: Command[] = [];
  const bytes = Buffer.from([0x7b, 0x22, 0xc3]);
  const run: Execute = async (command) => {
    calls.push(command);
    return command.output === "capture" ? bytes : Buffer.alloc(0);
  };
  return { directory, output, calls, bytes, run };
}

test("keeps helper setup, all twelve policies, warm cleanup and checker order", async (t) => {
  const f = fixture(t);
  await runAndroidDnd(f.output, f.run);
  assert.equal(f.calls.length, 71);
  assert.deepEqual(f.calls.slice(0, 7), [
    {
      program: "gradle",
      args: [
        "-p",
        "android/instrumentation",
        ":dnd-policy-helper:assembleDebug",
        "--stacktrace",
      ],
    },
    {
      program: "adb",
      args: [
        "install",
        "-r",
        "-t",
        "android/instrumentation/policy-helper/build/outputs/apk/debug/dnd-policy-helper-debug.apk",
      ],
    },
    {
      program: "adb",
      args: ["shell", "cmd", "notification", "allow_dnd", helper],
    },
    {
      program: "adb",
      args: ["shell", "pm", "grant", app, "android.permission.READ_CONTACTS"],
    },
    {
      program: "adb",
      args: ["shell", "pm", "grant", app, "android.permission.WRITE_CONTACTS"],
    },
    {
      program: "adb",
      args: [
        "shell",
        "cmd",
        "notification",
        "allow_listener",
        `${app}/${namespace}.DndObservationService`,
      ],
    },
    {
      program: "adb",
      args: [
        "shell",
        "am",
        "start",
        "-W",
        "-n",
        `${app}/${namespace}.DndBootstrapActivity`,
      ],
    },
  ]);
  for (const [
    index,
    [name, mode, senders, calls, repeat, caller, allowed, filter],
  ] of policies.entries()) {
    assert.deepEqual(f.calls.slice(7 + index * 5, 12 + index * 5), [
      {
        program: "adb",
        args: [
          "shell",
          "am",
          "broadcast",
          "-a",
          "expo.callkittelecom.SET_DND_POLICY",
          "-n",
          `${helper}/.PolicyReceiver`,
          "--es",
          "senders",
          senders,
          "--ez",
          "calls",
          String(calls),
          "--ez",
          "repeat",
          String(repeat),
        ],
      },
      {
        program: "adb",
        args: ["shell", "cmd", "notification", "set_dnd", mode],
      },
      {
        program: "adb",
        args: [
          "shell",
          "am",
          "instrument",
          "--no-restart",
          "-w",
          "-e",
          "class",
          `${namespace}.DndMatrixTest`,
          "-e",
          "dndCase",
          name,
          "-e",
          "allowed",
          String(allowed),
          "-e",
          "caller",
          caller,
          "-e",
          "mode",
          String(filter),
          runner,
        ],
      },
      {
        program: "adb",
        args: ["exec-out", "run-as", app, "cat", `files/dnd-${name}.json`],
        output: "capture",
      },
      {
        program: "node",
        args: [
          "tools/check-android-dnd-case.ts",
          join(f.output, `${name}.json`),
        ],
      },
    ]);
    assert.deepEqual(readFileSync(join(f.output, `${name}.json`)), f.bytes);
  }
  assert.deepEqual(f.calls.slice(67), [
    {
      program: "adb",
      args: [
        "shell",
        "am",
        "instrument",
        "--no-restart",
        "-w",
        "-e",
        "class",
        `${namespace}.DndWarmCleanupTest`,
        "-e",
        "dndWarm",
        "true",
        runner,
      ],
    },
    {
      program: "adb",
      args: ["exec-out", "run-as", app, "cat", "files/dnd-warm-cleanup.json"],
      output: "capture",
    },
    { program: "node", args: ["tools/check-android-dnd.ts", f.output] },
    restore,
  ]);
  assert.equal(readdirSync(f.output).length, 13);
  assert.deepEqual(readFileSync(join(f.output, "warm-cleanup.json")), f.bytes);
});

test("every failed setup, policy, collection or check stops the suite and restores DnD", async (t) => {
  for (let index = 0; index < 70; index++) {
    const f = fixture(t);
    const failure = new Error(`command ${index} failed`);
    await assert.rejects(
      runAndroidDnd(f.output, async (command) => {
        const result = await f.run(command);
        if (f.calls.length === index + 1) throw failure;
        return result;
      }),
      (error) => error === failure
    );
    assert.equal(f.calls.length, index + 2);
    assert.deepEqual(f.calls.at(-1), restore);
    assert.notDeepEqual(f.calls.at(-2), restore);
  }
});

test("a failed restoration stays fatal and overrides an earlier failure", async (t) => {
  for (const failSetup of [false, true]) {
    const f = fixture(t);
    const cleanup = new Error("restoration failed");
    await assert.rejects(
      runAndroidDnd(f.output, async (command) => {
        const result = await f.run(command);
        if (command.output === "ignore") throw cleanup;
        if (failSetup) throw new Error("setup failed");
        return result;
      }),
      (error) => error === cleanup
    );
    assert.deepEqual(f.calls.at(-1), restore);
    assert.equal(f.calls.length, failSetup ? 2 : 71);
  }
});

test("truncates stale evidence and preserves partial bytes when collection fails", async (t) => {
  for (const name of ["off-any", "warm-cleanup"]) {
    for (const stdout of [undefined, Buffer.from([0x7b, 0x22, 0xc3])]) {
      const f = fixture(t);
      mkdirSync(f.output);
      const file = join(f.output, `${name}.json`);
      writeFileSync(file, "stale evidence");
      const failure = Object.assign(new Error("collection failed"), { stdout });
      await assert.rejects(
        runAndroidDnd(f.output, async (command) => {
          if (command.args.at(-1) === `files/dnd-${name}.json`) {
            assert.equal(readFileSync(file, "utf8"), "");
            f.calls.push(command);
            throw failure;
          }
          return f.run(command);
        }),
        (error) => error === failure
      );
      assert.deepEqual(readFileSync(file), stdout ?? Buffer.alloc(0));
      assert.deepEqual(f.calls.at(-1), restore);
    }
  }
});

test("an output-directory failure happens before setup or DnD restoration", async (t) => {
  const f = fixture(t);
  writeFileSync(f.output, "not a directory");
  await assert.rejects(runAndroidDnd(f.output, f.run), { code: "EEXIST" });
  assert.equal(f.calls.length, 0);
});

test("missing and nonexecutable commands preserve shell startup codes and still restore DnD", (t) => {
  for (const [scenario, status, diagnostic] of [
    ["missing", 127, /ENOENT/],
    ["permission", 126, /EACCES/],
  ] as const) {
    const f = fixture(t);
    const trace = join(f.directory, "restored");
    const adb = join(f.directory, "fake-adb.ts");
    writeFileSync(
      adb,
      `#!${process.execPath}\nimport {writeFileSync} from "node:fs";writeFileSync(process.env.DND_RUNNER_TRACE!,JSON.stringify(process.argv.slice(2)));\n`,
      { mode: 0o755 }
    );
    symlinkSync(adb, join(f.directory, "adb"));
    if (scenario === "permission") {
      writeFileSync(join(f.directory, "gradle"), "not executable");
      chmodSync(join(f.directory, "gradle"), 0o644);
    }
    const result = spawnSync(
      process.execPath,
      [fileURLToPath(new URL("./run-android-dnd.ts", import.meta.url))],
      {
        cwd: f.directory,
        encoding: "utf8",
        timeout: 15_000,
        env: { ...process.env, PATH: f.directory, DND_RUNNER_TRACE: trace },
      }
    );
    assert.ifError(result.error);
    assert.equal(result.status, status, result.stderr);
    assert.match(result.stderr, diagnostic);
    assert.deepEqual(JSON.parse(readFileSync(trace, "utf8")), restore.args);
  }
});

test("real CLI retains checker diagnostics, command exits, partial bytes and restoration", (t) => {
  const f = fixture(t);
  const binary = join(f.directory, "bin");
  mkdirSync(binary);
  const fake = join(binary, "fake.ts");
  writeFileSync(
    fake,
    `#!${process.execPath}
import {appendFileSync,readFileSync} from "node:fs";
import {basename,join} from "node:path";
const args=process.argv.slice(2);
const program=basename(process.argv[1]!);
const trace=process.env.DND_RUNNER_TRACE!;
const scenario=process.env.DND_RUNNER_SCENARIO;
const previous=readFileSync(trace,"utf8").trim().split("\\n").filter(Boolean).map(line=>JSON.parse(line));
appendFileSync(trace,JSON.stringify({program,args})+"\\n");
if(program==="gradle" && scenario==="killed") process.kill(process.pid,"SIGKILL");
if(program==="gradle" && scenario?.startsWith("setup")) {console.error("helper build failed");process.exit(17);}
if(args.join(" ")==="shell cmd notification set_dnd off" && scenario?.includes("cleanup") && (scenario.startsWith("setup") || previous.filter(command=>command.args.join(" ")===args.join(" ")).length===2)) {console.error("restoration failed");process.exit(29);}
if(args[0]==="exec-out") {
  if(scenario==="partial") {process.stdout.write(Buffer.from([0x7b,0x22,0xc3]));process.exit(23);}
  const file=basename(args.at(-1)!).replace(/^dnd-/,"");
  process.stdout.write(readFileSync(join(process.env.DND_RUNNER_NATIVE!,file)));
}
`,
    { mode: 0o755 }
  );
  for (const name of ["adb", "gradle"]) symlinkSync(fake, join(binary, name));
  const scenarios: readonly [string, number, RegExp | undefined][] = [
    ["success", 0, undefined],
    ["setup", 17, /helper build failed/],
    ["killed", 137, undefined],
    ["partial", 23, undefined],
    ["case", 1, /Android DnD case passed flag must be true/],
    ["aggregate", 1, /off-any.coreAccepted must be true/],
    ["warm", 1, /warm-cleanup.captureStarted must be false/],
    ["json", 1, /Android DnD case evidence is not valid JSON/],
    ["cleanup", 29, /restoration failed/],
    ["setup-cleanup", 29, /helper build failed[\s\S]*restoration failed/],
  ];
  for (const [scenario, status, rule] of scenarios) {
    const cwd = join(f.directory, scenario);
    const native = join(cwd, "native");
    mkdirSync(native, { recursive: true });
    symlinkSync(
      fileURLToPath(new URL(".", import.meta.url)),
      join(cwd, "tools"),
      "dir"
    );
    writeFileSync(join(cwd, "package.json"), '{"type":"module"}');
    for (const [name, , , , , , permitted, filter] of policies) {
      const value = {
        passed: !(scenario === "case" && name === "off-any"),
        case: name,
        coreAccepted: !(scenario === "aggregate" && name === "off-any"),
        coreRingingObserved: true,
        coreCallsAfterCleanup: 0,
        callFilterAllowed: permitted,
        notificationAllowed: permitted,
        ringtonePlaybackObserved: permitted,
        ringVolume: permitted ? 3 : 0,
        interruptionFilter: filter,
        ringerMode: filter === 1 ? 2 : 0,
        notificationCategory: "call",
        soundConfigured: true,
        nativeHookCount: 1,
        nativeAllowedAfterRaw: permitted,
        outcome: permitted
          ? { status: "reported", id: name }
          : { status: "filtered", reason: "doNotDisturb" },
      };
      writeFileSync(
        join(native, `${name}.json`),
        scenario === "json" ? "invalid JSON" : JSON.stringify(value)
      );
    }
    writeFileSync(
      join(native, "warm-cleanup.json"),
      JSON.stringify({
        passed: true,
        prejoinedBeforeFilter: true,
        filteredReason: "doNotDisturb",
        serverClients: 0,
        captureStarted: scenario === "warm",
        nativeClosed: true,
        coreCallsAfterCleanup: 0,
        delayedRejectPreventsAnswerAndNotification: true,
        appReasons: ["blockList", "other"],
      })
    );
    const trace = join(cwd, "trace.jsonl");
    writeFileSync(trace, "");
    const result = spawnSync(
      process.execPath,
      [fileURLToPath(new URL("./run-android-dnd.ts", import.meta.url))],
      {
        cwd,
        encoding: "utf8",
        timeout: 15_000,
        env: {
          ...process.env,
          PATH: `${binary}:${process.env.PATH ?? ""}`,
          DND_RUNNER_SCENARIO: scenario,
          DND_RUNNER_TRACE: trace,
          DND_RUNNER_NATIVE: native,
        },
      }
    );
    assert.ifError(result.error);
    assert.equal(result.status, status, result.stderr);
    if (rule) assert.match(result.stderr, rule);
    if (["case", "aggregate", "warm"].includes(scenario)) {
      assert.match(result.stderr, /AssertionError/);
      assert.match(result.stderr, /actual:/);
      assert.match(result.stderr, /expected:/);
    }
    const commands: { program: string; args: string[] }[] = readFileSync(
      trace,
      "utf8"
    )
      .trimEnd()
      .split("\n")
      .map((line) => JSON.parse(line));
    assert.deepEqual(commands.at(-1), {
      program: "adb",
      args: [...restore.args],
    });
    const output = join(cwd, "android/instrumentation/build/dnd-results");
    if (scenario === "partial")
      assert.deepEqual(
        readFileSync(join(output, "off-any.json")),
        Buffer.from([0x7b, 0x22, 0xc3])
      );
    if (scenario === "success") {
      assert.equal(result.stderr, "");
      assert.equal(readdirSync(output).length, 13);
      assert.match(result.stdout, /^off-any: Core accepted;/);
      assert.match(
        result.stdout,
        /Prewarmed DND cleanup and native blockList\/other outcomes passed\n$/
      );
    }
    if (
      ["setup", "setup-cleanup", "killed", "partial", "case", "json"].includes(
        scenario
      )
    ) {
      assert.equal(result.stdout, "");
      assert.equal(
        commands.filter((command) =>
          command.args.some((argument) =>
            argument.endsWith(".DndWarmCleanupTest")
          )
        ).length,
        0
      );
    }
  }
});

for (const [signal, childInterrupted] of [
  ["SIGTERM", false],
  ["SIGHUP", false],
  ["SIGINT", false],
  ["SIGINT", true],
] as const)
  for (const cleanupFailed of [false, true])
    test(`${signal} with child interruption ${childInterrupted} and restoration failure ${cleanupFailed} preserves shell exits`, async (t) => {
      const f = fixture(t);
      const binary = join(f.directory, "bin");
      mkdirSync(binary);
      const trace = join(f.directory, "trace.jsonl");
      const release = join(f.directory, "release");
      writeFileSync(trace, "");
      const fake = join(binary, "fake.ts");
      writeFileSync(
        fake,
        `#!${process.execPath}
import {appendFileSync,existsSync} from "node:fs";
import {basename} from "node:path";
import {setTimeout as sleep} from "node:timers/promises";
const program=basename(process.argv[1]!);
appendFileSync(process.env.DND_RUNNER_TRACE!,JSON.stringify({program,args:process.argv.slice(2)})+"\\n");
if(program==="gradle") {
  for(let attempt=0;attempt<500&&!existsSync(process.env.DND_RUNNER_RELEASE!);attempt++) await sleep(10);
  if(process.env.DND_RUNNER_CHILD_INTERRUPT==="1") process.kill(process.pid,"SIGINT");
  else if(process.env.DND_RUNNER_PARENT_SIGNAL==="SIGINT") process.exit(17);
} else if(process.env.DND_RUNNER_CLEANUP_FAIL==="1") {
  process.exit(29);
}
`,
        { mode: 0o755 }
      );
      for (const name of ["gradle", "adb"])
        symlinkSync(fake, join(binary, name));
      const child = spawn(
        process.execPath,
        [fileURLToPath(new URL("./run-android-dnd.ts", import.meta.url))],
        {
          cwd: f.directory,
          stdio: "ignore",
          timeout: 10_000,
          killSignal: "SIGKILL",
          env: {
            ...process.env,
            PATH: `${binary}:${process.env.PATH ?? ""}`,
            DND_RUNNER_TRACE: trace,
            DND_RUNNER_RELEASE: release,
            DND_RUNNER_CHILD_INTERRUPT: childInterrupted ? "1" : "0",
            DND_RUNNER_PARENT_SIGNAL: signal,
            DND_RUNNER_CLEANUP_FAIL: cleanupFailed ? "1" : "0",
          },
        }
      );
      const closed = new Promise<{
        code: number | null;
        signal: NodeJS.Signals | null;
      }>((resolve, reject) => {
        child.once("error", reject);
        child.once("close", (code, signal) => resolve({ code, signal }));
      });
      try {
        for (
          let attempt = 0;
          attempt < 500 && !readFileSync(trace, "utf8");
          attempt++
        )
          await sleep(10);
        assert.match(readFileSync(trace, "utf8"), /"program":"gradle"/);
        assert.ok(child.kill(signal));
        writeFileSync(release, "");
        assert.deepEqual(
          await closed,
          signal === "SIGINT" && !childInterrupted
            ? { code: cleanupFailed ? 29 : 17, signal: null }
            : { code: null, signal }
        );
        const commands: { program: string; args: string[] }[] = readFileSync(
          trace,
          "utf8"
        )
          .trimEnd()
          .split("\n")
          .map((line) => JSON.parse(line));
        assert.equal(commands.length, 2);
        assert.equal(commands[0]?.program, "gradle");
        assert.deepEqual(commands[1], {
          program: "adb",
          args: [...restore.args],
        });
      } finally {
        child.kill("SIGKILL");
        if (existsSync(f.directory)) writeFileSync(release, "");
        await closed;
      }
    });
