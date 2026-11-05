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
 * Why a conversation failed, named after ios-voip-sdk's `CallFailure`. iOS can report every kind;
 * Android reports `notPlaced`, `media`, `connectTimedOut` and `server`, and omits the failure
 * when it doesn't know the cause.
 * - `refused` (iOS): the calling framework refused to report or start the call.
 * - `atCapacity` (iOS): the device already had as many calls as the app allows.
 * - `notPlaced`: no handler placed the call: none was set, it rejected, or it missed its deadline.
 *   On Android, also an incoming call that the `join` handler didn't join, under the handler join
 *   policy.
 * - `media`: on iOS, the call's media wasn't prepared before its deadline. On Android, the native
 *   media session couldn't be prepared, started or kept running.
 * - `audioActivationTimedOut` (iOS): the system didn't activate audio before its deadline.
 * - `audioSession` (iOS): the audio session couldn't be configured for the call.
 * - `audioRecoveryTimedOut` (iOS): audio didn't come back after an interruption or a media
 *   services reset.
 * - `connectTimedOut`: the call didn't connect before its deadline. On Android, an answered call
 *   that wasn't reported connected within `fulfillAnswerCallTimeout`.
 * - `reconnectTimedOut` (iOS): the call didn't reconnect before its deadline.
 * - `actionTimedOut` (iOS): the calling framework stopped waiting for an action on the call.
 * - `frameworkReset` (iOS): the calling framework reset, which ends every call.
 * - `server`: the app or its server reported the failure, with `reportConversation` or with a
 *   `failed` server end signal.
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
 * The native cause of a failed conversation, or, on iOS, `filtered` with its cause when the
 * system filtered an incoming call that ended instead of becoming a call preview.
 * @category State
 */
export type EndedFailure =
  | { readonly kind: EndedFailureKind }
  | { readonly kind: "filtered"; readonly cause: IncomingFilterReason };

/**
 * When and why a conversation ended. A failed conversation carries its native cause when the
 * platform knows it; see {@link EndedFailureKind} for the kinds each platform reports.
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
  /**
   * iOS: `"native"` once the app's native media, registered with `NativeCallMedia.register` in
   * Swift, prepared the conversation's media, for example by joining its room before JavaScript
   * loaded. Don't connect its media from JavaScript too.
   */
  readonly media?: "native";
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
  /** On iOS, `joinFailed` aborts a join that ended before the call took part. */
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
  /** iOS: presents calls in the Simulator in Debug builds. Defaults to false. */
  readonly simulator?: boolean;
  /**
   * iOS: posts a call notification for each call that Do Not Disturb or a Focus filtered.
   * Defaults to false. See {@link CallNotificationEvent}.
   */
  readonly callNotifications?: boolean;
  /**
   * iOS: shows call previews as Live Activities, from a generated widget extension unless
   * `widgetExtension` is false. Defaults to false. See {@link CallPreviewActivities}.
   */
  readonly callPreviewActivities?:
    | boolean
    | { readonly widgetExtension?: boolean };
  /**
   * iOS: reads the app's Focus filter state from the filter's App Group, and compiles the
   * filter's Swift `sources` in the app. See {@link FocusFilterState}.
   */
  readonly focusFilter?: {
    readonly appGroup: string;
    readonly sources?: readonly string[];
  };
}

/** iOS: what a call notification tells the user. @category Events */
export type CallNotificationKind = "incoming" | "missed";

/**
 * iOS: why the system didn't post a call notification.
 * - `notAuthorized`: the user hasn't allowed the app's notifications. The module never asks;
 *   ask with expo-notifications.
 * - `intentRejected`: the system didn't make it a communication notification, for example
 *   because the app lacks the Communication Notifications capability.
 * - `postFailed`: the system refused the notification.
 * @category Events
 */
export type CallNotificationError =
  | "notAuthorized"
  | "intentRejected"
  | "postFailed";

/**
 * iOS: a call notification, a communication notification from the caller about a conversation
 * that the system filtered. A Focus lets it through for the people it allows, and it never rings.
 * @category Events
 */
export interface CallNotification {
  readonly conversationId: ConversationId;
  /** `incoming` while the conversation is a call preview; `missed` once it ended unjoined. */
  readonly kind: CallNotificationKind;
  readonly caller: Participant;
  readonly video: boolean;
}

/**
 * iOS, with the `callNotifications` plugin option: what happened to a conversation's call
 * notification. The module posts one when the system filters an incoming conversation because of
 * Do Not Disturb or a Focus, and it becomes a call preview. It withdraws the notification when
 * the conversation is joined here, and replaces it with a `missed` one when the conversation ends
 * without being joined here.
 * @category Events
 */
export type CallNotificationEvent =
  | ({ readonly type: "posted" } & CallNotification)
  | { readonly type: "withdrawn"; readonly conversationId: ConversationId }
  | ({
      readonly type: "failed";
      readonly error: CallNotificationError;
    } & CallNotification);

/**
 * iOS: where the user's response to a call notification led. The module routes the responses
 * natively, as they arrive, before JavaScript loads.
 * - `joining`: the conversation is live in a call preview here, and the module asked to join it,
 *   because the user chose Join or tapped the notification. The join handler places it, as for
 *   `joinConversation`.
 * - `callBack`: the conversation is over. Show the caller, and call them back with
 *   `startConversation({ to: caller, video })`.
 * @category Events
 */
export type CallNotificationRoute =
  | { readonly type: "joining"; readonly conversationId: ConversationId }
  | {
      readonly type: "callBack";
      readonly caller: Participant;
      readonly video: boolean;
    };

/**
 * iOS: whether the user allows the app's Live Activities, and their frequent updates. The system
 * asks the user the first time one of the app's Live Activities appears; the module never asks.
 * @category Call previews
 */
export interface LiveActivityAuthorization {
  readonly activitiesEnabled: boolean;
  readonly frequentPushesEnabled: boolean;
}

/**
 * iOS: the push token of a call preview's Live Activity, with which the app's servers update and
 * end it.
 * @category Call previews
 */
export interface CallPreviewPushToken {
  readonly conversationId: ConversationId;
  /** The APNs token, hex-encoded. */
  readonly token: string;
}

/**
 * iOS, with the `callPreviewActivities` plugin option: what the app's servers need to start,
 * update and end call previews' Live Activities with ActivityKit pushes. Send all of it to them,
 * and don't start previews on a device whose user turned Live Activities off.
 * @category Call previews
 */
export interface CallPreviewActivities {
  readonly authorization: LiveActivityAuthorization;
  /**
   * The device's push-to-start token, hex-encoded, with which the servers start call previews,
   * or null until the system issues one.
   */
  readonly pushToStartToken: string | null;
  /**
   * The push token of each call preview's Live Activity that has one. A preview's token leaves
   * the list when the preview ends; stop sending its pushes then.
   */
  readonly pushTokens: readonly CallPreviewPushToken[];
}

/**
 * iOS: why the module couldn't start a call preview's Live Activity.
 * - `activitiesDisabled`: the user turned the app's Live Activities off.
 * - `notInForeground`: the system lets an app start a Live Activity only in the foreground; the
 *   app's servers can start the preview with the push-to-start token.
 * - `tooLarge`: the attributes and the state exceed the system's 4 KB, usually because of the
 *   conversation's metadata.
 * - `refused`: the system refused for another reason, such as too many Live Activities.
 * @category Call previews
 */
export type CallPreviewStartError =
  | "activitiesDisabled"
  | "notInForeground"
  | "tooLarge"
  | "refused";

/**
 * iOS, with the `callPreviewActivities` plugin option: what happened to a conversation's call
 * preview Live Activity, whether the module or the app's servers started it.
 * - `started`: a Live Activity shows the conversation's preview.
 * - `startFailed`: the module couldn't start one when the conversation became a call preview.
 * - `ended`: the preview's Live Activity ended: this device joined the conversation, the
 *   conversation ended, or the user, the system or the servers ended it.
 * @category Call previews
 */
export type CallPreviewEvent =
  | { readonly type: "started"; readonly conversationId: ConversationId }
  | {
      readonly type: "startFailed";
      readonly conversationId: ConversationId;
      readonly error: CallPreviewStartError;
    }
  | { readonly type: "ended"; readonly conversationId: ConversationId };

/**
 * iOS, with the `focusFilter` plugin option: what the app's Focus filter last asked for, as
 * ios-voip-sdk encodes it for the app's servers. Send it to them unchanged; they choose how each
 * call appears on this device, ringing or as a call preview.
 *
 * The system runs the filter when a Focus that has it turns on, and again when that Focus turns
 * off, with every parameter reset to its default. So the state is `active` with the settings
 * that the parameters ask for, or `inactive` when they equal the defaults, and `unknown` until the
 * filter first runs. It doesn't say whether a Focus is on: a Focus without the filter still
 * filters the app's calls.
 *
 * - `since`: when the filter started asking for this, in seconds since 1970 (Unix time), as the
 *   servers receive it from ios-voip-sdk elsewhere.
 * - `settings`: the app's settings as its Swift `Settings` type encodes them, such as
 *   `{ "ringing": "favorites" }`. The module doesn't check them.
 * @category Focus
 */
export type FocusFilterState =
  | { readonly state: "unknown" }
  | { readonly state: "inactive"; readonly since: number }
  | {
      readonly state: "active";
      readonly since: number;
      readonly settings: JSONValue;
    };

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
  /** iOS native media: also emitted for SDK audio interruptions and media services resets. */
  readonly audioDeactivated: { readonly conversationId?: ConversationId };
  readonly audioActivationMissing: { readonly conversationId: ConversationId };
  readonly audioActivationFailed: { readonly conversationId: ConversationId };
  readonly pushTokenUpdated: PushToken | null;
  /**
   * iOS: the user asked to call a handle from Recents, Siri or Contacts. The latest request waits
   * for a listener. With `callNotifications`, a request for a party whose conversation is live in
   * a call preview joins that conversation instead.
   */
  readonly dialRequest: { readonly handle: string; readonly video: boolean };
  /** iOS, with `callNotifications`: what happened to a call notification. */
  readonly callNotification: CallNotificationEvent;
  /**
   * iOS, with `callNotifications`: where the user's response to a call notification led. The
   * latest route waits for a listener, so a response that launched the app reaches it.
   */
  readonly callNotificationRoute: CallNotificationRoute;
  /** iOS, with `callPreviewActivities`: what happened to a call preview's Live Activity. */
  readonly callPreview: CallPreviewEvent;
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
   * iOS rejects at once; Android waits for one until the deadline. The conversation's
   * `ended.failure.kind` is `notPlaced`.
   */
  startConversation(options: StartConversationOptions): Promise<ConversationId>;
  /**
   * Answers a ringing conversation, or, on iOS, joins a call preview. Joining waits for the join
   * handler, as a start waits for the start handler, and resolves once the handler placed the
   * conversation; on iOS, answering doesn't wait for a handler.
   *
   * Rejects when the join handler doesn't place the conversation: with `handler_failed` when it
   * rejects, and with `deadline_exceeded` when it misses its deadline. On iOS the conversation
   * returns to `previewing`. On Android, where the join handler runs only under the handler join
   * policy, the conversation ends as `failed` with `failure.kind` `notPlaced`.
   */
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
