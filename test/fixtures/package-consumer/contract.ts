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
import type { MediaKind, MediaPermissions, MediaPermissionResponse, ConversationMediaReadiness } from "expo-callkit-telecom";
import type { MediaPermissions as MediaPermissionsSubpath } from "expo-callkit-telecom/types";
import type { Caller, ConversationStatus, ConversationErrorCode, EndedFailure } from "expo-callkit-telecom/types";

export const reconnecting: ConversationStatus = "reconnecting";
export const refused: ConversationErrorCode = "refused";
export const remaining: EndedFailure = { kind: "media", platformCallRemaining: true };
const kinds: readonly MediaKind[] = ["microphone", "camera"];
export const permissionQueries: readonly Promise<MediaPermissions>[] = [getMediaPermissions(), requestMediaPermissions(kinds)];
export const readinessQuery: Promise<ConversationMediaReadiness> = conversationMediaReadiness("video-call");
export const permissionResponse = (value: MediaPermissionsSubpath): MediaPermissionResponse => value.microphone;

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
