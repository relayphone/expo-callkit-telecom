import assert from "node:assert/strict";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { test } from "node:test";

import { runAndroidJourney } from "./android-journey.ts";

test("instrumentation failure and timeout fail the host and still stop the app", () => {
  for (const timeout of [false, true]) {
    const output = mkdtempSync(join(tmpdir(), "android-journey-"));
    const recorded: string[][] = [];
    try {
      assert.throws(
        () =>
          runAndroidJourney(
            "apks",
            output,
            (args, budget) => {
              assert.ok(budget > 0 && budget <= 330_000);
              recorded.push([...args]);
              if (args.includes("instrument")) {
                if (timeout) throw new Error("Subprocess timed out");
                return "FAILURES!!!";
              }
              if (args.includes("cat")) return "{}";
              return "";
            },
            "run"
          ),
        /timed out|instrumentation did not pass/
      );
      assert.deepEqual(recorded.at(-1), [
        "shell",
        "am",
        "force-stop",
        "com.example.expocallkittelecom",
      ]);
    } finally {
      rmSync(output, { recursive: true });
    }
  }
});

test("requires both native identity controls and the bridge journey before validating evidence", () => {
  for (const instrumentation of [
    "OK (2 tests)",
    "OK (1 test)",
    "OK (2 tests)\nFAILURES!!!",
  ]) {
    const output = mkdtempSync(join(tmpdir(), "android-journey-"));
    try {
      assert.throws(
        () =>
          runAndroidJourney(
            "apks",
            output,
            (args) => {
              if (args.includes("instrument")) return instrumentation;
              if (args.includes("cat"))
                return JSON.stringify({
                  schemaVersion: 1,
                  runID: "run",
                  stage: "failed",
                  failure: "Evidence must still be validated",
                });
              return "";
            },
            "run"
          ),
        instrumentation === "OK (2 tests)"
          ? /Evidence must still be validated/
          : /instrumentation did not pass/
      );
    } finally {
      rmSync(output, { recursive: true });
    }
  }
});
