# Conversations example

The screen uses the default v2 conversations entry, multiple immutable snapshots,
commands, transport reports and Core-Telecom audio routes. The handler registers
at module scope before React mounts. This example deliberately selects the public
`joinPolicy: { type: "handler" }` policy and accepts signaling locally. It does
not connect a media room or demonstrate native LiveKit credentials.

Install with `bun install --frozen-lockfile` at the repository root and here, build
the library with `bun run build` at the root, then use `bun run android` here.
Grant microphone and notification permission. Use the screen's buttons for manual
incoming, outgoing, answer, hold/resume, mute, endpoint selection and end.

The journey button asserts incoming → answer → connected → hold → resume → end
and outgoing → connected → end through the production Expo bridge. It writes
`conversation-journey.json` in the app's document directory with the ordered steps
and pass/fail result. Opening `conversations-example://journey` runs the same code.

The `v2 example` workflow builds an x86_64 release APK on ubuntu-latest, runs it
on the pinned API35 google_apis emulator with KVM, then validates and uploads the
result JSON. Root ADB is used only to read this test image's private result file;
the example APK itself remains a normal release build. No Firebase credentials
are needed for foreground reports; push delivery requires an app Firebase setup.

On iOS, `bun run ios` builds the example for the Simulator, where Debug builds present calls
with the plugin's `simulator` option. The `v2 iOS` workflow builds it for the Simulator.
