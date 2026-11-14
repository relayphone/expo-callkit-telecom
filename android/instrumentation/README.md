# Android instrumentation

This small application compiles the production conversations sources directly,
without requiring a local Expo prebuild or an Android Studio installation. It is
not an alternate implementation. `NativeIdentityReservationsTest` exercises the
runtime's exact application IDs, including Unicode and surrounding whitespace,
shared incoming ownership, direction collisions and safe reuse after release.
The installed package consumer separately checks raw IDs through the shared SDK
API. Public call IDs are never derived UUIDs.

CI runs `gradle -p android/instrumentation connectedDebugAndroidTest` on an API 35
Google APIs x86_64 emulator with KVM, and retains machine-checked JUnit results.
The Expo bridge/example integration is a separate gate from this native seam.

Query the API 35 emulator from the host with
`node tools/check-android-journey-state.ts emulator-5554`.
An optional second argument selects the application package; the default is
`expo.modules.callkittelecom.instrumentation`. The JSON contains current Telecom
calls and posted notification presence for that package. Each `callId` is a
Telecom platform identity such as `TC@3`; `state` uses the platform state names,
and `endReason` is the native disconnect reason when available, otherwise `null`.
Archived calls and notifications are excluded. Sequential dumps carry a host
monotonic observation interval. Raw dumps, handles and notification content stay
out of the output. The functional runner checks the parser controls and executes
the query on its existing emulator; malformed or unsupported dumps fail the run.

The exploratory DND study and its measurements are preserved on
[`archive/android-telecom-research`](https://github.com/relayphone/expo-callkit-telecom/tree/archive/android-telecom-research).
The active suite keeps `DndMatrixTest` for functional filtering and
`DndWarmCleanupTest` for teardown of prewarmed media after a policy change.
