# Android video changes

Android uses Core-Telecom 1.1.0. Applications must compile against Android SDK 36
or later; the minimum runtime version remains API 26. Video tracks and camera
permission belong to the app. Changing a call's video state never opens a camera
or requests permission.

`capabilities.video` means that the app or server can request a mid-call video
change. It follows the compatibility bound actually passed to
`registerAppWithTelecom`, rather than inspecting Core's internal session classes.
With the default bound of API 34:

| Backing path | App/server changes | Unsolicited `system` changes |
| --- | --- | --- |
| Legacy, API 26–34 | Supported | None; Core has no mid-call system bridge |
| Transactional, API 34 (custom bound below 34) | `unsupported` | None |
| Transactional, API 35–36 | Supported | Downgrades only; Core reverts system upgrades |
| Transactional, API 37+ | Supported | Upgrades and downgrades |

On iOS, SDK 0.8.0 reports video changes from the app or server only. The platform
has no mid-call system video action, so it never emits the `system` source.

The SDK 0.8.0 contract calls the native `setConversationVideo(id, enabled)`
endpoint. An `updated` report's `video` field follows the same platform request
path. Each request waits for Core to accept it and for the type stream to report
the requested value. Snapshots and CallStyle notifications then follow `isVideo`.
Same-value requests resolve without a new event. Unsupported calls reject with
`unsupported`, missing IDs with `unknown_conversation`, and retained or recently
ended IDs with `invalid_arguments` (the most recent 128 ends are remembered).
Updated reports publish member changes first and ignore unchanged video even
when unsupported; a changed video failure still rejects, with members retained.

`videoChanged` carries `conversationId`, `isVideo`, and `source`: `app` for a local
request, `server` for an updated report, and `system` for an unmatched platform
change. One collector consumes Core's type stream. App/server mutations are
serialized, matching a pending request by value; initial and duplicate emissions
are ignored. Core supplies no origin or request token. An unrelated system change
to the pending request's value is indistinguishable from its acknowledgment and
is attributed to that request. System events are best-effort as described above.

## Audio and routes

The running audio session remains in `MODE_IN_COMMUNICATION`; video changes do
not deactivate or reactivate it. On upgrade, Core's own conditional speaker
fallback applies: video, current receiver, speaker available, no wired headset
and no non-watch Bluetooth device. This package adds no second speaker fallback.

On downgrade, the package requests the receiver only after observing an upgrade's
receiver-to-speaker transition, with no later route change or explicit app route
selection. Bluetooth or wired endpoints prevent restoration. Already selected
speakers are preserved. Duplicate endpoint notifications are not route changes.
Core's Android-only limitation remains: an explicit receiver choice made more
than about three seconds before upgrade can be overridden by its fallback.
iOS retains the full user-route preservation rule.

## Qualification

The Core-Telecom 1.1.0 answer, compatibility and endpoint qualification is shared
with the [Android answer contract](android-answer.md). Video tests add request
matching, serialization, event sources, CallStyle changes and route restoration.
Simulator rendered-frame progress is a continuity signal, not an acoustic
recording or a guarantee about a physical headset’s switching gap.
