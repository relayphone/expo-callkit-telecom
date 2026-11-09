import type {
  Conversation,
  ConversationsApi,
  EndedFailure,
} from "expo-callkit-telecom";

export const androidStages = [
  ["incoming:ringing", "ringing", "RINGING"],
  ["incoming:connected", "connected", "ACTIVE"],
  ["incoming:held", "held", "ON_HOLD"],
  ["incoming:removed", "absent", "ABSENT"],
  ["outgoing:connected", "connected", "ACTIVE"],
  ["outgoing:removed", "absent", "ABSENT"],
  ["elsewhere:ringing", "ringing", "RINGING"],
  ["elsewhere:removed", "absent", "ABSENT"],
  ["filtered:removed", "absent", "ABSENT"],
] as const;
export type AndroidStage = (typeof androidStages)[number][0];
export type JourneyApi = Pick<
  ConversationsApi,
  | "getConversations"
  | "setConversationHandler"
  | "joinConversation"
  | "setHeld"
  | "startConversation"
  | "reportConversation"
  | "endConversation"
>;
export interface ActionRecord {
  id: string;
  kind: string;
  origin: string;
  held?: boolean;
}
export interface OutcomeRecord {
  id: string;
  eventId: string;
  status: string;
  reason?: string;
}
export interface AndroidResults {
  schemaVersion: 1;
  runID: string;
  sequence: number;
  stage: AndroidStage | "passed" | "failed";
  previewJoin: "unsupported";
  failure?: string;
  snapshots: {
    step: AndroidStage;
    id: string;
    status: Conversation["status"] | "absent";
    direction: Conversation["direction"];
  }[];
  handlers: ActionRecord[];
  deliveries: ActionRecord[];
  actions: ActionRecord[];
  outcomes: OutcomeRecord[];
  elsewhere: {
    conversationId: string;
    serverCallId: string;
    eventId: string;
  }[];
  cancelled: string[];
  terminals: { id: string; reason: string; failure?: EndedFailure }[];
  lifecycle: {
    id: string;
    kind: "ended" | "performedEnd" | "elsewhere" | "removed";
  }[];
  completed: string[];
}
