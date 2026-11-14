# iOS call previews and Focus

The SDK owns communication notifications, call preview Live Activities and Focus
integration. Follow the [SDK v0.5.0 guide](https://github.com/relayphone/ios-voip-sdk/blob/v0.5.0/docs/react-native.md#notifications-call-previews-and-focus)
and its [Live Activity server contract](https://github.com/relayphone/ios-voip-sdk/blob/v0.5.0/docs/call-preview-activity.md).

Pass those options through the single `expo-callkit-telecom` plugin entry, then
[regenerate native projects and rebuild](./canonical-contract.md#plugin-composition-and-native-project-upgrades).
Android does not support preview joining; its preview and Focus hooks return
`null`. See the [platform contract](./canonical-contract.md).
