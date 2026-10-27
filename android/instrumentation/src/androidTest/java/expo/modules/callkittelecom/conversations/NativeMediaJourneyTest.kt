package expo.modules.callkittelecom.conversations

import android.Manifest
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeMediaJourneyTest {
    @get:Rule val permissions = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val settings by lazy { JSONObject(InstrumentationRegistry.getInstrumentation().context.assets.open("media-ci.json").bufferedReader().readText()) }
    private val client = OkHttpClient.Builder().callTimeout(8, java.util.concurrent.TimeUnit.SECONDS).build()
    private suspend fun fixture(path: String): JSONObject = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(settings.getString("baseUrl") + path)
            .header("Authorization", "Bearer " + settings.getString("authentication")).build()
        client.newCall(request).execute().use {
            check(it.isSuccessful) { "Fixture request failed" }
            JSONObject(it.body!!.string())
        }
    }

    @Test fun realNativeLiveKitAudioAndCleanup() = journey("livekit", listOf("push", "fetcher"))
    @Test fun realNativeWebRTCAudioAndCleanup() = journey("webrtc", listOf("fetcher", "livekitPush"))

    private fun journey(provider: String, sources: List<String>) = runBlocking {
        withContext(Dispatchers.Main) {
            val engine = ConversationEngine.get(context)
            val previousPolicy = engine.policy
            val previousTimeout = engine.incomingTimeoutMillis
            val previousEmitter = engine.emit
            val signals = CopyOnWriteArrayList<NativeMediaSignal>()
            val observer = NativeMediaObserver { signals += it }
            NativeMediaSignals.observers += observer
            val results = mutableListOf<Map<String, Any?>>()
            val cleanupResults = mutableListOf<String>()
            var declineObservation: JSONObject? = null
            val owned = mutableSetOf<String>()
            val output = File(context.filesDir, if (provider == "livekit") "native-media-results.json" else "native-webrtc-results.json")
            output.delete()
            var warmRequests = 0
            var joinHandlerCalls = 0
            NativeMediaAuthenticationProviders.providers["fixture"] = {
                NativeMediaAuthentication(settings.getString("authentication"), "fixture-device")
            }
            engine.policy = JSONObject(mapOf("type" to "native", "media" to provider, "credentials" to "fromPush",
                "fallback" to mapOf("type" to "http", "baseUrl" to settings.getString("baseUrl"), "authentication" to "fixture")))
            engine.actions.resetBridge()
            engine.emit = { name, _ -> if (name == "handlerAction") joinHandlerCalls++ }
            val entry = NativeCallPush(engine) { warmRequests++ }
            fun events(serverId: String) = signals.filter { it.serverCallId == serverId }
            suspend fun stage(serverId: String, kind: NativeMediaStage, count: Int = 1): NativeMediaSignal = withTimeout(10_000) {
                while (events(serverId).count { it.stage == kind } < count) delay(10)
                events(serverId).filter { it.stage == kind }[count - 1]
            }
            fun present(id: String) = (engine.snapshot["conversations"] as List<*>).any { (it as Map<*, *>)["id"] == id }
            suspend fun absent(id: String) = withTimeout(7000) { while (present(id)) delay(10) }
            suspend fun incoming(serverId: String, pushedMedia: Boolean): String {
                val payload = fixture("/fixture/invitation/$serverId?media=${if (pushedMedia && provider == "livekit") 1 else 0}")
                if (pushedMedia && provider == "webrtc") payload.put("media", JSONObject(mapOf("provider" to "livekit",
                    "url" to "wss://unused.invalid", "room" to "unused", "identity" to "fixture", "token" to "unused-fixture",
                    "expiresAt" to System.currentTimeMillis()/1000 + 120)))
                val result = entry.handle(mapOf("messageType" to "incomingCall", "incomingCall" to payload.toString()), true)!!
                assertEquals("reported", result["status"])
                return (result["id"] as String).also { owned += it }
            }
            suspend fun noClient(serverId: String): JSONObject = withTimeout(7000) {
                var value = fixture("/fixture/observations/$serverId")
                while (value.getInt("clients") != 0) { delay(30); value = fixture("/fixture/observations/$serverId") }
                value
            }
            val stamp = android.os.SystemClock.elapsedRealtime().toString()
            val prefix = if (provider == "webrtc") "rtc-" else ""
            try { withTimeout(120_000) {
                for ((index, source) in sources.withIndex()) {
                    // Isolate each measurement from the previous test/call's audio teardown.
                    withTimeout(5000) { while (context.getSystemService(android.media.AudioManager::class.java).mode != android.media.AudioManager.MODE_NORMAL) delay(25) }
                    val serverId = "${prefix}timing-$source-$stamp"
                    val id = incoming(serverId, source != "fetcher")
                    val prejoined = stage(serverId, NativeMediaStage.PREJOINED)
                    delay(150)
                    assertFalse(events(serverId).any { it.stage in setOf(NativeMediaStage.CAPTURE_STARTED,
                        NativeMediaStage.PLAYBACK_STARTED, NativeMediaStage.REMOTE_SUBSCRIBED, NativeMediaStage.FIRST_AUDIO) })
                    engine.join(id)
                    val first = stage(serverId, NativeMediaStage.FIRST_AUDIO)
                    val reported = stage(serverId, NativeMediaStage.REPORTED)
                    val pushed = stage(serverId, NativeMediaStage.PUSH_RECEIVED)
                    assertEquals(1L, pushed.measurements["highPriority"])
                    val prewarm = stage(serverId, NativeMediaStage.PREWARM_STARTED)
                    val jsWarm = stage(serverId, NativeMediaStage.JS_WARM_STARTED)
                    val requested = stage(serverId, NativeMediaStage.ANSWER_REQUESTED)
                    val accepted = stage(serverId, NativeMediaStage.ANSWER_ACCEPTED)
                    val authorized = stage(serverId, NativeMediaStage.AUDIO_AUTHORIZED)
                    val capture = stage(serverId, NativeMediaStage.CAPTURE_STARTED)
                    val playback = stage(serverId, NativeMediaStage.PLAYBACK_STARTED)
                    val subscribed = stage(serverId, NativeMediaStage.REMOTE_SUBSCRIBED)
                    assertTrue(reported.elapsedRealtimeNanos <= prewarm.elapsedRealtimeNanos)
                    assertTrue(reported.elapsedRealtimeNanos <= jsWarm.elapsedRealtimeNanos)
                    assertTrue(accepted.elapsedRealtimeNanos <= authorized.elapsedRealtimeNanos)
                    assertTrue(authorized.elapsedRealtimeNanos <= first.elapsedRealtimeNanos)
                    for (started in listOf(capture, playback, subscribed))
                        assertTrue(authorized.elapsedRealtimeNanos <= started.elapsedRealtimeNanos)
                    val elapsedMs = (first.elapsedRealtimeNanos - requested.elapsedRealtimeNanos) / 1_000_000.0
                    assertTrue("Native answer-to-PCM exceeded bound", elapsedMs in 0.0..3000.0)
                    results += mapOf("credentials" to source, "pushNanos" to pushed.elapsedRealtimeNanos,
                        "highPriority" to true, "reportedNanos" to reported.elapsedRealtimeNanos, "prejoinedNanos" to prejoined.elapsedRealtimeNanos,
                        "prewarmNanos" to prewarm.elapsedRealtimeNanos, "jsWarmNanos" to jsWarm.elapsedRealtimeNanos,
                        "answerRequestedNanos" to requested.elapsedRealtimeNanos, "answerAcceptedNanos" to accepted.elapsedRealtimeNanos,
                        "audioAuthorizedNanos" to authorized.elapsedRealtimeNanos, "firstAudioNanos" to first.elapsedRealtimeNanos,
                        "captureStartedNanos" to capture.elapsedRealtimeNanos, "playbackStartedNanos" to playback.elapsedRealtimeNanos,
                        "remoteSubscribedNanos" to subscribed.elapsedRealtimeNanos,
                        "answerToFirstAudioMs" to elapsedMs, "pcm" to first.measurements, "preanswerCapture" to false)
                    assertEquals(0, joinHandlerCalls)
                    if (index == 0) {
                        // Non-Join signaling is accepted by a native test consumer; Join above had no JS handler.
                        engine.emit = { name, body -> if (name == "handlerAction") {
                            if (body["kind"] == "join") joinHandlerCalls++
                            engine.actions.settle(body["actionID"] as String, body["generation"] as Int, true, engine.actions.epoch)
                        } }
                        engine.actions.attach(true, 1, engine.actions.epoch)
                        val holdRequested = android.os.SystemClock.elapsedRealtimeNanos()
                        engine.hold(id, true)
                        assertTrue(stage(serverId, NativeMediaStage.CAPTURE_STOPPED).elapsedRealtimeNanos >= holdRequested)
                        assertTrue(stage(serverId, NativeMediaStage.PLAYBACK_STOPPED).elapsedRealtimeNanos >= holdRequested)
                        val starts = events(serverId).count { it.stage == NativeMediaStage.CAPTURE_STARTED }
                        val playbackStarts = events(serverId).count { it.stage == NativeMediaStage.PLAYBACK_STARTED }
                        delay(100)
                        assertEquals(starts, events(serverId).count { it.stage == NativeMediaStage.CAPTURE_STARTED })
                        assertEquals(playbackStarts, events(serverId).count { it.stage == NativeMediaStage.PLAYBACK_STARTED })
                        engine.hold(id, false)
                        val resumed = stage(serverId, NativeMediaStage.AUDIO_AUTHORIZED, 2)
                        for (kind in listOf(NativeMediaStage.CAPTURE_STARTED, NativeMediaStage.PLAYBACK_STARTED, NativeMediaStage.FIRST_AUDIO))
                            assertTrue(stage(serverId, kind, 2).elapsedRealtimeNanos >= resumed.elapsedRealtimeNanos)
                        cleanupResults += "hold-capture-stopped-resumed"
                    }
                    engine.report(id, mapOf("type" to "ended", "reason" to "remoteEnded"))
                    absent(id); stage(serverId, NativeMediaStage.CLOSED); noClient(serverId); owned -= id
                }
                val declinedServer = "${prefix}decline-$stamp"
                val declined = incoming(declinedServer, provider == "livekit")
                stage(declinedServer, NativeMediaStage.PREJOINED)
                engine.end(declined)
                absent(declined); stage(declinedServer, NativeMediaStage.CLOSED)
                val declineState = noClient(declinedServer)
                declineObservation = declineState
                if (provider == "webrtc") assertTrue(declineState.getJSONArray("states").toString().contains("declined"))
                assertFalse(events(declinedServer).any { it.stage == NativeMediaStage.CAPTURE_STARTED })
                owned -= declined; cleanupResults += "declined"

                engine.incomingTimeoutMillis = 3000
                val timedServer = "${prefix}timeout-$stamp"
                val timed = incoming(timedServer, provider == "livekit")
                stage(timedServer, NativeMediaStage.PREJOINED)
                absent(timed); stage(timedServer, NativeMediaStage.CLOSED); noClient(timedServer)
                assertFalse(events(timedServer).any { it.stage == NativeMediaStage.CAPTURE_STARTED })
                owned -= timed; cleanupResults += "unanswered"
                engine.incomingTimeoutMillis = previousTimeout

                for (code in listOf(404, 410)) {
                    val goneServer = "gone$code-$stamp"
                    val gone = incoming(goneServer, false)
                    withTimeout(5000) { while (fixture("/fixture/observations/$goneServer").getInt("fetches") < 1) delay(20) }
                    engine.join(gone)
                    absent(gone); stage(goneServer, NativeMediaStage.CLOSED)
                    assertEquals(2, fixture("/fixture/observations/$goneServer").getInt("fetches"))
                    assertFalse(events(goneServer).any { it.stage == NativeMediaStage.CAPTURE_STARTED })
                    owned -= gone; cleanupResults += code.toString()
                }
                assertEquals(sources.size + 4, warmRequests)
                assertEquals(0, joinHandlerCalls)
                output.writeText(JSONObject(mapOf("passed" to true, "provider" to provider, "metric" to "native-answer-request-to-first-remote-pcm",
                    "pcmThreshold" to 128, "budgetMs" to 3000, "targetMs" to 300, "samples" to results,
                    "cleanup" to cleanupResults, "declineObservation" to declineObservation,
                    "jsJoinHandlerCalls" to joinHandlerCalls, "jsWarmRequests" to warmRequests)).toString())
            } } catch (error: Throwable) {
                output.writeText(JSONObject(mapOf("passed" to false, "stage" to "native-$provider-journey", "samples" to results,
                    "cleanup" to cleanupResults, "declineObservation" to declineObservation, "observedStages" to signals.map { mapOf("stage" to it.stage.value,
                        "nanos" to it.elapsedRealtimeNanos, "measurements" to it.measurements) })).toString())
                throw error
            } finally {
                for (id in owned) runCatching { engine.report(id, mapOf("type" to "ended", "reason" to "failed")) }
                NativeMediaSignals.observers -= observer
                NativeMediaAuthenticationProviders.providers.remove("fixture")
                engine.policy = previousPolicy; engine.incomingTimeoutMillis = previousTimeout
                engine.actions.resetBridge(); engine.emit = previousEmitter
            }
        }
    }
}
