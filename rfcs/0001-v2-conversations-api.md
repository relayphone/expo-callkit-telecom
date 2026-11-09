# RFC 0001: conversations API migration

Status: superseded by the composed SDK API.

This proposal recorded the original v2 migration. Its generated IDs, broad action
handler and configurable join policy were superseded by the shared SDK contract.
The complete historical proposal is preserved on
[archive/android-telecom-research](https://github.com/relayphone/expo-callkit-telecom/blob/archive/android-telecom-research/rfcs/0001-v2-conversations-api.md).

Use these maintained references for the implementation:

- [SDK API and iOS guide](https://github.com/relayphone/ios-voip-sdk/blob/main/docs/react-native.md)
- [Fork conversations contract](../docs/canonical-contract.md)
- [Migration from v1](../docs/migrating-v1-to-v2.md)
- [Package evaluation and pinned SDK installation](../docs/sdk-package-evaluation.md)

The SDK owns the shared TypeScript API and Apple provider; this package supplies
Android Core-Telecom and the composed config plugin. The fork contract documents
Android-specific limits and lifecycle behavior.
