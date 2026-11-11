import type { ExportedConfig, Mod } from "expo/config-plugins";
import assert from "node:assert/strict";
import { mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { createRequire } from "node:module";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { test } from "node:test";

import type { ComposedPluginProps } from "../../plugin/src/withConversations";

const require = createRequire(import.meta.url);
const sdk: typeof import("@relayphone/ios-voip-sdk/plugin").default =
  require("@relayphone/ios-voip-sdk/app.plugin").default;
const {
  IOSConfig,
}: typeof import("expo/config-plugins") = require("expo/config-plugins");

const fork: typeof import("../../plugin/src/withConversations").default =
  require("../../app.plugin.js").default;
type IOSMods = NonNullable<NonNullable<ExportedConfig["mods"]>["ios"]>;
type Podfile = { path: string; language: "rb"; contents: string };

async function apply<Data>(
  config: ExportedConfig,
  mod: Mod<Data> | undefined,
  data: Data,
  root: string,
  modName: string
) {
  assert.ok(mod);
  return (
    await mod({
      ...config,
      modResults: data,
      modRawConfig: config,
      modRequest: {
        projectRoot: root,
        platformProjectRoot: join(root, "ios"),
        projectName: "HelloWorld",
        platform: "ios",
        modName,
        introspect: false,
      },
    })
  ).modResults;
}

function fieldValues(
  section: Record<string, unknown>,
  field: string
): string[] {
  return Object.values(section).flatMap((entry) => {
    if (typeof entry !== "object" || entry === null || !(field in entry))
      return [];
    const value: unknown = Reflect.get(entry, field);
    return typeof value === "string" ? [value.replaceAll('"', "")] : [];
  });
}

for (const sdkFirst of [false, true]) {
  test(`SDK ${
    sdkFirst ? "before" : "after"
  } composition keeps first iOS options and one widget/Focus integration`, async (t) => {
    const root = mkdtempSync(join(tmpdir(), "composition-project-"));
    t.after(() => rmSync(root, { recursive: true, force: true }));
    writeFileSync(join(root, "Filter.swift"), "import AppIntents\n");
    const options: ComposedPluginProps = {
      mediaAdapter: "none",
      incomingCallTimeout: 17,
      callPreviewActivities: true,
      focusFilter: {
        appGroup: "group.com.example.calls",
        sources: ["Filter.swift"],
      },
    };
    const changed: ComposedPluginProps = {
      mediaAdapter: "webrtc",
      incomingCallTimeout: 30,
      callPreviewActivities: false,
    };
    let config: ExportedConfig = {
      name: "HelloWorld",
      slug: "hello-world",
      ios: { bundleIdentifier: "com.example.calls" },
    };
    config = sdkFirst
      ? sdk(config, options)
      : fork(config, { ...options, androidNativeMedia: "livekit" });
    config = sdkFirst
      ? fork(config, { ...changed, androidNativeMedia: "livekit" })
      : sdk(config, changed);
    config = fork(config, { ...changed, androidNativeMedia: "none" });
    const info = await apply(
      config,
      config.mods?.ios?.infoPlist,
      {},
      root,
      "infoPlist"
    );
    assert.equal(info.VoIPConversationsIncomingCallTimeout, 17);
    assert.equal(info.NSSupportsLiveActivities, true);
    const entitlements = await apply(
      config,
      config.mods?.ios?.entitlements,
      {},
      root,
      "entitlements"
    );
    assert.deepEqual(entitlements["com.apple.security.application-groups"], [
      "group.com.example.calls",
    ]);
    const template = join(
      import.meta.dirname,
      "../../plugin/src/__tests__/fixtures"
    );
    const project = IOSConfig.XcodeUtils.getPbxproj(template);
    await apply(
      config,
      config.mods?.ios?.xcodeproj,
      project,
      root,
      "xcodeproj"
    );
    await apply(
      config,
      config.mods?.ios?.xcodeproj,
      project,
      root,
      "xcodeproj"
    );
    assert.equal(
      fieldValues(project.pbxNativeTargetSection(), "name").filter(
        (name) => name === "CallPreviewWidget"
      ).length,
      1
    );
    const files = fieldValues(project.pbxFileReferenceSection(), "path");
    for (const name of ["JoinCallPreview.swift", "Filter.swift"])
      assert.equal(files.filter((file) => file.endsWith(name)).length, 1, name);
    let podfile: Podfile = {
      path: join(root, "ios/Podfile"),
      language: "rb",
      contents: "target 'HelloWorld' do\n  use_expo_modules!\nend\n",
    };
    const ios: IOSMods & { podfile?: Mod<Podfile> } = config.mods?.ios ?? {};
    podfile = await apply(config, ios.podfile, podfile, root, "podfile");
    podfile = await apply(config, ios.podfile, podfile, root, "podfile");
    assert.equal(
      podfile.contents.match(/target 'CallPreviewWidget'/g)?.length,
      1
    );
    assert.equal(
      podfile.contents.match(/pod 'VoIPConversations\/LiveActivity'/g)?.length,
      1
    );
    assert.equal(podfile.contents.includes(":git =>"), false);
  });
}
