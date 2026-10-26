/** JSON values that can cross the native bridge without loss. @category Core */
export type JSONValue =
  | null
  | boolean
  | number
  | string
  | readonly JSONValue[]
  | { readonly [key: string]: JSONValue };

/** Native UUID, distinct from a transport's serverCallId. @category State */
export type ConversationId = string & {
  readonly __conversationId: unique symbol;
};

/** Remote party; preserves the v1 push wire fields. @category State */
export interface Participant {
  readonly id: string;
  readonly displayName?: string;
  readonly avatarUrl?: string;
  readonly phoneNumber?: string;
  readonly email?: string;
}

/** Incoming invitation from push or foreground transport. @category Reports */
export interface IncomingCallPayload {
  readonly eventId: string;
  readonly serverCallId: string;
  readonly caller: Participant;
  readonly hasVideo: boolean;
  /** Original RFC 3339 wire timestamp, when supplied. */
  readonly startedAt?: string;
  readonly metadata?: { readonly [key: string]: JSONValue };
}

/** Termination reported by the user, transport, or system. @category State */
export type EndedReason =
  | "local"
  | "remoteEnded"
  | "failed"
  | "unanswered"
  | "answeredElsewhere"
  | "declinedElsewhere";

/** Operations the system supports for this conversation. @category State */
export interface ConversationCapabilities {
  readonly hold: boolean;
  readonly merge: boolean;
  readonly tones: boolean;
  readonly video: boolean;
}

/** Origin of a handler action, including app commands. @category Handler */
export type ActionOrigin = "system" | "app";

/** Native-owned action identity and application deadline. @category Handler */
export interface PendingActionBase {
  readonly id: string;
  /** ISO 8601 effective deadline, before the platform timeout; never extend in JS. */
  readonly deadline: string;
  readonly origin: ActionOrigin;
}

/** Arguments are present only for the matching action kind. @category Handler */
export type PendingAction = PendingActionBase &
  (
    | { readonly kind: "join" | "start" | "end" }
    | { readonly kind: "hold"; readonly held: boolean }
    | { readonly kind: "mute"; readonly muted: boolean }
    | { readonly kind: "tones"; readonly digits: string }
  );

/** Shared immutable system state. Dates use ISO 8601. @category State */
export interface ConversationBase {
  readonly id: ConversationId;
  readonly direction: "incoming" | "outgoing";
  readonly members: readonly Participant[];
  readonly capabilities: ConversationCapabilities;
  readonly isMuted: boolean;
  readonly isVideo: boolean;
  /** Set only after the transport reports connected, not when a handler resolves. */
  readonly connectedAt?: string;
  readonly ended?: { readonly at: string; readonly reason: EndedReason };
  readonly payload?: IncomingCallPayload;
  readonly pending?: PendingAction;
}

/**
 * Several conversations may coexist. Native hold/termination overrides transport progress.
 * Held conversations may lack connectedAt if paused before a connected report.
 * @category State
 */
export type Conversation = ConversationBase &
  (
    | { readonly status: "ringing"; readonly direction: "incoming" }
    | { readonly status: "dialing"; readonly direction: "outgoing" }
    | { readonly status: "connecting" }
    | { readonly status: "connected"; readonly connectedAt: string }
    | { readonly status: "held" }
    | { readonly status: "ending" }
    | { readonly status: "ended" }
  );

/** All derived statuses, matching ios-voip-sdk. @category State */
export type ConversationStatus = Conversation["status"];

/**
 * Complete snapshot for reads and change events. Ignore older or equal revisions.
 * Revisions are nonnegative safe integers, increasing within the native process lifetime.
 * @category State
 */
export interface ConversationsSnapshot {
  readonly revision: number;
  readonly conversations: readonly Conversation[];
}

/** Cancellation also covers replacement, reset and platform timeout. @category Handler */
export interface ActionContext {
  /** Native effective deadline, expressed as ISO 8601. */
  readonly deadline: string;
  readonly signal: AbortSignal;
  readonly origin: ActionOrigin;
}

/** Resolve to accept signaling; reject to fail. Native owns settlement. @category Handler */
export type ActionHandler<Context extends ActionContext = ActionContext> = (
  conversation: Conversation,
  context: Context
) => void | Promise<void>;

/**
 * One handler object for system and app actions. Missing optional methods accept.
 * With no handler, native retains actions until the deadline (default policy: fail).
 * Resolving start/join does not report media connected. Native suppresses no-op echoes.
 * @category Handler
 */
export interface ConversationHandler {
  readonly join: ActionHandler;
  readonly start: ActionHandler;
  readonly end: ActionHandler;
  readonly hold?: ActionHandler<ActionContext & { readonly held: boolean }>;
  readonly mute?: ActionHandler<ActionContext & { readonly muted: boolean }>;
  readonly tones?: ActionHandler<ActionContext & { readonly digits: string }>;
}

/** Outgoing command options. @category Commands */
export interface StartConversationOptions {
  readonly to: Participant;
  readonly video: boolean;
  readonly metadata?: { readonly [key: string]: JSONValue };
}

/** Transport facts; do not invoke the application's action handler. @category Reports */
export type ConversationReport =
  | { readonly type: "connecting" }
  | { readonly type: "connected" }
  | {
      readonly type: "updated";
      readonly members?: readonly Participant[];
      readonly video?: boolean;
    }
  | { readonly type: "ended"; readonly reason: EndedReason };

/** Known incoming filtering reasons; unknown native values use other. @category Reports */
export type IncomingFilterReason = "doNotDisturb" | "blockList" | "other";

/** Rejection codes shared with the SDK; native details remain available. @category Core */
export type ConversationErrorCode =
  | "invalid_arguments"
  | "unknown_conversation"
  | "unsupported"
  | "conversation"
  | "duplicate_sdk_linkage"
  | "deadline_exceeded"
  | "handler_failed";

/** Serializable failure, also suitable for rejected command error fields. @category Core */
export interface ConversationFailure {
  readonly code: ConversationErrorCode;
  readonly message: string;
  readonly nativeDomain?: string;
  readonly nativeCode?: string | number;
}

/**
 * A filtered or rejected report never claims a conversation was created.
 * Repeated accepted eventId/serverCallId reports return the same native id.
 * @category Reports
 */
export type IncomingConversationOutcome =
  | { readonly status: "reported"; readonly id: ConversationId }
  | {
      readonly status: "filtered";
      readonly reason: IncomingFilterReason;
      readonly nativeReason?: string;
    }
  | { readonly status: "atCapacity" }
  | { readonly status: "failed"; readonly error: ConversationFailure };

/** Existing cross-platform output port names. Unknown ports use unknown. @category Audio */
export type AudioRouteType =
  | "builtInReceiver"
  | "builtInSpeaker"
  | "headphones"
  | "bluetoothA2DP"
  | "bluetoothLE"
  | "bluetoothHFP"
  | "airPlay"
  | "hdmi"
  | "carAudio"
  | "usbAudio"
  | "lineOut"
  | "unknown";

/** A platform route or selection target; id is opaque. @category Audio */
export interface AudioRoute {
  readonly id: string;
  readonly name: string;
  readonly type: AudioRouteType;
}

/** Actual system output; requested changes are not optimistic state. @category Audio */
export interface AudioRoutes {
  readonly routes: readonly AudioRoute[];
  readonly current: AudioRoute | null;
}

/** Native audio integration, selected at build time. @category Configuration */
export type MediaAdapter = "webrtc" | "livekit" | "none";

/** Plugin configuration added to the existing v1 props. @category Configuration */
export interface ConversationConfiguration {
  readonly mediaAdapter?: MediaAdapter;
  readonly unhandledAction?: "fail" | "accept";
  /** Positive interval in milliseconds; native performs at most one recovery attempt. */
  readonly audioActivationTimeoutMs?: number;
}

/** Push registers at launch; null state means unavailable or invalidated. @category Push */
export interface PushToken {
  readonly type: "apns-voip" | "fcm";
  readonly value: string;
}

/** Lifecycle signals that cannot be derived from the snapshot. @category Events */
export interface ConversationEventMap {
  readonly audioActivated: { readonly conversationId?: ConversationId };
  readonly audioDeactivated: { readonly conversationId?: ConversationId };
  readonly audioActivationMissing: { readonly conversationId: ConversationId };
  readonly audioActivationFailed: { readonly conversationId: ConversationId };
  readonly pushTokenUpdated: PushToken | null;
  readonly dialRequest: { readonly handle: string; readonly video: boolean };
  readonly providerReset: Record<string, never>;
  readonly providerOwnershipLost: Record<string, never>;
}

/** Removable native or public subscription. @category Core */
export interface Subscription {
  remove(): void;
}

/**
 * Platform implementation seam. Commands resolve on system acceptance; reports describe transport.
 * Incoming platform filtering/capacity/failure is returned as an outcome, not a fabricated id.
 * @category Core
 */
export interface ConversationsApi {
  getConversations(): Promise<readonly Conversation[]>;
  setConversationHandler(handler: ConversationHandler | null): void;
  startConversation(options: StartConversationOptions): Promise<ConversationId>;
  joinConversation(id: ConversationId): Promise<void>;
  endConversation(id: ConversationId): Promise<void>;
  setMuted(id: ConversationId, muted: boolean): Promise<void>;
  setHeld(id: ConversationId, held: boolean): Promise<void>;
  playTones(id: ConversationId, digits: string): Promise<void>;
  /** Rejects with unsupported on Android. */
  mergeConversations(
    first: ConversationId,
    second: ConversationId
  ): Promise<void>;
  reportConversation(
    id: ConversationId,
    report: ConversationReport
  ): Promise<void>;
  reportIncomingConversation(
    payload: IncomingCallPayload
  ): Promise<IncomingConversationOutcome>;
  selectAudioRoute(id: string): Promise<void>;
}
