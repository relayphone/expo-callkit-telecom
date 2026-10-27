import type { NativeTransport } from "./transport";

/** Private M3.5 SDK source used by the draft Apple adapter spike. @category iOS */
export const IOS_SDK_REVISION = "78bd2f1595b4cc2b9773ba6e254a6f2be5a80b7b";

/** Native module name owned by ios-voip-sdk; the fork creates no iOS provider. @category iOS */
export const IOS_SDK_MODULE_NAME = "IosVoipSDK";

/**
 * Required Expo Modules 2 SDK port. The private M3.5 tracer has a real synchronous
 * snapshot and native Join/pre-warm hooks, but its module is not this complete port.
 * Routes, incoming-outcome projection, end-signal delivery and dial-listener attachment
 * remain integration requirements. BridgeContract compiles against the pinned SDK;
 * shipping runtime composition waits for M3.5 on main and these gaps to close.
 * @category iOS
 */
export interface IOSConversationAdapter extends NativeTransport {
  readonly mediaAdapter: "webrtc" | "livekit" | "none";
}
