# Android answer and notification navigation

Audio answers preserve the call's audio-only platform state. On transactional
Android 14–16, the SDK activates an incoming audio call through Core-Telecom
`setActive()`, avoiding the platform's answer call-type remapping bug. Video
answers still use `answer(CALL_TYPE_VIDEO_CALL)`. Legacy answers use Core-Telecom
1.1.0's call-type conversion; Android 17+ uses the normal answer API.

These are different API domains: legacy `Connection.setVideoState` takes
`VideoProfile` flags, while transactional `CallControl.answer` validates
`CallAttributes` call types. Passing audio-only `VideoProfile` value zero to the
transactional answer API is invalid. Core-Telecom 1.1.0 adds legacy conversion and
a recovery mechanism for unsolicited video upgrades on Android 15–16, but that
recovery does not prevent the initial transition and is unavailable on Android 14.

Sources: [Core-Telecom 1.0.1 sources](https://dl.google.com/dl/android/maven2/androidx/core/core-telecom/1.0.1/core-telecom-1.0.1-sources.jar)
(`CallSessionLegacy.kt` lines 443–446 and `CallSession.kt` lines 474–479, 614–616),
[Core-Telecom 1.1.0 legacy conversion](https://android.googlesource.com/platform/frameworks/support/+/b44a77505e345a9f0af437b0405e3f6cd3b534b2/core/core-telecom/src/main/java/androidx/core/telecom/internal/CallSessionLegacy.kt#549),
and [platform CallControl](https://developer.android.com/reference/android/telecom/CallControl).

The standard incoming CallStyle Answer action opens the package's launch Activity
directly. An Expo Activity lifecycle listener handles its conversation ID and
one-use native answer delivery in `onCreate` or `onNewIntent`, independently of
JavaScript readiness. Recreating the Activity or replaying an already consumed
answer does not answer again. Decline and Hang Up remain broadcast actions.

Incoming and ongoing notification bodies open that same launch Activity with the
conversation ID, without an answer action. An app-supplied `contentIntent` takes
precedence. Ending notifications retain their existing controls.

To choose another Activity, configure and rebuild:

```ts
["expo-callkit-telecom", { androidCallActivity: ".CallActivity" }]
```

The Activity must be declared by the app. Bare or dot-prefixed relative names
resolve against the app package; fully qualified class names are also accepted. An Expo `ReactActivity`
receives the native lifecycle listener automatically. A plain Activity must call
`ConversationNotificationIntents.receive(this, intent)` in `onCreate` and for
each `onNewIntent`; the same native one-use check protects both entry points.
