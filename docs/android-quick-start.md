# Android v2 quick-start

This guide uses the fork's **`v2` branch**, not a published npm v2 release.
Android uses Core-Telecom and native FCM intake. **iOS v2 is pending M3.5**;
the [draft adapter spike #33](https://github.com/relayphone/expo-callkit-telecom/pull/33)
does not ship full iOS runtime/media integration. For API replacements and
breaking changes, start with [Migrating from v1](./migrating-v1-to-v2.md).

## Run the example's signaling journey

Use an Android native development build, with API 26 or later, microphone and
notification permission. From a checkout of this fork's `v2` branch:

```sh
bun install --frozen-lockfile
bun run build
cd example/client
bun install --frozen-lockfile
bun run android
```

Tap **Run incoming and outgoing journey**. It exercises incoming → answer →
connected → hold → resume → end, then outgoing → connected → end through the
production Expo bridge. The screen also supports manual reports, mute and route
selection. `useAudioRoutes()` shows the current route; request an available route
with `selectAudioRoute(route.id)` and observe the actual result rather than
assuming the request changed audio output. The deep link
`conversations-example://journey` runs the same journey. See
[the example journey instructions](https://github.com/relayphone/expo-callkit-telecom/blob/v2/example/client/V2-JOURNEYS.md).

The checked-in example deliberately chooses `joinPolicy: { type: "handler" }`
and registers its handler at module scope, before React mounts. Its signaling
handler accepts locally, and its buttons explicitly report connected. **It has
no media room and produces no remote call audio.** The release Expo CI journey
qualifies the bridge/UI flow; native media and decoded-audio timing are qualified
separately by the instrumentation fixture below.

For real FCM delivery, configure the example's Firebase Android application and
`example/client/google-services.json`, rebuild, and use the `fcm` token from
`usePushToken()` (`{ type: "fcm", value }`). Follow the
[example sender setup](https://github.com/relayphone/expo-callkit-telecom/blob/v2/example/server/README.md) for the service account and
device token. Its Android sender uses high-priority data messages:

```sh
cd example/server
bun send-test-push.ts --android --phoneNumber +14155551234 --display Alice
```

The sender creates the typed caller and invitation; it does not mint or attach
media credentials. Running it against the unmodified signaling example therefore
does not test native pre-warm or audio. Cloud FCM delivery requires your Firebase
setup and is not part of the emulator latency measurements.

## Configure a host for native answer and audio

The Android default is native answering with pre-warm. Choose an explicit typed
configuration for your host rather than relying on the demo's handler policy.
For built-in LiveKit with pushed credentials and a native HTTPS fallback:

```ts
import type { JoinPolicy } from "expo-callkit-telecom/types";

const joinPolicy = {
  type: "native",
  media: "livekit",
  credentials: "fromPush",
  prewarm: true,
  fallback: {
    type: "http",
    baseUrl: "https://api.example.com",
    authentication: "device-auth",
  },
} satisfies JoinPolicy;

// Place this tuple in app.config.ts's expo.plugins array, then rebuild:
const callingPlugin: [string, { joinPolicy: JoinPolicy }] = [
  "expo-callkit-telecom", { joinPolicy },
];
```

During `Application.onCreate`, register the `device-auth` entry in
`NativeMediaAuthenticationProviders.providers`. Its suspending native function
returns `NativeMediaAuthentication(bearer, deviceId)` from the host's native auth
store. It must work without JS. The built-in fetcher POSTs
`/v1/calls/{percent-encoded-serverCallId}/media-credentials` with
`{ eventId, deviceId }` and Bearer authentication. It uses HTTPS, refuses
redirects and bounds network work. The native fetcher alternative is
`credentials: { type: "nativeFetcher", name: "your-native-fetcher" }`, registered
in `NativeMediaRegistry.fetchers` at launch. No bearer belongs in plugin config.

For raw WebRTC, select `media: "webrtc"` with the HTTP or named native fetcher;
implement the [SDP/state endpoint contract](./contracts/android-webrtc.md).
The shared `incomingCall.media` push object is LiveKit-only; WebRTC credentials
come from its native fetcher. Read the complete
[LiveKit/native media contract](./contracts/android-native-media.md) before
adapting the example. Remove its synthetic connected reports for native media:
the native adapter reports connected after activation. Your server must treat
prejoined and answered as distinct states.

### Send the incoming payload

Build a typed invitation in the backend and JSON-encode it into FCM's string data
field. This illustrates the shape; the backend supplies a real short-lived token
and expiry and sends the message with its FCM credentials:

```ts
import type { IncomingCallPayload } from "expo-callkit-telecom/types";

const incoming: IncomingCallPayload = {
  eventId: "invitation-event-123",
  serverCallId: "server-call-456",
  caller: {
    handleType: "phone",
    value: "+14155551234",
    displayName: "Alice",
    hostContactID: "host-contact-42",
  },
  hasVideo: false,
  media: {
    provider: "livekit",
    url: "wss://media.example.com",
    room: "call-room-456",
    identity: "owner-device",
    token: "<short-lived-room-token>",
    expiresAt: Math.floor(Date.now() / 1000) + 60,
  },
};

const message = {
  message: {
    token: "<FCM-device-token>",
    android: { priority: "HIGH" },
    data: {
      messageType: "incomingCall",
      incomingCall: JSON.stringify(incoming),
    },
  },
};
```

Use a new `eventId` per genuine event and retain `serverCallId` across its call
lifecycle. Phone `value` normalizes to E.164 and becomes the same `tel:` identity
for Core-Telecom and notification Person matching. `hostContactID` is an opaque
host key, not an Android Contacts row ID; it does not make a contact starred.
Media credentials stay outside `metadata`, public snapshots and logs. The
Android LiveKit token needs the room-scoped join/publish/subscribe grants from
the [native media contract](./contracts/android-native-media.md); do not assume
the pending iOS prejoin credential policy is identical.

### What happens after the push

1. Native FCM intake reports through Core-Telecom `addCall` first, then resolves
   the incoming policy. A filtered/capacity/failed result does not start media.
2. For an accepted invitation, native media and the existing JS host receive
   warm requests independently. LiveKit stays joined and subscribed; WebRTC
   negotiates once. Remote decoding and muted playback warm before Answer.
   Physical microphone capture and audible output remain off.
3. The user answers in system UI, or the host calls `joinConversation(id)`.
   Native policy bypasses the JS Join handler. It waits for preparation and the
   same live Core-active call plus communication audio mode, then unmutes the
   existing media path. Answer does not renegotiate or resubscribe.
4. Connected state follows native activation. On hold, physical capture stops
   and output is muted while receive decoding stays warm. Resume waits for
   native audio authority again. Decline, incoming timeout, filtering and remote
   end close the owned media generation and Core call; canceled work cannot
   restore it. Missing/expired credentials use the configured fallback, with at
   most one answer-time preparation retry. HTTP 404/410 abandons that preparation.

Core-Telecom owns focus/mode; the media adapter does not acquire its own audio
authority. The native starting-route preference is cached without delaying the
initial report. Route metadata can be absent and is not an extra activation
acknowledgment. A returned command promise is not proof of first decoded audio.

To stop ringing when another endpoint answers, send the same server identity
through `receiveCallEnded({ eventId, serverCallId, reason: "answeredElsewhere" })`
or the [native FCM end envelope](./contracts/android-answered-elsewhere.md).
That intake bypasses the End handler; observe state/events for completion rather
than treating its boolean acknowledgment as proof that visible UI is gone.

## Measured answer latency

The [checked before/after results](./contracts/android-latency-results.md) use
API 35 x86_64 `google_apis` / `pixel_6` on Ubuntu with KVM, real LiveKit and an
independent WebRTC peer. Five fresh-process attempts per provider/credential path
form each 20-sample profile. Answer follows completed PREJOINED plus 150 ms.

| Measurement | Result |
| --- | --- |
| LiveKit push, median Answer → eligible decoded PCM | 1039.07 → 164.25 ms in the controlled comparison |
| LiveKit fetcher, median | 965.17 → 180.84 ms in the controlled comparison |
| All optimized primary samples | LiveKit 159.65–186.27 ms; WebRTC 160.39–189.06 ms (20/20 below 300 ms) |
| Separate merged-source verification | LiveKit 150.30–195.06 ms; WebRTC 157.24–251.31 ms (20/20 below 300 ms) |

Across those two optimized runs, all 40 answers met the 300 ms target. WebRTC already
met the target in the controlled baseline; no WebRTC median speedup is claimed.
The metric is the first eligible decoded PCM16 callback (peak ≥128), not acoustic
speaker output. It excludes OS process launch and cloud FCM delivery. It requires
completed pre-warm; an earlier answer can wait for preparation. Work moved before
Answer and did not improve invitation-to-audio time in these runs. The unchanged
test failure bound is 3,000 ms. These samples establish neither a production
percentile nor a fixed emulator floor. Full method, phase timings and source-hashed
JSON are linked from the results page.

The existing [v2 Android workflow](https://github.com/relayphone/expo-callkit-telecom/blob/v2/.github/workflows/v2-android.yml) reproduces
native media, privacy/hold/cleanup, DND controls and the 20-sample benchmark on a CI
emulator; it exercises production native FCM intake with a synthetic envelope,
not cloud delivery. The [release example workflow](https://github.com/relayphone/expo-callkit-telecom/blob/v2/.github/workflows/v2-example.yml)
separately runs the media-free Expo journey. No large local SDK installation is
needed to inspect the checked results.

## DND, contacts and repeat callers

| Allowance | Android Core-Telecom, API 35 emulator | iPhone 13/iOS 27 LCK research; iOS v2 pending M3.5 |
| --- | --- | --- |
| Contact/person allowed | Matching contacts rang under Contacts/Anyone; starred contacts also rang under Starred, 3/3 per cell in each lane. | Caller-only People allowance remained filtered with no call UI. |
| Repeat callers enabled | Second genuine call from an otherwise excluded caller rang, 3/3 per cell in each lane. | The second call 69.4 s later remained filtered with no call UI. |

The [Android study](./research/focus/android-results.md) retains 216 reports,
three samples per setting/caller cell in each of two independent lanes, using
exact `tel:` handles matched against actual contacts. It observes ringtone
playback, not physical sound. The linked pinned iPhone research has one sample
per measured row and no acoustic/haptic or CallKit qualification; app allowance
did admit its tested LCK calls. Neither result is a universal platform guarantee.
**iOS v2 remains pending M3.5** ([draft #33](https://github.com/relayphone/expo-callkit-telecom/pull/33));
those iPhone findings are SDK research, not shipped fork behavior.

On Android the host can read `NotificationManager.currentInterruptionFilter`
and the effective consolidated policy. On API 33+, caller-specific
`matchesCallFilter` requires existing contacts permission, notification listener
access or system access; the library requests neither contacts nor listener
access. When matching is unavailable, it leaves presentation to Android.
`reported` alone therefore never proves ringing.

The native `NativeIncomingOutcomes` hook reports typed outcomes without JS;
`incomingConversationOutcome` delivers the same envelope when JS is alive.
Filtered reasons are `doNotDisturb`, `blockList` and `other`. Dispatch authorized
backend decline/AI-screening work off the main callback; the hook does not itself
implement that backend. A policy change can also filter a prewarmed ringing call
and close its resources. See [policy/outcome details](./contracts/android-incoming-policy.md).
