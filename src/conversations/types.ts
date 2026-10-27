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

/**
 * Short-lived credentials for a muted native pre-join, shared by APNs and FCM.
 * The server must not treat prejoined as answered. Never log this object or its token.
 * @category Reports
 */
export interface LiveKitMediaCredentials {
  readonly provider: "livekit";
  /** Secure WebSocket URL of the LiveKit server. */
  readonly url: string;
  readonly room: string;
  /** Owner participant identity, scoped to this device. */
  readonly identity: string;
  /** Room-scoped JWT with roomJoin, canPublish and canSubscribe grants. */
  readonly token: string;
  /** Unix epoch seconds. Expired credentials require a native fetch, not a pre-join. */
  readonly expiresAt: number;
}

/** Native fetcher response for the built-in WebRTC SDP exchange. Never put it in snapshots. @category Configuration */
export interface WebRTCMediaCredentials {
  readonly provider: "webrtc";
  /** HTTPS endpoint accepting an offer and returning an answer, both with gathered ICE candidates. */
  readonly url: string;
  /** Short-lived, call-scoped Bearer token for the SDP/state endpoint. */
  readonly token: string;
  readonly expiresAt: number;
  readonly iceServers?: readonly {
    readonly urls: readonly string[];
    readonly username?: string;
    readonly credential?: string;
  }[];
}

/** Native credential fetcher result; the push media wire object remains LiveKit-only. @category Configuration */
export type NativeMediaCredentials =
  | LiveKitMediaCredentials
  | WebRTCMediaCredentials;

/** Incoming invitation from push or foreground transport. @category Reports */
export interface IncomingCallPayload {
  readonly eventId: string;
  readonly serverCallId: string;
  readonly caller: Participant;
  readonly hasVideo: boolean;
  /** Optional native pre-warm credentials; keep this outside application metadata. */
  readonly media?: LiveKitMediaCredentials;
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
 * One handler object for system and app actions. Missing hold/mute/tones methods accept.
 * Under the handler join policy a missing join method fails; native policy bypasses it.
 * For JS-gated actions with no handler, native waits until the deadline (default: fail).
 * Resolving start/join does not report media connected. Native suppresses no-op echoes.
 * @category Handler
 */
export interface ConversationHandler {
  /** Required by the explicit handler join policy; never gates a native-policy Join. */
  readonly join?: ActionHandler;
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

/**
 * Reference to an app-registered native fetcher, available without JavaScript.
 * It POSTs media-credentials with eventId/deviceId and native-held device authentication.
 * Fetchers return NativeMediaCredentials; 404/410 abandons this call's pre-warm.
 * A JavaScript callback is deliberately not a credentials source.
 * @category Configuration
 */
export interface NativeCredentialsFetcher {
  readonly type: "nativeFetcher";
  readonly name: string;
}

/** Built-in native HTTPS POST fetcher; authentication is resolved by registered native code. @category Configuration */
export interface HttpMediaCredentialsFetcher {
  readonly type: "http";
  /** HTTPS origin/base path; the native client appends /v1/calls/{serverCallId}/media-credentials. */
  readonly baseUrl: string;
  /** NativeMediaAuthenticationProviders entry registered in Application.onCreate. */
  readonly authentication: string;
}

/** Named app fetcher or the built-in authenticated HTTPS client. @category Configuration */
export type MediaCredentialsFetcher =
  | NativeCredentialsFetcher
  | HttpMediaCredentialsFetcher;

/**
 * Default policy: report first, then pre-warm media and JS in parallel.
 * Join fulfils natively; the microphone stays muted until native audio activation.
 * A missing/expired push credential uses fallback, then retries once on answer.
 * @category Configuration
 */
export interface NativeJoinPolicy {
  readonly type: "native";
  readonly media: Exclude<MediaAdapter, "none">;
  readonly credentials: "fromPush" | MediaCredentialsFetcher;
  /** Native fallback used when fromPush credentials are missing or expired. */
  readonly fallback?: MediaCredentialsFetcher;
  /** Defaults to true; covers both native media and the JS runtime. */
  readonly prewarm?: boolean;
}

/** Explicit fallback for applications whose media remains in JS. @category Configuration */
export interface HandlerJoinPolicy {
  readonly type: "handler";
  /** Positive native-enforced cold-launch budget; never extends the platform deadline. */
  readonly coldLaunchJoinBudgetMs?: number;
}

/** Native policy is the default; handler gating requires explicit selection. @category Configuration */
export type JoinPolicy = NativeJoinPolicy | HandlerJoinPolicy;

/** Plugin configuration added to the existing v1 props. @category Configuration */
export interface ConversationConfiguration {
  /** Defaults to native answering with the configured media adapter and prewarm enabled. */
  readonly joinPolicy?: JoinPolicy;
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
  /** Native report/filter result, also delivered when Android filters a prewarmed ringing call after a policy change. */
  readonly incomingConversationOutcome: {
    readonly eventId: string;
    readonly serverCallId: string;
    readonly outcome: IncomingConversationOutcome;
  };
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
