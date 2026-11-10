import assert from "node:assert/strict";
import {
  mkdtempSync,
  readFileSync,
  readdirSync,
  rmSync,
  writeFileSync,
} from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { test } from "node:test";

import {
  runNativeMediaJourney,
  type Command,
  type Execute,
} from "./run-native-media-journey.ts";

function fixture() {
  const output = mkdtempSync(join(tmpdir(), "native-media-journey-"));
  const calls: Command[] = [];
  const run: Execute = (command) => {
    calls.push(command);
    return command.program === "adb"
      ? JSON.stringify({ name: command.args.at(-1) })
      : "";
  };
  return {
    output,
    calls,
    run,
    close: () => rmSync(output, { recursive: true, force: true }),
  };
}

test("runs the complete functional suite once and checks all four results", () => {
  const f = fixture();
  try {
    runNativeMediaJourney(f.output, f.run);
    assert.equal(f.calls.length, 9);
    assert.deepEqual(f.calls[0], {
      program: "gradle",
      args: [
        "-p",
        "android/instrumentation",
        ":connectedDebugAndroidTest",
        "-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true",
        "--stacktrace",
      ],
      timeout: 1_500_000,
    });
    const names = [
      "journey-results",
      "answered-elsewhere-results",
      "native-media-results",
      "native-webrtc-results",
    ];
    for (const [index, name] of names.entries()) {
      assert.deepEqual(f.calls[index * 2 + 1], {
        program: "adb",
        args: [
          "exec-out",
          "run-as",
          "expo.modules.callkittelecom.instrumentation",
          "cat",
          `files/${name}.json`,
        ],
        timeout: 15_000,
        capture: true,
      });
      assert.equal(
        JSON.parse(readFileSync(join(f.output, `${name}.json`), "utf8")).name,
        `files/${name}.json`
      );
      const checker =
        index === 0
          ? "check-android-journey.ts"
          : index === 1
          ? "check-answered-elsewhere.ts"
          : "check-native-media.ts";
      assert.deepEqual(f.calls[index * 2 + 2], {
        program: "node",
        args: [join("tools", checker), join(f.output, `${name}.json`)],
        timeout: 15_000,
      });
    }
    assert.equal(readdirSync(f.output).length, 4);
  } finally {
    f.close();
  }
});

test("retains available media diagnostics after a failed suite and propagates its failure", () => {
  const f = fixture();
  const failure = new Error("suite failed");
  try {
    writeFileSync(join(f.output, "native-webrtc-results.json"), "stale");
    assert.throws(
      () =>
        runNativeMediaJourney(f.output, (command) => {
          if (command.program === "gradle") {
            f.run(command);
            throw failure;
          }
          if (command.args.at(-1) === "files/native-webrtc-results.json")
            throw new Error("unavailable");
          return f.run(command);
        }),
      (error) => error === failure
    );
    assert.deepEqual(readdirSync(f.output).sort(), [
      "answered-elsewhere-results.json",
      "native-media-results.json",
    ]);
    assert.equal(
      f.calls.filter((command) => command.program === "node").length,
      0
    );
  } finally {
    f.close();
  }
});

for (const [index, name] of [
  "journey-results",
  "answered-elsewhere-results",
  "native-media-results",
  "native-webrtc-results",
].entries())
  test(`a failed ${name} check stays fatal while remaining evidence is collected`, () => {
    const f = fixture();
    const failure = new Error("invalid journey evidence");
    try {
      assert.throws(
        () =>
          runNativeMediaJourney(f.output, (command) => {
            const result = f.run(command);
            if (command.args.at(-1) === join(f.output, `${name}.json`))
              throw failure;
            return result;
          }),
        (error) => error === failure
      );
      assert.equal(readdirSync(f.output).length, 4);
      assert.equal(
        f.calls.filter((command) => command.program === "node").length,
        index + 1
      );
      assert.equal(
        f.calls.filter((command) => command.program === "gradle").length,
        1
      );
    } finally {
      f.close();
    }
  });
