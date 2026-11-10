import { spawn } from "node:child_process";
import { writeFileSync } from "node:fs";
import { constants } from "node:os";
import { setTimeout as sleep } from "node:timers/promises";

export interface Command {
  program: "adb" | "node";
  args: readonly string[];
  timeout?: number;
  output?: "capture" | "ignore";
}

export type Execute = (command: Command) => Promise<Uint8Array>;

const execute: Execute = ({ program, args, timeout, output }) =>
  new Promise((resolve, reject) => {
    const child = spawn(
      program === "node" ? process.execPath : program,
      [...args],
      {
        stdio: [
          "ignore",
          output === "ignore"
            ? "ignore"
            : output === "capture"
            ? "pipe"
            : "inherit",
          output === "ignore" ? "ignore" : "inherit",
        ],
      }
    );
    let timedOut = false;
    const deadline =
      timeout === undefined
        ? undefined
        : setTimeout(() => {
            timedOut = true;
            child.kill("SIGKILL");
          }, timeout);
    const stdout: Buffer[] = [];
    child.stdout?.on("data", (chunk: Buffer) => {
      stdout.push(chunk);
    });
    child.once("error", (error) => {
      clearTimeout(deadline);
      reject(error);
    });
    child.once("close", (code, signal) => {
      clearTimeout(deadline);
      if (code === 0) resolve(Buffer.concat(stdout));
      else {
        const signalNumber = Object.entries(constants.signals).find(
          ([name]) => name === signal
        )?.[1];
        reject(
          Object.assign(new Error(`${program} failed: ${code ?? signal}`), {
            code: timedOut ? 124 : code ?? 128 + (signalNumber ?? 0),
            stdout: Buffer.concat(stdout),
          })
        );
      }
    });
  });

const app = "com.example.expocallkittelecom";
const result = `/data/user/0/${app}/files/conversation-journey.json`;

function capturedOutput(error: unknown): Uint8Array {
  return error !== null &&
    typeof error === "object" &&
    "stdout" in error &&
    error.stdout instanceof Uint8Array
    ? error.stdout
    : new Uint8Array();
}

/** Run the release example journey through root ADB and validate its evidence. */
export async function runExampleJourney(
  output = "example-journey.json",
  run: Execute = execute,
  delay: (milliseconds: number) => Promise<void> = sleep
): Promise<void> {
  const adb = (
    args: readonly string[],
    options: Omit<Command, "program" | "args"> = {}
  ) => run({ program: "adb", args, ...options });
  // adbd may close the root request while restarting; verify its resulting identity.
  try {
    await adb(["root"], { timeout: 15_000, output: "ignore" });
  } catch {
    /* The identity check below remains authoritative. */
  }
  await adb(["wait-for-device"], { timeout: 30_000 });
  let uid = "";
  try {
    uid = Buffer.from(
      await adb(["shell", "id", "-u"], {
        timeout: 10_000,
        output: "capture",
      })
    ).toString("utf8");
  } catch (error) {
    uid = Buffer.from(capturedOutput(error)).toString("utf8");
  }
  if (uid.replaceAll("\r", "").replace(/\n+$/u, "") !== "0")
    throw new Error(
      "The example journey requires the test image to provide root ADB"
    );
  await adb(["install", "-r", "example-apk/app-release.apk"]);
  for (const permission of ["RECORD_AUDIO", "POST_NOTIFICATIONS"])
    await adb([
      "shell",
      "pm",
      "grant",
      app,
      `android.permission.${permission}`,
    ]);
  await adb([
    "shell",
    "am",
    "start",
    "-W",
    "-a",
    "android.intent.action.VIEW",
    "-d",
    "conversations-example://journey",
    app,
  ]);
  for (let attempt = 0; attempt < 60; attempt++) {
    let ready = false;
    try {
      await adb(["shell", "test", "-s", result]);
      ready = true;
    } catch {
      /* Keep polling until the app writes a nonempty result. */
    }
    if (ready) {
      writeFileSync(output, "");
      try {
        writeFileSync(
          output,
          await adb(["exec-out", "cat", result], { output: "capture" })
        );
      } catch (error) {
        writeFileSync(output, capturedOutput(error));
        throw error;
      }
      await run({
        program: "node",
        args: ["tools/check-example-journey.ts", output],
      });
      await adb(["shell", "am", "force-stop", app]);
      return;
    }
    await delay(2_000);
  }
  await adb(["logcat", "-d", "-s", "AndroidRuntime"]);
  throw new Error(
    "The example did not produce its journey result within 120 seconds"
  );
}

if (import.meta.main) {
  try {
    await runExampleJourney();
  } catch (error) {
    const code =
      error !== null && typeof error === "object" && "code" in error
        ? error.code
        : undefined;
    if (typeof code !== "number") console.error(error);
    process.exitCode =
      typeof code === "number"
        ? code
        : code === "ENOENT"
        ? 127
        : code === "EACCES"
        ? 126
        : 1;
  }
}
