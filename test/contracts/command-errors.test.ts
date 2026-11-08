import assert from "node:assert/strict";
import { describe, it } from "node:test";

import {
  commandError,
  withCommandErrors,
} from "../../src/conversations/errors.ts";

describe("native command rejection details", () => {
  for (const code of [
    "invalid_arguments",
    "unknown_conversation",
    "unsupported",
    "conversation",
    "duplicate_sdk_linkage",
    "deadline_exceeded",
    "handler_failed",
  ]) {
    it(`decodes ${code} through the command proxy`, async () => {
      const native = {
        async joinConversation() {
          throw new Error(
            `Call to function rejected: expo-conversation:${JSON.stringify({
              code,
              message: "Conversation operation failed",
            })}`
          );
        },
      };
      await assert.rejects(withCommandErrors(native).joinConversation(), {
        code,
        message: "Conversation operation failed",
      });
    });
  }

  it("preserves platform details and discards extra native fields", () => {
    for (const nativeCode of [4, "refused"]) {
      const error = commandError(
        new Error(
          `expo-conversation:${JSON.stringify({
            code: "conversation",
            message: "Control failed",
            nativeDomain: "androidx.core.telecom",
            nativeCode,
            credentials: "private",
          })}\nNative stack follows`
        )
      );
      assert.ok(error instanceof Error);
      assert.equal(error.message, "Control failed");
      assert.deepEqual(
        { ...error },
        {
          code: "conversation",
          nativeDomain: "androidx.core.telecom",
          nativeCode,
        }
      );
    }
  });

  it("keeps unrelated and malformed rejections unchanged", () => {
    for (const error of [
      undefined,
      "other failure",
      new Error("Another module failed"),
      new Error('expo-conversation:{"code":"unknown","message":"bad"}'),
      new Error('expo-conversation:{"code":"conversation"}'),
      new Error("expo-conversation:invalid-json"),
    ]) {
      assert.equal(commandError(error), error);
    }
  });

  it("keeps successful commands, subscriptions and their receiver", async () => {
    const native = {
      result: "native-value",
      async getPushToken() {
        return this.result;
      },
      addListener() {
        return this.result;
      },
    };
    const api = withCommandErrors(native);
    assert.equal(await api.getPushToken(), "native-value");
    assert.equal(api.addListener(), "native-value");
  });
});
