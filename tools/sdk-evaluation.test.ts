import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import {
  mkdtempSync,
  mkdirSync,
  readFileSync,
  rmSync,
  writeFileSync,
} from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { test } from "node:test";

import {
  inspectInstalledPackages,
  installSdk,
  packSdk,
  prepareIosConsumer,
  readSdkArtifact,
} from "./sdk-evaluation.ts";

function consumerProject(root: string, prepare = "") {
  const project = join(root, "project");
  mkdirSync(project);
  mkdirSync(join(project, "fixture-dependency"));
  writeFileSync(
    join(project, "fixture-dependency/package.json"),
    JSON.stringify({ name: "fixture-dependency", version: "1.0.0" })
  );
  const dependencies = { "fixture-dependency": "file:./fixture-dependency" };
  writeFileSync(
    join(project, "package.json"),
    JSON.stringify({ name: "sdk-consumer", private: true, dependencies })
  );
  execFileSync("bun", ["install", "--lockfile-only"], {
    cwd: project,
    stdio: "pipe",
    timeout: 30_000,
  });
  writeFileSync(
    join(project, "package.json"),
    JSON.stringify({
      name: "sdk-consumer",
      private: true,
      dependencies: {
        ...dependencies,
        "@relayphone/ios-voip-sdk": "9.9.9-unpublished",
      },
      scripts: prepare ? { prepare: "node prepare.cjs" } : {},
    })
  );
  writeFileSync(join(project, "prepare.cjs"), prepare);
  return project;
}

test("the verified archive is installed before lifecycle scripts without changing manifests or locks", (t) => {
  const f = fixture(t);
  const artifact = packSdk(f.sdk, f.artifacts, f.revision);
  const project = consumerProject(
    f.root,
    `
    const assert = require('node:assert/strict');
    assert.equal(require('@relayphone/ios-voip-sdk/package.json').version, '0.3.0');
    require('node:fs').writeFileSync('prepared', 'sdk available');
  `
  );
  const manifest = readFileSync(join(project, "package.json"), "utf8");
  const lock = readFileSync(join(project, "bun.lock"), "utf8");
  assert.deepEqual(installSdk(f.artifacts, project, f.revision), artifact);
  assert.equal(
    readFileSync(join(project, "prepared"), "utf8"),
    "sdk available"
  );
  assert.equal(readFileSync(join(project, "package.json"), "utf8"), manifest);
  assert.equal(readFileSync(join(project, "bun.lock"), "utf8"), lock);
});

test("a failed consumer install restores its original manifest and lock", (t) => {
  const f = fixture(t);
  packSdk(f.sdk, f.artifacts, f.revision);
  const project = consumerProject(
    f.root,
    "throw new Error('intentional install failure');"
  );
  const manifest = readFileSync(join(project, "package.json"), "utf8");
  const lock = readFileSync(join(project, "bun.lock"), "utf8");
  assert.throws(
    () => installSdk(f.artifacts, project, f.revision),
    /intentional install failure/
  );
  assert.equal(readFileSync(join(project, "package.json"), "utf8"), manifest);
  assert.equal(readFileSync(join(project, "bun.lock"), "utf8"), lock);
});

test("wrong revision, damaged archive and wrong installed version are rejected", (t) => {
  const f = fixture(t);
  const artifact = packSdk(f.sdk, f.artifacts, f.revision);
  const project = consumerProject(f.root);
  const manifest = readFileSync(join(project, "package.json"), "utf8");
  assert.throws(
    () => installSdk(f.artifacts, project, "0".repeat(40)),
    /Invalid SDK evaluation artifact/
  );
  const archive = readFileSync(join(f.artifacts, artifact.filename));
  writeFileSync(join(f.artifacts, artifact.filename), "damaged");
  assert.throws(
    () => installSdk(f.artifacts, project, f.revision),
    /SDK tarball integrity differs/
  );
  assert.equal(readFileSync(join(project, "package.json"), "utf8"), manifest);
  writeFileSync(join(f.artifacts, artifact.filename), archive);
  writeFileSync(
    join(f.artifacts, "sdk-artifact.json"),
    JSON.stringify({ ...artifact, version: "99.0.0" })
  );
  assert.throws(
    () => installSdk(f.artifacts, project, f.revision),
    /Installed SDK version differs/
  );
});

function fixture(t: { after: (cleanup: () => void) => void }) {
  const root = mkdtempSync(join(tmpdir(), "sdk-evaluation-test-"));
  t.after(() => rmSync(root, { recursive: true, force: true }));
  const sdk = join(root, "sdk");
  const expo = join(sdk, "expo");
  mkdirSync(expo, { recursive: true });
  writeFileSync(
    join(expo, "package.json"),
    JSON.stringify({
      name: "@relayphone/ios-voip-sdk",
      version: "0.3.0",
      private: true,
      scripts: { prepack: "node prepare.ts" },
      files: ["ios"],
    })
  );
  writeFileSync(
    join(expo, "package-lock.json"),
    JSON.stringify({
      name: "@relayphone/ios-voip-sdk",
      version: "0.3.0",
      lockfileVersion: 3,
      packages: { "": { name: "@relayphone/ios-voip-sdk", version: "0.3.0" } },
    })
  );
  writeFileSync(
    join(expo, "prepare.ts"),
    `
    import { mkdirSync, writeFileSync } from 'node:fs';
    mkdirSync('ios/Vendor', { recursive: true });
    writeFileSync('ios/Vendor/Prepared.swift', '// prepared during npm pack');
  `
  );
  const git = (...args: string[]) =>
    execFileSync("git", args, {
      cwd: sdk,
      encoding: "utf8",
      timeout: 10_000,
    }).trim();
  git("init", "--quiet");
  git("add", ".");
  git(
    "-c",
    "user.name=Package Test",
    "-c",
    "user.email=package@example.invalid",
    "commit",
    "--quiet",
    "-m",
    "Package fixture"
  );
  return {
    root,
    sdk,
    expo,
    revision: git("rev-parse", "HEAD"),
    artifacts: join(root, "artifacts"),
  };
}

test("packing a full checkout runs prepack and creates a verified reusable artifact", (t) => {
  const f = fixture(t);
  const artifact = packSdk(f.sdk, f.artifacts, f.revision);
  assert.equal(artifact.revision, f.revision);
  assert.equal(artifact.version, "0.3.0");
  assert.equal(
    readFileSync(join(f.expo, "ios/Vendor/Prepared.swift"), "utf8"),
    "// prepared during npm pack"
  );
  assert.deepEqual(readSdkArtifact(f.artifacts, f.revision), artifact);
  const contents = execFileSync(
    "tar",
    ["-tzf", join(f.artifacts, artifact.filename)],
    { encoding: "utf8", timeout: 10_000 }
  );
  assert.match(contents, /package\/ios\/Vendor\/Prepared.swift/);
});

test("a matching HEAD cannot hide modified SDK sources", (t) => {
  const f = fixture(t);
  writeFileSync(
    join(f.expo, "prepare.ts"),
    "throw new Error('modified checkout');"
  );
  assert.throws(
    () => packSdk(f.sdk, f.artifacts, f.revision),
    /SDK checkout has local changes/
  );
});

test("packing rejects a checkout at a different revision before installing", (t) => {
  const f = fixture(t);
  assert.throws(
    () => packSdk(f.sdk, f.artifacts, "0".repeat(40)),
    /SDK checkout must be at 0000000000000000000000000000000000000000/
  );
});

test("an artifact cannot be reused with a different revision or altered tarball", (t) => {
  const f = fixture(t);
  const artifact = packSdk(f.sdk, f.artifacts, f.revision);
  assert.throws(
    () => readSdkArtifact(f.artifacts, "0".repeat(40)),
    /Invalid SDK evaluation artifact/
  );
  writeFileSync(join(f.artifacts, artifact.filename), "tampered");
  assert.throws(
    () => readSdkArtifact(f.artifacts, f.revision),
    /integrity differs/
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
        version: "0.3.0",
        exports: {
          ".": "./index.js",
          "./package.json": "./package.json",
          "./app.plugin": "./app.plugin.js",
          "./plugin": "./app.plugin.js",
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
        "@relayphone/ios-voip-sdk": "0.3.0",
        "expo-callkit-telecom": "0.3.0",
      },
    })
  );
  const sdk = install("@relayphone/ios-voip-sdk", {
    apple: { modules: ["VoIPConversationsModule"] },
  });
  install("expo-callkit-telecom", {
    android: {
      modules: ["expo.modules.callkittelecom.ExpoCallKitTelecomModule"],
    },
  });
  return { consumer, sdk };
}

test("installed packages must expose working plugins and their native registration payload", (t) => {
  const { consumer, sdk } = installedConsumer(t);
  assert.equal(inspectInstalledPackages(consumer, "0.3.0").sdkInstallations, 1);
  writeFileSync(
    join(sdk, "expo-module.config.json"),
    JSON.stringify({ apple: { modules: [] } })
  );
  assert.throws(
    () => inspectInstalledPackages(consumer, "0.3.0"),
    /SDK Apple module registration is missing/
  );
  rmSync(join(sdk, "expo-module.config.json"));
  assert.throws(() => inspectInstalledPackages(consumer, "0.3.0"), /ENOENT/);
});

test("a no-op plugin cannot borrow mods from another valid plugin", (t) => {
  const { consumer } = installedConsumer(
    t,
    "module.exports = config => config;"
  );
  assert.throws(
    () => inspectInstalledPackages(consumer, "0.3.0"),
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
  assert.deepEqual(prepared.expo.autolinking.exclude, ["expo-callkit-telecom"]);
  assert.equal(readFileSync(join(project, "index.ts"), "utf8"), "export {};\n");
});
