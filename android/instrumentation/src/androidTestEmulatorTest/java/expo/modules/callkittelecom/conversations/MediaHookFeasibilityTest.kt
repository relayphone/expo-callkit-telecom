package expo.modules.callkittelecom.conversations

import android.Manifest
import android.media.AudioFormat
import android.media.AudioManager
import android.os.Bundle
import io.livekit.android.audio.AudioBufferCallback
import io.livekit.android.room.track.LocalAudioTrack
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@RunWith(AndroidJUnit4::class)
class MediaHookFeasibilityTest {
    @get:Rule val permissions = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val settings by lazy { JSONObject(InstrumentationRegistry.getInstrumentation().context.assets
        .open("media-ci.json").bufferedReader().readText()) }
    private val client = OkHttpClient.Builder().callTimeout(8, java.util.concurrent.TimeUnit.SECONDS).build()

    private suspend fun fixture(path: String, reset: Boolean = false, body: JSONObject? = null): JSONObject = suspendCancellableCoroutine { continuation ->
        val request = Request.Builder().url(settings.getString("baseUrl") + path)
            .header("Authorization", "Bearer " + settings.getString("authentication"))
        if (reset || body != null) request.post((body?.toString() ?: "{}").toRequestBody("application/json".toMediaType()))
        val call = client.newCall(request.build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }
            override fun onResponse(call: Call, response: Response) {
                val result = runCatching { response.use {
                    check(it.isSuccessful) { "Media fixture request failed" }
                    JSONObject(it.body!!.string())
                } }
                if (continuation.isActive) result.fold(continuation::resume, continuation::resumeWithException)
            }
        })
    }

    private fun healthy(peer: JSONObject, generation: Long) {
        assertTrue("A healthy subscribed receiver is required: $peer", peer.getBoolean("running"))
        assertTrue("The receiver failed: $peer", peer.isNull("error"))
        assertEquals("tones-v2", peer.getString("profile"))
        assertEquals(generation, peer.getLong("generation"))
    }

    private suspend fun peerAudio(id: String, generation: Long, within: Long = 5000) = withTimeout(within) {
        var peer: JSONObject
        do {
            delay(40); peer = fixture("/fixture/markers/$id")
            healthy(peer, generation)
        } while (peer.getLong("cleanCyclesWithPostRoll") < 1)
        peer
    }

    private suspend fun outputAudio(id: String, hooks: EmulatorMediaHooks, record: (JSONObject) -> Unit): JSONObject {
        val baseline = withTimeout(2000) { hooks.outputStats(baseline = true) }
        hooks.startOutputCapture()
        try {
            return withTimeout(5000) {
                fixture("/fixture/output/$id", body = JSONObject().put("reset", true).put("sampleRate", 48000)
                    .put("firstFrame", 0).put("pcm16", "").put("stats", baseline))
                var peer: JSONObject
                do {
                    delay(100)
                    val stats = hooks.outputStats()
                    val (first, bytes) = hooks.drainOutput()
                    peer = fixture("/fixture/output/$id", body = JSONObject().put("sampleRate", 48000)
                        .put("firstFrame", first).put("pcm16", Base64.getEncoder().encodeToString(bytes)).put("stats", stats))
                    assertTrue(peer.isNull("error")); assertTrue(peer.getBoolean("running"))
                    record(peer)
                } while (peer.getLong("cleanCyclesWithPostRoll") < 1)
                peer
            }
        } finally { hooks.stopOutputCapture() }
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

    @Test fun captureSamplesMatchMarkerAndEmissionVectors() {
        for ((rate, emission, expected) in listOf(
            Triple(8000, false, "7305be4e1d4ba73d2f2f876869fa5cb27c12bb91450081452139f41262f7c003"),
            Triple(48000, false, "82f79efb9f1b24a5c839cd500f5c9ad291ef5969348c3d68b775fcef6f8fa3b4"),
            Triple(8000, true, "e339576ee90e3d72ef65c7a5e44ab4a74345be472fdcd39b8f25072448a5fbab"),
            Triple(48000, true, "0a79ca1399b4fc018584bcf8e90be7c476618fab47f903b9670ce1282eafbaa1"))) {
            val hooks = EmulatorMediaHooks(context, "parity-v2", emission = emission)
            try {
                val callback = hooks.capture(object : AudioBufferCallback {
                    override fun onBuffer(buffer: ByteBuffer, format: Int, channels: Int, rate: Int,
                        bytesRead: Int, timestamp: Long) = timestamp
                })
                val size = rate * (if (emission) 1020 else 720) / 1000 * 2
                val pcm = ByteBuffer.allocateDirect(size)
                assertEquals(123L, callback.onBuffer(pcm, AudioFormat.ENCODING_PCM_16BIT, 1, rate, size, 123L))
                val bytes = ByteArray(size).also { pcm.duplicate().apply { clear() }.get(it) }
                // SDK markerSamples(createMarker("parity-v2")), PCM16 little-endian, Math.round(value * 32767).
                val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                assertEquals("tones-v2 at $rate Hz", expected, digest)
            } finally { hooks.close() }
        }
    }

    @Test fun realRecordinglessCapturePublicationGateDecodedAndPostMuteOutput() = journey(48000)

    private fun journey(rate: Int) = runBlocking {
        withContext(Dispatchers.Main) {
            val engine = ConversationEngine.get(context)
            val previousMedia = engine.nativeMedia; val previousPolicy = engine.policy
            val previousEmitter = engine.emit; val previousAccept = engine.actions.acceptUnhandled
            val previousFactory = NativeMediaRegistry.factories["livekit"]
            val id = InstrumentationRegistry.getArguments().getString("mediaTestId") ?: "hooks-${System.nanoTime()}"
            require(id.matches(Regex("hooks-[0-9]+")))
            val hooks = EmulatorMediaHooks(context, "$id:fromDevice", requestedRate = rate)
            val signals = CopyOnWriteArrayList<NativeMediaSignal>()
            val observer = NativeMediaObserver { if (it.serverCallId == id) signals += it }
            var step = "prejoin"
            var pipeline = "unobserved"
            var caught: Throwable? = null
            var apmMonitor: Job? = null
            val evidence = JSONObject().put("captureRate", rate).put("profile", "tones-v2")
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
                val track = publication.track as LocalAudioTrack
                val options = track.options
                val manager = context.getSystemService(AudioManager::class.java)
                apmMonitor = launch {
                    var samples = 0
                    val fields = setOf("audioLevel", "totalAudioEnergy", "totalSamplesDuration", "echoReturnLoss",
                        "echoReturnLossEnhancement", "totalSamplesCaptured", "totalCaptureDelay", "droppedSamplesDuration",
                        "droppedSamplesEvents", "concealedSamples", "silentConcealedSamples", "concealmentEvents",
                        "totalSamplesReceived", "packetsReceived", "packetsLost", "jitterBufferEmittedCount",
                        "jitterBufferDelay", "insertedSamplesForDeceleration", "removedSamplesForAcceleration",
                        "packetsSent", "bytesSent", "active")
                    while (isActive) {
                        val current = track.options
                        hooks.trace.state(manager.mode, current.echoCancellation, current.autoGainControl,
                            current.noiseSuppression, track.enabled, publication.muted)
                        if (samples++ % 5 == 0) hooks.trace.stats(track.getRTCStats()?.statsMap?.values
                            ?.filter { it.type in setOf("media-source", "inbound-rtp", "outbound-rtp") }
                            ?.map { mapOf("type" to it.type, "values" to it.members.filterKeys { key -> key in fields }) }
                            ?: emptyList())
                        delay(50)
                    }
                }
                assertFalse(options.noiseSuppression)
                assertFalse(options.echoCancellation)
                assertTrue(options.autoGainControl && options.highPassFilter)
                pipeline = "NS=${options.noiseSuppression},AGC=${options.autoGainControl}," +
                    "AEC=${options.echoCancellation},highPass=${options.highPassFilter}"
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
                assertEquals(rate, hooks.captureSampleRate)
                // Three completed boundaries guarantee at least two whole words inside the muted window.
                assertTrue("Muted source generated only $mutedWords boundaries", mutedWords >= 3)
                hooks.beginPhase()
                withTimeout(3000) { while (hooks.decoded().tones < 5 || hooks.output().blocks < 10) delay(10) }
                silenceWithLiveDecodedAudio(hooks)
                assertFalse(signals.any { it.stage == NativeMediaStage.CAPTURE_BEFORE_AUTHORITY ||
                    it.stage == NativeMediaStage.CAPTURE_STARTED || it.stage == NativeMediaStage.OUTPUT_BEFORE_AUTHORITY })
                peer = fixture("/fixture/markers/$id")
                healthy(peer, generation)
                assertTrue(peer.getBoolean("muted")); assertTrue(publication.muted)
                assertEquals("A muted publication must never leak a raw marker", 0L, peer.getLong("markers"))
                evidence.put("mutedPublication", peer)

                step = "answer"
                engine.join(id); stage(NativeMediaStage.CONNECTED)
                withTimeout(3000) {
                    var codec: Any?
                    do {
                        val stats = track.getRTCStats()?.statsMap
                        val outbound = stats?.values?.firstOrNull { it.type == "outbound-rtp" &&
                            (it.members["kind"] == "audio" || it.members["mediaType"] == "audio") }
                        codec = stats?.get(outbound?.members?.get("codecId"))?.members?.get("mimeType")
                        if (codec != "audio/opus") delay(30)
                    } while (codec != "audio/opus")
                }
                pipeline += ",codec=audio/opus"
                InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("pipeline", pipeline) })
                hooks.trace.phase = "answered"
                hooks.postApm.start()
                fixture("/fixture/markers/$id", reset = true)
                val answered = peerAudio(id, generation)
                evidence.put("fromDevice", answered)
                InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                    putString("tonesV2", answered.put("captureRate", hooks.captureSampleRate).toString())
                })
                assertSame(publication, hooks.publicationIdentity()); assertFalse(publication.muted)
                step = "answered output"
                outputAudio(id, hooks) { evidence.put("toDevice", it) }
                hooks.postApm.stop()
                hooks.beginPhase()
                withTimeout(3000) { while (hooks.decoded().tones < 5 || hooks.output().tones < 5) delay(10) }

                step = "source controls"
                for (stimulus in listOf(EmulatorMediaHooks.Stimulus.ZERO, EmulatorMediaHooks.Stimulus.WRONG)) {
                    hooks.trace.phase = stimulus.name
                    hooks.stimulus = stimulus
                    delay(300)
                    fixture("/fixture/markers/$id", reset = true)
                    val rejected = runCatching { peerAudio(id, generation, 3100) }.exceptionOrNull()
                    assertTrue("$stimulus must fail the far-end audio assertion", rejected is TimeoutCancellationException)
                    val rejectedPeer = fixture("/fixture/markers/$id")
                    healthy(rejectedPeer, generation)
                    assertEquals("$stimulus must not produce a raw marker, even with impaired transport", 0L, rejectedPeer.getLong("markers"))
                    evidence.put(stimulus.name, rejectedPeer)
                }
                hooks.stimulus = EmulatorMediaHooks.Stimulus.MARKER
                hooks.trace.phase = "restored"
                fixture("/fixture/markers/$id", reset = true)
                peerAudio(id, generation)

                step = "held output"
                hooks.trace.phase = "held"
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
                        "$pipeline," +
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
                runCatching { evidence.put("fromDevice", fixture("/fixture/markers/$id")) }
                InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                    putString("tonesV2Failure", runCatching { fixture("/fixture/markers/$id").put("captureRate", hooks.captureSampleRate).toString() }.getOrDefault("unavailable"))
                    putString("mediaHookFailure", "step=$step, error=$error, signals=${signals.map { it.stage }}")
                    putString("pipeline", pipeline)
                })
                throw AssertionError("step=$step, captureRate=${hooks.captureSampleRate}, " +
                    "injected=${hooks.injectedFrames.get()}, decoded=${runCatching { hooks.decoded() }.getOrNull()}, " +
                    "output=${runCatching { hooks.output() }.getOrNull()}", error)
            } finally {
                apmMonitor?.cancel()
                try {
                    context.filesDir.resolve("media-hook-results.json").writeText(evidence.put("step", step)
                        .put("passed", caught == null).put("pipeline", pipeline).toString())
                    context.filesDir.resolve("capture-trace.json").writeText(hooks.trace.snapshot()
                        .put("postApm", hooks.postApm.finish()).toString())
                    InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                        putString("captureTraceFile", "capture-trace.json")
                    })
                } catch (diagnostic: Throwable) {
                    val primary = caught
                    if (primary == null) { caught = diagnostic; throw diagnostic }
                    else primary.addSuppressed(diagnostic)
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
}
