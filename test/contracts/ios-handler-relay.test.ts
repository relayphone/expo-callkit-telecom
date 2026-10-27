import { expect, test } from "bun:test";
import type { SDKHandlerRelay } from "../../apple/BridgeContract/contract";
import { HandlerDispatcher } from "../../src/conversations/runtime";
import type { HandlerAction } from "../../src/conversations/transport";
import type { ConversationId } from "../../src/conversations/types";

// This is module evaluation, with no React tree, hooks, or native-owner creation.
// The relay's exact SDK type is checked separately against the pinned checkout.
const attachments: [boolean, number][] = [];
const settlements: [string, number, boolean][] = [];
const invocations: string[] = [];
const relay: SDKHandlerRelay = {
  setHandlerAttached(attached, generation) {
    attachments.push([attached, generation]);
  },
  settleAction(actionID, generation, success) {
    settlements.push([actionID, generation, success]);
  },
};
const dispatcher = new HandlerDispatcher(relay);
dispatcher.set({
  start() {
    invocations.push("start");
  },
  end() {
    invocations.push("end");
  },
});

const flush = () => new Promise<void>((resolve) => setTimeout(resolve, 0));

test("module-scope SDK handler relay attaches before React and settles End once", async () => {
  expect(attachments).toEqual([[true, 1]]);
  const action: HandlerAction = {
    actionID: "m35-end",
    generation: 1,
    kind: "end",
    origin: "system",
    deadline: new Date(Date.now() + 30_000).toISOString(),
    conversation: {
      // Fixture represents the UUID already validated by a future wire projector.
      id: "e9799cf2-daba-51df-8bdf-d59b88db0bca" as ConversationId,
      direction: "incoming",
      status: "ringing",
      members: [],
      isMuted: false,
      isVideo: false,
      capabilities: { hold: true, merge: false, tones: true, video: true },
    },
  };
  dispatcher.dispatch(action);
  dispatcher.dispatch(action);
  await flush();
  expect(invocations).toEqual(["end"]);
  expect(settlements).toEqual([["m35-end", 1, true]]);
  dispatcher.set(null);
  expect(attachments).toEqual([[true, 1], [false, 2]]);
});
