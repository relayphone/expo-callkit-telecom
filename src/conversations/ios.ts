import type { NativeTransport } from "./transport";

/** Exact SDK source used by the compile-only Apple boundary. @category iOS */
export const IOS_SDK_REVISION = "52c2eaba23f61e67e9086a7b2328cf9e293da4ea";

/** Native module name owned by ios-voip-sdk; the fork creates no iOS provider. @category iOS */
export const IOS_SDK_MODULE_NAME = "IosVoipSDK";

/**
 * Required Expo Modules 2 SDK port. The existing pre-M3.5 module does not satisfy it:
 * it lacks the synchronous snapshot, typed incoming outcomes and native media policy.
 * Full runtime composition waits for that SDK bridge rather than inventing replacements.
 * @category iOS
 */
export interface IOSConversationAdapter extends NativeTransport {
  readonly mediaAdapter: "webrtc" | "livekit" | "none";
}
