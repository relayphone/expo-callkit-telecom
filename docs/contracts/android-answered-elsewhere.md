# Android answered elsewhere

When a backend or another endpoint takes an incoming call, send its existing
server identity through the host API:

```ts
const accepted = await receiveCallEnded({
  eventId: "server-event-123",
  serverCallId: "server-call-456",
  reason: "answeredElsewhere",
});
```

The input uses the [pinned SDK types](https://github.com/relayphone/ios-voip-sdk/blob/597c39137b51b2537ce60e15e6ecf86d281f5d86/expo/src/conversations/types.ts):
`CallEndedPayload` contains `eventId`, `serverCallId`, and
`reason: Exclude<EndedReason, "local">`. Local hangups use `endConversation`.
The promise reports whether a signal was accepted for a known incoming
invitation; it does not promise that platform or visible UI cleanup has finished.

Accepted signals stop native media without consulting an action handler or waiting
for JavaScript. Native state publishes an ended conversation with the supplied
reason before emitting `answeredElsewhere`. That event contains
`{ conversationId, serverCallId, eventId }`, correlated to the accepted signal,
and is emitted once. A later platform admission receives a retained end intent
without restoring the ringing notification or starting media.

Android retains incoming ownership and deduplicates end events:

- End event IDs are deduplicated for 120 seconds before known-call lookup. A
  valid unknown signal consumes its event ID, returns false, and creates no
  tombstone that would end a future invitation.
- Incoming ownership survives the existing 120-second metadata retention
  window; active or pending admissions are not discarded while awaiting a report.
  Intake is not restricted to ringing status: a known connected or held incoming
  conversation can also be ended by the server.
- Typed local signals reject with `invalid_arguments` before deduplication, so a
  corrected remote signal can still use that event ID.
- IDs must contain a non-whitespace character under Foundation's
  `whitespacesAndNewlines` trim rules. Matching and deduplication preserve the
  original identifier bytes, including any surrounding whitespace.

The existing FCM envelope uses the same intake:

```json
{
  "messageType": "callEnded",
  "callEnded": "{\"eventId\":\"server-event-123\",\"serverCallId\":\"server-call-456\",\"reason\":\"answeredElsewhere\"}"
}
```

The wire body requires both IDs. Missing, local, and unrecognized wire reasons
normalize to `remoteEnded`, matching the [SDK wire decoder](https://github.com/relayphone/ios-voip-sdk/blob/597c39137b51b2537ce60e15e6ecf86d281f5d86/Sources/VoIPCore/VoIPPush%2BDefaultFormat.swift).
These defaults do not change the typed host API's required non-local reason.

## Native disconnect mapping

Android's transactional `CallControl.disconnect` accepts only LOCAL, REMOTE,
MISSED, and REJECTED. Passing ANSWERED_ELSEWHERE (11) throws before the request
reaches Telecom. The API34+ transactional backend therefore uses REMOTE (3) and
sets `DisconnectCause.reason` to `answeredElsewhere`. The SDK terminal reason and
correlated event remain `answeredElsewhere`; the native reason string does not
turn code 3 into code 11. See [API35 platform validation](https://android.googlesource.com/platform/frameworks/base/+/android-15.0.0_r1/telecomm/java/android/telecom/CallControl.java#419).

The legacy Connection backend can use ANSWERED_ELSEWHERE (11): the pinned
[Core-Telecom 1.0.1 source artifact](https://dl.google.com/dl/android/maven2/androidx/core/core-telecom/1.0.1/core-telecom-1.0.1-sources.jar)
has `CallSessionLegacy.setConnectionDisconnect` forward the supplied cause to
`Connection.setDisconnected` without the transactional whitelist. The platform
[Connection implementation](https://android.googlesource.com/platform/frameworks/base/+/android-15.0.0_r1/telecomm/java/android/telecom/Connection.java#2695)
retains and forwards that cause. The SDK uses the backend Core selects; it never
forces legacy mode, reflects into private APIs, or fabricates successful code-11
evidence on API35.

## Emulator acceptance

The API35 journey requires three direct native-host samples and three samples
through `NativeCallPush.handle` with the production FCM data envelope. This
exercises native FCM intake, not cloud delivery. Each sample first requires an
actual RINGING Telecom call and a visible, uniquely identified incoming CallStyle
card in the expanded SystemUI notification shade.

Completion requires terminal SDK state, the correlated event, notification
removal, Core call removal, and visible card removal. UI absence must be observed
in two consecutive valid SystemUI accessibility roots; a missing or unrelated
root is not evidence of dismissal. The result records monotonic observation
timestamps and Telecom's recorded disconnect time, code and reason for all six
samples. Terminal state, correlated event, empty snapshot and notification-manager
removal retain the 1,000 ms engineering budget. Telecom's own `SET_DISCONNECTED`
event must also be recorded within 1,000 ms of the signal, with the expected code
and reason. API35 [EventManager](https://android.googlesource.com/platform/frameworks/base/+/android-15.0.0_r1/telecomm/java/android/telecom/Logging/EventManager.java)
records wall time as `HH:mm:ss.SSS` in the system timezone. The test captures
wall time next to the signal's monotonic timestamp, checks the timezone, and
resolves the event's date across midnight. Polling does not determine disconnect latency.

The call must still leave Telecom's tracked calls. Observing that removal and
visible-card removal share the existing separate bound:
the runtime SystemUI `heads_up_notification_minimum_time` resource plus 1,000 ms
for observation. The test reads that integer with public package/resources APIs;
it does not change notification policy or simulate a dismissal.
Every Telecom poll records its request/return timestamps, tracked-call presence
and disconnect-event presence without retaining raw dumps. Results report the
recorded disconnect, first event observation and tracked-call removal observation
separately, so emulator polling and removal lag remain visible.

API35 configures a [2,000 ms heads-up minimum](https://android.googlesource.com/platform/frameworks/base/+/android-15.0.0_r1/packages/SystemUI/res/values/config.xml#158).
Incoming notifications carry a full-screen intent, making heads-up entries
[sticky](https://android.googlesource.com/platform/frameworks/base/+/android-15.0.0_r1/packages/SystemUI/src/com/android/systemui/statusbar/policy/BaseHeadsUpManager.java#836);
[SystemUI may retain a cancelled entry until its earliest removal time](https://android.googlesource.com/platform/frameworks/base/+/android-15.0.0_r1/packages/SystemUI/src/com/android/systemui/statusbar/notification/collection/coordinator/HeadsUpCoordinator.kt#662).
The [archived six-sample results](https://github.com/relayphone/expo-callkit-telecom/blob/archive/android-telecom-research/docs/contracts/android-answered-elsewhere-results.md) report native
completion and visible-card removal separately, including runtime policy and
observer cost. They do not establish a universal floor or physical-device timing.

Each sample collapses the shade before posting its invitation, then requires the
actual caller and incoming actions before signalling. Receipts include incoming,
report, visible-before-signal, first-absence and per-scan timings so card age and
observer cost remain visible. No artificial wait consumes the heads-up minimum.
A successful boolean, SDK snapshot or notification-manager query alone never
proves visible UI dismissal; physical-device and DND measurements remain separate.
