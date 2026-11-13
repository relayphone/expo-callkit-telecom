package expo.modules.callkittelecom.conversations

import android.Manifest
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaHookFeasibilityTest {
    @get:Rule val permissions = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val settings by lazy { JSONObject(InstrumentationRegistry.getInstrumentation().context.assets
        .open("media-ci.json").bufferedReader().readText()) }
    private val client = OkHttpClient.Builder().callTimeout(8, java.util.concurrent.TimeUnit.SECONDS).build()

    private suspend fun fixture(path: String, reset: Boolean = false): JSONObject = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(settings.getString("baseUrl") + path)
            .header("Authorization", "Bearer " + settings.getString("authentication"))
        if (reset) request.post("{}".toRequestBody("application/json".toMediaType()))
        client.newCall(request.build()).execute().use {
            check(it.isSuccessful) { "Media fixture request failed" }
            JSONObject(it.body!!.string())
        }
    }

    @Test fun aDisabledOutputTapFailsInsteadOfReportingSilence() {
        val error = assertThrows(MediaHookUnavailable::class.java) { EmulatorMediaHooks(context, tapEnabled = false) }
        assertEquals(MediaHookFailure.OUTPUT_TAP_UNAVAILABLE, error.reason)
    }

    @Test fun realCapturePublicationMuteDecodedAndPostMuteOutputHooks() = runBlocking {
        withContext(Dispatchers.Main) {
            val engine = ConversationEngine.get(context)
            val previousMedia = engine.nativeMedia; val previousPolicy = engine.policy
            val previousEmitter = engine.emit; val previousAccept = engine.actions.acceptUnhandled
            val previousFactory = NativeMediaRegistry.factories["livekit"]
            val id = "hooks-${System.nanoTime()}"
            val hooks = EmulatorMediaHooks(context)
            val signals = CopyOnWriteArrayList<NativeMediaSignal>()
            val observer = NativeMediaObserver { if (it.serverCallId == id) signals += it }
            fun present() = (engine.snapshot["conversations"] as List<*>).any { (it as Map<*, *>)["id"] == id }
            suspend fun stage(kind: NativeMediaStage) = withTimeout(10_000) {
                while (signals.none { it.stage == kind }) delay(10)
            }
            suspend fun peerAudio() = withTimeout(5000) {
                var result: JSONObject
                do { delay(40); result = fixture("/fixture/markers/$id") }
                while (!result.getBoolean("running") || result.getLong("markers") < 2)
                result
            }
            engine.nativeMedia = AndroidNativeMedia.LIVEKIT
            engine.policy = JSONObject(mapOf("media" to "livekit", "credentials" to "fromPush", "prewarm" to true))
            engine.actions.resetBridge(); engine.actions.acceptUnhandled = true
            engine.emit = { name, _ -> assertNotEquals("handlerAction", name) }
            NativeMediaRegistry.factories["livekit"] = { LiveKitNativeSession(it, id, hooks) }
            NativeMediaSignals.observers += observer
            try {
                withTimeout(7000) { while (TelecomEvidence.calls().isNotEmpty()) delay(20) }
                val media = fixture("/fixture/invitation/$id?media=1").getJSONObject("media")
                val grant = media.keys().asSequence().associateWith { media.get(it) }
                assertEquals("reported", engine.incoming(IncomingCallPush(id, "event-$id",
                    mapOf("handleType" to "generic", "value" to "peer", "displayName" to "Peer"), media = grant).payload())["status"])
                stage(NativeMediaStage.PREJOINED)
                assertTrue(hooks.directArrayVerified)
                hooks.beginPhase()
                withTimeout(3000) { while (hooks.decoded().tones < 5 || hooks.output().blocks < 10) delay(10) }
                assertEquals(0L, hooks.output().nonzero)
                assertTrue(hooks.injectedFrames.get() > 0)
                assertTrue(hooks.recordinglessCallbacks.get() > 0)

                engine.join(id); stage(NativeMediaStage.CONNECTED)
                fixture("/fixture/markers/$id", reset = true)
                val heard = peerAudio()
                hooks.beginPhase()
                withTimeout(3000) { while (hooks.decoded().tones < 5 || hooks.output().tones < 5) delay(10) }

                // Change the real publication gate, never the source. Its callback must keep injecting.
                hooks.publicationMuted(true)
                withTimeout(3000) { while (!fixture("/fixture/markers/$id").optBoolean("muted")) delay(30) }
                delay(300) // Drain packets already in flight before the server's mute acknowledgment.
                fixture("/fixture/markers/$id", reset = true)
                val injectedBefore = hooks.injectedFrames.get()
                delay(1200)
                val muted = fixture("/fixture/markers/$id")
                assertTrue(muted.getBoolean("running")); assertTrue(muted.getBoolean("muted"))
                assertEquals(0L, muted.getLong("markers"))
                assertTrue("The source must continue while publication is muted", hooks.injectedFrames.get() - injectedBefore > 5000)
                hooks.publicationMuted(false)
                fixture("/fixture/markers/$id", reset = true)
                val resumed = peerAudio()

                engine.hold(id, true)
                hooks.beginPhase()
                withTimeout(3000) { while (hooks.decoded().tones < 5 || hooks.output().blocks < 10) delay(10) }
                delay(500)
                val heldOutput = hooks.output()
                assertTrue(heldOutput.frames > 0); assertEquals(0L, heldOutput.nonzero)
                assertTrue(hooks.decoded().tones > 0)
                engine.hold(id, false)
                hooks.beginPhase()
                withTimeout(3000) { while (hooks.output().tones < 5) delay(10) }
                assertFalse(signals.any { it.stage == NativeMediaStage.OUTPUT_OBSERVATION_UNAVAILABLE })
                InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                    putString("mediaHooks", "directArray=true,uplinkMarkers=${heard.getLong("markers")}," +
                        "mutedMarkers=${muted.getLong("markers")},resumedMarkers=${resumed.getLong("markers")}," +
                        "heldOutputBlocks=${heldOutput.blocks},heldNonzero=${heldOutput.nonzero}")
                })
                engine.report(id, mapOf("type" to "ended", "reason" to "remoteEnded"))
                withTimeout(7000) { while (present() || TelecomEvidence.calls().isNotEmpty() ||
                    fixture("/fixture/observations/$id").getInt("clients") != 0) delay(30) }
                stage(NativeMediaStage.CLOSED)
            } finally {
                if (present()) runCatching { engine.report(id, mapOf("type" to "ended", "reason" to "remoteEnded")) }
                withTimeout(7000) { while (present() || TelecomEvidence.calls().isNotEmpty()) delay(20) }
                NativeMediaSignals.observers -= observer
                if (previousFactory == null) NativeMediaRegistry.factories.remove("livekit")
                else NativeMediaRegistry.factories["livekit"] = previousFactory
                engine.nativeMedia = previousMedia; engine.policy = previousPolicy
                engine.actions.resetBridge(); engine.actions.acceptUnhandled = previousAccept; engine.emit = previousEmitter
            }
        }
    }
}
