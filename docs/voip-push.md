---
description: VoIP push payload shape for expo-callkit-telecom v2 — APNs VoIP (PushKit) on iOS and FCM data messages on Android. Decoded natively so incoming calls report from a terminated state.
---

# VoIP push payload

When a VoIP push arrives ([PushKit](https://developer.apple.com/documentation/pushkit) on iOS, an [FCM](https://firebase.google.com/docs/cloud-messaging) data message on Android), the module decodes the payload natively — before JavaScript runs — and reports the call to the system.

The invitation is the same `IncomingCallPayload` on both transports. All keys are camelCase:

```jsonc
{
  "eventId": "550e8400-e29b-41d4-a716-446655440000",   // required: identifies this delivery, for deduplication
  "serverCallId": "call-456",                          // required: your backend's call ID, kept for the whole call
  "caller": {
    "handleType": "phone",                             // "phone", "email" or "generic"
    "value": "+14155551234",                           // phone numbers must be E.164
    "displayName": "Jane Smith",
    "hostContactID": "host-contact-42"                 // optional, opaque
  },
  "hasVideo": false,
  "metadata": {                                        // optional, opaque pass-through
    "chatId": "abc-123",
    "tenantId": "acme-co"
  }
}
```

v1's participant-like caller, `{ id, phoneNumber?, email? }`, is no longer valid; see [Replace the incoming caller child](./migrating-v1-to-v2#replace-the-incoming-caller-child).

Keys under `metadata` reach JavaScript unchanged, as the conversation's `payload.metadata`. The module treats them as opaque, so cast at the read site:

```ts
import { useConversation } from "expo-callkit-telecom";
import type { ConversationId } from "expo-callkit-telecom/types";

function ChatLink({ id }: { id: ConversationId }) {
  const conversation = useConversation(id);
  const chatId = conversation?.payload?.metadata?.chatId as string | undefined;
  // ...
}
```

Both transports wrap the invitation under an `incomingCall` key, at different layers — APNs in the push payload dictionary, FCM in the data block. (The snake_case `incoming_call` is also accepted for backwards compatibility.)

## iOS — APNs VoIP push

Send a [VoIP push through APNs](https://developer.apple.com/documentation/usernotifications/sending-voip-pushes) (`apns-push-type: voip`) whose dictionary payload nests the invitation under `incomingCall`:

```jsonc
{
  "incomingCall": { /* IncomingCallPayload — see above */ }
}
```

The SDK reports the call to LiveCommunicationKit before PushKit's callback returns, as Apple requires. A repeated `eventId` or `serverCallId` doesn't present a second call.

## Android — FCM data message

FCM data values must be strings, so JSON-encode the invitation and put it under `incomingCall`:

```jsonc
{
  "data": {
    "messageType": "incomingCall",
    "incomingCall": "{\"eventId\":\"...\",\"serverCallId\":\"...\", ... }"
  }
}
```

A repeat delivery of an `eventId` or `serverCallId` seen in the last two minutes is dropped. Other data messages are forwarded to [`expo-notifications`](https://docs.expo.dev/versions/latest/sdk/notifications/)'s service for normal handling.

## Ending a call from the server

Besides `incomingCall`, both transports carry `callEnded`, which ends a known incoming call: a VoIP push on iOS, a data message on Android, each with `messageType: "callEnded"` and a JSON-string `callEnded` body. See [Ending a call from the server](platform-notes.md#ending-a-call-from-the-server).
