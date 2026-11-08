import type { AndroidManifest, ExportedConfig, Mod } from "expo/config-plugins";
import assert from "node:assert/strict";
import { test } from "node:test";

import {
  ANDROID_NATIVE_MEDIA_GRADLE_PROPERTY,
  ANDROID_NATIVE_MEDIA_METADATA,
  withAndroidNativeMedia,
} from "../../plugin/src/androidNativeMedia.ts";

type Properties = NonNullable<NonNullable<ExportedConfig["mods"]>["android"]>;
type GradleProperties = Parameters<
  NonNullable<Properties["gradleProperties"]>
>[0]["modResults"];

async function evaluate<Data>(
  config: ExportedConfig,
  mod: Mod<Data> | undefined,
  data: Data
) {
  assert.ok(mod, "The plugin registers the requested Android mod");
  return (
    await mod({
      ...config,
      modResults: data,
      modRequest: {
        projectRoot: "/tmp/android-native-media",
        platformProjectRoot: "/tmp/android-native-media/android",
        modName: "test",
        platform: "android",
        introspect: true,
      },
      modRawConfig: { name: "media-test", slug: "media-test" },
    })
  ).modResults;
}

test("native provider selection defaults to none and replaces only its own Gradle entry", async () => {
  for (const androidNativeMedia of [undefined, "none", "livekit"] as const) {
    const config: ExportedConfig = withAndroidNativeMedia(
      { name: "media-test", slug: "media-test" },
      androidNativeMedia ? { androidNativeMedia } : {}
    );
    const existing: GradleProperties = [
      { type: "property", key: "android.useAndroidX", value: "true" },
      {
        type: "property",
        key: "expo-callkit-telecom.mediaAdapter",
        value: "webrtc",
      },
      {
        type: "property",
        key: ANDROID_NATIVE_MEDIA_GRADLE_PROPERTY,
        value: "livekit",
      },
    ];
    const result = await evaluate(
      config,
      config.mods?.android?.gradleProperties,
      existing
    );
    assert.deepEqual(result, [
      ...existing.slice(0, 2),
      {
        type: "property",
        key: ANDROID_NATIVE_MEDIA_GRADLE_PROPERTY,
        value: androidNativeMedia ?? "none",
      },
    ]);
  }
});

test("the native selection metadata matches the source-set selection after repeated prebuild", async () => {
  const existing: AndroidManifest = {
    manifest: {
      $: { "xmlns:android": "http://schemas.android.com/apk/res/android" },
      queries: [],
      application: [
        {
          $: { "android:name": ".MainApplication" },
          "meta-data": [
            { $: { "android:name": "unrelated", "android:value": "retained" } },
            {
              $: {
                "android:name": ANDROID_NATIVE_MEDIA_METADATA,
                "android:value": "livekit",
              },
            },
          ],
        },
      ],
    },
  };
  for (const androidNativeMedia of ["none", "livekit", "none"] as const) {
    const config: ExportedConfig = withAndroidNativeMedia(
      { name: "media-test", slug: "media-test" },
      { androidNativeMedia }
    );
    await evaluate(config, config.mods?.android?.manifest, existing);
    assert.deepEqual(existing.manifest.application?.[0]?.["meta-data"], [
      { $: { "android:name": "unrelated", "android:value": "retained" } },
      {
        $: {
          "android:name": ANDROID_NATIVE_MEDIA_METADATA,
          "android:value": androidNativeMedia,
        },
      },
    ]);
  }
});

test("unknown native providers fail configuration before mods are installed", () => {
  assert.throws(
    () =>
      Reflect.apply(withAndroidNativeMedia, undefined, [
        { name: "media-test", slug: "media-test" },
        { androidNativeMedia: "webrtc" },
      ]),
    /androidNativeMedia must be livekit or none/
  );
});
