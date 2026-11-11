import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { createHash } from "node:crypto";
import {
  mkdirSync,
  mkdtempSync,
  readFileSync,
  rmSync,
  writeFileSync,
} from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { test } from "node:test";

import {
  archiveFiles,
  checkFiles,
  checkTag,
  packRelease,
  publishRelease,
  releaseNotes,
  releaseVersion,
  requireHostedCI,
  verifyChecksum,
} from "./release.ts";

const version = "2.0.0-alpha.1";
const tag = `v${version}`;
const filename = `expo-callkit-telecom-${version}.tgz`;
const source = "a".repeat(40);
const runUrl =
  "https://github.com/relayphone/expo-callkit-telecom/actions/runs/123";
const changelog = `# Changelog\n\n## Unreleased\n\nFuture work.\n\n## ${version} (2026-11-11)\n\nFirst release.\n\n### Features\n\n* Calls.\n\n## 0.5.0\n\nOld work.\n`;
const hosted = {
  GITHUB_ACTIONS: "true",
  RUNNER_ENVIRONMENT: "github-hosted",
  RUNNER_OS: "Linux",
  GITHUB_REPOSITORY: "relayphone/expo-callkit-telecom",
  GITHUB_EVENT_NAME: "push",
  GITHUB_REF: `refs/tags/${tag}`,
  GITHUB_SHA: source,
  GITHUB_RUN_ID: "123",
};
const publicFiles = [
  "package.json",
  "README.md",
  "CHANGELOG.md",
  "build/index.js",
  "build/index.d.ts",
  "build/types.d.ts",
  "app.plugin.js",
  "expo-module.config.json",
  "android/build.gradle",
  "android/src/main/AndroidManifest.xml",
  "android/src/main/java/expo/modules/callkittelecom/ExpoCallKitTelecomModule.kt",
  "android/src/main/java/expo/modules/callkittelecom/conversations/AnotherCall.kt",
  "android/src/livekit/java/expo/modules/callkittelecom/conversations/BuiltInMedia.kt",
  "android/src/withoutLivekit/java/expo/modules/callkittelecom/conversations/BuiltInMedia.kt",
  ...[
    "withConversations",
    "composedPlugin",
    "androidNativeMedia",
    "androidOptions",
  ].flatMap((name) => [`plugin/build/${name}.js`, `plugin/build/${name}.d.ts`]),
];

function fixture(
  t: { after: (cleanup: () => void) => void },
  packageVersion = version
) {
  const root = mkdtempSync(join(tmpdir(), "release-test-"));
  t.after(() => rmSync(root, { recursive: true, force: true }));
  for (const file of publicFiles) {
    mkdirSync(dirname(join(root, file)), { recursive: true });
    writeFileSync(join(root, file), "fixture\n");
  }
  writeFileSync(
    join(root, "package.json"),
    JSON.stringify({
      name: "expo-callkit-telecom",
      version: packageVersion,
      files: [
        "README.md",
        "CHANGELOG.md",
        "build/index.js",
        "build/index.d.ts",
        "build/types.d.ts",
        "android",
        "plugin/build/*",
        "app.plugin.js",
        "expo-module.config.json",
      ],
    })
  );
  writeFileSync(
    join(root, "CHANGELOG.md"),
    changelog.replaceAll(version, packageVersion)
  );
  return { root, output: join(root, "output") };
}

function git(root: string, args: string[]): string {
  const result = spawnSync(
    "git",
    [
      "-c",
      "user.name=Release Test",
      "-c",
      "user.email=test@example.invalid",
      "-c",
      "commit.gpgsign=false",
      "-c",
      "tag.gpgsign=false",
      ...args,
    ],
    { cwd: root, encoding: "utf8", timeout: 10_000 }
  );
  assert.ifError(result.error);
  assert.equal(result.status, 0, result.stderr);
  return result.stdout.trim();
}

function tagged(root: string, annotated = true): string {
  const release = releaseVersion(
    JSON.parse(readFileSync(join(root, "package.json"), "utf8")).version
  );
  git(root, ["init", "--quiet", "--initial-branch=v2"]);
  git(root, ["add", "."]);
  git(root, ["commit", "--quiet", "-m", "base"]);
  git(root, ["commit", "--quiet", "--allow-empty", "-m", "release"]);
  git(root, ["update-ref", "refs/remotes/origin/v2", "HEAD"]);
  git(
    root,
    annotated
      ? ["tag", "-a", release.tag, "-m", release.version]
      : ["tag", release.tag]
  );
  return git(root, ["rev-parse", "HEAD"]);
}

test("v2 SemVer distinguishes stable, prerelease and build metadata", () => {
  assert.deepEqual(releaseVersion(version), {
    version,
    tag,
    filename,
    prerelease: true,
  });
  for (const value of ["2.0.0-beta.2", "2.0.0-rc.1+build.3"])
    assert.equal(releaseVersion(value).prerelease, true);
  for (const value of ["2.0.0", "2.1.3+build-with-dashes"])
    assert.equal(releaseVersion(value).prerelease, false);
  for (const value of [
    "1.0.0",
    "3.0.0",
    "2.00.0",
    "2.0.0-alpha.01",
    "2.0.0-",
    "2.0.0\n",
    "v2.0.0",
  ])
    assert.throws(() => releaseVersion(value));
});

test("annotated package version tags contained in v2 are accepted", (t) => {
  const f = fixture(t);
  const sha = tagged(f.root);
  assert.equal(checkTag(f.root, tag, sha), sha);
});

test("lightweight tags and version or source mismatches fail", (t) => {
  const f = fixture(t);
  const sha = tagged(f.root, false);
  assert.throws(() => checkTag(f.root, tag, sha), /must be annotated/);
  assert.throws(() => checkTag(f.root, "v2.0.0-beta.1", sha), /must match/);
  git(f.root, ["tag", "-d", tag]);
  git(f.root, ["tag", "-a", tag, "-m", version]);
  assert.throws(
    () => checkTag(f.root, tag, source),
    /differs from event source/
  );
  git(f.root, ["commit", "--quiet", "--allow-empty", "-m", "later"]);
  assert.throws(() => checkTag(f.root, tag, sha), /Checkout differs/);
});

test("an annotated tag outside v2 is rejected", (t) => {
  const f = fixture(t);
  const sha = tagged(f.root);
  git(f.root, ["update-ref", "refs/remotes/origin/v2", "HEAD~1"]);
  assert.throws(() => checkTag(f.root, tag, sha), /git failed/);
});

test("archive paths must stay under the package root", () => {
  assert.deepEqual(
    archiveFiles("package/build/index.js\npackage/README.md\n"),
    ["README.md", "build/index.js"]
  );
  for (const path of [
    "README.md",
    "package/../escape",
    "package/..",
    "package/./file",
    "package//file",
    "package/a\\b",
    "package/../../escape/",
    "other/",
  ])
    assert.throws(() => archiveFiles(path));
});

test("packed files must include the entire allowlist, without extras or duplicates", (t) => {
  const f = fixture(t);
  checkFiles(f.root, publicFiles);
  writeFileSync(join(f.root, "LICENSE"), "existing license fixture");
  checkFiles(f.root, [...publicFiles, "LICENSE"]);
  rmSync(join(f.root, "LICENSE"));
  assert.throws(
    () =>
      checkFiles(
        f.root,
        publicFiles.filter((file) => !file.endsWith("AnotherCall.kt"))
      ),
    /differs from package inputs/
  );
  for (const file of [
    "docs/guide.md",
    "example/app.ts",
    "test/calls.test.ts",
    "android/src/main/__tests__/call.kt",
  ])
    assert.throws(
      () => checkFiles(f.root, [...publicFiles, file]),
      /Unexpected packed file/
    );
  assert.throws(
    () => checkFiles(f.root, [...publicFiles, "README.md"]),
    /Duplicate/
  );
  rmSync(join(f.root, "build/index.js"));
  assert.throws(() => checkFiles(f.root, publicFiles), /Missing package input/);
});

test("checksums reject corrupt archives, wrong names, malformed and duplicate records", () => {
  const bytes = Buffer.from("test archive");
  const digest = createHash("sha256").update(bytes).digest("hex");
  const sums = `${digest}  ${filename}\n`;
  assert.equal(verifyChecksum(bytes, sums, filename), digest);
  assert.throws(() => verifyChecksum(Buffer.from("corrupt"), sums, filename));
  for (const value of [
    "",
    `${digest}  other.tgz\n`,
    `${digest} ${filename}\n`,
    sums + sums,
  ])
    assert.throws(() => verifyChecksum(bytes, value, filename));
});

test("notes contain exactly the chosen changelog, source, run and install URL", () => {
  const notes = releaseNotes(changelog, version, source, runUrl);
  assert.match(notes, /First release\./);
  assert.match(notes, /### Features/);
  assert.ok(notes.includes(source) && notes.includes(runUrl));
  assert.ok(notes.includes(`releases/download/${tag}/${filename}`));
  assert.doesNotMatch(notes, /Future work|Old work|Unreleased/);
  assert.throws(
    () => releaseNotes(changelog, "2.0.0-beta.1", source, runUrl),
    /exactly one/
  );
  assert.throws(
    () =>
      releaseNotes(
        changelog + `\n## ${version}\nDuplicate`,
        version,
        source,
        runUrl
      ),
    /exactly one/
  );
});

test("production packing rejects local, self-hosted, non-Linux and foreign-repository environments", () => {
  requireHostedCI(hosted);
  for (const override of [
    { GITHUB_ACTIONS: "false" },
    { RUNNER_ENVIRONMENT: "self-hosted" },
    { RUNNER_OS: "macOS" },
    { GITHUB_REPOSITORY: "other/repo" },
  ])
    assert.throws(() => requireHostedCI({ ...hosted, ...override }));
});

function commands(root: string, output: string, failSubject?: string) {
  const archiveName = releaseVersion(
    JSON.parse(readFileSync(join(root, "package.json"), "utf8")).version
  ).filename;
  const calls: string[][] = [];
  const run = (program: string, args: string[]): string => {
    calls.push([program, ...args]);
    if (program === "git") return git(root, args);
    if (program === "npm") {
      writeFileSync(join(output, archiveName), "fixture archive");
      return "[]";
    }
    if (program === "tar")
      return publicFiles.map((file) => `package/${file}`).join("\n");
    assert.equal(program, "gh");
    if (
      args[0] === "attestation" &&
      args[2] === join(output, failSubject ?? "")
    )
      throw new Error("Attestation rejected");
    return "";
  };
  return { calls, run };
}

test("packing records the actual file list and verified checksum in CI evidence", (t) => {
  const f = fixture(t);
  const summary = join(f.root, "summary.md");
  const fake = commands(f.root, f.output);
  packRelease(
    f.root,
    f.output,
    { ...hosted, GITHUB_STEP_SUMMARY: summary },
    fake.run
  );
  assert.deepEqual(
    readFileSync(join(f.output, "package-files.txt"), "utf8")
      .trim()
      .split("\n"),
    publicFiles.toSorted()
  );
  const sums = readFileSync(join(f.output, "SHA256SUMS"), "utf8");
  verifyChecksum(readFileSync(join(f.output, filename)), sums, filename);
  assert.ok(readFileSync(summary, "utf8").includes(sums));
  assert.throws(
    () => packRelease(f.root, f.output, hosted, fake.run),
    /must be empty/
  );
});

test("publication verifies both subjects with all identity constraints before release creation", (t) => {
  const f = fixture(t);
  const sha = tagged(f.root);
  const fake = commands(f.root, f.output);
  packRelease(f.root, f.output, hosted, fake.run);
  const bundle = join(f.root, "bundle.json");
  writeFileSync(bundle, "test bundle");
  publishRelease(
    f.root,
    f.output,
    bundle,
    { ...hosted, GITHUB_SHA: sha },
    fake.run
  );
  const gh = fake.calls.filter((call) => call[0] === "gh");
  assert.equal(gh.length, 3);
  for (const [index, subject] of [filename, "SHA256SUMS"].entries()) {
    const call = gh[index]!;
    assert.deepEqual(call.slice(0, 4), [
      "gh",
      "attestation",
      "verify",
      join(f.output, subject),
    ]);
    for (const value of [
      "--signer-workflow",
      "relayphone/expo-callkit-telecom/.github/workflows/v2-release.yml",
      "--source-ref",
      hosted.GITHUB_REF,
      "--source-digest",
      sha,
      "--deny-self-hosted-runners",
      "--bundle",
    ])
      assert.ok(call.includes(value));
  }
  assert.deepEqual(gh[2]!.slice(0, 4), ["gh", "release", "create", tag]);
  assert.deepEqual(gh[2]!.slice(4, 7), [
    join(f.output, filename),
    join(f.output, "SHA256SUMS"),
    join(f.output, "attestation.sigstore.json"),
  ]);
  assert.ok(gh[2]!.includes("--prerelease") && gh[2]!.includes("--verify-tag"));
  assert.ok(
    readFileSync(join(f.output, "release-notes.md"), "utf8").includes(sha)
  );
});

test("failure of either attestation prevents publication", (t) => {
  for (const subject of [filename, "SHA256SUMS"]) {
    const f = fixture(t);
    const sha = tagged(f.root);
    const fake = commands(f.root, f.output, subject);
    packRelease(f.root, f.output, hosted, fake.run);
    const bundle = join(f.root, "bundle.json");
    writeFileSync(bundle, "test bundle");
    assert.throws(
      () =>
        publishRelease(
          f.root,
          f.output,
          bundle,
          { ...hosted, GITHUB_SHA: sha },
          fake.run
        ),
      /Attestation rejected/
    );
    assert.ok(
      !fake.calls.some((call) => call[0] === "gh" && call[1] === "release")
    );
  }
});

test("a changed archive fails checksum verification before attestation or publication", (t) => {
  const f = fixture(t);
  const sha = tagged(f.root);
  const fake = commands(f.root, f.output);
  packRelease(f.root, f.output, hosted, fake.run);
  writeFileSync(join(f.output, filename), "corrupt archive");
  const bundle = join(f.root, "bundle.json");
  writeFileSync(bundle, "test bundle");
  assert.throws(
    () =>
      publishRelease(
        f.root,
        f.output,
        bundle,
        { ...hosted, GITHUB_SHA: sha },
        fake.run
      ),
    /SHA256SUMS differs/
  );
  assert.ok(!fake.calls.some((call) => call[0] === "gh"));
});

test("stable versions publish without the prerelease flag", (t) => {
  const f = fixture(t, "2.0.0");
  const sha = tagged(f.root);
  const fake = commands(f.root, f.output);
  packRelease(f.root, f.output, hosted, fake.run);
  const bundle = join(f.root, "bundle.json");
  writeFileSync(bundle, "test bundle");
  publishRelease(
    f.root,
    f.output,
    bundle,
    { ...hosted, GITHUB_SHA: sha, GITHUB_REF: "refs/tags/v2.0.0" },
    fake.run
  );
  const publish = fake.calls.find(
    (call) => call[0] === "gh" && call[1] === "release"
  );
  assert.ok(publish && !publish.includes("--prerelease"));
});
