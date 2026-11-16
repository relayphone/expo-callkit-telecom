package expo.modules.callkittelecom.conversations

import android.Manifest
import android.app.ActivityManager
import android.app.NotificationManager
import android.app.Person
import android.media.AudioManager
import android.os.ParcelUuid
import android.telecom.DisconnectCause
import androidx.core.telecom.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import java.util.UUID
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConversationVideoJourneyTest {
    @get:Rule val permissions = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Suppress("DEPRECATION")
    private fun service() = context.getSystemService(ActivityManager::class.java).getRunningServices(Int.MAX_VALUE)
        .firstOrNull { it.service.className == ConversationService::class.java.name }

    private class Core : CoreCallAdmission {
        val types = MutableSharedFlow<Int>(replay = 1, extraBufferCapacity = 32)
        val receiver = CallEndpointCompat("Receiver", CallEndpointCompat.TYPE_EARPIECE, ParcelUuid(UUID.randomUUID()))
        val speaker = CallEndpointCompat("Speaker", CallEndpointCompat.TYPE_SPEAKER, ParcelUuid(UUID.randomUUID()))
        val headset = CallEndpointCompat("Wired", CallEndpointCompat.TYPE_WIRED_HEADSET, ParcelUuid(UUID.randomUUID()))
        val endpoint = MutableStateFlow(receiver)
        val available = MutableStateFlow(listOf(receiver, speaker))
        val requests = mutableListOf<Int>()
        val routeRequests = mutableListOf<CallEndpointCompat>()
        var delaySpeaker = false
        var rejectRoute = false
        var requestGate: CompletableDeferred<Unit>? = null
        val removed = CompletableDeferred<Unit>()
        override suspend fun add(attributes: CallAttributesCompat, callbacks: CoreCallCallbacks, admitted: CallControlScope.() -> Unit) = coroutineScope {
            types.emit(attributes.callType)
            admitted(object : CallControlScope {
                override val coroutineContext: CoroutineContext = this@coroutineScope.coroutineContext
                override fun getCallId() = ParcelUuid(UUID.randomUUID())
                override val currentCallEndpoint = endpoint
                override val availableEndpoints = available
                override val isMuted = flowOf(false)
                override fun callTypeFlow(): Flow<Int> = types
                override suspend fun requestCallType(callType: Int): CallControlResult {
                    requests += callType
                    types.emit(callType)
                    // Model Core's conditional fallback; the production integration adds none.
                    if (!delaySpeaker && callType == CallAttributesCompat.CALL_TYPE_VIDEO_CALL && endpoint.value == receiver &&
                        available.value.none { it.type == CallEndpointCompat.TYPE_WIRED_HEADSET }) endpoint.value = speaker
                    requestGate?.await()
                    return CallControlResult.Success()
                }
                override suspend fun requestEndpointChange(endpoint: CallEndpointCompat): CallControlResult {
                    routeRequests += endpoint
                    if (rejectRoute) return CallControlResult.Error(CallException.ERROR_UNKNOWN)
                    this@Core.endpoint.value = endpoint
                    return CallControlResult.Success()
                }
                override suspend fun answer(callType: Int) = CallControlResult.Success()
                override suspend fun setActive() = CallControlResult.Success()
                override suspend fun setInactive() = CallControlResult.Success()
                override suspend fun disconnect(cause: DisconnectCause): CallControlResult {
                    removed.complete(Unit); return CallControlResult.Success()
                }
            })
            removed.await()
        }
    }

    private suspend fun fixture(incoming: Boolean = false, observeCallStyle: Boolean = false,
        body: suspend (ConversationEngine, String, Core, MutableList<Map<*, *>>) -> Unit) = withContext(Dispatchers.Main) {
        // Android's package-wide enqueue quota survives test cases. Start real-card assertions
        // after a quiet interval; their four-second delivery bound remains unchanged.
        if (observeCallStyle) delay(2 * RINGING_ALERT_INTERVAL_MILLIS)
        val engine = ConversationEngine.get(context)
        val previousAdmission = engine.coreAdmission
        val previousMedia = engine.nativeMedia
        val previousEmit = engine.emit
        val previousAccept = engine.actions.acceptUnhandled
        val id = "video-${System.nanoTime()}"
        val core = Core()
        val events = mutableListOf<Map<*, *>>()
        try {
            engine.coreAdmission = core; engine.nativeMedia = AndroidNativeMedia.NONE
            engine.actions.resetBridge(); engine.actions.acceptUnhandled = true
            engine.emit = { name, value -> if (name == "conversationEvent" && value["type"] == "videoChanged") events += value["payload"] as Map<*, *> }
            if (incoming) assertEquals("reported", engine.incoming(IncomingCallPush(id, "$id-event",
                mapOf("handleType" to "generic", "value" to "caller", "displayName" to id), hasVideo = true).payload())["status"])
            else {
                engine.start(mapOf("id" to id, "to" to mapOf("id" to "peer", "displayName" to id)))
                engine.report(id, mapOf("type" to "connected"))
            }
            yield()
            withTimeout(3000) { while (core.types.subscriptionCount.value == 0) delay(10) }
            withTimeout(5000) { while (service()?.foreground != true) delay(10) }
            body(engine, id, core, events)
        } finally {
            try {
                runCatching { engine.report(id, mapOf("type" to "ended", "reason" to "remoteEnded")) }
                withTimeout(5000) {
                    while ((engine.snapshot["conversations"] as List<*>).any { (it as Map<*, *>)["id"] == id } ||
                        service() != null || context.getSystemService(NotificationManager::class.java).activeNotifications.any {
                            it.notification.extras.getParcelable(android.app.Notification.EXTRA_CALL_PERSON, Person::class.java)?.name == id
                        }) delay(10)
                }
            } finally {
                engine.coreAdmission = previousAdmission; engine.nativeMedia = previousMedia
                engine.emit = previousEmit; engine.actions.acceptUnhandled = previousAccept
            }
        }
    }
    private fun state(engine: ConversationEngine, id: String) = (engine.snapshot["conversations"] as List<*>)
        .filterIsInstance<Map<*, *>>().first { it["id"] == id }
    private suspend fun notificationVideo(id: String, enabled: Boolean) = awaitPresentationObservation(
        "CallStyle video=$enabled", { context.getSystemService(NotificationManager::class.java).activeNotifications.filter {
            it.notification.extras.getParcelable(android.app.Notification.EXTRA_CALL_PERSON, Person::class.java)?.name == id } },
        { cards -> cards.any { it.notification.extras.getBoolean("android.callIsVideo", false) == enabled &&
            it.notification.extras.containsKey("android.callIsVideo") } },
        { cards -> cards.map { "id=${it.id}, flags=${it.notification.flags}, hasVideoField=${
            it.notification.extras.containsKey("android.callIsVideo")}, video=${
            it.notification.extras.getBoolean("android.callIsVideo", false)}" }.toString() }, timeoutMillis = 4000)

    @Test fun appServerSystemChangesUpdateSnapshotsEventsAndCallStyleWithoutAudioDeactivation() = runBlocking {
        fixture(observeCallStyle = true) { engine, id, core, events ->
            val audio = context.getSystemService(AudioManager::class.java)
            val mode = audio.mode
            engine.setVideo(id, false)
            assertTrue(events.isEmpty())
            engine.setVideo(id, true)
            assertEquals(true, state(engine, id)["isVideo"])
            notificationVideo(id, true)
            withTimeout(3000) { while (core.endpoint.value != core.speaker) delay(10) }
            yield()
            engine.report(id, mapOf("type" to "updated", "video" to false))
            assertEquals(false, state(engine, id)["isVideo"])
            notificationVideo(id, false)
            assertEquals(listOf(core.receiver), core.routeRequests)
            core.types.emit(CallAttributesCompat.CALL_TYPE_VIDEO_CALL); yield()
            core.types.emit(CallAttributesCompat.CALL_TYPE_AUDIO_CALL); yield()
            assertEquals(listOf("app", "server", "system", "system"), events.map { it["source"] })
            assertEquals(listOf(true, false, true, false), events.map { it["isVideo"] })
            assertTrue(events.all { it["conversationId"] == id })
            assertEquals(2, core.requests.size)
            assertEquals(mode, audio.mode)
        }
    }

    @Test fun laterRouteChangesAndExplicitSelectionPreventDowngradeRestoration() = runBlocking {
        fixture { engine, id, core, _ ->
            engine.setVideo(id, true); yield()
            engine.selectRoute(core.speaker.identifier.toString())
            engine.setVideo(id, false)
            assertEquals(listOf(core.speaker), core.routeRequests)
            core.endpoint.value = core.receiver; yield()
            core.available.value = listOf(core.receiver, core.speaker, core.headset); yield()
            engine.setVideo(id, true)
            assertEquals(core.receiver, core.endpoint.value)
            core.endpoint.value = core.headset; yield()
            engine.setVideo(id, false)
            assertEquals(core.headset, core.endpoint.value)
            assertEquals(listOf(core.speaker), core.routeRequests)
        }
    }

    @Test fun incomingVideoCallStyleChangesInPlaceAndEndedAndUnknownCommandsReject() = runBlocking {
        fixture(incoming = true, observeCallStyle = true) { engine, id, _, events ->
            notificationVideo(id, true)
            engine.setVideo(id, false)
            notificationVideo(id, false)
            assertEquals("app", events.single()["source"])
            assertEquals("unknown_conversation", runCatching { engine.setVideo("missing-$id", true) }.exceptionOrNull()?.message)
            engine.report(id, mapOf("type" to "ended", "reason" to "remoteEnded"))
            assertEquals("invalid_arguments", runCatching { engine.setVideo(id, true) }.exceptionOrNull()?.message)
        }
    }

    @Test fun delayedUpgradeRouteAfterDowngradeRestoresTheReceiver() = runBlocking {
        fixture { engine, id, core, _ ->
            core.delaySpeaker = true
            engine.setVideo(id, true)
            engine.setVideo(id, false)
            assertTrue(core.routeRequests.isEmpty())
            core.endpoint.value = core.speaker
            withTimeout(3000) { while (core.routeRequests.isEmpty()) delay(10) }
            assertEquals(listOf(core.receiver), core.routeRequests)
            assertEquals(core.receiver, core.endpoint.value)
            assertEquals(false, state(engine, id)["isVideo"])
        }
    }

    @Test fun systemRestoreRejectionDoesNotEndTheCallOrCrash() = runBlocking {
        fixture { engine, id, core, _ ->
            engine.setVideo(id, true); yield()
            core.rejectRoute = true
            core.types.emit(CallAttributesCompat.CALL_TYPE_AUDIO_CALL)
            withTimeout(3000) { while (core.routeRequests.isEmpty()) delay(10) }
            yield()
            assertEquals(false, state(engine, id)["isVideo"])
            assertEquals("connected", state(engine, id)["status"])
            assertEquals(core.speaker, core.endpoint.value)
        }
    }

    @Test fun endWhileSystemRestoreWaitsForAnAppRequestDoesNotCrash() = runBlocking {
        fixture { engine, id, core, _ -> coroutineScope {
            val gate = CompletableDeferred<Unit>()
            core.requestGate = gate
            val app = async { runCatching { engine.setVideo(id, true) }.exceptionOrNull() }
            withTimeout(3000) { while (state(engine, id)["isVideo"] != true) delay(10) }
            core.types.emit(CallAttributesCompat.CALL_TYPE_AUDIO_CALL); yield()
            engine.report(id, mapOf("type" to "ended", "reason" to "remoteEnded"))
            gate.complete(Unit)
            assertEquals("invalid_arguments", app.await()?.message)
            yield()
            assertTrue(core.routeRequests.isEmpty())
        } }
    }
}
