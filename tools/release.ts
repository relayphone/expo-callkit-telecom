import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { createHash } from "node:crypto";
import {
  appendFileSync,
  copyFileSync,
  globSync,
  lstatSync,
  mkdirSync,
  readFileSync,
  readdirSync,
  writeFileSync,
} from "node:fs";
import { join, posix, resolve } from "node:path";
import { fileURLToPath } from "node:url";

import { assertPackageFiles } from "./sdk-evaluation.ts";

const repository = "relayphone/expo-callkit-telecom";
const workflow = `${repository}/.github/workflows/v2-release.yml`;
type Run = (program: string, args: string[], cwd: string) => string;
const command: Run = (program, args, cwd) => {
  const result = spawnSync(program, args, {
    cwd,
    encoding: "utf8",
    timeout: 120_000,
  });
  assert.ifError(result.error);
  assert.equal(result.status, 0, `${program} failed: ${result.stderr}`);
  return result.stdout.trim();
};

export function releaseVersion(version: string) {
  assert.ok(!/\s/.test(version), "Unexpected whitespace in version");
  const numeric = "(?:0|[1-9][0-9]*)";
  const identifier = "(?:0|[1-9][0-9]*|[0-9A-Za-z-]*[A-Za-z-][0-9A-Za-z-]*)";
  assert.match(
    version,
    new RegExp(
      `^2\\.${numeric}\\.${numeric}(?:-${identifier}(?:\\.${identifier})*)?(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?$`
    ),
    "Expected a v2 SemVer version"
  );
  return {
    version,
    tag: `v${version}`,
    filename: `expo-callkit-telecom-${version}.tgz`,
    prerelease: version.split("+")[0]!.includes("-"),
  };
}

function manifest(root: string) {
  const value: unknown = JSON.parse(
    readFileSync(join(root, "package.json"), "utf8")
  );
  assert.ok(
    value &&
      typeof value === "object" &&
      "name" in value &&
      "version" in value &&
      "files" in value
  );
  assert.equal(value.name, "expo-callkit-telecom");
  assert.equal(typeof value.version, "string");
  assert.ok(
    Array.isArray(value.files) &&
      value.files.length > 0 &&
      value.files.every((file: unknown) => typeof file === "string")
  );
  return {
    ...releaseVersion(value.version as string),
    files: value.files as string[],
  };
}

/** Require the exact annotated version tag, checked-out source and v2 ancestry. */
export function checkTag(
  root: string,
  tag: string,
  source: string,
  run: Run = command
): string {
  assert.equal(tag, manifest(root).tag, "Tag must match package.json version");
  assert.match(source, /^[0-9a-f]{40}$/, "Expected the source commit digest");
  assert.equal(
    run("git", ["cat-file", "-t", `refs/tags/${tag}`], root),
    "tag",
    "Release tag must be annotated"
  );
  const commit = run("git", ["rev-parse", `refs/tags/${tag}^{commit}`], root);
  assert.equal(commit, source, "Tag commit differs from event source");
  assert.equal(
    run("git", ["rev-parse", "HEAD"], root),
    commit,
    "Checkout differs from tag commit"
  );
  run(
    "git",
    ["merge-base", "--is-ancestor", commit, "refs/remotes/origin/v2"],
    root
  );
  return commit;
}

export function archiveFiles(listing: string): string[] {
  const files: string[] = [];
  for (const path of listing.trim().split("\n")) {
    if (path === "package/") continue;
    assert.ok(path.startsWith("package/"), `Unexpected archive root: ${path}`);
    const relative = path.slice("package/".length).replace(/\/$/, "");
    assert.ok(
      relative &&
        relative !== "." &&
        relative !== ".." &&
        !relative.startsWith("/") &&
        !relative.includes("\\") &&
        posix.normalize(relative) === relative &&
        !relative.startsWith("../"),
      `Unsafe archive path: ${path}`
    );
    if (!path.endsWith("/")) files.push(relative);
  }
  return files.sort();
}

/** Compare the actual archive with every file selected by package.json's allowlist. */
export function checkFiles(root: string, actual: string[]): void {
  assert.equal(new Set(actual).size, actual.length, "Duplicate archive files");
  assertPackageFiles(actual);
  const expected = new Set([
    "package.json",
    "README.md",
    "CHANGELOG.md",
    ...globSync("LICENSE", { cwd: root }),
  ]);
  for (const entry of manifest(root).files) {
    assert.ok(
      !entry.startsWith("/") && !entry.split("/").includes(".."),
      "Unsafe files entry"
    );
    let count = 0;
    for (const path of globSync([entry, `${entry}/**/*`], { cwd: root })) {
      const stat = lstatSync(join(root, path));
      assert.ok(!stat.isSymbolicLink(), `Symlink in package inputs: ${path}`);
      if (stat.isFile()) {
        expected.add(path);
        count++;
      }
    }
    assert.ok(count > 0, `Missing package input: ${entry}`);
  }
  assert.deepEqual(
    actual.toSorted(),
    [...expected].sort(),
    "Packed file list differs from package inputs"
  );
}

export function verifyChecksum(
  bytes: Uint8Array,
  sums: string,
  filename: string
): string {
  const checksum = createHash("sha256").update(bytes).digest("hex");
  assert.equal(
    sums,
    `${checksum}  ${filename}\n`,
    "SHA256SUMS differs from the archive"
  );
  return checksum;
}

export function releaseNotes(
  changelog: string,
  version: string,
  source: string,
  runUrl: string
): string {
  const release = releaseVersion(version);
  assert.match(source, /^[0-9a-f]{40}$/);
  assert.match(
    runUrl,
    /^https:\/\/github\.com\/relayphone\/expo-callkit-telecom\/actions\/runs\/[0-9]+$/
  );
  const headings = [...changelog.matchAll(/^## (.+)$/gm)];
  const matches = headings.filter(
    (heading) => heading[1]!.split(/[ (]/)[0] === version
  );
  assert.equal(
    matches.length,
    1,
    "Expected exactly one version section in CHANGELOG"
  );
  const start = matches[0]!.index;
  const end =
    headings.find((heading) => heading.index > start)?.index ??
    changelog.length;
  return `${changelog
    .slice(start, end)
    .trim()}\n\nSource commit: \`${source}\`. [CI run](${runUrl}).\n\nInstall in \`package.json\`:\n\n\`\`\`json\n"expo-callkit-telecom": "https://github.com/${repository}/releases/download/${
    release.tag
  }/${release.filename}"\n\`\`\`\n`;
}

/** Build artifacts are prepared only by the GitHub-hosted Linux workflow. */
export function requireHostedCI(env: NodeJS.ProcessEnv): void {
  assert.equal(
    env.GITHUB_ACTIONS,
    "true",
    "Release packaging requires GitHub Actions"
  );
  assert.equal(
    env.RUNNER_ENVIRONMENT,
    "github-hosted",
    "Release packaging requires a GitHub-hosted runner"
  );
  assert.equal(env.RUNNER_OS, "Linux", "Release packaging requires Linux");
  assert.equal(
    env.GITHUB_REPOSITORY,
    repository,
    "Unexpected release repository"
  );
}

export function verifyPackage(
  root: string,
  output: string,
  run: Run = command
): void {
  const { filename } = manifest(root);
  verifyChecksum(
    readFileSync(join(output, filename)),
    readFileSync(join(output, "SHA256SUMS"), "utf8"),
    filename
  );
  checkFiles(
    root,
    archiveFiles(run("tar", ["-tzf", join(output, filename)], root))
  );
}

export function packRelease(
  root: string,
  output: string,
  env: NodeJS.ProcessEnv,
  run: Run = command
): void {
  requireHostedCI(env);
  mkdirSync(output, { recursive: true });
  assert.equal(readdirSync(output).length, 0, "Release output must be empty");
  const release = manifest(root);
  run(
    "npm",
    ["pack", "--json", "--ignore-scripts", "--pack-destination", output],
    root
  );
  assert.deepEqual(
    readdirSync(output),
    [release.filename],
    "Unexpected packed archive"
  );
  const files = archiveFiles(
    run("tar", ["-tzf", join(output, release.filename)], root)
  );
  checkFiles(root, files);
  const checksum = createHash("sha256")
    .update(readFileSync(join(output, release.filename)))
    .digest("hex");
  const sums = `${checksum}  ${release.filename}\n`;
  writeFileSync(join(output, "SHA256SUMS"), sums);
  writeFileSync(join(output, "package-files.txt"), files.join("\n") + "\n");
  verifyPackage(root, output, run);
  if (env.GITHUB_STEP_SUMMARY)
    appendFileSync(
      env.GITHUB_STEP_SUMMARY,
      `## Packed files\n\n\`\`\`text\n${files.join(
        "\n"
      )}\n\`\`\`\n\n## SHA256SUMS\n\n\`\`\`text\n${sums}\`\`\`\n`
    );
}

/** Verify both attested subjects before creating any public release. */
export function publishRelease(
  root: string,
  output: string,
  bundle: string,
  env: NodeJS.ProcessEnv,
  run: Run = command
): void {
  requireHostedCI(env);
  assert.equal(env.GITHUB_EVENT_NAME, "push");
  const release = manifest(root);
  assert.equal(env.GITHUB_REF, `refs/tags/${release.tag}`);
  const source = checkTag(root, release.tag, env.GITHUB_SHA ?? "", run);
  verifyPackage(root, output, run);
  const attestation = join(output, "attestation.sigstore.json");
  copyFileSync(bundle, attestation);
  for (const file of [release.filename, "SHA256SUMS"]) {
    run(
      "gh",
      [
        "attestation",
        "verify",
        join(output, file),
        "--repo",
        repository,
        "--signer-workflow",
        workflow,
        "--source-ref",
        env.GITHUB_REF,
        "--source-digest",
        source,
        "--deny-self-hosted-runners",
        "--bundle",
        attestation,
      ],
      root
    );
  }
  verifyPackage(root, output, run);
  assert.match(env.GITHUB_RUN_ID ?? "", /^[0-9]+$/);
  const notes = releaseNotes(
    readFileSync(join(root, "CHANGELOG.md"), "utf8"),
    release.version,
    source,
    `https://github.com/${repository}/actions/runs/${env.GITHUB_RUN_ID}`
  );
  const notesPath = join(output, "release-notes.md");
  writeFileSync(notesPath, notes);
  run(
    "gh",
    [
      "release",
      "create",
      release.tag,
      join(output, release.filename),
      join(output, "SHA256SUMS"),
      attestation,
      "--repo",
      repository,
      "--verify-tag",
      "--title",
      release.tag,
      "--notes-file",
      notesPath,
      ...(release.prerelease ? ["--prerelease"] : []),
    ],
    root
  );
}

if (
  process.argv[1] &&
  resolve(process.argv[1]) === fileURLToPath(import.meta.url)
) {
  try {
    const [action, argument, bundle] = process.argv.slice(2);
    const root = process.cwd();
    assert.ok(
      argument,
      "Expected check-tag TAG, pack DIR, verify DIR or publish DIR BUNDLE"
    );
    if (action === "check-tag")
      checkTag(root, argument, process.env.GITHUB_SHA ?? "");
    else if (action === "pack")
      packRelease(root, resolve(argument), process.env);
    else if (action === "verify") verifyPackage(root, resolve(argument));
    else if (action === "publish" && bundle)
      publishRelease(root, resolve(argument), bundle, process.env);
    else throw new Error("Unknown release command");
  } catch (error) {
    console.error(error instanceof Error ? error.message : error);
    process.exitCode = 1;
  }
}
