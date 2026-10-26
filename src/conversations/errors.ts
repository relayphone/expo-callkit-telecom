import type { ConversationErrorCode, ConversationFailure } from "./types";

const codes = new Set<ConversationErrorCode>([
  "invalid_arguments",
  "unknown_conversation",
  "unsupported",
  "conversation",
  "duplicate_sdk_linkage",
  "deadline_exceeded",
  "handler_failed",
]);

/** Decode sanitized native details without assuming every caught value came from this library. */
export function commandError(value: unknown): unknown {
  if (!(value instanceof Error)) return value;
  const prefix = "expo-conversation:";
  const offset = value.message.indexOf(prefix);
  if (offset < 0) return value;
  try {
    const line = value.message.slice(offset + prefix.length).split("\n")[0]!;
    const data: unknown = JSON.parse(line.slice(0, line.lastIndexOf("}") + 1));
    if (
      !data ||
      typeof data !== "object" ||
      !("code" in data) ||
      typeof data.code !== "string" ||
      !codes.has(data.code as ConversationErrorCode) ||
      !("message" in data) ||
      typeof data.message !== "string"
    )
      return value;
    const details: ConversationFailure = {
      code: data.code as ConversationErrorCode,
      message: data.message,
      ...("nativeCode" in data &&
      (typeof data.nativeCode === "number" ||
        typeof data.nativeCode === "string")
        ? { nativeCode: data.nativeCode }
        : {}),
      ...("nativeDomain" in data && typeof data.nativeDomain === "string"
        ? { nativeDomain: data.nativeDomain }
        : {}),
    };
    return Object.assign(new Error(details.message), details);
  } catch {
    return value;
  }
}
