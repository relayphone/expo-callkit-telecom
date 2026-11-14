# VoIP push payload

Native push intake reports incoming calls before JavaScript runs. This package
supplies Android's FCM intake; the SDK supplies iOS PushKit intake. Both use the
shared `IncomingCallPayload` with camelCase keys:

```json
{
  "eventId": "delivery-123",
  "serverCallId": "call-456",
  "caller": {
    "handleType": "phone",
    "value": "+14155551234",
    "displayName": "Alice",
    "hostContactID": "host-contact-42"
  },
  "hasVideo": false,
  "metadata": { "chatId": "abc-123" }
}
```

`serverCallId` becomes the conversation ID unchanged. Use a distinct `eventId`
for each server event. `handleType` is `phone`, `email` or `generic`;
`hostContactID` is optional and opaque. Phone handles require an international
country code. See the [caller contract](./contracts/android-caller.md) and
[migration guide](./migrating-v1-to-v2.md#replace-the-incoming-caller-child).

Optional `metadata` reaches `conversation.payload.metadata`. Check its value
type before using it:

```ts
import { useConversation } from "expo-callkit-telecom";
import type { ConversationId } from "expo-callkit-telecom/types";

export function useChatId(id: ConversationId): string | undefined {
  const value = useConversation(id)?.payload?.metadata?.chatId;
  return typeof value === "string" ? value : undefined;
}
```

Media credentials belong in the separate `media` child and are excluded from
public snapshots. Follow the [Android native media guide](./android-quick-start.md)
for fresh LiveKit credentials; do not put credentials in `metadata`.

## iOS — APNs VoIP push

Place the invitation object under `incomingCall` in the APNs payload:

```json
{
  "incomingCall": {
    "eventId": "delivery-123",
    "serverCallId": "call-456",
    "caller": { "handleType": "phone", "value": "+14155551234", "displayName": "Alice" },
    "hasVideo": false
  }
}
```

Follow the [SDK push guidance](https://github.com/relayphone/ios-voip-sdk#quick-start)
for APNs headers, reporting requirements and server events. The SDK handles
PushKit reporting and duplicate deliveries natively.

## Android — FCM data message

Send a high-priority data message. FCM data values are strings, so JSON-encode
the invitation:

```json
{
  "data": {
    "messageType": "incomingCall",
    "incomingCall": "{\"eventId\":\"delivery-123\",\"serverCallId\":\"call-456\",\"caller\":{\"handleType\":\"phone\",\"value\":\"+14155551234\",\"displayName\":\"Alice\"},\"hasVideo\":false}"
  }
}
```

Native intake shares duplicate admission by event/call identity and retains
reported or filtered outcomes for two minutes. A reported outcome proves Core
admission, not audible ringing. Other message types pass to `expo-notifications`.
See [incoming policy](./contracts/android-incoming-policy.md) and the
[public push sender](../example/server/README.md).

## Ending a call from the server

Use `messageType: "callEnded"` and a JSON-string `callEnded` body, or the host
`receiveCallEnded` API. See the [Android envelope and ownership contract](./platform-notes.md#ending-a-call-from-the-server)
and the SDK's [iOS push rules](https://github.com/relayphone/ios-voip-sdk/blob/v0.5.0/Sources/VoIPCore/VoIPPush%2BDefaultFormat.swift)
for when a VoIP push is appropriate. Server-end acceptance does not mean UI
removal has finished; observe state and correlated events.
