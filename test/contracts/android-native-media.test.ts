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

test("FCM ownership defaults to library and app ownership preserves other services", async () => {
  const { withAndroidOptions } = await import(
    "../../plugin/build/androidOptions.js"
  );
  const notifications =
    "expo.modules.notifications.service.ExpoFirebaseMessagingService";
  const library =
    "expo.modules.callkittelecom.services.ExpoCallKitTelecomMessagingService";
  const existing: AndroidManifest = {
    manifest: {
      $: { "xmlns:android": "http://schemas.android.com/apk/res/android" },
      application: [
        {
          $: { "android:name": ".MainApplication" },
          service: [
            {
              $: { "android:name": notifications, "android:exported": "false" },
              "intent-filter": [
                {
                  action: [
                    {
                      $: {
                        "android:name": "com.google.firebase.MESSAGING_EVENT",
                      },
                    },
                  ],
                },
              ],
            },
            {
              $: {
                "android:name": ".AppMessagingService",
                "android:exported": "false",
              },
            },
          ],
        },
      ],
    },
  };
  for (const owner of [undefined, "library", "app"] as const) {
    const config: ExportedConfig = withAndroidOptions(
      { name: "fcm-test", slug: "fcm-test" },
      owner ? { androidFcmService: owner } : {}
    );
    const result = await evaluate(
      config,
      config.mods?.android?.manifest,
      structuredClone(existing)
    );
    const services = result.manifest.application?.[0]?.service ?? [];
    assert.ok(
      services.some((item) => item.$["android:name"] === ".AppMessagingService")
    );
    if (owner === "app") {
      assert.deepEqual(services, existing.manifest.application?.[0]?.service);
    } else {
      assert.deepEqual(
        services.find((item) => item.$["android:name"] === notifications),
        { $: { "android:name": notifications, "tools:node": "remove" } }
      );
      assert.deepEqual(
        services.find((item) => item.$["android:name"] === library),
        {
          $: { "android:name": library, "android:exported": "false" },
          "intent-filter": [
            {
              action: [
                {
                  $: { "android:name": "com.google.firebase.MESSAGING_EVENT" },
                },
              ],
            },
          ],
        }
      );
    }
  }
  let result = structuredClone(existing);
  for (const androidFcmService of [
    "library",
    "library",
    "app",
    "app",
    "library",
    "app",
  ] as const) {
    const config: ExportedConfig = withAndroidOptions(
      { name: "fcm-test", slug: "fcm-test" },
      { androidFcmService }
    );
    result = await evaluate(config, config.mods?.android?.manifest, result);
    const services = result.manifest.application?.[0]?.service ?? [];
    assert.equal(
      services.filter((item) => item.$["android:name"] === library).length,
      androidFcmService === "library" ? 1 : 0
    );
    assert.equal(
      services.filter(
        (item) =>
          item.$["android:name"] === notifications &&
          item.$["tools:node"] === "remove"
      ).length,
      1
    );
  }
  const removed = structuredClone(existing);
  removed.manifest.application![0]!.service = [
    { $: { "android:name": notifications, "tools:node": "remove" } },
  ];
  const appConfig: ExportedConfig = withAndroidOptions(
    { name: "fcm-test", slug: "fcm-test" },
    { androidFcmService: "app" }
  );
  const preserved = await evaluate(
    appConfig,
    appConfig.mods?.android?.manifest,
    structuredClone(removed)
  );
  assert.deepEqual(
    preserved.manifest.application?.[0]?.service,
    removed.manifest.application?.[0]?.service
  );
});

test("unknown FCM owners fail before registering mods", async () => {
  const { withAndroidOptions } = await import(
    "../../plugin/build/androidOptions.js"
  );
  assert.throws(
    () =>
      Reflect.apply(withAndroidOptions, undefined, [
        { name: "fcm-test", slug: "fcm-test" },
        { androidFcmService: "other" },
      ]),
    /androidFcmService must be library or app/
  );
});
