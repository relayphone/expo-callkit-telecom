# Android instrumentation

This small application compiles the production conversations sources directly,
without requiring a local Expo prebuild or an Android Studio installation. It is
not an alternate implementation. The initial identity test reads the same
`android/src/androidTest/assets/call-identity.json` used by the TypeScript contract
suite. `tools/check-call-identity.mjs` verifies that copy byte-for-byte against a
pinned commit in ios-voip-sdk.

CI runs `gradle -p android/instrumentation connectedDebugAndroidTest` on an API 35
Google APIs x86_64 emulator with KVM, and retains machine-checked JUnit results.
The Expo bridge/example integration is a separate gate from this native seam.
