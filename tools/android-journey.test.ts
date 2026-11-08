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
