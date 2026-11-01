import type { ExpoConfig } from "expo/config";
import { IOSConfig, type ModConfig } from "expo/config-plugins";
import { mkdtempSync, readFileSync, writeFileSync } from "fs";
import { tmpdir } from "os";
import { join } from "path";

import type { ExpoCallKitTelecomPluginProps } from "../withExpoCallKitTelecom";
import { withExpoCallKitTelecomIos } from "../withExpoCallKitTelecomIos";
import { addFocusFilterSources } from "../withFocusFilter";

const app: ExpoConfig = { name: "test-app", slug: "test-app" };
const focusFilter = { appGroup: "group.com.example.calls" };

/** Applies the iOS plugin and runs one of its mods against the given results. */
async function evaluateMod(
  props: ExpoCallKitTelecomPluginProps,
  modName: "infoPlist" | "podfileProperties" | "entitlements",
  modResults: Record<string, unknown> = {},
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
    modResults,
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

describe("withExpoCallKitTelecomIos Focus filter", () => {
  it("adds the Focus subspec, the App Group and its identifier for the module", async () => {
    expect(
      await evaluateMod({ focusFilter }, "podfileProperties"),
    ).toMatchObject({ "expo-callkit-telecom.focusFilter": "true" });
    expect(await evaluateMod({ focusFilter }, "infoPlist")).toMatchObject({
      ExpoCallKitTelecomFocusAppGroup: "group.com.example.calls",
    });
    expect(
      await evaluateMod({ focusFilter }, "entitlements", {
        "com.apple.security.application-groups": [
          "group.com.example.other",
          "group.com.example.calls",
        ],
      }),
    ).toMatchObject({
      "com.apple.security.application-groups": [
        "group.com.example.other",
        "group.com.example.calls",
      ],
    });
    expect(await evaluateMod({}, "infoPlist")).not.toHaveProperty(
      "ExpoCallKitTelecomFocusAppGroup",
    );
  });

  it("needs an App Group identifier", () => {
    expect(() =>
      withExpoCallKitTelecomIos(
        { ...app },
        { focusFilter: { appGroup: "com.example.calls" } },
      ),
    ).toThrow('"focusFilter.appGroup" must be an App Group identifier');
  });

  it("compiles the filter's sources in the app only when there are some", () => {
    const withoutSources: ExpoConfig & { mods?: ModConfig } =
      withExpoCallKitTelecomIos({ ...app }, { focusFilter });
    expect(withoutSources.mods?.ios?.xcodeproj).toBeUndefined();
    const withSources: ExpoConfig & { mods?: ModConfig } =
      withExpoCallKitTelecomIos(
        { ...app },
        { focusFilter: { ...focusFilter, sources: ["./focus/Filter.swift"] } },
      );
    expect(withSources.mods?.ios?.xcodeproj).toBeDefined();
  });
});

describe("addFocusFilterSources", () => {
  function setUp() {
    const projectRoot = mkdtempSync(join(tmpdir(), "focus-filter-"));
    writeFileSync(join(projectRoot, "Filter.swift"), "struct Filter {}\n");
    const project = IOSConfig.XcodeUtils.getPbxproj(
      join(__dirname, "fixtures"),
    );
    return { projectRoot, project };
  }

  it("copies the sources and compiles them in the app target, once", () => {
    const { projectRoot, project } = setUp();
    const options = {
      projectRoot,
      projectName: "HelloWorld",
      sources: ["./Filter.swift"],
    };
    addFocusFilterSources(project, options);
    expect(
      readFileSync(
        join(projectRoot, "ios/HelloWorld/FocusFilter/Filter.swift"),
        "utf8",
      ),
    ).toBe("struct Filter {}\n");
    const once = project.writeSync();
    expect(once).toContain(
      "/* Filter.swift in Sources */ = {isa = PBXBuildFile; fileRef = ",
    );
    expect(once).toContain('path = "HelloWorld/FocusFilter/Filter.swift"');
    expect(addFocusFilterSources(project, options).writeSync()).toBe(once);
  });

  it("rejects a missing file and files that aren't Swift", () => {
    const { projectRoot, project } = setUp();
    expect(() =>
      addFocusFilterSources(project, {
        projectRoot,
        projectName: "HelloWorld",
        sources: ["./Missing.swift"],
      }),
    ).toThrow("Focus filter source not found");
    expect(() =>
      addFocusFilterSources(project, {
        projectRoot,
        projectName: "HelloWorld",
        sources: ["./Filter.m"],
      }),
    ).toThrow("isn't a Swift file");
  });
});
