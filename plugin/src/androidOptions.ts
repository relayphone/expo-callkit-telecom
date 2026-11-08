import {
  AndroidConfig,
  type ConfigPlugin,
  withAndroidManifest,
  withDangerousMod,
} from "expo/config-plugins.js";
import { mkdirSync, copyFileSync } from "node:fs";
import { basename, resolve } from "node:path";

import type { ComposedPluginProps } from "./composedPlugin";

/** Android options alongside the canonical SDK iOS plugin. */
export const withAndroidOptions: ConfigPlugin<ComposedPluginProps> = (
  config,
  props
) => {
  if ("joinPolicy" in props)
    throw new Error(
      "joinPolicy was removed; use androidNativeMedia for native media selection."
    );
  if ("defaultDialtone" in props || "androidEventReceiver" in props)
    throw new Error(
      "defaultDialtone and androidEventReceiver are unsupported."
    );
  config = withAndroidManifest(config, (mod) => {
    const manifest = mod.modResults.manifest;
    manifest.$["xmlns:tools"] = "http://schemas.android.com/tools";
    const app = AndroidConfig.Manifest.getMainApplicationOrThrow(
      mod.modResults
    );
    const values: Record<string, string> = {
      VoIPConversationsMediaAdapter: props.mediaAdapter ?? "webrtc",
      ExpoCallKitTelecomUnhandledAction: props.unhandledAction ?? "fail",
      ExpoCallKitTelecomIncomingCallTimeout: String(
        props.incomingCallTimeout ?? 45
      ),
      ExpoCallKitTelecomOutgoingCallTimeout: String(
        props.outgoingCallTimeout ?? 60
      ),
      ExpoCallKitTelecomFulfillAnswerCallTimeout: String(
        props.fulfillAnswerCallTimeout ?? 30
      ),
    };
    const ringtone = props.defaultRingtoneAndroid;
    if (ringtone && ringtone !== "default") {
      if (!props.sounds?.some((sound) => basename(sound) === ringtone))
        throw new Error("defaultRingtoneAndroid must name a provided sound.");
      values.ExpoCallKitTelecomDefaultRingtone = resourceName(ringtone);
    }
    const retired = [
      "ExpoCallKitTelecomJoinPolicy",
      "ExpoCallKitTelecomDefaultDialtone",
      "ExpoCallKitTelecomDefaultRingtone",
    ];
    app["meta-data"] = (app["meta-data"] ?? []).filter(
      (item) =>
        !(item.$["android:name"] in values) &&
        !retired.includes(item.$["android:name"])
    );
    for (const [name, value] of Object.entries(values))
      app["meta-data"].push({
        $: { "android:name": name, "android:value": value },
      });
    const service =
      "expo.modules.notifications.service.ExpoFirebaseMessagingService";
    app.service = (app.service ?? []).filter(
      (item) => item.$["android:name"] !== service
    );
    app.service.push({
      $: { "android:name": service, "tools:node": "remove" },
    });
    app.receiver = (app.receiver ?? []).filter(
      (item) =>
        !item["intent-filter"]?.some((filter) =>
          filter.action?.some(
            (action) =>
              action.$["android:name"] ===
              "expo.modules.callkittelecom.ACTION_CALL_EVENT"
          )
        )
    );
    return mod;
  });
  return withDangerousMod(config, [
    "android",
    (mod) => {
      const directory = resolve(
        mod.modRequest.projectRoot,
        "android/app/src/main/res/raw"
      );
      for (const sound of props.sounds ?? []) {
        mkdirSync(directory, { recursive: true });
        const name = basename(sound);
        const extension = name.includes(".")
          ? name.slice(name.lastIndexOf("."))
          : "";
        copyFileSync(
          resolve(mod.modRequest.projectRoot, sound),
          resolve(directory, resourceName(name) + extension)
        );
      }
      return mod;
    },
  ]);
};

function resourceName(name: string): string {
  const value = name
    .replace(/\.[^.]+$/, "")
    .toLowerCase()
    .replace(/[^a-z0-9_]/g, "_");
  return /^\d/.test(value) ? `_${value}` : value;
}
