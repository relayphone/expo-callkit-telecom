# Verification scope

The fork's `v2` branch is an unreleased composition of the SDK's Apple provider
and this package's Android provider. The former upstream device matrix does not
qualify this implementation. Its historical copy is preserved on
[archive/android-telecom-research](https://github.com/relayphone/expo-callkit-telecom/blob/archive/android-telecom-research/docs/verified-against.md).

Current automated checks cover:

- [Packed SDK/fork consumers](../.github/workflows/v2-sdk-evaluation.yml), public
  types, plugin composition, errors and route updates.
- [Android native behavior](../.github/workflows/v2-android.yml), including media
  readiness, hold/resume, filtering and cleanup on an API 35 emulator.
- [Installed Android JavaScript journeys](../.github/workflows/v2-example.yml)
  through the real bridge, with independent Telecom and notification evidence.
- [iOS example](../.github/workflows/v2-ios.yml) and
  [thin installed SDK consumer](../.github/workflows/v2-sdk-evaluation.yml) builds
  for the iOS Simulator.

Emulator/simulator results do not establish physical-device audio, cloud push
transport delivery or production latency percentiles. See
[Getting started](./getting-started.md) and the
[SDK guide](https://github.com/relayphone/ios-voip-sdk/blob/main/docs/react-native.md)
for current integration requirements.
