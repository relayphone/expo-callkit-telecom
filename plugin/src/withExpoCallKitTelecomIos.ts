import {
  type ConfigPlugin,
  IOSConfig,
  withEntitlementsPlist,
  withInfoPlist,
  withPodfile,
  withPodfileProperties,
  withXcodeProject,
} from "expo/config-plugins";
import { copyFileSync, existsSync } from "fs";
import { basename, resolve } from "path";

import {
  DEFAULT_CALLING_FRAMEWORK,
  DEFAULT_FULFILL_ANSWER_CALL_TIMEOUT,
  DEFAULT_INCLUDES_CALLS_IN_RECENTS,
  DEFAULT_INCOMING_CALL_TIMEOUT,
  IOS_VOIP_SDK_REPOSITORY,
  IOS_VOIP_SDK_REVISION,
} from "./constants";
import type { ExpoCallKitTelecomPluginProps } from "./withExpoCallKitTelecom";

const ERROR_MSG_PREFIX = "An error occurred while configuring iOS calls. ";

// Default permission messages
const CAMERA_USAGE = "Allow $(PRODUCT_NAME) to access your camera";
const MICROPHONE_USAGE = "Allow $(PRODUCT_NAME) to access your microphone";

// Required background modes for CallKit and PushKit:
// - voip: Receive VoIP push notifications to wake the app for incoming calls
// - audio: Continue audio playback/recording during calls when app is backgrounded
const BACKGROUND_MODES = ["voip", "audio"];

// SiriKit intents for voice-activated calls
// INStartCallIntent: Unified intent for iOS 13+ (recommended)
// INStartAudioCallIntent/INStartVideoCallIntent: Deprecated in iOS 13, but still
// sent by the system in some cases (e.g., redialing from call history)
const SIRI_INTENTS = [
  "INStartCallIntent",
  "INStartAudioCallIntent",
  "INStartVideoCallIntent",
];

/**
 * Configures camera and microphone permissions for VoIP and video calls.
 */
const withPermissions: ConfigPlugin<{
  cameraPermission?: string | false;
  microphonePermission?: string | false;
}> = (config, { cameraPermission, microphonePermission }) => {
  return IOSConfig.Permissions.createPermissionsPlugin({
    NSCameraUsageDescription: CAMERA_USAGE,
    NSMicrophoneUsageDescription: MICROPHONE_USAGE,
  })(config, {
    NSCameraUsageDescription: cameraPermission,
    NSMicrophoneUsageDescription: microphonePermission,
  });
};

/**
 * Configures push notification entitlement for PushKit VoIP notifications.
 */
const withPushNotificationEntitlement: ConfigPlugin = (config) => {
  return withEntitlementsPlist(config, (config) => {
    const key = "aps-environment";
    // Only set if not already configured; production builds use provisioning profile value
    if (!config.modResults[key]) {
      config.modResults[key] = "development";
    }
    return config;
  });
};

/**
 * Configures UIBackgroundModes for VoIP call handling.
 */
const withBackgroundModes: ConfigPlugin = (config) => {
  return withInfoPlist(config, (config) => {
    const existingModes = config.modResults.UIBackgroundModes;
    const modes = Array.isArray(existingModes)
      ? (existingModes as string[])
      : [];

    const newModes = new Set([...modes, ...BACKGROUND_MODES]);
    config.modResults.UIBackgroundModes = [...newModes];

    return config;
  });
};

/**
 * Configures SiriKit intents for voice-activated audio/video calls.
 */
const withSiriIntents: ConfigPlugin = (config) => {
  return withInfoPlist(config, (config) => {
    const existingIntents = config.modResults.NSUserActivityTypes;
    const intents = Array.isArray(existingIntents)
      ? (existingIntents as string[])
      : [];

    const newIntents = new Set([...intents, ...SIRI_INTENTS]);
    config.modResults.NSUserActivityTypes = [...newIntents];

    return config;
  });
};

/**
 * Configures call timeout values in Info.plist. The SDK's ring deadline covers both incoming
 * rings and outgoing dialing, so iOS has no separate outgoing timeout.
 */
const withTimeouts: ConfigPlugin<{
  incomingCallTimeout?: number;
  fulfillAnswerCallTimeout?: number;
}> = (config, { incomingCallTimeout, fulfillAnswerCallTimeout }) => {
  return withInfoPlist(config, (config) => {
    config.modResults.ExpoCallKitTelecomIncomingCallTimeout =
      incomingCallTimeout ?? DEFAULT_INCOMING_CALL_TIMEOUT;
    config.modResults.ExpoCallKitTelecomFulfillAnswerCallTimeout =
      fulfillAnswerCallTimeout ?? DEFAULT_FULFILL_ANSWER_CALL_TIMEOUT;
    return config;
  });
};

const CALLING_FRAMEWORKS: readonly string[] = ["liveCommunicationKit", "callKit"];
const MEDIA_ADAPTERS: readonly string[] = ["webrtc", "livekit", "none"];
const SDK_SOURCE_MARKER = "# expo-callkit-telecom: ios-voip-sdk source";

/**
 * Adds the ios-voip-sdk pod's source to the Podfile, after `use_expo_modules!`. The module's
 * podspec chooses the SDK's subspecs from the Podfile properties. Running it again replaces the
 * line it added.
 */
export function addSDKSource(podfile: string): string {
  const lines = podfile
    .split("\n")
    .filter((line) => !line.includes(SDK_SOURCE_MARKER));
  const anchor = lines.findIndex((line) => /^\s*use_expo_modules!/.test(line));
  if (anchor < 0) {
    throw new Error(
      `${ERROR_MSG_PREFIX}Unable to find use_expo_modules! in the Podfile.`,
    );
  }
  const indent = /^\s*/.exec(lines[anchor] ?? "")?.[0] ?? "";
  lines.splice(
    anchor + 1,
    0,
    `${indent}pod 'IosVoipSDK/Core', :git => '${IOS_VOIP_SDK_REPOSITORY}', ` +
      `:commit => '${IOS_VOIP_SDK_REVISION}' ${SDK_SOURCE_MARKER}`,
  );
  return lines.join("\n");
}

/**
 * Selects the calling framework, the media integration and the Debug simulator runtime for the
 * module's podspec, and adds the SDK's source to the Podfile. Regenerate the iOS project after
 * changing them.
 */
const withCallCore: ConfigPlugin<{
  callingFramework?: string;
  mediaAdapter?: string;
  simulator?: boolean;
  unhandledAction?: string;
}> = (config, { callingFramework, mediaAdapter, simulator, unhandledAction }) => {
  const framework = callingFramework ?? DEFAULT_CALLING_FRAMEWORK;
  if (!CALLING_FRAMEWORKS.includes(framework)) {
    throw new Error(
      `${ERROR_MSG_PREFIX}"callingFramework" must be one of ` +
        `${CALLING_FRAMEWORKS.join(", ")}.`,
    );
  }
  const media = mediaAdapter ?? "webrtc";
  if (!MEDIA_ADAPTERS.includes(media)) {
    throw new Error(
      `${ERROR_MSG_PREFIX}"mediaAdapter" must be one of ${MEDIA_ADAPTERS.join(", ")}.`,
    );
  }
  config = withPodfileProperties(config, (config) => {
    config.modResults["expo-callkit-telecom.callingFramework"] = framework;
    config.modResults["expo-callkit-telecom.mediaAdapter"] = media;
    config.modResults["expo-callkit-telecom.simulator"] = String(
      simulator ?? false,
    );
    return config;
  });
  config = withPodfile(config, (config) => {
    config.modResults.contents = addSDKSource(config.modResults.contents);
    return config;
  });
  return withInfoPlist(config, (config) => {
    config.modResults.ExpoCallKitTelecomMediaAdapter = media;
    config.modResults.ExpoCallKitTelecomUnhandledAction =
      unhandledAction ?? "fail";
    return config;
  });
};

/**
 * Configures whether calls appear in the phone's call history (Recents).
 */
const withRecents: ConfigPlugin<{ includesCallsInRecents?: boolean }> = (
  config,
  { includesCallsInRecents },
) => {
  return withInfoPlist(config, (config) => {
    config.modResults.ExpoCallKitTelecomIncludesCallsInRecents =
      includesCallsInRecents ?? DEFAULT_INCLUDES_CALLS_IN_RECENTS;
    return config;
  });
};

/**
 * Copies a resource file into the iOS project directory and registers it in the
 * Xcode project so it lands in the app bundle. Shared by the sound-file and
 * icon-template plugins. The caller validates that `sourcePath` exists first, so
 * it can emit a context-specific error message.
 */
function copyResourceFileToProject(
  config: Parameters<Parameters<typeof withXcodeProject>[1]>[0],
  sourcePath: string,
  projectName: string,
) {
  const filename = basename(sourcePath);
  const destinationPath = resolve(
    config.modRequest.projectRoot,
    "ios",
    projectName,
    filename,
  );

  // Copy the file to the iOS project directory
  copyFileSync(sourcePath, destinationPath);

  // Add the file to the Xcode project if not already present
  if (!config.modResults.hasFile(`${projectName}/${filename}`)) {
    config.modResults = IOSConfig.XcodeUtils.addResourceFileToGroup({
      filepath: `${projectName}/${filename}`,
      groupName: projectName,
      isBuildFile: true,
      project: config.modResults,
    });
  }

  return config;
}

/**
 * Copies sound files into the iOS project bundle.
 */
function setSoundFiles(
  config: Parameters<Parameters<typeof withXcodeProject>[1]>[0],
  sounds: string[],
) {
  const projectRoot = config.modRequest.projectRoot;
  const projectName = config.modRequest.projectName;

  if (!projectName) {
    throw new Error(`${ERROR_MSG_PREFIX}Unable to find iOS project name.`);
  }

  for (const soundPath of sounds) {
    const sourcePath = resolve(projectRoot, soundPath);

    if (!existsSync(sourcePath)) {
      throw new Error(`${ERROR_MSG_PREFIX}Sound file not found: ${sourcePath}`);
    }

    config = copyResourceFileToProject(config, sourcePath, projectName);
  }

  return config;
}

/**
 * Copies sound files into the iOS project bundle.
 */
const withSounds: ConfigPlugin<{ sounds?: string[] }> = (
  config,
  { sounds },
) => {
  if (sounds && sounds.length > 0) {
    config = withXcodeProject(config, (config) => {
      return setSoundFiles(config, sounds);
    });
  }
  return config;
};

/**
 * Configures the default ringtone for incoming calls in Info.plist.
 */
const withDefaultRingtone: ConfigPlugin<{
  sounds?: string[];
  defaultRingtone?: string;
}> = (config, { sounds, defaultRingtone }) => {
  const soundFilenames = sounds?.map((s) => basename(s)) ?? [];

  // Validate defaultRingtone if specified and not 'default'
  if (defaultRingtone && defaultRingtone !== "default") {
    if (soundFilenames.length === 0) {
      throw new Error(
        `${ERROR_MSG_PREFIX}"defaultRingtone" was specified but no ` +
          `sounds were provided.`,
      );
    }
    if (!soundFilenames.includes(defaultRingtone)) {
      throw new Error(
        `${ERROR_MSG_PREFIX}"defaultRingtone" must be one of the provided ` +
          `sounds (${soundFilenames.join(", ")}) or "default" for ` +
          `system ringtone.`,
      );
    }
  }

  return withInfoPlist(config, (config) => {
    config.modResults.ExpoCallKitTelecomDefaultRingtone = defaultRingtone || "default";
    return config;
  });
};

/**
 * Bundles a CallKit icon template into the iOS app and records its filename in
 * Info.plist, so the native side can load it into
 * `CXProviderConfiguration.iconTemplateImageData`.
 *
 * CallKit renders this image as a *template*: only the alpha channel is used
 * (RGB is ignored) and the system tints it. Provide a ~40x40pt square PNG whose
 * alpha describes the glyph.
 */
const withIconTemplate: ConfigPlugin<{
  sounds?: string[];
  iconTemplateIos?: string;
}> = (config, { sounds, iconTemplateIos }) => {
  if (!iconTemplateIos) {
    return config;
  }

  const iconFilename = basename(iconTemplateIos);

  // Sounds and the icon are copied flat into the same directory by filename,
  // so a shared name would silently overwrite one with the other.
  if (sounds?.some((s) => basename(s) === iconFilename)) {
    throw new Error(
      `${ERROR_MSG_PREFIX}"iconTemplateIos" filename "${iconFilename}" ` +
        `collides with a file in "sounds".`,
    );
  }

  // Copy the file into the iOS project and add it as a bundle resource.
  config = withXcodeProject(config, (config) => {
    const projectName = config.modRequest.projectName;

    if (!projectName) {
      throw new Error(`${ERROR_MSG_PREFIX}Unable to find iOS project name.`);
    }

    const sourcePath = resolve(config.modRequest.projectRoot, iconTemplateIos);

    if (!existsSync(sourcePath)) {
      throw new Error(
        `${ERROR_MSG_PREFIX}Icon template file not found: ${sourcePath}`,
      );
    }

    return copyResourceFileToProject(config, sourcePath, projectName);
  });

  // Record the filename so the native side can resolve it from the bundle.
  config = withInfoPlist(config, (config) => {
    config.modResults.ExpoCallKitTelecomIconTemplate = iconFilename;
    return config;
  });

  return config;
};

export const withExpoCallKitTelecomIos: ConfigPlugin<ExpoCallKitTelecomPluginProps> = (
  config,
  {
    cameraPermission,
    microphonePermission,
    incomingCallTimeout,
    fulfillAnswerCallTimeout,
    includesCallsInRecents,
    sounds,
    defaultRingtoneIos,
    iconTemplateIos,
    callingFramework,
    mediaAdapter,
    simulator,
    unhandledAction,
  },
) => {
  config = withCallCore(config, {
    callingFramework,
    mediaAdapter,
    simulator,
    unhandledAction,
  });
  config = withPermissions(config, { cameraPermission, microphonePermission });
  config = withPushNotificationEntitlement(config);
  config = withBackgroundModes(config);
  config = withSiriIntents(config);
  config = withTimeouts(config, {
    incomingCallTimeout,
    fulfillAnswerCallTimeout,
  });
  config = withRecents(config, { includesCallsInRecents });
  config = withSounds(config, { sounds });
  config = withDefaultRingtone(config, {
    sounds,
    defaultRingtone: defaultRingtoneIos,
  });
  config = withIconTemplate(config, { sounds, iconTemplateIos });

  return config;
};
