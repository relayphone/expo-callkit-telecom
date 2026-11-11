import type {
  AndroidManifest,
  ConfigPlugin,
  ExportedConfig,
  Mod,
} from "expo/config-plugins";
import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import {
  appendFileSync,
  mkdtempSync,
  readdirSync,
  rmSync,
  writeFileSync,
} from "node:fs";
import { createRequire, registerHooks } from "node:module";
import { tmpdir } from "node:os";
import { dirname, join, resolve, sep } from "node:path";
import { fileURLToPath } from "node:url";

const packageName = "expo-callkit-telecom";
const sdkName = "@relayphone/ios-voip-sdk";
const expoVersions = ["57.0.24", "58.0.0"] as const;
type ExpoMajor = 57 | 58;
type Options = {
  ios?: boolean;
  mediaAdapter: "none";
  androidNativeMedia: "livekit";
  androidSupportsHold: boolean;
  incomingCallTimeout: number;
};

async function applyMod<Data>(
  config: ExportedConfig,
  mod: Mod<Data> | undefined,
  data: Data,
  project: string,
  name: string
): Promise<Data> {
  assert.ok(mod, `Missing Android ${name} mod`);
  return (
    await mod({
      ...config,
      modResults: data,
      modRawConfig: config,
      modRequest: {
        projectRoot: project,
        platformProjectRoot: join(project, "android"),
        platform: "android",
        modName: name,
        introspect: true,
      },
    })
  ).modResults;
}

/** Execute the registered mods, rather than only checking that callbacks exist. */
async function checkAndroid(config: ExportedConfig, project: string) {
  const manifest: AndroidManifest = {
    manifest: {
      $: { "xmlns:android": "http://schemas.android.com/apk/res/android" },
      queries: [],
      application: [{ $: { "android:name": ".MainApplication" } }],
    },
  };
  const result = await applyMod(
    config,
    config.mods?.android?.manifest,
    manifest,
    project,
    "manifest"
  );
  const app = result.manifest.application?.[0];
  assert.ok(app);
  const metadata = Object.fromEntries(
    (app["meta-data"] ?? []).map((entry) => [
      entry.$["android:name"],
      entry.$["android:value"],
    ])
  );
  assert.equal(metadata.ExpoCallKitTelecomAndroidNativeMedia, "livekit");
  assert.equal(metadata.VoIPConversationsMediaAdapter, "none");
  assert.equal(metadata.ExpoCallKitTelecomSupportsHold, "false");
  assert.equal(metadata.ExpoCallKitTelecomIncomingCallTimeout, "17");
  assert.ok(
    app.service?.some(
      (entry) =>
        entry.$["android:name"] ===
        "expo.modules.callkittelecom.services.ExpoCallKitTelecomMessagingService"
    )
  );
  const properties = await applyMod(
    config,
    config.mods?.android?.gradleProperties,
    [],
    project,
    "gradleProperties"
  );
  assert.deepEqual(properties, [
    {
      type: "property",
      key: "expo-callkit-telecom.androidNativeMedia",
      value: "livekit",
    },
  ]);
  await applyMod(
    config,
    config.mods?.android?.dangerous,
    {},
    project,
    "dangerous"
  );
}

type ImportFailure = { error: unknown; parentURL: string | undefined };

/** Match the exact failed resolution observed in the SDK, never a similar v2 error. */
export function assertSdkImportFailure(
  error: unknown,
  sdkRoot: string,
  observed: ImportFailure | undefined
): void {
  assert.ok(
    error instanceof Error && "code" in error,
    "Expected a Node module-loading error"
  );
  assert.equal(error.code, "MODULE_NOT_FOUND");
  assert.equal(
    observed?.error,
    error,
    "Expected the observed Expo import failure"
  );
  assert.ok(
    observed?.parentURL?.startsWith("file:") &&
      fileURLToPath(observed.parentURL).startsWith(sdkRoot + sep),
    "The missing import must originate in the SDK, after the v2 plugin loads"
  );
}

/** Load the installed app.plugin.js with this consumer's real Expo dependency. */
export async function inspectPluginProject(project: string, major: ExpoMajor) {
  const require = createRequire(join(project, "package.json"));
  const expo: unknown = require("expo/package.json");
  assert.ok(
    expo &&
      typeof expo === "object" &&
      "version" in expo &&
      typeof expo.version === "string" &&
      expo.version.startsWith(`${major}.`)
  );
  const sdkRoot = dirname(require.resolve(`${sdkName}/package.json`));
  const entry: unknown = require(`${packageName}/app.plugin.js`);
  const candidate: unknown =
    entry && typeof entry === "object" && "default" in entry
      ? entry.default
      : entry;
  assert.equal(
    typeof candidate,
    "function",
    "The packed plugin entry must load"
  );
  const plugin = candidate as ConfigPlugin<Options>;
  const config = (): ExportedConfig => ({
    name: "PluginLoading",
    slug: "plugin-loading",
    ios: { bundleIdentifier: "com.example.pluginloading" },
  });
  const options: Options = {
    mediaAdapter: "none",
    androidNativeMedia: "livekit",
    androidSupportsHold: false,
    incomingCallTimeout: 17,
  };
  const androidOnly: ExportedConfig = plugin(config(), {
    ...options,
    ios: false,
  });
  assert.equal(
    androidOnly.mods?.ios,
    undefined,
    "ios:false must add no iOS mods"
  );
  assert.deepEqual(androidOnly.ios, config().ios);
  assert.deepEqual(
    Object.keys(require.cache).filter((path) => path.startsWith(sdkRoot + sep)),
    [],
    "ios:false must not require any SDK plugin module"
  );
  await checkAndroid(androidOnly, project);

  let combined: ExportedConfig;
  let observed: ImportFailure | undefined;
  const hooks = registerHooks({
    resolve(specifier, context, next) {
      try {
        return next(specifier, context);
      } catch (error) {
        if (specifier === "expo/config-plugins.js")
          observed = { error, parentURL: context.parentURL };
        throw error;
      }
    },
  });
  try {
    combined = plugin(config(), options);
  } catch (error) {
    if (major !== 58) throw error;
    assertSdkImportFailure(error, sdkRoot, observed);
    return {
      expo: expo.version,
      androidOnly: "passed",
      iosDefault: "expected-sdk-failure",
    } as const;
  } finally {
    hooks.deregister();
  }
  assert.equal(
    major,
    57,
    "The SDK now loads on Expo 58; replace its expected-failure case with success coverage"
  );
  assert.ok(combined.mods?.ios?.infoPlist, "iOS is enabled by default");
  await checkAndroid(combined, project);
  return {
    expo: expo.version,
    androidOnly: "passed",
    iosDefault: "passed",
  } as const;
}

/** Install one CI-built tarball in isolated minimal Expo 57 and 58 projects. */
export async function checkPackedPlugin(directory: string): Promise<void> {
  const archives = readdirSync(directory).filter((name) =>
    name.endsWith(".tgz")
  );
  assert.equal(archives.length, 1, "Expected one CI package archive");
  const archive = resolve(directory, archives[0]!);
  for (const expo of expoVersions) {
    const project = mkdtempSync(
      join(tmpdir(), "conversations-plugin-loading-")
    );
    try {
      writeFileSync(
        join(project, "package.json"),
        JSON.stringify({
          name: "plugin-loading",
          version: "1.0.0",
          private: true,
          dependencies: { expo, [packageName]: `file:${archive}` },
        })
      );
      execFileSync(
        "npm",
        [
          "install",
          "--ignore-scripts",
          "--no-audit",
          "--no-fund",
          "--legacy-peer-deps",
          "--package-lock=false",
        ],
        {
          cwd: project,
          timeout: 240_000,
          stdio: "pipe",
        }
      );
      const result = await inspectPluginProject(
        project,
        expo.startsWith("57.") ? 57 : 58
      );
      console.log(JSON.stringify(result));
      if (process.env.GITHUB_STEP_SUMMARY)
        appendFileSync(
          process.env.GITHUB_STEP_SUMMARY,
          `\nExpo ${result.expo}: packed Android-only plugin passed; default iOS: ${result.iosDefault}.\n`
        );
    } finally {
      rmSync(project, { recursive: true, force: true });
    }
  }
}

if (
  process.argv[1] &&
  resolve(process.argv[1]) === fileURLToPath(import.meta.url)
) {
  const [mode, path, major] = process.argv.slice(2);
  assert.ok(
    path,
    "Use check ARCHIVE_DIRECTORY or inspect CONSUMER_DIRECTORY 57|58"
  );
  if (mode === "check") await checkPackedPlugin(resolve(path));
  else if (mode === "inspect" && (major === "57" || major === "58"))
    console.log(
      JSON.stringify(
        await inspectPluginProject(resolve(path), Number(major) as ExpoMajor)
      )
    );
  else throw new Error("Unknown plugin-loading command");
}
