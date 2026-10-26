import type {
  ActionOrigin,
  AudioRoutes,
  Conversation,
  ConversationEventMap,
  ConversationsApi,
  ConversationsSnapshot,
  MediaAdapter,
  PushToken,
  Subscription,
} from "./types";

/** Token revision timeline is independent of conversation state. @category Transport */
export interface PushTokenSnapshot {
  readonly revision: number;
  readonly token: PushToken | null;
}

/** Route revision timeline is independent of conversation state. @category Transport */
export interface AudioRoutesSnapshot extends AudioRoutes {
  readonly revision: number;
}

/** Private native delivery; applications implement ConversationHandler instead. @category Transport */
export type HandlerAction = {
  readonly actionID: string;
  readonly generation: number;
  readonly conversation: Conversation;
  readonly deadline: string;
  readonly origin: ActionOrigin;
} & (
  | { readonly kind: "join" | "start" | "end" }
  | { readonly kind: "hold"; readonly held: boolean }
  | { readonly kind: "mute"; readonly muted: boolean }
  | { readonly kind: "tones"; readonly digits: string }
);

/** Discriminated native lifecycle event, excluding the revisioned token stream. @category Transport */
export type ConversationEvent = {
  [Name in Exclude<keyof ConversationEventMap, "pushTokenUpdated">]: {
    readonly type: Name;
    readonly payload: ConversationEventMap[Name];
  };
}[Exclude<keyof ConversationEventMap, "pushTokenUpdated">];

/** Native subscriptions attach before hydration or handler attachment. @category Transport */
export interface NativeEvents {
  readonly conversationsChanged: ConversationsSnapshot;
  readonly audioRoutesChanged: AudioRoutesSnapshot;
  readonly pushTokenUpdated: PushTokenSnapshot;
  readonly handlerAction: HandlerAction;
  readonly actionCancelled: {
    readonly actionID: string;
    readonly generation: number;
    readonly reason: string;
  };
  readonly conversationEvent: ConversationEvent;
}

/**
 * Small native seam for Android and the M3.5 iOS adapter. All state is copied immutable JSON.
 * Settlement is internal: generation plus action identity rejects replaced/late results.
 * @category Transport
 */
export interface NativeTransport
  extends Omit<
    ConversationsApi,
    "getConversations" | "setConversationHandler"
  > {
  /** O(1) cached native state; reading it performs no I/O or SDK work. */
  readonly snapshot: ConversationsSnapshot;
  readonly mediaAdapter: MediaAdapter;
  getConversations(): Promise<ConversationsSnapshot>;
  getAudioRoutes(): Promise<AudioRoutesSnapshot>;
  getPushToken(): Promise<PushTokenSnapshot>;
  addListener<Name extends keyof NativeEvents>(
    name: Name,
    listener: (event: NativeEvents[Name]) => void
  ): Subscription;
  setHandlerAttached(attached: boolean, generation: number): void;
  setDialRequestListenerAttached(attached: boolean, generation: number): void;
  settleAction(
    actionID: string,
    generation: number,
    success: boolean,
    error?: { readonly name: string; readonly message: string }
  ): void;
}
