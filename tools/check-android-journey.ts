import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

/** Validate the incoming, concurrent held-call and outgoing Core-Telecom journey. */
export function assertCoreTelecomJourney(value: unknown): void {
  assert.ok(
    value !== null && typeof value === "object" && !Array.isArray(value),
    "Android journey evidence must be an object"
  );
  const result = value as Record<string, unknown>;
  assert.equal(result.passed, true, "Android journey did not pass");
  assert.deepEqual(
    result.steps,
    [
      "ringing",
      "connecting",
      "connected",
      "held",
      "second-ringing",
      "second-ended",
      "connected",
      "incoming-ended",
      "dialing",
      "connecting",
      "connected",
      "outgoing-ended",
    ],
    "Android journey steps are incomplete or out of order"
  );
  assert.equal(
    result.identityPolicy,
    "raw-application-id",
    "Missing raw application identity policy"
  );
}

if (import.meta.main) {
  try {
    const file = process.argv[2];
    assert.ok(file, "Missing Android journey evidence path");
    const result: unknown = JSON.parse(readFileSync(file, "utf8"));
    assertCoreTelecomJourney(result);
    console.log(
      "Core-Telecom incoming, concurrent held call and outgoing journeys passed"
    );
  } catch (error) {
    if (error instanceof SyntaxError)
      console.error("Android journey evidence is not valid JSON.");
    else console.error(error);
    process.exitCode = 1;
  }
}
