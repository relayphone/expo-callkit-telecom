import {
  startConversation,
  setConversationHandler,
  reportIncomingConversation,
} from "@relayphone/ios-voip-sdk";
import type { VoIPConversationsPluginProps } from "@relayphone/ios-voip-sdk/plugin";
import type {
  ConversationHandler,
  StartConversationOptions,
  IncomingCallPayload,
} from "@relayphone/ios-voip-sdk/types";
import { getConversations as getAndroidConversations } from "expo-callkit-telecom";
import { getMediaPermissions, requestMediaPermissions, conversationMediaReadiness } from "expo-callkit-telecom";
import { setConversationVideo, reportConversation } from "expo-callkit-telecom";
import type { MediaKind, MediaPermissions, MediaPermissionResponse, ConversationMediaReadiness } from "expo-callkit-telecom";
import type { MediaPermissions as MediaPermissionsSubpath } from "expo-callkit-telecom/types";
import type { Caller, ConversationStatus, ConversationErrorCode, EndedFailure } from "expo-callkit-telecom/types";
import type { Conversation, ConversationEnd, ConversationEventMap } from "expo-callkit-telecom/types";
import type { ConversationReport } from "expo-callkit-telecom/types";

export const reconnecting: ConversationStatus = "reconnecting";
export const refused: ConversationErrorCode = "refused";
export const remaining: EndedFailure = { kind: "media", platformCallRemaining: true };
const kinds: readonly MediaKind[] = ["microphone", "camera"];
export const permissionQueries: readonly Promise<MediaPermissions>[] = [getMediaPermissions(), requestMediaPermissions(kinds)];
export const readinessQuery: Promise<ConversationMediaReadiness> = conversationMediaReadiness("video-call");
export const permissionResponse = (value: MediaPermissionsSubpath): MediaPermissionResponse => value.microphone;

declare const diagnosticConversationId: Conversation["id"];
export const videoUpdate: ConversationReport = { type: "updated", video: true };
export const videoCommands: readonly Promise<void>[] = [
  setConversationVideo(diagnosticConversationId, true),
  reportConversation(diagnosticConversationId, videoUpdate),
];
export const videoChanges: readonly ConversationEventMap["videoChanged"][] = [
  { conversationId: diagnosticConversationId, isVideo: true, source: "app" },
  { conversationId: diagnosticConversationId, isVideo: false, source: "server" },
  { conversationId: diagnosticConversationId, isVideo: true, source: "system" },
];
// @ts-expect-error Video changes require a boolean.
void setConversationVideo(diagnosticConversationId, "on");
// @ts-expect-error Video updates require a boolean.
const invalidVideoUpdate: ConversationReport = { type: "updated", video: "on" };
// @ts-expect-error Video sources are a closed union.
const invalidVideoSource: ConversationEventMap["videoChanged"] = { conversationId: diagnosticConversationId, isVideo: true, source: "remote" };
void [invalidVideoUpdate, invalidVideoSource];
export const prewarmFailure: ConversationEventMap["prewarmFailed"] = {
  conversationId: diagnosticConversationId,
  stage: "deadline",
  errorType: "TimeoutCancellationException",
};
export const prewarmStages: readonly ConversationEventMap["prewarmFailed"]["stage"][] = [
  "connect", "identity", "subscribe", "deadline",
];
export const mediaFailures: readonly ConversationEnd[] = [
  { at: "2026-11-16T00:00:00.000Z", reason: "failed", failure: { kind: "media" } },
  {
    at: "2026-11-16T00:00:00.000Z", reason: "failed",
    failure: { kind: "media", stage: "prepare", timedOut: true, timeoutMs: 5000 },
  },
  {
    at: "2026-11-16T00:00:00.000Z", reason: "failed",
    failure: { kind: "media", stage: "audioSession", timedOut: false },
  },
  {
    at: "2026-11-16T00:00:00.000Z", reason: "failed",
    failure: { kind: "media", stage: "activate", timedOut: false, reason: "publishPermissionNotGranted" },
  },
  {
    at: "2026-11-16T00:00:00.000Z", reason: "failed",
    failure: { kind: "media", stage: "prepare", timedOut: false, reason: "prejoinCloseFailed", platformCallRemaining: true },
  },
];
export function inspectMediaFailure(conversation: Conversation): void {
  const ended = conversation.ended;
  if (ended?.reason === "failed" && ended.failure?.kind === "media") {
    const stage: "prepare" | "audioSession" | "activate" | undefined = ended.failure.stage;
    const timedOut: boolean | undefined = ended.failure.timedOut;
    const timeoutMs: number | undefined = ended.failure.timeoutMs;
    const reason: "publishPermissionNotGranted" | "prejoinCloseFailed" | undefined = ended.failure.reason;
    void [stage, timedOut, timeoutMs, reason];
    // @ts-expect-error Media diagnostics are immutable.
    ended.failure.stage = "prepare";
  }
}
// @ts-expect-error Prewarm diagnostics never expose exception messages.
void prewarmFailure.message;
// @ts-expect-error The error type is required.
const missingErrorType: ConversationEventMap["prewarmFailed"] = { conversationId: diagnosticConversationId, stage: "connect" };
// @ts-expect-error Prewarm stages form a closed union.
const invalidPrewarmStage: ConversationEventMap["prewarmFailed"] = { conversationId: diagnosticConversationId, stage: "activate", errorType: "Error" };
// @ts-expect-error Media stages use descriptive public values.
const numericMediaStage: EndedFailure = { kind: "media", stage: 1 };
// @ts-expect-error Native uppercase reason codes are not public values.
const nativeMediaReason: EndedFailure = { kind: "media", reason: "PREJOIN_CLOSE_FAILED" };
// @ts-expect-error Media diagnostics belong only to a media failure.
const nonMediaDiagnostics: EndedFailure = { kind: "server", stage: "prepare" };
void [missingErrorType, invalidPrewarmStage, numericMediaStage, nativeMediaReason, nonMediaDiagnostics];

const handler: ConversationHandler = {
  start: async (call, { signal }) => {
    if (signal.aborted) throw new Error(call.id);
  },
};
const options: StartConversationOptions = {
  id: "server-call-id",
  to: { id: "alice" },
  video: false,
};
const caller: Caller = {
  handleType: "generic",
  value: "alice",
  displayName: "Alice",
};
const payload: IncomingCallPayload = {
  eventId: "incoming-event",
  serverCallId: "server-call-id",
  caller,
  hasVideo: false,
};
export const props: VoIPConversationsPluginProps = {
  mediaAdapter: "none",
  simulator: true,
};
setConversationHandler(handler);
export const commands = [
  startConversation(options),
  reportIncomingConversation(payload),
  getAndroidConversations(),
];
