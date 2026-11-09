import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import {
  mkdtempSync,
  mkdirSync,
  readFileSync,
  readdirSync,
  rmSync,
  writeFileSync,
} from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { test } from "node:test";

import {
  downloadSdk,
  inspectInstalledPackages,
  installSdk,
  prepareIosConsumer,
  readSdkArtifact,
  sdkAssetUrl,
  sdkChecksumsUrl,
  sdkFilename,
  sdkVersion,
  verifySdkLock,
} from "./sdk-evaluation.ts";

function fixture(t: { after: (cleanup: () => void) => void }) {
  const root = mkdtempSync(join(tmpdir(), "sdk-release-test-"));
  t.after(() => rmSync(root, { recursive: true, force: true }));
  return { root, artifacts: join(root, "artifacts") };
}
const bytes = new TextEncoder().encode("released SDK archive fixture");
const checksum = createHash("sha256").update(bytes).digest("hex");
function releaseDownload(sums = `${checksum}  ${sdkFilename}\n`) {
  return async (url: string) => {
    if (url === sdkAssetUrl) return bytes;
    assert.equal(url, sdkChecksumsUrl);
    return new TextEncoder().encode(sums);
  };
}
function consumerProject(root: string) {
  const project = join(root, "project");
  mkdirSync(project);
  writeFileSync(
    join(project, "package.json"),
    JSON.stringify({
      name: "sdk-consumer",
      private: true,
      dependencies: { "@relayphone/ios-voip-sdk": sdkAssetUrl },
    })
  );
  return project;
}

test("downloads the release URL and rechecks SHA256SUMS on reuse", async (t) => {
  const f = fixture(t);
  const requested: string[] = [];
  const artifact = await downloadSdk(f.artifacts, async (url) => {
    requested.push(url);
    return releaseDownload()(url);
  });
  assert.deepEqual(requested.sort(), [sdkAssetUrl, sdkChecksumsUrl].sort());
  assert.equal(artifact.version, sdkVersion);
  assert.equal(artifact.url, sdkAssetUrl);
  assert.equal(artifact.sha256, checksum);
  assert.deepEqual(readSdkArtifact(f.artifacts), artifact);
  writeFileSync(join(f.artifacts, sdkFilename), "damaged");
  assert.throws(() => readSdkArtifact(f.artifacts), /SHA-256 differs/);
});

test("wrong, missing, malformed and duplicate release checksums fail before retaining the archive", async (t) => {
  for (const sums of [
    `${"0".repeat(64)}  ${sdkFilename}\n`,
    `${checksum}  other.tgz\n`,
    `bad-hash  ${sdkFilename}\n`,
    `${checksum}  ${sdkFilename}\n${checksum}  ${sdkFilename}\n`,
  ]) {
    const f = fixture(t);
    await assert.rejects(
      downloadSdk(f.artifacts, releaseDownload(sums)),
      /SHA-256 differs|exactly one SDK/
    );
    assert.deepEqual(readdirSync(f.artifacts), []);
  }
});

test("network failure and a nonempty destination do not produce a reusable artifact", async (t) => {
  const f = fixture(t);
  await assert.rejects(
    downloadSdk(f.artifacts, async () => {
      throw Error("download failed");
    }),
    /download failed/
  );
  assert.deepEqual(readdirSync(f.artifacts), []);
  writeFileSync(join(f.artifacts, "existing"), "keep");
  let requested = false;
  await assert.rejects(
    downloadSdk(f.artifacts, async () => {
      requested = true;
      return bytes;
    }),
    /must be empty/
  );
  assert.equal(requested, false);
});

test("installation requires a verified archive and the public URL manifest without rewriting it", async (t) => {
  const f = fixture(t);
  await downloadSdk(f.artifacts, releaseDownload());
  const project = consumerProject(f.root);
  const manifest = join(project, "package.json");
  const original = readFileSync(manifest, "utf8");
  writeFileSync(join(f.artifacts, sdkFilename), "damaged");
  assert.throws(() => installSdk(f.artifacts, project), /SHA-256 differs/);
  assert.equal(readFileSync(manifest, "utf8"), original);
  writeFileSync(join(f.artifacts, sdkFilename), bytes);
  writeFileSync(
    manifest,
    JSON.stringify({ dependencies: { "@relayphone/ios-voip-sdk": "0.4.0" } })
  );
  assert.throws(
    () => installSdk(f.artifacts, project),
    /must use the release asset URL/
  );
  assert.equal(
    readFileSync(manifest, "utf8"),
    JSON.stringify({ dependencies: { "@relayphone/ios-voip-sdk": "0.4.0" } })
  );
});

test("the locked URL and integrity must match the verified release bytes", async (t) => {
  const f = fixture(t);
  const artifact = await downloadSdk(f.artifacts, releaseDownload());
  const project = consumerProject(f.root);
  const lock = join(project, "bun.lock");
  const writeLock = (url: string, integrity: string) =>
    writeFileSync(
      lock,
      `{"packages":{"@relayphone/ios-voip-sdk":[${JSON.stringify(
        `@relayphone/ios-voip-sdk@${url}`
      )},{},${JSON.stringify(integrity)},],},}`
    );
  writeLock(sdkAssetUrl, artifact.integrity);
  assert.doesNotThrow(() => verifySdkLock(project, artifact.integrity));
  writeLock(sdkAssetUrl, "sha512-wrong");
  assert.throws(
    () => verifySdkLock(project, artifact.integrity),
    /URL or integrity differs/
  );
  writeLock("https://example.invalid/other-sdk.tgz", artifact.integrity);
  assert.throws(
    () => verifySdkLock(project, artifact.integrity),
    /URL or integrity differs/
  );
});

test("temporary-fork mode cannot relax installation without a local fork tarball", async (t) => {
  const f = fixture(t);
  await downloadSdk(f.artifacts, releaseDownload());
  const project = consumerProject(f.root);
  assert.throws(
    () => installSdk(f.artifacts, project, "temporary-fork"),
    /requires a local fork tarball/
  );
  const path = join(project, "package.json");
  const pkg = JSON.parse(readFileSync(path, "utf8"));
  pkg.dependencies["expo-callkit-telecom"] = "0.5.0";
  writeFileSync(path, JSON.stringify(pkg));
  assert.throws(
    () => installSdk(f.artifacts, project, "temporary-fork"),
    /requires a local fork tarball/
  );
});

function installedConsumer(
  t: { after: (cleanup: () => void) => void },
  forkPlugin = "module.exports = config => { config.mods = { android: {} }; return config; };"
) {
  const f = fixture(t);
  const consumer = join(f.root, "consumer");
  mkdirSync(consumer);
  const install = (name: string, config: unknown) => {
    const root = join(consumer, "node_modules", name);
    mkdirSync(root, { recursive: true });
    writeFileSync(
      join(root, "package.json"),
      JSON.stringify({
        name,
        version: "0.4.0",
        exports: {
          ".": "./index.js",
          "./package.json": "./package.json",
          "./app.plugin": "./app.plugin.js",
          "./plugin": "./app.plugin.js",
          "./types": { types: "./index.d.ts" },
        },
      })
    );
    writeFileSync(join(root, "index.js"), "module.exports = {};");
    writeFileSync(
      join(root, "app.plugin.js"),
      name === "expo-callkit-telecom"
        ? forkPlugin
        : "module.exports = config => { config.mods = { ios: {} }; return config; };"
    );
    writeFileSync(
      join(root, "expo-module.config.json"),
      JSON.stringify(config)
    );
    return root;
  };
  writeFileSync(
    join(consumer, "package.json"),
    JSON.stringify({
      private: true,
      dependencies: {
        "@relayphone/ios-voip-sdk": "0.4.0",
        "expo-callkit-telecom": "0.4.0",
      },
    })
  );
  const sdk = install("@relayphone/ios-voip-sdk", {
    apple: { modules: ["VoIPConversationsModule"] },
  });
  install("expo-callkit-telecom", {
    platforms: ["android"],
    android: {
      modules: ["expo.modules.callkittelecom.ExpoCallKitTelecomModule"],
    },
  });
  return { consumer, sdk };
}

test("installed packages must expose working plugins and their native registration payload", (t) => {
  const { consumer, sdk } = installedConsumer(t);
  assert.equal(inspectInstalledPackages(consumer, "0.4.0").sdkInstallations, 1);
  writeFileSync(
    join(sdk, "expo-module.config.json"),
    JSON.stringify({ apple: { modules: [] } })
  );
  assert.throws(
    () => inspectInstalledPackages(consumer, "0.4.0"),
    /SDK Apple module registration is missing/
  );
  rmSync(join(sdk, "expo-module.config.json"));
  assert.throws(() => inspectInstalledPackages(consumer, "0.4.0"), /ENOENT/);
});

test("a no-op plugin cannot borrow mods from another valid plugin", (t) => {
  const { consumer } = installedConsumer(
    t,
    "module.exports = config => config;"
  );
  assert.throws(
    () => inspectInstalledPackages(consumer, "0.4.0"),
    /expo-callkit-telecom plugin did not configure project mods/
  );
});

test("the thin iOS fixture retains installed dependencies and chooses one Apple provider", (t) => {
  const f = fixture(t);
  const project = consumerProject(f.root);
  const original = JSON.parse(
    readFileSync(join(project, "package.json"), "utf8")
  );
  const fixtures = join(f.root, "ios-fixture");
  mkdirSync(fixtures);
  writeFileSync(
    join(fixtures, "app.config.ts"),
    "export default { name: 'SDKComposition' };\n"
  );
  writeFileSync(join(fixtures, "index.ts"), "export {};\n");
  prepareIosConsumer(project, fixtures);
  const prepared = JSON.parse(
    readFileSync(join(project, "package.json"), "utf8")
  );
  assert.deepEqual(prepared.dependencies, original.dependencies);
  assert.equal(prepared.main, "index.ts");
  assert.equal(prepared.expo, undefined);
  assert.equal(readFileSync(join(project, "index.ts"), "utf8"), "export {};\n");
});

test("the composed package rejects a second Apple provider and a runtime types entry", (t) => {
  const { consumer } = installedConsumer(t);
  const root = join(consumer, "node_modules/expo-callkit-telecom");
  const nativePath = join(root, "expo-module.config.json");
  const native = JSON.parse(readFileSync(nativePath, "utf8"));
  writeFileSync(
    nativePath,
    JSON.stringify({ ...native, apple: { modules: ["LegacyProvider"] } })
  );
  assert.throws(
    () => inspectInstalledPackages(consumer, "0.4.0"),
    /Android module registration is missing/
  );
  writeFileSync(nativePath, JSON.stringify(native));
  const path = join(root, "package.json");
  const pkg = JSON.parse(readFileSync(path, "utf8"));
  pkg.exports["./types"].default = "./index.js";
  writeFileSync(path, JSON.stringify(pkg));
  assert.throws(
    () => inspectInstalledPackages(consumer, "0.4.0"),
    /types entry must be type-only/
  );
});
