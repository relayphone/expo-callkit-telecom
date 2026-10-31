# Migrate from v1 to v2

This guide covers the fork's conversations API on branch `v2`. It is a
breaking change from upstream expo-callkit-telecom v1, with no compatibility shim.
iOS runs the same API over ios-voip-sdk; see [iOS](#ios) for what differs there.

The inherited package name/version and `bun add expo-callkit-telecom` instructions
do not identify this fork's v2 rollout. Use a reviewed fork `v2` checkout pinned
to a commit, build the package/plugin and rebuild the native application. See the
[Android quick-start](./android-quick-start.md) for the example workflow. This is
not a JavaScript-only upgrade or an Expo Go installation.

## Change the state model and imports

Runtime functions and React hooks come from `expo-callkit-telecom`. Types can be
imported without loading the native module:

```ts
import { getConversations, useConversations } from "expo-callkit-telecom";
import type { Caller, ConversationId } from "expo-callkit-telecom/types";
```

Replace the single active `CallSession` and its added/updated/removed listeners
with the immutable conversation list. Several calls can coexist; select one by
its returned `ConversationId`, rather than treating the first array element as
the active call. `useConversation(id)` subscribes to one conversation. The native
runtime reads the synchronous native snapshot before handler attachment; an
async refresh follows. A late JS observer can recover current calls without
replaying v1 listener events.

| v1 field or assumption | v2 replacement / breaking change |
| --- | --- |
| `CallSession`, `remoteParticipants` | `Conversation`, `members`; outgoing/member identity remains `Participant`. |
| `origin: incoming / outgoingApp / outgoingSystem` | `direction: incoming / outgoing`; an action's context separately supplies `origin: app / system`. |
| `status: requesting` | Outgoing calls use `dialing`; statuses also include `held` and `ending`. Rewrite exhaustive switches. |
| `isOnHold`, `options.hasVideo`, `incomingCallEvent` | `status === "held"`, `isVideo`, `payload`. |
| `requestId` from an answer event | No public fulfill token; native owns action settlement and deadlines. |
| A handler resolved, therefore media is connected | `connectedAt` follows a connected transport report, not handler fulfillment. |
| Any string is a call ID | Use the branded ID returned by the API or snapshot. Do not substitute `serverCallId` or cast an arbitrary string. |
| An ended call has only a reason | A conversation that ended as `failed` also carries `ended.failure.kind` when the platform knows the cause, such as `notPlaced` or `media`. [`EndedFailureKind`](https://github.com/relayphone/expo-callkit-telecom/blob/v2/src/conversations/types.ts) lists the kinds each platform reports. |

Incoming conversation UUIDs use UUIDv5 with namespace
`d460ee4a-efbc-5b0d-8035-0b915bf9551a` over the exact UTF-8 `serverCallId` bytes.
Do not trim or case-fold that server identity. Outgoing IDs are returned by
`startConversation`; applications should treat both kinds as opaque native IDs.
`eventId` still identifies a transport delivery for deduplication, not a second
genuine call. The accepted [RFC](https://github.com/relayphone/expo-callkit-telecom/blob/v2/rfcs/0001-v2-conversations-api.md) and
[current types](https://github.com/relayphone/expo-callkit-telecom/blob/v2/src/conversations/types.ts)
describe the full model.

## Map the v1 API

The left column comes from the
[v1 source](https://github.com/relayphone/expo-callkit-telecom/blob/bc335010313712617ffe690ac10936309b0a6e26/src/Calls.ts).
The right column is the current v2 runtime on both platforms.

| v1 API | v2 migration |
| --- | --- |
| `getActiveCallSession`, `addCallSessionAddedListener`, `addCallSessionUpdatedListener`, `addCallSessionRemovedListener` | `getConversations()`, `useConversations()`, `useConversation(id)`. State replaces those event subscriptions. |
| `startOutgoingCall(recipient, { hasVideo })`, `addOutgoingCallStartedListener` | `startConversation({ to: recipient, video })`; signaling belongs in the module-scope handler's `start`. |
| `reportIncomingCall`, `addIncomingCallReportedListener` | `reportIncomingConversation(payload)` returns a typed outcome. Native push intake also emits `incomingConversationOutcome` when JS is alive. |
| `answerCall`, `addCallAnsweredListener` | `joinConversation(id)` requests an in-app answer. The native join policy handles system answers without JS; the explicit handler policy invokes `handler.join`. |
| `fulfillIncomingCallConnected`, `failIncomingCallConnected` | Remove the fulfill-token flow. Under handler policy resolve/reject `join`; native policy owns its Join. Report actual media progress separately with `reportConversation`. |
| `reportOutgoingCallConnected` | `reportConversation(id, { type: "connected" })`. The same report describes connected transport state for incoming calls. |
| `endCall`, `addCallEndedListener` | `endConversation(id)` for a local request; put handler-owned signaling cleanup in `handler.end` and derive UI state from snapshots. |
| `reportCallEnded`, `addReportedCallEndedListener` | `reportConversation(id, { type: "ended", reason })` for a known conversation. For a backend signal keyed by `serverCallId`, use `receiveCallEnded` below. Reports bypass the action handler. |
| `setMuted`, `addSetMutedActionListener` | `setMuted(id, muted)` and optional `handler.mute(conversation, context)`, whose context includes `muted`. Observe `isMuted`. |
| `setHeld`, `addSetHeldActionListener` | `setHeld(id, held)` and optional `handler.hold`, whose context includes `held`. Observe `status === "held"`. |
| `reportVideo`, `addVideoChangedListener` | `reportConversation(id, { type: "updated", video })`; observe `isVideo`. This reports state; it does not create a camera track. |
| `playDTMF`, `addDTMFListener` | `playTones(id, digits)` and optional `handler.tones`, whose context includes `digits`. |
| `getAudioSession`, `addAudioRouteChangedListener`, `setAudioSessionPortOverride` | `useAudioRoutes()` and `selectAudioRoute(route.id)` using a currently available opaque route ID. There is no speaker boolean override. |
| `addAudioSessionActivatedListener`, `addAudioSessionDeactivatedListener` | `useConversationEvent("audioActivated", callback)` / `"audioDeactivated"`. Watch the missing/failed activation events too. |
| `setRTCAudioSessionConfiguration`, `prepareAudioSessionForCall`, `restoreAudioSession` | Remove JS audio-session ownership. Native media adapters coordinate activation and teardown with Core-Telecom on Android and LiveCommunicationKit on iOS (see `mediaAdapter` under [iOS](#ios)). |
| `getCaptureSession` | No equivalent v2 export. The host manages permission/UI needs; conversation state is not a camera-permission API. |
| `registerVoIPPush`, `getVoIPPushToken`, `useVoIPPushToken`, `addVoIPPushTokenUpdatedListener` | Native registration starts at launch; use `usePushToken()` or the `pushTokenUpdated` hook event. `{ token, type: "APNS_VOIP" / "FCM" }` becomes `{ value, type: "apns-voip" / "fcm" }`, or `null`. |
| `addCallIntentReceivedListener` | Shared replacement: `useConversationEvent("dialRequest", ...)`. On iOS, a call intent from Recents, Siri or Contacts that arrives before a listener waits for the first `conversationEvent` listener. |

Hooks belong in components; the v1 imperative `add*Listener` helpers are not
compatibility aliases. Register the one `setConversationHandler` at module scope,
before mounting React. Its `start` and `end` methods are required; `join` is
required for the explicit handler join policy and bypassed by native Join.
Honor `context.signal` cancellation and the native ISO deadline, including handler
replacement. Do not extend the deadline with a JS timer. Optional hold/mute/tones
methods may accept by omission. Commands resolve on system acceptance, while
reports communicate transport facts. Inspect capabilities; Android
`mergeConversations` currently rejects with `unsupported`.

## Replace the incoming caller child

v1 inferred a handle from a participant-like `{ id, phoneNumber?, email? }`.
v2 requires an explicit `Caller`; the old child is invalid:

```ts
import type { IncomingCallPayload } from "expo-callkit-telecom/types";

const payload: IncomingCallPayload = {
  eventId: "delivery-123",
  serverCallId: "call-456",
  caller: {
    handleType: "phone",
    value: "+14155551234",
    displayName: "Jane Smith",
    hostContactID: "host-contact-42",
  },
  hasVideo: false,
};
```

`handleType` is `phone`, `email` or `generic`; `value` and `displayName` are
required. Phones normalize permitted formatting to E.164; no region or extension
is guessed. `hostContactID` is optional and opaque: it is not an Android Contacts
row ID or permission to bypass DND. The normalized communication URI associates
the call with Android's notification Person/contact matching. Outgoing `to` and
snapshot `members` still use `Participant`, so do not replace all participant
objects with Caller. See [validation and projection](./contracts/android-caller.md).

FCM retains the `data.messageType` and JSON-string `data.incomingCall` envelope.
Update the server's inner caller child before upgrading the client. Put optional
short-lived credentials in `incomingCall.media`, never in `metadata`; media is
removed from public snapshots. The [quick-start](./android-quick-start.md)
describes the LiveKit push contract and native WebRTC fetcher path.

## Handle outcomes and answered-elsewhere signals

`reportIncomingConversation` no longer resolves `void`. Branch on
`outcome.status`: `reported` includes an ID; `filtered` includes
`reason: "doNotDisturb" | "blockList" | "other"`; `atCapacity` includes no ID;
`failed` includes a typed error. A rejected/filtered invitation is not a created
conversation. Do not continue to answer or report connected without a reported
ID. Native push intake uses the same outcome envelope through the
`incomingConversationOutcome` event and a native hook that can run without JS.

On Android, `reported` proves Core admission, not audible ringing. Priority DND
can still allow a matching contact or repeat caller. Caller matching requires
existing OS authorization; if unavailable, native intake leaves presentation to
Android. `blockList` is the app's native policy hook, not an automatic Android
system block-list lookup. A later policy change can filter a ringing prewarmed
call and close its media. See [native policy/outcomes](./contracts/android-incoming-policy.md)
and the [Android/iPhone comparison](./research/focus/android-results.md#iphone-comparison-and-host-controlled-breakthrough).

When another endpoint takes the call, send the server identity rather than
pretending the local user hung up:

```ts
import { receiveCallEnded } from "expo-callkit-telecom";

const accepted = await receiveCallEnded({
  eventId: "ownership-event-789",
  serverCallId: "call-456",
  reason: "answeredElsewhere",
});
```

The boolean acknowledges a known incoming invitation, not completed UI removal.
The signal bypasses the End handler and closes native media; terminal state
precedes the once-only `answeredElsewhere` event
`{ conversationId, serverCallId, eventId }`. Local hangup still uses
`endConversation`. Server signals are deduplicated and can also end a known held
or connected incoming call; do not use them as tombstones for future calls.
On Android API34+ the transactional platform uses supported REMOTE(3) plus the
reason string, preserving the SDK's `answeredElsewhere` semantics; visible UI can
remain for Android's heads-up minimum. See the [contract and measured limits](./contracts/android-answered-elsewhere.md).

## Move answer-time media work before Answer

Android's default policy is native answer plus pre-warm. Select the native media
provider and credential source in typed plugin configuration, rebuild, and
register any native fetcher/authentication provider during `Application.onCreate`.
There is no JS credential callback or feature flag. Reporting to Core happens
first; then permitted invitations warm native media and the existing JS runtime.
Remote subscription/decoding stays ready with output muted and physical capture
off. Native Answer releases the already prepared path after Core audio authority,
without waiting for a JS Join handler or renegotiating at Answer.

Choose `{ type: "handler" }` explicitly for an app that retains JS-owned media,
and implement its cancellation-aware `join` method. A successful handler settles
the action; only the actual transport can report connected. The checked-in
example deliberately uses this media-free handler flow, so its Connect step is
not evidence of native Room audio. See [Android quick-start](./android-quick-start.md)
and [native media lifecycle](./contracts/android-native-media.md) for the separate
real-media fixtures and cleanup requirements.

## iOS

iOS 26 or later. The module runs ios-voip-sdk's `VoIPClient` from launch, through
an app delegate subscriber, before JavaScript loads, so a VoIP push that launches
the app is reported and the user can answer before JavaScript runs.

- **Calling framework.** Calls are presented with LiveCommunicationKit, not CallKit,
  and there is no option to choose. A Focus's allowed people and Repeated Calls don't
  make a call ring; the call becomes a call preview instead. CallKit's Call Directory
  extension doesn't apply, and CarPlay is unverified. Prebuild fails while the
  plugin's configuration still sets the removed `callingFramework` option. See
  ios-voip-sdk's [ADR 0002](https://github.com/relayphone/ios-voip-sdk/blob/main/docs/adr/0002-one-calling-framework-per-build.md).
- **SDK source.** Prebuild adds ios-voip-sdk's pod to the Podfile at a pinned
  revision; don't add the SDK's own Expo module to the app as well.
- **Native answer.** The SDK answers, ends, holds, mutes and plays tones itself, then
  tells the app. `joinPolicy` and media credentials are Android-only. Only `start`,
  and `join` from a call preview, wait for the handler: resolving them confirms that
  the app placed the call. Rejecting them, or missing their deadline, fails a start,
  whose conversation ends with `failure.kind` `notPlaced`, and returns a join to its
  call preview. `end`, `hold`, `mute` and `tones` reach the handler after they
  happened, and their results are ignored. An answer doesn't reach the handler;
  observe `status === "connecting"`.
- **Call previews.** A call that the system filtered (Do Not Disturb, a Focus or the
  block list), or that was answered elsewhere, becomes `status === "previewing"`
  with a `previewReason`, instead of ringing or ending. `joinConversation` joins it
  and `endConversation` stops following it. `reportIncomingConversation` returns
  `"previewed"` for such a call.
- **Identity.** A conversation's ID is the SDK's call ID: the `serverCallId` of an
  incoming call, and a new UUID for an outgoing one. Phone numbers must be E.164.
- **Media.** `mediaAdapter: "webrtc"` or `"livekit"` coordinates WebRTC's
  `RTCAudioSession` in manual audio mode, so the app must link a WebRTC build, such
  as `@livekit/react-native-webrtc`. `"none"` leaves the audio session to the app.
- **System call UI.** `includesCallsInRecents`, `defaultRingtoneIos` and
  `iconTemplateIos` set LiveCommunicationKit's `ConversationManager.Configuration`;
  see [Getting started](./getting-started.md).
- **Simulator.** `simulator: true` presents calls in the iOS Simulator in Debug
  builds, with ios-voip-sdk's simulator runtime. Release builds and devices are
  unaffected.
- **Not on iOS.** `outgoingCallTimeout` (`incomingCallTimeout` also bounds dialing),
  `defaultDialtone`, and `video` in an `updated` report.
