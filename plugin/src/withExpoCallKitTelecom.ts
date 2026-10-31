import { type ConfigPlugin, createRunOncePlugin } from "expo/config-plugins";

import pkg from "../../package.json";
import { withExpoCallKitTelecomAndroid } from "./withExpoCallKitTelecomAndroid";
import { withExpoCallKitTelecomIos } from "./withExpoCallKitTelecomIos";

import type { JoinPolicy, MediaAdapter } from "../../build/conversations/types";

export type ExpoCallKitTelecomPluginProps = {
  /**
   * Native answer and media/JS pre-warm are the default.
   * @platform android
   */
  joinPolicy?: JoinPolicy;
  /**
   * On iOS, "webrtc" and "livekit" both coordinate WebRTC's `RTCAudioSession`, and "none" leaves
   * the audio session to the app.
   *
   * On Android, "livekit" adds the native media engine to the build: livekit-android, its WebRTC
   * build and JitPack repository, and OkHttp. Android's built-in LiveKit and WebRTC sessions and its
   * HTTPS credentials fetcher run on it, and the default `joinPolicy` uses LiveKit media. With
   * "webrtc" or "none", the build adds none of them, and native media needs a session registered in
   * `NativeMediaRegistry.factories` and credentials from a registered native fetcher. A native
   * `joinPolicy` with LiveKit media needs "livekit".
   * @default "webrtc"
   */
  mediaAdapter?: MediaAdapter;
  unhandledAction?: "fail" | "accept";
  /**
   * Presents calls in the iOS Simulator in Debug builds, with ios-voip-sdk's simulator runtime.
   * Release builds and devices are unaffected.
   * @default false
   * @platform ios
   */
  simulator?: boolean;
  /**
   * Custom message for microphone permission prompt. Set to false to skip.
   * @platform ios
   */
  microphonePermission?: string | false;
  /**
   * Custom message for camera permission prompt. Set to false to skip.
   * @platform ios
   */
  cameraPermission?: string | false;
  /**
   * Timeout in seconds for incoming calls before they are marked as unanswered. On iOS it also
   * bounds how long an outgoing call dials.
   * @default 45
   * @platform ios
   * @platform android
   */
  incomingCallTimeout?: number;
  /**
   * Timeout in seconds for outgoing calls to connect before they are marked as unanswered.
   * @default 60
   * @platform android
   */
  outgoingCallTimeout?: number;
  /**
   * Timeout in seconds for waiting for the call to connect after answering.
   * @default 30
   * @platform ios
   * @platform android
   */
  fulfillAnswerCallTimeout?: number;
  /**
   * Whether the system's Recents list includes a call after it ends. Sets LiveCommunicationKit's
   * `ConversationManager.Configuration.includesConversationInRecents`.
   * @default true
   * @platform ios
   */
  includesCallsInRecents?: boolean;
  /**
   * Array of sound file paths (relative to project root) to include in the app.
   * These files will be copied into the iOS bundle and Android raw resources.
   * .wav recommended
   * @platform ios
   * @platform android
   */
  sounds?: string[];
  /**
   * The ringtone for incoming calls on iOS: the filename (with extension) of one of the provided
   * sounds, which the plugin bundles with the app, or 'default' for the system ringtone. Sets
   * LiveCommunicationKit's `ConversationManager.Configuration.ringtoneName`, the name of the
   * sound resource in the app bundle for the app's ringtone.
   * @default 'default'
   * @platform ios
   */
  defaultRingtoneIos?: string;
  /**
   * The default ringtone for incoming calls on Android (notification channel).
   * Can be the filename (with extension) of one of the provided sounds,
   * or 'default' to use the system ringtone.
   * @default 'default'
   * @platform android
   */
  defaultRingtoneAndroid?: string;
  /**
   * The default dialtone to play during outgoing calls while connecting.
   * Must be the filename (with extension) of one of the provided sounds.
   * @platform android
   */
  defaultDialtone?: string;
  /**
   * Path (relative to the project root) to a PNG of the app's icon for the system's call UI on
   * iOS. The plugin bundles it with the app, and the module passes its data to
   * LiveCommunicationKit's `ConversationManager.Configuration.iconTemplateImageData`, which
   * Apple documents only as the PNG data for the app's icon image. The property is named for a
   * template image, so provide a square PNG, about 40x40 points, whose alpha channel describes
   * the glyph. When omitted, the module passes no icon.
   * @platform ios
   */
  iconTemplateIos?: string;
  /**
   * Class name of a `BroadcastReceiver` to register for the module's call-event
   * broadcast, fired when a call event can't reach a live JS observer (e.g. the
   * user declines an incoming call while the app is killed). The plugin writes
   * the manifest `<receiver>` + `<intent-filter>`. You supply the receiver class
   * (e.g. via a local Expo module); the plugin does not generate it.
   *
   * Accepts a manifest-relative name (`.CallEventReceiver`) or a fully-qualified
   * one (`com.acme.app.CallEventReceiver`). See "Call ended while the app is
   * killed" in the docs.
   * @platform android
   */
  androidEventReceiver?: string;
};

const withExpoCallKitTelecom: ConfigPlugin<
  ExpoCallKitTelecomPluginProps | void
> = (config, props) => {
  const opts: ExpoCallKitTelecomPluginProps = props ?? {};
  config = withExpoCallKitTelecomAndroid(config, opts);
  config = withExpoCallKitTelecomIos(config, opts);

  return config;
};

export default createRunOncePlugin(
  withExpoCallKitTelecom,
  pkg.name,
  pkg.version
);
