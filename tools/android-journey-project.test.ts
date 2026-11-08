import assert from "node:assert/strict";
import { test } from "node:test";

import { packedExampleManifest } from "./android-journey-project.ts";

test("packed example disables checkout autolinking and declares the fork archive", () => {
  const input = {
    main: "index.ts",
    dependencies: { expo: "57", "@relayphone/ios-voip-sdk": "file:sdk.tgz" },
    expo: { autolinking: { nativeModulesDir: "../.." } },
  };
  const result = packedExampleManifest(input, "/tmp/fork.tgz");
  assert.deepEqual(result, {
    main: "index.ts",
    dependencies: {
      ...input.dependencies,
      "expo-callkit-telecom": "file:/tmp/fork.tgz",
    },
    expo: { autolinking: {} },
  });
  assert.equal(input.expo.autolinking.nativeModulesDir, "../..");
});
