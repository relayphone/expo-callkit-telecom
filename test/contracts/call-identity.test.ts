import { describe, expect, test } from "bun:test";
import { createHash } from "node:crypto";
import { readFileSync } from "node:fs";

const contract = JSON.parse(
  readFileSync(
    new URL(
      "../../android/src/androidTest/assets/call-identity.json",
      import.meta.url
    ),
    "utf8"
  )
);

describe("shared SDK call identity contract", () => {
  test("pins the decided namespace and exact UTF-8 policy", () => {
    expect(contract.namespace).toBe("d460ee4a-efbc-5b0d-8035-0b915bf9551a");
    expect(contract.inputEncoding).toBe("UTF-8");
    expect(contract.normalization).toBe("none");
    expect(contract.vectors.length).toBeGreaterThanOrEqual(11);
  });
  for (const vector of contract.vectors) {
    test(vector.name, () => {
      const namespace = Buffer.from(
        contract.namespace.replaceAll("-", ""),
        "hex"
      );
      const hash = createHash("sha1")
        .update(namespace)
        .update(vector.serverCallId, "utf8")
        .digest();
      hash[6] = (hash[6]! & 15) | 80;
      hash[8] = (hash[8]! & 63) | 128;
      const hex = hash.subarray(0, 16).toString("hex");
      const uuid = [
        hex.slice(0, 8),
        hex.slice(8, 12),
        hex.slice(12, 16),
        hex.slice(16, 20),
        hex.slice(20),
      ].join("-");
      expect(uuid).toBe(vector.uuid);
    });
  }
});
