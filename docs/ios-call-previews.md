---
description: Reach iOS users whose Focus filters your calls — call notifications from the caller and call previews as Live Activities, with expo-callkit-telecom v2 and ios-voip-sdk.
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
| `callPreviewActivities` | The call preview as a Live Activity on the Lock Screen and in the Dynamic Island, with a Join button. | `CallPreviewActivities` |

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

## Call previews as Live Activities

```jsonc
{
  "expo": {
    "ios": { "bundleIdentifier": "com.example.calls" },
    "plugins": [["expo-callkit-telecom", { "callPreviewActivities": true }]]
  }
}
```

A call preview shows a conversation that is live elsewhere, such as one that a Focus filtered or
that was answered on another device, as a Live Activity, from which the user can join it. It never
rings. The Live Activity's type is ios-voip-sdk's `CallPreviewAttributes`, whose JSON is a
[contract with your servers](https://github.com/relayphone/ios-voip-sdk/blob/main/docs/call-preview-activity.md)
([ADR 0010](https://github.com/relayphone/ios-voip-sdk/blob/main/docs/adr/0010-call-preview-activity-is-sdk-type.md)).

Prebuild:

- sets `NSSupportsLiveActivities` in the app's Info.plist, and builds the module with
  ios-voip-sdk's `LiveActivity` subspec;
- generates the `CallPreviewWidget` widget extension in `ios/CallPreviewWidget`, embedded in the
  app, with the bundle identifier `<ios.bundleIdentifier>.CallPreviewWidget`. It shows call
  previews with ios-voip-sdk's default Lock Screen and Dynamic Island views, `CallPreviewView`;
- generates `JoinCallPreview.swift`, the Join button's intent, which both the app and the extension
  compile: the system opens the app and performs it there;
- adds the extension's target to the Podfile, with the `LiveActivity` subspec from the app's SDK
  source;
- declares the extension in `extra.eas.build.experimental.ios.appExtensions`, so that EAS Build
  creates its provisioning profile. For local builds, sign the extension with the app's team.

Prebuild replaces the generated files each time, so don't edit them. Run `expo prebuild --clean`
after turning the option off, so that the extension's target goes too. For frequent updates, add
`NSSupportsLiveActivitiesFrequentUpdates` to `ios.infoPlist`.

### Starting, updating and ending previews

While the app is in the foreground, the module starts a call preview's Live Activity itself when a
conversation becomes a call preview. iOS lets an app start a Live Activity only in the foreground,
so in the background, where most filtered calls arrive, your servers start it with an ActivityKit
push to the device's push-to-start token: `apns-push-type: liveactivity`, the topic
`<bundle identifier>.push-type.liveactivity`, and `"attributes-type": "CallPreviewAttributes"`.
They update and end it with the Live Activity's own push token. The module ends a preview at once
when this device joins the conversation, and shows its final state for two minutes when the
conversation ends; end the previews on the user's other devices from your servers.

Send your servers the tokens and whether the user allows Live Activities, each time they change:

```tsx
const previews = useCallPreviewActivities(); // null on Android and without the option

useEffect(() => {
  if (!previews) return;
  void server.updateCallPreviews({
    activitiesEnabled: previews.authorization.activitiesEnabled,
    frequentPushesEnabled: previews.authorization.frequentPushesEnabled,
    pushToStartToken: previews.pushToStartToken, // hex, or null until iOS issues one
    pushTokens: previews.pushTokens, // [{ conversationId, token }], one per live preview
  });
}, [previews]);
```

The system can launch the app in the background to deliver a server-started preview's push token,
and the module follows the previews from launch, so the tokens are in the snapshot when JavaScript
reads it. `getCallPreviewActivities()` reads the same value outside React. A preview's token leaves
`pushTokens` when the preview ends; stop sending its pushes then. Don't start previews on a device
whose user turned Live Activities off.

The `callPreview` conversation event follows each preview:

```ts
useConversationEvent("callPreview", (event) => {
  if (event.type === "startFailed" && event.error === "notInForeground") {
    void server.startCallPreview(event.conversationId); // Your servers push-to-start it.
  }
});
```

### Joining

The preview's Join button opens the app, after the user unlocks the device, and joins the
conversation: the join handler places it, as for `joinConversation`. A preview that your servers
started before the app ran has a conversation that the device doesn't know yet, so joining
reports it first, from the Live Activity's attributes, as a call preview.

### Your own views

To show call previews with your own design, turn the generated extension off and add your own
widget extension, for example with a config plugin of your own:

```jsonc
["expo-callkit-telecom", { "callPreviewActivities": { "widgetExtension": false } }]
```

Your extension and the app both compile the Join intent, exactly as generated:

```swift
import AppIntents
import IosVoipSDK

struct JoinCallPreview: JoinCallPreviewIntent {
  static let title: LocalizedStringResource = "Join Call"
  static let supportedModes: IntentModes = .foreground(.immediate)
  static let isDiscoverable = false

  @Parameter(title: "Call ID")
  var callID: String
}
```

The extension shows `CallPreviewAttributes` and passes the intent's type to its Join buttons. Start
from ios-voip-sdk's views, or read `context.attributes` and `context.state` in your own:

```swift
import IosVoipSDK
import SwiftUI
import WidgetKit

@main
struct CallPreviewWidgetBundle: WidgetBundle {
  var body: some Widget { CallPreviewWidget() }
}

struct CallPreviewWidget: Widget {
  var body: some WidgetConfiguration {
    ActivityConfiguration(for: CallPreviewAttributes.self) { context in
      CallPreviewView(context, join: JoinCallPreview.self)
    } dynamicIsland: { context in
      CallPreviewView.dynamicIsland(context, join: JoinCallPreview.self)
    }
  }
}
```

Give the extension's Podfile target ios-voip-sdk's `LiveActivity` subspec from the same source as
the app's, which prebuild writes to the Podfile next to `use_expo_modules!`:

```ruby
target 'YourWidgetExtension' do
  pod 'IosVoipSDK/LiveActivity', :git => 'https://github.com/relayphone/ios-voip-sdk.git', :commit => '<the app's revision>'
end
```
