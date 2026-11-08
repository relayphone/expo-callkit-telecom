import type { AndroidManifest, ExportedConfig, Mod } from "expo/config-plugins";
import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { test } from "node:test";

import type { ComposedPluginProps } from "../../plugin/src/composedPlugin.ts";

const require = createRequire(import.meta.url);
const {
  composeConversationsPlugin,
}: typeof import("../../plugin/src/composedPlugin") = require("../../plugin/build/composedPlugin.js");

async function evaluate<Data>(
  config: ExportedConfig,
  mod: Mod<Data> | undefined,
  data: Data,
  platform: "ios" | "android" = "ios"
) {
  assert.ok(mod, "The composed plugin registers the platform mod");
  return (
    await mod({
      ...config,
      modResults: data,
      modRequest: {
        projectRoot: "/tmp/composed-plugin",
        platformProjectRoot: `/tmp/composed-plugin/${platform}`,
        modName: "test",
        platform,
        introspect: true,
      },
      modRawConfig: { name: "Composed", slug: "composed" },
    })
  ).modResults;
}

test("composes the actual SDK iOS plugin and forwards canonical options to Android", async () => {
  const options: ComposedPluginProps = {
    mediaAdapter: "none",
    incomingCallTimeout: 17,
    fulfillAnswerCallTimeout: 23,
    androidNativeMedia: "livekit",
  };
  let forwarded: ComposedPluginProps | undefined;
  const composed = composeConversationsPlugin((config, props) => {
    forwarded = props;
    return config;
  });
  const config: ExportedConfig = composed(
    { name: "Composed", slug: "composed" },
    options
  );
  assert.equal(forwarded, options);
  const info = await evaluate(config, config.mods?.ios?.infoPlist, {});
  assert.equal(info.VoIPConversationsIncomingCallTimeout, 17);
  assert.equal(info.VoIPConversationsFulfillAnswerCallTimeout, 23);
});

test("native Android selection stays independent of the canonical audio adapter", async () => {
  const composed = composeConversationsPlugin((config) => config);
  for (const androidNativeMedia of [undefined, "none", "livekit"] as const) {
    const options: ComposedPluginProps = {
      mediaAdapter: "none",
      ...(androidNativeMedia ? { androidNativeMedia } : {}),
    };
    const config: ExportedConfig = composed(
      { name: "Composed", slug: "composed" },
      options
    );
    const properties = await evaluate(
      config,
      config.mods?.android?.gradleProperties,
      [],
      "android"
    );
    assert.deepEqual(properties, [
      {
        type: "property",
        key: "expo-callkit-telecom.androidNativeMedia",
        value: androidNativeMedia ?? "none",
      },
    ]);
    const manifest: AndroidManifest = {
      manifest: {
        $: { "xmlns:android": "http://schemas.android.com/apk/res/android" },
        queries: [],
        application: [{ $: { "android:name": ".MainApplication" } }],
      },
    };
    const result = await evaluate(
      config,
      config.mods?.android?.manifest,
      manifest,
      "android"
    );
    assert.deepEqual(result.manifest.application?.[0]?.["meta-data"], [
      {
        $: {
          "android:name": "ExpoCallKitTelecomAndroidNativeMedia",
          "android:value": androidNativeMedia ?? "none",
        },
      },
    ]);
  }
});

test("repeat composition preserves the SDK run-once wrapper and Android idempotence", async () => {
  let applications = 0;
  const composed = composeConversationsPlugin((config) => {
    applications++;
    return config;
  });
  const first: ExportedConfig = composed(
    { name: "Composed", slug: "composed" },
    {
      mediaAdapter: "none",
      incomingCallTimeout: 17,
      androidNativeMedia: "livekit",
    }
  );
  const infoMod = first.mods?.ios?.infoPlist;
  const second: ExportedConfig = composed(first, {
    mediaAdapter: "webrtc",
    incomingCallTimeout: 30,
    androidNativeMedia: "none",
  });
  assert.equal(second.mods?.ios?.infoPlist, infoMod);
  const info = await evaluate(second, second.mods?.ios?.infoPlist, {});
  assert.equal(info.VoIPConversationsIncomingCallTimeout, 17);
  assert.equal(applications, 1);
  const properties = await evaluate(
    second,
    second.mods?.android?.gradleProperties,
    [],
    "android"
  );
  assert.deepEqual(properties, [
    {
      type: "property",
      key: "expo-callkit-telecom.androidNativeMedia",
      value: "livekit",
    },
  ]);
});

test("a prior explicit SDK entry retains its iOS options while composition adds Android", async () => {
  const sdk: typeof import("@relayphone/ios-voip-sdk/plugin").default =
    require("@relayphone/ios-voip-sdk/app.plugin").default;
  const configured: ExportedConfig = sdk(
    { name: "Composed", slug: "composed" },
    {
      mediaAdapter: "none",
      incomingCallTimeout: 19,
    }
  );
  let applications = 0;
  const composed = composeConversationsPlugin((config) => {
    applications++;
    return config;
  });
  const result: ExportedConfig = composed(configured, {
    mediaAdapter: "webrtc",
    incomingCallTimeout: 30,
    androidNativeMedia: "livekit",
  });
  const info = await evaluate(result, result.mods?.ios?.infoPlist, {});
  assert.equal(info.VoIPConversationsIncomingCallTimeout, 19);
  assert.equal(applications, 1);
  const properties = await evaluate(
    result,
    result.mods?.android?.gradleProperties,
    [],
    "android"
  );
  assert.deepEqual(properties, [
    {
      type: "property",
      key: "expo-callkit-telecom.androidNativeMedia",
      value: "livekit",
    },
  ]);
});

test("the SDK validates canonical options rather than widening its media adapter", () => {
  const composed = composeConversationsPlugin((config) => config);
  assert.throws(
    () =>
      Reflect.apply(composed, undefined, [
        { name: "Composed", slug: "composed" },
        { mediaAdapter: "livekit" },
      ]),
    /mediaAdapter/
  );
});
