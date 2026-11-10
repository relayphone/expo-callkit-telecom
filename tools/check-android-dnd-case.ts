import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

/** Require a passing case before continuing the Android DnD policy run. */
export function assertAndroidDndCase(value: unknown): void {
  assert.ok(
    value !== null && typeof value === "object" && !Array.isArray(value),
    "Android DnD case evidence must be an object"
  );
  const result = value as Record<string, unknown>;
  assert.equal(
    result.passed,
    true,
    "Android DnD case passed flag must be true"
  );
}

if (import.meta.main) {
  const file = process.argv[2];
  try {
    assert.ok(file, "Missing Android DnD case evidence path");
    const result: unknown = JSON.parse(readFileSync(file, "utf8"));
    assertAndroidDndCase(result);
  } catch (error) {
    if (file) console.error(`Android DnD case evidence: ${file}`);
    if (error instanceof SyntaxError)
      console.error("Android DnD case evidence is not valid JSON.");
    else console.error(error);
    process.exitCode = 1;
  }
}
