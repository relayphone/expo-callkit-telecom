import { describe, expect, test } from "bun:test";
import { RevisionStore } from "../../src/conversations/runtime";

describe("native snapshot ordering", () => {
  test("stale hydration cannot replace a newer event or mutate the cache", () => {
    const store = new RevisionStore<readonly { id: string }[]>([]);
    store.accept(2, [{ id: "new" }]);
    const value = store.read();
    store.accept(1, [{ id: "old" }]);
    expect(store.read()).toBe(value);
    expect(Object.isFrozen(store.read()[0])).toBe(true);
    expect(() => store.accept(-1, [])).toThrow();
  });
  test("valid state recovers failed hydration", () => {
    const store = new RevisionStore<readonly string[]>([]);
    store.fail(new Error("read failed"));
    expect(() => store.read()).toThrow();
    store.accept(0, []);
    expect(store.read()).toEqual([]);
  });
  test("unchanged frozen values retain their identity across revisions", () => {
    const store = new RevisionStore<readonly { id: string }[]>([]);
    store.accept(1, [{ id: "stable" }]);
    const first = store.read()[0];
    store.accept(2, [first!]);
    expect(store.read()[0]).toBe(first);
  });
});

import { HandlerDispatcher } from "../../src/conversations/runtime";
import type {
  HandlerAction,
  NativeTransport,
} from "../../src/conversations/transport";
import type {
  ConversationHandler,
  ConversationId,
} from "../../src/conversations/types";

function harness() {
  const attachments: [boolean, number][] = [];
  const settlements: [string, number, boolean][] = [];
  const bridge: Pick<NativeTransport, "setHandlerAttached" | "settleAction"> = {
    setHandlerAttached(attached, generation) {
      attachments.push([attached, generation]);
    },
    settleAction(id, generation, success) {
      settlements.push([id, generation, success]);
    },
  };
  return {
    dispatcher: new HandlerDispatcher(bridge),
    attachments,
    settlements,
  };
}
function action(generation = 1): HandlerAction {
  return {
    actionID: "action",
    generation,
    kind: "join",
    origin: "system",
    deadline: new Date(Date.now() + 30_000).toISOString(),
    conversation: {
      // Literal represents a native UUID at the bridge fixture boundary.
      id: "e9799cf2-daba-51df-8bdf-d59b88db0bca" as ConversationId,
      direction: "incoming",
      status: "ringing",
      members: [],
      isMuted: false,
      isVideo: false,
      capabilities: { hold: true, merge: false, tones: true, video: true },
    },
  };
}
const signaling: ConversationHandler = { start() {}, end() {}, join() {} };
const flush = () => new Promise<void>((resolve) => setTimeout(resolve, 0));

describe("handler generation and cancellation", () => {
  test("resolves once; duplicate delivery cannot repeat application work", async () => {
    const h = harness();
    let joined = 0;
    h.dispatcher.set({
      ...signaling,
      join() {
        joined++;
      },
    });
    h.dispatcher.dispatch(action());
    h.dispatcher.dispatch(action());
    await flush();
    expect(joined).toBe(1);
    expect(h.settlements).toEqual([["action", 1, true]]);
  });
  test("cancellation before invocation skips application work", async () => {
    const h = harness();
    let joined = false;
    h.dispatcher.set({
      ...signaling,
      join() {
        joined = true;
      },
    });
    h.dispatcher.dispatch(action());
    h.dispatcher.cancel("action", 1, "deadline");
    await flush();
    expect(joined).toBe(false);
    expect(h.settlements).toEqual([]);
  });
  test("replacement aborts old work and ignores its late resolution", async () => {
    const h = harness();
    let finish: (() => void) | undefined;
    let signal: AbortSignal | undefined;
    h.dispatcher.set({
      ...signaling,
      join(_, context) {
        signal = context.signal;
        return new Promise<void>((resolve) => {
          finish = resolve;
        });
      },
    });
    h.dispatcher.dispatch(action());
    await flush();
    h.dispatcher.set(signaling);
    expect(signal?.aborted).toBe(true);
    finish?.();
    h.dispatcher.dispatch(action(2));
    await flush();
    expect(h.settlements).toEqual([["action", 2, true]]);
  });
  test("a reentrant abort listener owns the final replacement", async () => {
    const h = harness();
    h.dispatcher.set({
      ...signaling,
      join(_, { signal }) {
        signal.addEventListener("abort", () => h.dispatcher.set(signaling));
        return new Promise<void>(() => {});
      },
    });
    h.dispatcher.dispatch(action());
    await flush();
    h.dispatcher.set(null);
    expect(h.attachments).toEqual([
      [true, 1],
      [true, 3],
    ]);
  });
  test("missing required join fails; expired work never invokes it", async () => {
    const h = harness();
    h.dispatcher.set({ start() {}, end() {} });
    h.dispatcher.dispatch(action());
    await flush();
    expect(h.settlements).toEqual([["action", 1, false]]);
    h.dispatcher.dispatch({
      ...action(),
      actionID: "expired",
      deadline: new Date(0).toISOString(),
    });
    await flush();
    expect(h.settlements.length).toBe(1);
  });
});
