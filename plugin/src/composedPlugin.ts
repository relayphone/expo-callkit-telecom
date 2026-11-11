import type { VoIPConversationsPluginProps } from "@relayphone/ios-voip-sdk/plugin";
import {
  type ConfigPlugin,
  createRunOncePlugin,
  withDangerousMod,
} from "expo/config-plugins.js";
import { existsSync, readFileSync } from "node:fs";
import { join } from "node:path";

import {
  type AndroidNativeMedia,
  withAndroidNativeMedia,
} from "./androidNativeMedia";
import pkg from "../../package.json";

export type ComposedPluginProps = VoIPConversationsPluginProps & {
  /** Android native provider, independent of iOS audio integration. Defaults to none. */
  androidNativeMedia?: AndroidNativeMedia;
  /** FCM service owner. App services forward call messages to CallPushIntake. Defaults to library. */
  androidFcmService?: "library" | "app";
  /** Advertise hold/resume to Core-Telecom. Defaults to true. */
  androidSupportsHold?: boolean;
  outgoingCallTimeout?: number;
  defaultRingtoneAndroid?: string;
};

function hasRetiredAppleIntegration(directory: string): boolean {
  const read = (name: string) => {
    const file = join(directory, name);
    return existsSync(file) ? readFileSync(file, "utf8") : "";
  };
  const properties: unknown = JSON.parse(
    read("Podfile.properties.json") || "{}"
  );
  return (
    read("Podfile").includes("# expo-callkit-telecom: ios-voip-sdk source") ||
    /^\s+- ExpoCallKitTelecom(?:\/|\s+\()/m.test(read("Podfile.lock")) ||
    (properties !== null &&
      typeof properties === "object" &&
      Object.keys(properties).some((key) =>
        key.startsWith("expo-callkit-telecom.")
      ))
  );
}

/** Composes the supported SDK plugin with Android's platform options and native selection. */
export function composeConversationsPlugin(
  android: ConfigPlugin<ComposedPluginProps>
): ConfigPlugin<ComposedPluginProps | void> {
  return createRunOncePlugin(
    (config, props) => {
      const options = props ?? {};
      const entry: unknown = require("@relayphone/ios-voip-sdk/app.plugin");
      const ios =
        typeof entry === "function"
          ? entry
          : entry && typeof entry === "object" && "default" in entry
          ? entry.default
          : undefined;
      if (typeof ios !== "function")
        throw new Error("The SDK config plugin is unavailable.");
      const configured: unknown = ios(config, options);
      if (
        !configured ||
        typeof configured !== "object" ||
        !("name" in configured) ||
        typeof configured.name !== "string" ||
        !("slug" in configured) ||
        typeof configured.slug !== "string"
      )
        throw new Error(
          "The SDK config plugin returned an invalid project configuration."
        );
      const composed = android(
        { ...configured, name: configured.name, slug: configured.slug },
        options
      );
      const checked = withDangerousMod(composed, [
        "ios",
        (mod) => {
          if (hasRetiredAppleIntegration(mod.modRequest.platformProjectRoot))
            throw new Error(
              "This project still contains the retired ExpoCallKitTelecom iOS integration. " +
                "Save native customizations and run npx expo prebuild --clean to regenerate with the SDK provider."
            );
          return mod;
        },
      ]);
      return withAndroidNativeMedia(checked, options);
    },
    pkg.name,
    pkg.version
  );
}
