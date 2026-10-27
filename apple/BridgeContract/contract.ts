/* eslint-disable no-void -- Compile-only expressions exercise the pinned contract. */
/**
 * Compile against the exact checkout in apple/.sdk, never a hand-written SDK copy.
 * This file is not imported by the product. @ts-expect-error entries pin real gaps
 * so closing one requires updating the spike rather than silently widening v2.
 */
import type {
  AudioRoute as SDKAudioRoute,
  ConversationBridgeV2,
  ConversationsSnapshot as SDKSnapshot,
  IncomingConversationOutcome as SDKOutcome,
  JoinPolicy as SDKJoinPolicy,
  ModuleConversationHandler,
} from "../.sdk/ios-voip-sdk/Expo/src/bridge";
import type {
  Caller as SDKCaller,
  IncomingCallPayload as SDKIncoming,
} from "../.sdk/ios-voip-sdk/Expo/src/types";
import type { NativeBridge as SDKNativeBridge } from "../.sdk/ios-voip-sdk/Expo/src/internal/protocol";
import { HandlerDispatcher } from "../../src/conversations/runtime";
import type {
  AudioRoute,
  Caller,
  ConversationsSnapshot,
  HandlerJoinPolicy,
  IncomingCallPayload,
  IncomingConversationOutcome,
  NativeJoinPolicy,
} from "../../src/conversations/types";
import type { NativeTransport } from "../../src/conversations/transport";

export type SDKHandlerRelay = Pick<
  SDKNativeBridge,
  "setHandlerAttached" | "settleAction"
>;

type Same<Left, Right> = (<T>() => T extends Left ? 1 : 2) extends <T>() =>
  T extends Right ? 1 : 2
  ? true
  : false;
const callerShape: Same<Caller, SDKCaller> = true;
void callerShape;

declare const bridge: ConversationBridgeV2;
declare const legacyBridge: SDKNativeBridge;
declare const fork: NativeTransport;
declare const handlerPolicy: HandlerJoinPolicy;
declare const nativePolicy: NativeJoinPolicy;
declare const payload: IncomingCallPayload;
declare const sdkSnapshot: SDKSnapshot;
declare const sdkOutcome: SDKOutcome;
declare const sdkRoute: SDKAudioRoute;

// The existing shared dispatcher can attach/settle native SDK actions at module
// scope. It never installs a raw JS callback as a native continuation owner.
const dispatcher = new HandlerDispatcher(legacyBridge);
void dispatcher;
const relay: SDKHandlerRelay = fork;
void relay;
const sdkHandlerPolicy: SDKJoinPolicy = handlerPolicy;
void sdkHandlerPolicy;

// The fork's readonly inputs can enter the proposed readonly bridge operations.
void bridge.reportIncomingConversation(payload);
const sdkIncoming: SDKIncoming = {
  eventId: "event",
  serverCallId: "call",
  caller: { handleType: "generic", value: "caller", displayName: "Caller" },
  hasVideo: false,
};
void sdkIncoming;
// Variable assignability above accepts extra media structurally; the public SDK
// TS input still omits that private wire child. Native credential decoding and
// redaction need separate runtime evidence, not this type-only check.
const sdkMediaInput: SDKIncoming = {
  ...sdkIncoming,
  // @ts-expect-error SDK public incoming literals cannot yet declare media.
  media: payload.media,
};
void sdkMediaInput;
// @ts-expect-error Credentials are absent from the SDK's public snapshot type.
void sdkSnapshot.conversations[0]?.payload?.media;
const nativeHandler: ModuleConversationHandler<"native"> = {
  start() {},
  end() {},
};
const handlerHandler: ModuleConversationHandler<"handler"> = {
  start() {},
  end() {},
  join() {},
};
void nativeHandler;
void handlerHandler;
// @ts-expect-error Handler policy requires Join; native policy may omit it.
const missingJoin: ModuleConversationHandler<"handler"> = nativeHandler;
void missingJoin;

// The SDK policy requires an authenticated native backend; fork Android config
// currently lacks that child. Do not infer answer permission from credentials.
// @ts-expect-error Native policy needs a named backend in the SDK proposal.
const sdkNativePolicy: SDKJoinPolicy = nativePolicy;
void sdkNativePolicy;

// SDK wire UUIDs require validation/branding before entering the fork, and the
// SDK's broad Conversation interface does not prove status/direction invariants.
// @ts-expect-error Broad SDK snapshots cannot be passed unchecked into v2 state.
const snapshot: ConversationsSnapshot = sdkSnapshot;
void snapshot;
// @ts-expect-error Reported wire IDs and at_capacity error codes need projection.
const outcome: IncomingConversationOutcome = sdkOutcome;
void outcome;
// @ts-expect-error Open native port names need mapping to unknown, not a cast.
const route: AudioRoute = sdkRoute;
void route;

// The published current native declaration is not the proposed v2 bridge either.
// @ts-expect-error Legacy declaration lacks snapshot/routes/outcome/dial projection.
const proposedFromLegacy: ConversationBridgeV2 = legacyBridge;
void proposedFromLegacy;

// The proposed type-only bridge still lacks the accepted #159 API. Its actual
// macro tracer additionally lacks the declared route/outcome/dial operations.
// @ts-expect-error Missing end-signal delivery prevents full NativeTransport parity.
void bridge.receiveCallEnded;
// @ts-expect-error A proposed SDK bridge is not yet the complete fork transport.
const transport: NativeTransport = bridge;
void transport;
