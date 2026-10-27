# Android answered-elsewhere dismissal

All six native samples met their separate budgets: SDK/notification/Core completion stayed below 1,000 ms; visible caller-card absence was observed in 1,834.40–1,935.22 ms against a 3,000 ms UI budget. These are controlled emulator observations, not production percentiles or a universal dismissal floor.

- Source: [b1af396992b0869a3c8a85d4b7c9f00934a91f3e](https://github.com/ziahamza/expo-callkit-telecom/commit/b1af396992b0869a3c8a85d4b7c9f00934a91f3e); [native Android run 40464699410](https://github.com/ziahamza/expo-callkit-telecom/actions/runs/40464699410).
- Merged as `c37fb13803da48ba36577100069357db4223b82c`, with a full source tree identical to the qualified head.
- Environment: API35, x86_64 google_apis, pixel_6 emulator; ubuntu-latest with KVM.
- Evidence: [checked JSON](./android-answered-elsewhere-results.json); [API/intake and native mapping contract](./android-answered-elsewhere.md). Native JUnit reports 57 tests, 4 expected skips and 0 failures/errors. All nine workflow jobs and automated review on that head completed green before merge.

## Six observed samples

Milliseconds from the native end signal, rounded to two decimals. The JSON retains six-decimal derived values, source/artifact hashes and independent visibility proof.

| Path/sample | Return* | SDK terminal | Event | Empty hook | Notification gone | UI gone | Core gone |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Host 1 | 4.00 | 60.46 | 60.60 | 61.96 | 78.22 | 1872.46 | 446.29 |
| Host 2 | 0.67 | 35.84 | 36.09 | 66.54 | 85.18 | 1902.87 | 599.28 |
| Host 3 | 0.51 | 36.59 | 36.66 | 44.77 | 58.17 | 1834.40 | 443.68 |
| Native push 1 | 1.52 | 10.53 | 10.58 | 11.42 | 64.14 | 1935.22 | 533.95 |
| Native push 2 | 1.67 | 17.62 | 17.69 | 20.16 | 54.39 | 1934.18 | 510.68 |
| Native push 3 | 1.18 | 31.51 | 56.38 | 63.33 | 66.37 | 1895.37 | 515.67 |

*Return is true direct-host acceptance or `NativeCallPush.handle` return. The wire handler supplies no acceptance boolean; neither timestamp means dismissal completed. Phase intervals overlap and must not be summed. Each path has n=3; host UI min/median/max is 1,834.40 / 1,872.46 / 1,902.87 ms, native-push UI is 1,895.37 / 1,934.18 / 1,935.22 ms.

Every sample first had an actual Telecom RINGING call and visible full caller label, Answer and Decline controls. Every end produced terminal SDK state before exactly one correlated `answeredElsewhere` event, then an empty hook, with zero handler-action deliveries. Telecom's actual historical SET_DISCONNECTED evidence recorded **REMOTE code 3 and reason `answeredElsewhere`** in all six; API35 transactional Core did not use code 11.

## Native completion and SystemUI lifetime

SDK terminal observations were 10.53–60.46 ms; notification-manager absence 54.39–85.18 ms; verified Core removal 443.68–599.28 ms, including Telecom-query overhead. All five native completion observations in each sample passed the 1,000 ms engineering bound.

The test read runtime SystemUI `heads_up_notification_minimum_time` as **2,000 ms** through public package/resources APIs. UI completion used that observed value plus 1,000 ms observer allowance: **3,000 ms**. The configured heads-up minimum and sticky-entry lifetime are consistent with card retention after native cleanup. They do not establish that the whole observed delay has one cause, or that every device must take two seconds from the end signal. [AOSP15 default](https://android.googlesource.com/platform/frameworks/base/+/android-15.0.0_r1/packages/SystemUI/res/values/config.xml#158), [sticky-entry/removal sources](./android-answered-elsewhere.md#emulator-acceptance).

The shade was collapsed before each invitation, and no artificial wait consumed the minimum. Reported→signal offsets were 1,073.60–1,471.48 ms; final visible confirmation→signal was under 0.02 ms. The JSON retains incoming/report/visibility offsets rather than assuming equal card ages.

## Visibility method and limits

UI absence required two consecutive complete depth-first accessibility scans in the same verified SystemUI shade window, with a 20 ms requested gap. Missing/different roots and incomplete trees were inconclusive. Each sample had 63–69 complete scans; its maximum scan took 118.53–181.02 ms. The final two complete, same-window caller-absent scans are retained separately; UI-gone timestamps the second confirmation.

Notification content marks a root namespace that text-search recursion can skip. A negative root-level text query therefore cannot prove dismissal; the oracle enumerates accessibility children. [Notification namespace](https://android.googlesource.com/platform/frameworks/base/+/android-15.0.0_r1/packages/SystemUI/src/com/android/systemui/statusbar/notification/row/NotificationContentView.java#220), [ViewGroup exclusion](https://android.googlesource.com/platform/frameworks/base/+/android-15.0.0_r1/core/java/android/view/ViewGroup.java#1444), [accessibility delegation](https://android.googlesource.com/platform/frameworks/base/+/android-15.0.0_r1/core/java/android/view/AccessibilityInteractionController.java#564).

These are observer upper bounds including callbacks, Binder traversal, scheduling and Telecom dump/query work, not pixel-render or acoustic measurements. Three calls used the direct native host API; three used the production FCM data envelope through `NativeCallPush.handle`, **not cloud FCM**. Explicit handler policy with `prewarm:false` isolates termination without JS readiness or media-room departure. Physical-device, DND, network-delivery and broader reliability claims remain separate.

## Provenance

The generator ran the exact-head checker before deriving this JSON. All six derived rows, the raw artifact hash and checker/journey/workflow hashes were independently compared with their local sources. Commit/run references are collector-supplied metadata; hashes bind exact bytes. No raw node text, Telecom dumps, screenshots, credentials, tokens or audio are included.

Raw artifact SHA256: `c46f684c0869db88fed8b5d5040dcbe67553cdae141336f2e46700ceee738f56`. Published JSON SHA256: `7561ef50a901f1935c20024c3822708ca31e91f62a79f29f7e90b5847ac0eef7`.
