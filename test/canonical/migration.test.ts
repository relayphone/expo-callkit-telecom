import type { ExportedConfig } from "expo/config-plugins";
import assert from "node:assert/strict";
import { mkdtempSync, mkdirSync, rmSync, writeFileSync } from "node:fs";
import { createRequire } from "node:module";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { test } from "node:test";

const require = createRequire(import.meta.url);
const plugin: typeof import("../../plugin/src/withConversations").default =
  require("../../app.plugin.js").default;

function project(
  t: { after(cleanup: () => void): void },
  files: Record<string, string>
) {
  const root = mkdtempSync(join(tmpdir(), "conversations-migration-"));
  t.after(() => rmSync(root, { recursive: true, force: true }));
  mkdirSync(join(root, "ios"));
  for (const [file, contents] of Object.entries(files))
    writeFileSync(join(root, "ios", file), contents);
  return root;
}

async function prebuild(root: string) {
  const config: ExportedConfig = plugin(
    { name: "Migration", slug: "migration" },
    { mediaAdapter: "none" }
  );
  const mod = config.mods?.ios?.dangerous;
  assert.ok(mod, "The composed plugin checks prebuilt native projects");
  return mod({
    ...config,
    modResults: {},
    modRawConfig: config,
    modRequest: {
      projectRoot: root,
      platformProjectRoot: join(root, "ios"),
      platform: "ios",
      modName: "dangerous",
      introspect: false,
    },
  });
}

test("prebuilt Apple projects with the retired SDK source pin explain clean regeneration", async (t) => {
  const root = project(t, {
    Podfile:
      "use_expo_modules!\n  pod 'IosVoipSDK/Core', :git => 'https://example.invalid/sdk', :tag => 'v0.1.0' # expo-callkit-telecom: ios-voip-sdk source\n",
  });
  await assert.rejects(
    prebuild(root),
    /retired.*ExpoCallKitTelecom[\s\S]*expo prebuild --clean/
  );
});

test("a locked legacy Apple provider and old widget settings require regeneration", async (t) => {
  const cases: Record<string, string>[] = [
    {
      "Podfile.lock":
        "PODS:\n  - ExpoCallKitTelecom (0.5.0):\n    - IosVoipSDK/Core\n",
    },
    {
      "Podfile.properties.json": JSON.stringify({
        "expo-callkit-telecom.callPreviewActivities": "true",
      }),
    },
  ];
  for (const files of cases) {
    const root = project(t, files);
    await assert.rejects(
      prebuild(root),
      /retired.*ExpoCallKitTelecom[\s\S]*expo prebuild --clean/
    );
  }
});

test("fresh and canonical Apple projects accept repeat prebuild", async (t) => {
  const cases: Record<string, string>[] = [
    {},
    {
      Podfile: "use_expo_modules!\n",
      "Podfile.lock":
        "PODS:\n  - VoIPConversations (0.3.0)\n  - ExpoCallKitTelecomTools (1.0.0)\n",
      "Podfile.properties.json": JSON.stringify({
        "@relayphone/ios-voip-sdk.mediaAdapter": "none",
        "custom.flag": "true",
      }),
    },
  ];
  for (const files of cases) {
    const root = project(t, files);
    await prebuild(root);
    await prebuild(root);
  }
});

test("Android-only mode preserves app iOS mods and skips the retired integration check", async (t) => {
  const root = project(t, {
    "Podfile.properties.json": "not valid JSON",
    Podfile: "# expo-callkit-telecom: ios-voip-sdk source\n",
  });
  let runs = 0;
  const owned: NonNullable<NonNullable<ExportedConfig["mods"]>["ios"]> = {
    dangerous: async (mod) => {
      runs++;
      return mod;
    },
  };
  const config = plugin(
    {
      name: "AndroidOnly",
      slug: "android-only",
      mods: { ios: owned },
    },
    { ios: false }
  );
  assert.equal(config.mods?.ios, owned);
  assert.deepEqual(Object.keys(owned), ["dangerous"]);
  await owned.dangerous!({
    ...config,
    modResults: {},
    modRawConfig: config,
    modRequest: {
      projectRoot: root,
      platformProjectRoot: join(root, "ios"),
      platform: "ios",
      modName: "dangerous",
      introspect: false,
    },
  });
  assert.equal(runs, 1);
  assert.ok(config.mods?.android?.manifest);
});
