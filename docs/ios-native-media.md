# iOS media

The SDK owns Apple native media registration and WebRTC audio integration. Use
the [SDK v0.4.0 media guide](https://github.com/relayphone/ios-voip-sdk/blob/v0.4.0/docs/react-native.md#media-and-audio-routes)
for the API shipped in the installed release asset.

Pass SDK options through the single `expo-callkit-telecom` plugin entry. The
independent `androidNativeMedia` option affects Android only. The SDK tarball
contains its native sources; do not add another SDK pod or Swift package.
Follow [native project regeneration](./canonical-contract.md#plugin-composition-and-native-project-upgrades)
after upgrading the provider or changing native options, then rebuild the app.
