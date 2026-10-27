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

The input matches [SDK #159 at `8a58905`](https://github.com/ziahamza/ios-voip-sdk/blob/8a58905d1be01dcc6c11da183f2564dd80aef79d/Expo/src/types.ts):
`CallEndedPayload` contains `eventId`, `serverCallId`, and
`reason: Exclude<EndedReason, "local">`. Local hangups use `endConversation`.
The promise reports whether a signal was accepted for a known incoming
invitation; it does not promise that platform or visible UI cleanup has finished.

Accepted signals bypass the End handler and stop native media without waiting
for JavaScript. Native state publishes an ended conversation with the supplied
reason before emitting `answeredElsewhere`. That event contains
`{ conversationId, serverCallId, eventId }`, correlated to the accepted signal,
and is emitted once. A later platform admission receives a retained end intent
without restoring the ringing notification or starting media.

Intake follows the SDK's [retained incoming ownership and end-event policy](https://github.com/ziahamza/ios-voip-sdk/blob/8a58905d1be01dcc6c11da183f2564dd80aef79d/Sources/VoIPSDK/IncomingIntake.swift):

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
normalize to `remoteEnded`, matching the [SDK wire decoder](https://github.com/ziahamza/ios-voip-sdk/blob/8a58905d1be01dcc6c11da183f2564dd80aef79d/Sources/VoIPSDK/PushWire.swift).
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
root is not evidence of dismissal. The result records monotonic timestamps for
all six samples, together with actual native disconnect code and reason. The
1,000 ms signal-to-cleanup bound is an engineering acceptance budget, separate
from physical-device UI dismissal or DND measurements. A successful boolean
return, SDK snapshot, or notification-manager query alone does not prove visible
UI dismissal.
