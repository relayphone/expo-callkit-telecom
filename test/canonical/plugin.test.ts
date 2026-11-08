import type { AndroidManifest, ExportedConfig } from "expo/config-plugins";
import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { test } from "node:test";

const require = createRequire(import.meta.url);
const plugin: typeof import("../../plugin/src/withConversations").default =
  require("../../app.plugin.js").default;

test("the active entry composes SDK iOS mods and Android options without an extra wrapper", async () => {
  const config: ExportedConfig = plugin(
    { name: "Composed", slug: "composed" },
    {
      mediaAdapter: "none",
      androidNativeMedia: "livekit",
      incomingCallTimeout: 17,
    }
  );
  assert.ok(config.mods?.ios?.infoPlist);
  assert.ok(config.mods?.android?.manifest);
  const manifest: AndroidManifest = {
    manifest: {
      queries: [],
      $: { "xmlns:android": "http://schemas.android.com/apk/res/android" },
      application: [{ $: { "android:name": ".MainApplication" } }],
    },
  };
  const result = await config.mods.android.manifest({
    ...config,
    modResults: manifest,
    modRawConfig: config,
    modRequest: {
      projectRoot: "/tmp/composed",
      platformProjectRoot: "/tmp/composed/android",
      platform: "android",
      modName: "manifest",
      introspect: true,
    },
  });
  const metadata = result.modResults.manifest.application?.[0]?.["meta-data"];
  assert.equal(
    metadata?.find(
      (entry) =>
        entry.$["android:name"] === "ExpoCallKitTelecomAndroidNativeMedia"
    )?.$["android:value"],
    "livekit"
  );
  assert.equal(
    metadata?.find(
      (entry) => entry.$["android:name"] === "VoIPConversationsMediaAdapter"
    )?.$["android:value"],
    "none"
  );
  assert.equal(
    metadata?.some(
      (entry) => entry.$["android:name"] === "ExpoCallKitTelecomJoinPolicy"
    ),
    false
  );
});

test("the active plugin rejects the retired answer policy", () => {
  assert.throws(
    () =>
      Reflect.apply(plugin, undefined, [
        { name: "Composed", slug: "composed" },
        { joinPolicy: { type: "handler" } },
      ]),
    /joinPolicy/
  );
});
