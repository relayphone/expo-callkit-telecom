import assert from "node:assert/strict";
import { spawn, spawnSync } from "node:child_process";
import {
  existsSync,
  mkdirSync,
  mkdtempSync,
  readFileSync,
  realpathSync,
  rmSync,
  writeFileSync,
} from "node:fs";
import { createServer } from "node:http";
import { tmpdir } from "node:os";
import { delimiter, dirname, join } from "node:path";
import { test } from "node:test";
import { fileURLToPath } from "node:url";

import { configureGradleDependencies } from "./gradle-dependencies.ts";

test("the installer rejects insecure remote mirrors and its CLI rejects extra arguments", (t) => {
  const directory = mkdtempSync(join(tmpdir(), "gradle-dependencies-input-"));
  t.after(() => rmSync(directory, { recursive: true, force: true }));
  for (const url of [
    "http://example.com/maven/",
    "https://user:password@example.com/maven/",
  ])
    assert.throws(
      () => configureGradleDependencies(directory, new URL(url)),
      /HTTPS Maven mirror/
    );
  const result = spawnSync(
    process.execPath,
    [
      fileURLToPath(new URL("./gradle-dependencies.ts", import.meta.url)),
      "unexpected",
    ],
    {
      encoding: "utf8",
      timeout: 5000,
      env: { ...process.env, GRADLE_USER_HOME: directory },
    }
  );
  assert.notEqual(result.status, 0);
});

test(
  "cold-cache settings plugins, included builds and classpaths survive Central 429s; real failures name infrastructure",
  {
    skip: process.env.GRADLE_DEPENDENCY_INTEGRATION !== "1",
    timeout: 180_000,
  },
  async (t) => {
    const directory = mkdtempSync(
      join(tmpdir(), "gradle-dependencies-resolution-")
    );
    t.after(() => rmSync(directory, { recursive: true, force: true }));
    const gradle =
      process.env.GRADLE_TEST_EXECUTABLE ??
      process.env
        .PATH!.split(delimiter)
        .map((path) => join(path, "gradle"))
        .find(existsSync);
    assert.ok(gradle, "Gradle must be installed before resolution tests");
    const gradleHome = dirname(dirname(realpathSync(gradle)));
    const java = (tool: string) =>
      process.env.JAVA_HOME ? join(process.env.JAVA_HOME, "bin", tool) : tool;
    const source = join(directory, "SettingsPlugin.java"),
      classes = join(directory, "classes");
    mkdirSync(join(classes, "META-INF/gradle-plugins"), { recursive: true });
    writeFileSync(
      source,
      "package fixture; public class SettingsPlugin implements org.gradle.api.Plugin<org.gradle.api.initialization.Settings> { public void apply(org.gradle.api.initialization.Settings settings) {} }"
    );
    const compile = spawnSync(
      java("javac"),
      ["-classpath", join(gradleHome, "lib", "*"), "-d", classes, source],
      { encoding: "utf8", timeout: 30_000 }
    );
    assert.equal(compile.status, 0, compile.stderr);
    writeFileSync(
      join(classes, "META-INF/gradle-plugins/fixture.settings.properties"),
      "implementation-class=fixture.SettingsPlugin\n"
    );
    const jar = join(directory, "plugin.jar");
    const archive = spawnSync(
      java("jar"),
      ["--create", "--file", jar, "-C", classes, "."],
      { encoding: "utf8", timeout: 10_000 }
    );
    assert.equal(archive.status, 0, archive.stderr);
    const bytes = readFileSync(jar);
    const modules = [
      [
        "fixture.settings",
        "fixture.settings.gradle.plugin",
        "<packaging>pom</packaging><dependencies><dependency><groupId>fixture</groupId><artifactId>plugin</artifactId><version>1.0</version></dependency></dependencies>",
      ],
      ["fixture", "plugin", ""],
      ["fixture", "classpath", ""],
      ["fixture", "payload", ""],
      ["fixture", "included-payload", ""],
    ];
    const requests: string[] = [];
    let deniedArtifact: string | undefined;
    const server = createServer((request, response) => {
      const path = request.url!;
      requests.push(path);
      if (
        !path.startsWith("/mirror/") ||
        (deniedArtifact && path.includes(`/fixture/${deniedArtifact}/`))
      ) {
        response.writeHead(429);
        response.end("Too Many Requests");
        return;
      }
      const module = modules.find(([group, artifact]) =>
        path.startsWith(
          `/mirror/${group!.replaceAll(
            ".",
            "/"
          )}/${artifact}/1.0/${artifact}-1.0.`
        )
      );
      if (!module) {
        response.writeHead(404);
        response.end();
        return;
      }
      if (path.endsWith(".pom"))
        response.end(
          `<project><modelVersion>4.0.0</modelVersion><groupId>${module[0]}</groupId><artifactId>${module[1]}</artifactId><version>1.0</version>${module[2]}</project>`
        );
      else if (path.endsWith(".jar")) response.end(bytes);
      else {
        response.writeHead(404);
        response.end();
      }
    });
    await new Promise<void>((resolve) =>
      server.listen(0, "127.0.0.1", resolve)
    );
    t.after(
      () =>
        new Promise<void>((resolve, reject) =>
          server.close((error) => (error ? reject(error) : resolve()))
        )
    );
    const address = server.address();
    assert.ok(address && typeof address !== "string");
    const origin = `http://127.0.0.1:${address.port}`;
    const repository = `maven { url '${origin}/central/'; allowInsecureProtocol = true }`;
    const project = join(directory, "project");
    mkdirSync(join(project, "included"), { recursive: true });
    writeFileSync(
      join(project, "settings.gradle"),
      `
pluginManagement { repositories { ${repository} } }
plugins { id 'fixture.settings' version '1.0' }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { ${repository} }
}
rootProject.name = 'resolution-fixture'
includeBuild('included')
`
    );
    writeFileSync(
      join(project, "included/settings.gradle"),
      `pluginManagement { repositories { ${repository} } }; rootProject.name = 'included'`
    );
    for (const [target, artifact] of [
      [project, "payload"],
      [join(project, "included"), "included-payload"],
    ]) {
      writeFileSync(
        join(target!, "build.gradle"),
        `
buildscript { repositories { ${repository} }; dependencies { classpath 'fixture:classpath:1.0' } }
${target === project ? "" : `repositories { ${repository} }`}
configurations { payload }
dependencies { payload 'fixture:${artifact}:1.0' }
tasks.register('resolve') { doLast {
    println('RESOLVED=' + configurations.payload.files.size())
    if (project.findProperty('fixtureFailure')) throw new GradleException('test assertion 429 private-content')
} }
${
  target === project
    ? "tasks.named('resolve') { dependsOn gradle.includedBuild('included').task(':resolve') }"
    : ""
}
`
      );
    }
    async function run(
      name: string,
      mirrorPath?: string,
      productFailure = false
    ) {
      const home = join(directory, name),
        summary = join(directory, `${name}.md`);
      if (mirrorPath) {
        configureGradleDependencies(home, new URL(origin + mirrorPath));
        configureGradleDependencies(home, new URL(origin + mirrorPath));
      }
      const start = requests.length;
      const result = await new Promise<{ code: number | null; output: string }>(
        (resolve, reject) => {
          const child = spawn(
            gradle!,
            [
              "-p",
              project,
              "resolve",
              "--no-daemon",
              "--max-workers=2",
              "--console=plain",
              ...(productFailure ? ["-PfixtureFailure=true"] : []),
            ],
            {
              env: {
                ...process.env,
                GRADLE_USER_HOME: home,
                GITHUB_STEP_SUMMARY: summary,
              },
              timeout: 55_000,
            }
          );
          let output = "";
          child.stdout.on("data", (value) => {
            output += value;
          });
          child.stderr.on("data", (value) => {
            output += value;
          });
          child.once("error", reject);
          child.once("close", (code) => resolve({ code, output }));
        }
      );
      return {
        ...result,
        requests: requests.slice(start),
        summary: existsSync(summary) ? readFileSync(summary, "utf8") : "",
      };
    }
    const before = await run("before");
    assert.notEqual(before.code, 0);
    assert.ok(before.requests.length > 0);
    assert.equal(
      before.requests.filter((path) => path.startsWith("/mirror/")).length,
      0
    );
    const after = await run("after", "/mirror/");
    assert.equal(after.code, 0, after.output);
    assert.equal(
      after.requests.filter((path) => path.startsWith("/central/")).length,
      0
    );
    assert.equal((after.output.match(/RESOLVED=1/g) ?? []).length, 2);
    const resolved = new Set(
      after.requests.filter((path) => path.endsWith(".pom"))
    );
    assert.equal(resolved.size, modules.length);
    assert.equal(after.summary, "");
    const product = await run("after", "/mirror/", true);
    assert.notEqual(product.code, 0);
    assert.equal(product.summary, "");
    for (const artifact of ["classpath", "payload"]) {
      deniedArtifact = artifact;
      const failure = await run(`${artifact}-limited`, "/mirror/");
      assert.notEqual(failure.code, 0);
      assert.match(failure.summary, /Infrastructure: HTTP 429/);
    }
    deniedArtifact = undefined;
    const limited = await run("limited", "/limited/");
    assert.notEqual(limited.code, 0);
    assert.match(
      limited.summary,
      /Infrastructure: HTTP 429 during Maven dependency resolution/
    );
    assert.ok(!limited.summary.includes(origin));
    console.log(
      `Cold-cache control: before resolved 0/${modules.length}, Central requests ${before.requests.length}; after resolved ${resolved.size}/${modules.length}, Central requests 0.`
    );
  }
);
