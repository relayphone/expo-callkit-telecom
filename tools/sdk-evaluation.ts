import { spawnSync } from "node:child_process";
import { createHash } from "node:crypto";
import { mkdirSync, readFileSync, readdirSync, writeFileSync } from "node:fs";
import { createRequire } from "node:module";
import { basename, dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

export const sdkRevision = "d9c6b24bd8c91a287feae71566c3a2658e68a246";
const sdkName = "@relayphone/ios-voip-sdk";
const artifactFile = "sdk-artifact.json";

interface SdkArtifact {
  repository: "relayphone/ios-voip-sdk";
  revision: string;
  version: string;
  filename: string;
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

function integrity(path: string): string {
  return `sha512-${createHash("sha512")
    .update(readFileSync(path))
    .digest("base64")}`;
}

/** Packs the complete SDK checkout, including its normal source-copying prepack hook. */
export function packSdk(
  checkout: string,
  destination: string,
  expectedRevision = sdkRevision
): SdkArtifact {
  const revision = command("git", ["rev-parse", "HEAD"], checkout);
  if (revision !== expectedRevision)
    throw new Error(`SDK checkout must be at ${expectedRevision}`);
  if (
    command("git", ["status", "--porcelain", "--untracked-files=all"], checkout)
  ) {
    throw new Error("SDK checkout has local changes");
  }
  const expo = join(checkout, "expo");
  const pkg = json(join(expo, "package.json"));
  if (!object(pkg) || pkg.name !== sdkName || typeof pkg.version !== "string") {
    throw new Error("The checkout does not contain the SDK Expo package");
  }
  mkdirSync(destination, { recursive: true });
  if (readdirSync(destination).length)
    throw new Error("SDK artifact directory must be empty");
  command("npm", ["ci", "--no-audit", "--no-fund"], expo, 600_000);
  command(
    "npm",
    ["pack", "--pack-destination", resolve(destination)],
    expo,
    300_000
  );
  const filenames = readdirSync(destination).filter((name) =>
    name.endsWith(".tgz")
  );
  if (filenames.length !== 1 || !filenames[0])
    throw new Error("Expected exactly one SDK tarball");
  const artifact: SdkArtifact = {
    repository: "relayphone/ios-voip-sdk",
    revision,
    version: pkg.version,
    filename: filenames[0],
    integrity: integrity(join(destination, filenames[0])),
  };
  writeFileSync(
    join(destination, artifactFile),
    `${JSON.stringify(artifact, null, 2)}\n`
  );
  return artifact;
}

/** Validates the downloaded tarball before a consumer installs it. */
export function readSdkArtifact(
  directory: string,
  expectedRevision = sdkRevision
): SdkArtifact {
  const value = json(join(directory, artifactFile));
  if (
    !object(value) ||
    value.repository !== "relayphone/ios-voip-sdk" ||
    value.revision !== expectedRevision ||
    typeof value.version !== "string" ||
    typeof value.filename !== "string" ||
    basename(value.filename) !== value.filename ||
    !value.filename.endsWith(".tgz") ||
    typeof value.integrity !== "string"
  ) {
    throw new Error("Invalid SDK evaluation artifact manifest");
  }
  if (integrity(join(directory, value.filename)) !== value.integrity)
    throw new Error("SDK tarball integrity differs");
  return {
    repository: "relayphone/ios-voip-sdk",
    revision: expectedRevision,
    version: value.version,
    filename: value.filename,
    integrity: value.integrity,
  };
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
    !Array.isArray(forkNative.android.modules) ||
    !forkNative.android.modules.includes(
      "expo.modules.callkittelecom.ExpoCallKitTelecomModule"
    )
  ) {
    throw new Error("Android module registration is missing");
  }
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

/** Installs both packed distributions outside their checkouts and typechecks their public entries. */
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
        },
        devDependencies: { typescript: "6.0.3", "@types/react": "19.2.14" },
      },
      null,
      2
    )}\n`
  );
  command(
    "npm",
    [
      "install",
      "--no-audit",
      "--no-fund",
      join(artifactDirectory, artifact.filename),
      join(packed, tarballs[0]),
    ],
    consumer,
    600_000
  );
  const installed = inspectInstalledPackages(consumer, artifact.version);
  writeFileSync(
    join(consumer, "consumer.ts"),
    `
import { startConversation, setConversationHandler, reportIncomingConversation } from "@relayphone/ios-voip-sdk";
import type { ConversationHandler, StartConversationOptions, IncomingCallPayload } from "@relayphone/ios-voip-sdk/types";
import { getConversations as getAndroidConversations } from "expo-callkit-telecom";
import type { Caller } from "expo-callkit-telecom/types";

const handler: ConversationHandler = { start: async (call, { signal }) => { void call.id; void signal.aborted; } };
const options: StartConversationOptions = { id: "server-call-id", to: { id: "alice" }, video: false };
const caller: Caller = { handleType: "generic", value: "alice", displayName: "Alice" };
const payload: IncomingCallPayload = { eventId: "incoming-event", serverCallId: "server-call-id", caller, hasVideo: false };
setConversationHandler(handler);
void startConversation(options);
void reportIncomingConversation(payload);
void getAndroidConversations();
`
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
      "react",
      "consumer.ts",
    ],
    consumer
  );
  return { sdkRevision: artifact.revision, ...installed };
}

if (
  process.argv[1] &&
  resolve(process.argv[1]) === fileURLToPath(import.meta.url)
) {
  const [mode, first, second, third] = process.argv.slice(2);
  if (mode === "revision") console.log(`revision=${sdkRevision}`);
  else if (mode === "pack-sdk" && first && second)
    console.log(JSON.stringify(packSdk(resolve(first), resolve(second))));
  else if (mode === "check-consumer" && first && second && third) {
    console.log(
      JSON.stringify(
        checkConsumer(resolve(first), resolve(second), resolve(third))
      )
    );
  } else
    throw new Error(
      "Use revision, pack-sdk SDK_CHECKOUT ARTIFACT_DIRECTORY, or check-consumer ARTIFACT_DIRECTORY FORK_CHECKOUT CONSUMER_DIRECTORY"
    );
}
