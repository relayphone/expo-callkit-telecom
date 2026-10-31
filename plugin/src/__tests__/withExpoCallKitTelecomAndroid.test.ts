import type { ExpoConfig } from "expo/config";
import type { ModConfig } from "expo/config-plugins";

import type { ExpoCallKitTelecomPluginProps } from "../withExpoCallKitTelecom";
import {
  MEDIA_ADAPTER_GRADLE_PROPERTY,
  withExpoCallKitTelecomAndroid,
} from "../withExpoCallKitTelecomAndroid";

type PropertiesItem =
  | { type: "comment"; value: string }
  | { type: "empty" }
  | { type: "property"; key: string; value: string };

/**
 * Applies the Android plugin to a bare config and runs its gradle.properties mods against the
 * given entries, returning the resulting entries.
 */
async function evaluateGradleProperties(
  props: ExpoCallKitTelecomPluginProps,
  modResults: PropertiesItem[] = [],
): Promise<PropertiesItem[]> {
  const config: ExpoConfig & { mods?: ModConfig } =
    withExpoCallKitTelecomAndroid({ name: "test-app", slug: "test-app" }, props);
  const mod = config.mods?.android?.gradleProperties as unknown as
    | ((config: unknown) => Promise<{ modResults: PropertiesItem[] }>)
    | undefined;
  if (!mod) throw new Error("The plugin registered no gradleProperties mod.");
  const result = await mod({
    ...config,
    modResults,
    modRequest: {
      projectRoot: "/tmp/test-app",
      platformProjectRoot: "/tmp/test-app/android",
      modName: "gradleProperties",
      platform: "android",
      introspect: true,
    },
    modRawConfig: { name: "test-app", slug: "test-app" },
  });
  return result.modResults;
}

const mediaAdapterProperty = (items: PropertiesItem[]) =>
  items.filter(
    (item) => item.type === "property" && item.key === MEDIA_ADAPTER_GRADLE_PROPERTY,
  );

describe("withExpoCallKitTelecomAndroid native media engine", () => {
  it("leaves the engine out by default", async () => {
    expect(mediaAdapterProperty(await evaluateGradleProperties({}))).toEqual([
      { type: "property", key: MEDIA_ADAPTER_GRADLE_PROPERTY, value: "webrtc" },
    ]);
  });

  it("writes the media adapter that the module's build reads", async () => {
    for (const mediaAdapter of ["webrtc", "livekit", "none"] as const) {
      expect(
        mediaAdapterProperty(await evaluateGradleProperties({ mediaAdapter })),
      ).toEqual([
        { type: "property", key: MEDIA_ADAPTER_GRADLE_PROPERTY, value: mediaAdapter },
      ]);
    }
  });

  it("replaces the entry that an earlier prebuild wrote", async () => {
    const items = await evaluateGradleProperties({ mediaAdapter: "none" }, [
      { type: "property", key: "android.useAndroidX", value: "true" },
      { type: "property", key: MEDIA_ADAPTER_GRADLE_PROPERTY, value: "livekit" },
    ]);
    expect(items).toEqual([
      { type: "property", key: "android.useAndroidX", value: "true" },
      { type: "property", key: MEDIA_ADAPTER_GRADLE_PROPERTY, value: "none" },
    ]);
  });

  it("requires the LiveKit media adapter for a native join policy with LiveKit media", () => {
    const joinPolicy = {
      type: "native",
      media: "livekit",
      credentials: "fromPush",
    } as const;
    for (const mediaAdapter of [undefined, "webrtc", "none"] as const) {
      expect(() =>
        withExpoCallKitTelecomAndroid(
          { name: "test-app", slug: "test-app" },
          { joinPolicy, mediaAdapter },
        ),
      ).toThrow('needs mediaAdapter: "livekit"');
    }
    expect(() =>
      withExpoCallKitTelecomAndroid(
        { name: "test-app", slug: "test-app" },
        { joinPolicy, mediaAdapter: "livekit" },
      ),
    ).not.toThrow();
  });
});

describe("withExpoCallKitTelecomAndroid removed options", () => {
  it("rejects the options that v2 no longer reads", () => {
    for (const [option, value] of [
      ["defaultDialtone", "dialtone.wav"],
      ["androidEventReceiver", ".CallEndedReceiver"],
    ] as const) {
      expect(() =>
        withExpoCallKitTelecomAndroid(
          { name: "test-app", slug: "test-app" },
          { [option]: value } as ExpoCallKitTelecomPluginProps,
        ),
      ).toThrow(`The "${option}" option was removed`);
    }
  });

  it("drops the dialtone meta-data and call-event receiver that an earlier prebuild wrote", async () => {
    const config: ExpoConfig & { mods?: ModConfig } =
      withExpoCallKitTelecomAndroid({ name: "test-app", slug: "test-app" }, {});
    const mod = config.mods?.android?.manifest as unknown as (
      config: unknown,
    ) => Promise<{ modResults: { manifest: { application: Record<string, unknown>[] } } }>;
    const receiver = (name: string, action: string) => ({
      $: { "android:name": name, "android:exported": "false" },
      "intent-filter": [{ action: [{ $: { "android:name": action } }] }],
    });
    const result = await mod({
      ...config,
      modResults: {
        manifest: {
          $: { "xmlns:android": "http://schemas.android.com/apk/res/android" },
          application: [
            {
              $: { "android:name": ".MainApplication" },
              "meta-data": [
                {
                  $: {
                    "android:name": "ExpoCallKitTelecomDefaultDialtone",
                    "android:value": "dialtone",
                  },
                },
              ],
              receiver: [
                receiver(".CallEndedReceiver", "expo.modules.callkittelecom.ACTION_CALL_EVENT"),
                receiver(".OtherReceiver", "com.example.OTHER"),
              ],
            },
          ],
        },
      },
      modRequest: {
        projectRoot: "/tmp/test-app",
        platformProjectRoot: "/tmp/test-app/android",
        modName: "manifest",
        platform: "android",
        introspect: true,
      },
      modRawConfig: { name: "test-app", slug: "test-app" },
    });
    const app = result.modResults.manifest.application[0] as {
      "meta-data": { $: Record<string, string> }[];
      receiver: { $: Record<string, string> }[];
    };
    expect(app["meta-data"].map((item) => item.$["android:name"])).not.toContain(
      "ExpoCallKitTelecomDefaultDialtone",
    );
    expect(app.receiver.map((item) => item.$["android:name"])).toEqual([".OtherReceiver"]);
  });
});
