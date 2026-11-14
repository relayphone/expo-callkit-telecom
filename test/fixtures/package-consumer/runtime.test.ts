import type { ConfigPlugin, ExportedConfig } from "expo/config-plugins";
import assert from "node:assert/strict";
import { mkdtempSync, mkdirSync, rmSync, writeFileSync } from "node:fs";
import { createRequire, registerHooks } from "node:module";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { test } from "node:test";

const listeners = new Map<string, (event: unknown) => void>();
const selected: string[] = [];
const native = {
  snapshot: { revision: 0, conversations: [] },
  mediaAdapter: "none",
  async getConversations() {
    return this.snapshot;
  },
  async getAudioRoutes() {
    return {
      revision: 1,
      routes: [
        {
          id: "route/raw 電話",
          name: "Headset",
          type: process.env.CONVERSATIONS_TEST_LEGACY_ROUTE
            ? "bluetoothHFP"
            : "bluetooth",
        },
      ],
      current: null,
    };
  },
  async getPushToken() {
    return { revision: 0, token: null };
  },
  addListener(name: string, callback: (event: unknown) => void) {
    listeners.set(name, callback);
    return {
      remove() {
        listeners.delete(name);
      },
    };
  },
  setHandlerAttached() {},
  settleAction() {},
  async startConversation(options: { id: string }) {
    return options.id;
  },
  async joinConversation() {
    if (process.env.CONVERSATIONS_TEST_UNTYPED_ERROR)
      throw new Error("Unknown call");
    throw new Error(
      'Native rejection: expo-conversation:{"code":"unknown_conversation","message":"Unknown call","nativeDomain":"androidx.core.telecom","nativeCode":4,"extra":"discarded"}'
    );
  },
  async endConversation() {},
  async setHeld() {
    throw new Error('Native rejection: expo-conversation:{"code":"refused","message":"keepActive"}');
  },
  async setMuted() {},
  async playTones() {},
  async mergeConversations() {},
  async selectAudioRoute(id: string) {
    selected.push(id);
  },
  async reportConversation() {},
  async receiveCallEnded() {
    return false;
  },
  async reportIncomingConversation() {
    return { status: "reported", id: "incoming/raw" };
  },
};
Object.assign(globalThis, { compositionTestTransport: native });
const hooks = registerHooks({
  resolve(specifier, context, next) {
    if (specifier === "expo" || specifier === "react") {
      const source =
        specifier === "expo"
          ? 'export function requireNativeModule(name) { if (name !== "VoIPConversations") throw Error(name); return globalThis.compositionTestTransport; }'
          : "export function useEffect() {} export function useRef(current) { return { current }; } export function useSyncExternalStore(_subscribe, read) { return read(); }";
      return {
        shortCircuit: true,
        url: "data:text/javascript," + encodeURIComponent(source),
      };
    }
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

test("installed packages share the SDK runtime, canonical errors and actual route updates", async () => {
  try {
    const fork = await import("expo-callkit-telecom");
    const sdk = await import("@relayphone/ios-voip-sdk");
    assert.equal(fork.startConversation, sdk.startConversation);
    assert.equal(fork.setConversationHandler, sdk.setConversationHandler);
    const id = await fork.startConversation({
      id: "server/電話 42",
      to: { id: "peer" },
      video: false,
    });
    assert.equal(id, "server/電話 42");
    await assert.rejects(fork.joinConversation(id), (error: unknown) => {
      assert.ok(error instanceof Error);
      assert.equal(error.message, "Unknown call");
      assert.deepEqual(
        { ...error },
        {
          code: "unknown_conversation",
          nativeDomain: "androidx.core.telecom",
          nativeCode: 4,
        }
      );
      return true;
    });
    await assert.rejects(fork.setHeld(id, true), (error: unknown) => {
      assert.ok(error instanceof Error);
      assert.equal(error.message, "keepActive");
      assert.deepEqual({...error}, {code: "refused"});
      return true;
    });
    const routes = fork.useAudioRoutes();
    assert.deepEqual(routes.routes, [
      { id: "route/raw 電話", name: "Headset", type: "bluetooth" },
    ]);
    assert.equal(routes.current, null);
    await fork.selectAudioRoute("route/raw 電話");
    assert.deepEqual(selected, ["route/raw 電話"]);
    assert.equal(fork.useAudioRoutes().current, null);
    const changed = listeners.get("audioRoutesChanged");
    assert.ok(changed);
    changed({
      revision: 2,
      routes: [{ id: "system/42", name: "System output", type: "other" }],
      current: { id: "system/42", name: "System output", type: "other" },
    });
    assert.deepEqual(fork.useAudioRoutes().current, {
      id: "system/42",
      name: "System output",
      type: "other",
    });
    assert.equal(await fork.getCallPreviewActivities(), null);
    assert.equal(await fork.getFocusFilterState(), null);
  } finally {
    hooks.deregister();
  }
});

test("the installed plugin diagnoses stale native projects and accepts clean regeneration", async (t) => {
  const root = mkdtempSync(join(tmpdir(), "installed-composition-"));
  t.after(() => rmSync(root, { recursive: true, force: true }));
  mkdirSync(join(root, "ios"));
  const podfile = join(root, "ios/Podfile");
  writeFileSync(
    podfile,
    "use_expo_modules!\n# expo-callkit-telecom: ios-voip-sdk source\n"
  );
  const require = createRequire(import.meta.url);
  const plugin: ConfigPlugin<{ mediaAdapter: "none" }> =
    require("expo-callkit-telecom/app.plugin").default;
  const config: ExportedConfig = plugin(
    { name: "Installed", slug: "installed" },
    { mediaAdapter: "none" }
  );
  const mod = config.mods?.ios?.dangerous;
  assert.ok(mod);
  const run = async () =>
    mod({
      ...config,
      modResults: {},
      modRawConfig: config,
      modRequest: {
        projectRoot: root,
        platformProjectRoot: join(root, "ios"),
        platform: "ios",
        modName: "dangerous",
        introspect: false,
      },
    });
  await assert.rejects(
    run(),
    /retired.*ExpoCallKitTelecom[\s\S]*expo prebuild --clean/
  );
  rmSync(podfile);
  await run();
});
