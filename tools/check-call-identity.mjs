import { readFile } from "node:fs/promises";
import { strict as assert } from "node:assert";

// Canonical shared SDK contract. Change the pin only with an intentional fixture update.
const commit = "52c2eaba23f61e67e9086a7b2328cf9e293da4ea";
const url = `https://raw.githubusercontent.com/ziahamza/ios-voip-sdk/${commit}/docs/contracts/call-identity.json`;
const response = await fetch(url, { signal: AbortSignal.timeout(30_000) });
assert.ok(
  response.ok,
  `Pinned identity contract fetch failed: ${response.status}`
);
const upstream = Buffer.from(await response.arrayBuffer());
const local = await readFile(
  new URL(
    "../android/src/androidTest/assets/call-identity.json",
    import.meta.url
  )
);
assert.ok(
  local.equals(upstream),
  "Vendored identity vectors differ from the pinned SDK contract"
);
console.log(`Identity vectors match ios-voip-sdk@${commit}`);
