# Changelog

All notable changes to `expo-callkit-telecom` are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## Unreleased

## 2.0.0-alpha.6 (2026-11-13)

### Changed

* **android:** answered/outgoing/held LiveKit calls disconnected with `ROOM_DELETED`
  or `PARTICIPANT_REMOVED` now end as `remoteEnded` without a media failure.
  Ringing pre-join loss continues ringing; other final transport failures retain
  their media failure. Native room disconnect telemetry includes the reason name
  without call or room identifiers.

### Features

* **android:** opt in to `NativeMediaOptions(disableNetworkMonitor = true)` through
  `ConversationMedia.configure` for calls behind a VPN without a default route.
  The default WebRTC network monitor remains enabled.

## 2.0.0-alpha.5 (2026-11-13)

### Features

* **android:** localize retained ending cards with `CallNotificationContent.endingTitle`
  in the new `CallPhase.ENDING`. The default remains “Ending call…”.

### Fixes

* **android:** enter foreground synchronously for every foreground-service start,
  including a start delivered after its call ended. Empty rosters stop the service
  without starting it again. A ringing start inside the alert window gets a trailing
  alert. Measure that window after Android publishes the notification, avoiding
  early ringing updates caused by asynchronous foreground publication.
* **android:** compare displayed ringing content before re-posting. Unrelated
  snapshots and unchanged app refreshes no longer restart ringing for cards with
  avatars or extra actions. Queued changes reverted to the posted content are
  dropped. Avatar replacements are applied by an explicit refresh.

## 2.0.0-alpha.4 (2026-11-13)

### Features

* **android:** refresh app-provided call notification content with
  `ConversationPresentation.refresh(context)`. Update resolved names, avatars and
  extra actions without changing notification IDs or foreground ownership.
  Refreshing a ringing call restarts its ring once; alerting updates are spaced at
  least 1.1 seconds apart and close requests merge into one latest-content update.
  Pending updates are dropped when ringing ends. Ongoing and retained “Ending
  call…” cards remain quiet.

## 2.0.0-alpha.3 (2026-11-11)

### Fixes

* **android:** remove an answered call's system entry even when Android refuses
  the first disconnects. End media and audio promptly, then retry platform removal
  for up to 60 seconds while retaining the foreground service and an “Ending
  call…” notification. Its End action retries immediately. If Android continues
  refusing removal, emit `PLATFORM_REMOVAL_FAILED` before local cleanup.

## 2.0.0-alpha.2 (2026-11-11)

### Fixes

* Load the config plugin on Expo 57 and 58 through `expo/config-plugins`.
* Add `ios: false` for Android-only apps that keep their own iOS calling.
  It skips the SDK plugin, iOS mods and retired Apple integration check.

iOS on Expo 58 needs the next iOS SDK release. Use `ios: false` or Expo 57
until that release is available. The default still configures both platforms.

## 2.0.0-alpha.1 (2026-11-11)

The first v2 prerelease shares the Conversations API, with Android on Core-Telecom.
Install it from its GitHub release asset URL.

### Fixes

* **android:** keep incoming calls ringing when their optional pre-joined room is
  lost. Emit `PREWARM_LOST`, release the old session, and join fresh on answer.

### Documentation

* **ios:** document the observed iOS 27 behavior of delivering a push-started preview's update
  token after the Live Activities question, and how servers handle a preview that ends before
  its token arrives.

### Diagnostics

* **ios:** log Focus store reads at launch, foreground activation and changes, and the state sent
  to JavaScript, under the `VoIPFocus` subsystem. Foreground diagnostics compare persisted and
  cached state. Logs include only state tags, revisions and conversion failures, without settings.

### Features

* **android:** add `ConversationPlatform.backgroundRestriction` and
  `unrestrictedSettingsIntent` so apps can explain battery restrictions and let
  people open the app's system battery controls.

* **android:** publish DTMF through native LiveKit with shared `playTones` and
  `ConversationControls.playTones`. Advertise tones only with native LiveKit selected.
  Use proximity screen-off while an active call is on the earpiece route.

* **android:** add `ConversationPresentation` for app-owned notification content,
  caller avatars, ringing screens, channels and extra actions. Add native
  `ConversationControls.answer`, `decline` and `end` through the app action path.

* **android:** add `ConversationEnds.report` for call ends learned by the app.
  Reports share push-end ownership, reason handling and pending-admission cleanup.

* **android:** accept native LiveKit credentials through `ConversationMedia.provide`
  for app-started calls and unprepared ringing calls. Preserve Core-owned audio,
  signal room recovery without changing conversation status, and bound reconnection to 30 seconds.

* **android:** add `ConversationActions` for native server agreement on answer and app controls,
  with bounded handler waits, local decline/end, and handler/connected timing signals.
  System hold/mute notify the handler while retaining the state already applied by the OS.

* **android:** allow incoming pushes to set `expiresAtMillis` for their ringing deadline,
  ending unanswered at expiry. Omitted expiry keeps the configured default timeout.
  Add `androidSupportsHold` to disable hold/resume in Core-Telecom and conversation capabilities.

* **android:** support app-owned FCM services with `CallPushIntake`, custom decoders and typed
  admission decisions. Refusal reasons reach native media observers before any Telecom report.
  Set `androidFcmService: "app"` to keep service ownership in the app; expo-notifications is optional.

* **ios:** move to ios-voip-sdk 0.2.1, pinned by the `v0.2.1` tag. VoIP pushes now keep the app running briefly, so call notifications and other push-triggered work finish before iOS suspends it.
* **ios:** cancel pending joins with `joinFailed` when joining ends before the call takes part.

### Changed

* **android:** allow `ws://` LiveKit signaling in debuggable apps; release apps
  continue to require `wss://`.

* **android:** ringing CallStyle notifications now set `FLAG_INSISTENT` so channel
  sound and vibration repeat until answered, ended or silenced by Android. Applies
  to both default and app-provided channels; ongoing notifications clear the flag.

* **android:** use one stable foreground notification for the primary call. Held
  calls no longer have their own cards; the primary card summarises them with
  default text or `CallNotificationContent.heldSummary`. Call-waiting incoming
  calls retain separate ringing cards until answered or ended.

* **ios:** call notifications post "missed" only when the caller hung up or nobody answered. They're removed when the call ended on the device or was answered or declined elsewhere; failures and other end reasons keep the notification. This changes the SDK's default behavior. Native integrations that configure their own SDK notifier can restore the previous default with `CallNotifier.Options(endedWithoutJoining: { _ in .missedCall })`.

### Bug Fixes

* **android:** preserve app mute across Core-Telecom's first mute snapshot, report
  unexpected Core removal as `failed/frameworkReset`, and retry failed disconnects
  once while sharing teardown across overlapping ends.

* **ios:** configure the audio session through the SDK when native call media is registered, so its calls get audio.
* **ios:** give the generated call preview widget the app's version and build number, so apps that use call previews install on devices.
* **ios:** include the system's refusal reason in refused action messages when available.
* **ios:** report VoIP pushes while the app isn't active, fixing iOS terminating apps and stopping VoIP push delivery, through the SDK update.
* **ios:** wait for the app to become active before joining from a call notification, for up to two seconds, through the SDK update.

## [0.5.0](https://github.com/mfairley/expo-callkit-telecom/compare/v0.4.1...v0.5.0) (2026-09-05)


### Features

* **ios:** make includesCallsInRecents configurable via a plugin prop ([#33](https://github.com/mfairley/expo-callkit-telecom/issues/33)) ([e666dbd](https://github.com/mfairley/expo-callkit-telecom/commit/e666dbdabd59008192910afeb97583a00717b62d))

## [0.4.1](https://github.com/mfairley/expo-callkit-telecom/compare/v0.4.0...v0.4.1) (2026-07-30)


### Documentation

* add peer deps to docs ([614dd62](https://github.com/mfairley/expo-callkit-telecom/commit/614dd6262a16f65e8a40de31de8b51635d7f18b0))

## [0.4.0](https://github.com/mfairley/expo-callkit-telecom/compare/v0.3.9...v0.4.0) (2026-06-16)


### Features

* **android:** broadcast call-ended when no JS observer can receive it ([a98b6ee](https://github.com/mfairley/expo-callkit-telecom/commit/a98b6ee20cd4ddd06cb0d520e0604328a33312a3))
* **android:** embed session in call events and broadcast undelivered events ([24dd9e0](https://github.com/mfairley/expo-callkit-telecom/commit/24dd9e00f16b746d2aa71591ec98b4d607bf0726))
* **example:** demonstrate the call-ended broadcast receiver ([a032691](https://github.com/mfairley/expo-callkit-telecom/commit/a032691b5d804f7e6d3b9ba18448bd3637a4353c))

## [0.3.9](https://github.com/mfairley/expo-callkit-telecom/compare/v0.3.8...v0.3.9) (2026-06-04)


### Bug Fixes

* make incomingCall the canonical VoIP push key, accept incoming_call ([637fb83](https://github.com/mfairley/expo-callkit-telecom/commit/637fb835ba7675d17f2ed31e1842e9655c89d428)), closes [#15](https://github.com/mfairley/expo-callkit-telecom/issues/15)

## [0.3.8](https://github.com/mfairley/expo-callkit-telecom/compare/v0.3.7...v0.3.8) (2026-05-21)


### Bug Fixes

* **docs:** further reduce image sizes ([23b21c9](https://github.com/mfairley/expo-callkit-telecom/commit/23b21c9b5d2be0458c19d80952f9daac6f1a5f2d))
* **docs:** reduce image sizes and fix accessibility issues ([4c7db67](https://github.com/mfairley/expo-callkit-telecom/commit/4c7db67b29120424d00bf21a4be4437d8178e804))
* **docs:** update readme image references ([eb67ba1](https://github.com/mfairley/expo-callkit-telecom/commit/eb67ba1aabad4451d11898784abe5f5bcfbe7046))

## [0.3.7](https://github.com/mfairley/expo-callkit-telecom/compare/v0.3.6...v0.3.7) (2026-05-19)


### Bug Fixes

* **docs:** update reportIncomingCall example in docs ([9505fa2](https://github.com/mfairley/expo-callkit-telecom/commit/9505fa266d7a2e55f5cf330a6452f95567da90b6))


### Documentation

* add cloudlfare web analytics script ([b558818](https://github.com/mfairley/expo-callkit-telecom/commit/b5588184f96fdab25baba05479f4d5f9cbecd07a))
* add personal website link to docs footer ([e4cee8e](https://github.com/mfairley/expo-callkit-telecom/commit/e4cee8e1d0438abffe7cb393b6b0a4d1c367e3d6))

## [0.3.6](https://github.com/mfairley/expo-callkit-telecom/compare/v0.3.5...v0.3.6) (2026-05-18)


### Bug Fixes

* change default docs path ([86aaeca](https://github.com/mfairley/expo-callkit-telecom/commit/86aaeca957ba1369151a00a98f5f1754a6daf0ae))
* site paths for cloudflare pages ([23b0c92](https://github.com/mfairley/expo-callkit-telecom/commit/23b0c92dc8e192f4a0aa6412db7958dc6c05a169))


### Documentation

* add cloudflare wrangler config ([d12f3fb](https://github.com/mfairley/expo-callkit-telecom/commit/d12f3fb297bbad871e1beb2f7dc2be5d7ed58095))
* redirect github pages to cloudflare ([806f52c](https://github.com/mfairley/expo-callkit-telecom/commit/806f52c17e3df4d49c160092d6edc4831a8f60ee))

## [0.3.5](https://github.com/mfairley/expo-callkit-telecom/compare/v0.3.4...v0.3.5) (2026-05-18)


### Documentation

* clean up keywords ([92560e1](https://github.com/mfairley/expo-callkit-telecom/commit/92560e1549e1367d5ac2619769ee41a2054df4f1))

## [0.3.4](https://github.com/mfairley/expo-callkit-telecom/compare/v0.3.3...v0.3.4) (2026-05-17)


### Documentation

* recommend wav audio format ([cdeed74](https://github.com/mfairley/expo-callkit-telecom/commit/cdeed74f38ee3072d6ab1382c8ec8ab019fa9403))

## [0.3.3](https://github.com/mfairley/expo-callkit-telecom/compare/v0.3.2...v0.3.3) (2026-05-17)


### Documentation

* add Cloudflare Web Analytics ([36906d7](https://github.com/mfairley/expo-callkit-telecom/commit/36906d752aeef82639e50343a6a4044dc3ebb422))
* tighten doc wording ([b841554](https://github.com/mfairley/expo-callkit-telecom/commit/b841554ddf7a8393d0818d3b5af9ed065628605e))

## [0.3.2](https://github.com/mfairley/expo-callkit-telecom/compare/v0.3.1...v0.3.2) (2026-05-17)


### Bug Fixes

* expose app.plugin in exports field so prebuild can resolve plugin ([559ad5a](https://github.com/mfairley/expo-callkit-telecom/commit/559ad5af6ea8d9ba91f912c6adee2dad92b2e296))


### Documentation

* fix layout of video on mobile ([4d1d85c](https://github.com/mfairley/expo-callkit-telecom/commit/4d1d85cf0f6169f388b9f84f45eca826c98a568b))

## [0.3.1](https://github.com/mfairley/expo-callkit-telecom/compare/v0.3.0...v0.3.1) (2026-05-17)


### Documentation

* improve wording of docs ([39c73e8](https://github.com/mfairley/expo-callkit-telecom/commit/39c73e82f6891039896a0368c5e76bbb5ca9ec35))

## [0.3.0](https://github.com/mfairley/expo-callkit-telecom/compare/v0.2.8...v0.3.0) (2026-05-17)


### Features

* **docs:** branded icon, demo gallery, social cards, SEO outlinks ([33f8bc3](https://github.com/mfairley/expo-callkit-telecom/commit/33f8bc3d6329fe04ca6f76eca452b481941fef64))


### Documentation

* **readme:** swap &lt;video&gt; for clickable poster &lt;img&gt; ([584dd1f](https://github.com/mfairley/expo-callkit-telecom/commit/584dd1f717766536e87c3b4cef3196412e606045))

## [0.2.8](https://github.com/mfairley/expo-callkit-telecom/compare/v0.2.7...v0.2.8) (2026-05-16)


### Documentation

* cross-link related functions and add per-page SEO descriptions ([c03b2d4](https://github.com/mfairley/expo-callkit-telecom/commit/c03b2d4420bd15f2fd40c1ad1f20112bedc9a122))

## [0.2.7] — 2026-05-16

### Added
- VitePress now emits a `sitemap.xml` on each docs build for better search-engine crawling.
- Prominent "📖 Full documentation" link near the top of the README, plus a `docs` badge in the badge row.
- `SECURITY.md` describing how to report vulnerabilities via GitHub Security Advisories.
- `.github/ISSUE_TEMPLATE/` with bug-report and feature-request templates, plus a `config.yml` that points casual readers at the docs and the callkeep comparison first.
- `exports` field in `package.json` alongside the existing `main` and `types` (modern dual-export shape; no behavior change for consumers).

### Changed
- `homepage` field in `package.json` now points at the docs site (`https://mfairley.github.io/expo-callkit-telecom/`) instead of the README anchor — affects the "Homepage" link on npm's package page.
- README `<h1>` expanded to "expo-callkit-telecom — native calling UI for Expo (CallKit + Jetpack Core-Telecom)" for clearer SEO and first-impression scannability.

## [0.2.6] — 2026-05-16

### Changed
- API reference sections now appear in a learn-the-module order (Sessions → Requests → Reporters → Fulfillers → Call Events → Audio → Audio Events → Capture → VoIP Push → Hooks → Permissions → Core) instead of alphabetical.
- Within each interface, type, and category, members are now listed in source order — so `CallParticipant` reads `id` → `displayName` → `avatarUrl` → `phoneNumber` → `email` as defined, rather than alphabetically.

### Removed
- The self-referential "each release updates this matrix…" sentence following the Verified-against table in `README.md`, `docs/index.md`, and `docs/vs-callkeep.md`. The table itself stays; the meta-commentary was unnecessary.

## [0.2.5] — 2026-05-16

### Changed
- API reference page now groups items into 12 themed sections (Sessions, Requests, Reporters, Fulfillers, Call Events, Audio, Audio Events, Capture, VoIP Push, Permissions, Hooks, Core). Added `@category` tags to all exports in `src/Calls.ts` and `src/Calls.types.ts`, including the previously-uncategorized session functions and every type alias.
- Every interface and type alias now carries a one-line summary, replacing the empty placeholders and the repeated "Base type for all native events with metadata" inherited descriptions on event interfaces.

## [0.2.4] — 2026-05-16

### Added
- Documentation site at https://mfairley.github.io/expo-callkit-telecom/ — VitePress with auto-generated typedoc API reference, deployed via a GitHub Actions workflow on push to `main`.
- "Verified against" matrix in the README and docs site, listing the iOS / Android / Expo SDK / React Native / New Architecture / media-transport versions exercised end-to-end on real devices in each release.
- `docs:dev` / `docs:build` / `docs:preview` / `docs:api` scripts.

### Changed
- Reframed the `react-native-callkeep` comparison around architectural choices (Jetpack `androidx.core:core-telecom`, Swift + Kotlin, Expo Modules API, `RTCAudioSession` manual-audio coordination, native VoIP push parsing) — framing tied to platform recommendations rather than to any other library's release cadence.
- `docs/` and `marketing/` excluded from the npm tarball.

## [0.2.3] — 2026-05-16

### Added
- `CHANGELOG.md`.
- `docs/vs-callkeep.md` — standalone migration / comparison guide.

### Changed
- Tightened the npm description and expanded keywords for discoverability.
- Trimmed the README callkeep comparison; full side-by-side now lives at [`docs/vs-callkeep.md`](docs/vs-callkeep.md).

## [0.2.2] — 2026-05-16

### Changed
- Moved README badges below the intro for consistency with sibling repo.

## [0.2.1] — 2026-05-16

### Fixed
- Dropped `.svg` suffix from the license badge URL.

### Changed
- Auto-create a GitHub release on publish; README polish.
- Applied `swift-format` and `ktfmt` across the native sources.

## [0.2.0] — 2026-05-15

### Added
- Initial public implementation:
  - CallKit (iOS) + Jetpack `androidx.core:core-telecom` (Android) with a single typed `CallSession` and parity API (`request` / `report` / `fulfill`).
  - Native VoIP push parsing: APNs VoIP (PushKit) on iOS, FCM data messages on Android — calls can be reported from a terminated state.
  - System ringtone for incoming calls and looped dialtone (with fade-in) for outgoing calls; both configurable via the config plugin.
  - Cross-platform audio session management with typed port types and live route-change events; integrates with WebRTC's `RTCAudioSession` for manual-audio stacks (LiveKit, plain WebRTC).
  - Mute / hold / video / DTMF, both `app → system` and `system → app`.
  - iOS call intents: Recents list and Siri ("call Jane").
  - Config plugin: entitlements, background modes, microphone permission, ringtone/dialtone bundling, FCM service registration.
- CI: trusted publishing on Node 24 / npm 11.

[0.2.7]: https://github.com/mfairley/expo-callkit-telecom/releases/tag/v0.2.7
[0.2.6]: https://github.com/mfairley/expo-callkit-telecom/releases/tag/v0.2.6
[0.2.5]: https://github.com/mfairley/expo-callkit-telecom/releases/tag/v0.2.5
[0.2.4]: https://github.com/mfairley/expo-callkit-telecom/releases/tag/v0.2.4
[0.2.3]: https://github.com/mfairley/expo-callkit-telecom/releases/tag/v0.2.3
[0.2.2]: https://github.com/mfairley/expo-callkit-telecom/releases/tag/v0.2.2
[0.2.1]: https://github.com/mfairley/expo-callkit-telecom/releases/tag/v0.2.1
[0.2.0]: https://github.com/mfairley/expo-callkit-telecom/releases/tag/v0.2.0
