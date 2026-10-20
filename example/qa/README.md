# Native push isolation regression

This XCTest runs the real Expo example, native module, JS bridge and CallKit
objects with the separate simulator runtime spike. It connects outgoing call A,
records its UUID, explicitly triggers malformed synthetic PushKit delivery B,
waits for a payload-free completion receipt, then checks A's unchanged identity,
connected state, mute round trip and final hangup. It uses no APNs credentials.
The receipt establishes delivery/completion, not exactly-once semantics or APNs
watchdog/cold-launch compliance. Runtime and library PRs remain draft.

Prepare the example using its normal Bun/prebuild/pod installation workflow.
Then run from the repository root:

```sh
bash example/qa/run-ios-push-isolation.sh /absolute/path/to/callkit-simulator
```

Ruby must load the `xcodeproj` gem supplied with CocoaPods. With Homebrew
CocoaPods, prefix the command with
`GEM_HOME="$(brew --prefix cocoapods)/libexec"` when necessary. An optional
`QA_DERIVED_DATA` reuses a caller-owned local build directory; the script does
not delete that directory. The default build and run-owned simulator are deleted
on exit. `OUT` must be fresh if explicitly supplied.

`QA_FORCE_LEGACY_PUSH=1` compiles both the fixture and native delegate on their
legacy callback paths, even with a newer SDK. The expected legacy completion
receipt is still required. This exercises fallback behavior; it does not claim
that the whole Expo dependency stack builds with an older Xcode. CI also sets
`QA_EXPECT_PUSH_API=metadata|legacy`, so a lane cannot silently exercise the
wrong callback because the selected SDK lacks a header.

Only the generated, ignored Xcode project is modified to add the test target;
authored Swift/Ruby/shell sources stay in git. The simulator launch environment
is preserved, with fixture libraries appended for the app under test. No device
or global launchd settings are changed.

Keep the raw `.xcresult` and console logs private: the existing example logs its
VoIP token. Inspect screenshots before sharing. This is an integration checkpoint,
not independent media, system-call-screen, or hosted-provider qualification.
