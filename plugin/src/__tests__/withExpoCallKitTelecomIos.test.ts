import type { ExpoConfig } from "expo/config";
import type { ModConfig } from "expo/config-plugins";

import type { ExpoCallKitTelecomPluginProps } from "../withExpoCallKitTelecom";
import {
  addSDKSource,
  withExpoCallKitTelecomIos,
} from "../withExpoCallKitTelecomIos";

/**
 * Applies the iOS plugin to a bare config and executes one registered mod
 * chain against the given results (empty by default), returning the resulting
 * entries. This exercises every mod of that kind the plugin composes, without
 * running a full prebuild.
 */
async function evaluateMod(
  props: ExpoCallKitTelecomPluginProps,
  modName: "infoPlist" | "podfileProperties",
  modResults: Record<string, unknown> = {},
): Promise<Record<string, unknown>> {
  const config: ExpoConfig & { mods?: ModConfig } = withExpoCallKitTelecomIos(
    { name: "test-app", slug: "test-app" },
    props,
  );

  const mod = config.mods?.ios?.[modName] as unknown as
    | ((config: unknown) => Promise<{ modResults: Record<string, unknown> }>)
    | undefined;
  if (!mod) throw new Error(`The plugin registered no ${modName} mod.`);

  const result = await mod({
    ...config,
    modResults,
    modRequest: {
      projectRoot: "/tmp/test-app",
      platformProjectRoot: "/tmp/test-app/ios",
      modName,
      platform: "ios",
      introspect: true,
    },
    modRawConfig: { name: "test-app", slug: "test-app" },
  });
  return result.modResults;
}

const evaluateInfoPlist = (props: ExpoCallKitTelecomPluginProps) =>
  evaluateMod(props, "infoPlist");

describe("withExpoCallKitTelecomIos Info.plist configuration", () => {
  it("defaults ExpoCallKitTelecomIncludesCallsInRecents to true when the prop is omitted", async () => {
    const plist = await evaluateInfoPlist({});
    expect(plist.ExpoCallKitTelecomIncludesCallsInRecents).toBe(true);
  });

  it("writes ExpoCallKitTelecomIncludesCallsInRecents=false when the prop is false", async () => {
    const plist = await evaluateInfoPlist({ includesCallsInRecents: false });
    expect(plist.ExpoCallKitTelecomIncludesCallsInRecents).toBe(false);
  });

  it("writes ExpoCallKitTelecomIncludesCallsInRecents=true when the prop is explicitly true", async () => {
    const plist = await evaluateInfoPlist({ includesCallsInRecents: true });
    expect(plist.ExpoCallKitTelecomIncludesCallsInRecents).toBe(true);
  });

  it("keeps writing the timeout defaults alongside the new key (chain intact)", async () => {
    const plist = await evaluateInfoPlist({});
    expect(plist.ExpoCallKitTelecomIncomingCallTimeout).toBe(45);
    expect(plist.ExpoCallKitTelecomFulfillAnswerCallTimeout).toBe(30);
  });

  it("writes the media adapter and unhandled action for the native module", async () => {
    expect(await evaluateInfoPlist({})).toMatchObject({
      ExpoCallKitTelecomMediaAdapter: "webrtc",
      ExpoCallKitTelecomUnhandledAction: "fail",
    });
    expect(
      await evaluateInfoPlist({ mediaAdapter: "none", unhandledAction: "accept" }),
    ).toMatchObject({
      ExpoCallKitTelecomMediaAdapter: "none",
      ExpoCallKitTelecomUnhandledAction: "accept",
    });
  });
});

describe("withExpoCallKitTelecomIos call core selection", () => {
  it("defaults to WebRTC media without the simulator runtime", async () => {
    expect(await evaluateMod({}, "podfileProperties")).toEqual({
      "expo-callkit-telecom.mediaAdapter": "webrtc",
      "expo-callkit-telecom.simulator": "false",
    });
  });

  it("selects the media adapter and the simulator runtime", async () => {
    expect(
      await evaluateMod(
        { simulator: true, mediaAdapter: "livekit" },
        "podfileProperties",
      ),
    ).toEqual({
      "expo-callkit-telecom.mediaAdapter": "livekit",
      "expo-callkit-telecom.simulator": "true",
    });
  });

  it("drops the calling framework property that earlier versions wrote", async () => {
    expect(
      await evaluateMod({}, "podfileProperties", {
        "expo-callkit-telecom.callingFramework": "callKit",
      }),
    ).not.toHaveProperty("expo-callkit-telecom.callingFramework");
  });

  it("rejects the removed calling framework option", () => {
    for (const callingFramework of ["callKit", "liveCommunicationKit"]) {
      expect(() =>
        withExpoCallKitTelecomIos(
          { name: "test-app", slug: "test-app" },
          { callingFramework } as ExpoCallKitTelecomPluginProps,
        ),
      ).toThrow('The "callingFramework" option was removed');
    }
  });

  it("adds the SDK source after use_expo_modules! once", () => {
    const podfile = [
      "target 'App' do",
      "  use_expo_modules!",
      "  config = use_native_modules!(config_command)",
      "end",
    ].join("\n");
    const once = addSDKSource(podfile);
    expect(addSDKSource(once)).toBe(once);
    const lines = once.split("\n");
    expect(lines[2]).toMatch(
      /^  pod 'IosVoipSDK\/Core', :git => 'https:\/\/github\.com\/relayphone\/ios-voip-sdk\.git', :commit => '[0-9a-f]{40}' # expo-callkit-telecom: ios-voip-sdk source$/,
    );
    expect(lines).toHaveLength(5);
  });

  it("requires use_expo_modules! in the Podfile", () => {
    expect(() => addSDKSource("target 'App' do\nend")).toThrow(
      "Unable to find use_expo_modules! in the Podfile.",
    );
  });
});
