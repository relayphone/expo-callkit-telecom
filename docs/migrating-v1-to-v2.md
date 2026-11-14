# Migrate from v1 to v2

The fork's `v2` branch composes `@relayphone/ios-voip-sdk`. The SDK owns the shared
TypeScript API and the sole Apple native provider; this package adds Android
Core-Telecom and the composed config plugin. This is a breaking migration with
no v1 compatibility API. Use [package evaluation](./sdk-package-evaluation.md)
for the fork tarball and SDK release asset; the inherited version does not identify a published v2
release. Follow [Getting started](./getting-started.md), regenerate native projects
and rebuild the development client.

## Change the state model and imports

Import functions and hooks from `expo-callkit-telecom`, and types from
`expo-callkit-telecom/types`. `useConversations()` observes the immutable list;
`useConversation(id)` observes one call; `getConversations()` reads outside React.
Replace the single active `CallSession` and its added/updated/removed listeners.
Several calls can coexist, so select by ID.

Incoming IDs are the exact `serverCallId`; outgoing calls require the application's
`id`, returned unchanged. Remove UUID derivation, normalization and assumptions
that native generates an outgoing ID. Platform UUIDs stay internal. `eventId`
identifies one server event for deduplication. Incoming duplicate reports share
admission; outgoing duplicate IDs and direction collisions reject on Android.

| v1 field or API | v2 replacement |
| --- | --- |
| `CallSession`, `remoteParticipants` | `Conversation`, `members` |
| `origin: incoming / outgoingApp / outgoingSystem` | Conversation `direction: incoming / outgoing`; action `origin: app / system` |
| `isOnHold`, `options.hasVideo`, `incomingCallEvent` | `status === "held"`, `isVideo`, `payload` |
| `startOutgoingCall` | `startConversation({ id, to, video })` |
| `reportIncomingCall` | `reportIncomingConversation(payload)`; inspect its typed outcome |
| `answerCall` | `joinConversation(id)`; native answers a ringing call |
| `fulfillIncomingCallConnected`, `failIncomingCallConnected` | Remove fulfill tokens; report actual transport progress with `reportConversation` |
| `endCall`, `setMuted`, `setHeld` | `endConversation(id)`, `setMuted(id, muted)`, `setHeld(id, held)` |
| `reportOutgoingCallConnected`, `reportCallEnded` | `reportConversation(id, { type: "connected" })` or `{ type: "ended", reason }` |
| `playDTMF` | `playTones(id, digits)` when supported; Android requires native LiveKit |
| Audio-session and route listeners | `useAudioRoutes()`, `selectAudioRoute(route.id)`, audio lifecycle events |
| VoIP push registration/listeners | Native registration at launch; `usePushToken()` returns `{ type: "apns-voip" / "fcm", value }` or `null` |
| `addCallIntentReceivedListener` | `useConversationEvent("dialRequest", listener)` |

Read the [SDK guide](https://github.com/relayphone/ios-voip-sdk/blob/v0.5.0/docs/react-native.md)
for all shared APIs, statuses, failure causes and events. Check each conversation's
capabilities; Android also rejects merging and does not support call previews.

## Place calls and observe native actions

Register one handler at module scope before React mounts. Its required `start`
places outgoing calls; optional `join` places iOS preview joins. Honor the supplied
`AbortSignal` and deadline. Resolving confirms placement; report connected when the
transport actually connects. The handler must not await its own command's promise.

```ts
import {
  setConversationHandler,
  startConversation,
} from "expo-callkit-telecom";
import type { Conversation } from "expo-callkit-telecom/types";

declare function placeCall(call: Conversation, signal: AbortSignal): Promise<void>;

setConversationHandler({ start: (call, { signal }) => placeCall(call, signal) });

export function callAlice(serverCallId: string) {
  return startConversation({
    id: serverCallId,
    to: { id: "alice", displayName: "Alice" },
    video: false,
  });
}
```

Remove handler `end`, `hold`, `mute` and `tones` callbacks. Answer, end, hold and
mute run natively, including before JavaScript loads. Observe snapshots and
`actionPerformed`, with its `app` or `system` origin, for completed actions.
Server reports do not create performed actions. With the default policy, an app
start without a handler fails immediately. See the [placement contract](./canonical-contract.md)
for deadlines and Android admission ordering.

## Replace the incoming caller child

Replace v1's participant-like caller with an explicit `Caller`:

```ts
import type { IncomingCallPayload } from "expo-callkit-telecom/types";

export const invitation: IncomingCallPayload = {
  eventId: "delivery-123",
  serverCallId: "call-456",
  caller: { handleType: "phone", value: "+14155551234", displayName: "Alice" },
  hasVideo: false,
};
```

`handleType` is `phone`, `email` or `generic`. Phone handles require an international
country code. Outgoing `to` and snapshot `members` remain `Participant` values.
Follow the [caller contract](./contracts/android-caller.md) and [push formats](./voip-push.md).

Incoming reports return a typed outcome rather than an unconditional ID. Only a
`reported` outcome proves Android admission; it does not prove audible ringing.
Filtering, capacity and failure do not create a usable call ID. iOS can also
return `previewed`. Android filtered calls terminate and remove the notification;
a filtered failure records its cause. See [incoming policy](./contracts/android-incoming-policy.md).

When another endpoint answers, send `receiveCallEnded({ eventId, serverCallId,
reason: "answeredElsewhere" })` or its native push equivalent. Acceptance does not
mean UI removal has completed. Android publishes terminal state before the
correlated event and removes the call; iOS can retain a preview. See
[server end signals](./platform-notes.md#ending-a-call-from-the-server).

## Replace plugin and media configuration

Configure one `expo-callkit-telecom` plugin entry. It invokes the SDK's supported
iOS plugin; the SDK package supplies its native sources. Remove the old provider,
manual SDK Git pod pin and duplicate SDK plugin configuration through
[clean regeneration](./canonical-contract.md#plugin-composition-and-native-project-upgrades).

- Remove `callingFramework`, `defaultDialtone` and `androidEventReceiver`.
- Remove `joinPolicy`; Android no longer has a handler-versus-native answer policy.
- Use `androidNativeMedia: "livekit" | "none"` to select Android native media.
  Its default is `"none"`, which leaves media to the app and creates no native session.
- Use `mediaAdapter: "webrtc" | "none"` for SDK audio integration. LiveKit React
  Native uses `"webrtc"`; `"livekit"` is not a `mediaAdapter` value.

Android's native LiveKit path uses short-lived incoming push credentials and
prepares before Answer. Capture and audible output wait for Core-Telecom audio
authority. There is no built-in HTTP credential fallback. Follow the
[Android guide](./android-quick-start.md) and [native lifecycle contract](./contracts/android-native-media.md).

## iOS

The SDK supplies LiveCommunicationKit, PushKit, native media registration, call
previews, communication notifications and Focus integration. Use its
[React Native guide](https://github.com/relayphone/ios-voip-sdk/blob/v0.5.0/docs/react-native.md)
for requirements, plugin options and shared behavior, and
[native media guidance](./ios-native-media.md) for media prepared before JavaScript.
Call previews and `providerReset` are iOS-only. Rebuild the application after
changing native configuration.
