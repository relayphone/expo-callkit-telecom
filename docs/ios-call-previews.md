---
description: Reach iOS users whose Focus filters your calls — call notifications from the caller, call previews as Live Activities, and the Focus filter state for your servers, with expo-callkit-telecom v2 and ios-voip-sdk.
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
| `focusFilter` | Your app's Focus filter, which asks the user how calls appear in each Focus; your servers receive what it asked for. | `CallFocusFilter`, `FocusFilterStore` |

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
ends without being joined here, a missed-call notification replaces it only when the caller hung
up or nobody answered. Ending it on this device, or answering or declining it elsewhere, removes
the notification. Failures and other end reasons keep it as it is.

This default changed in ios-voip-sdk 0.2.1. The module uses the SDK's default. Native integrations
that configure their own SDK notifier can restore the previous behavior, a missed-call
notification for every end reason, with
`CallNotifier.Options(endedWithoutJoining: { _ in .missedCall })`.

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
and the module follows the previews from launch. Tokens enter the snapshot when iOS supplies
them; a `callPreview` event of type `started` can arrive first. In device testing on iOS 27, the
system gave a push-started preview its update token only once the user answered the system's
question about the app's Live Activities on the Lock Screen, which could take minutes.
`activitiesEnabled: true` and a push-to-start token don't guarantee that a preview's update token
is available. An absent token alone doesn't prove that consent is pending.

Keep observing `useCallPreviewActivities()` for late tokens and rotations, without a fixed timeout.
Include `"input-push-token": 1` and a `stale-date` in each start push. Until the update token
arrives, your servers can't update or end that activity; if the conversation already ended, end
the activity when its token arrives. See the SDK's
[server contract](https://github.com/relayphone/ios-voip-sdk/blob/main/docs/call-preview-activity.md#pushes).
`getCallPreviewActivities()` reads the same snapshot outside React. A preview's token leaves
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
  pod 'IosVoipSDK/LiveActivity', :git => 'https://github.com/relayphone/ios-voip-sdk.git', :tag => 'v0.2.1'
end
```

## Focus filter

Your servers choose how each call appears on a device: a VoIP push that rings, or a call preview
started with a Live Activity push. To choose per Focus, they need what the user wants in each one.
Your app's Focus filter asks: in Settings > Focus, the user adds the filter to a Focus and sets its
parameters, such as which calls ring. The module reads the Focus filter state, what the filter last
asked for, and the app sends it to its servers.

```jsonc
{
  "expo": {
    "plugins": [
      [
        "expo-callkit-telecom",
        {
          "focusFilter": {
            "appGroup": "group.com.example.calls",
            "sources": ["./focus/CallsFocusFilter.swift"]
          }
        }
      ]
    ]
  }
}
```

Prebuild adds the App Group to the app's entitlements, builds the module with ios-voip-sdk's
`Focus` subspec, and copies `sources` to `ios/<app>/FocusFilter`, which the app compiles. With an
explicit App ID, register the App Group for it in the Apple Developer portal; EAS Build does so from
the entitlements.

### The filter

The filter's parameters are your app's own, so you write it in Swift. Start from this one, which
asks which calls ring; it is the example app's
[`focus/CallsFocusFilter.swift`](https://github.com/relayphone/expo-callkit-telecom/blob/v2/example/client/focus/CallsFocusFilter.swift):

```swift
import AppIntents
import IosVoipSDK

/// What the filter asks for in one Focus. JavaScript receives it as JSON:
/// `{ "ringing": "favorites" }`.
struct CallSettings: Codable, Hashable, Sendable {
  /// The default is what the app does outside a Focus: every call rings.
  var ringing = Ringing.everyone

  /// The store in the App Group of the plugin's `focusFilter.appGroup`.
  static let store = FocusFilterStore<CallSettings>(appGroup: "group.com.example.calls")
}

enum Ringing: String, Codable, Hashable, Sendable, AppEnum {
  case everyone
  case favorites
  case nobody

  static let typeDisplayRepresentation: TypeDisplayRepresentation = "Calls that ring"
  static let caseDisplayRepresentations: [Ringing: DisplayRepresentation] = [
    .everyone: "Everyone",
    .favorites: "Favorites",
    .nobody: "Nobody",
  ]
}

struct CallsFocusFilter: CallFocusFilter {
  static let title: LocalizedStringResource = "Incoming calls"
  static let description: IntentDescription? = "Choose which calls ring during this Focus."
  static let store = CallSettings.store

  @Parameter(title: "Ring for", default: .everyone)
  var ringing: Ringing

  var displayRepresentation: DisplayRepresentation {
    DisplayRepresentation(title: "Ring for \(ringing.rawValue)")
  }

  var settings: CallSettings { CallSettings(ringing: ringing) }
}
```

The system runs the filter when a Focus that has it turns on, and again when that Focus turns off,
with every parameter reset to its default. Its `perform()`, which ios-voip-sdk provides, records
the settings in the App Group: `active` with the settings, or `inactive` when they equal the
defaults, so make the defaults what the app does outside a Focus
([ADR 0008](https://github.com/relayphone/ios-voip-sdk/blob/main/docs/adr/0008-focus-filter-defaults-are-inactive.md)).
A target can declare one Focus filter.

### The state

```tsx
const focus = useFocusFilterState(); // null on Android and without the option

useEffect(() => {
  if (focus) void server.updateFocusFilterState(focus);
}, [focus]);
```

The state is the JSON that ios-voip-sdk sends servers, so send it unchanged:
`{ "state": "unknown" }` until the filter first runs, `{ "state": "inactive", "since": 1761948000 }`,
or `{ "state": "active", "since": 1761948000, "settings": { "ringing": "favorites" } }`, with
`since` in seconds since 1970. It changes whenever the filter runs, in any process, and
`getFocusFilterState()` reads it outside React. It says what the filter asked for, not whether a
Focus is on: a Focus without the filter still filters the app's calls. Neither the filter nor the
state needs a permission.

### Where the filter runs

With `sources`, the filter is in the app, so the system launches the app in the background, and
with it JavaScript, each time a Focus that has the filter turns on or off. We recommend it for Expo
apps: prebuild keeps it, and the app needs no extension. To run the filter without launching the
app, as ios-voip-sdk suggests, declare it in an App Intents extension instead:

1. Leave `sources` out, and keep `appGroup`.
2. Add an App Intents extension target to the iOS project, with a config plugin of your own so that
   prebuild keeps it, and give it the same App Group.
3. Compile the filter's file in the extension, with the extension's entry point:

   ```swift
   import AppIntents
   import ExtensionFoundation

   @main
   struct FocusFilterExtension: AppIntentsExtension {}
   ```

4. Give the extension's Podfile target ios-voip-sdk's `Focus` subspec from the app's SDK source:
   `pod 'IosVoipSDK/Focus', :git => 'https://github.com/relayphone/ios-voip-sdk.git', :tag => 'v0.2.1'`.
5. Send the state to your servers from the filter's `didRecord(_:)` too, since the app may not run.
