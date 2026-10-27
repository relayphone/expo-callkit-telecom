/* eslint-disable no-void -- Compile-only expressions exercise the public contract. */
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
  caller: { id: "p" },
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
if (action.kind === "hold") {
  const held: boolean = action.held;
  void held;
  // @ts-expect-error A hold action carries no tone digits.
  void action.digits;
}
if (outcome.status === "reported") {
  void api.endConversation(outcome.id);
} else {
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
  caller: { id: "owner" },
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
