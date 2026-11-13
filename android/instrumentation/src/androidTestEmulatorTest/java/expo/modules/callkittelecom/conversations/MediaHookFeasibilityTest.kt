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
    private enum class Step { PREJOIN, ANSWER, UPLINK, MUTED, RESUMED, HOLD, OUTPUT_RESUMED, END }
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

    private suspend fun heldSilence(hooks: EmulatorMediaHooks): EmulatorMediaHooks.Observation {
        var output = hooks.output()
        var decoded = hooks.decoded()
        repeat(5) {
            delay(100)
            val nextOutput = hooks.output()
            val nextDecoded = hooks.decoded()
            if (nextOutput.blocks <= output.blocks || nextOutput.frames <= output.frames)
                throw MediaHookUnavailable(MediaHookFailure.OUTPUT_TAP_UNAVAILABLE)
            assertEquals(0L, nextOutput.nonzero)
            assertTrue("Decoded tone must remain live throughout held silence",
                nextDecoded.frames > decoded.frames && nextDecoded.tones > decoded.tones)
            output = nextOutput; decoded = nextDecoded
        }
        return output
    }

    @Test fun realCapturePublicationMuteDecodedAndPostMuteOutputHooks() = runBlocking {
        withContext(Dispatchers.Main) {
            val engine = ConversationEngine.get(context)
            val previousMedia = engine.nativeMedia; val previousPolicy = engine.policy
            val previousEmitter = engine.emit; val previousAccept = engine.actions.acceptUnhandled
            val previousFactory = NativeMediaRegistry.factories["livekit"]
            val id = "hooks-${System.nanoTime()}"
            val hooks = EmulatorMediaHooks(context)
            var step = Step.PREJOIN
            var peer = JSONObject()
            fun mark(next: Step) {
                step = next
                InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("mediaHookStep", next.name) })
            }
            val signals = CopyOnWriteArrayList<NativeMediaSignal>()
            val observer = NativeMediaObserver { if (it.serverCallId == id) signals += it }
            fun present() = (engine.snapshot["conversations"] as List<*>).any { (it as Map<*, *>)["id"] == id }
            suspend fun stage(kind: NativeMediaStage) = withTimeout(10_000) {
                while (signals.none { it.stage == kind }) delay(10)
            }
            suspend fun peerAudio(within: Long = 5000) = withTimeout(within) {
                var result: JSONObject
                do { delay(40); result = fixture("/fixture/markers/$id"); peer = result }
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

                mark(Step.ANSWER)
                engine.join(id); stage(NativeMediaStage.CONNECTED)
                mark(Step.UPLINK)
                fixture("/fixture/markers/$id", reset = true)
                val heard = peerAudio()
                for (stimulus in listOf(EmulatorMediaHooks.Stimulus.ZERO, EmulatorMediaHooks.Stimulus.WRONG)) {
                    hooks.stimulus = stimulus
                    delay(300)
                    fixture("/fixture/markers/$id", reset = true)
                    val rejected = runCatching { peerAudio(3100) }.exceptionOrNull()
                    assertTrue("$stimulus must fail the far-end audio assertion: $peer", rejected is TimeoutCancellationException)
                    assertTrue(peer.getBoolean("running"))
                }
                hooks.stimulus = EmulatorMediaHooks.Stimulus.MARKER
                fixture("/fixture/markers/$id", reset = true)
                peerAudio(3100)
                hooks.beginPhase()
                withTimeout(3000) { while (hooks.decoded().tones < 5 || hooks.output().tones < 5) delay(10) }

                // Change the real publication gate, never the source. Its callback must keep injecting.
                mark(Step.MUTED)
                hooks.publicationMuted(true)
                withTimeout(3000) { while (!fixture("/fixture/markers/$id").optBoolean("muted")) delay(30) }
                delay(300) // Drain packets already in flight before the server's mute acknowledgment.
                fixture("/fixture/markers/$id", reset = true)
                val injectedBefore = hooks.injectedFrames.get()
                val wordsBefore = hooks.generatedWords.get()
                var lastInjected = injectedBefore
                repeat(31) {
                    delay(100)
                    val current = hooks.injectedFrames.get()
                    assertTrue("Capture must remain live throughout publication mute", current > lastInjected)
                    lastInjected = current
                }
                val muted = fixture("/fixture/markers/$id")
                assertTrue(muted.getBoolean("running")); assertTrue(muted.getBoolean("muted"))
                assertEquals(0L, muted.getLong("markers"))
                val injectedWhileMuted = hooks.injectedFrames.get() - injectedBefore
                assertEquals(EmulatorMediaHooks.Stimulus.MARKER, hooks.stimulus)
                val wordsWhileMuted = hooks.generatedWords.get() - wordsBefore
                // Three boundaries guarantee at least two entire words inside this window.
                assertTrue("Muted source must generate complete marker opportunities: words=$wordsWhileMuted", wordsWhileMuted >= 3)
                mark(Step.RESUMED)
                hooks.publicationMuted(false)
                fixture("/fixture/markers/$id", reset = true)
                val resumed = peerAudio()
                hooks.releaseCaptureLease()

                mark(Step.HOLD)
                engine.hold(id, true)
                hooks.beginPhase()
                withTimeout(3000) { while (hooks.decoded().tones < 5 || hooks.output().blocks < 10) delay(10) }
                val heldOutput = heldSilence(hooks)
                assertTrue(heldOutput.frames > 0); assertEquals(0L, heldOutput.nonzero)
                assertTrue(hooks.decoded().tones > 0)
                hooks.observeOutput(false)
                val stoppedTap = runCatching { heldSilence(hooks) }.exceptionOrNull()
                assertTrue(stoppedTap is MediaHookUnavailable)
                assertEquals(MediaHookFailure.OUTPUT_TAP_UNAVAILABLE, (stoppedTap as MediaHookUnavailable).reason)
                hooks.observeOutput(true)
                mark(Step.OUTPUT_RESUMED)
                engine.hold(id, false)
                hooks.beginPhase()
                withTimeout(3000) { while (hooks.output().tones < 5) delay(10) }
                assertFalse(signals.any { it.stage == NativeMediaStage.OUTPUT_OBSERVATION_UNAVAILABLE })
                InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                    putString("mediaHooks", "directArray=true,captureRate=${hooks.captureSampleRate},uplinkMarkers=${heard.getLong("markers")}," +
                        "mutedMarkers=${muted.getLong("markers")},injectedMutedFrames=$injectedWhileMuted,generatedMutedWords=$wordsWhileMuted," +
                        "resumedMarkers=${resumed.getLong("markers")},wrongAndZeroRejected=true," +
                        "heldOutputBlocks=${heldOutput.blocks},heldNonzero=${heldOutput.nonzero},stoppedTapRejected=true")
                })
                mark(Step.END)
                engine.report(id, mapOf("type" to "ended", "reason" to "remoteEnded"))
                withTimeout(7000) { while (present() || TelecomEvidence.calls().isNotEmpty() ||
                    fixture("/fixture/observations/$id").getInt("clients") != 0) delay(30) }
                stage(NativeMediaStage.CLOSED)
            } catch (error: TimeoutCancellationException) {
                throw AssertionError("step=$step, captureRate=${hooks.captureSampleRate}, injected=${hooks.injectedFrames.get()}, " +
                    "decoded=${runCatching { hooks.decoded() }.getOrNull()}, " +
                    "output=${runCatching { hooks.output() }.getOrNull()}, peer=$peer, " +
                    "stages=${signals.map { it.stage }}", error)
            } finally {
                withContext(NonCancellable) {
                    try {
                        withTimeout(7000) {
                            if (present()) engine.report(id, mapOf("type" to "ended", "reason" to "remoteEnded"))
                            while (present() || TelecomEvidence.calls().isNotEmpty()) delay(20)
                        }
                    } finally {
                        try { hooks.close() }
                        finally {
                            NativeMediaSignals.observers -= observer
                            if (previousFactory == null) NativeMediaRegistry.factories.remove("livekit")
                            else NativeMediaRegistry.factories["livekit"] = previousFactory
                            engine.nativeMedia = previousMedia; engine.policy = previousPolicy
                            engine.actions.resetBridge(); engine.actions.acceptUnhandled = previousAccept; engine.emit = previousEmitter
                        }
                    }
                }
            }
        }
    }
}
