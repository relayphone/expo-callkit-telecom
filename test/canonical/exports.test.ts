import type { StartConversationOptions, MediaKind, MediaPermissions, ConversationMediaReadiness } from "@relayphone/ios-voip-sdk/types";
import assert from "node:assert/strict";
import { registerHooks } from "node:module";
import { after, test } from "node:test";

const starts: unknown[] = [];
const permissionCalls: (["getMediaPermissions"] | ["requestMediaPermissions", readonly MediaKind[]] |
  ["conversationMediaReadiness", string])[] = [];
const permissions: MediaPermissions = {
  microphone: { status: "granted", granted: true, canAskAgain: true, expires: "never" },
  camera: { status: "undetermined", granted: false, canAskAgain: true, expires: "never" },
};
const readiness: ConversationMediaReadiness = { required: ["microphone", "camera"], missing: ["camera"] };
const transport = {
  snapshot: { revision: 0, conversations: [] },
  mediaAdapter: "none",
  async getMediaPermissions() { permissionCalls.push(["getMediaPermissions"]); return permissions; },
  async requestMediaPermissions(kinds: readonly MediaKind[]) {
    permissionCalls.push(["requestMediaPermissions", kinds]); return permissions;
  },
  async conversationMediaReadiness(id: string) {
    permissionCalls.push(["conversationMediaReadiness", id]); return readiness;
  },
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
  async setConversationVideo() {},
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
after(() => hooks.deregister());

test("the active root shares the SDK runtime and preserves application IDs", async () => {
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
});

test("media permission exports delegate to the Android VoIPConversations native functions", async () => {
  const composed = await import("../../build/index.js");
  const sdk = await import("@relayphone/ios-voip-sdk");
  for (const name of ["getMediaPermissions", "requestMediaPermissions", "conversationMediaReadiness"] as const) {
    assert.equal(typeof composed[name], "function");
    assert.equal(composed[name], sdk[name]);
  }
  assert.deepEqual(await composed.getMediaPermissions(), permissions);
  assert.deepEqual(await composed.requestMediaPermissions(["microphone", "camera"]), permissions);
  assert.deepEqual(await composed.conversationMediaReadiness("android/video-call"), readiness);
  assert.deepEqual(permissionCalls, [["getMediaPermissions"], ["requestMediaPermissions", ["microphone", "camera"]],
    ["conversationMediaReadiness", "android/video-call"]]);
});
