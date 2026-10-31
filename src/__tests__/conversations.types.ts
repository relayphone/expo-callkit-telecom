/* eslint-disable no-void -- Compile-only expressions exercise the public contract. */
import { receiveCallEnded } from "../api";
import type {
  ActionContext,
  Conversation,
  ConversationHandler,
  ConversationId,
  ConversationsApi,
  ConversationsSnapshot,
  IncomingConversationOutcome,
  PendingAction,
  NativeTransport,
  JoinPolicy,
  IncomingCallPayload,
  NativeMediaCredentials,
  Caller,
  CallEndedPayload,
  ConversationEventMap,
  ConversationEnd,
  EndedFailure,
  EndedFailureKind,
  EndedReason,
  IncomingFilterReason,
  PreviewReason,
} from "../conversations";

declare const api: ConversationsApi;
declare const conversation: Conversation;
declare const id: ConversationId;
declare const snapshot: ConversationsSnapshot;
declare const action: PendingAction;
declare const outcome: IncomingConversationOutcome;
const webRTCCredentials: NativeMediaCredentials = {
  provider: "webrtc",
  url: "https://media.invalid/sdp",
  token: "test-fixture",
  expiresAt: 2000000000,
  iceServers: [
    {
      urls: ["turns:relay.invalid:443"],
      username: "device",
      credential: "test-fixture",
    },
  ],
};
void webRTCCredentials;

void api.joinConversation(id);
// @ts-expect-error A server id is not a native ConversationId.
void api.joinConversation("server-call-id");
// @ts-expect-error Snapshots are immutable, including nested members.
conversation.members.push({ id: "someone" });
void api.reportIncomingConversation({
  eventId: "e",
  serverCallId: "s",
  caller: { handleType: "generic", value: "p", displayName: "Caller" },
  hasVideo: false,
  // @ts-expect-error Payload metadata is JSON, not arbitrary class instances.
  metadata: { date: new Date() },
});
// @ts-expect-error An ended report must include its reason.
void api.reportConversation(id, { type: "ended" });
// @ts-expect-error Applications cannot rewrite the native revision.
snapshot.revision = 7;

if (conversation.status === "connected") {
  const connectedAt: string = conversation.connectedAt;
  void connectedAt;
}
if (conversation.status === "ringing") {
  const incoming: "incoming" = conversation.direction;
  void incoming;
}
if (conversation.ended?.reason === "failed") {
  const failure: EndedFailure | undefined = conversation.ended.failure;
  if (failure?.kind === "filtered") {
    const cause: IncomingFilterReason = failure.cause;
    void cause;
  } else if (failure) {
    const kind: EndedFailureKind = failure.kind;
    void kind;
  }
} else if (conversation.ended) {
  // @ts-expect-error Only a failed conversation carries a native failure.
  void conversation.ended.failure;
}
// The end shape before failures were added still type-checks.
declare const reasonOnly: { readonly at: string; readonly reason: EndedReason };
const compatibleEnd: ConversationEnd = reasonOnly;
const endedReason: EndedReason | undefined = conversation.ended?.reason;
void compatibleEnd;
void endedReason;
const nativeFailure: ConversationEnd = {
  at: "2026-10-31T00:00:00.000Z",
  reason: "failed",
  failure: { kind: "audioActivationTimedOut" },
};
void nativeFailure;
// @ts-expect-error A filtered failure carries the filter's cause.
const filteredWithoutCause: EndedFailure = { kind: "filtered" };
void filteredWithoutCause;
// @ts-expect-error Failure kinds are the SDK's known cases.
const unknownFailure: EndedFailure = { kind: "somethingElse" };
void unknownFailure;
const remoteFailure: ConversationEnd = {
  at: "2026-10-31T00:00:00.000Z",
  reason: "remoteEnded",
  // @ts-expect-error A conversation that didn't fail has no failure.
  failure: { kind: "server" },
};
void remoteFailure;
if (action.kind === "hold") {
  const held: boolean = action.held;
  void held;
  // @ts-expect-error A hold action carries no tone digits.
  void action.digits;
}
if (conversation.status === "previewing") {
  const incoming: "incoming" = conversation.direction;
  const reason: PreviewReason = conversation.previewReason;
  if (reason.type === "filtered") {
    const cause: IncomingFilterReason = reason.cause;
    void cause;
  }
  void incoming;
}
if (outcome.status === "previewed") {
  // A call preview is a conversation, which the app can join.
  void api.joinConversation(outcome.id);
  const reason: PreviewReason = outcome.reason;
  void reason;
}
if (outcome.status === "notShown") {
  // @ts-expect-error A call that doesn't appear has no conversation id.
  void outcome.id;
}
if (outcome.status === "reported") {
  void api.endConversation(outcome.id);
} else if (outcome.status !== "previewed") {
  // @ts-expect-error Filtered/failed reports cannot manufacture a conversation id.
  void outcome.id;
}

const handler: ConversationHandler = {
  async join(_, context: ActionContext) {
    context.signal.throwIfAborted();
  },
  start() {},
  async end() {},
  async hold(_, { held }) {
    const value: boolean = held;
    void value;
  },
};
api.setConversationHandler(handler);
api.setConversationHandler(null);
// @ts-expect-error Attaching a handler still requires start/end signaling callbacks.
api.setConversationHandler({ mute() {} });

declare const native: NativeTransport;
native.addListener("conversationsChanged", ({ revision, conversations }) => {
  const latest: ConversationsSnapshot = { revision, conversations };
  void latest;
});
// @ts-expect-error Versioned change events cannot omit the revision.
const invalidSnapshot: ConversationsSnapshot = { conversations: [] };
void invalidSnapshot;

const nativePolicy: JoinPolicy = {
  type: "native",
  media: "livekit",
  credentials: "fromPush",
  prewarm: true,
  fallback: { type: "nativeFetcher", name: "authenticated-device-fetcher" },
};
const handlerPolicy: JoinPolicy = {
  type: "handler",
  coldLaunchJoinBudgetMs: 1500,
};
void nativePolicy;
void handlerPolicy;
const invitation: IncomingCallPayload = {
  eventId: "event",
  serverCallId: "call",
  caller: { handleType: "generic", value: "owner", displayName: "Owner" },
  hasVideo: false,
  media: {
    provider: "livekit",
    url: "wss://media.invalid",
    room: "room",
    identity: "owner-device",
    token: "test-fixture-not-a-token",
    expiresAt: 1798000000,
  },
};
void invitation;
const jsFetcher: JoinPolicy = {
  type: "native",
  media: "livekit",
  // @ts-expect-error Native credentials cannot depend on a JavaScript callback.
  credentials: async () => invitation.media,
};
void jsFetcher;
const eventOnly: JoinPolicy = {
  type: "native",
  // @ts-expect-error Event-only media has no native engine to answer with.
  media: "none",
  credentials: "fromPush",
};
void eventOnly;

const nativeHttpPolicy: JoinPolicy = {
  type: "native",
  media: "livekit",
  credentials: "fromPush",
  fallback: {
    type: "http",
    baseUrl: "https://api.example.invalid",
    authentication: "device-auth",
  },
};
void nativeHttpPolicy;

// Match the SDK #150 public input shape, including mutability and optional host ID.
type Same<Left, Right> = (<T>() => T extends Left ? 1 : 2) extends <
  T
>() => T extends Right ? 1 : 2
  ? true
  : false;
const callerShape: Same<
  Caller,
  {
    handleType: "phone" | "email" | "generic";
    value: string;
    displayName: string;
    hostContactID?: string;
  }
> = true;
void callerShape;
const obsoleteCaller: Caller = {
  // @ts-expect-error Incoming callers no longer use the Participant id child.
  id: "legacy",
};
void obsoleteCaller;

// Match SDK #159's host/server end input and acceptance result.
const endedShape: Same<
  CallEndedPayload,
  {
    eventId: string;
    serverCallId: string;
    reason: Exclude<EndedReason, "local">;
  }
> = true;
const receiveEndedSignature: Same<
  typeof receiveCallEnded,
  (payload: CallEndedPayload) => Promise<boolean>
> = true;
void endedShape;
void receiveEndedSignature;
const ended: CallEndedPayload = {
  eventId: "end-event",
  serverCallId: "server-call",
  reason: "answeredElsewhere",
};
const accepted: Promise<boolean> = api.receiveCallEnded(ended);
void accepted;
const localEnd: CallEndedPayload = {
  eventId: "local-event",
  serverCallId: "server-call",
  // @ts-expect-error A local hangup uses endConversation, not a server signal.
  reason: "local",
};
void localEnd;
const answeredEvent: ConversationEventMap["answeredElsewhere"] = {
  conversationId: id,
  serverCallId: "server-call",
  eventId: "end-event",
};
void answeredEvent;
native.addListener("conversationEvent", (event) => {
  if (event.type === "answeredElsewhere") {
    const nativeID: ConversationId = event.payload.conversationId;
    const eventID: string = event.payload.eventId;
    const serverID: string = event.payload.serverCallId;
    void nativeID;
    void eventID;
    void serverID;
  }
});
