# expo-callkit-telecom example client

A media-free Expo app for the v2 conversations API. It registers a conversation handler at module scope that accepts every action locally, and its screen reports incoming calls, starts outgoing ones, answers, holds, mutes, selects audio routes and ends calls. It connects no media room, so a call has no audio; everything else goes through the real system call UI: LiveCommunicationKit on iOS and Core-Telecom on Android.

[V2-JOURNEYS.md](V2-JOURNEYS.md) describes the screen and the scripted journey that CI runs on Android.

## Running

From the repo root:

```sh
bun install --frozen-lockfile
bun run build
cd example/client
bun install --frozen-lockfile
bun run android   # or: bun run ios
```

On iOS, the example sets the plugin's `simulator` option, so Debug builds present calls in the iOS Simulator. VoIP pushes need a real device; the screen shows whether push registration succeeded, and `usePushToken()` holds the token to send a test push with `example/server/`.
