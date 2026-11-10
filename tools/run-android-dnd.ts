import { spawn } from "node:child_process";
import { mkdirSync, writeFileSync } from "node:fs";
import { constants } from "node:os";
import { join } from "node:path";

export interface Command {
  program: "gradle" | "adb" | "node";
  args: readonly string[];
  output?: "capture" | "ignore";
}

export type Execute = (command: Command) => Promise<Uint8Array>;

class CommandFailure extends Error {
  readonly status: number;
  readonly stdout: Uint8Array;

  constructor(
    program: Command["program"],
    status: number,
    stdout: Uint8Array,
    cause?: Error
  ) {
    super(`${program} failed: ${status}`, { cause });
    this.status = status;
    this.stdout = stdout;
  }
}

const execute: Execute = ({ program, args, output }) =>
  new Promise((resolve, reject) => {
    const child = spawn(
      program === "node" ? process.execPath : program,
      [...args],
      {
        stdio: [
          "ignore",
          output === "capture"
            ? "pipe"
            : output === "ignore"
            ? "ignore"
            : "inherit",
          "inherit",
        ],
      }
    );
    const stdout: Buffer[] = [];
    child.stdout?.on("data", (chunk: Buffer) => stdout.push(chunk));
    child.once("error", (error: NodeJS.ErrnoException) => {
      reject(
        new CommandFailure(
          program,
          error.code === "ENOENT" ? 127 : error.code === "EACCES" ? 126 : 1,
          Buffer.concat(stdout),
          error
        )
      );
    });
    child.once("close", (code, signal) => {
      if (code === 0) resolve(Buffer.concat(stdout));
      else {
        const signalNumber = Object.entries(constants.signals).find(
          ([name]) => name === signal
        )?.[1];
        reject(
          new CommandFailure(
            program,
            code ?? 128 + (signalNumber ?? 0),
            Buffer.concat(stdout)
          )
        );
      }
    });
  });

type PolicyCase = readonly [
  name: string,
  mode: "off" | "on" | "priority",
  senders: "any" | "contacts" | "starred",
  calls: boolean,
  repeat: boolean,
  caller: "unknown" | "opaque" | "contact" | "starred" | "repeat",
  allowed: boolean,
  filter: 1 | 2 | 3
];

const cases = [
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
] satisfies readonly PolicyCase[];

const app = "expo.modules.callkittelecom.instrumentation";
const helper = "expo.modules.callkittelecom.dndpolicy";
const runner = `${app}.test/androidx.test.runner.AndroidJUnitRunner`;
const conversations = "expo.modules.callkittelecom.conversations";

function capturedOutput(error: unknown): Uint8Array {
  return error !== null &&
    typeof error === "object" &&
    "stdout" in error &&
    error.stdout instanceof Uint8Array
    ? error.stdout
    : new Uint8Array();
}

/** Runs the API35 DnD matrix and warm cleanup, restoring DnD after success or failure. */
export async function runAndroidDnd(
  output = "android/instrumentation/build/dnd-results",
  run: Execute = execute
): Promise<void> {
  mkdirSync(output, { recursive: true });
  const adb = (args: readonly string[], mode?: Command["output"]) =>
    run({
      program: "adb",
      args,
      ...(mode === undefined ? {} : { output: mode }),
    });
  async function collect(name: string, source: string): Promise<string> {
    const destination = join(output, `${name}.json`);
    writeFileSync(destination, "");
    try {
      writeFileSync(
        destination,
        await adb(
          ["exec-out", "run-as", app, "cat", `files/${source}.json`],
          "capture"
        )
      );
    } catch (error) {
      writeFileSync(destination, capturedOutput(error));
      throw error;
    }
    return destination;
  }
  try {
    await run({
      program: "gradle",
      args: [
        "-p",
        "android/instrumentation",
        ":dnd-policy-helper:assembleDebug",
        "--stacktrace",
      ],
    });
    await adb([
      "install",
      "-r",
      "-t",
      "android/instrumentation/policy-helper/build/outputs/apk/debug/dnd-policy-helper-debug.apk",
    ]);
    await adb(["shell", "cmd", "notification", "allow_dnd", helper]);
    for (const permission of ["READ_CONTACTS", "WRITE_CONTACTS"])
      await adb([
        "shell",
        "pm",
        "grant",
        app,
        `android.permission.${permission}`,
      ]);
    await adb([
      "shell",
      "cmd",
      "notification",
      "allow_listener",
      `${app}/${conversations}.DndObservationService`,
    ]);
    await adb([
      "shell",
      "am",
      "start",
      "-W",
      "-n",
      `${app}/${conversations}.DndBootstrapActivity`,
    ]);
    for (const [
      name,
      mode,
      senders,
      calls,
      repeat,
      caller,
      allowed,
      filter,
    ] of cases) {
      await adb([
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
      ]);
      await adb(["shell", "cmd", "notification", "set_dnd", mode]);
      await adb([
        "shell",
        "am",
        "instrument",
        "--no-restart",
        "-w",
        "-e",
        "class",
        `${conversations}.DndMatrixTest`,
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
      ]);
      const destination = await collect(name, `dnd-${name}`);
      await run({
        program: "node",
        args: ["tools/check-android-dnd-case.ts", destination],
      });
    }
    await adb([
      "shell",
      "am",
      "instrument",
      "--no-restart",
      "-w",
      "-e",
      "class",
      `${conversations}.DndWarmCleanupTest`,
      "-e",
      "dndWarm",
      "true",
      runner,
    ]);
    await collect("warm-cleanup", "dnd-warm-cleanup");
    await run({
      program: "node",
      args: ["tools/check-android-dnd.ts", output],
    });
  } finally {
    await adb(["shell", "cmd", "notification", "set_dnd", "off"], "ignore");
  }
}

if (import.meta.main) {
  let terminated: "SIGTERM" | "SIGHUP" | "SIGINT" | undefined;
  let interrupted = false;
  const terminate = () => {
    terminated ??= "SIGTERM";
  };
  const hangup = () => {
    terminated ??= "SIGHUP";
  };
  const interrupt = () => {
    interrupted = true;
  };
  process.once("SIGTERM", terminate);
  process.once("SIGHUP", hangup);
  process.on("SIGINT", interrupt);
  try {
    await runAndroidDnd(undefined, async (command) => {
      // Finish the current command before restoring DnD, as the EXIT trap did.
      if (terminated && command.output !== "ignore")
        throw new CommandFailure(
          command.program,
          128 + constants.signals[terminated],
          new Uint8Array()
        );
      try {
        return await execute(command);
      } catch (error) {
        // A foreground command that handles SIGINT can let the suite continue.
        if (
          interrupted &&
          error instanceof CommandFailure &&
          error.status === 130
        )
          terminated ??= "SIGINT";
        throw error;
      } finally {
        interrupted = false;
      }
    });
  } catch (error) {
    if (error instanceof CommandFailure) {
      if (error.cause !== undefined) console.error(error.cause);
      process.exitCode = error.status;
    } else {
      console.error(error);
      process.exitCode = 1;
    }
  } finally {
    process.removeListener("SIGTERM", terminate);
    process.removeListener("SIGHUP", hangup);
    process.removeListener("SIGINT", interrupt);
    if (terminated) process.kill(process.pid, terminated);
  }
}
