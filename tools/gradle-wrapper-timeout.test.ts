import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
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
import { fileURLToPath } from "node:url";

import { configureWrapperTimeout } from "./gradle-wrapper-timeout.ts";

function fixture(t: { after(cleanup: () => void): void }, contents: string) {
  const project = mkdtempSync(join(tmpdir(), "gradle-wrapper-timeout-"));
  t.after(() => rmSync(project, { recursive: true, force: true }));
  const wrapper = join(project, "gradle/wrapper");
  mkdirSync(wrapper, { recursive: true });
  const file = join(wrapper, "gradle-wrapper.properties");
  writeFileSync(file, contents);
  return { project, file };
}

test("raises the wrapper download timeout without changing its distribution", (t) => {
  const distribution =
    "distributionUrl=https\\://services.gradle.org/distributions/gradle-9.3.1-bin.zip\n";
  const { project, file } = fixture(
    t,
    distribution + "networkTimeout=10000\nvalidateDistributionUrl=true\n"
  );

  configureWrapperTimeout(project);

  assert.equal(
    readFileSync(file, "utf8"),
    distribution + "validateDistributionUrl=true\nnetworkTimeout=120000\n"
  );
});

test("adds a missing timeout, retains CRLF and removes duplicate property spellings", (t) => {
  for (const [input, expected] of [
    [
      "distributionPath=wrapper/dists",
      "distributionPath=wrapper/dists\nnetworkTimeout=120000\n",
    ],
    [
      "# networkTimeout is a socket timeout\r\ndistributionSha256Sum=abc\r\n",
      "# networkTimeout is a socket timeout\r\ndistributionSha256Sum=abc\r\nnetworkTimeout=120000\r\n",
    ],
    [
      "networkTimeout = 1\n networkTimeout:2\nnetworkTimeout 3\nnetworkTimeoutExtra=4\n",
      "networkTimeoutExtra=4\nnetworkTimeout=120000\n",
    ],
  ]) {
    assert.ok(input !== undefined && expected !== undefined);
    const { project, file } = fixture(t, input);
    configureWrapperTimeout(project);
    configureWrapperTimeout(project);
    assert.equal(readFileSync(file, "utf8"), expected);
  }
});

test("the CLI configures an existing wrapper and fails for a missing project", (t) => {
  const { project, file } = fixture(t, "networkTimeout=10000\n");
  const cli = fileURLToPath(
    new URL("./gradle-wrapper-timeout.ts", import.meta.url)
  );
  const configured = spawnSync(process.execPath, [cli, project], {
    encoding: "utf8",
    timeout: 5000,
  });
  assert.equal(configured.status, 0, configured.stderr);
  assert.equal(readFileSync(file, "utf8"), "networkTimeout=120000\n");

  const missing = spawnSync(process.execPath, [cli, join(project, "missing")], {
    encoding: "utf8",
    timeout: 5000,
  });
  assert.notEqual(missing.status, 0);
  assert.match(missing.stderr, /ENOENT/);
  const noArguments = spawnSync(process.execPath, [cli], {
    encoding: "utf8",
    timeout: 5000,
  });
  assert.notEqual(noArguments.status, 0);
  assert.match(noArguments.stderr, /ANDROID_PROJECT/);
});
