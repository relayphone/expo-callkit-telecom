import assert from "node:assert/strict";
import { test } from "node:test";

import { driveAndroidJourney, newAndroidResults } from "./android-drive.ts";
import type { JourneyApi } from "./android-results.ts";

function fakeApi(): JourneyApi {
  return {
    getConversations: async () => [],
    setConversationHandler() {},
    joinConversation: async () => {},
    setHeld: async () => {},
    startConversation: async () => {
      throw new Error("unused");
    },
    reportConversation: async () => {},
    endConversation: async () => {},
  };
}

test("bounds checkpoint synchronization and reports failures", async () => {
  const result = newAndroidResults("run");
  const api = fakeApi();
  let now = 0;
  await assert.rejects(
    driveAndroidJourney(
      result,
      api,
      { save() {}, acknowledged: async () => false },
      {
        now: () => now,
        pause: async () => {
          now += 10_000;
        },
      }
    ),
    /checkpoint 1/
  );
  assert.equal(result.stage, "failed");
  assert.deepEqual(result.completed, []);
  assert.match(result.failure!, /checkpoint 1/);
});

test("rejected bridge reads and placement cancellation cannot complete a journey", async () => {
  for (const cancellation of [false, true]) {
    const result = newAndroidResults("run");
    const api = fakeApi();
    if (cancellation) result.cancelled.push("deadline_exceeded");
    else
      api.getConversations = async () => {
        throw new Error("Bridge read rejected");
      };
    await assert.rejects(
      driveAndroidJourney(result, api, {
        save() {},
        acknowledged: async () => true,
      }),
      cancellation ? /cancellation/ : /Bridge read rejected/
    );
    assert.equal(result.stage, "failed");
    assert.deepEqual(result.completed, []);
  }
});
