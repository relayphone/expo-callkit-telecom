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
