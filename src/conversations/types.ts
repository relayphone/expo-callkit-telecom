/** JSON values that can cross the native bridge without loss. @category Core */
export type JSONValue =
  | null
  | boolean
  | number
  | string
  | readonly JSONValue[]
  | { readonly [key: string]: JSONValue };

/**
 * Opaque native identity of a conversation. Android derives a UUID from the serverCallId; iOS
 * uses the SDK's call ID, which is the serverCallId of an incoming call. Compare it only with
 * other ConversationId values. @category State
 */
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

/** Host-supplied caller identity. Phone values require an international + country code. @category State */
export interface Caller {
  handleType: "phone" | "email" | "generic";
  /** Phone formatting normalizes to E.164; email/generic values are not reinterpreted. */
  value: string;
  displayName: string;
  /** Opaque identifier in the host contact store, not an operating-system Contacts identifier. */
  hostContactID?: string;
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
  readonly caller: Caller;
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

/**
 * Why a conversation failed on iOS, named after ios-voip-sdk's `CallFailure`:
 * - `refused`: the calling framework refused to report or start the call.
 * - `atCapacity`: the device already had as many calls as the app allows.
 * - `notPlaced`: no handler placed the call: none was set, it rejected, or it missed its deadline.
 * - `media`: the call's media wasn't prepared before its deadline.
 * - `audioActivationTimedOut`: the system didn't activate audio before its deadline.
 * - `audioSession`: the audio session couldn't be configured for the call.
 * - `audioRecoveryTimedOut`: audio didn't come back after an interruption or a media services reset.
 * - `connectTimedOut`, `reconnectTimedOut`: the call didn't connect, or reconnect, before its deadline.
 * - `actionTimedOut`: the calling framework stopped waiting for an action on the call.
 * - `frameworkReset`: the calling framework reset, which ends every call.
 * - `server`: the app or its server reported the failure.
 * @category State
 */
export type EndedFailureKind =
  | "refused"
  | "atCapacity"
  | "notPlaced"
  | "media"
  | "audioActivationTimedOut"
  | "audioSession"
  | "audioRecoveryTimedOut"
  | "connectTimedOut"
  | "reconnectTimedOut"
  | "actionTimedOut"
  | "frameworkReset"
  | "server";

/**
 * The native cause of a failed conversation, or `filtered` with its cause when the system
 * filtered an incoming call that ended instead of becoming a call preview.
 * @category State
 */
export type EndedFailure =
  | { readonly kind: EndedFailureKind }
  | { readonly kind: "filtered"; readonly cause: IncomingFilterReason };

/**
 * When and why a conversation ended. iOS adds the native cause of a failure; Android doesn't
 * report one.
 * @category State
 */
export type ConversationEnd =
  | { readonly at: string; readonly reason: Exclude<EndedReason, "failed"> }
  | {
      readonly at: string;
      readonly reason: "failed";
      readonly failure?: EndedFailure;
    };

/** A backend/host ownership signal; local hangs up through endConversation instead. @category Reports */
export interface CallEndedPayload {
  eventId: string;
  serverCallId: string;
  reason: Exclude<EndedReason, "local">;
}

/**
 * Why an incoming conversation is a call preview on this device instead of ringing: the system
 * filtered it, it was answered elsewhere, or the app's presentation policy previews it.
 * @category State
 */
export type PreviewReason =
  | { readonly type: "filtered"; readonly cause: IncomingFilterReason }
  | { readonly type: "answeredElsewhere" }
  | { readonly type: "policy" };

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
  readonly ended?: ConversationEnd;
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
    | {
        /**
         * iOS: the call is live elsewhere and this device follows it without ringing or opening
         * audio. joinConversation joins it; endConversation stops following it.
         */
        readonly status: "previewing";
        readonly direction: "incoming";
        readonly previewReason: PreviewReason;
      }
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
  | {
      /** iOS: the conversation is a call preview and does not ring. */
      readonly status: "previewed";
      readonly id: ConversationId;
      readonly reason: PreviewReason;
    }
  /** iOS: the presentation policy does not show the call on this device. */
  | { readonly status: "notShown" }
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
 * iOS system framework that presents calls, selected at build time. LiveCommunicationKit is the
 * default; CallKit is for apps that need its measured behaviour and can't ship in China mainland.
 * @category Configuration
 */
export type CallingFramework = "liveCommunicationKit" | "callKit";

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
  /** iOS: defaults to liveCommunicationKit. */
  readonly callingFramework?: CallingFramework;
  /** iOS: presents calls in the Simulator in Debug builds. Defaults to false. */
  readonly simulator?: boolean;
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
  /**
   * Correlated server ownership signal, emitted after the snapshot in which the conversation
   * ended or, on iOS, became a call preview.
   */
  readonly answeredElsewhere: {
    conversationId: ConversationId;
    serverCallId: string;
    eventId: string;
  };
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
  /**
   * Resolves once the start handler placed the conversation, so the start handler must not wait
   * for it. Joining a call preview waits for the join handler the same way.
   *
   * Rejects, and the conversation ends as `failed`, when the call isn't placed: with
   * `handler_failed` when the start handler rejects, and with `deadline_exceeded` when it misses
   * its deadline or no handler is set and `unhandledAction` isn't `"accept"`. Without a handler,
   * iOS rejects at once; Android waits for one until the deadline. On iOS the conversation's
   * `ended.failure` is `notPlaced`.
   */
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
  /**
   * iOS holds a connecting or connected report sent before the conversation is placed, from the
   * start or join handler, and applies it once the handler placed the conversation.
   */
  reportConversation(
    id: ConversationId,
    report: ConversationReport
  ): Promise<void>;
  reportIncomingConversation(
    payload: IncomingCallPayload
  ): Promise<IncomingConversationOutcome>;
  /** Accept a known incoming call's server end signal; observe state/events for completion. */
  receiveCallEnded(payload: CallEndedPayload): Promise<boolean>;
  selectAudioRoute(id: string): Promise<void>;
}
