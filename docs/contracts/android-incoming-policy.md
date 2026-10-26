# Android incoming policy and outcomes

Android can accept a Core-Telecom call while suppressing its CATEGORY_CALL
notification. Core admission and notification presentation are separate facts.
The library reports to Core first, checks native policy, then prepares media/JS
only for accepted invitations. A filtered report returns the existing shared
`{ status: "filtered", reason, nativeReason? }` shape, without a conversation ID
or a fabricated snapshot entry. Duplicate reports retain their original outcome.
Native Answer also waits for this native policy decision. Application block-list
and other custom rejections are resolved before presenting a notification or
Answer action. A DND-suppressed call notification is enqueued and cancelled to
preserve Android's repeat-caller history.

The default native check maps total DND and alarms-only to `doNotDisturb`.
On API33+, priority mode uses `NotificationManager.matchesCallFilter` with the
caller's real `tel:`/`mailto:` URI. The notification also carries that Person URI,
so Android can apply contact/starred/repeat-caller policy. A user-enabled bypass
channel remains under Android's presentation policy.
An id-only VoIP caller uses a stable app-scoped URI, has unknown contact affinity,
and participates in Android's non-telephone repeat-caller history.

Priority caller matching requires existing READ_CONTACTS permission or notification
listener access. The library requests neither permission nor listener access.
When caller matching is unavailable (including older Android), it leaves the call
reported and lets Android control sound. A reported outcome alone never proves
that the phone rang. Total DND does not require contacts access to determine that
calls are suppressed.

Register a native `NativeIncomingObserver` in `NativeIncomingOutcomes.observers`
during `Application.onCreate`. It receives typed Reported/Filtered/AtCapacity/Failed
results with eventId/serverCallId, without credentials. A filtered result includes
the typed `NativeIncomingFilterReason` (`doNotDisturb`, `blockList`, `other`).
The callback runs on main and must dispatch backend decline/AI screening work
without blocking. It works without JS. The same envelope is available as the
typed `incomingConversationOutcome` JS event when the bridge is alive.

Apps can register `NativeIncomingFilters.evaluate` for a native local block list
or another policy. Its input excludes media credentials, it must honor
cancellation, and its budget is750ms. An unavailable/failing custom check leaves
presentation to the OS. It must never wait for JavaScript. This is an application
policy hook, not an Android system block-list lookup.

Policy broadcasts recheck ringing calls. If a previously reported/prewarmed call
becomes filtered, native closes its media and Core call, updates dedupe state and
emits the filtered outcome through both hooks. Active calls are not reclassified
as incoming invitations.

## Emulator evidence

`tools/run-android-dnd.sh` drives actual `adb shell cmd notification set_dnd
on|priority|off`. A CI-only APK targeting34 sets global sender/repeat-caller
policy through the public API; Android15 restricts global writes by target35
apps. The application under test continues to target35. The helper is never
packaged in the library or example.

Each case first uses Core-Telecom directly and posts the real library CallStyle
notification. A test-only notification listener records Android's actual
`Ranking.matchesInterruptionFilter`. It then checks the production typed result,
native hook and duplicate delivery. The raw call contributes to repeat history;
the JSON records the native decision after that first call separately.

The matrix covers off/on, priority any/no calls, contacts versus unknown, starred
versus ordinary contacts, repeat caller first/second attempt, and restoration.
Required JSON checks distinguish Core's actual RINGING state from notification
eligibility/suppression. They also observe actual ringtone playback activity via
AudioManager, require nonzero volume for allowed calls, and record ringer mode.
Priority DND can report external ringer mode SILENT while an allowed call plays;
the mode is recorded, while actual playback is the required evidence. NORMAL is
asserted when DND is off, and allowed cases require nonzero ring volume.
These are virtual-device playback facts, not an acoustic recording. A separate real media journey
requires prejoin→DND change→filtered outcome, Core removal, native closure,
server participant departure and no capture. Native blockList/other outcomes and
deduplication are also checked, including a delayed rejecting filter that exposes
neither a notification nor an early Answer. Platform CallsManager dump counts
independently verify Core removal; raw dumps are not uploaded. These artifacts must pass on the exact PR head before merge.

References: [Android caller-filter API](https://developer.android.com/reference/android/app/NotificationManager#matchesCallFilter(android.net.Uri)),
[Android15 policy behavior](https://developer.android.com/about/versions/15/behavior-changes-15#dnd-changes).
