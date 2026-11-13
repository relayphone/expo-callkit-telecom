package expo.modules.callkittelecom.conversations

import android.Manifest
import android.media.AudioFormat
import android.os.Bundle
import io.livekit.android.audio.AudioBufferCallback
import java.nio.ByteBuffer
import java.security.MessageDigest
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

    private fun healthy(peer: JSONObject, generation: Long) {
        assertTrue("A healthy subscribed receiver is required: $peer", peer.getBoolean("running"))
        assertTrue("The receiver failed: $peer", peer.isNull("error"))
        assertEquals(generation, peer.getLong("generation"))
    }

    private suspend fun peerAudio(id: String, generation: Long, within: Long = 5000) = withTimeout(within) {
        var peer: JSONObject
        do {
            delay(40); peer = fixture("/fixture/markers/$id")
            healthy(peer, generation)
        } while (peer.getLong("markers") < 2)
        peer
    }

    private suspend fun silenceWithLiveDecodedAudio(hooks: EmulatorMediaHooks): EmulatorMediaHooks.Observation {
        var output = hooks.output()
        var decoded = hooks.decoded()
        repeat(5) {
            delay(100)
            val nextOutput = hooks.output()
            val nextDecoded = hooks.decoded()
            if (nextOutput.blocks <= output.blocks || nextOutput.frames <= output.frames)
                throw MediaHookUnavailable(MediaHookFailure.OUTPUT_TAP_UNAVAILABLE)
            assertEquals(0L, nextOutput.nonzero)
            assertTrue("Decoded tone must remain live throughout output silence",
                nextDecoded.frames > decoded.frames && nextDecoded.tones > decoded.tones)
            output = nextOutput; decoded = nextDecoded
        }
        return output
    }

    @Test fun aDisabledOutputTapFailsInsteadOfReportingSilence() {
        val error = assertThrows(MediaHookUnavailable::class.java) { EmulatorMediaHooks(context, "disabled", tapEnabled = false) }
        assertEquals(MediaHookFailure.OUTPUT_TAP_UNAVAILABLE, error.reason)
    }

    @Test fun captureSamplesMatchTheSharedTonesV1Vector() {
        val hooks = EmulatorMediaHooks(context, "marker-parity:fromDevice")
        try {
            val callback = hooks.capture(object : AudioBufferCallback {
                override fun onBuffer(buffer: ByteBuffer, format: Int, channels: Int, rate: Int,
                    bytesRead: Int, timestamp: Long) = timestamp
            })
            val pcm = ByteBuffer.allocateDirect(6400)
            assertEquals(123L, callback.onBuffer(pcm, AudioFormat.ENCODING_PCM_16BIT, 1, 8000, 6400, 123L))
            val bytes = ByteArray(6400).also { pcm.duplicate().apply { clear() }.get(it) }
            // SDK PR293 markerSamples(createMarker(identity), 8000, 3200), Math.round(value * 32767).
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            assertEquals("440c129abb3f9e18876ceb15035c5d6f9718695bc00c01ff0a97dc9c588fa44c", digest)
        } finally { hooks.close() }
    }

    @Test fun realRecordinglessCapturePublicationGateDecodedAndPostMuteOutput() = runBlocking {
        withContext(Dispatchers.Main) {
            val engine = ConversationEngine.get(context)
            val previousMedia = engine.nativeMedia; val previousPolicy = engine.policy
            val previousEmitter = engine.emit; val previousAccept = engine.actions.acceptUnhandled
            val previousFactory = NativeMediaRegistry.factories["livekit"]
            val id = "hooks-${System.nanoTime()}"
            val hooks = EmulatorMediaHooks(context, "$id:fromDevice")
            val signals = CopyOnWriteArrayList<NativeMediaSignal>()
            val observer = NativeMediaObserver { if (it.serverCallId == id) signals += it }
            var step = "prejoin"
            var caught: Throwable? = null
            fun present() = (engine.snapshot["conversations"] as List<*>).any { (it as Map<*, *>)["id"] == id }
            suspend fun stage(kind: NativeMediaStage) = withTimeout(10_000) {
                while (signals.none { it.stage == kind }) delay(10)
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
                step = "muted publication"
                assertTrue(hooks.directArrayVerified)
                val publication = hooks.publicationIdentity()
                assertTrue(publication.muted)
                var peer = fixture("/fixture/markers/$id", reset = true)
                assertTrue("Muted prejoin requires a subscribed receiver: $peer", peer.getBoolean("running"))
                val generation = peer.getLong("generation")
                healthy(peer, generation)
                assertTrue(peer.getBoolean("muted"))
                val wordsBefore = hooks.generatedWords.get()
                var injected = hooks.injectedFrames.get()
                var recordingless = hooks.recordinglessCallbacks.get()
                repeat(31) {
                    delay(100)
                    val next = hooks.injectedFrames.get()
                    assertTrue("Recordingless source stopped", next > injected)
                    assertTrue("The recordingless pacing callback must stay live", hooks.recordinglessCallbacks.get() > recordingless)
                    injected = next; recordingless = hooks.recordinglessCallbacks.get()
                    peer = fixture("/fixture/markers/$id")
                    healthy(peer, generation)
                    assertTrue(peer.getBoolean("muted")); assertTrue(publication.muted)
                    assertEquals(0L, peer.getLong("markers"))
                }
                val mutedWords = hooks.generatedWords.get() - wordsBefore
                // Three completed boundaries guarantee at least two whole words inside the muted window.
                assertTrue("Muted source generated only $mutedWords boundaries", mutedWords >= 3)
                hooks.beginPhase()
                withTimeout(3000) { while (hooks.decoded().tones < 5 || hooks.output().blocks < 10) delay(10) }
                silenceWithLiveDecodedAudio(hooks)
                assertFalse(signals.any { it.stage == NativeMediaStage.CAPTURE_BEFORE_AUTHORITY ||
                    it.stage == NativeMediaStage.CAPTURE_STARTED || it.stage == NativeMediaStage.OUTPUT_BEFORE_AUTHORITY })

                step = "answer"
                engine.join(id); stage(NativeMediaStage.CONNECTED)
                fixture("/fixture/markers/$id", reset = true)
                val answered = peerAudio(id, generation)
                InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                    putString("tonesV1", answered.toString())
                })
                assertSame(publication, hooks.publicationIdentity()); assertFalse(publication.muted)
                hooks.beginPhase()
                withTimeout(3000) { while (hooks.decoded().tones < 5 || hooks.output().tones < 5) delay(10) }

                step = "source controls"
                for (stimulus in listOf(EmulatorMediaHooks.Stimulus.ZERO, EmulatorMediaHooks.Stimulus.WRONG)) {
                    hooks.stimulus = stimulus
                    delay(300)
                    fixture("/fixture/markers/$id", reset = true)
                    val rejected = runCatching { peerAudio(id, generation, 3100) }.exceptionOrNull()
                    assertTrue("$stimulus must fail the far-end audio assertion", rejected is TimeoutCancellationException)
                }
                hooks.stimulus = EmulatorMediaHooks.Stimulus.MARKER
                fixture("/fixture/markers/$id", reset = true)
                peerAudio(id, generation)

                step = "held output"
                engine.hold(id, true); hooks.beginPhase()
                withTimeout(3000) { while (hooks.decoded().tones < 5 || hooks.output().blocks < 10) delay(10) }
                val held = silenceWithLiveDecodedAudio(hooks)
                hooks.observeOutput(false)
                val stopped = runCatching { silenceWithLiveDecodedAudio(hooks) }.exceptionOrNull()
                assertTrue(stopped is MediaHookUnavailable)
                assertEquals(MediaHookFailure.OUTPUT_TAP_UNAVAILABLE, (stopped as MediaHookUnavailable).reason)
                hooks.observeOutput(true)
                engine.hold(id, false); hooks.beginPhase()
                withTimeout(3000) { while (hooks.output().tones < 5) delay(10) }
                assertFalse(signals.any { it.stage == NativeMediaStage.OUTPUT_OBSERVATION_UNAVAILABLE })
                InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                    putString("mediaHooks", "directArray=true,captureRate=${hooks.captureSampleRate}," +
                        "generatedMutedWords=$mutedWords,receivedMutedMarkers=0,answeredMarkers=${answered.getLong("markers")}," +
                        "sameReceiver=true,wrongAndZeroRejected=true,heldOutputBlocks=${held.blocks},heldNonzero=${held.nonzero},stoppedTapRejected=true")
                })
                step = "end"
                engine.report(id, mapOf("type" to "ended", "reason" to "remoteEnded"))
                withTimeout(7000) { while (present() || TelecomEvidence.calls().isNotEmpty() ||
                    fixture("/fixture/observations/$id").getInt("clients") != 0) delay(30) }
                stage(NativeMediaStage.CLOSED)
            } catch (error: Throwable) {
                caught = error
                InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                    putString("tonesV1Failure", runCatching { fixture("/fixture/markers/$id").toString() }.getOrDefault("unavailable"))
                    putString("mediaHookFailure", "step=$step, error=$error, signals=${signals.map { it.stage }}")
                })
                throw AssertionError("step=$step, captureRate=${hooks.captureSampleRate}, " +
                    "injected=${hooks.injectedFrames.get()}, decoded=${runCatching { hooks.decoded() }.getOrNull()}, " +
                    "output=${runCatching { hooks.output() }.getOrNull()}", error)
            } finally {
                withContext(NonCancellable) {
                    try {
                        try {
                            withTimeout(7000) {
                                if (present()) engine.report(id, mapOf("type" to "ended", "reason" to "remoteEnded"))
                                while (present() || TelecomEvidence.calls().isNotEmpty()) delay(20)
                            }
                        } catch (cleanup: Throwable) {
                            val primary = caught
                            if (primary != null) primary.addSuppressed(cleanup) else throw cleanup
                        }
                    } finally {
                        try { hooks.close() } finally {
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
