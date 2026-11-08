import type { ExpoConfig } from "expo/config";

const config: ExpoConfig = {
  name: "SDKComposition",
  slug: "sdk-composition",
  ios: { bundleIdentifier: "com.example.sdkcomposition" },
  plugins: [
    ["expo-build-properties", { ios: { deploymentTarget: "26.0" } }],
    [
      "expo-callkit-telecom/app.plugin",
      { mediaAdapter: "none", simulator: true },
    ],
  ],
};
export default config;
