import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

/** Validate the incoming, hold/resume and outgoing Expo native bridge journey. */
export function assertExampleJourney(value: unknown): void {
  assert.ok(
    value !== null && typeof value === "object" && !Array.isArray(value),
    "Example journey evidence must be an object"
  );
  const result = value as Record<string, unknown>;
  assert.equal(result.passed, true, "Example journey did not pass");
  assert.deepEqual(
    result.steps,
    [
      "ringing",
      "connecting",
      "connected",
      "held",
      "connected",
      "incoming-ended",
      "dialing",
      "connecting",
      "connected",
      "outgoing-ended",
    ],
    "Example journey steps are incomplete or out of order"
  );
  assert.equal(
    result.transport,
    "expo-native-bridge",
    "Example journey must use the Expo native bridge"
  );
}

if (import.meta.main) {
  try {
    const file = process.argv[2];
    assert.ok(file, "Missing example journey evidence path");
    const result: unknown = JSON.parse(readFileSync(file, "utf8"));
    assertExampleJourney(result);
    console.log(
      "Expo example incoming, hold/resume and outgoing journeys passed"
    );
  } catch (error) {
    if (error instanceof SyntaxError)
      console.error("Example journey evidence is not valid JSON.");
    else console.error(error);
    process.exitCode = 1;
  }
}
