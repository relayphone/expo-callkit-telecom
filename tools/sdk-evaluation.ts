import { spawnSync } from "node:child_process";
import { createHash } from "node:crypto";
import {
  copyFileSync,
  mkdirSync,
  readFileSync,
  readdirSync,
  writeFileSync,
} from "node:fs";
import { createRequire } from "node:module";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

export const sdkVersion = "0.4.0";
export const sdkFilename = "relayphone-ios-voip-sdk-0.4.0.tgz";
export const sdkAssetUrl = `https://github.com/relayphone/ios-voip-sdk/releases/download/v${sdkVersion}/${sdkFilename}`;
export const sdkChecksumsUrl = `https://github.com/relayphone/ios-voip-sdk/releases/download/v${sdkVersion}/SHA256SUMS`;
const sdkName = "@relayphone/ios-voip-sdk";

interface SdkArtifact {
  version: string;
  filename: string;
  url: string;
  sha256: string;
  integrity: string;
}

function command(
  command: string,
  args: readonly string[],
  cwd: string,
  timeout = 120_000
): string {
  const result = spawnSync(command, args, {
    cwd,
    encoding: "utf8",
    timeout,
    maxBuffer: 16 * 1024 * 1024,
  });
  if (result.error || result.status !== 0) {
    throw new Error(
      `${command} failed: ${
        result.error?.message ?? (result.stdout + result.stderr).slice(-4000)
      }`
    );
  }
  return result.stdout.trim();
}

function json(path: string): unknown {
  return JSON.parse(readFileSync(path, "utf8"));
}
function object(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

function verifySdk(bytes: Uint8Array, checksums: string): SdkArtifact {
  const matches = checksums
    .split(/\r?\n/)
    .map((line) => /^([a-f0-9]{64}) [ *](.+)$/i.exec(line))
    .filter((match) => match?.[2] === sdkFilename);
  const expected =
    matches.length === 1 ? matches[0]?.[1]?.toLowerCase() : undefined;
  if (!expected)
    throw new Error("SHA256SUMS must contain exactly one SDK asset checksum");
  const sha256 = createHash("sha256").update(bytes).digest("hex");
  if (sha256 !== expected)
    throw new Error("SDK release asset SHA-256 differs from SHA256SUMS");
  return {
    version: sdkVersion,
    filename: sdkFilename,
    url: sdkAssetUrl,
    sha256,
    integrity: `sha512-${createHash("sha512").update(bytes).digest("base64")}`,
  };
}

async function fetchAsset(url: string): Promise<Uint8Array> {
  const response = await fetch(url, { signal: AbortSignal.timeout(120_000) });
  if (!response.ok)
    throw new Error(
      `SDK release download failed: HTTP ${response.status} (${url})`
    );
  return new Uint8Array(await response.arrayBuffer());
}

/** Downloads the released SDK and verifies its checksum before retaining the archive. */
export async function downloadSdk(
  destination: string,
  download: (url: string) => Promise<Uint8Array> = fetchAsset
): Promise<SdkArtifact> {
  mkdirSync(destination, { recursive: true });
  if (readdirSync(destination).length)
    throw new Error("SDK artifact directory must be empty");
  const [bytes, checksumBytes] = await Promise.all([
    download(sdkAssetUrl),
    download(sdkChecksumsUrl),
  ]);
  const checksums = new TextDecoder().decode(checksumBytes);
  const artifact = verifySdk(bytes, checksums);
  writeFileSync(join(destination, sdkFilename), bytes);
  writeFileSync(join(destination, "SHA256SUMS"), checksums);
  return artifact;
}

/** Rechecks the release checksum whenever another job consumes the downloaded archive. */
export function readSdkArtifact(directory: string): SdkArtifact {
  return verifySdk(
    readFileSync(join(directory, sdkFilename)),
    readFileSync(join(directory, "SHA256SUMS"), "utf8")
  );
}

/** Binds Bun's URL resolution to the archive already verified against the release. */
export function verifySdkLock(
  project: string,
  expectedIntegrity: string
): void {
  const entry: unknown = JSON.parse(
    command(
      "bun",
      [
        "-e",
        'console.log(JSON.stringify(Bun.JSONC.parse(await Bun.file("bun.lock").text()).packages["@relayphone/ios-voip-sdk"] ?? null))',
      ],
      project
    )
  );
  if (
    !Array.isArray(entry) ||
    entry[0] !== `${sdkName}@${sdkAssetUrl}` ||
    entry.at(-1) !== expectedIntegrity
  )
    throw new Error(
      "Locked SDK URL or integrity differs from the verified release asset"
    );
}

type InstallMode = "frozen" | "temporary-fork";

/** Installs the URL dependency from its checked-in lock after release verification. */
export function installSdk(
  artifactDirectory: string,
  project: string,
  mode: InstallMode = "frozen"
): SdkArtifact {
  const artifact = readSdkArtifact(artifactDirectory);
  const manifest = join(project, "package.json");
  const pkg = json(manifest);
  if (
    !object(pkg) ||
    !object(pkg.dependencies) ||
    pkg.dependencies[sdkName] !== sdkAssetUrl
  )
    throw new Error("Consumer SDK dependency must use the release asset URL");
  if (mode === "temporary-fork") {
    const fork = pkg.dependencies["expo-callkit-telecom"];
    if (
      typeof fork !== "string" ||
      !fork.startsWith("file:") ||
      !fork.endsWith(".tgz")
    )
      throw new Error(
        "Temporary-fork installation requires a local fork tarball"
      );
  }
  verifySdkLock(project, artifact.integrity);
  command(
    "bun",
    ["install", mode === "frozen" ? "--frozen-lockfile" : "--no-save"],
    project,
    600_000
  );
  const require = createRequire(resolve(manifest));
  const installed = json(require.resolve(`${sdkName}/package.json`));
  if (
    !object(installed) ||
    installed.name !== sdkName ||
    installed.version !== artifact.version
  )
    throw new Error("Installed SDK version differs from its verified release");
  return artifact;
}

/** Checks installed public entries and native registration without executing native code. */
export function inspectInstalledPackages(
  consumer: string,
  expectedVersion: string
) {
  const require = createRequire(join(consumer, "package.json"));
  const sdkRoot = dirname(require.resolve(`${sdkName}/package.json`));
  const forkRoot = dirname(
    require.resolve("expo-callkit-telecom/package.json")
  );
  const sdk = json(join(sdkRoot, "package.json"));
  const fork = json(join(forkRoot, "package.json"));
  if (
    !object(sdk) ||
    sdk.version !== expectedVersion ||
    !object(fork) ||
    typeof fork.version !== "string"
  ) {
    throw new Error(
      "Installed package versions differ from the evaluation artifacts"
    );
  }
  require.resolve(sdkName);
  require.resolve(`${sdkName}/plugin`);
  require.resolve("expo-callkit-telecom");
  for (const name of [sdkName, "expo-callkit-telecom"]) {
    const config = {
      name: "PackageEvaluation",
      slug: "package-evaluation",
      ios: { bundleIdentifier: "com.example.packageevaluation" },
    };
    const entry: unknown = require(`${name}/app.plugin`);
    const plugin: unknown = object(entry) ? entry.default : entry;
    if (typeof plugin !== "function")
      throw new Error(`${name} has no config plugin`);
    const configured: unknown = plugin(config, { mediaAdapter: "none" });
    if (!object(configured) || !object(configured.mods))
      throw new Error(`${name} plugin did not configure project mods`);
  }
  const sdkNative = json(join(sdkRoot, "expo-module.config.json"));
  if (
    !object(sdkNative) ||
    !object(sdkNative.apple) ||
    !Array.isArray(sdkNative.apple.modules) ||
    !sdkNative.apple.modules.includes("VoIPConversationsModule")
  ) {
    throw new Error("SDK Apple module registration is missing");
  }
  const forkNative = json(join(forkRoot, "expo-module.config.json"));
  if (
    !object(forkNative) ||
    !object(forkNative.android) ||
    JSON.stringify(forkNative.platforms) !== JSON.stringify(["android"]) ||
    "apple" in forkNative ||
    !Array.isArray(forkNative.android.modules) ||
    !forkNative.android.modules.includes(
      "expo.modules.callkittelecom.ExpoCallKitTelecomModule"
    )
  ) {
    throw new Error("Android module registration is missing");
  }
  if (
    !object(fork.exports) ||
    !object(fork.exports["./types"]) ||
    typeof fork.exports["./types"].types !== "string" ||
    "default" in fork.exports["./types"]
  )
    throw new Error("The types entry must be type-only");
  const installations = new Set(
    command("npm", ["ls", sdkName, "--all", "--parseable"], consumer)
      .split("\n")
      .filter(Boolean)
  );
  if (installations.size !== 1 || !installations.has(sdkRoot))
    throw new Error("Expected one installed SDK package");
  return {
    sdkVersion: expectedVersion,
    forkVersion: fork.version,
    sdkInstallations: installations.size,
  };
}

/** Installs the fork tarball with the SDK release URL and checks their public entries. */
export function checkConsumer(
  artifactDirectory: string,
  forkCheckout: string,
  consumer: string
) {
  const artifact = readSdkArtifact(artifactDirectory);
  mkdirSync(consumer);
  const packed = join(consumer, "packages");
  mkdirSync(packed);
  command("npm", ["pack", "--pack-destination", packed], forkCheckout, 300_000);
  const tarballs = readdirSync(packed).filter((name) => name.endsWith(".tgz"));
  if (tarballs.length !== 1 || !tarballs[0])
    throw new Error("Expected exactly one Android package tarball");
  writeFileSync(
    join(consumer, "package.json"),
    `${JSON.stringify(
      {
        name: "conversations-package-evaluation",
        version: "0.0.0",
        private: true,
        dependencies: {
          expo: "57.0.24",
          react: "19.2.3",
          "react-native": "0.86.3",
          "expo-notifications": "57.0.20",
          "expo-build-properties": "57.0.21",
          [sdkName]: sdkAssetUrl,
          "expo-callkit-telecom": `file:${join(packed, tarballs[0])}`,
        },
        devDependencies: { typescript: "6.0.3", "@types/react": "19.2.14" },
      },
      null,
      2
    )}\n`
  );
  command("npm", ["install", "--no-audit", "--no-fund"], consumer, 600_000);
  const lock = json(join(consumer, "package-lock.json"));
  const sdkLock =
    object(lock) && object(lock.packages)
      ? lock.packages[`node_modules/${sdkName}`]
      : undefined;
  if (
    !object(sdkLock) ||
    sdkLock.resolved !== sdkAssetUrl ||
    sdkLock.integrity !== artifact.integrity
  )
    throw new Error(
      "Installed URL archive differs from the verified SDK release asset"
    );
  const installed = inspectInstalledPackages(consumer, artifact.version);
  copyFileSync(
    join(forkCheckout, "test/fixtures/package-consumer/contract.ts"),
    join(consumer, "consumer.ts")
  );
  copyFileSync(
    join(forkCheckout, "test/fixtures/package-consumer/runtime.test.ts"),
    join(consumer, "runtime.test.ts")
  );
  command(
    process.execPath,
    [
      join(consumer, "node_modules/typescript/bin/tsc"),
      "--noEmit",
      "--strict",
      "--skipLibCheck",
      "--target",
      "esnext",
      "--module",
      "preserve",
      "--moduleResolution",
      "bundler",
      "--types",
      "react,node",
      "consumer.ts",
      "runtime.test.ts",
    ],
    consumer
  );
  command(process.execPath, ["--test", "runtime.test.ts"], consumer);
  return { sdkRelease: artifact.url, sdkSha256: artifact.sha256, ...installed };
}

/** Prepares the composed plugin consumer with one provider per platform. */
export function prepareIosConsumer(consumer: string, fixtures: string) {
  const path = join(consumer, "package.json");
  const pkg = json(path);
  if (!object(pkg)) throw new Error("Invalid consumer package manifest");
  writeFileSync(
    path,
    JSON.stringify({
      ...pkg,
      main: "index.ts",
    })
  );
  for (const name of ["app.config.ts", "index.ts"])
    copyFileSync(join(fixtures, name), join(consumer, name));
}

if (
  process.argv[1] &&
  resolve(process.argv[1]) === fileURLToPath(import.meta.url)
) {
  const [mode, first, second, third] = process.argv.slice(2);
  if (mode === "download-sdk" && first)
    console.log(JSON.stringify(await downloadSdk(resolve(first))));
  else if (
    mode === "install-sdk" &&
    first &&
    second &&
    (third === undefined || third === "frozen" || third === "temporary-fork")
  )
    console.log(
      JSON.stringify(
        installSdk(resolve(first), resolve(second), third ?? "frozen")
      )
    );
  else if (
    (mode === "check-consumer" || mode === "ios-consumer") &&
    first &&
    second &&
    third
  ) {
    const result = checkConsumer(
      resolve(first),
      resolve(second),
      resolve(third)
    );
    if (mode === "ios-consumer")
      prepareIosConsumer(
        resolve(third),
        resolve(second, "test/fixtures/ios-consumer")
      );
    console.log(JSON.stringify(result));
  } else
    throw new Error(
      "Use download-sdk ARTIFACT_DIRECTORY, install-sdk ARTIFACT_DIRECTORY PROJECT [frozen|temporary-fork], or check-consumer/ios-consumer ARTIFACT_DIRECTORY FORK_CHECKOUT CONSUMER_DIRECTORY"
    );
}
