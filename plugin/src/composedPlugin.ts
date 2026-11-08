import type { VoIPConversationsPluginProps } from "@relayphone/ios-voip-sdk/plugin";
import { type ConfigPlugin, createRunOncePlugin } from "expo/config-plugins.js";

import {
  type AndroidNativeMedia,
  withAndroidNativeMedia,
} from "./androidNativeMedia";
import pkg from "../../package.json";

export type ComposedPluginProps = VoIPConversationsPluginProps & {
  /** Android native provider, independent of iOS audio integration. Defaults to none. */
  androidNativeMedia?: AndroidNativeMedia;
  outgoingCallTimeout?: number;
  defaultRingtoneAndroid?: string;
};

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
      return withAndroidNativeMedia(composed, options);
    },
    pkg.name,
    pkg.version
  );
}
