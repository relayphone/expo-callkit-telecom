import { execFileSync } from "node:child_process";
import {
  appendFileSync,
  copyFileSync,
  mkdirSync,
  readFileSync,
  writeFileSync,
} from "node:fs";
import { join, resolve } from "node:path";

type Manifest = {
  dependencies: Record<string, string>;
  expo: { autolinking: { nativeModulesDir?: string } };
};
export function packedExampleManifest<T extends Manifest>(
  manifest: T,
  archive: string
) {
  const { nativeModulesDir: _checkout, ...autolinking } =
    manifest.expo.autolinking;
  return {
    ...manifest,
    dependencies: {
      ...manifest.dependencies,
      "expo-callkit-telecom": `file:${archive}`,
    },
    expo: { ...manifest.expo, autolinking },
  };
}

if (import.meta.main) {
  const [mode, directory] = process.argv.slice(2);
  if (
    !directory ||
    !["package", "instrumentation", "artifacts"].includes(mode ?? "")
  )
    throw new Error(
      "Expected package ARCHIVE_DIRECTORY, instrumentation ANDROID_PROJECT or artifacts OUTPUT_DIRECTORY"
    );
  if (mode === "package") {
    mkdirSync(directory, { recursive: true });
    const packed: { filename: string }[] = JSON.parse(
      execFileSync(
        "npm",
        [
          "pack",
          "--json",
          "--ignore-scripts",
          "--pack-destination",
          resolve(directory),
        ],
        { timeout: 120_000, encoding: "utf8" }
      )
    );
    if (packed.length !== 1 || !packed[0]?.filename.endsWith(".tgz"))
      throw new Error("Missing fork archive");
    const manifestPath = "example/client/package.json";
    writeFileSync(
      manifestPath,
      JSON.stringify(
        packedExampleManifest(
          JSON.parse(readFileSync(manifestPath, "utf8")),
          resolve(directory, packed[0].filename)
        )
      )
    );
    const tsconfig = JSON.parse(
      readFileSync("example/client/tsconfig.json", "utf8")
    );
    tsconfig.compilerOptions.paths = {};
    writeFileSync("example/client/tsconfig.json", JSON.stringify(tsconfig));
  } else if (mode === "instrumentation") {
    appendFileSync(
      join(directory, "app/build.gradle"),
      '\napply from: file("../../journeys/android-test.gradle")\n'
    );
  } else {
    mkdirSync(directory, { recursive: true });
    copyFileSync(
      "example/client/android/app/build/outputs/apk/release/app-release.apk",
      join(directory, "app-release.apk")
    );
    copyFileSync(
      "example/client/android/app/build/outputs/apk/androidTest/release/app-release-androidTest.apk",
      join(directory, "app-release-androidTest.apk")
    );
  }
}
