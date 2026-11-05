import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";

import type { ConversationEvent } from "../../src/conversations/transport";

// CallService has no host-native test target; check its bridge contract without iOS frameworks.
const service = readFileSync(
  new URL("../../ios/CallService.swift", import.meta.url),
  "utf8"
);
const mappings = [
  ["audioActivated", "audioActivated"],
  ["audioDeactivated", "audioDeactivated"],
  ["audioInterrupted", "audioDeactivated"],
  ["mediaServicesReset", "audioDeactivated"],
] as const;

for (const [sdkEvent, jsEvent] of mappings) {
  test(`iOS SDK ${sdkEvent} delivers ${jsEvent} and refreshes routes`, () => {
    const branch = service.split("\n    case ").find((entry) => {
      const cases = entry.slice(0, entry.indexOf(":"));
      return cases.split(",").some((name) => name.trim() === `.${sdkEvent}`);
    });
    assert.ok(branch, `Missing SDK event mapping for ${sdkEvent}`);
    const emitted = branch.match(/"type": \.string\("([^"]+)"\)/)?.[1];
    const expected: ConversationEvent = { type: jsEvent, payload: {} };
    assert.equal(emitted, expected.type);
    assert.match(branch, /"payload": \.object\(\[:\]\)/);
    assert.match(branch, /routes\.refresh\(\)/);
  });
}
