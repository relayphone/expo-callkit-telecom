---
description: Reach iOS users whose Focus filters your calls — call notifications from the caller, with expo-callkit-telecom v2 and ios-voip-sdk.
---

# iOS: Focus and call previews

When a Focus doesn't allow your app, iOS filters its incoming calls, even from the people the
Focus allows: LiveCommunicationKit doesn't ring for them. The module keeps such a call as a call
preview, a conversation with `status === "previewing"` and a `previewReason` of
`{ type: "filtered", cause: "doNotDisturb" }`, which `joinConversation` joins. ios-voip-sdk has
opt-in pieces that reach the user anyway, and the config plugin turns them on:

| Plugin option | What the user gets | ios-voip-sdk |
| --- | --- | --- |
| `callNotifications` | A communication notification from the caller, which a Focus lets through for the people it allows. It never rings. | `CallNotifier` |

Each option is off by default and changes the native build, so rebuild the app after changing it.

## Call notifications

```jsonc
{
  "expo": {
    "plugins": [["expo-callkit-telecom", { "callNotifications": true }]]
  }
}
```

The module posts a call notification for each incoming conversation that Do Not Disturb or a
Focus filtered, as the conversation becomes a call preview. The system shows it as coming from the
caller: at once for the people the Focus allows, and quietly in Notification Center for everyone
else. It is never time-sensitive and never rings: its interruption level is active, with the
default notification sound
([ADR 0009](https://github.com/relayphone/ios-voip-sdk/blob/main/docs/adr/0009-call-notifications-never-ring.md)).
When the conversation is joined on this device, the module withdraws the notification. When it
ends without being joined here, the module replaces it with a missed-call notification.

Prebuild adds the Communication Notifications capability, the
`com.apple.developer.usernotifications.communication` entitlement, and builds the module with
ios-voip-sdk's `Notifications` subspec and expo-notifications. `INStartCallIntent` is in the app's
`NSUserActivityTypes` already. The app:

- asks for the user's permission for notifications, for example with expo-notifications'
  `requestPermissionsAsync()`. The module never asks; without the permission, each notification
  fails with `notAuthorized`.
- leaves expo-notifications as the notification center's delegate, as it is by default: the module
  receives the responses to call notifications through expo-notifications'
  `NotificationCenterManager`. Another library that sets `UNUserNotificationCenter`'s delegate
  stops them from arriving.
- with an explicit App ID, enables Communication Notifications for it in the Apple Developer
  portal. EAS Build does so from the entitlement.

Follow the notifications with the `callNotification` conversation event, while JavaScript runs:

```ts
useConversationEvent("callNotification", (event) => {
  if (event.type === "failed" && event.error === "notAuthorized") explainNotifications();
});
```

### Responses

An incoming call's notification has the category `VoIPNotifications.call`, with a Join action,
`VoIPNotifications.join`, which opens the app. The module adds the category at launch unless the
app already has one with that identifier. To localize the action's title, add the category from
JavaScript, which replaces the module's:

```ts
import * as Notifications from "expo-notifications";

await Notifications.setNotificationCategoryAsync("VoIPNotifications.call", [
  { identifier: "VoIPNotifications.join", buttonTitle: "Rejoindre", options: { opensAppToForeground: true } },
]);
```

The module routes the user's responses natively, as they arrive, so a response that launches the
app is handled before JavaScript loads. When the user chooses Join, or taps the notification, while
the conversation is live in a call preview, the module joins it: the join handler places it, as
for `joinConversation`. When the conversation is over, the route is to call the caller back. The
`callNotificationRoute` conversation event says where each response led; the latest route waits
for a listener, so the route of a response that launched the app isn't lost:

```ts
useConversationEvent("callNotificationRoute", (route) => {
  if (route.type === "callBack") offerCallBack(route.caller, route.video);
  // "joining": the conversation's status shows the join, and the join handler places it.
});
```

expo-notifications still delivers each response to its own listeners. Don't join from them: the
module already did.

With `callNotifications`, a request from Recents or Siri to call a party whose conversation is
live in a call preview joins that conversation. Otherwise it arrives as a `dialRequest` event, as
without the option.

The notifications' text is English ("Incoming call", "Missed call", and their video forms), and
the module uses the defaults of ios-voip-sdk's `CallNotifier.Options`.
