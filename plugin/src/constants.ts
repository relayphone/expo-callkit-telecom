import type { CallingFramework } from "../../build/conversations/types";

// Default timeout values in seconds
export const DEFAULT_INCOMING_CALL_TIMEOUT = 45;
export const DEFAULT_OUTGOING_CALL_TIMEOUT = 60;
export const DEFAULT_FULFILL_ANSWER_CALL_TIMEOUT = 30;

// Whether completed calls appear in the phone's call history (Recents)
export const DEFAULT_INCLUDES_CALLS_IN_RECENTS = true;

/**
 * Action for the package-internal call-event broadcast (Android). The
 * `androidEventReceiver` plugin prop registers the manifest receiver for it.
 */
export const ANDROID_CALL_EVENT_ACTION =
  "expo.modules.callkittelecom.ACTION_CALL_EVENT";

/**
 * The iOS calling framework when the app doesn't choose one. Change this one value to change
 * the default.
 */
export const DEFAULT_CALLING_FRAMEWORK: CallingFramework = "liveCommunicationKit";

/** The ios-voip-sdk source that the iOS module builds against. */
export const IOS_VOIP_SDK_REPOSITORY =
  "https://github.com/relayphone/ios-voip-sdk.git";
export const IOS_VOIP_SDK_REVISION = "0ade3da9cbadd4841ef9cc6aa20ac33d575d01e5";
