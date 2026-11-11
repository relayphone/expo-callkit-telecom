import assert from "node:assert/strict";
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { test } from "node:test";
import { pathToFileURL } from "node:url";

import {
  assertSdkImportFailure,
  inspectPluginProject,
} from "./plugin-loading.ts";

function fixture(
  t: { after(cleanup: () => void): void },
  major: 57 | 58,
  fault: "none" | "android" | "sdk-required" | "wrong-origin" = "none"
) {
  const root = mkdtempSync(join(tmpdir(), "plugin-loading-test-"));
  t.after(() => rmSync(root, { recursive: true, force: true }));
  writeFileSync(join(root, "package.json"), "{}");
  function install(name: string, files: Record<string, string>) {
    const directory = join(root, "node_modules", name);
    mkdirSync(directory, { recursive: true });
    writeFileSync(
      join(directory, "package.json"),
      JSON.stringify({ name, version: `${major}.0.0` })
    );
    for (const [file, code] of Object.entries(files))
      writeFileSync(join(directory, file), code);
    return directory;
  }
  install("expo", {});
  const sdk = install("@relayphone/ios-voip-sdk", {
    "app.plugin.js":
      major === 58
        ? 'require("expo/config-plugins.js");'
        : "module.exports = config => { config.mods ??= {}; config.mods.ios = { infoPlist: async value => value }; };",
  });
  install("expo-callkit-telecom", {
    "app.plugin.js": `module.exports = (config, options) => {
      if (options.ios !== false) {
        ${
          fault === "wrong-origin"
            ? 'require("expo/config-plugins.js");'
            : 'require("@relayphone/ios-voip-sdk/app.plugin")(config);'
        }
      } else {
        ${
          fault === "sdk-required"
            ? 'require("@relayphone/ios-voip-sdk/package.json");'
            : ""
        }
      }
      const metadata = {
        ExpoCallKitTelecomAndroidNativeMedia: ${JSON.stringify(
          fault === "android" ? "none" : "livekit"
        )},
        VoIPConversationsMediaAdapter: "none", ExpoCallKitTelecomSupportsHold: "false",
        ExpoCallKitTelecomIncomingCallTimeout: "17"
      };
      const mod = data => async value => ({...value, modResults: data});
      config.mods ??= {};
      config.mods.android = {
        manifest: mod({ manifest: { application: [{ $: {},
          "meta-data": Object.entries(metadata).map(([name, value]) => ({ $: { "android:name": name, "android:value": value }})),
          service: [{ $: { "android:name": "expo.modules.callkittelecom.services.ExpoCallKitTelecomMessagingService" }}]
        }]}}),
        gradleProperties: mod([{type: "property", key: "expo-callkit-telecom.androidNativeMedia", value: "livekit"}]),
        dangerous: mod({})
      };
      return config;
    };`,
  });
  return { root, sdk };
}

test("the loading oracle checks both modes on Expo 57", async (t) => {
  const { root } = fixture(t, 57);
  assert.deepEqual(await inspectPluginProject(root, 57), {
    expo: "57.0.0",
    androidOnly: "passed",
    iosDefault: "passed",
  });
});

test("Expo 58 only accepts the default SDK's own missing-import failure", async (t) => {
  const { root } = fixture(t, 58);
  assert.deepEqual(await inspectPluginProject(root, 58), {
    expo: "58.0.0",
    androidOnly: "passed",
    iosDefault: "expected-sdk-failure",
  });
});

test("a missing import in v2 cannot be classified as the SDK's expected failure", async (t) => {
  const { root } = fixture(t, 58, "wrong-origin");
  await assert.rejects(
    inspectPluginProject(root, 58),
    /must originate in the SDK/
  );
});

test("requiring the SDK in Android-only mode fails the loading oracle", async (t) => {
  const { root } = fixture(t, 58, "sdk-required");
  await assert.rejects(
    inspectPluginProject(root, 58),
    /must not require any SDK/
  );
});

test("incorrect Android mod results fail even when the plugin loads", async (t) => {
  const { root } = fixture(t, 57, "android");
  await assert.rejects(inspectPluginProject(root, 57), /livekit/);
});

test("other SDK errors cannot hide behind the expected Expo 58 failure", () => {
  const sdk = join(tmpdir(), "sdk");
  const error = Object.assign(new Error("Missing module"), {
    code: "MODULE_NOT_FOUND",
  });
  const observed = {
    error,
    parentURL: pathToFileURL(join(sdk, "app.plugin.js")).href,
  };
  assertSdkImportFailure(error, sdk, observed);
  assert.throws(() =>
    assertSdkImportFailure(
      Object.assign(new Error(), { code: "OTHER" }),
      sdk,
      observed
    )
  );
  assert.throws(() => assertSdkImportFailure(error, sdk, undefined));
  assert.throws(() =>
    assertSdkImportFailure(new Error("Other failure"), sdk, observed)
  );
  assert.throws(() =>
    assertSdkImportFailure(error, `${sdk}-neighbor`, observed)
  );
});
