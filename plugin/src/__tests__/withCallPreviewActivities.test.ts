import type { ExpoConfig } from "expo/config";
import {
  IOSConfig,
  type ModConfig,
  type XcodeProject,
} from "expo/config-plugins";
import { join } from "path";

import { IOS_VOIP_SDK_VERSION } from "../constants";
import {
  addCallPreviewWidget,
  addWidgetPods,
  WIDGET_TARGET,
  widgetFiles,
  withWidgetCredentials,
} from "../withCallPreviewActivities";
import type { ExpoCallKitTelecomPluginProps } from "../withExpoCallKitTelecom";
import { withExpoCallKitTelecomIos } from "../withExpoCallKitTelecomIos";

const app: ExpoConfig = {
  name: "test-app",
  slug: "test-app",
  version: "2.3.4",
  ios: { bundleIdentifier: "com.example.calls", buildNumber: "17" },
};

/** Applies the iOS plugin and runs one of its mods against the given results. */
async function evaluateMod(
  props: ExpoCallKitTelecomPluginProps,
  modName: "infoPlist" | "podfileProperties",
): Promise<Record<string, unknown>> {
  const config: ExpoConfig & { mods?: ModConfig } = withExpoCallKitTelecomIos(
    { ...app },
    props,
  );
  const mod = config.mods?.ios?.[modName] as unknown as
    | ((config: unknown) => Promise<{ modResults: Record<string, unknown> }>)
    | undefined;
  if (!mod) throw new Error(`The plugin registered no ${modName} mod.`);
  const result = await mod({
    ...config,
    modResults: {},
    modRequest: {
      projectRoot: "/tmp/test-app",
      platformProjectRoot: "/tmp/test-app/ios",
      modName,
      platform: "ios",
      introspect: true,
    },
    modRawConfig: { ...app },
  });
  return result.modResults;
}

/** The Expo template's Xcode project, whose app target is `HelloWorld`. */
function templateProject(): XcodeProject {
  return IOSConfig.XcodeUtils.getPbxproj(join(__dirname, "fixtures"));
}

type Section = Record<string, Record<string, unknown> | string>;

function section(project: XcodeProject, name: string): Section {
  return (project.hash.project.objects as Record<string, Section>)[name] ?? {};
}

/** The names of the files in a target's build phase of the given type. */
function phaseFiles(
  project: XcodeProject,
  targetName: string,
  type: string,
): string[] {
  const targets = section(project, "PBXNativeTarget");
  const target = Object.values(targets).find(
    (entry) =>
      typeof entry === "object" &&
      String(entry.name).replace(/"/g, "") === targetName,
  ) as { buildPhases: { value: string }[] };
  const phases = section(project, type);
  return target.buildPhases.flatMap(({ value }) => {
    const phase = phases[value];
    if (typeof phase !== "object") return [];
    return (phase.files as { comment: string }[]).map(({ comment }) => comment);
  });
}

describe("withExpoCallKitTelecomIos call preview Live Activities", () => {
  it("adds the LiveActivity subspec and supports Live Activities", async () => {
    expect(
      await evaluateMod({ callPreviewActivities: true }, "podfileProperties"),
    ).toMatchObject({ "expo-callkit-telecom.callPreviewActivities": "true" });
    expect(
      await evaluateMod({ callPreviewActivities: true }, "infoPlist"),
    ).toMatchObject({
      NSSupportsLiveActivities: true,
    });
    expect(await evaluateMod({}, "infoPlist")).not.toHaveProperty(
      "NSSupportsLiveActivities",
    );
  });

  it("generates the widget extension unless the app brings its own", () => {
    const generated: ExpoConfig & { mods?: ModConfig } =
      withExpoCallKitTelecomIos({ ...app }, { callPreviewActivities: true });
    expect(generated.mods?.ios?.xcodeproj).toBeDefined();
    expect(generated.mods?.ios?.podfile).toBeDefined();
    const own: ExpoConfig & { mods?: ModConfig } = withExpoCallKitTelecomIos(
      { ...app },
      { callPreviewActivities: { widgetExtension: false } },
    );
    expect(own.mods?.ios?.xcodeproj).toBeUndefined();
    expect(own.extra).toBeUndefined();
  });

  it("needs the app's bundle identifier for the widget extension's", () => {
    expect(() =>
      withExpoCallKitTelecomIos(
        { name: "test-app", slug: "test-app" },
        { callPreviewActivities: true },
      ),
    ).toThrow('"ios.bundleIdentifier"');
  });

  it("declares the widget extension for EAS Build once", () => {
    const config = withWidgetCredentials(
      withWidgetCredentials(
        {
          ...app,
          extra: { eas: { projectId: "project", build: { other: true } } },
        },
        "com.example.calls",
      ),
      "com.example.calls",
    );
    expect(config.extra).toEqual({
      eas: {
        projectId: "project",
        build: {
          other: true,
          experimental: {
            ios: {
              appExtensions: [
                {
                  targetName: WIDGET_TARGET,
                  bundleIdentifier: "com.example.calls.CallPreviewWidget",
                  entitlements: {},
                },
              ],
            },
          },
        },
      },
    });
  });
});

describe("addCallPreviewWidget", () => {
  it("adds the widget extension, embedded in the app, and the shared Join intent", () => {
    const project = addCallPreviewWidget(templateProject(), {
      projectName: "HelloWorld",
      bundleIdentifier: "com.example.calls",
    });
    const widget = project.pbxTargetByName(`"${WIDGET_TARGET}"`) as {
      productType: string;
      buildConfigurationList: string;
    };
    expect(widget.productType).toBe('"com.apple.product-type.app-extension"');
    expect(phaseFiles(project, WIDGET_TARGET, "PBXSourcesBuildPhase")).toEqual([
      "CallPreviewWidget.swift in Sources",
      "JoinCallPreview.swift in Sources",
    ]);
    expect(phaseFiles(project, "HelloWorld", "PBXSourcesBuildPhase")).toContain(
      "JoinCallPreview.swift in Sources",
    );
    expect(phaseFiles(project, "HelloWorld", "PBXCopyFilesBuildPhase")).toEqual(
      ["CallPreviewWidget.appex in Embed Foundation Extensions"],
    );
    const embed = Object.values(
      section(project, "PBXCopyFilesBuildPhase"),
    ).find((entry) => typeof entry === "object");
    expect(embed).toMatchObject({ dstSubfolderSpec: 13, dstPath: '""' });

    const lists = section(project, "XCConfigurationList");
    const configurations = section(project, "XCBuildConfiguration");
    const settings = (
      lists[widget.buildConfigurationList] as {
        buildConfigurations: { value: string }[];
      }
    ).buildConfigurations.map(
      ({ value }) =>
        (configurations[value] as { buildSettings: Record<string, unknown> })
          .buildSettings,
    );
    expect(settings).toHaveLength(2);
    for (const setting of settings) {
      expect(setting).toMatchObject({
        PRODUCT_BUNDLE_IDENTIFIER: '"com.example.calls.CallPreviewWidget"',
        INFOPLIST_FILE: "CallPreviewWidget/Info.plist",
        IPHONEOS_DEPLOYMENT_TARGET: "26.0",
        SWIFT_VERSION: "6.0",
      });
    }
    expect(project.writeSync()).not.toContain("undefined");
  });

  it("leaves a project that has the widget extension unchanged", () => {
    const options = {
      projectName: "HelloWorld",
      bundleIdentifier: "com.example.calls",
    };
    const project = addCallPreviewWidget(templateProject(), options);
    const once = project.writeSync();
    expect(addCallPreviewWidget(project, options).writeSync()).toBe(once);
  });
});

describe("the widget extension's files and pods", () => {
  it("gives the extension the app's version and the shared Join intent", () => {
    const files = widgetFiles(app);
    expect(files["Info.plist"]).toContain("<string>2.3.4</string>");
    expect(files["Info.plist"]).toContain("<string>17</string>");
    expect(files["Info.plist"]).toContain("com.apple.widgetkit-extension");
    expect(files["CallPreviewWidget.swift"]).toContain(
      "join: JoinCallPreview.self",
    );
    expect(files["JoinCallPreview.swift"]).toContain(
      "struct JoinCallPreview: JoinCallPreviewIntent",
    );
    expect(files["JoinCallPreview.swift"]).toContain(".foreground(.immediate)");
  });

  it("adds the extension's pod target once, from the app's SDK source", () => {
    const podfile = "target 'App' do\n  use_expo_modules!\nend\n";
    const once = addWidgetPods(podfile);
    expect(addWidgetPods(once)).toBe(once);
    expect(once).toContain(
      `target '${WIDGET_TARGET}' do\n  pod 'IosVoipSDK/LiveActivity', ` +
        `:git => 'https://github.com/relayphone/ios-voip-sdk.git', ` +
        `:tag => 'v${IOS_VOIP_SDK_VERSION}'\nend\n`,
    );
    expect(once.startsWith(podfile)).toBe(true);
  });
});
