import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { createRequire } from "node:module";
import { dirname, join, resolve } from "node:path";
import { test } from "node:test";

const require = createRequire(import.meta.url);
const cli = join(
  dirname(require.resolve("expo-modules-autolinking/package.json")),
  "bin/expo-modules-autolinking.js"
);
const example = resolve(import.meta.dirname, "../../example/client");

function object(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === "object";
}

test("the local example autolinks the SDK Apple module and subscriber", () => {
  const result: unknown = JSON.parse(
    execFileSync(
      process.execPath,
      [cli, "resolve", "--json", "--platform", "apple", "--project-root", example],
      { encoding: "utf8", timeout: 15_000 }
    )
  );
  assert.ok(object(result) && Array.isArray(result.modules));
  const providers = result.modules.filter(
    (module: unknown) =>
      object(module) &&
      ["@relayphone/ios-voip-sdk", "expo-callkit-telecom"].includes(
        String(module.packageName)
      )
  );
  assert.equal(providers.length, 1);
  const sdk: unknown = providers[0];
  assert.ok(object(sdk));
  assert.equal(sdk.packageName, "@relayphone/ios-voip-sdk");
  assert.deepEqual(sdk.swiftModuleNames, ["VoIPConversations"]);
  assert.deepEqual(sdk.modules, [{ name: null, class: "VoIPConversationsModule" }]);
  assert.deepEqual(sdk.appDelegateSubscribers, [
    "VoIPConversationsAppDelegateSubscriber",
  ]);
});
