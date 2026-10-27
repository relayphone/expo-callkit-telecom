# Typed incoming caller identity

The Android v2 caller matches [SDK #150 at e25b64f](https://github.com/ziahamza/ios-voip-sdk/blob/e25b64f71fb9b2c1ea884c1d39d965c131f00ac1/Expo/CALLER.md).
`Participant` remains the outgoing/member model. Incoming callers now require:

```ts
caller: {
  handleType: "phone", // "phone" | "email" | "generic"
  value: "+1 (202) 555-0123",
  displayName: "Caller",
  hostContactID: "host-store-42", // optional opaque host identifier
}
```

The native caller trims Foundation whitespace from the value, display name and
optional host contact ID. Required text and a supplied contact ID must remain
nonempty and contain no Unicode control/format characters. Phone formatting removes
only ASCII spaces and `()-.`; the result must be `+` followed by 2–15 ASCII digits,
starting with a nonzero digit. No region, extension or actual number allocation is
guessed. Email follows the SDK's Swift-character separator rule: exactly one standalone
`@` grapheme with nonempty sides and no whitespace;
case is preserved and a dotted domain is not required. Generic values keep their
type and case. Unknown fields are ignored, but a legacy `caller: { id: ... }`
without the required typed fields is invalid.

Core-Telecom receives the normalized display name and a `tel:`, `mailto:` or
`sip:` address according to the explicit type. Android URI encoding preserves the
exact normalized scheme-specific value; for example `tel:%2B12025550123` decodes
to `+12025550123`. Core-Telecom 1.0.1's documented API26/27 compatibility uses SIP
for the Core address on those two versions; API28+ retains the typed URI.
Notification Person and caller filtering always use the typed communication URI.

Incoming snapshots retain the normalized `Caller` child. Their member projection
uses `id = hostContactID ?? value`, the normalized name, and a phone/email field
only for the declared type. Notification `Person.uri` and `.addPerson` expose the
same communication identity to Android contact affinity and repeat-call matching.
`hostContactID` is preserved as `Person.key`; it is not interpreted as an Android
Contacts row ID, nor does it grant starred/VIP or DND privileges.

Both host reports and the FCM intake use this path. Invalid caller input returns
the existing v2 failed outcome with `invalid_arguments`. Media credentials remain
the fork's separate optional `incomingCall.media` extension; no credentials enter
snapshots. iOS native wiring is unchanged.

[Caller Swift validation](https://github.com/ziahamza/ios-voip-sdk/blob/e25b64f71fb9b2c1ea884c1d39d965c131f00ac1/Sources/VoIPSDK/ConversationModels.swift#L44)
provides the parity vectors. Instrumented tests cover normalization, strict field
types, Unicode controls, projections and actual Android URI encoding; the complete
Core, media, DND and release Expo journeys also run with the new fixture schema.
