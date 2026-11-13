# Android v2 quick-start

Install the v2 prerelease from the GitHub release asset in your `package.json`:

```json
"expo-callkit-telecom": "https://github.com/relayphone/expo-callkit-telecom/releases/download/v2.0.0-alpha.4/expo-callkit-telecom-2.0.0-alpha.4.tgz"
```

See [release verification](./releases.md). Prerelease versions can change APIs.
Android uses Core-Telecom and native FCM intake. For iOS, see
[iOS](./migrating-v1-to-v2.md#ios). For API replacements and breaking changes,
start with [Migrating from v1](./migrating-v1-to-v2.md).

## Android only

Apps that keep their own iOS calling can disable v2's iOS config plugin:

```json
{
  "expo": {
    "plugins": [
      ["expo-callkit-telecom", { "ios": false, "androidNativeMedia": "livekit" }]
    ]
  }
}
```

`ios` defaults to `true`. With `false`, v2 never loads the iOS SDK plugin,
adds no iOS mods and skips the retired Apple integration check. Android options
work as usual. This lets apps generate their iOS project at prebuild while
keeping their existing iOS calling setup.

The pinned iOS SDK still needs its next release to load on Expo 58. Until then,
use `ios: false` on Expo 58 or keep Expo 57 when using the iOS integration.

## Run the example's signaling journey

Use an Android native development build, with API 26 or later, microphone and
notification permission. Prepare the fork with the SDK release asset using [package evaluation](./sdk-package-evaluation.md),
including SDK installation into `example/client`, before building the example.
Then, from this fork's checkout:

```sh
bun run build
cd example/client
bun run android
```

Tap **Run incoming and outgoing journey**. It exercises incoming → answer →
connected → hold → resume → end, then outgoing → connected → end through the
production Expo bridge. The screen also supports manual reports, mute and route
selection. `useAudioRoutes()` shows the current route; request an available route
with `selectAudioRoute(route.id)` and observe the actual result rather than
assuming the request changed audio output. The deep link
`conversations-example://journey` runs the same journey. See
[the example journey instructions](https://github.com/relayphone/expo-callkit-telecom/blob/v2/example/client/V2-JOURNEYS.md).

The checked-in example chooses `androidNativeMedia: "none"` and registers its
start handler at module scope, before React mounts. Incoming answer runs natively;
outgoing placement accepts locally, and its buttons explicitly report connected. **It has
no media room and produces no remote call audio.** The release Expo CI journey
qualifies the bridge/UI flow; native media and decoded-audio timing are qualified
separately by the instrumentation fixture below.

For real FCM delivery, configure the example's Firebase Android application and
`example/client/google-services.json`, rebuild, and use the `fcm` token from
`usePushToken()` (`{ type: "fcm", value }`). Follow the
[example sender setup](https://github.com/relayphone/expo-callkit-telecom/blob/v2/example/server/README.md) for the service account and
device token. Its Android sender uses high-priority data messages:

```sh
cd example/server
bun send-test-push.ts --android --phoneNumber +14155551234 --display Alice
```

The sender creates the typed caller and invitation; it does not mint or attach
media credentials. Running it against the unmodified signaling example therefore
does not test native pre-warm or audio. Cloud FCM delivery requires your Firebase
setup and is not part of the emulator latency measurements.

## Calls on phones that suspend apps

Battery restrictions can delay incoming calls while your app is in the background.
Read the current Android policy from native app code:

```kotlin
val restriction = ConversationPlatform.backgroundRestriction(context)
```

`UNRESTRICTED` means the app ignores battery optimizations and has no explicit
background restriction; `OPTIMIZED` means normal optimizations apply;
`RESTRICTED` means Android explicitly restricts background work. On Android 26–27,
only the battery optimization status is available. This reports Android's
[battery exemption](https://developer.android.com/reference/android/os/PowerManager#isIgnoringBatteryOptimizations(java.lang.String))
and [background restriction](https://developer.android.com/reference/android/app/ActivityManager#isBackgroundRestricted())
status; it cannot detect every manufacturer's suspension policy or guarantee push delivery.

Explain how battery restrictions affect calls, then let the person choose whether
to open settings from your app:

```kotlin
context.startActivity(ConversationPlatform.unrestrictedSettingsIntent(context))
```

The intent opens this app's system details screen. From there, open its battery
controls to allow unrestricted use; wording varies by phone. Your app owns the
explanation and the decision to launch settings. Check the status again when the
person returns. v2 only reads status and creates the intent.

## Your own FCM service

Set `androidFcmService: "app"` in the `expo-callkit-telecom` plugin options and
rebuild. The plugin registers no library messaging service and leaves
expo-notifications' service alone. Keep one service handling `MESSAGING_EVENT`
in your app's manifest; resolve any other installed messaging services there.
expo-notifications is optional in this mode. The default `"library"` retains
the library service and its forwarding of other messages to expo-notifications,
which must be installed for that mode.

Configure the decoder and admission once from your `Application.onCreate`.
This example adapts an app-defined data envelope; the default decoder accepts
the `messageType` envelope shown below.

```kotlin
import android.app.Application
import android.util.Log
import expo.modules.callkittelecom.conversations.*

class CallingApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CallPushIntake.configure(
            decoder = CallPushDecoder { data ->
                if (data["type"] != "call.invite") return@CallPushDecoder null
                val id = data["id"] ?: return@CallPushDecoder null
                val event = data["event"] ?: return@CallPushDecoder null
                DecodedCallPush.Incoming(IncomingCallPush(
                    serverCallId = id, eventId = event,
                    caller = mapOf("handleType" to "generic", "value" to id,
                        "displayName" to (data["name"] ?: "Caller")),
                    metadata = mapOf("accountId" to data["accountId"]),
                ))
            },
            admission = CallPushAdmission { push ->
                val account = getSharedPreferences("account", MODE_PRIVATE).getString("id", null)
                if (account != null && push.metadata["accountId"] == account) CallPushDecision.Admit
                else CallPushDecision.Refuse("accountMismatch")
            },
        )
        NativeMediaSignals.observers += NativeMediaObserver { signal ->
            if (signal.stage == NativeMediaStage.PUSH_REFUSED)
                Log.i("Calls", "Push refused: ${signal.reason}")
        }
    }
}
```

Forward your service's messages and token changes to the intake:

```kotlin
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import expo.modules.callkittelecom.conversations.CallPushIntake

class AppMessagingService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        if (CallPushIntake.handle(applicationContext, message)) return
        super.onMessageReceived(message) // Handle your other push types here.
    }
    override fun onNewToken(token: String) {
        CallPushIntake.newToken(applicationContext, token)
        super.onNewToken(token)
    }
}
```

`handle` returns true when decoding recognizes a call, including a refused call;
false leaves the push to your other handlers. Admission suspends on the engine's
main scope before any Telecom report; move blocking work to an appropriate
dispatcher. The default admits every incoming push. Refusal starts neither
Telecom reporting nor media/JS warmup. Refusal reasons are app-defined telemetry:
use short reason codes without credentials or personal data. End notifications
use `DecodedCallPush.Ended(EndedCallPush(serverCallId, eventId, reason))` and
bypass incoming admission. Both service paths retain the engine's dedupe and
report-before-warmup behavior.

## Server actions

Register a suspending native handler from `Application.onCreate` when your server
must agree to an answer or app control before it takes effect. For example,
`server.claim` below is your app's suspending request and returns a push-shaped
media grant, or null when another device already claimed the call:

```kotlin
ConversationActions.configure { action ->
    when (action) {
        is ConversationAction.Answer -> {
            val media = server.claim(action.serverCallId ?: action.conversationId)
            if (media == null) ConversationActionResult.Refuse("claimLost", "answeredElsewhere")
            else ConversationActionResult.Proceed(media)
        }
        is ConversationAction.Decline, is ConversationAction.End -> {
            server.end(action.serverCallId ?: action.conversationId)
            ConversationActionResult.Proceed()
        }
        is ConversationAction.Hold, is ConversationAction.Mute -> ConversationActionResult.Proceed()
    }
}
```

The handler runs once per shared native receipt, including app actions and their
system callback echoes. It runs off main with a 3.5 s ceiling, reserving 1.5 s of
Core-Telecom's 5 s callback budget for settlement. Throws become `handlerFailed`;
timeouts become `handlerTimedOut`. Use short refusal codes without personal data
or credentials. Answer refusal fails the callback and ends with `endReason`.
App hold/mute refusal uses the existing typed `handler_failed` or
`deadline_exceeded` rejection; disabled hold remains `unsupported`.

System hold/mute are observations of a state the OS already applied. The handler
is notified, but a refusal is only signalled and the SDK keeps that state.
Decline/end always disconnect regardless of the result: their handler wait is
capped at 2.5 s, leaving 0.5 s to request disconnection within 3 s. Late results
are discarded after signalling the timeout, and are never retried.

`Proceed.media` is used for an answer only when no prewarmed native media session
exists; an existing prewarmed session ignores it. The native handler does not
receive outgoing start, which keeps its JavaScript placement handler. JavaScript
still receives the usual snapshots and `actionPerformed` events. Calling
`ConversationActions.configure(null)` restores the existing native behavior.

With `androidNativeMedia: "livekit"`, an active native media session accepts the
shared `playTones(conversationId, digits)` API or
`ConversationControls.playTones(context, conversationId, digits)`. Digits are
`0`–`9`, `*`, `#`, and `A`–`D` (lowercase is accepted). Each successful request
publishes LiveKit SIP DTMF and emits an app-origin `actionPerformed` tones event.
Tones do not ask the native server-action handler. Held or unprepared media
cannot publish tones; app-managed media advertises `capabilities.tones = false`
and rejects tones with `unsupported`.

An active call on Core-Telecom's earpiece route holds a proximity screen-off lock.
Switching to speaker, Bluetooth or wired audio, holding, or ending the call
releases it immediately. Devices without a proximity sensor need no lock.

Observe `ANSWER_REQUESTED`, `HANDLER_STARTED`, `HANDLER_RESPONDED`,
`ROOM_CONNECTED` and `CONNECTED` through `NativeMediaSignals` to time the answer.
`HANDLER_RESPONDED.reason` is `proceed` or the refusal code. A room prewarmed
before the answer can emit `ROOM_CONNECTED` earlier. Signals contain monotonic
timings, counts and reason codes, never names, phone numbers or media grants.

## Your own ringing screen

Configure notification content from `Application.onCreate`. The library keeps the
CallStyle notification, foreground service, and native Answer/Decline controls.
The customizer receives the public conversation snapshot entry (including members,
status and `payload.metadata`), with media credentials removed. It runs on state
changes and when you call `ConversationPresentation.refresh(context)`. It runs on main:
use already-loaded content and do no I/O. Null fields retain the library defaults;
`ConversationPresentation.configure(null)` restores them for subsequent notifications.

```kotlin
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import expo.modules.callkittelecom.conversations.*

// In Application.onCreate, after super.onCreate():
getSystemService(NotificationManager::class.java).createNotificationChannel(
    NotificationChannel("app-ringing", "Incoming calls", NotificationManager.IMPORTANCE_HIGH)
        .apply { enableVibration(true) },
)
ConversationPresentation.configure { conversation, phase ->
    val id = conversation["id"] as String
    val member = (conversation["members"] as? List<*>)?.firstOrNull() as? Map<*, *>
    val screen = PendingIntent.getActivity(this, 0,
        Intent(this, RingingActivity::class.java)
            .setData(Uri.Builder().scheme("app-call").authority(packageName).appendPath(id).build())
            .putExtra("conversationId", id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    CallNotificationContent(
        title = member?.get("displayName") as? String,
        fullScreenIntent = if (phase == CallPhase.RINGING) screen else null,
        contentIntent = screen,
        channelId = if (phase == CallPhase.RINGING) "app-ringing" else null,
    )
}
```

When your app resolves a name or avatar, or changes an extra action, call `refresh`
from any thread. It refreshes existing live-call cards with the same IDs and
foreground ownership. Ringing updates do not re-alert or launch another full-screen
activity; an existing insistent ring keeps playing on its posted channel. Ended calls and retained
“Ending call…” cards are unchanged, and missing cards are not created.

```kotlin
val callerNames = java.util.concurrent.ConcurrentHashMap<String, String>()
ConversationPresentation.configure { conversation, _ ->
    CallNotificationContent(title = callerNames[conversation["id"] as String])
}
// In your app's existing coroutine scope, resolve the name outside the customizer:
scope.launch {
    callerNames[conversationId] = contacts.resolveName(conversationId)
    ConversationPresentation.refresh(applicationContext)
}
```

Register the app's activity in its manifest with `android:exported="false"`.
Its controls use the same native action handler as the notification, with origin
`app`; server agreement and refusal semantics still apply. Calls are dispatched
on main and may be made from any thread. Observe conversation snapshots to update
or close the activity after an answer, refusal or remote end.

```kotlin
import android.app.Activity
import android.os.Bundle
import android.os.Build
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import expo.modules.callkittelecom.conversations.ConversationControls

class RingingActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra("conversationId") ?: run { finish(); return }
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        val controls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun button(label: String, action: () -> Unit) {
            controls.addView(Button(this).apply { text = label; setOnClickListener { action() } })
        }
        button("Answer") { ConversationControls.answer(this, id) }
        button("Decline") { ConversationControls.decline(this, id) }
        button("End") { ConversationControls.end(this, id) }
        setContentView(controls)
    }
}
```

Supply an already-created channel to control sound and vibration; the library
preserves its settings. Ringing notifications set `FLAG_INSISTENT` for repeating
alerts on either the app's channel or the default channel. Android stops repetition
when the notification is cancelled or its notification window is opened; answering
replaces it with an ongoing notification without that flag or a full-screen intent.
Full-screen launch remains subject to Android's permissions and system policy.
`avatar` accepts an Android `Icon`; `extraActions` accepts `NotificationCompat.Action`
values for app actions such as Assistant. [CallStyle](https://developer.android.com/reference/androidx/core/app/NotificationCompat.CallStyle)
allows one custom action while ringing and up to two while ongoing, alongside its
required controls. Extra actions beyond the platform's visible limit may be omitted.

One stable foreground card shows the primary call: the active call, otherwise the
most recently held call, otherwise the ringing call. Other held calls have no
separate cards. The primary card keeps its End control and summarises all held calls
with text such as "1 call on hold" or "2 calls on hold". Return a non-null
`heldSummary` in `CallNotificationContent` to override that wording; it is shown
only when calls are held. The primary card's customizer receives the primary
call's public snapshot entry. Unhold calls from your app or the system UI.

A waiting incoming call has its own incoming CallStyle card alongside the primary
card. Answering removes that waiting card and presents the answered call on the
same foreground notification ID. Ending the primary call promotes a remaining
held call on that ID. The library never transfers foreground ownership between
notification IDs.

## Ends your app learns about

Local app or system-UI ends use `local`; remote ends use `remoteEnded`. An unexpected
Core-Telecom removal uses `failed` with `failure.kind = "frameworkReset"`, while
native media connection loss uses `failed` with `failure.kind = "media"`.
Core's first mute observation is a baseline and preserves any app-requested mute.
Failed disconnects retry once after a short delay, then end locally; a call Core
no longer tracks is already removed. Overlapping ends share the teardown.
An app end report received while a local end is disconnecting keeps the report's
reason and suppresses the pending local action receipt without another Core request.

When your native server integration learns that an incoming call ended, report
the same `serverCallId` used by its invitation:

```kotlin
ConversationEnds.report(context, serverCallId, "answeredElsewhere")
```

Call this from any thread. Reasons are `remoteEnded`, `answeredElsewhere`,
`declinedElsewhere`, `unanswered` and `failed`; other values become `failed`.
Reports use the same native path as a `callEnded` push, including termination
during pending Telecom admission. Repeated reports and earlier or later pushes
share the call's server identity, remove its ringing surfaces, and publish its
terminal reason without ending it twice. Reports for unknown calls follow the
same behavior as unknown end pushes.

## Media for calls your app starts

With `androidNativeMedia: "livekit"`, pass credentials obtained by your native
server integration to the existing conversation ID:

```kotlin
ConversationMedia.provide(context, conversationId, media)
```

`media` has the same LiveKit shape as an incoming push: `provider`, `url`, `room`,
`identity`, `token` and `expiresAt` (Unix seconds). Outgoing calls join natively,
including credentials supplied while placement is still pending. Capture and
audible output wait for Core-Telecom activation; the existing connected path
publishes `connected` and emits `CONNECTED` after native media activation.
Ringing incoming calls without a prewarmed session keep the credentials privately
until answer. An answer handler's `Proceed.media`, when supplied, takes precedence.

LiveKit signaling URLs require `wss://` in release apps. Debuggable apps also
accept `ws://` for local development; the app's network security policy still applies.

Unknown, ended, disabled-provider or already-owned sessions are ignored with
`MEDIA_IGNORED` and a reason code. Credentials stay out of snapshots and signals.
`MEDIA_PROVIDED` records accepted provision. Calls may emit `RECONNECTING` and
`RECONNECTED` while keeping their current status. If an incoming call is still
ringing, losing its optional pre-join emits `PREWARM_LOST` and keeps it ringing.
Answer creates a fresh session using the push credentials, `Proceed.media`, or
credentials supplied through `ConversationMedia.provide`. For outgoing, answered
and held calls, a final room disconnection or 30 seconds without recovery fails
the call. Ending the call
cancels recovery observation and its deadline.

## Configure a host for native answer and audio

Incoming pushes can set `IncomingCallPush.expiresAtMillis` to their absolute UTC
expiry in Unix epoch milliseconds. The default JSON decoder reads the same
optional `expiresAtMillis` field. It can shorten the configured incoming timeout
but never extend it. To allow longer rings, raise `incomingCallTimeout`. An
already expired invitation ends immediately as `unanswered`; time spent in admission or Telecom
reporting does not extend its expiry. Omit the field to keep the configured
`incomingCallTimeout` (45 seconds by default). Answering or ending the call
cancels its ringing deadline; duplicate invitations do not extend it.

Set `androidSupportsHold: false` in the plugin options and rebuild to disable
hold/resume. The default is `true`. Disabled calls advertise `capabilities.hold:
false`, omit Core-Telecom's hold flag, and reject `setHeld` with the existing
typed `unsupported` error. Normal answer and activation remain available.

Select the Android native LiveKit engine independently of the SDK's Apple audio
adapter, then rebuild the native application:

```ts
const callingPlugin: [string, { androidNativeMedia: "livekit" }] = [
  "expo-callkit-telecom",
  { androidNativeMedia: "livekit" },
];
```

The default `androidNativeMedia: "none"` leaves media to the application. With
`"livekit"`, production native media uses fresh LiveKit credentials from an
incoming push or `ConversationMedia.provide`. There is no built-in HTTP credentials endpoint, authentication
provider registry or configurable HTTP fallback. Supply a short-lived token and
expiry from your backend; never place a bearer in plugin configuration.

The independent media-server fixture registers a named fetcher inside the
instrumentation APK. It retains HTTP404/410, cancellation, hold and cleanup
controls; that fixture lookup is not packaged in the library. Raw WebRTC signaling
also remains an instrumentation research path, outside the public configuration.
See the [native media contract](./contracts/android-native-media.md) and
[composed package contract](./canonical-contract.md). Your server must treat
prejoined and answered as distinct states.

### Send the incoming payload

Build a typed invitation in the backend and JSON-encode it into FCM's string data
field. This illustrates the shape; the backend supplies a real short-lived token
and expiry and sends the message with its FCM credentials:

```ts
import type { IncomingCallPayload } from "expo-callkit-telecom/types";

const incoming: IncomingCallPayload = {
  eventId: "invitation-event-123",
  serverCallId: "server-call-456",
  caller: {
    handleType: "phone",
    value: "+14155551234",
    displayName: "Alice",
    hostContactID: "host-contact-42",
  },
  hasVideo: false,
  media: {
    provider: "livekit",
    url: "wss://media.example.com",
    room: "call-room-456",
    identity: "owner-device",
    token: "<short-lived-room-token>",
    expiresAt: Math.floor(Date.now() / 1000) + 60,
  },
};

const message = {
  message: {
    token: "<FCM-device-token>",
    android: { priority: "HIGH" },
    data: {
      messageType: "incomingCall",
      incomingCall: JSON.stringify(incoming),
    },
  },
};
```

Use a new `eventId` per genuine event and retain `serverCallId` across its call
lifecycle. Phone `value` normalizes to E.164 and becomes the same `tel:` identity
for Core-Telecom and notification Person matching. `hostContactID` is an opaque
host key, not an Android Contacts row ID; it does not make a contact starred.
Media credentials stay outside `metadata`, public snapshots and logs. The
Android LiveKit token needs the room-scoped join/publish/subscribe grants from
the [native media contract](./contracts/android-native-media.md); do not assume
the pending iOS prejoin credential policy is identical.

### What happens after the push

1. Native FCM intake reports through Core-Telecom `addCall` first, then resolves
   the incoming policy. A filtered/capacity/failed result does not start media.
2. For an accepted invitation, native media and the existing JS host receive
   warm requests independently. LiveKit stays joined and subscribed.
   Remote decoding and muted playback warm before Answer.
   Physical microphone capture and audible output remain off.
3. The user answers in system UI, or the host calls `joinConversation(id)`.
   Native answer does not consult the handler. It waits for preparation and the
   same live Core-active call plus communication audio mode, then unmutes the
   existing media path. Answer does not renegotiate or resubscribe.
4. Connected state follows native activation. On hold, physical capture stops
   and output is muted while receive decoding stays warm. Resume waits for
   native audio authority again. Decline, incoming timeout, filtering and remote
   end close the owned media generation and Core call; canceled work cannot
   restore it. Missing or expired push credentials fail preparation, with at most
   one answer-time preparation retry. HTTP404/410 controls apply to the
   instrumentation-only named fetcher.

Core-Telecom owns focus/mode; the media adapter does not acquire its own audio
authority. The native starting-route preference is cached without delaying the
initial report. Route metadata can be absent and is not an extra activation
acknowledgment. A returned command promise is not proof of first decoded audio.

To stop ringing when another endpoint answers, send the same server identity
through `receiveCallEnded({ eventId, serverCallId, reason: "answeredElsewhere" })`
or the [native FCM end envelope](./contracts/android-answered-elsewhere.md).
That intake runs natively without an action handler; observe state/events for completion rather
than treating its boolean acknowledgment as proof that visible UI is gone.

## Native audio readiness checks

The [native audio readiness contract](./contracts/android-latency.md) describes
prewarm, physical audio authority and the retained functional media controls.
The [v2 Android workflow](https://github.com/relayphone/expo-callkit-telecom/blob/v2/.github/workflows/v2-android.yml)
runs media, privacy, hold/resume, cleanup and DND checks on a CI emulator. It
exercises production native FCM intake with a synthetic envelope. The
[example workflow](https://github.com/relayphone/expo-callkit-telecom/blob/v2/.github/workflows/v2-example.yml)
separately runs the media-free Expo journey.

The historical latency benchmark and comparisons are preserved on
[archive/android-telecom-research](https://github.com/relayphone/expo-callkit-telecom/tree/archive/android-telecom-research).
They measured decoded PCM after completed prewarm, excluded cloud delivery,
process launch and acoustic output, and did not establish production percentiles.

## DND, contacts and repeat callers

Android applies its DND policy to call notifications. Historical contact, starred
and repeat-caller measurements and the linked iOS comparison are preserved in the
[archived typed-caller study](https://github.com/relayphone/expo-callkit-telecom/blob/archive/android-telecom-research/docs/research/focus/android-results.md).

On Android the host can read `NotificationManager.currentInterruptionFilter`
and the effective consolidated policy. On API 33+, caller-specific
`matchesCallFilter` requires existing contacts permission, notification listener
access or system access; the library requests neither contacts nor listener
access. When matching is unavailable, it leaves presentation to Android.
`reported` alone therefore never proves ringing.

The native `NativeIncomingOutcomes` hook reports typed outcomes without JS;
`incomingConversationOutcome` delivers the same envelope when JS is alive.
Filtered reasons are `doNotDisturb`, `blockList` and `other`. Dispatch authorized
backend decline/AI-screening work off the main callback; the hook does not itself
implement that backend. A policy change can also filter a prewarmed ringing call
and close its resources. See [policy/outcome details](./contracts/android-incoming-policy.md).
