# iOS native media

`@relayphone/ios-voip-sdk` supplies this package's sole Apple native provider.
Its [React Native media guide](https://github.com/relayphone/ios-voip-sdk/blob/main/docs/react-native.md#media-and-audio-routes)
owns iOS media setup and the `NativeCallMedia` registration contract.

For media that prepares before JavaScript loads, register a Swift `CallMedia`
through the SDK's `NativeCallMedia.register(_:observers:)` in an Expo app delegate
subscriber's `application(_:willFinishLaunchingWithOptions:)`. Registration must
precede the SDK module's client startup in `didFinishLaunching`.
See [NativeCallMedia](https://github.com/relayphone/ios-voip-sdk/blob/main/expo/ios/NativeCallMedia.swift)
for registration and access to credentials kept out of JavaScript.

The SDK configures audio before media preparation and calls `activate()` and
`deactivate()` with system audio changes. Registered native media overrides
`mediaAdapter`. A prepared conversation reports `media: "native"`; the app should
not connect the same media again in JavaScript. The placement handler still
places outgoing calls and iOS preview joins; native media preparation follows
placement. Answering a ringing call does not consult the handler.

For app-managed WebRTC or LiveKit React Native media, configure
`mediaAdapter: "webrtc"` and provide the WebRTC dependency described in the SDK
guide. `mediaAdapter: "none"` leaves audio integration to the app.
The independent `androidNativeMedia` option affects Android only.

Configure the fork plugin once and follow
[clean regeneration](./canonical-contract.md#plugin-composition-and-native-project-upgrades)
when upgrading an app with the retired provider. The SDK tarball includes its
native sources; do not add a separate SDK pod or Swift package to the app.
