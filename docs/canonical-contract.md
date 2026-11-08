# Conversations contract

The package composes `@relayphone/ios-voip-sdk`: its root re-exports that API and `expo-callkit-telecom/types` exports types only. Install both packages; the SDK supplies the sole iOS provider and this package supplies the Android `VoIPConversations` provider. The SDK is currently consumed from the pinned prepack archive described in [package evaluation](sdk-package-evaluation.md).

Incoming IDs are the exact `serverCallId`. Outgoing calls require an application ID: `startConversation({ id: serverCallId, to: { id: "peer" }, video: false })`. IDs are neither normalized nor converted to UUIDs. Incoming duplicate reports share admission; outgoing duplicates and direction collisions reject with `invalid_arguments`.

The handler decides outgoing **start placement** on Android. Native answer, end, hold and mute run without a handler. Observe snapshots and `actionPerformed`, including its `app` or `system` origin. End publishes the terminal snapshot, the performed event, then removes the call. Server end reports do not fabricate performed actions. Missing start handlers fail unless `unhandledAction: "accept"` is explicit.

App placement finishes before Android Telecom admission. The default SDK placement deadline is 10 seconds; the handler receives an effective deadline one second earlier, **9 seconds** by default, matching iOS. Settlement at or after that effective deadline is rejected. Connection reports during placement are applied only after acceptance. Rejecting, cancelling or timing out placement leaves no Telecom call.

[Core-Telecom's `CallsManager.addCall`](https://developer.android.com/reference/kotlin/androidx/core/telecom/CallsManager#addCall(androidx.core.telecom.CallAttributesCompat,kotlin.coroutines.SuspendFunction1,kotlin.coroutines.SuspendFunction1,kotlin.coroutines.SuspendFunction0,kotlin.coroutines.SuspendFunction0,kotlin.Function1)) permits delaying outgoing admission until the remote side is ready. Its five-second admission and native-callback bounds begin after app placement; they do not limit the app's network operation. The implementation uses [Core-Telecom 1.0.1](https://dl.google.com/dl/android/maven2/androidx/core/core-telecom/1.0.1/core-telecom-1.0.1-sources.jar). The SDK [handler deadline](https://github.com/relayphone/ios-voip-sdk/blob/dabaad1589fbc9c5dfc3b4b998bd9cbbe949bfd4/expo/ios/HandlerActions.swift#L103) reserves the same second for completion.

Configure the composed plugin with canonical SDK options and `androidNativeMedia: "livekit" | "none"` (default `"none"`). This option alone selects the Android native engine and creates its controller. `mediaAdapter` remains `"webrtc" | "none"` for SDK audio integration. The former `joinPolicy` option is rejected. Regenerate native projects after changing selection.

Android audio events come from native lifecycle ownership. `audioActivated` follows successful native audio authority, or successful Core activation when media is app-managed. Hold and disconnect deactivate audio. Native authority's own 1500 ms timeout emits `audioActivationMissing`. Android never emits `providerReset`; that event is iOS-only.

Android advertises `capabilities.tones = false`; `playTones` rejects `unsupported` without a performed event. Preview joining is unsupported on Android; filtered and answered-elsewhere calls terminate and remove their notification. Previews and notification-based preview joins remain iOS-only. Audio routes use canonical `bluetooth` and `other` values.

See the SDK's [iOS and React Native guide](https://github.com/relayphone/ios-voip-sdk/blob/main/docs/react-native.md) for the shared API and iOS setup.
