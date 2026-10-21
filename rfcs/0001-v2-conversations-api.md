# RFC 0001: a conversations API for v2 (LiveCommunicationKit and Core-Telecom)

- **Status:** draft for internal review. If Hamza approves, it would be proposed to @mfairley as the design for v2.
- **Scope:** the TypeScript API, the native architecture, push, audio, migration and testing.
- **Not in scope:** the media engine itself. The app still owns media.

## Summary

v2 replaces the CallKit-shaped API with a **conversations** API that works the same way on both platforms:

| Part | What it is |
| --- | --- |
| **State** | A list of conversations that the system maintains. Apps read it as a snapshot, and it supports several conversations from the start. |
| **Handler** | One object that does the app's share of every system action. A resolved promise confirms the action and a rejected one fails it. Platform deadlines are passed in. |
| **Commands and reports** | Commands are things the app asks the system to do. Reports are things the app's transport tells the system. |
| **Audio routes** | One cross-platform API. Audio session plumbing moves out of JS into native media adapters. |

- **iOS** runs on **LiveCommunicationKit**, Apple's successor API, through a small Swift package (`ios-voip-sdk`).
- **Android** runs on **Core-Telecom**. Its suspending callbacks already have this shape, so Android can drop the CallKit emulation it carries today.
- **Unchanged:** the push wire format (`incomingCall` and `callEnded` envelopes, deduplicated by `eventId`) and the config plugin's existing props.
- **Migration:** a `v1` compatibility entry point keeps existing apps building for one major version.

## Motivation

### The current API is shaped around CallKit and WebRTC

- **One active session.** `getActiveCallSession` plus added, updated and removed events. Android refuses a second call outright (`CallManager.reportIncomingCall`: "validates single-session constraint"). See #38.
- **CallKit's fulfill step is in JS.** The answer listener must call `fulfillIncomingCallConnected(requestId)` or `failIncomingCallConnected`. Android recreates this with `FulfillRequestManager` and channels into `CallControlScope`.
- **Two kinds of events for the same thing.** Action listeners (`addSetMutedActionListener`, `addSetHeldActionListener`, `addDTMFListener`, `addCallEndedListener`) sit next to `report*` calls and `addReportedCallEndedListener`.
- **Audio session control in JS.** `setRTCAudioSessionConfiguration`, `prepareAudioSessionForCall` and `restoreAudioSession` are timing-sensitive, and JS isn't present on a cold launch. See #22, #32 and #36.
- **Event-based state is lost before JS attaches.** See #13 and #46, and the open PRs #49 and #50 that work around it.

### LiveCommunicationKit fits better

These measurements were made on an iPhone 13 running iOS 27.0, with genuine frameworks and probes in a separate repository.

- **One `ConversationManager` replaces three objects:** `CXProvider`, `CXCallController`, and the app's own call store.
- **The system tracks conversation state:** `idle`, `joining`, `joined`, `paused`, `leaving`, `left`. That state is `Observable`.
  - A reported conversation is already in the manager's list before `reportNewIncomingConversation` returns.
- **Every CallKit action has a counterpart:** start, join, end, mute, pause, tones, merge and unmerge, plus translation in iOS 26.
- **Behavior matched CallKit** for:
  - every action listed above;
  - the system's mute echo;
  - audio deactivation on pause and reactivation on resume.
- **Apple's newer calling features are LiveCommunicationKit-only:**
  - **Default dialer:** `TelephonyConversationManager`, EU only.
  - **Conversation history.**
  - **Translation.**
- **Default calling app** (iOS 18.2 and later) works with LiveCommunicationKit.

### Core-Telecom already has this shape

`CallsManager.addCall` takes suspending `onAnswer`, `onDisconnect`, `onSetActive` and `onSetInactive` callbacks and exposes a `CallControlScope`:

- **Requests:** `setActive`, `setInactive`, `answer`, `disconnect`, `requestEndpointChange`.
- **Flows:** `currentCallEndpoint`, `availableEndpoints`, `isMuted`.

So the platform already says "do your part, then return". A promise-based handler maps directly onto it.

## Design principles

1. **The system is the source of truth.** JS reads a snapshot, so a listener attached late (cold launch, reload, Fast Refresh) never misses state.
2. **One way to do each thing.** Commands go app → system and reports go transport → system. Every system action goes through the handler.
3. **Native owns anything time-critical:** push reporting, audio activation and action deadlines. JS still gets to decide when its decision is needed.
4. **No platform vocabulary at the top level.** The API never names CallKit, Core-Telecom or `RTCAudioSession`.
5. **Keep what works:** the push format, `eventId` deduplication, config plugin props, ringtone, icon and recents.

## API

### State

```ts
import { useConversations, useConversation, getConversations } from "expo-callkit-telecom";

type ConversationStatus =
  | "ringing"     // incoming, not yet joined
  | "dialing"     // outgoing, transport not yet connecting
  | "connecting"  // joining or start in progress
  | "connected"
  | "held"
  | "ending"
  | "ended";

interface Conversation {
  id: string;                         // UUID, stable for the conversation's life
  direction: "incoming" | "outgoing";
  status: ConversationStatus;
  members: Participant[];             // remote parties
  capabilities: { hold: boolean; merge: boolean; tones: boolean; video: boolean };
  isMuted: boolean;
  isVideo: boolean;
  connectedAt?: string;
  ended?: { at: string; reason: EndedReason };
  payload?: IncomingCallPayload;      // the parsed push or reported event (eventId, serverCallId, caller, metadata)
  pending?: PendingAction;            // an action waiting for the handler, with its deadline
}

type EndedReason = "local" | "remoteEnded" | "failed" | "unanswered" | "answeredElsewhere" | "declinedElsewhere";

const conversations = useConversations();  // re-renders on change
const one = useConversation(id);
const snapshot = await getConversations(); // imperative form
```

- **iOS** projects `ConversationManager.conversations` plus `Conversation.State`, and takes direction from `CXCallObserver`, which is read-only and not a provider.
- **Android** projects the library's call registry, driven by Core-Telecom callbacks.
- On both platforms, `status` is derived the same way, and conformance tests check that.

### Handler: the app's share of each action

```ts
import { setConversationHandler } from "expo-callkit-telecom";

setConversationHandler({
  // The user answered, from the system UI or via join(id).
  // Resolve once signaling has accepted, not once media is connected: deadlines are short (30 s on iOS).
  async join(c, ctx) { await signaling.accept(c.payload!.serverCallId, { signal: ctx.signal }); },
  // Outgoing: resolve once the transport has accepted the dial. Report "connected" later.
  async start(c, ctx) { await signaling.dial(c.members[0], { signal: ctx.signal }); },
  async end(c, ctx) { await signaling.hangup(c.id); },
  async hold(c, { held }) { await media.setHeld(c.id, held); },  // optional
  async mute(c, { muted }) { media.setMuted(muted); },           // optional; defaults to accept
  async tones(c, { digits }) { media.sendDtmf(digits); },        // optional; iOS only from the system keypad
});

interface ActionContext {
  deadline: string;                  // when the platform gives up
  signal: AbortSignal;               // aborted at the deadline or when the action is cancelled
  origin: "system" | "app";
}
```

- **Without a handler** (cold launch before JS), native keeps the action pending and shows it in `conversation.pending`. Native then hands the action to the handler once one is set, if its deadline hasn't passed. If it has passed, native applies `unhandledAction: "fail" | "accept"` from the config plugin, defaulting to `"fail"`.
  - This replaces the event queue in #46/#49 and the `requestId` fulfil step.
- **Repeated system actions.** The same action can arrive twice, such as the system's mute echo or the unmute after a muted call ends. Native filters out the ones that change nothing (mute with `muted` already equal to `isMuted`, hold with `held` already equal to the current state), so handlers don't have to be idempotent.

### Commands (app → system)

```ts
const id = await startConversation({ to: participant, video: false, metadata });
await joinConversation(id);
await endConversation(id);
await setMuted(id, true);
await setHeld(id, true);
await playTones(id, "12#");
await mergeConversations(a, b);     // iOS only; fails with "unsupported" on Android
```

Commands go through the system, not around it, so the system UI, Recents and the handler stay consistent. Each command resolves when the system has accepted the request.

### Reports (transport → system)

```ts
reportConversation(id, { type: "connecting" });
reportConversation(id, { type: "connected" });
reportConversation(id, { type: "updated", members, video });
reportConversation(id, { type: "ended", reason: "remoteEnded" });

// An incoming call that didn't arrive by push, for example over a WebSocket while foregrounded.
// It takes the same payload shape as the push envelope.
const id = await reportIncomingConversation({ eventId, serverCallId, caller, hasVideo, metadata });
```

### Audio routes

```ts
const { routes, current } = useAudioRoutes(); // earpiece, speaker, bluetooth, wired, carAudio, airPlay…
await selectAudioRoute(routes[1].id);
```

- **iOS:** `AVAudioSession` routes plus a speaker override.
- **Android:** Core-Telecom `availableEndpoints`, `currentCallEndpoint` and `requestEndpointChange`.

The existing port type names are kept.

### Media adapters (native, configured in the plugin)

Audio activation is driven by native code, never by JS:

| Adapter | iOS | Android |
| --- | --- | --- |
| `webrtc` (default; any `react-native-webrtc` build, as since #44) | `didActivate` / `didDeactivate` → `RTCAudioSession.audioSessionDidActivate` and `isAudioEnabled` | Device mute plus audio mode, as today |
| `livekit` | LiveKit's `AudioManager` engine availability | LiveKit's audio handling |
| `none` | Emits events only | Emits events only |

There is also an **activation watchdog**. If audio isn't activated within the configured time:

1. Emit `audioActivationMissing`.
2. Make one state-aware recovery attempt.
3. If that fails, emit `audioActivationFailed` and end the call cleanly.

It never fabricates an activation. Apple has confirmed `callservicesd` can hold a stale audio session (DTS thread 837211). See #22, #32 and #36.

### Push

- **Wire format: unchanged.**
  - APNs VoIP: `incomingCall`.
  - FCM data: `messageType` / `incomingCall` as JSON, plus `callEnded`.
  - Deduplication by `eventId` within two minutes.
- **iOS 26.4 and later:** handled through PushKit's metadata callback. If `mustReport` is false and the payload is malformed, nothing is reported. Required pushes and valid invitations always report.
- **Dedup across sources:** a push and a JS `reportIncomingConversation` with the same `eventId` or `serverCallId` produce one conversation, so the phone rings once (#50).
- **Token:** `usePushToken()` returns `{ type: "apns-voip" | "fcm", value }`.

### System entry points

```ts
useConversationEvent("dialRequest", ({ handle, video }) => { /* tel: link, Siri, Recents, default calling app */ });
```

| Feature | iOS | Android |
| --- | --- | --- |
| Default calling app (`ios.defaultCallingApp: true`) | iOS 18.2+: adds `com.apple.developer.calling-app` and handles `tel:` and the start-call intent | No equivalent; becoming the dialer requires a full `InCallService` |
| Default dialer (`ios.defaultDialer: true`) | iOS 26+, EU only, `TelephonyConversationManager`; a later phase | Not applicable |

### Events

Most apps need only state and the handler. Lifecycle signals that aren't state go through `useConversationEvent`:

- `audioActivated` and `audioDeactivated`;
- `audioActivationMissing` and `audioActivationFailed`;
- `pushTokenUpdated`;
- `dialRequest`;
- `providerReset`.

## Platform mapping

| v2 | iOS: LiveCommunicationKit | Android: Core-Telecom |
| --- | --- | --- |
| `reportIncomingConversation` / push | `reportNewIncomingConversation(uuid:update:)` | `addCall(DIRECTION_INCOMING)` plus the incoming notification |
| `startConversation` | `perform([StartConversationAction])` → handler `start` | `addCall(DIRECTION_OUTGOING)` → handler `start` |
| handler `join` | `JoinConversationAction` → fulfill or fail | `onAnswer` suspends until the handler settles |
| handler `end` | `EndConversationAction` | `onDisconnect` |
| handler `hold` | `PauseConversationAction` | `onSetInactive` / `onSetActive` |
| handler `mute` | `MuteConversationAction`, with the echo filtered | `isMuted` flow (system), plus the device microphone mute |
| handler `tones` | `PlayToneAction` | App-initiated only; Telecom has no keypad for self-managed calls |
| `reportConversation(connected)` | `reportConversationEvent(.conversationConnected)` | `setActive()` |
| `reportConversation(ended)` | `.conversationEnded(date, reason)` | `disconnect(DisconnectCause)` |
| `mergeConversations` | `MergeConversationAction` | Unsupported (Core-Telecom 1.0 has no conference API) |
| Audio routes | `AVAudioSession` | Endpoints |
| Multiple conversations | Configuration: groups and conversations per group | Multiple `addCall` scopes; hold via `setInactive` |

## Native architecture

**iOS: `ios-voip-sdk`**, an Expo-independent Swift 6 package that the module depends on. It is to be created at `ziahamza/ios-voip-sdk`.

- **Manager ownership.** It owns the process's **only** `ConversationManager`.
  - **Why it matters:** on the iPhone, a `CXProvider` created later, by any SDK, receives every action instead. So do a second manager's actions.
  - **Debug builds:** warn when that happens.
- **State.** It projects LiveCommunicationKit's state, registers app metadata synchronously before reporting, and enforces handler deadlines.
- **Push.** A small Objective-C shim handles the PushKit metadata selector, so older SDKs still compile.
- **Audio.** Media adapter protocols and the activation watchdog.

**Dependency.** The module would depend on it through CocoaPods or the `spm_dependency` podspec helper. Native Swift apps can use the package directly, and later watchOS and Mac Catalyst apps can too.

**Android.** The Kotlin stays in this repo, refactored to the handler and registry model:

- `FulfillRequestManager` and the single-session constraint go away.
- `onAnswer`, `onDisconnect`, `onSetActive` and `onSetInactive` suspend until the JS handler settles, or until native policy decides when no handler is attached.
- **To verify before committing to this:** how long Core-Telecom lets these callbacks suspend, and what happens when one throws or times out. Measure on the emulator and a device; this sets Android's `deadline`.

## Requirements

- **iOS 17.4 or later**, up from 16.0. LiveCommunicationKit requires it, and we expect few active devices to fall below it by the time v2 ships. Confirm with current adoption data.
  - **Alternative for Michael:** keep a CallKit backend for iOS 16.0–17.3 behind the same API for one major version. The mapping is mechanical: start/answer/end/held/muted/DTMF/group. The cost is a second backend to test.
- **Expo SDK:** 55 or later, unchanged.
- **Android:** minSdk 26 and Core-Telecom 1.0.x, unchanged.
- **Mac Catalyst:** waits for a fix to an Apple SDK defect. With Xcode 27.1, LiveCommunicationKit delegate conformances fail to link on Catalyst.

## Migration

`expo-callkit-telecom/v1` keeps the old functions working, implemented on the v2 core, with deprecation warnings for one major version:

| v1 | v2 |
| --- | --- |
| `getActiveCallSession`, `add…CallSession…Listener` | `useConversations()` / `getConversations()` |
| `reportIncomingCall(event)` | `reportIncomingConversation(event)` |
| `startOutgoingCall(recipient, options)` | `startConversation({ to, video })` |
| `answerCall(id)` | `joinConversation(id)` |
| `addCallAnsweredListener` plus `fulfillIncomingCallConnected(requestId)` / `failIncomingCallConnected` | handler `join` |
| `reportOutgoingCallConnected(id)` | `reportConversation(id, { type: "connected" })` |
| `endCall` / `reportCallEnded(id, reason)` | `endConversation(id)` / `reportConversation(id, { type: "ended", reason })` |
| `setMuted` / `setHeld` / `playDTMF` plus their action listeners | the same commands, plus handler `mute` / `hold` / `tones` |
| `reportVideo` | `reportConversation(id, { type: "updated", video })` |
| `setRTCAudioSessionConfiguration`, `prepareAudioSessionForCall`, `restoreAudioSession` | Removed; media adapters do this |
| `setAudioSessionPortOverride` | `selectAudioRoute` |
| `registerVoIPPush` / `getVoIPPushToken` | Push is registered at launch; `usePushToken()` |
| `addCallIntentReceivedListener` | `dialRequest` event |

## Testing

- **iOS CI:** the [CallKit Simulator](https://github.com/ziahamza/callkit-simulator) runtime makes LiveCommunicationKit work in the iOS simulator. Genuine LiveCommunicationKit is torn down there about 100 ms after the report, just like CallKit. Hosted runners then exercise end-to-end journeys:
  - push, ring, join, connected, hold, end;
  - a cold launch with no handler;
  - the stale-audio fault.

  This is optional for upstream and needs no secrets.
- **Conformance:** the same scenario list runs on the iOS simulator with the runtime, on the Android emulator, and periodically on reference devices. Status derivation and deadline handling are compared across platforms.

## Phasing

1. **`2.0.0-alpha`.** `ios-voip-sdk` core and handler, iOS on LiveCommunicationKit, the Android refactor, `v1` compatibility, and the push format kept.
2. **Audio.** Media adapters (`webrtc`, `livekit`) plus the watchdog.
3. **System entry points.** Default calling app, then the default dialer (EU), translation and Mac Catalyst once Apple fixes the SDK.

## Questions for Michael

1. Is raising iOS to 17.4 in v2 acceptable, or should we keep a CallKit backend for iOS 16.0–17.3 for one major version?
2. Should the Swift core be an external package (`ios-voip-sdk`, co-maintained), or would you prefer it vendored in this repo?
3. Are the status names and the handler shape right for your users? Is `unhandledAction: "fail"` the right default on a cold launch?
4. Should the `v1` compatibility entry point live for one major version, or ship only as a codemod?
5. How should this land alongside the open fixes (#29, #49–#52) and the pending 1.0.0 release?
