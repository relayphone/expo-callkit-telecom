# Conversations contract

The package composes `@relayphone/ios-voip-sdk`: its root re-exports that API and `expo-callkit-telecom/types` exports types only. Install the fork tarball together with the SDK's v0.5.0 release asset as described in [package evaluation](sdk-package-evaluation.md). The SDK supplies the sole iOS provider and this package supplies the Android `VoIPConversations` provider.

Incoming IDs are the exact `serverCallId`. Outgoing calls require an application ID: `startConversation({ id: serverCallId, to: { id: "peer" }, video: false })`. IDs are neither normalized nor converted to UUIDs. Incoming duplicate reports share admission; outgoing duplicates and direction collisions reject with `invalid_arguments`.

The JavaScript handler decides outgoing **start placement** on Android. Native answer, end, hold and mute can use an optional [native server action handler](android-quick-start.md#server-actions); without one they execute directly. Answer and app controls can be refused with code `refused` and the handler's reason. System hold/mute are observations of OS state, and decline/end always disconnect. Observe snapshots and `actionPerformed`, including its `app` or `system` origin. End publishes the terminal snapshot, the performed event, then removes the call. A server end during an accepted local End or Decline settles that original action immediately and exactly once. Without a pending local action, server reports do not fabricate performed actions. Missing start handlers fail unless `unhandledAction: "accept"` is explicit.

App placement finishes before Android Telecom admission. The default SDK placement deadline is 10 seconds; the handler receives an effective deadline one second earlier, **9 seconds** by default, matching iOS. Settlement at or after that effective deadline is rejected. Connection reports during placement are applied only after acceptance. Rejecting, cancelling or timing out placement leaves no Telecom call.

[Core-Telecom's `CallsManager.addCall`](https://developer.android.com/reference/kotlin/androidx/core/telecom/CallsManager#addCall(androidx.core.telecom.CallAttributesCompat,kotlin.coroutines.SuspendFunction1,kotlin.coroutines.SuspendFunction1,kotlin.coroutines.SuspendFunction0,kotlin.coroutines.SuspendFunction0,kotlin.Function1)) permits delaying outgoing admission until the remote side is ready. Its five-second admission and native-callback bounds begin after app placement; they do not limit the app's network operation. The implementation uses [Core-Telecom 1.0.1](https://dl.google.com/dl/android/maven2/androidx/core/core-telecom/1.0.1/core-telecom-1.0.1-sources.jar). The SDK [handler deadline](https://github.com/relayphone/ios-voip-sdk/blob/597c39137b51b2537ce60e15e6ecf86d281f5d86/expo/ios/HandlerActions.swift) reserves the same second for completion.

Configure the composed plugin with canonical SDK options and `androidNativeMedia: "livekit" | "none"` (default `"none"`). This option alone selects the Android native engine and creates its controller. `mediaAdapter` remains `"webrtc" | "none"` for SDK audio integration. The former `joinPolicy` option is rejected. Regenerate native projects after changing selection.

Android audio events come from native lifecycle ownership. `audioActivated` follows successful native audio authority, or successful Core activation when media is app-managed. Hold and disconnect deactivate audio. Native authority's own 1500 ms timeout emits `audioActivationMissing`. Android never emits `providerReset`; that event is iOS-only.

Android advertises `capabilities.tones = true` only with native LiveKit media selected. `playTones` publishes SIP DTMF through an active native session and emits an app-origin performed event after success. App-managed media rejects tones with `unsupported` without a performed event. Preview joining is unsupported on Android; filtered and answered-elsewhere calls terminate and remove their notification. Previews and notification-based preview joins remain iOS-only. Audio routes use canonical `bluetooth` and `other` values.

See the SDK's [iOS and React Native guide](https://github.com/relayphone/ios-voip-sdk/blob/v0.5.0/docs/react-native.md) for the shared API and iOS setup.

## Plugin composition and native project upgrades

Configure the fork once; it invokes the SDK's supported iOS plugin:

```ts
plugins: [
  ["expo-callkit-telecom", { mediaAdapter: "none", androidNativeMedia: "livekit" }],
]
```

When an app also lists the SDK plugin explicitly, the first SDK invocation supplies the iOS
options, including an invocation made by the fork. The first fork invocation supplies Android
options. Later duplicate entries keep those choices. Keep shared settings in one fork entry to
make the platform choices clear. Repeated prebuild preserves one SDK provider, one configured
call-preview widget and one set of Focus filter sources.

Projects generated with the former iOS provider need clean regeneration. Prebuild reports the
retired `ExpoCallKitTelecom` integration when it finds its locked pod, generated SDK Git pin or
old Podfile properties, including widget settings. Commit native customizations, run
`npx expo prebuild --clean`, then rebuild the development client. Reapply intentional native
customizations to the regenerated project. This replaces old widget targets, source registrations
and provider autolinking together. Use the same clean regeneration when upgrading an existing
Android native project or changing `androidNativeMedia`.

Rebuild installed clients after upgrading the native provider. A client reporting that
`VoIPConversations` is missing still needs the new native module. The package evaluation checks
also exercise canonical command errors and route updates through the installed SDK runtime.
