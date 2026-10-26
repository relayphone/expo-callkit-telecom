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
} from "../conversations/types";

declare const api: ConversationsApi;
declare const conversation: Conversation;
declare const id: ConversationId;
declare const snapshot: ConversationsSnapshot;
declare const action: PendingAction;
declare const outcome: IncomingConversationOutcome;

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
// @ts-expect-error The three signaling callbacks are required when a handler attaches.
api.setConversationHandler({ mute() {} });
