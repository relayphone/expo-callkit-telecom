import type { StartConversationOptions } from "@relayphone/ios-voip-sdk/types";
import assert from "node:assert/strict";
import { registerHooks } from "node:module";
import { test } from "node:test";

const starts: unknown[] = [];
const transport = {
  snapshot: { revision: 0, conversations: [] },
  mediaAdapter: "none",
  async getConversations() {
    return this.snapshot;
  },
  async getAudioRoutes() {
    return { revision: 0, routes: [], current: null };
  },
  async getPushToken() {
    return { revision: 0, token: null };
  },
  addListener() {
    return { remove() {} };
  },
  setHandlerAttached() {},
  settleAction() {},
  async startConversation(options: StartConversationOptions) {
    starts.push(options);
    return options.id;
  },
  async joinConversation() {},
  async endConversation() {},
  async setHeld() {},
  async setMuted() {},
  async playTones() {},
  async mergeConversations() {},
  async selectAudioRoute() {},
  async reportConversation() {},
  async receiveCallEnded() {
    return false;
  },
  async reportIncomingConversation() {
    return { status: "reported", id: "incoming/raw" };
  },
};
Object.assign(globalThis, { canonicalTestTransport: transport });
const hooks = registerHooks({
  resolve(specifier, context, next) {
    if (specifier === "expo")
      return {
        shortCircuit: true,
        url:
          "data:text/javascript," +
          encodeURIComponent(
            'export function requireNativeModule(name) { if (name !== "VoIPConversations") throw Error(name); return globalThis.canonicalTestTransport; }'
          ),
      };
    try {
      return next(specifier, context);
    } catch (error) {
      if (
        specifier.startsWith(".") &&
        error instanceof Error &&
        "code" in error &&
        error.code === "ERR_MODULE_NOT_FOUND"
      )
        return next(specifier + ".js", context);
      throw error;
    }
  },
});

test("the active root shares the SDK runtime and preserves application IDs", async () => {
  try {
    const composed = await import("../../build/index.js");
    const sdk = await import("@relayphone/ios-voip-sdk");
    assert.equal(composed.startConversation, sdk.startConversation);
    assert.equal(composed.setConversationHandler, sdk.setConversationHandler);
    assert.equal(
      await composed.startConversation({
        id: "server/電話 42",
        to: { id: "peer" },
        video: false,
      }),
      "server/電話 42"
    );
    assert.deepEqual(starts, [
      { id: "server/電話 42", to: { id: "peer" }, video: false },
    ]);
  } finally {
    hooks.deregister();
  }
});
