# iOS: Focus and call previews

The SDK owns call previews, communication notifications and Focus integration.
Follow its [React Native guide](https://github.com/relayphone/ios-voip-sdk/blob/main/docs/react-native.md#notifications-call-previews-and-focus)
for setup, permissions, event types and native extension details. Configure the
options through the fork's composed plugin; the SDK supplies the Apple provider.

| Plugin option | Integration |
| --- | --- |
| `callNotifications` | Communication notifications for calls filtered by Do Not Disturb or a Focus; requires notification permission and `expo-notifications` |
| `callPreviewActivities` | Preview Live Activities, with a generated widget and shared Join intent; requires `ios.bundleIdentifier` |
| `focusFilter` | App Group and optional app-relative Swift sources for the application's Focus filter |

These options are disabled by default and require a rebuilt native application.
For example:

```json
{
  "expo": {
    "ios": { "bundleIdentifier": "com.example.calls" },
    "plugins": [["expo-callkit-telecom", {
      "callNotifications": true,
      "callPreviewActivities": true,
      "focusFilter": { "appGroup": "group.com.example.calls" }
    }]]
  }
}
```

On iOS, a preview has `status: "previewing"` and a typed `previewReason`.
`joinConversation(id)` requests placement through the optional `join` handler;
`endConversation(id)` stops following that preview on this device. A ringing
call is answered natively and does not consult the handler.

Use `useCallPreviewActivities()` or `getCallPreviewActivities()` to send
ActivityKit authorization and push tokens to the backend. Follow the SDK's
[Live Activity server contract](https://github.com/relayphone/ios-voip-sdk/blob/main/docs/call-preview-activity.md)
for pushes. With `{ widgetExtension: false }`, supply your own widget and Join
intent using the SDK's supported integration.

`useFocusFilterState()` and `getFocusFilterState()` expose the application's
filter settings, rather than whether any system Focus is enabled. Send that
state unchanged to the backend. Follow the SDK guide for Swift imports and source
registration; do not add the retired provider or a separate Git-pinned SDK pod.

Android does not support preview joins. Preview and Focus getters/hooks return
`null` there; filtered and answered-elsewhere calls terminate and remove their
notification. See the [platform contract](./canonical-contract.md) and
[clean regeneration instructions](./canonical-contract.md#plugin-composition-and-native-project-upgrades).
