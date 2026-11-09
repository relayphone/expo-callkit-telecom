# Android v2 quick-start

This guide uses the fork's **`v2` branch**, not a published npm v2 release.
Android uses Core-Telecom and native FCM intake. For iOS, see
[iOS](./migrating-v1-to-v2.md#ios). For API replacements and breaking changes,
start with [Migrating from v1](./migrating-v1-to-v2.md).

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

The checked-in example chooses `androidNativeMedia: "none"` and registers its
start handler at module scope, before React mounts. Incoming answer runs natively;
outgoing placement accepts locally, and its buttons explicitly report connected. **It has
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

Select the Android native LiveKit engine independently of the SDK's Apple audio
adapter, then rebuild the native application:

```ts
const callingPlugin: [string, { androidNativeMedia: "livekit" }] = [
  "expo-callkit-telecom",
  { androidNativeMedia: "livekit" },
];
```

The default `androidNativeMedia: "none"` leaves media to the application. With
`"livekit"`, production native media uses fresh LiveKit credentials from the
incoming push. There is no built-in HTTP credentials endpoint, authentication
provider registry or configurable HTTP fallback. Supply a short-lived token and
expiry from your backend; never place a bearer in plugin configuration.

The independent media-server fixture registers a named fetcher inside the
instrumentation APK. It retains HTTP404/410, cancellation, hold and cleanup
controls; that fixture lookup is not packaged in the library. Raw WebRTC signaling
also remains an instrumentation research path, outside the public configuration.
See the [native media contract](./contracts/android-native-media.md) and
[composed package contract](./canonical-contract.md). Your server must treat
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
   warm requests independently. LiveKit stays joined and subscribed.
   Remote decoding and muted playback warm before Answer.
   Physical microphone capture and audible output remain off.
3. The user answers in system UI, or the host calls `joinConversation(id)`.
   Native policy bypasses the JS Join handler. It waits for preparation and the
   same live Core-active call plus communication audio mode, then unmutes the
   existing media path. Answer does not renegotiate or resubscribe.
4. Connected state follows native activation. On hold, physical capture stops
   and output is muted while receive decoding stays warm. Resume waits for
   native audio authority again. Decline, incoming timeout, filtering and remote
   end close the owned media generation and Core call; canceled work cannot
   restore it. Missing or expired push credentials fail preparation, with at most
   one answer-time preparation retry. HTTP404/410 controls apply to the
   instrumentation-only named fetcher.

Core-Telecom owns focus/mode; the media adapter does not acquire its own audio
authority. The native starting-route preference is cached without delaying the
initial report. Route metadata can be absent and is not an extra activation
acknowledgment. A returned command promise is not proof of first decoded audio.

To stop ringing when another endpoint answers, send the same server identity
through `receiveCallEnded({ eventId, serverCallId, reason: "answeredElsewhere" })`
or the [native FCM end envelope](./contracts/android-answered-elsewhere.md).
That intake bypasses the End handler; observe state/events for completion rather
than treating its boolean acknowledgment as proof that visible UI is gone.

## Native audio readiness checks

The [native audio readiness contract](./contracts/android-latency.md) describes
prewarm, physical audio authority and the retained functional media controls.
The [v2 Android workflow](https://github.com/relayphone/expo-callkit-telecom/blob/v2/.github/workflows/v2-android.yml)
runs media, privacy, hold/resume, cleanup and DND checks on a CI emulator. It
exercises production native FCM intake with a synthetic envelope. The
[example workflow](https://github.com/relayphone/expo-callkit-telecom/blob/v2/.github/workflows/v2-example.yml)
separately runs the media-free Expo journey.

The historical latency benchmark and comparisons are preserved on
[archive/android-telecom-research](https://github.com/relayphone/expo-callkit-telecom/tree/archive/android-telecom-research).
They measured decoded PCM after completed prewarm, excluded cloud delivery,
process launch and acoustic output, and did not establish production percentiles.

## DND, contacts and repeat callers

| Allowance | Android Core-Telecom, API 35 emulator | iPhone 13/iOS 27 LCK research |
| --- | --- | --- |
| Contact/person allowed | Matching contacts rang under Contacts/Anyone; starred contacts also rang under Starred, 3/3 per cell in each lane. | Caller-only People allowance remained filtered with no call UI. |
| Repeat callers enabled | Second genuine call from an otherwise excluded caller rang, 3/3 per cell in each lane. | The second call 69.4 s later remained filtered with no call UI. |

The [Android study](./research/focus/android-results.md) retains 216 reports,
three samples per setting/caller cell in each of two independent lanes, using
exact `tel:` handles matched against actual contacts. It observes ringtone
playback, not physical sound. The linked pinned iPhone research has one sample
per measured row and no acoustic/haptic or CallKit qualification; app allowance
did admit its tested LCK calls. Neither result is a universal platform guarantee.
Those iPhone findings are SDK research, not measurements of this module; on iOS a
filtered call becomes a call preview rather than ringing.

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
