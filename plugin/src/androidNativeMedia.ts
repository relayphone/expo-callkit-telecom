import {
  AndroidConfig,
  type ConfigPlugin,
  withAndroidManifest,
  withGradleProperties,
} from "expo/config-plugins.js";

/** Android's native provider selection is independent of the public audio adapter. */
export type AndroidNativeMedia = "livekit" | "none";

export const ANDROID_NATIVE_MEDIA_GRADLE_PROPERTY =
  "expo-callkit-telecom.androidNativeMedia";
export const ANDROID_NATIVE_MEDIA_METADATA =
  "ExpoCallKitTelecomAndroidNativeMedia";

/** Writes matching build and runtime selections; does not select an answer policy. */
export const withAndroidNativeMedia: ConfigPlugin<{
  androidNativeMedia?: AndroidNativeMedia;
}> = (config, { androidNativeMedia = "none" }) => {
  if (androidNativeMedia !== "none" && androidNativeMedia !== "livekit") {
    throw new Error("androidNativeMedia must be livekit or none.");
  }
  config = withGradleProperties(config, (mod) => {
    mod.modResults = mod.modResults.filter(
      (item) =>
        !(
          item.type === "property" &&
          item.key === ANDROID_NATIVE_MEDIA_GRADLE_PROPERTY
        )
    );
    mod.modResults.push({
      type: "property",
      key: ANDROID_NATIVE_MEDIA_GRADLE_PROPERTY,
      value: androidNativeMedia,
    });
    return mod;
  });
  return withAndroidManifest(config, (mod) => {
    const app = AndroidConfig.Manifest.getMainApplicationOrThrow(
      mod.modResults
    );
    app["meta-data"] = (app["meta-data"] ?? []).filter(
      (entry) => entry.$["android:name"] !== ANDROID_NATIVE_MEDIA_METADATA
    );
    app["meta-data"].push({
      $: {
        "android:name": ANDROID_NATIVE_MEDIA_METADATA,
        "android:value": androidNativeMedia,
      },
    });
    return mod;
  });
};
