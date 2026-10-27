# Android priority DND: contacts and repeat callers

**Yes, in this API35 emulator study.** A starred contact rang through priority
DND when calls from starred contacts were allowed. With repeat callers enabled,
a second genuine call from the same otherwise excluded contact or noncontact
also rang. Both raw Core-Telecom and production native intake produced this
pattern in all three independent samples per cell. These are Android ringtone
playback observations; physical speaker audibility was not measured.

[PR29](https://github.com/relayphone/expo-callkit-telecom/pull/29) was qualified at
`7c9668ab43173a64ed225b20877ff160b7dd996b` in
[run40456679331](https://github.com/relayphone/expo-callkit-telecom/actions/runs/40456679331)
and merged as `f2424a8166425afed4bbd93c856be36770190d09` with the same full source
tree. The [checked JSON](./android-results.json) retains all 108 pairs / 216
reports, three samples per policy/contact cell in each lane, source hashes,
policy readbacks and missing observations. This is the Android counterpart to
the [pinned iPhone Focus results](https://github.com/relayphone/ios-voip-sdk/blob/41b5de1787a59fd16a9493b5d4366c98768bd25a/docs/research/focus/results.md).

## Which settings rang

Each cell is **first call A → second call B**, with ringtone playback observed
out of three samples. The table applies independently to both lanes; results
were identical. The second call used the same exact caller URI and a new genuine
server/event identity.

| Allow calls from | Repeat callers | Starred contact | Ordinary contact | Noncontact |
| --- | --- | --- | --- | --- |
| Anyone | Off | 3/3 → 3/3 | 3/3 → 3/3 | 3/3 → 3/3 |
| Anyone | On | 3/3 → 3/3 | 3/3 → 3/3 | 3/3 → 3/3 |
| Contacts | Off | 3/3 → 3/3 | 3/3 → 3/3 | 0/3 → 0/3 |
| Contacts | On | 3/3 → 3/3 | 3/3 → 3/3 | 0/3 → 3/3 |
| Starred | Off | 3/3 → 3/3 | 0/3 → 0/3 | 0/3 → 0/3 |
| Starred | On | 3/3 → 3/3 | 0/3 → 3/3 | 0/3 → 3/3 |

A `0/3` ringtone result means no ringtone playback was observed in three bounded
windows. In the platform lane, Core admitted and exposed RINGING for all 108
reports, including 27 whose CATEGORY_CALL notifications were ineligible and
silent. Actual notification ranking and the public caller-filter query agreed
in all 108 reports. Core admission alone therefore does not prove ringing.

In the production native lane, Core admission was observed for all 108 reports.
The SDK returned 81 `reported` outcomes and 27 `filtered` outcomes with reason
`doNotDisturb`; the corresponding playback results were 81 true and 27 false.
Every report produced exactly one native outcome hook, and duplicate delivery
retained the original outcome. The 27 filtered notifications disappeared before
listener ranking could be captured; 24 also escaped the RINGING-state poll.
Those fields remain **null**, not false or invented successful observations.
Every captured native ranking agreed with the public filter. All calls,
notifications, engine entries and owned contact rows were removed.

## Method and limits

Two separate `ubuntu-latest`/KVM jobs ran API35/target35, x86_64 `google_apis`,
`pixel_6` emulators. One lane directly used Core-Telecom plus the library's
CallStyle notification; the other used production native intake and typed
outcomes. The fixture reported synthetic incoming invitations to real Core-Telecom.
Cloud FCM delivery, PSTN calling, media sessions and the JavaScript bridge were
outside this study.

Each lane/cell/sample used a fresh E.164 identity with a distinct significant
phone suffix. `PhoneLookup` verified actual contact membership and STARRED
status. The declared `Caller.handleType = "phone"` value supplied the same exact
`tel:` address to Core, notification Person and `matchesCallFilter`. Serialized
`tel:%2B…` decodes to that same `+…` value. `hostContactID` was an opaque host key;
it was not used as an Android Contacts row ID. Marking Person important did not
make the caller a starred contact.

Before A, a calls-disabled/repeats-enabled probe returned false, proving the
identity was not already in repeat history. After A ended, that probe returned
true. The target policy was restored and read back before B. This proves the
second call's repeat match without reusing another lane's history. The study
requested a 2,000ms post-cleanup delay:

| Lane | Cleanup → B, min / median / max ms | A start → B start, min / median / max ms |
| --- | --- | --- |
| Platform | 2150.607 / 2322.733 / 2713.433 | 4215.856 / 4399.323 / 6163.295 |
| Native | 2144.699 / 2347.074 / 2972.780 | 4241.322 / 4467.296 / 5333.230 |

This tests a second call inside the observed repeat window; it does not measure
that window's expiration. Process restart, reinstall or switching DND off is
not a repeat-history reset. No failed case was retried as a replacement sample.
The earlier `bfaf6a4` run failed JUnit discovery because its method was not void;
none of its records contributes to these results.

The runner drove `adb shell cmd notification set_dnd priority|off`; a test-only
APK targeting34 set sender/repeat policy through public APIs, while the tested
app remained target35. Effective interruption filter and consolidated policy
were read back before each attempt. The helper is not packaged in the product.
The notification channel had configured sound and no DND bypass. Allowed cases
required nonzero ring volume. Android AudioManager playback configurations were
observed for at least 1,200ms, including asynchronous contact-affinity updates.
These three observations per cell do not establish behavior on other Android
versions, OEM devices, locked phones or physical audio hardware.

## How the host learns the policy

`NotificationManager.currentInterruptionFilter` exposes the global state:
ALL(1), PRIORITY(2), NONE(3), ALARMS(4), or UNKNOWN(0).
`consolidatedNotificationPolicy` exposes the effective sender/category settings
on API30+. A priority mode may still allow this caller; it is not equivalent to
"all calls filtered." On API33+, query `matchesCallFilter(callerUri)` from a
worker thread for the caller-specific decision. It requires existing contacts
read permission, notification-listener access or system access. See the
[Android API reference](https://developer.android.com/reference/android/app/NotificationManager#matchesCallFilter(android.net.Uri)).

The library requests neither contacts permission nor listener access. If caller
matching is unavailable, priority-mode intake leaves presentation to Android;
`reported` is then not evidence of sound. The native
[`NativeIncomingOutcomes` hook](../../contracts/android-incoming-policy.md)
reports typed outcomes without waiting for JS, so the host can enqueue an
authorized backend decline or AI-screening disposition. The host must dispatch
that work off the main callback. This study did not test an AI backend or its
network delivery. The bridge also receives `incomingConversationOutcome` when
alive. Total-DND/alarms-only controls remain covered separately by the existing
[policy journeys](../../contracts/android-incoming-policy.md#emulator-evidence).

## iPhone comparison and host-controlled breakthrough

| Allowance | Android Core-Telecom, API35 emulator | iPhone13/iOS27, LCK |
| --- | --- | --- |
| Contact/person allowed | Matching contacts rang under Contacts/Anyone; starred contacts also rang under Starred (3/3 per cell in each lane). | Caller-only People allowance did not admit the call; filtered with no call UI (F13–F14). |
| Repeat callers enabled | The second genuine call from an otherwise excluded caller rang (3/3 per cell in each lane). | The second call, 69.4 seconds later, remained filtered with no call UI (C02). |

The pinned iPhone pass observed call UI when the **app** was allowed in Focus
(F11–F12, F15–F16). Its person and repeat allowances did not provide breakthrough
in that setup. Android has 216 reports and three samples per cell in each lane;
the iPhone source has one sample per measured row, no acoustic/haptic
qualification and no CallKit run. These protocols, providers and sample counts
differ; neither result establishes universal platform behavior. See the
[source results and limitations](https://github.com/relayphone/ios-voip-sdk/blob/41b5de1787a59fd16a9493b5d4366c98768bd25a/docs/research/focus/results.md).

The [merged host-controlled breakthrough RFC](https://github.com/relayphone/ios-voip-sdk/blob/9fa1fa73164dc36a608b05e495ff05c2c0885888/docs/research/focus/app-breakthrough.md)
is a conditional design, not an implemented automatic override. Its user-chosen
VIP/repeat/AI routing maps to Android as host/server routing for genuine calls,
combined with the device's actual sender/repeat policy and native outcome hook.
The host may help the user configure legitimate priority contacts; a host VIP
label alone cannot star an OS contact or force Android to ring. Do not manufacture
calls, duplicate pushes or retries to prime repeat history. A duplicate SDK event
retains its earlier outcome; it is not a second genuine call.

Android's global interruption filter and caller-specific permission are separate
facts. Likewise, the RFC's proposed app-relative Focus state is not a global
Focus-off signal. Keep unknown/unauthorized/stale state explicit and route or
screen according to host authorization and actual device policy.
